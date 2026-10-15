package fr.hadrienrose.dictee

import java.io.File
import kotlin.math.log10

/**
 * Les règles PURES de l'app (testées sans Android, app/src/test) : l'origine autorisée, l'identifiant d'une dictée, le
 * chemin confiné d'un fichier servi à la page, la comparaison de versions, le niveau du micro en dB.
 */
object Rules {
    /** La page de Prisme qui vit dans l'app : la seule origine qui voit le pont JS et les fichiers natifs. */
    const val HOST = "voice.hadrien-rose.com"
    /** Le login Cloudflare Access (code par e-mail) : autorisé dans la WebView, sans pont JS. */
    private val LOGIN_HOSTS = listOf("cloudflareaccess.com")
    /** Le chemin, sur notre origine, où la page retrouve l'audio d'une dictée native : /__native/rec/<id>?part=k */
    const val NATIVE_PATH = "/__native/rec/"
    private val ID_RE = Regex("^[a-z0-9]{8,32}$")

    fun isSiteHost(host: String?): Boolean = host.equals(HOST, ignoreCase = true)

    /** Navigable dans la WebView : notre site, ou le login Cloudflare. Tout le reste s'ouvre dehors. */
    fun isAllowedHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        if (h == HOST) return true
        return LOGIN_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    fun isRecId(id: String?): Boolean = id != null && ID_RE.matches(id)

    /** L'identifiant d'une dictée native lu dans un chemin /__native/rec/<id>, ou null. */
    fun recIdFromPath(path: String?): String? {
        if (path == null || !path.startsWith(NATIVE_PATH)) return null
        val id = path.removePrefix(NATIVE_PATH).trimEnd('/')
        return if (isRecId(id)) id else null
    }

    /** Le fichier d'une partie d'une dictée, CONFINÉ au dossier des dictées (jamais un chemin libre), ou null. */
    fun partFile(root: File, id: String?, part: String?): File? {
        if (!isRecId(id)) return null
        val k = (part ?: "0").toIntOrNull() ?: return null
        if (k < 0 || k > 999) return null
        val f = File(File(root, id), "part$k.ogg")
        val rootC = root.canonicalFile.path + File.separator
        return if (f.canonicalFile.path.startsWith(rootC)) f else null
    }

    /** Vrai si la version publiée (code) est plus récente que l'installée. */
    fun isNewer(latestCode: Int, installedCode: Int): Boolean = latestCode > installedCode

    /** Niveau du micro : l'amplitude max (0..32767) de MediaRecorder en dB pleine échelle, -100 pour le silence. */
    fun ampToDb(amp: Int): Double {
        if (amp <= 0) return -100.0
        val db = 20.0 * log10(amp.toDouble() / 32767.0)
        return if (db < -100.0) -100.0 else Math.round(db * 10.0) / 10.0
    }

    /** Un identifiant de dictée : l'instant en base 36 + un suffixe aléatoire, lettres et chiffres seulement. */
    fun newRecId(now: Long = System.currentTimeMillis()): String {
        val a = java.lang.Long.toString(now, 36)
        val b = java.lang.Long.toString((Math.random() * 36.0 * 36 * 36 * 36 * 36 * 36).toLong(), 36).padStart(6, '0')
        return (a + b).lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(32)
    }

    fun fmtSecs(s: Long): String {
        val h = s / 3600; val m = (s % 3600) / 60; val x = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, x) else String.format("%02d:%02d", m, x)
    }
}
