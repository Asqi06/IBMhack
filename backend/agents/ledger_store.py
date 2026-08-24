"""
ledger_store.py — storage backend for the hash-chained scam registry.

Two interchangeable stores behind one small interface, selected by the presence of
DATABASE_URL:

  DATABASE_URL set    -> PostgresStore  survives restarts. Required on free-tier
                                        hosts (Render/Code Engine), whose container
                                        disk is wiped on every deploy and every
                                        scale-to-zero wake-up — which would silently
                                        reset every scam report to zero.
  DATABASE_URL unset  -> SqliteStore    local dev; byte-for-byte the previous behaviour.

Both implement the identical chain:

    entry_hash = sha256(f"{key}|{category}|{timestamp}|{prev_hash}")

where prev_hash is the entry_hash of the globally-last row ("0" * 64 for the first).
Swapping stores therefore never changes a hash the app has already shown a user — it
only changes where rows live.

Concurrency: read-last-hash-then-insert must be atomic, or two simultaneous reports
share a prev_hash, fork the chain, and make verify_chain() cry tamper on honest data.
SQLite gets BEGIN IMMEDIATE; Postgres gets a transaction-scoped advisory lock.

Every method here is BLOCKING. Callers in async context must go through
asyncio.to_thread — a Postgres round-trip is real network latency, unlike the
local SQLite file this replaced.
"""
import hashlib
import os
import sqlite3
import threading
from pathlib import Path
from typing import Dict, List, Optional, Tuple

GENESIS_HASH = "0" * 64

# (key, category, timestamp, prev_hash, entry_hash)
Row = Tuple[str, str, str, str, str]


def hash_entry(key: str, category: str, timestamp: str, prev_hash: str) -> str:
    """The one definition of the chain hash. Both stores and verify_chain use this."""
    data = f"{key}|{category}|{timestamp}|{prev_hash}".encode("utf-8")
    return hashlib.sha256(data).hexdigest()


