package fr.hadrienrose.dictee

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject
import java.io.File

/**
 * L'enregistreur de Dictée : un SERVICE DE PREMIER PLAN de type microphone — ce qu'une page web n'a pas, et la raison
 * des dictées coupées des 14-15/10 (Android coupe le micro d'une app cachée ; le 15/10 : 4 sur 4, −91 dB dès que
 * l'app passe en arrière-plan). Ici, écran verrouillé ou pas, appel ou pas, l'enregistrement CONTINUE : aucune pause
 * automatique (arbitrage d'Hadrien du 15/10 : « jamais coupé par un appel ») ; pendant un appel, Android donne du
 * silence aux autres apps, le son reprend au raccrochage, le journal le note (mode audio). Si le système arrête tout
 * de même l'encodeur (onError), une NOUVELLE PARTIE repart aussitôt (part1.ogg, part2.ogg…) : le serveur les recolle.
 * Seul « Arrêter » (la page, ou la notification) met fin à la dictée.
 */
class RecorderService : Service() {
    companion object {
        const val CHANNEL = "dictee_rec"
        const val NOTIF_ID = 7
        const val ACTION_PAUSE = "fr.hadrienrose.dictee.PAUSE"
        const val ACTION_RESUME = "fr.hadrienrose.dictee.RESUME"
        const val ACTION_STOP = "fr.hadrienrose.dictee.STOP"
        const val BEAT_MS = 15_000L
        const val RESTART_MS = 1_500L
        const val RESTART_MAX = 60
        const val SAMPLE_RATE = 48_000
        const val BIT_RATE = 32_000
    }

