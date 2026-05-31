package com.sofzizou.bls

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    @Volatile private var livenessData: GitHubClient.LivenessData? = null
    @Volatile private var sha2: String? = null
    private lateinit var filename: String
    private var proxyUrl: String = ""

    // ── Demande permission caméra ─────────────────────────────────────────────
    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startFlow()
        } else {
            Toast.makeText(this,
                "❌ Permission caméra refusée — activez-la dans les paramètres",
                Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_liveness)

        filename = intent.getStringExtra(Constants.EXTRA_FILENAME) ?: run {
            Toast.makeText(this, "Erreur: fichier manquant", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        proxyUrl = intent.getStringExtra(Constants.EXTRA_PROXY_URL) ?: ""

        webView = findViewById(R.id.webview)
        setupWebView()

        // Demander la permission caméra avant de démarrer
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            applyProxyThenStart()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun applyProxyThenStart() {
        if (proxyUrl.isNotEmpty()) {
            applyProxy(proxyUrl) { startFlow() }
        } else {
            startFlow()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled               = true
            domStorageEnabled               = true
            allowFileAccess                 = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode                = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.addJavascriptInterface(AndroidBridge(), "Android")

        webView.webViewClient = object : WebViewClient() {

            // Intercepte toutes les requêtes OzForensics → injecte X-Forwarded-For + UA
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                val isTarget = url.contains("ozforensics.com") || url.contains("jscrambler.com")
                if (!isTarget) return null
                // Ne jamais intercepter les POST/PUT : le body est inaccessible via
                // WebResourceRequest → OzForensics recevrait un POST vide → erreur 1-22
                // Les POST sont gérés par le patch XHR/fetch dans liveness.html
                if (request.method.uppercase() != "GET") return null

                val ip = livenessData?.ip       ?: return null
                val ua = livenessData?.userAgent ?: return null
                if (ip == "N/A" && ua == "N/A") return null

                return try {
                    val reqBuilder = okhttp3.Request.Builder().url(url)

                    request.requestHeaders.forEach { (k, v) ->
                        if (!k.equals("User-Agent", ignoreCase = true)) {
                            try { reqBuilder.header(k, v) } catch (_: Exception) {}
                        }
                    }

                    if (ua != "N/A") reqBuilder.header("User-Agent", ua)
                    if (ip != "N/A") reqBuilder.header("X-Forwarded-For", ip)
                    reqBuilder.header("Referer", "https://algeria.blsspainglobal.com/dza/appointment/livenessrequest")

                    val resp        = github.httpClient().newCall(reqBuilder.build()).execute()
                    val contentType = resp.header("Content-Type") ?: "application/octet-stream"
                    val mime        = contentType.split(";").first().trim()
                    val encoding    = if (contentType.contains("charset="))
                                         contentType.substringAfter("charset=").trim() else "UTF-8"
                    val respHeaders = mutableMapOf<String, String>()
                    resp.headers.forEach { (k, v) -> respHeaders[k] = v }

                    WebResourceResponse(
                        mime, encoding, resp.code, resp.message.ifEmpty { "OK" },
                        respHeaders, resp.body?.byteStream()
                    )
                } catch (e: Exception) {
                    Log.e("OzIntercept", "Erreur: ${e.message}")
                    null
                }
            }

            override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                Log.e("WebView", "Error ${err.errorCode}: ${err.description} @ ${req.url}")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Accorder la caméra au WebView (OzForensics)
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }
    }

    private fun applyProxy(proxyUrl: String, onReady: () -> Unit) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            val config = ProxyConfig.Builder().addProxyRule(proxyUrl).build()
            ProxyController.getInstance().setProxyOverride(
                config, Executors.newSingleThreadExecutor()
            ) { runOnUiThread(onReady) }
        } else {
            try {
                val uri = android.net.Uri.parse(proxyUrl)
                System.setProperty("http.proxyHost",  uri.host ?: "")
                System.setProperty("http.proxyPort",  uri.port.toString())
                System.setProperty("https.proxyHost", uri.host ?: "")
                System.setProperty("https.proxyPort", uri.port.toString())
            } catch (e: Exception) {
                Log.e("Proxy", "Fallback failed: ${e.message}")
            }
            onReady()
        }
    }

    private fun startFlow() {
        CoroutineScope(Dispatchers.Main).launch {

            val data = withContext(Dispatchers.IO) { github.readFile(filename) }
            if (data == null) {
                Toast.makeText(this@LivenessActivity,
                    "Erreur: fichier introuvable sur GitHub", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            livenessData = data

            sha2 = withContext(Dispatchers.IO) {
                github.markViewed(filename, data.sha, data.rawJson)
            }

            withContext(Dispatchers.IO) {
                github.sendTelegram(
                    "👁️ *Lien ouvert (Android)*\n📱 Client a ouvert l'app\n📁 `$filename`"
                )
            }

            if (data.userAgent != "N/A") {
                webView.settings.userAgentString = data.userAgent
            }

            val html = assets.open("liveness.html").bufferedReader().readText()
            webView.loadDataWithBaseURL(
                Constants.BLS_BASE_URL, html, "text/html", "UTF-8", null
            )
        }
    }

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

                    val ok = withContext(Dispatchers.IO) {
                        github.writeLivenessId(filename, sha, rawData, livenessId)
                    }

                    withContext(Dispatchers.IO) {
                        github.sendTelegram(
                            "✅ *Selfie terminé*\n🆔 *Liveness ID:* `$livenessId`\n📁 *Fichier:* $filename\n💾 *GitHub:* ${if (ok) "✅" else "❌"}"
                        )
                        Thread.sleep(500)
                        github.sendTelegram(
                            "✅ *Commande injection :*\n```javascript\ndocument.getElementById('LivenessId').value = \"$livenessId\";\ndocument.getElementById('formLiveness').submit();\n```"
                        )
                    }
                    showDoneScreen(livenessId)
                }
            }
        }

        @JavascriptInterface
        fun onLivenessError(error: String) {
            Log.e("Liveness", "❌ $error")
            runOnUiThread {
                Toast.makeText(this@LivenessActivity, "Erreur selfie: $error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showDoneScreen(livenessId: String) {
        webView.loadDataWithBaseURL(null, """
            <html><body style="margin:0;background:linear-gradient(135deg,#07111f,#0f2a44);
              display:flex;justify-content:center;align-items:center;min-height:100vh;
              font-family:Arial;color:white;text-align:center;padding:20px">
            <div>
              <div style="font-size:80px">✅</div>
              <h2 style="color:#f5d27a;margin:20px 0">Selfie envoyé !</h2>
              <p style="opacity:.8;line-height:1.6">Votre selfie a été transmis.<br>Votre rendez-vous sera finalisé sous peu.</p>
              <p style="font-size:10px;opacity:.3;margin-top:30px">${livenessId.take(16)}…</p>
            </div></body></html>
        """.trimIndent(), "text/html", "UTF-8", null)
    }

    override fun onDestroy() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            ProxyController.getInstance().clearProxyOverride(Executors.newSingleThreadExecutor()) {}
        }
        super.onDestroy()
    }
}
