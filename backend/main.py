"""
main.py — FastAPI app exposing ScamShield endpoints (Section 4).
Extended for v1.2: live-call shield, QR/UPI, multilingual.

POST /analyze — {text}
POST /analyze-call — {text, callerId?, chunkId?, isLive?} live-call streaming (wraps /analyze)
POST /transcribe — multipart audio upload -> transcript -> analyze (Whisper/fallback)
POST /scan-qr — {qrData} QR/UPI string
POST /report  — {numberOrUrl, category}
GET  /check/{numberOrUrl}
GET  /health  — drives the app's "Connected" chip: store, entry count, Granite mode
GET  /warmup
GET  /logs
GET  /ledger/verify
POST /scan-url
"""
import logging
import os
import sys
import time
import uuid
import re
from collections import deque
from contextlib import asynccontextmanager
from typing import Deque, Dict, Optional

from fastapi import FastAPI, HTTPException, Request, UploadFile, File, Form
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
VERSION = "1.2.0"

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


class AnalyzeCallRequest(BaseModel):
    text: str = Field(..., description="Live call transcript chunk", examples=["aapka account band ho jayega, OTP batao"])
    callerId: Optional[str] = Field(None, description="Caller phone number if available")
    chunkId: Optional[int] = Field(None, description="Monotonic chunk id for streaming")
    isLive: bool = Field(True, description="True if this is a live streaming chunk")


class ScanQrRequest(BaseModel):
    qrData: str = Field(..., description="Decoded QR string (url, upi://, or text)", examples=["upi://pay?pa=scammer@upi&pn=SCAM"])