# --------------------------------------------------------------------------- #
# SQLite — local dev / no DATABASE_URL
# --------------------------------------------------------------------------- #
class SqliteStore:
    name = "sqlite"

    def __init__(self, db_path: str):
        self._raw_path = db_path
        self._lock = threading.Lock()
        self._ready = False

    @property
    def location(self) -> str:
        return str(self._path())

    def _path(self) -> Path:
        p = Path(self._raw_path)
        if not p.is_absolute():
            # Resolve relative to backend/, not the process cwd — uvicorn may be
            # started from either the repo root or backend/.
            p = Path(__file__).resolve().parents[1] / p
        return p

    def _connect(self) -> sqlite3.Connection:
        # isolation_level=None puts us in autocommit so we can issue BEGIN IMMEDIATE
        # explicitly; the default implicit-transaction mode would only take a write
        # lock at INSERT time, after the last-hash SELECT had already been read.
        conn = sqlite3.connect(str(self._path()), timeout=15.0, isolation_level=None)
        conn.row_factory = sqlite3.Row
        return conn

    def ensure(self) -> None:
        if self._ready:
            return
        with self._lock:
            if self._ready:
                return
            db = self._path()
            db.parent.mkdir(parents=True, exist_ok=True)
            conn = self._connect()
            try:
                conn.execute("PRAGMA journal_mode=WAL")
                conn.execute(
                    """
                    CREATE TABLE IF NOT EXISTS reports (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        key TEXT NOT NULL,
                        category TEXT NOT NULL,
                        timestamp TEXT NOT NULL,
                        prev_hash TEXT NOT NULL,
                        entry_hash TEXT NOT NULL
                    )
                    """
                )
                conn.execute("CREATE INDEX IF NOT EXISTS idx_reports_key ON reports(key)")
            finally:
                conn.close()
            self._ready = True

    def fetch_by_key(self, key: str) -> List[Dict]:
        self.ensure()
        conn = self._connect()
        try:
            cur = conn.execute(
                "SELECT category, timestamp FROM reports WHERE key = ? ORDER BY id ASC",
                (key,),
            )
            return [{"category": r["category"], "timestamp": r["timestamp"]} for r in cur.fetchall()]
        finally:
            conn.close()

    def append(self, key: str, category: str, timestamp: str) -> Dict:
        self.ensure()
        conn = self._connect()
        try:
            conn.execute("BEGIN IMMEDIATE")
            try:
                cur = conn.execute("SELECT entry_hash FROM reports ORDER BY id DESC LIMIT 1")
                row = cur.fetchone()
                prev_hash = row["entry_hash"] if row else GENESIS_HASH
                entry_hash = hash_entry(key, category, timestamp, prev_hash)
                conn.execute(
                    "INSERT INTO reports (key, category, timestamp, prev_hash, entry_hash)"
                    " VALUES (?, ?, ?, ?, ?)",
                    (key, category, timestamp, prev_hash, entry_hash),
                )
                cur = conn.execute("SELECT COUNT(*) AS c FROM reports WHERE key = ?", (key,))
                count = cur.fetchone()["c"]
                conn.execute("COMMIT")
            except Exception:
                conn.execute("ROLLBACK")
                raise
            return {"prevHash": prev_hash, "entryHash": entry_hash, "reportCount": count}
        finally:
            conn.close()

    def all_rows(self) -> List[Row]:
        self.ensure()
        conn = self._connect()
        try:
            cur = conn.execute(
                "SELECT key, category, timestamp, prev_hash, entry_hash FROM reports ORDER BY id ASC"
            )
            return [
                (r["key"], r["category"], r["timestamp"], r["prev_hash"], r["entry_hash"])
                for r in cur.fetchall()
            ]
        finally:
            conn.close()

    def count_all(self) -> int:
        self.ensure()
        conn = self._connect()
        try:
            return conn.execute("SELECT COUNT(*) AS c FROM reports").fetchone()["c"]
        finally:
            conn.close()


# --------------------------------------------------------------------------- #
# Postgres — cloud (Neon / Supabase / Render Postgres / IBM Databases)
# --------------------------------------------------------------------------- #
# "timestamp" is a type name in Postgres, so it is quoted everywhere below. "key" is
# non-reserved but quoted alongside it for symmetry.
_PG_CREATE = """
CREATE TABLE IF NOT EXISTS reports (
    id BIGSERIAL PRIMARY KEY,
    "key" TEXT NOT NULL,
    category TEXT NOT NULL,
    "timestamp" TEXT NOT NULL,
    prev_hash TEXT NOT NULL,
    entry_hash TEXT NOT NULL
)
"""
_PG_INDEX = 'CREATE INDEX IF NOT EXISTS idx_reports_key ON reports("key")'

# Any 64-bit constant works; it just has to be the same in every process that appends.
_APPEND_LOCK_ID = 0x5CA5_1E1D


