"""
main.py — FastAPI app exposing ScamShield endpoints (Section 4).

POST /analyze — {text}
POST /report  — {numberOrUrl, category}
GET  /check/{numberOrUrl}
GET  /health  — drives the app's "Connected" chip: store, entry count, Granite mode
GET  /warmup  — cheap ping so a scaled-to-zero cloud instance is awake before the
                first scan (Render free / Code Engine min-scale 0 both sleep)
GET  /logs    — recent request log, in-memory ring buffer (demo + debugging)
GET  /ledger/verify (demo helper)
POST /scan-url (helper for Chrome extension — single URL check)
"""
import logging
import os
import sys
import time
import uuid
from collections import deque
from contextlib import asynccontextmanager
from typing import Deque, Dict, Optional

from fastapi import FastAPI, HTTPException, Request
from fastapi import Path as FastPath
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from dotenv import load_dotenv
from pathlib import Path as PathLib

import http_client
import orchestrator
from agents.ledger_agent import check_number_reputation, report_scam, verify_chain_async
from agents.ledger_store import store_info
from agents.watsonx_client import mode_info as granite_mode_info

load_dotenv(dotenv_path=PathLib(__file__).resolve().parent / ".env")
load_dotenv()

PORT = int(os.getenv("PORT", "8000"))
CORS_ORIGINS = os.getenv("CORS_ORIGINS", "*")
LEDGER_MODE = os.getenv("LEDGER_MODE", "fallback")
MOCK_GRANITE = os.getenv("MOCK_GRANITE", "false").lower() in ("1", "true", "yes")
VERSION = "1.1.0"

# stdout, unbuffered (see Dockerfile PYTHONUNBUFFERED) so Render / Code Engine log
# viewers show these live rather than in delayed 4 KB chunks.
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-5s %(message)s",
    datefmt="%H:%M:%S",
    stream=sys.stdout,
)
log = logging.getLogger("scamshield")

_STARTED_AT = time.time()

# Recent requests, newest last. Bounded so a long-running instance cannot grow this
# without limit — it is a demo/debug aid, not an audit trail (the ledger is that).
_LOG_CAPACITY = 200
_recent: Deque[Dict] = deque(maxlen=_LOG_CAPACITY)


@asynccontextmanager
async def lifespan(app: FastAPI):
    mode = granite_mode_info()
    store = store_info()
    log.info(
        "ScamShield %s up — granite=%s model=%s ledger=%s/%s entries=%s",
        VERSION, mode["granite"], mode["model"], LEDGER_MODE, store["store"], store["entries"],
    )
    if mode["granite"] == "mock":
        log.warning("Granite is MOCKED (%s) — verdicts are heuristic, not real AI", mode.get("reason"))
    yield
    await http_client.aclose()
    log.info("ScamShield shutting down")


app = FastAPI(
    title="ScamShield Backend",
    description="AI + Blockchain scam detection — IBM Granite + Hyperledger Fabric (fallback: hash-chain)",
    version=VERSION,
    lifespan=lifespan,
)

# CORS — open for dev, also allows Chrome extension
if CORS_ORIGINS == "*":
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
else:
    origins = [o.strip() for o in CORS_ORIGINS.split(",") if o.strip()]
    app.add_middleware(
        CORSMiddleware,
        allow_origins=origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )


@app.middleware("http")
async def request_logger(request: Request, call_next):
    """
    One line per request: id, method, path, status, duration. Without this, a failing
    scan on a deployed instance is invisible — the app just shows a timeout and the
    platform log shows nothing but uvicorn's access line.
    """
    rid = uuid.uuid4().hex[:8]
    started = time.perf_counter()
    status = 500
    try:
        response = await call_next(request)
        status = response.status_code
        response.headers["X-Request-Id"] = rid
        return response
    except Exception as e:
        log.exception("[%s] %s %s -> unhandled %s", rid, request.method, request.url.path, e)
        raise
    finally:
        ms = int((time.perf_counter() - started) * 1000)
        # /logs itself is excluded, or polling it from the app fills the buffer with
        # nothing but reads of the buffer.
        if request.url.path != "/logs":
            _recent.append({
                "id": rid,
                "ts": time.time(),
                "method": request.method,
                "path": request.url.path,
                "status": status,
                "durationMs": ms,
            })
            log.info("[%s] %s %s -> %s in %dms", rid, request.method, request.url.path, status, ms)


class AnalyzeRequest(BaseModel):
    text: str = Field(..., description="Extracted text from OCR or user input", examples=["Your account will be blocked, share OTP now"])


class ReportRequest(BaseModel):
    numberOrUrl: str = Field(..., examples=["+919876543210"])
    category: str = Field(..., examples=["OTP scam", "fake refund/KYC", "phishing link", "impersonation"])


class ScanUrlRequest(BaseModel):
    url: str = Field(..., examples=["https://arnaz0n-kyc.in/login"])


