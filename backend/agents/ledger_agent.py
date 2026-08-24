"""
ledger_agent.py — tamper-proof scam registry client.

Two functions with identical signatures (Section 7), kept stable so Fabric can be
swapped for the hash-chain fallback without changing callers:

  check_number_reputation(numberOrUrl: str) -> dict
  report_scam(numberOrUrl: str, category: str) -> dict

Modes (env LEDGER_MODE):
  fabric   -> calls real Hyperledger Fabric gateway REST (POST /report, GET /check/:key)
  fallback -> hash-chained append-only log, tamper-detectable

No PII is stored: only {numberOrUrl, category, timestamp, hashes}.

Fallback storage lives in agents/ledger_store.py and is chosen by DATABASE_URL:
Postgres when set (cloud — the container disk is ephemeral, so SQLite there would
silently reset every report on redeploy), SQLite when unset (local dev). The chain
itself is identical either way:

  entry_hash = sha256(f"{key}|{category}|{timestamp}|{prev_hash}")

check_number_reputation returns:
  {"reported": bool, "reportCount": int, "riskLevel": "high"|"medium"|"low",
   "mode": "fabric"|"fallback", "store": "postgres"|"sqlite", "entries": [...]}
"""
import asyncio
import os
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List

import httpx
from dotenv import load_dotenv

from agents.ledger_store import GENESIS_HASH, get_store, hash_entry

load_dotenv(dotenv_path=Path(__file__).resolve().parents[1] / ".env")
load_dotenv()

LEDGER_MODE = os.getenv("LEDGER_MODE", "fallback").lower().strip()
FABRIC_GATEWAY_URL = os.getenv("FABRIC_GATEWAY_URL", "http://localhost:3000").rstrip("/")


# Normalize key from input (phone/URL): strip spaces, lowercase urls
# For phones, normalize to 10-digit core so +919876543210 and 9876543210 map to same key.
def _normalize_key(raw: str) -> str:
    k = raw.strip()
    lower = k.lower().strip()
    # URL detection: scheme, www, or domain with letters and dot
    if re.match(r"^https?://", k, re.I) or lower.startswith("www."):
        return lower
    # Heuristic domain like "arnaz0n-kyc.in" — contains dot and letters, no spaces => treat as URL
    if "." in k and re.search(r"[a-zA-Z]", k) and " " not in k and re.match(r"^[a-zA-Z0-9\-\.]+\.[a-z]{2,}.*", k):
        # but ensure not a pure phone with dots — phones with dots are formatted like 987.654.3210 but have digits
        digits_only = re.sub(r"\D", "", k)
        if len(digits_only) < 7:  # not a phone, it's a domain
            return lower
        # if mixed, prefer domain lowercased
        if re.search(r"[a-zA-Z]", k):
            return lower
    # Phone path: extract digits, normalize to last 10 digits for Indian numbers
    digits = re.sub(r"\D", "", k)
    if len(digits) >= 10:
        # Indian: 10 digits local; 12 with 91 prefix; 11 with leading 0
        if len(digits) > 10:
            if digits.startswith("91") and len(digits) == 12:
                return digits[-10:]
            if digits.startswith("0") and len(digits) == 11:
                return digits[-10:]
            # generic: take last 10
            return digits[-10:]
        return digits
    # fallback: remove spaces/dashes/parens
    return re.sub(r"[\s\-()]", "", k)


def _risk_level_from_count(count: int) -> str:
    if count > 2:
        return "high"
    if count > 0:
        return "medium"
    return "low"


def _fallback_note(store_name: str) -> str:
    where = "Postgres" if store_name == "postgres" else "SQLite"
    return (
        f"FALLBACK MODE — hash-chained {where}, not Fabric consensus. "
        "Tamper-detectable via the entryHash chain (GET /ledger/verify)."
    )


# ---------- Fallback (hash-chain) implementation ----------
# These are the blocking store calls. Public entry points wrap them in
# asyncio.to_thread, because with DATABASE_URL set each one is a network
# round-trip and would otherwise stall the whole event loop mid-scan.
def _fallback_check_sync(key: str) -> Dict:
    store = get_store()
    norm = _normalize_key(key)
    entries = store.fetch_by_key(norm)
    count = len(entries)
    return {
        "reported": count > 0,
        "reportCount": count,
        "riskLevel": _risk_level_from_count(count),
        "mode": "fallback",
        "store": store.name,
        # Per-key we only report count; global integrity is exposed via /ledger/verify.
        "tampered": False,
        # entries included for demo/debugging — no PII beyond key/category/timestamp
        "entries": entries,
    }


def _fallback_report_sync(key: str, category: str) -> Dict:
    store = get_store()
    norm = _normalize_key(key)
    cat = category.strip() or "none"
    ts = datetime.now(timezone.utc).isoformat()
    written = store.append(norm, cat, ts)
    return {
        "success": True,
        "key": norm,
        "category": cat,
        "timestamp": ts,
        "reportCount": written["reportCount"],
        "entryHash": written["entryHash"],
        "prevHash": written["prevHash"],
        "mode": "fallback",
        "store": store.name,
        "note": _fallback_note(store.name),
    }


async def _fallback_check(key: str) -> Dict:
    return await asyncio.to_thread(_fallback_check_sync, key)


async def _fallback_report(key: str, category: str) -> Dict:
    return await asyncio.to_thread(_fallback_report_sync, key, category)


