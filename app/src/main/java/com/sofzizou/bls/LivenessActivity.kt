package com.sofzizou.bls

import android.Manifest
import android.annotation.SuppressLint
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
    private var scriptInjected = false   // injecter une seule fois

    // ── Demande permission caméra ─────────────────────────────────────────────
    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startFlow()
        else {
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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            applyProxyThenStart()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun applyProxyThenStart() {
        if (proxyUrl.isNotEmpty()) applyProxy(proxyUrl) { startFlow() }
        else startFlow()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            allowFileAccess                  = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode                 = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.addJavascriptInterface(AndroidBridge(), "Android")

        webView.webViewClient = object : WebViewClient() {

            /**
             * Proxy GET vers Cloudflare Worker pour les requêtes OzForensics.
             * Le Worker ajoute X-Forwarded-For + X-Real-IP (IP attendue par BLS),
             * envoie User-Agent Windows Chrome, et n'envoie PAS sec-ch-ua
             * (absent côté serveur → OzForensics ne voit pas "Android WebView").
             *
             * POST non intercepté (corps illisible) → WebView natif.
             * OPTIONS non intercepté → WebView natif.
             */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url      = request.url.toString()
                val isTarget = url.contains("ozforensics.com") || url.contains("jscrambler.com")
                if (!isTarget) return null

                val method   = request.method
                val shortUrl = url.replace(Regex("^https?://[^/]+"), "").take(70)

                // ── Log : toutes les requêtes OzForensics avec leurs headers ──
                val allHeaders = request.requestHeaders
                val important  = listOf("x-forwarded-for","x-real-ip","user-agent",
                                        "sec-ch-ua","sec-ch-ua-platform","sec-ch-ua-mobile",
                                        "origin","referer")
                val headersSummary = buildString {
                    important.forEach { key ->
                        allHeaders.entries.firstOrNull { it.key.lowercase() == key }
                            ?.let { append("  ✅ ${it.key}: ${it.value.take(70)}\n") }
                            ?: append("  ❌ $key: absent\n")
                    }
                }
                CoroutineScope(Dispatchers.IO).launch {
                    github.sendTelegram(
                        "📡 *$method* `$shortUrl`\n```\n${headersSummary.trimEnd()}\n```"
                    )
                }

                // POST / OPTIONS → natif (corps illisible → pas de proxy possible)
                if (method != "GET") {
                    CoroutineScope(Dispatchers.IO).launch {
                        github.sendTelegram("⚠️ *$method* → natif (non-GET, corps non lisible)")
                    }
                    return null
                }

                val data = livenessData
                if (data == null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        github.sendTelegram("⚠️ livenessData NULL → natif (données pas encore chargées)")
                    }
                    return null
                }
                if (data.ip == "N/A" || data.ip.isEmpty()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        github.sendTelegram("⚠️ IP = '${data.ip}' → natif (IP absente du JSON GitHub)")
                    }
                    return null
                }

                // ── Appel au Cloudflare Worker /proxy ────────────────────────
                val workerBase  = Constants.WORKER_URL.substringBeforeLast('/')
                val targetEnc   = java.net.URLEncoder.encode(url, "UTF-8")
                val ipEnc       = java.net.URLEncoder.encode(data.ip, "UTF-8")
                val proxyReqUrl = "$workerBase/proxy?url=$targetEnc&ip=$ipEnc"

                CoroutineScope(Dispatchers.IO).launch {
                    github.sendTelegram(
                        "🔄 Proxy → Worker\n🌐 IP: `${data.ip}`\n🔗 `$shortUrl`"
                    )
                }

                return try {
                    val resp = github.httpClient()
                        .newBuilder()
                        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                        .newCall(
                            okhttp3.Request.Builder()
                                .url(proxyReqUrl)
                                .get()
                                .build()
                        ).execute()

                    if (!resp.isSuccessful) {
                        CoroutineScope(Dispatchers.IO).launch {
                            github.sendTelegram(
                                "❌ Worker ${resp.code} `$shortUrl` → fallback natif"
                            )
                        }
                        resp.close()
                        return null
                    }

                    // Lire le corps en mémoire (OkHttp décompresse gzip automatiquement)
                    val body  = resp.body ?: run { resp.close(); return null }
                    val bytes = body.bytes()

                    // Parser Content-Type → mimeType + charset
                    val ct      = resp.header("Content-Type", "application/octet-stream") ?: "application/octet-stream"
                    val parts   = ct.split(";").map { it.trim() }
                    val mime    = parts[0].ifEmpty { "application/octet-stream" }
                    val charset = parts.firstOrNull { it.startsWith("charset=") }
                        ?.substringAfter("charset=")

                    // Headers de réponse (skip hop-by-hop + content-encoding géré par OkHttp)
                    val skipHeaders = setOf("transfer-encoding","connection","keep-alive","content-encoding")
                    val headers = linkedMapOf<String, String>()
                    resp.headers.toMultimap().forEach { (name, values) ->
                        if (name.lowercase() !in skipHeaders) headers[name] = values.last()
                    }
                    if (headers.keys.none { it.equals("access-control-allow-origin", ignoreCase = true) }) {
                        headers["Access-Control-Allow-Origin"] = "https://algeria.blsspainglobal.com"
                    }

                    CoroutineScope(Dispatchers.IO).launch {
                        github.sendTelegram(
                            "✅ Proxy OK `$shortUrl`\n📦 ${bytes.size} bytes | X-Forwarded-For: ${data.ip}"
                        )
                    }

                    WebResourceResponse(
                        mime, charset,
                        resp.code,
                        resp.message.ifEmpty { "OK" },
                        headers,
                        java.io.ByteArrayInputStream(bytes)
                    )

                } catch (e: Exception) {
                    CoroutineScope(Dispatchers.IO).launch {
                        github.sendTelegram(
                            "❌ Proxy exception `$shortUrl`:\n${e.message?.take(120)}"
                        )
                    }
                    null  // fallback natif
                }
            }

            override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                Log.e("WebView", "Error ${err.errorCode}: ${err.description} @ ${req.url}")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }
    }

    // ── Construit le script JS à injecter dans la vraie page BLS ─────────────
    private fun buildInjectScript(data: GitHubClient.LivenessData): String {
        fun js(s: String) = "\"${s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")}\""
        val ip  = js(data.ip)
        val ua  = js(data.userAgent)
        val uid = js(data.userId)
        val tid = js(data.transactionId)

        return """
(function(){
    var IP=$ip, UA=$ua, UID=$uid, TID=$tid;

    /* ① Même URL que l'extension (content_favicon.js) */
    try{history.replaceState(null,'Liveness Check','/dza/appointment/livenessrequest');}catch(e){}
    document.title='Liveness Check';

    /* ② Écran de chargement */
    document.body.style.cssText='margin:0;background:linear-gradient(135deg,#07111f,#0f2a44);min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;font-family:Arial,sans-serif;color:white;text-align:center;padding:20px;overflow:auto;';
    document.body.innerHTML='<style>*{margin:0;padding:0;box-sizing:border-box}html,body{width:100%;height:100%;overflow:auto}#loader{width:60px;height:60px;border:4px solid rgba(255,255,255,.1);border-top:4px solid #f5d27a;border-radius:50%;animation:spin 1s linear infinite;margin-bottom:20px}@keyframes spin{to{transform:rotate(360deg)}}#status{font-size:16px;color:#f5d27a;max-width:300px;line-height:1.5}</style><div id="loader"></div><div id="status">Initialisation...</div>';

    /* ③ Patch XHR/fetch : injecte X-Forwarded-For + X-Real-IP sur TOUTES les
          requêtes (GET ET POST) vers ozforensics.com / jscrambler.com.
          Équivalent de declarativeNetRequest de l'extension. */
    if(IP&&IP!=='N/A'){(function(){
        var T=['ozforensics.com','jscrambler.com'];
        function hit(u){if(!u)return false;for(var i=0;i<T.length;i++)if(u.indexOf(T[i])>=0)return true;return false;}
        var XP=XMLHttpRequest.prototype,oO=XP.open,oS=XP.setRequestHeader,oD=XP.send;
        XP.open=function(){this._u=String(arguments[1]||'');this._h={};return oO.apply(this,arguments);};
        XP.setRequestHeader=function(n){if(this._h)this._h[n.toLowerCase()]=1;return oS.apply(this,arguments);};
        XP.send=function(b){
            if(hit(this._u)){
                if(!this._h['x-forwarded-for'])try{oS.call(this,'X-Forwarded-For',IP);}catch(e){}
                if(!this._h['x-real-ip'])try{oS.call(this,'X-Real-IP',IP);}catch(e){}
            }
            return oD.apply(this,[b]);
        };
        if(window.fetch){var oF=window.fetch;window.fetch=function(inp,ini){
            var u=typeof inp==='string'?inp:(inp&&inp.url)?inp.url:'';
            if(hit(u)){
                ini=ini?Object.assign({},ini):{};
                var h=ini.headers?(typeof ini.headers.forEach==='function'?(function(x){ini.headers.forEach(function(v,k){x[k]=v;});return x;}({})):Object.assign({},ini.headers)):{};
                if(!h['x-forwarded-for']&&!h['X-Forwarded-For'])h['X-Forwarded-For']=IP;
                if(!h['x-real-ip']&&!h['X-Real-IP'])h['X-Real-IP']=IP;
                ini.headers=h;
            }
            return oF.call(window,inp,ini);
        };}
    })();}

    /* ④ Helpers */
    function setStatus(m){var s=document.getElementById('status');if(s)s.textContent=m;}
    function sleep(ms){return new Promise(function(r){setTimeout(r,ms);});}
    function loadScript(url){
        return new Promise(function(res,rej){
            var s=document.createElement('script');
            s.src=url;
            s.onload=res;
            s.onerror=function(){rej(new Error('Erreur chargement: '+url));};
            document.head.appendChild(s);
        });
    }

    /* ⑤ Init — même ordre + délais que l'extension (background.js INJECT_OZ) */
    (async function init(){
        try{
            setStatus('Chargement du SDK selfie...');
            var css=document.createElement('link');
            css.rel='stylesheet';
            css.href='https://web-sdk.prod.cdn.spain.ozforensics.com/blsinternational/plugin/liveness-81ab90655a.css?ver=1.8.1-10';
            document.head.appendChild(css);
            await sleep(1000);
            await loadScript('https://code.jquery.com/jquery-3.6.0.min.js');
            await sleep(500);
            await loadScript('https://web-sdk.prod.cdn.spain.ozforensics.com/blsinternational/plugin/ozliveness_main.js?ver=1.8.1-10');
            await sleep(500);
            await loadScript('https://web-sdk.prod.cdn.spain.ozforensics.com/blsinternational/plugin_liveness.php?ver=1.8.1-10');
            setStatus('Préparation du selfie...');
            var at=0,ck=setInterval(function(){
                at++;
                if(typeof OzLiveness!=='undefined'&&typeof OzLiveness.open==='function'){
                    clearInterval(ck);launch();
                }else if(at>=120){
                    clearInterval(ck);
                    setStatus('Timeout: SDK non chargé');
                    Android.onLivenessError('Timeout: OzLiveness non disponible');
                }
            },500);
        }catch(e){
            setStatus('Erreur: '+e.message);
            Android.onLivenessError(e.message);
        }
    })();

    /* ⑥ Launch OzLiveness — config identique à l'extension (background.js lignes 409-436) */
    function launch(){
        document.getElementById('loader').style.display='none';
        setStatus('Prêt — selfie en cours...');
        document.body.style.overflow='auto';
        var cfg={
            lang:'en',
            meta:{user_id:UID,transaction_id:TID},
            overlay_options:false,
            action:['video_selfie_blank'],
            headers:{}
        };
        if(IP&&IP!=='N/A'&&IP!=='undefined'&&IP!==''){cfg.headers['X-Forwarded-For']=IP;cfg.headers['X-Real-IP']=IP;}
        if(UA&&UA!=='N/A'&&UA!=='undefined'&&UA!==''){cfg.headers['User-Agent']=UA;}
        if(Object.keys(cfg.headers).length===0)delete cfg.headers;
        cfg.on_complete=function(r){
            var id=r.event_session_id||r.session_id||r.liveness_id||r.LivenessId||r.id||r.result||(r.data&&r.data.liveness_id)||(r.meta&&r.meta.liveness_id);
            if(id){setStatus('✅ Selfie terminé !');Android.onLivenessComplete(id);}
            else{Android.onLivenessError('on_complete sans ID: '+JSON.stringify(r));}
        };
        cfg.on_error=function(e){
            var d;
            try{if(e==null)d='null (SDK fermé sans détail)';else if(e instanceof Error)d=e.name+': '+e.message;else if(typeof e==='string')d=e;else d=JSON.stringify(e);}
            catch(ex){d=String(e);}
            setStatus('❌ '+d.substring(0,150));
            Android.onLivenessError(d);
        };
        OzLiveness.open(cfg);
    }
})();
        """.trimIndent()
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
                    "👁️ *Lien ouvert (Android)*\n" +
                    "📁 `$filename`\n" +
                    "👤 UID: `${data.userId.take(12)}`\n" +
                    "🔑 TID: `${data.transactionId.take(12)}`\n" +
                    "🌐 IP proxy: `${data.ip}`\n" +
                    "🔧 UA: `${data.userAgent.take(50)}`"
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

        /** Log Telegram depuis JS — appelé à chaque étape clé pour debug */
        @JavascriptInterface
        fun sendLog(msg: String) {
            Log.d("OzLog", msg)
            CoroutineScope(Dispatchers.IO).launch {
                github.sendTelegram("📋 *[Android Log]*\n`$filename`\n$msg")
            }
        }

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
            CoroutineScope(Dispatchers.IO).launch {
                github.sendTelegram("❌ *Erreur selfie (Android)*\n📁 `$filename`\n⚠️ $error")
            }
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