@app.get("/")
async def root():
    return {
        "service": "ScamShield",
        "version": VERSION,
        "ledgerMode": LEDGER_MODE,
        "mockGranite": MOCK_GRANITE,
        "endpoints": ["/analyze", "/analyze-call", "/transcribe", "/scan-qr", "/scan-url", "/report", "/check/{numberOrUrl}", "/health", "/warmup", "/logs", "/ledger/verify"],
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


@app.post("/analyze-call")
async def analyze_call(req: AnalyzeCallRequest):
    """
    Live-call streaming endpoint. Same orchestrator as /analyze but with caller context.
    Frontend chunks 4s transcripts -> this endpoint -> immediate risk + advisory.
    Sliding-window: callerId is echoed and also checked against ledger.
    """
    if not req.text or not req.text.strip():
        raise HTTPException(status_code=400, detail="text is required")
    # If callerId known, prepend to text for ledger correlation but analyze the transcript
    result = await orchestrator.analyze(req.text)
    # If callerId supplied, also attach ledger reputation for that number
    if req.callerId and req.callerId.strip():
        ledger = await check_number_reputation(req.callerId.strip())
        result.setdefault("details", []).append({
            "source": "callerId",
            "number": req.callerId.strip(),
            "reported": ledger.get("reported", False),
            "reportCount": ledger.get("reportCount", 0),
            "riskLevel": ledger.get("riskLevel", "low"),
            "mode": ledger.get("mode", "fallback"),
        })
        # callerId high risk upgrades overall
        if ledger.get("riskLevel") == "high" and result.get("overallRisk") != "high":
            # keep max logic: ledger high dominates if transcript was medium/low
            order = {"high": 3, "medium": 2, "low": 1, "unknown": 0}
            cur = order.get(result.get("overallRisk", "low"), 0)
            if order["high"] > cur:
                result["overallRisk"] = "high"
    result["callMeta"] = {"callerId": req.callerId, "chunkId": req.chunkId, "isLive": req.isLive}
    m = result.get("meta", {})
    log.info("analyze-call: caller=%s chunk=%s risk=%s chars=%s", req.callerId, req.chunkId, result.get("overallRisk"), m.get("textLength"))
    return result


@app.post("/transcribe")
async def transcribe(audio: UploadFile = File(...), language: Optional[str] = Form(None)):
    """
    Audio upload -> transcript -> analyze. Tries faster-whisper if installed, else returns
    error directing caller to send transcript text via /analyze-call. This keeps the endpoint
    usable even without heavy ML deps on Code Engine free tier.
    """
    data = await audio.read()
    if not data or len(data) < 100:
        raise HTTPException(status_code=400, detail="empty audio")
    # Try whisper if available
    transcript = None
    try:
        import tempfile, os
        # lazy import so missing dep doesn't break app startup
        try:
            from faster_whisper import WhisperModel  # type: ignore
            has_fw = True
        except ImportError:
            try:
                import whisper  # openai-whisper
                has_fw = False
                has_whisper = True
            except ImportError:
                has_whisper = False
                has_fw = False
            else:
                has_whisper = True
        if has_fw:
            with tempfile.NamedTemporaryFile(delete=False, suffix="-" + (audio.filename or "audio.wav")) as tf:
                tf.write(data)
                tf.flush()
                tmp = tf.name
            try:
                model = WhisperModel("tiny", device="cpu", compute_type="int8")
                segments, _ = model.transcribe(tmp, language=language)
                transcript = " ".join(s.text for s in segments).strip()
            finally:
                try: os.remove(tmp)
                except: pass
        elif has_whisper:
            import whisper
            with tempfile.NamedTemporaryFile(delete=False, suffix=".wav") as tf:
                tf.write(data)
                tf.flush()
                tmp = tf.name
            try:
                model = whisper.load_model("tiny")
                res = model.transcribe(tmp, language=language)
                transcript = res.get("text", "").strip()
            finally:
                try: os.remove(tmp)
                except: pass
    except Exception as e:
        log.warning("transcribe whisper failed: %s", e)
    if not transcript:
        # Graceful fallback: tell client to use text path; still return 200 with flag
        return {
            "transcribed": False,
            "transcript": "",
            "note": "Whisper not configured on this host. Send transcript text via POST /analyze-call instead.",
            "overallRisk": "unknown",
            "details": [],
        }
    # Analyze transcript
    result = await orchestrator.analyze(transcript)
    return {"transcribed": True, "transcript": transcript, **result}


@app.post("/scan-qr")
async def scan_qr(req: ScanQrRequest):
    """
    QR/UPI shield. Decodes qrData string: URL -> url_risk_agent, UPI -> ledger + pattern check, plain text -> orchestrator.
    Frontend uses ML Kit Barcode to get qrData, backend judges it.
    """
    qr = req.qrData.strip()
    if not qr:
        raise HTTPException(status_code=400, detail="qrData is required")
    # UPI pattern: upi://pay?pa=...  or ...@upi / ...@ybl etc
    is_upi = qr.lower().startswith("upi://") or re.search(r"[\w.\-]{2,}@(upi|ybl|okaxis|okhdfcbank|oksbi|paytm)", qr, re.IGNORECASE)
    if is_upi:
        # Extract VPA and check ledger
        m = re.search(r"pa=([^&]+)", qr, re.IGNORECASE)
        vpa = m.group(1) if m else qr
        # also extract pa if bare VPA
        if not m and "@" in qr:
            vpa = qr.split()[0]
        ledger = await check_number_reputation(vpa)
        # Heuristic: UPI with urgency keywords upgrades
        text_res = await orchestrator.analyze(qr)
        # Merge: UPI scams are high if ledger reported or text high
        overall = text_res.get("overallRisk", "low")
        if ledger.get("reported"):
            overall = "high"
        return {
            "qrData": qr,
            "type": "upi",
            "vpa": vpa,
            "ledger": ledger,
            "textAnalysis": text_res,
            "overallRisk": overall,
        }
    # URL-like -> scan-url logic
    if re.search(r"https?://|www\.|\.in/|\.com|upi://", qr, re.IGNORECASE):
        # Try url agent
        from agents.url_risk_agent import check_url as check_single_url
        # Extract first url
        urls = orchestrator.extract_urls(qr)
        target = urls[0] if urls else qr
        url_res = await check_single_url(target)
        ledger = await check_number_reputation(target)
        order = {"high": 3, "medium": 2, "low": 1, "unknown": 0}
        overall = max([url_res.get("risk", "low"), ledger.get("riskLevel", "low")], key=lambda r: order.get(r, 0))
        if overall == "unknown":
            overall = "low"
        return {"qrData": qr, "type": "url", "urlRisk": url_res, "ledger": ledger, "overallRisk": overall}
    # Plain text QR (e.g., OTP instructions) -> text analyze
    text_res = await orchestrator.analyze(qr)
    return {"qrData": qr, "type": "text", "textAnalysis": text_res, "overallRisk": text_res.get("overallRisk", "low")}


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
