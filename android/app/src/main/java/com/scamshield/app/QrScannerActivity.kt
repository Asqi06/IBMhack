package com.scamshield.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import android.widget.TextView
import android.widget.Button
import android.view.View
import com.google.android.material.card.MaterialCardView
import androidx.core.graphics.toColorInt

/**
 * v1.2 QR / UPI Shield — scans QR via ZXing (ML Kit barcode underlying) + sends to /scan-qr.
 * Supports: upi://, http(s)://, and plain text QRs.
 */
class QrScannerActivity : AppCompatActivity() {

    private var backendUrl: String = "https://scanshield-ii9n.onrender.com"

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchScanner() else Toast.makeText(this, "Camera permission needed for QR scan", Toast.LENGTH_LONG).show()
    }

    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents == null) {
            Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show()
            finish()
            return@registerForActivityResult
        }
        onQrDecoded(result.contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scanner)
        backendUrl = getSharedPreferences("scamshield", MODE_PRIVATE).getString("backendUrl", backendUrl) ?: backendUrl
        findViewById<MaterialToolbar>(R.id.qrToolbar)?.setNavigationOnClickListener { finish() }
        findViewById<Button>(R.id.qrScanBtn)?.setOnClickListener { checkAndScan() }
        findViewById<Button>(R.id.qrPasteBtn)?.setOnClickListener {
            val input = findViewById<android.widget.EditText>(R.id.qrInput)?.text?.toString()?.trim().orEmpty()
            if (input.isBlank()) Toast.makeText(this, "Paste QR data first", Toast.LENGTH_SHORT).show()
            else onQrDecoded(input)
        }
        checkAndScan()
    }

    private fun checkAndScan() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> launchScanner()
            else -> permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchScanner() {
        val opts = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Align QR / UPI QR within frame")
            setCameraId(0)
            setBeepEnabled(true)
            setBarcodeImageEnabled(false)
            setOrientationLocked(false)
        }
        barcodeLauncher.launch(opts)
    }

    private fun onQrDecoded(qrData: String) {
        findViewById<TextView>(R.id.qrRawText)?.text = qrData
        findViewById<View>(R.id.qrResultCard)?.visibility = View.GONE
        findViewById<TextView>(R.id.qrStatus)?.text = "Analyzing…"
        lifecycleScope.launch {
            try {
                val res = ApiClient.scanQr(qrData, backendUrl)
                renderResult(qrData, res)
            } catch (e: Exception) {
                findViewById<TextView>(R.id.qrStatus)?.text = "Scan failed: ${e.message}"
                Toast.makeText(this@QrScannerActivity, "Scan failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderResult(qrData: String, json: org.json.JSONObject) {
        val overall = json.optString("overallRisk", "unknown")
        val type = json.optString("type", "text")
        val risk = overall.lowercase()
        val color = when (risk) { "high" -> "#E53935".toColorInt(); "medium" -> "#FB8C00".toColorInt(); else -> "#43A047".toColorInt() }
        val card = findViewById<MaterialCardView>(R.id.qrResultCard)
        card.visibility = View.VISIBLE
        card.strokeColor = color
        findViewById<TextView>(R.id.qrOverallRisk)?.text = getString(R.string.risk_label, overall.uppercase())
        findViewById<TextView>(R.id.qrOverallRisk)?.setTextColor(color)
        findViewById<TextView>(R.id.qrTypeLabel)?.text = "Type: $type"
        val detail = when (type) {
            "upi" -> {
                val ledger = json.optJSONObject("ledger")
                val reported = ledger?.optBoolean("reported", false) == true
                val count = ledger?.optInt("reportCount", 0) ?: 0
                "VPA: ${json.optString("vpa", qrData)}\nReported: $reported (count $count)\n${if (reported) "⚠️ Do NOT pay — reported as scam" else "No ledger reports"}"
            }
            "url" -> {
                val urlRisk = json.optJSONObject("urlRisk")?.optString("reason") ?: ""
                val ledger = json.optJSONObject("ledger")
                "URL risk: ${json.optJSONObject("urlRisk")?.optString("risk")} — $urlRisk\nLedger reported: ${ledger?.optBoolean("reported")}"
            }
            else -> json.optJSONObject("textAnalysis")?.optJSONArray("details")?.let { arr ->
                (0 until arr.length()).joinToString("\n") { i -> val o = arr.getJSONObject(i); "• ${o.optString("source")}: ${o.optString("risk")}" }
            } ?: overall
        }
        findViewById<TextView>(R.id.qrReason)?.text = when (risk) {
            "high" -> "⚠️ Advised: Do NOT pay/scan. Reported as scam.\n\n$detail"
            "medium" -> "⚠️ Suspicious — verify sender before paying.\n\n$detail"
            else -> detail
        }
        findViewById<TextView>(R.id.qrStatus)?.text = ""
        // Save dangerous to log
        if (risk == "high" || risk == "medium") {
            val reason = findViewById<TextView>(R.id.qrReason)?.text?.toString() ?: ""
            val log = ScanLog(overallRisk = risk, category = if (type=="upi") "UPI scam" else "phishing link", reason = reason.take(200), snippet = qrData.take(120), fullText = qrData, source = "qr", target = qrData)
            lifecycleScope.launch { try { AppDatabase.get(this@QrScannerActivity).scanLogDao().insert(log) } catch(_: Exception){} }
        }
    }
}
