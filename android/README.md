# ScamShield Android — Native Kotlin App

This is a **native Android (Kotlin)** app — not hybrid, not webview. Main MVP is the **floating widget/bubble** (chat-head). A home-screen widget is also provided as a quick launcher.

## Where is the widget MVP?
- **Floating bubble (MVP, brief Sec 1 & 8)**: `app/src/main/java/com/scamshield/app/FloatingWidgetService.kt:1`
  - `WindowManager` `TYPE_APPLICATION_OVERLAY` + draggable `view_floating_widget.xml:1` (56dp ◈ bubble)
  - On **explicit tap only** → `ScreenshotHelper.kt:1` (`MediaProjection` one-shot) → `OcrHelper.kt:1` (ML Kit on-device `TextRecognition.getClient`) → `ApiClient.kt:1` `POST /analyze` **text only** → color card `view_floating_widget.xml:18` (red `high` / orange `medium` / green `low`) + `Report as Scam` → `POST /report`
  - Background `ForegroundService` with notification, no continuous monitoring.

- **Home-screen widget (companion)**: `app/src/main/java/com/scamshield/app/ScamShieldWidgetProvider.kt:1` + `res/layout/widget_scamshield.xml:1` + `res/xml/scamshield_widget_info.xml:1`
  - Added because "app widget" is often shown to judges on the launcher. Shows `Last scan: HIGH` from `MainActivity.kt:203` `persistLastRisk()` and a `Scan screen` button that opens `MainActivity` to start the bubble flow. Not required by brief, but makes the MVP visible.
  - Declared in `AndroidManifest.xml:34` as `receiver` with `APPWIDGET_UPDATE`.

Both are native Kotlin, `minSdk 26`, `targetSdk 34` at `app/build.gradle:5`.

## Native build — step by step

### Option A: Android Studio (recommended)
1. Open folder `F:\ibm hackathon\android` in Android Studio Hedgehog+ (not the parent).
2. Wait for Gradle sync — fetches `com.google.mlkit:text-recognition:16.0.0`, `okhttp:4.12.0`, `play-services-auth`.
3. Start backend first: in terminal `cd F:\ibm hackathon\backend && uvicorn main:app --host 0.0.0.0 --port 8000` (or use `run.ps1`).
4. In Studio: Run → Edit Configurations → app → choose device:
   - **Emulator**: backend URL in app = `http://10.0.2.2:8000` (this is how emulator reaches host `localhost`)
   - **Physical device**: same Wi-Fi, backend URL = `http://<your PC IP>:8000` (find via `ipconfig`, e.g. `192.168.1.12`). Backend already allows CORS `*` in `backend/.env:20`.
5. Click Run. App installs as `ScamShield`.

### Option B: Command line (no Studio)
```powershell
cd F:\ibm hackathon\android
# if you have Android SDK + gradle:
.\gradlew assembleDebug   # produces app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\debug\app-debug.apk
# or launch backend then use adb reverse for emulator:
adb reverse tcp:8000 tcp:8000  # then use http://localhost:8000 even on device
```

### Grant flow (first run — do in order)
1. App opens → set Backend URL correctly → tap **Enable Floating Widget** → system takes you to `Settings → Display over other apps` → enable for ScamShield → back.
2. Tap **Start floating bubble** → one-time `MediaProjection` prompt ("Start now") → bubble ◈ appears overlay (drag to move).
3. Keep app in background (press home). Open any other app (e.g. Messages with `Your account will be blocked share OTP...`). Tap bubble → toast `Scanning…` → card appears below bubble with color + reason.
4. If `high`/`medium`, **Report as Scam** button writes to ledger (`POST /report`). 
5. Home widget: long-press launcher → Widgets → ScamShield → drag to home → tap `Scan screen` → opens app.

### Testing without bubble (for judges without overlay)
In `MainActivity`'s "Manual test" field paste: `Your SBI KYC expired click http://sbi-kyc-update.com` → Analyze → same pipeline, same `overallRisk` card. Useful for demo table.

### SMS Retriever (auto-scan, no READ_SMS)
`SmsRetrieverHelper.kt:1` registers `SmsRetriever.getClient().startSmsRetriever()`. Incoming SMS containing app hash is delivered to `onSms` → same `ApiClient.analyze()` → shows in same result card + toast. Requires Play Services, no `READ_SMS` permission (brief Sec 2).

### Hard constraints enforced (see code comments)
- `FloatingWidgetService.kt:1` — tap-only, never background
- `OcrHelper.kt:1` — `bitmap` never leaves device, only `String` sent
- `AndroidManifest.xml:5` — only `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `INTERNET`; no `RECORD_AUDIO`, no `READ_SMS`
- `MainActivity.kt:1` + `FloatingWidgetService.kt` — ledger writes only `{numberOrUrl, category, timestamp}`

### Troubleshooting
- **Bubble not showing**: check `Settings.canDrawOverlays()` — re-tap Enable, ensure not in battery optimization.
- **Scan fails / backend unreachable**: confirm backend `uvicorn` is on `0.0.0.0`, phone/emulator can `curl http://10.0.2.2:8000/health` (emulator) or `http://<PC IP>:8000/health`. Check device and PC same Wi-Fi, firewall allows 8000.
- **ML Kit text empty**: ensure screenshot has text; try larger font in other app.
- **Widget not in list**: rebuild (`assembleDebug`), ensure `res/xml/scamshield_widget_info.xml` present.

### Related
- Backend contract at `backend/main.py:1`, test with `backend/test_api.py:1`
- Chrome extension mirrors scanning at `extension/content.js:1` via same `POST /scan-url`