# ---------- Fabric gateway implementation ----------
async def _fabric_check(key: str) -> Dict:
    import urllib.parse

    norm = _normalize_key(key)
    url = f"{FABRIC_GATEWAY_URL}/check/{urllib.parse.quote(norm, safe='')}"
    try:
        async with httpx.AsyncClient() as client:
            resp = await client.get(url, timeout=10.0)
            if resp.status_code == 404:
                return {"reported": False, "reportCount": 0, "riskLevel": "low", "mode": "fabric", "entries": []}
            resp.raise_for_status()
            data = resp.json()
            # Gateway is expected to return {reported, reportCount, entries?} or list
            if isinstance(data, list):
                count = len(data)
                return {"reported": count > 0, "reportCount": count, "riskLevel": _risk_level_from_count(count), "mode": "fabric", "entries": data}
            if isinstance(data, dict):
                # normalize fields
                count = int(data.get("reportCount") or data.get("count") or len(data.get("entries", [])) or (1 if data.get("reported") else 0))
                return {
                    "reported": bool(data.get("reported", count > 0)),
                    "reportCount": count,
                    "riskLevel": data.get("riskLevel") or _risk_level_from_count(count),
                    "mode": "fabric",
                    "entries": data.get("entries", []),
                    "raw": data,
                }
            return {"reported": False, "reportCount": 0, "riskLevel": "low", "mode": "fabric"}
    except httpx.HTTPStatusError as e:
        if e.response.status_code == 404:
            return {"reported": False, "reportCount": 0, "riskLevel": "low", "mode": "fabric"}
        print(f"[ledger_agent] fabric check failed {e}, falling back to local hash-chain read")
        # For resilience during demo, fall back to local check so UI still works
        fb = await _fallback_check(norm)
        fb["fabric_error"] = str(e)
        return fb
    except Exception as e:
        print(f"[ledger_agent] fabric check error {e}, using fallback")
        fb = await _fallback_check(norm)
        fb["fabric_error"] = str(e)
        return fb


async def _fabric_report(key: str, category: str) -> Dict:
    norm = _normalize_key(key)
    cat = category.strip() or "none"
    ts = datetime.now(timezone.utc).isoformat()
    body = {"numberOrUrl": norm, "category": cat, "timestamp": ts}
    # Some fabric gateways expect {"key":..., "category":...}
    try:
        async with httpx.AsyncClient() as client:
            resp = await client.post(f"{FABRIC_GATEWAY_URL}/report", json=body, timeout=10.0)
            # retry with alternate field names if 400
            if resp.status_code == 400:
                alt = {"key": norm, "category": cat, "timestamp": ts}
                resp = await client.post(f"{FABRIC_GATEWAY_URL}/report", json=alt, timeout=10.0)
            resp.raise_for_status()
            data = resp.json() if resp.headers.get("content-type", "").startswith("application/json") else {"raw": resp.text}
            # also write to the hash-chain for local demo continuity
            try:
                await _fallback_report(norm, cat)
            except Exception:
                pass
            return {"success": True, "key": norm, "category": cat, "timestamp": ts, "mode": "fabric", "gatewayResponse": data}
    except Exception as e:
        print(f"[ledger_agent] fabric report failed {e}, writing to hash-chain fallback")
        # Ensure report is not lost during demo
        fb = await _fallback_report(norm, cat)
        fb["fabric_error"] = str(e)
        return fb


# ---------- Public API (identical signatures for both modes) ----------
async def check_number_reputation(numberOrUrl: str) -> Dict:
    """
    Returns {"reported": bool, "reportCount": int,
             "riskLevel": "high" if >2 else "medium" if >0 else "low"}.
    """
    if not numberOrUrl or not numberOrUrl.strip():
        return {"reported": False, "reportCount": 0, "riskLevel": "low", "mode": LEDGER_MODE}

    if LEDGER_MODE == "fabric":
        return await _fabric_check(numberOrUrl)
    return await _fallback_check(numberOrUrl)


async def report_scam(numberOrUrl: str, category: str) -> Dict:
    """
    Writes a report to the ledger, returns confirmation.
    """
    if not numberOrUrl or not numberOrUrl.strip():
        return {"success": False, "error": "empty numberOrUrl"}

    if LEDGER_MODE == "fabric":
        return await _fabric_report(numberOrUrl, category)
    return await _fallback_report(numberOrUrl, category)


def verify_chain() -> Dict:
    """
    Utility for judges/demo: recomputes the whole chain and reports the first row
    that does not match. Blocking — prefer verify_chain_async() from async code.

    Returns {"valid": bool, "entries": int, "tampered_at": int|None, "store": str}
    """
    store = get_store()
    try:
        rows = store.all_rows()
    except Exception as e:
        return {"valid": False, "entries": 0, "tampered_at": None, "store": store.name, "error": str(e)[:300]}

    prev = GENESIS_HASH
    for idx, (key, cat, ts, prev_h, entry_h) in enumerate(rows):
        expected = hash_entry(key, cat, ts, prev)
        if prev_h != prev or entry_h != expected:
            return {"valid": False, "entries": len(rows), "tampered_at": idx, "store": store.name}
        prev = entry_h
    return {
        "valid": True,
        "entries": len(rows),
        "tampered_at": None,
        "store": store.name,
        "headHash": prev,
    }


async def verify_chain_async() -> Dict:
    """verify_chain() off the event loop — it walks every row, over the network on Postgres."""
    return await asyncio.to_thread(verify_chain)
