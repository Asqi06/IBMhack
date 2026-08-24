# ScamShield Chrome Extension

Companion to the ScamShield Android app. When enabled, automatically scans pages for malicious links using the same backend (`POST /scan-url` → SafeBrowsing + Granite).

## Install (dev)
1. Start backend: `cd backend && uvicorn main:app --port 8000`
2. Open `chrome://extensions` → enable Developer mode → Load unpacked → select `extension/` folder
3. Pin ScamShield, open popup → set Backend URL (default `http://localhost:8000`, or your Code Engine URL) → Save
4. Toggle “Auto-scan links” ON
5. Browse any page with links — high/medium risks get a red/orange outline + badge. Click badge for reason + “Report as scam” (calls `POST /report`).

## How it works
- `content.js` collects `<a href>` + visible `https://` text, dedupes, calls `POST /scan-url` (fallback `POST /analyze`) with concurrency 4
- Highlights `scamshield-high/medium`, injects badge, tooltip shows `reason` from Granite
- `popup.html` lets user toggle + configure backend URL (stored in `chrome.storage.sync`)
- `background.js` sets badge count for high-risk links per tab
- Respects future App ↔ Extension sync: app could flip `enabled` via `GET /extension/config` (hook left in `background.js`).

## Backend contract
- `POST /scan-url {url}` → `{overallRisk, urlRisk, ledger}`
- `POST /report {numberOrUrl, category}`
- CORS must be `*` in backend `.env` for local dev.

## Testing a spoof manually
Popup → Quick test → `https://arnaz0n-kyc.in/login` → Test → should return `high`.
