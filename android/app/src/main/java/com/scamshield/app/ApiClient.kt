package com.scamshield.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Backend client — sends TEXT ONLY (never images) to /analyze, /report, /check.
 * Matches backend/main.py contract.
 */
data class AnalyzeDetail(
    val source: String,
    val risk: String = "",
    val category: String = "",
    val reason: String = "",
    val url: String? = null,
    val number: String? = null,
    val reported: Boolean? = null,
    val reportCount: Int? = null,
    val riskLevel: String? = null,
)

data class AnalyzeResult(
    val overallRisk: String,
    val details: List<AnalyzeDetail>
)

/**
 * Snapshot of GET /health — what actually serves requests right now, as opposed to the
 * static chips in the header. `granite` is "live" or "mock", so the UI can stop claiming
 * real AI when the backend fell back to heuristics.
 */
data class BackendHealth(
    val ok: Boolean,
    val version: String = "",
    val granite: String = "",       // "live" | "mock"
    val model: String = "",
    val graniteNote: String? = null,
    val ledgerMode: String = "",    // "fabric" | "fallback"
    val ledgerStore: String = "",   // "sqlite" | "fabric"
    val entries: Int = 0,
    val error: String? = null
)

object ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * Status probes get their own client. The scan client waits up to 20s because Granite
     * legitimately takes that long, but a header chip blocking for 20s just looks frozen —
     * a probe that has not answered in a few seconds has told us what we need to know.
     */
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /** GET /health. Never throws — a dead backend is a normal state the chip must render. */
    suspend fun health(backendUrl: String): BackendHealth = withContext(Dispatchers.IO) {
        try {
            val url = backendUrl.trimEnd('/') + "/health"
            val req = Request.Builder().url(url).get().build()
            probeClient.newCall(req).execute().use { resp ->
                val txt = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) {
                    return@withContext BackendHealth(ok = false, error = "HTTP ${resp.code}")
                }
                val o = JSONObject(txt)
                BackendHealth(
                    ok = o.optString("status") == "ok",
                    version = o.optString("version"),
                    // Older backends (< 1.1.0) only sent mockGranite — derive the same answer
                    // so a stale server does not render an empty chip.
                    granite = o.optString("granite").ifEmpty {
                        if (o.optBoolean("mockGranite")) "mock" else "live"
                    },
                    model = o.optString("model"),
                    graniteNote = o.optString("graniteNote").ifEmpty { null },
                    ledgerMode = o.optString("ledgerMode"),
                    ledgerStore = o.optString("ledgerStore"),
                    entries = o.optInt("entries", 0)
                )
            }
        } catch (e: Exception) {
            BackendHealth(ok = false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * GET /warmup — fire-and-forget on launch. Free-tier hosts (Render, Code Engine at
     * min-scale 0) suspend an idle instance, and the cold start can outlast the scan
     * timeout, so the user's first bubble tap fails for a reason that has nothing to do
     * with their input. Paying that boot cost here means the first real scan is warm.
     */
    suspend fun warmup(backendUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = backendUrl.trimEnd('/') + "/warmup"
            val req = Request.Builder().url(url).get().build()
            // Longer read budget than /health: waiting through the cold start IS the point.
            val waker = probeClient.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
            waker.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun analyze(text: String, backendUrl: String): AnalyzeResult = withContext(Dispatchers.IO) {
        val url = backendUrl.trimEnd('/') + "/analyze"
        val body = JSONObject().put("text", text).toString().toRequestBody(JSON)
        val req = Request.Builder().url(url).post(body).header("Accept", "application/json").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("analyze ${resp.code}: ${resp.body?.string()?.take(300)}")
            val json = JSONObject(resp.body!!.string())
            val overall = json.optString("overallRisk", "unknown")
            val arr = json.optJSONArray("details") ?: JSONArray()
            val details = mutableListOf<AnalyzeDetail>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                details += AnalyzeDetail(
                    source = o.optString("source"),
                    risk = o.optString("risk").ifEmpty { o.optString("riskLevel") },
                    category = o.optString("category"),
                    reason = o.optString("reason"),
                    url = o.optString("url").ifEmpty { null },
                    number = o.optString("number").ifEmpty { null },
                    reported = if (o.has("reported")) o.optBoolean("reported") else null,
                    reportCount = if (o.has("reportCount")) o.optInt("reportCount") else null,
                    riskLevel = o.optString("riskLevel").ifEmpty { null }
                )
            }
            AnalyzeResult(overall, details)
        }
    }

    suspend fun report(numberOrUrl: String, category: String, backendUrl: String): JSONObject = withContext(Dispatchers.IO) {
        val url = backendUrl.trimEnd('/') + "/report"
        val body = JSONObject().put("numberOrUrl", numberOrUrl).put("category", category).toString().toRequestBody(JSON)
        val req = Request.Builder().url(url).post(body).build()
        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("report ${resp.code}: $txt")
            JSONObject(txt)
        }
    }

    suspend fun check(numberOrUrl: String, backendUrl: String): JSONObject = withContext(Dispatchers.IO) {
        // GET /check/{key} — key must be URL-encoded
        val enc = java.net.URLEncoder.encode(numberOrUrl, "UTF-8")
        val url = backendUrl.trimEnd('/') + "/check/$enc"
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw RuntimeException("check ${resp.code}: $txt")
            JSONObject(txt)
        }
    }

    // v1.2: Live call streaming — same as analyze but with caller context
    suspend fun analyzeCall(text: String, backendUrl: String, callerId: String? = null, chunkId: Int? = null): AnalyzeResult = withContext(Dispatchers.IO) {
        val url = backendUrl.trimEnd('/') + "/analyze-call"
        val bodyJson = JSONObject().put("text", text).put("isLive", true)
        if (callerId != null) bodyJson.put("callerId", callerId)
        if (chunkId != null) bodyJson.put("chunkId", chunkId)
        val body = bodyJson.toString().toRequestBody(JSON)
        val req = Request.Builder().url(url).post(body).header("Accept", "application/json").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("analyze-call ${resp.code}: ${resp.body?.string()?.take(300)}")
            val json = JSONObject(resp.body!!.string())
            val overall = json.optString("overallRisk", "unknown")
            val arr = json.optJSONArray("details") ?: JSONArray()
            val details = mutableListOf<AnalyzeDetail>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                details += AnalyzeDetail(
                    source = o.optString("source"),
                    risk = o.optString("risk").ifEmpty { o.optString("riskLevel") },
                    category = o.optString("category"),
                    reason = o.optString("reason"),
                    url = o.optString("url").ifEmpty { null },
                    number = o.optString("number").ifEmpty { null },
                    reported = if (o.has("reported")) o.optBoolean("reported") else null,
                    reportCount = if (o.has("reportCount")) o.optInt("reportCount") else null,
                    riskLevel = o.optString("riskLevel").ifEmpty { null }
                )
            }
            AnalyzeResult(overall, details)
        }
    }

    // v1.2: QR / UPI shield
    suspend fun scanQr(qrData: String, backendUrl: String): JSONObject = withContext(Dispatchers.IO) {
        val url = backendUrl.trimEnd('/') + "/scan-qr"
        val body = JSONObject().put("qrData", qrData).toString().toRequestBody(JSON)
        val req = Request.Builder().url(url).post(body).build()
        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw RuntimeException("scan-qr ${resp.code}: $txt")
            JSONObject(txt)
        }
    }
}