@app.get("/")
async def root():
    return {
        "service": "ScamShield",
        "version": VERSION,
        "ledgerMode": LEDGER_MODE,
        "mockGranite": MOCK_GRANITE,
        "endpoints": ["/analyze", "/report", "/check/{numberOrUrl}", "/health", "/warmup", "/logs", "/scan-url", "/ledger/verify"],
    }


@app.get("/health")
async def health():
    """
    Everything the app's status chip needs in one round-trip. Kept cheap: store_info()
    is a single COUNT(*), and the Granite mode check is local (no watsonx call), so a
    platform health probe hitting this every 30s costs nothing.
    """
    mode = granite_mode_info()
    store = store_info()
    return {
        "status": "ok",
        "version": VERSION,
        "uptimeSeconds": int(time.time() - _STARTED_AT),
        # legacy fields — kept so older app/extension builds keep working
        "ledgerMode": LEDGER_MODE,
        "mockGranite": MOCK_GRANITE,
        # what actually serves requests
        "granite": mode["granite"],
        "model": mode["model"],
        "graniteNote": mode.get("reason"),
        "ledgerStore": store["store"],
        "ledgerLocation": store["location"],
        "entries": store["entries"],
    }


@app.get("/warmup")
async def warmup():
    """
    The app calls this on launch. Free-tier hosts sleep an idle instance, and the
    cold start (container boot + import + first pool connection) can outlast the
    phone's request timeout — which looks to the user like "backend is down" on the
    very first scan. Paying that cost here, in the background, avoids it.
    """
    return {"awake": True, "uptimeSeconds": int(time.time() - _STARTED_AT)}


@app.get("/logs")
async def logs(limit: int = 50):
    """Recent requests, newest first. In-memory only — resets when the instance restarts."""
    limit = max(1, min(limit, _LOG_CAPACITY))
    items = list(_recent)[-limit:]
    items.reverse()
    return {"count": len(items), "capacity": _LOG_CAPACITY, "logs": items}


@app.post("/analyze")
async def analyze(req: AnalyzeRequest):
    """
    Runs scam_text_agent always; url_risk_agent if URL detected; ledger_agent if phone detected.
    Body: {"text": "..."} -> {overallRisk, details[], meta{timings, mode}}
    """
    if not req.text or not req.text.strip():
        raise HTTPException(status_code=400, detail="text is required and cannot be empty")
    result = await orchestrator.analyze(req.text)
    m = result.get("meta", {})
    log.info(
        "analyze: chars=%s risk=%s urls=%s phones=%s timings=%s",
        m.get("textLength"), result.get("overallRisk"),
        len(m.get("detectedUrls", [])), len(m.get("detectedPhones", [])), m.get("timings"),
    )
    return result


@app.post("/report")
async def report(req: ReportRequest):
    """
    Writes a report to the blockchain ledger via ledger_agent.
    No PII stored — only numberOrUrl + category + timestamp.
    """
    key = req.numberOrUrl.strip()
    if not key:
        raise HTTPException(status_code=400, detail="numberOrUrl is required")
    cat = req.category.strip() or "none"
    # Minimal allowlist warning — still store whatever category, but normalize common ones
    result = await report_scam(key, cat)
    if not result.get("success"):
        raise HTTPException(status_code=500, detail=result.get("error", "report failed"))
    log.info("report: key=%s category=%s count=%s store=%s",
             result.get("key"), cat, result.get("reportCount"), result.get("store"))
    return result


@app.get("/check/{numberOrUrl:path}")
async def check(numberOrUrl: str = FastPath(..., description="Phone number or URL to look up")):
    if not numberOrUrl or not numberOrUrl.strip():
        raise HTTPException(status_code=400, detail="numberOrUrl path param required")
    result = await check_number_reputation(numberOrUrl)
    return result


@app.post("/scan-url")
async def scan_url(req: ScanUrlRequest):
    """
    Helper for Chrome extension: scans a single URL without needing orchestrator text parsing.
    Calls url_risk_agent + ledger check and returns combined verdict.
    """
    from agents.url_risk_agent import check_url as check_single_url

    if not req.url or not req.url.strip():
        raise HTTPException(status_code=400, detail="url is required")

    url_result = await check_single_url(req.url)
    ledger_result = await check_number_reputation(req.url)
    # overall risk is max of url risk and ledger risk
    risks = [url_result.get("risk", "low"), ledger_result.get("riskLevel", "low")]
    order = {"high": 3, "medium": 2, "low": 1, "unknown": 0}
    overall = max(risks, key=lambda r: order.get(r, 0))
    if overall == "unknown":
        overall = "low"
    return {
        "url": req.url,
        "urlRisk": url_result,
        "ledger": ledger_result,
        "overallRisk": overall,
    }


@app.get("/ledger/verify")
async def ledger_verify():
    """
    Demo helper: verifies hash-chain integrity (fallback mode) or fabric connectivity.
    """
    if LEDGER_MODE == "fallback":
        return await verify_chain_async()
    # fabric: try a check on a dummy key to test connectivity
    try:
        res = await check_number_reputation("__health__")
        return {"valid": True, "mode": "fabric", "probe": res}
    except Exception as e:
        return {"valid": False, "mode": "fabric", "error": str(e)}
