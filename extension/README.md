# ScamShield Chrome Extension

Companion to the ScamShield Android app. Scans pages for malicious links using the same backend (`POST /scan-url` → SafeBrowsing + Granite), and **blocks the click** on anything flagged high/medium.

## Install (dev)
1. Open `chrome://extensions` → enable Developer mode → Load unpacked → select the `extension/` folder
2. Pin ScamShield. It works immediately against the hosted backend (`https://scanshield-ii9n.onrender.com`) — no local server needed.
   - For a local backend: `cd backend && uvicorn main:app --port 8000`, then popup → Backend URL → `http://localhost:8000` → Save
3. Toggle "Auto-scan links" ON (default)
4. Browse any page with links — high/medium risks get a red/orange outline + badge.

## What it actually does
- **Realtime check:** `content.js` collects `<a href>` + visible `https://` text, dedupes, and calls `POST /scan-url` (fallback `POST /analyze`) with concurrency 4, re-running on DOM mutations and SPA route changes.
- **Realtime block:** clicking a flagged link is intercepted in the *capture* phase (with `stopImmediatePropagation`, so the page's own handlers can't navigate around it) and a full-screen interstitial appears instead: **Go back (safe) / Report as scam / Continue anyway**. "Continue anyway" is remembered per URL so it never nags twice.
  - Unscanned links are never blocked — an unknown verdict must not become a blocked click.
  - Backend-supplied `reason` text and the URL are injected with `textContent`, never `innerHTML`.
- **Badge:** `background.js` counts high-risk links per tab.
- **Report:** the interstitial and the badge tooltip both `POST /report` to the shared ledger.

## Backend contract
- `POST /scan-url {url}` → `{overallRisk, urlRisk: {risk, reason, flagged_by}, ledger}`
- `POST /report {numberOrUrl, category}`
- CORS is `*` by default (`CORS_ORIGINS` env), which is what lets the content script call it from any page.

## Verifying it works
Popup → Quick test → `https://arnaz0n-kyc.in/login` → Test. Expected: `high`, with a Granite reason about typosquatting. Then put that URL in a link on any page and click it — the interstitial should block navigation.
