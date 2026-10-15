package fr.hadrienrose.dictee

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * La mise à jour « en deux touchers » : à l'ouverture (une fois par jour, ou à la demande de la page) l'app lit
 * `version.json` de la DERNIÈRE release GitHub (dépôt public, sans jeton) ; une version plus récente → la page le dit ;
 * « Mettre à jour » → l'APK est téléchargée dans le cache de l'app, son SHA-256 comparé à celui publié ET sa signature
 * comparée à celle de l'app installée (une APK étrangère est refusée), puis PackageInstaller affiche l'écran système
 * « Installer ». Jamais pendant une dictée.
 */
object Updater {
    private const val PREF = "dictee"
    private const val KEY_LAST = "update_checked"
    private const val KEY_INFO = "update_info"
    private const val DAY_MS = 24 * 3600 * 1000L
    @Volatile private var busy = false

    private fun installed(ctx: Context) = JSONObject().put("code", BuildConfig.VERSION_CODE).put("name", BuildConfig.VERSION_NAME)

    /** Vérifie au plus une fois par jour, en arrière-plan ; `cb` reçoit l'info s'il y a une mise à jour. */
    fun checkLater(ctx: Context, cb: (JSONObject) -> Unit) {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val last = p.getLong(KEY_LAST, 0)
        val cached = p.getString(KEY_INFO, null)
        if (System.currentTimeMillis() - last < DAY_MS && cached != null) {
            try { val j = JSONObject(cached); if (j.optBoolean("available")) cb(j) } catch (_: Exception) { }
            return
        }
        Thread { try { val r = check(ctx); if (r.optBoolean("available")) cb(r) } catch (_: Exception) { } }.start()
    }

    /** Lit version.json (synchrone, à appeler hors du thread principal — le pont JS l'est). */
    fun check(ctx: Context): JSONObject {
        val out = JSONObject().put("ok", true).put("current", installed(ctx)).put("available", false)
        try {
            val txt = get(BuildConfig.UPDATE_JSON, 15_000)
            val j = JSONObject(txt)
            val code = j.optInt("versionCode", 0)
            val latest = JSONObject().put("code", code).put("name", j.optString("versionName")).put("apk", j.optString("apk"))
                .put("sha256", j.optString("sha256")).put("notes", j.optString("notes").take(2000)).put("size", j.optLong("size"))
            out.put("latest", latest).put("available", Rules.isNewer(code, BuildConfig.VERSION_CODE))
        } catch (e: Exception) {
            out.put("ok", false).put("error", e.javaClass.simpleName + ": " + (e.message ?: "").take(120))
        }
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        p.edit().putLong(KEY_LAST, System.currentTimeMillis()).putString(KEY_INFO, out.toString()).apply()
        return out
    }

    /** Télécharge, vérifie, installe. Rend tout de suite {ok:true, started:true} ; la suite arrive par `ev` (update-progress / update-error / update-ready). */
    fun install(ctx: Context, ev: (String, JSONObject) -> Unit): JSONObject {
        if (busy) return JSONObject().put("ok", false).put("error", "busy")
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            return JSONObject().put("ok", false).put("error", "unknown-sources")
        }
        val info = try { JSONObject(ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_INFO, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val latest = info.optJSONObject("latest") ?: return JSONObject().put("ok", false).put("error", "no-info")
        if (!Rules.isNewer(latest.optInt("code"), BuildConfig.VERSION_CODE)) return JSONObject().put("ok", false).put("error", "up-to-date")
        val apkUrl = latest.optString("apk"); val sha = latest.optString("sha256").lowercase()
        if (!apkUrl.startsWith("https://github.com/hadrien-rose/dictee-android/") || sha.length != 64)
            return JSONObject().put("ok", false).put("error", "bad-release")
        busy = true
        Thread {
            try {
                val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
                val f = File(dir, "dictee.apk")
                download(apkUrl, f) { done, total -> ev("update-progress", JSONObject().put("done", done).put("total", total)) }
                val got = sha256(f)
                if (got != sha) throw IllegalStateException("sha256 attendu $sha, reçu $got")
                if (!sameSigner(ctx, f)) throw IllegalStateException("signature différente de l'app installée")
                ev("update-progress", JSONObject().put("done", f.length()).put("total", f.length()).put("verified", true))
                commit(ctx, f)
                ev("update-ready", JSONObject().put("name", latest.optString("name")))
            } catch (e: Exception) {
                ev("update-error", JSONObject().put("error", (e.message ?: e.javaClass.simpleName).take(200)))
            } finally { busy = false }
        }.start()
        return JSONObject().put("ok", true).put("started", true)
    }

    private fun get(url: String, timeout: Int): String {
        val c = open(url, timeout)
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP " + c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Suit les redirections (github.com → objects.githubusercontent.com), HTTPS seulement. */
    private fun open(url: String, timeout: Int): HttpURLConnection {
        var u = url
        repeat(6) {
            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeout; readTimeout = timeout; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "DicteeApp/" + BuildConfig.VERSION_NAME); setRequestProperty("Accept", "*/*")
            }
            val code = c.responseCode
            if (code in 301..308) {
                val loc = c.getHeaderField("Location") ?: throw IllegalStateException("redirection sans Location")
                c.disconnect()
                u = if (loc.startsWith("http")) loc else URL(URL(u), loc).toString()
                if (!u.startsWith("https://")) throw IllegalStateException("redirection non https")
                return@repeat
            }
            return c
        }
        throw IllegalStateException("trop de redirections")
    }

    private fun download(url: String, to: File, progress: (Long, Long) -> Unit) {
        val c = open(url, 30_000)
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP " + c.responseCode)
            val total = c.contentLengthLong
            var done = 0L; var lastAt = 0L
            c.inputStream.use { ins -> to.outputStream().use { out ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    val now = System.currentTimeMillis()
                    if (now - lastAt > 400) { lastAt = now; progress(done, total) }
                }
            } }
            progress(done, total)
        } finally { c.disconnect() }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins -> val buf = ByteArray(65536); while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** La signature de l'APK téléchargée = celle de l'app installée (sinon Android refuserait, et on ne tente même pas). */
    @Suppress("DEPRECATION")
    private fun sameSigner(ctx: Context, apk: File): Boolean {
        val pm = ctx.packageManager
        return if (Build.VERSION.SDK_INT >= 28) {
            val a = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo?.apkContentsSigners ?: return false
            val b = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners ?: return false
            a.isNotEmpty() && a.map { it.toCharsString() }.toSet() == b.map { it.toCharsString() }.toSet()
        } else {
            val a = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)?.signatures ?: return false
            val b = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures ?: return false
            a.isNotEmpty() && a.map { it.toCharsString() }.toSet() == b.map { it.toCharsString() }.toSet()
        }
    }

    private fun commit(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val sid = pi.createSession(params)
        pi.openSession(sid).use { s ->
            s.openWrite("dictee.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val intent = Intent(ctx, InstallReceiver::class.java).setAction("fr.hadrienrose.dictee.INSTALL")
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, sid, intent, flags).intentSender)
        }
    }
}

/** Reçoit le verdict de PackageInstaller : l'écran système « Installer » à montrer, puis le succès ou l'échec. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                try { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
            }
            PackageInstaller.STATUS_SUCCESS -> android.util.Log.i("Dictee", "mise à jour installée")
            else -> android.util.Log.w("Dictee", "installation : " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
