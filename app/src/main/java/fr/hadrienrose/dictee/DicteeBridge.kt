package fr.hadrienrose.dictee

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

/**
 * Le pont `window.DicteeNative` que la page de Prisme appelle (src/voice.html, `hasNative()`). Chaque méthode rend du
 * JSON en texte. Garde : rien ne répond si la page qui appelle n'est pas voice.hadrien-rose.com (le pont est posé sur
 * la WebView, l'origine est revérifiée ici à chaque appel).
 */
class DicteeBridge(private val act: MainActivity) {
    companion object { const val NAME = "DicteeNative" }

    private fun guard(): JSONObject? = if (Rules.isSiteHost(act.currentHost)) null else JSONObject().put("ok", false).put("error", "origin")
    private fun svc(): RecorderService? = act.service

    @JavascriptInterface fun version(): String = (guard() ?: JSONObject().put("ok", true).put("versionName", BuildConfig.VERSION_NAME)
        .put("versionCode", BuildConfig.VERSION_CODE).put("android", android.os.Build.VERSION.SDK_INT).put("release", android.os.Build.VERSION.RELEASE)
        .put("model", android.os.Build.MODEL).put("brand", android.os.Build.MANUFACTURER).put("site", BuildConfig.SITE)).toString()

    @JavascriptInterface fun state(): String {
        guard()?.let { return it.toString() }
        val s = svc() ?: return JSONObject().put("state", "nosvc").toString()
        return (act.onMain { s.snapshot() } ?: JSONObject().put("state", "busy")).toString()
    }

    @JavascriptInterface fun start(metaJson: String?): String {
        guard()?.let { return it.toString() }
        if (!act.hasMic()) { act.askMic(); return JSONObject().put("ok", false).put("error", "permission").toString() }
        val s = svc() ?: return JSONObject().put("ok", false).put("error", "nosvc").toString()
        val m = try { JSONObject(metaJson ?: "{}") } catch (_: Exception) { JSONObject() }
        return (act.onMain { s.start(m, "page") } ?: JSONObject().put("ok", false).put("error", "timeout")).toString()
    }

    @JavascriptInterface fun pause(): String { guard()?.let { return it.toString() }; val s = svc() ?: return nosvc(); return (act.onMain { s.pause("page") } ?: timeout()).toString() }
    @JavascriptInterface fun resume(): String { guard()?.let { return it.toString() }; val s = svc() ?: return nosvc(); return (act.onMain { s.resume("page") } ?: timeout()).toString() }
    @JavascriptInterface fun stop(): String { guard()?.let { return it.toString() }; val s = svc() ?: return nosvc(); return (act.onMain { s.stop("page") } ?: timeout()).toString() }

    /** Les dictées arrêtées que le serveur n'a pas encore reçues (et les orphelines d'un arrêt brutal). */
    @JavascriptInterface fun pending(): String { guard()?.let { return it.toString() }; return Store.ready(act).toString() }

    @JavascriptInterface fun journal(id: String?): String {
        guard()?.let { return it.toString() }
        if (!Rules.isRecId(id)) return "[]"
        return Store.journal(act, id!!).toString()
    }

    @JavascriptInterface fun meta(id: String?): String {
        guard()?.let { return it.toString() }
        if (!Rules.isRecId(id)) return "{}"
        return (Store.readMeta(act, id!!) ?: JSONObject()).toString()
    }

    /** La page a reçu la réponse du serveur : le fichier natif est effacé. Jamais une dictée en cours. */
    @JavascriptInterface fun discard(id: String?): String {
        guard()?.let { return it.toString() }
        if (!Rules.isRecId(id)) return JSONObject().put("ok", false).put("error", "id").toString()
        if (svc()?.id == id) return JSONObject().put("ok", false).put("error", "recording").toString()
        return JSONObject().put("ok", Store.discard(act, id!!)).toString()
    }

    /** Une ligne de la page dans le journal natif (la verbose des deux côtés au même endroit). */
    @JavascriptInterface fun log(line: String?): String {
        guard()?.let { return it.toString() }
        android.util.Log.i("DicteePage", (line ?: "").take(500))
        return "{}"
    }

    @JavascriptInterface fun device(): String { guard()?.let { return it.toString() }; val s = svc() ?: return nosvc(); return (act.onMain { s.deviceInfo() } ?: JSONObject()).toString() }

    // ---------------- mise à jour ----------------
    @JavascriptInterface fun checkUpdate(): String { guard()?.let { return it.toString() }; return Updater.check(act).toString() }
    @JavascriptInterface fun installUpdate(): String {
        guard()?.let { return it.toString() }
        if ((svc()?.state ?: "idle") != "idle") return JSONObject().put("ok", false).put("error", "recording").toString()
        return Updater.install(act) { ev, data -> act.pageEvent(ev, data) }.toString()
    }

    /** Ouvre un réglage système : « battery » (exclure de l'optimisation), « notifications », « unknown » (sources inconnues). */
    @JavascriptInterface fun openSettings(what: String?): String {
        guard()?.let { return it.toString() }
        val i = when (what) {
            "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + act.packageName))
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, act.packageName)
            "unknown" -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName))
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + act.packageName))
        }
        return try { act.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); JSONObject().put("ok", true).toString() }
        catch (e: Exception) { JSONObject().put("ok", false).put("error", e.javaClass.simpleName).toString() }
    }

    private fun nosvc() = JSONObject().put("ok", false).put("error", "nosvc").toString()
    private fun timeout() = JSONObject().put("ok", false).put("error", "timeout")
}