class PostgresStore:
    name = "postgres"

    def __init__(self, dsn: str):
        self._dsn = _normalize_pg_dsn(dsn)
        self._pool = None
        self._lock = threading.Lock()
        self._ready = False

    @property
    def location(self) -> str:
        return _redact_dsn(self._dsn)

    def _get_pool(self):
        if self._pool is not None:
            return self._pool
        with self._lock:
            if self._pool is None:
                from psycopg_pool import ConnectionPool

                # min_size=1 keeps one warm connection so the first scan after a
                # cold start does not pay TLS + auth on top of the container boot.
                self._pool = ConnectionPool(
                    self._dsn, min_size=1, max_size=5, timeout=15.0, open=True
                )
            return self._pool

    def ensure(self) -> None:
        if self._ready:
            return
        with self._get_pool().connection() as conn:
            with conn.cursor() as cur:
                cur.execute(_PG_CREATE)
                cur.execute(_PG_INDEX)
        self._ready = True

    def fetch_by_key(self, key: str) -> List[Dict]:
        self.ensure()
        with self._get_pool().connection() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    'SELECT category, "timestamp" FROM reports WHERE "key" = %s ORDER BY id ASC',
                    (key,),
                )
                return [{"category": r[0], "timestamp": r[1]} for r in cur.fetchall()]

    def append(self, key: str, category: str, timestamp: str) -> Dict:
        self.ensure()
        with self._get_pool().connection() as conn:
            # psycopg's context manager wraps this in one transaction, so the
            # advisory lock is held until commit — exactly the window we need to
            # protect (last-hash read through insert).
            with conn.cursor() as cur:
                cur.execute("SELECT pg_advisory_xact_lock(%s)", (_APPEND_LOCK_ID,))
                cur.execute("SELECT entry_hash FROM reports ORDER BY id DESC LIMIT 1")
                row = cur.fetchone()
                prev_hash = row[0] if row else GENESIS_HASH
                entry_hash = hash_entry(key, category, timestamp, prev_hash)
                cur.execute(
                    'INSERT INTO reports ("key", category, "timestamp", prev_hash, entry_hash)'
                    " VALUES (%s, %s, %s, %s, %s)",
                    (key, category, timestamp, prev_hash, entry_hash),
                )
                cur.execute('SELECT COUNT(*) FROM reports WHERE "key" = %s', (key,))
                count = cur.fetchone()[0]
        return {"prevHash": prev_hash, "entryHash": entry_hash, "reportCount": int(count)}

    def all_rows(self) -> List[Row]:
        self.ensure()
        with self._get_pool().connection() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    'SELECT "key", category, "timestamp", prev_hash, entry_hash'
                    " FROM reports ORDER BY id ASC"
                )
                return [tuple(r) for r in cur.fetchall()]  # type: ignore[misc]

    def count_all(self) -> int:
        self.ensure()
        with self._get_pool().connection() as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT COUNT(*) FROM reports")
                return int(cur.fetchone()[0])


def _normalize_pg_dsn(dsn: str) -> str:
    """
    Managed Postgres (Neon, Supabase, Render) is TLS-only, but not every provider's
    copy-paste string carries sslmode. Add it rather than let the first connection
    fail with a confusing 'server does not support SSL' at boot.
    """
    dsn = dsn.strip()
    if "sslmode=" in dsn:
        return dsn
    sep = "&" if "?" in dsn else "?"
    return f"{dsn}{sep}sslmode=require"


def _redact_dsn(dsn: str) -> str:
    """host/db only — this value is returned by /health, which is public."""
    try:
        from urllib.parse import urlsplit

        parts = urlsplit(dsn)
        host = parts.hostname or "?"
        db = (parts.path or "/?").lstrip("/") or "?"
        return f"postgres://{host}/{db}"
    except Exception:
        return "postgres://(unparsed)"


# --------------------------------------------------------------------------- #
# Selection
# --------------------------------------------------------------------------- #
_store: Optional[object] = None
_store_lock = threading.Lock()


def get_store():
    """
    The active store. Cached — building a PostgresStore opens a pool.

    Read env lazily rather than at import: main.py calls load_dotenv() and Code
    Engine/Render inject env after the module graph is already importable, so a
    module-level snapshot can miss DATABASE_URL entirely.
    """
    global _store
    if _store is not None:
        return _store
    with _store_lock:
        if _store is None:
            dsn = (os.getenv("DATABASE_URL") or "").strip()
            if dsn:
                _store = PostgresStore(dsn)
            else:
                _store = SqliteStore(os.getenv("FALLBACK_DB_PATH", "./ledger.db"))
        return _store


def store_info() -> Dict:
    """Non-secret description of the active store, for /health."""
    s = get_store()
    info = {"store": s.name, "location": s.location}
    try:
        info["entries"] = s.count_all()
    except Exception as e:
        info["entries"] = -1
        info["error"] = str(e)[:200]
    return info