    inner class LocalBinder : Binder() { val service: RecorderService get() = this@RecorderService }
    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())

    /** idle | recording | paused */
    @Volatile var state: String = "idle"; private set
    @Volatile var id: String? = null; private set
    private var recorder: MediaRecorder? = null
    private var journal: Journal? = null
    private var meta: JSONObject? = null
    private var t0 = 0L
    private var accMs = 0L
    private var segT0 = 0L
    private var part = 0
    private var restarts = 0
    private var restarting = false
    private var wake: PowerManager.WakeLock? = null
    private var lastAmp = 0
    private var lastAmpAt = 0L
    /** La page (via l'activité) écoute : stopped(id), error(msg), beat */
    @Volatile var listener: ((String, JSONObject) -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // une dictée laissée en cours par un arrêt brutal devient « prête » : la page la renverra
        val n = Store.adoptOrphans(this, null)
        if (n > 0) android.util.Log.i("Dictee", "orphelines adoptées : $n")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pause("notification")
            ACTION_RESUME -> resume("notification")
            ACTION_STOP -> stop("notification")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        journal?.ev("service-destroy", JSONObject().put("state", state))
        // le système tue le service en pleine dictée : on ferme proprement ce qu'on peut, le fichier reste, adopté au prochain démarrage
        if (state != "idle") try { finishRecorder(abandon = false) } catch (_: Exception) { }
        super.onDestroy()
    }

    // ---------------- commandes (thread principal) ----------------

    fun start(m: JSONObject, from: String): JSONObject {
        if (state != "idle") return JSONObject().put("ok", false).put("error", "busy").put("id", id)
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return JSONObject().put("ok", false).put("error", "permission")
        val newId = Rules.newRecId()
        val dir = Store.dir(this, newId).apply { mkdirs() }
        t0 = System.currentTimeMillis(); accMs = 0; part = 0; restarts = 0; lastAmp = 0
        journal = Journal(File(dir, "journal.json"), t0)
        meta = JSONObject().put("id", newId).put("t0", t0).put("state", "recording").put("type", mimeType())
            .put("name", m.optString("name")).put("notes", m.optString("notes")).put("cc", m.optString("cc"))
        id = newId
        Store.writeMeta(this, newId, meta!!)
        try {
            startForegroundNotif()
        } catch (e: Exception) {
            journal?.ev("err", JSONObject().put("where", "foreground").put("msg", e.toString().take(200)))
            id = null; state = "idle"
            return JSONObject().put("ok", false).put("error", "foreground: " + e.javaClass.simpleName)
        }
        acquireWake()
        journal?.ev("start", deviceInfo().put("from", from))
        return try {
            startSegment()
            state = "recording"; segT0 = System.currentTimeMillis()
            main.removeCallbacks(beat); main.postDelayed(beat, BEAT_MS)
            updateNotif()
            JSONObject().put("ok", true).put("id", newId).put("t0", t0)
        } catch (e: Exception) {
            journal?.ev("err", JSONObject().put("where", "start").put("msg", e.toString().take(200)))
            releaseRecorder()
            stopForegroundCompat(); releaseWake()
            Store.discard(this, newId)
            id = null; state = "idle"
            JSONObject().put("ok", false).put("error", "recorder: " + e.javaClass.simpleName + " " + (e.message ?: "").take(80))
        }
    }

    fun pause(from: String): JSONObject {
        if (state != "recording") return JSONObject().put("ok", false).put("error", "state:$state")
        try { recorder?.pause() } catch (e: Exception) { journal?.ev("err", JSONObject().put("where", "pause").put("msg", e.toString().take(200))) }
        accMs += System.currentTimeMillis() - segT0
        state = "paused"
        journal?.ev("pause", JSONObject().put("from", from).put("secs", secs()))
        meta?.put("state", "paused"); Store.writeMeta(this, id!!, meta!!)
        updateNotif()
        return JSONObject().put("ok", true).put("secs", secs())
    }

    fun resume(from: String): JSONObject {
        if (state != "paused") return JSONObject().put("ok", false).put("error", "state:$state")
        try { recorder?.resume() } catch (e: Exception) {
            journal?.ev("err", JSONObject().put("where", "resume").put("msg", e.toString().take(200)))
            restartSegment("resume-failed")
        }
        segT0 = System.currentTimeMillis()
        state = "recording"
        journal?.ev("resume", JSONObject().put("from", from).put("secs", secs()))
        meta?.put("state", "recording"); Store.writeMeta(this, id!!, meta!!)
        updateNotif()
        return JSONObject().put("ok", true).put("secs", secs())
    }

    fun stop(from: String): JSONObject {
        if (state == "idle") return JSONObject().put("ok", false).put("error", "state:idle")
        val rid = id!!
        if (state == "recording") accMs += System.currentTimeMillis() - segT0
        val s = secs()
        journal?.ev("stop", JSONObject().put("from", from).put("secs", s))
        finishRecorder(abandon = false)
        val ps = Store.parts(this, rid)
        val m = meta ?: JSONObject().put("id", rid).put("t0", t0)
        m.put("state", "ready").put("secs", s).put("parts", ps.size).put("size", ps.sumOf { it.length() })
            .put("stopped", System.currentTimeMillis())
        meta = m
        Store.writeMeta(this, rid, m)
        val out = JSONObject().put("ok", true).put("id", rid).put("secs", s).put("parts", ps.size)
            .put("size", ps.sumOf { it.length() }).put("type", mimeType())
        state = "idle"; id = null
        main.removeCallbacks(beat)
        stopForegroundCompat(); releaseWake()
        listener?.invoke("stopped", out)
        stopSelf()
        return out
    }

    /** L'état vivant, lu par la page chaque seconde (minuteur, témoin de signal, mode appel) et par le battement. */
    fun snapshot(): JSONObject {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val amp = readAmp()
        val o = JSONObject().put("state", state).put("id", id).put("secs", secs()).put("part", part)
            .put("amp", amp).put("db", Rules.ampToDb(amp)).put("mode", am.mode).put("inCall", am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION)
            .put("micMute", am.isMicrophoneMute).put("restarts", restarts)
        if (id != null) o.put("bytes", Store.parts(this, id!!).sumOf { it.length() }).put("t0", t0).put("type", mimeType())
        return o
    }

    fun deviceInfo(): JSONObject {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return JSONObject().put("app", BuildConfig.VERSION_NAME).put("code", BuildConfig.VERSION_CODE)
            .put("android", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE)
            .put("model", Build.MODEL).put("brand", Build.MANUFACTURER)
            .put("source", "MIC").put("format", if (Build.VERSION.SDK_INT >= 29) "ogg/opus" else "m4a/aac")
            .put("rate", SAMPLE_RATE).put("bitrate", BIT_RATE).put("mode", am.mode).put("micMute", am.isMicrophoneMute)
            .put("battery", battery()).put("ignoreBattOpt", pm.isIgnoringBatteryOptimizations(packageName))
            .put("notifs", nm.areNotificationsEnabled()).put("screenOn", pm.isInteractive)
    }

    // ---------------- l'encodeur ----------------

    private fun mimeType() = if (Build.VERSION.SDK_INT >= 29) "audio/ogg" else "audio/mp4"

    private fun startSegment() {
        val rid = id ?: throw IllegalStateException("no id")
        val f = File(Store.dir(this, rid), "part$part.ogg")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)   // le micro BRUT, jamais VOICE_COMMUNICATION (traitement d'appel)
        if (Build.VERSION.SDK_INT >= 29) {
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
        } else {
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        }
        r.setAudioChannels(1)
        r.setAudioSamplingRate(SAMPLE_RATE)
        r.setAudioEncodingBitRate(BIT_RATE)
        r.setOutputFile(f.absolutePath)
        r.setOnErrorListener { _, what, extra ->
            journal?.ev("recorder-error", JSONObject().put("what", what).put("extra", extra).put("part", part))
            main.post { restartSegment("error:$what") }
        }
        r.setOnInfoListener { _, what, extra -> journal?.ev("recorder-info", JSONObject().put("what", what).put("extra", extra)) }
        r.prepare()
        r.start()
        recorder = r
        lastAmpAt = System.currentTimeMillis()
        journal?.ev("segment", JSONObject().put("part", part).put("file", f.name))
    }

    /** L'encodeur s'est arrêté sans qu'on le demande : une nouvelle partie, tout de suite, tant que la dictée dure. */
    private fun restartSegment(why: String) {
        if (state == "idle" || restarting) return
        restarting = true
        releaseRecorder()
        restarts++
        if (restarts > RESTART_MAX) {
            journal?.ev("restart-abandon", JSONObject().put("why", why).put("restarts", restarts))
            restarting = false
            listener?.invoke("error", JSONObject().put("error", "recorder-dead"))
            return
        }
        main.postDelayed({
            restarting = false
            if (state == "idle") return@postDelayed
            part++
            try {
                startSegment()
                if (state == "paused") try { recorder?.pause() } catch (_: Exception) { }
                journal?.ev("restart-ok", JSONObject().put("why", why).put("part", part).put("restarts", restarts))
            } catch (e: Exception) {
                journal?.ev("restart-fail", JSONObject().put("why", why).put("part", part).put("msg", e.toString().take(200)))
                part--   // le fichier raté n'existe pas : on garde la numérotation serrée
                main.post { restartSegment(why) }
            }
        }, RESTART_MS)
    }

    private fun finishRecorder(abandon: Boolean) {
        val r = recorder ?: return
        recorder = null
        try { if (state == "paused") r.resume() } catch (_: Exception) { }
        try { r.stop() } catch (e: Exception) {
            // un stop trop tôt après start : la partie est vide ou invalide, on la retire
            journal?.ev("stop-error", JSONObject().put("msg", e.toString().take(200)).put("part", part))
            val f = File(Store.dir(this, id ?: ""), "part$part.ogg")
            if (f.isFile && f.length() < 2048) f.delete()
        }
        try { r.release() } catch (_: Exception) { }
        if (abandon) journal?.ev("abandon")
    }

    private fun releaseRecorder() {
        val r = recorder ?: return
        recorder = null
        try { r.stop() } catch (_: Exception) { }
        try { r.release() } catch (_: Exception) { }
    }

    private fun readAmp(): Int {
        val r = recorder ?: return 0
        if (state != "recording") return 0
        return try { val a = r.maxAmplitude; lastAmp = a; lastAmpAt = System.currentTimeMillis(); a } catch (_: Exception) { 0 }
    }

    private fun secs(): Long = (accMs + (if (state == "recording") System.currentTimeMillis() - segT0 else 0L)) / 1000

    // ---------------- battement, veille CPU, notification ----------------

    private val beat = object : Runnable {
        override fun run() {
            if (state == "idle") return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val s = snapshot()
            val rt = Runtime.getRuntime()
            journal?.ev("beat", JSONObject().put("secs", s.optLong("secs")).put("bytes", s.optLong("bytes")).put("db", s.optDouble("db"))
                .put("mode", s.optInt("mode")).put("inCall", s.optBoolean("inCall")).put("micMute", s.optBoolean("micMute"))
                .put("screenOn", pm.isInteractive).put("battery", battery()).put("part", part).put("restarts", restarts)
                .put("memFree", (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / 1048576).put("state", state))
            updateNotif()
            main.postDelayed(this, BEAT_MS)
        }
    }

    private fun battery(): Int = try {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) { -1 }

    private fun acquireWake() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dictee:rec").also { it.acquire(12 * 3600 * 1000L) }
            journal?.ev("wake-ok")
        } catch (e: Exception) { journal?.ev("wake-fail", JSONObject().put("msg", e.toString().take(100))) }
    }

    private fun releaseWake() { try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }; wake = null }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            ch.description = getString(R.string.notif_channel_desc)
            ch.setShowBadge(false); ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
    }

    private fun action(act: String, label: String): Notification.Action {
        val pi = PendingIntent.getService(this, act.hashCode(), Intent(this, RecorderService::class.java).setAction(act),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null, label, pi).build()
    }

    private fun buildNotif(): Notification {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inCall = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
        val title = when {
            state == "paused" -> getString(R.string.notif_paused)
            inCall -> getString(R.string.notif_in_call)
            else -> getString(R.string.notif_recording)
        }
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val name = meta?.optString("name").orEmpty().ifBlank { "Sans objet" }
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif).setContentTitle(title + " · " + Rules.fmtSecs(secs()))
            .setContentText(name).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_SERVICE)
        if (state == "recording") b.addAction(action(ACTION_PAUSE, getString(R.string.act_pause)))
        else b.addAction(action(ACTION_RESUME, getString(R.string.act_resume)))
        b.addAction(action(ACTION_STOP, getString(R.string.act_stop)))
        return b.build()
    }

    private fun startForegroundNotif() {
        val n = buildNotif()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
    }

    private fun updateNotif() {
        if (state == "idle") return
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotif()) } catch (_: Exception) { }
    }

    private fun stopForegroundCompat() {
        try { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true) } catch (_: Exception) { }
    }
}
