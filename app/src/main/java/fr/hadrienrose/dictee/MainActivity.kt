package fr.hadrienrose.dictee

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FileInputStream

/**
 * L'app Dictée = LA PAGE DE PRISME (`/voice?app=1`, la même interface qu'aujourd'hui : liste, fiche, lecteur, sélection
 * des passages) dans une WebView, plus un enregistreur natif (RecorderService) que la page pilote par le pont
 * `window.DicteeNative`. La WebView ne va que sur voice.hadrien-rose.com et le login Cloudflare (code par e-mail) ;
 * tout autre lien s'ouvre dans le navigateur ; le pont n'existe que sur notre origine ; l'audio d'une dictée native est
 * servi à la page sur `/__native/rec/<id>` (jamais un chemin libre, voir Rules).
 */
class MainActivity : Activity() {
    companion object {
        const val START_URL = BuildConfig.SITE + "/voice?app=1"
        const val REQ_MIC = 11
        const val REQ_NOTIF = 12
    }

    lateinit var web: WebView
    private var bridge: DicteeBridge? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile var currentHost: String = ""
    var service: RecorderService? = null; private set
    private var bound = false
    private val pageEvents = ArrayList<String>()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            service = (b as RecorderService.LocalBinder).service
            service?.listener = { ev, data -> pageEvent(ev, data) }
            pageEvent("service", JSONObject().put("state", service?.state))
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        setupWeb()
        bridge = DicteeBridge(this)
        web.addJavascriptInterface(bridge!!, DicteeBridge.NAME)
        // le service existe dès l'ouverture (il adopte une dictée orpheline), lié pour piloter
        startService(Intent(this, RecorderService::class.java))
        bindService(Intent(this, RecorderService::class.java), conn, Context.BIND_AUTO_CREATE)
        bound = true
        askPermissions()
        web.loadUrl(START_URL)
        Updater.checkLater(this) { info -> pageEvent("update", info) }
    }

    private fun setupWeb() {
        val s: WebSettings = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.userAgentString = s.userAgentString + " DicteeApp/" + BuildConfig.VERSION_NAME
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)   // le login Cloudflare Access passe par cloudflareaccess.com puis revient
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme != "https" && u.scheme != "http") { openOutside(u); return true }
                if (!Rules.isAllowedHost(u.host)) { openOutside(u); return true }
                return false
            }
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                currentHost = Uri.parse(url).host ?: ""
            }
            override fun onPageFinished(view: WebView, url: String) {
                currentHost = Uri.parse(url).host ?: ""
                CookieManager.getInstance().flush()
                if (Rules.isSiteHost(currentHost)) flushPageEvents()
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (!Rules.isSiteHost(u.host)) return null
                val id = Rules.recIdFromPath(u.path) ?: return null
                // l'audio d'une dictée native, pour la page seule, confiné au dossier des dictées
                val f = Rules.partFile(Store.root(this@MainActivity), id, u.getQueryParameter("part"))
                return if (f != null && f.isFile) WebResourceResponse("audio/ogg", null, 200, "OK",
                    mapOf("Cache-Control" to "no-store", "Content-Length" to f.length().toString()), FileInputStream(f))
                else WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame && Rules.isSiteHost(request.url.host)) showOffline(error.description?.toString() ?: "")
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()   // la page n'a pas besoin du micro du navigateur : l'enregistreur est natif
            }
        }
    }

    /** Pas de réseau : une page minimale, Réessayer (une dictée en cours continue d'enregistrer, le service est à part). */
    private fun showOffline(detail: String) {
        val html = """<!doctype html><html lang="fr"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<body style="margin:0;background:#101010;color:#e8e4dc;font:16px system-ui;display:flex;min-height:100vh;align-items:center;justify-content:center;text-align:center">
<div style="padding:24px;max-width:360px"><h2 style="font-weight:600;margin:0 0 10px">${getString(R.string.offline_title)}</h2>
<p style="color:#a8a39a;line-height:1.45">${getString(R.string.offline_body)}</p><p style="color:#6b675f;font-size:12px">${detail.replace("<", "&lt;")}</p>
<a href="$START_URL" style="display:inline-block;margin-top:14px;padding:12px 22px;border-radius:12px;background:#d4b06a;color:#101010;text-decoration:none;font-weight:600">${getString(R.string.offline_retry)}</a></div></body></html>"""
        web.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null)
    }

    private fun openOutside(u: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }

    private fun askPermissions() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (requestCode == REQ_MIC) {
            pageEvent("permission", JSONObject().put("what", "mic").put("granted", ok))
            if (ok && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        } else if (requestCode == REQ_NOTIF) pageEvent("permission", JSONObject().put("what", "notif").put("granted", ok))
    }

    /** Un événement natif pour la page : `window.dicteeNativeEvent({ev, …})` (gardé si la page n'est pas encore là). */
    fun pageEvent(ev: String, data: JSONObject) {
        val js = "try{window.dicteeNativeEvent&&dicteeNativeEvent(" + data.put("ev", ev).toString() + ")}catch(e){}"
        main.post {
            if (Rules.isSiteHost(currentHost) && web.progress >= 100) web.evaluateJavascript(js, null)
            else synchronized(pageEvents) { if (pageEvents.size < 50) pageEvents.add(js) }
        }
    }

    private fun flushPageEvents() {
        val l: List<String>
        synchronized(pageEvents) { l = ArrayList(pageEvents); pageEvents.clear() }
        l.forEach { web.evaluateJavascript(it, null) }
    }

    /** Exécute sur le thread principal et attend le résultat (le pont JS tourne sur un autre thread). */
    fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var out: T? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post { try { out = block() } finally { latch.countDown() } }
        latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
        return out
    }

    fun hasMic(): Boolean = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun askMic() { main.post { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC) } }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Retour = une couche de la page (elle gère son historique) ; au bout, l'app passe derrière (la dictée continue)
        if (web.canGoBack() && Rules.isSiteHost(currentHost)) web.goBack() else moveTaskToBack(true)
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); setIntent(intent) }

    override fun onResume() {
        super.onResume()
        pageEvent("visible", JSONObject())
    }

    override fun onDestroy() {
        if (bound) { try { service?.listener = null; unbindService(conn) } catch (_: Exception) { }; bound = false }
        web.destroy()
        super.onDestroy()
    }
}
