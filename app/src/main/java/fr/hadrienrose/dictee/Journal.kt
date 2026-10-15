package fr.hadrienrose.dictee

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Le journal d'une dictée côté natif (la « verbose » demandée le 15/10) : un événement horodaté par geste, erreur ou
 * battement, écrit sur disque à chaque ajout (`journal.json` à côté de l'audio) pour survivre à un arrêt brutal, et
 * envoyé par la page avec la dictée (`journal.native`). Capé à MAX entrées : le dernier événement remplace alors le
 * plus ancien battement.
 */
class Journal(private val file: File?, private val t0: Long = System.currentTimeMillis()) {
    companion object { const val MAX = 5000 }
    private val items = JSONArray()

    @Synchronized
    fun ev(name: String, x: JSONObject? = null) {
        val o = JSONObject().put("t", (System.currentTimeMillis() - t0) / 1000.0).put("ev", name)
        if (x != null && x.length() > 0) o.put("x", x)
        if (items.length() >= MAX) {
            // on sacrifie le plus ancien battement (jamais un geste ni une erreur)
            var k = -1
            for (i in 0 until items.length()) if (items.getJSONObject(i).optString("ev") == "beat") { k = i; break }
            if (k >= 0) items.remove(k) else return
        }
        items.put(o)
        flush()
    }

    @Synchronized
    fun snapshot(): JSONArray = JSONArray(items.toString())

    @Synchronized
    fun size(): Int = items.length()

    private fun flush() {
        val f = file ?: return
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(items.toString())
            if (!tmp.renameTo(f)) f.writeText(items.toString())
        } catch (_: Exception) { }
    }
}
