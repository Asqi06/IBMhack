# ScamShield — AI + Blockchain Scam Protection

Floating-widget Android app that OCRs screen text on-demand → backend AI risk pipeline (IBM Granite via watsonx.ai) → blockchain scam registry. + Chrome companion that auto-scans links.

## What was built (per your brief + extension add-on)

**Backend** `backend/` — Python FastAPI, containerized for IBM Cloud Code Engine:
- `agents/watsonx_client.py` — IAM token + generation/chat fallback + `MOCK_GRANITE` heuristic + retry-once
- `agents/scam_text_agent.py` — verbatim Sec 5 prompt, JSON-only, fallback `unknown` on bad parse
- `agents/url_risk_agent.py` — Safe Browsing v4 (free tier) then Granite spoof-domain judgment
- `agents/ledger_agent.py` — `check_number_reputation()` / `report_scam()` with `LEDGER_MODE=fabric|fallback` (identical signatures; fallback is hash-chained SQLite, flagged)
- `agents/ledger_store.py` — hash-chain storage + `store_info()` (store kind, location, entry count)
- `http_client.py` — one pooled `httpx.AsyncClient` shared by every agent, so repeat scans reuse the warm TLS connection instead of re-handshaking to watsonx each tap
- `orchestrator.py` — regex URL + Indian phone extraction, parallel agent fan-out, `overallRisk = max(high>medium>low)`
- `main.py` — `POST /analyze`, `POST /report`, `GET /check/{key}`, `POST /scan-url` (extension helper), `GET /health`, `GET /warmup`, `GET /logs`, `GET /ledger/verify`, plus a per-request log line with an `X-Request-Id`

**Android** `android/` — Kotlin, `minSdk 26`, `WindowManager` chat-head, `MediaProjection` screenshot on **explicit tap only**, ML Kit OCR on-device (text-only sent), color card + Report button, SMS Retriever API. Header chips are driven by `GET /health` — they show real state (`● LIVE` / `● OFFLINE`, model id, `Granite MOCK` when mocked, `Hash-chain (sqlite)` vs `Fabric ledger`, ledger entry count) rather than hardcoded labels, and `GET /warmup` is called on launch so a scaled-to-zero host is awake before the first scan.

**Extension** `extension/` — Manifest V3 (`content.js` + `popup.html` + `background.js`) auto-scans `<a href>` on every page via `POST /scan-url`, highlights red/orange, badge counts, respects enable toggle (future-synced with app).

All Sec 2 hard constraints enforced — see code comments at `FloatingWidgetService.kt:1`, `OcrHelper.kt:1`, `main.py:14`.

## Backend quickstart (verify before Android)

```bash
cd backend
# .env is already filled in with working watsonx credentials — don't overwrite it.
# Starting fresh elsewhere? cp .env.example .env and set MOCK_GRANITE=true to run
# keyless (deterministic heuristics, flagged as mock everywhere they surface).
pip install -r requirements.txt
uvicorn main:app --reload --port 8000
# In another terminal — exact curl from brief:
curl -X POST http://localhost:8000/analyze -H "Content-Type: application/json" -d "{\"text\":\"Your account will be blocked, share OTP now. Call +919876543210\"}"
curl -X POST http://localhost:8000/analyze -H "Content-Type: application/json" -d "{\"text\":\"Hey, are we still meeting for lunch?\"}"
curl -X POST http://localhost:8000/report  -H "Content-Type: application/json" -d "{\"numberOrUrl\":\"+919876543210\",\"category\":\"OTP scam\"}"
curl http://localhost:8000/check/+919876543210
curl http://localhost:8000/health
curl http://localhost:8000/ledger/verify
# Ops helpers:
curl http://localhost:8000/warmup        # wake a scaled-to-zero instance (app calls this on launch)
curl "http://localhost:8000/logs?limit=5" # last N requests, newest first (in-memory ring buffer)
# Extension helper:
curl -X POST http://localhost:8000/scan-url -H "Content-Type: application/json" -d "{\"url\":\"https://arnaz0n-kyc.in/login\"}"
```

`GET /health` is the one the app's header chips read:
```json
{ "status":"ok", "version":"1.1.0", "granite":"live", "model":"ibm/granite-4-h-small",
  "ledgerMode":"fallback", "ledgerStore":"sqlite", "entries":1 }
```

Expected `POST /analyze` shape matches Sec 4:
```json
{ "overallRisk": "high", "details": [{"source":"text","risk":"high","category":"OTP scam","reason":"..."}, {"source":"url",...}, {"source":"number","reported":true,"reportCount":3,"riskLevel":"high"}] }
```

## Key status in `backend/.env`
- `WATSONX_API_KEY` / `WATSONX_PROJECT_ID` / `WATSONX_URL` / `WATSONX_MODEL_ID` / `WATSONX_IAM_URL` — **set and working.** `MOCK_GRANITE=false`, so verdicts come from real Granite (`ibm/granite-4-h-small` on `us-south`). `GET /health` reports `"granite":"live"`; if the credentials ever fail it flips to `"mock"` and both the header chip and the verdict reason say so.
- `GOOGLE_SAFE_BROWSING_API_KEY` — **still empty.** Optional: the url agent skips Safe Browsing and goes straight to Granite for spoof-domain judgment. Paste a key to add the free reputation pre-check.
- `FABRIC_GATEWAY_URL` — set, but `LEDGER_MODE=fallback` so it is unused until you flip the mode and have a gateway running.

## Ledger modes
- `LEDGER_MODE=fabric` — calls your Fabric gateway `GET /check/:key` + `POST /report`. Also mirrors writes to local fallback for resilience.
- `LEDGER_MODE=fallback` — SQLite `ledger.db` with `sha256(key|category|timestamp|prevHash)` chain. `GET /ledger/verify` proves tamper-detectability. Every response includes `"mode":"fallback"` + `"note":"FALLBACK MODE — ... not Fabric consensus"` so judges see the honest trade-off. Swap by changing one env var — no code changes.

## Chrome Extension
1. `chrome://extensions` → Developer mode → Load unpacked → `extension/`
2. Popup → set Backend URL (`http://localhost:8000` locally, or your Code Engine URL), toggle ON.
3. Open any page with a spoof link (`https://arnaz0n-kyc.in/login` injected for testing) — red outline + badge appears. Click badge → tooltip with `reason` → Report → writes to same ledger.

## Android
See `android/README.md`. Open project in Android Studio, set backend URL (`10.0.2.2:8000` for emulator), grant overlay → start bubble → tap to scan.

## Deploy (IBM Cloud Code Engine)
`backend/Dockerfile` already uses `$PORT`. `ibmcloud ce app create --name scamshield --image ... --env-from .env` etc. — you handle push, env injection is from `.env.example`.

## What to tell judges
- Fallback ledger is hash-chained and flagged honestly; Fabric interface is 1:1 swappable.
- Granite is live (`ibm/granite-4-h-small`) — the header chip shows the actual model id. Mock mode still exists as a keyless fallback and is flagged `(mock mode)` in reasons plus a `Granite MOCK` chip, so a mocked run can never be mistaken for a real one.
- Nothing in the header is hardcoded: kill the backend and the chip goes `● OFFLINE`, switch `LEDGER_MODE` and the ledger chip follows.
- No image/SMS audio ever leaves device — only OCR text.
