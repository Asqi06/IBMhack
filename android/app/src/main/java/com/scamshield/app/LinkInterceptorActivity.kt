package com.scamshield.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Chrome block — intercepts http/https intents when ScamShield is set as a handler.
 * User taps a link in WhatsApp/any app → system shows chooser → if ScamShield is chosen
 * (or set as default for the host), we scan the URL via /scan-url. If high/medium,
 * we BLOCK and advise Delete/Not to touch; if low, we forward to Chrome.
 *
 * This is the Android-side counterpart to the Chrome extension's content.js block.
 * For a full "Chrome should block" without chooser, the user can set ScamShield as
 * default for the malicious host, or we can use a VPNService — but intent-filter is
 * the hackathon-appropriate, non-invasive path.
 */
class LinkInterceptorActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = intent.data
        val url = uri?.toString()
        if (url.isNullOrBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            finish()
            return
        }
        val prefs = getSharedPreferences("scamshield", MODE_PRIVATE)
        val backendUrl = prefs.getString("backendUrl", null) ?: "http://10.0.2.2:8000"

        // Show a quick blocking UI while we scan
        lifecycleScope.launch {
            try {
                val res = ApiClient.check(url, backendUrl) // check ledger first
                // Also check URL risk via /scan-url
                val scan = try {
                    val client = okhttp3.OkHttpClient()
                    val json = org.json.JSONObject().put("url", url).toString()
                    val req = okhttp3.Request.Builder()
                        .url(backendUrl.trimEnd('/') + "/scan-url")
                        .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                    val txt = resp.body?.string() ?: "{}"
                    org.json.JSONObject(txt)
                } catch (_: Exception) { null }

                val urlRisk = scan?.optJSONObject("urlRisk")?.optString("risk") ?: "unknown"
                val ledgerRisk = res.optString("riskLevel", "low")
                val overallHigh = urlRisk == "high" || ledgerRisk == "high" || res.optBoolean("reported", false) && res.optInt("reportCount", 0) > 0

                if (overallHigh || urlRisk == "high" || urlRisk == "medium") {
                    val reason = scan?.optJSONObject("urlRisk")?.optString("reason") ?: res.optString("reason", "Flagged as phishing")
                    // BLOCK — advise Delete / Don't touch
                    AlertDialog.Builder(this@LinkInterceptorActivity)
                        .setTitle("🛑 ScamShield blocked this link")
                        .setMessage("This link is flagged as HIGH RISK:\n\n$url\n\nReason: $reason\n\nAdvised: DO NOT touch it. Delete the message that contained it. If you tapped it, close Chrome and do not enter any OTP/PIN.")
                        .setPositiveButton("Delete & Close") { _, _ ->
                            // Log it
                            lifecycleScope.launch {
                                val db = AppDatabase.get(this@LinkInterceptorActivity)
                                db.scanLogDao().insert(
                                    ScanLog(
                                        overallRisk = "high",
                                        category = "phishing link",
                                        reason = reason,
                                        snippet = url.take(120),
                                        fullText = url,
                                        source = "chrome_block"
                                    )
                                )
                            }
                            Toast.makeText(this@LinkInterceptorActivity, "Logged as blocked — delete the source message", Toast.LENGTH_LONG).show()
                            finish()
                        }
                        .setNeutralButton("Report") { _, _ ->
                            lifecycleScope.launch {
                                try { ApiClient.report(url, "phishing link", backendUrl) } catch(_: Exception) {}
                                Toast.makeText(this@LinkInterceptorActivity, "Reported to ledger", Toast.LENGTH_SHORT).show()
                            }
                            finish()
                        }
                        .setNegativeButton("Open anyway (risky)") { _, _ ->
                            // Forward to Chrome despite warning — user insisted
                            try {
                                val i = Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                startActivity(i)
                            } catch (_: Exception) {}
                            finish()
                        }
                        .setCancelable(false)
                        .show()
                } else {
                    // Low — forward to Chrome
                    try {
                        val i = Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        startActivity(i)
                    } catch (e: Exception) {
                        Toast.makeText(this@LinkInterceptorActivity, "No app to open link: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    finish()
                }
            } catch (e: Exception) {
                // On error, fail open but warn
                AlertDialog.Builder(this@LinkInterceptorActivity)
                    .setTitle("ScamShield — could not verify")
                    .setMessage("Could not check this link ($url) — backend unreachable. Open anyway?")
                    .setPositiveButton("Open") { _, _ ->
                        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch(_: Exception) {}
                        finish()
                    }
                    .setNegativeButton("Cancel") { _, _ -> finish() }
                    .show()
            }
        }
    }


}
