package fr.hadrienrose.dictee

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Les dictées natives sur le disque de l'app (stockage privé, jamais lisible par une autre app) :
 *   files/rec/<id>/meta.json     — nom, notes, cc, instants, état (recording | paused | ready), parties, tailles
 *   files/rec/<id>/part0.ogg …   — l'audio (une partie de plus à chaque relance de l'enregistreur)
 *   files/rec/<id>/journal.json  — le journal natif
 * Une dictée n'est effacée que sur ordre de la page (`discard`), après réception par le serveur.
 */
object Store {
    fun root(ctx: Context): File = File(ctx.filesDir, "rec").apply { mkdirs() }
    fun dir(ctx: Context, id: String): File = File(root(ctx), id)
    fun metaFile(ctx: Context, id: String): File = File(dir(ctx, id), "meta.json")

    fun readMeta(ctx: Context, id: String): JSONObject? = try {
        val f = metaFile(ctx, id)
        if (f.isFile) JSONObject(f.readText()) else null
    } catch (_: Exception) { null }

    fun writeMeta(ctx: Context, id: String, meta: JSONObject) {
        val d = dir(ctx, id).apply { mkdirs() }
        val tmp = File(d, "meta.json.tmp")
        tmp.writeText(meta.toString())
        if (!tmp.renameTo(File(d, "meta.json"))) File(d, "meta.json").writeText(meta.toString())
    }

    /** Les parties d'une dictée, dans l'ordre, avec leur taille (les fichiers vides sont ignorés). */
    fun parts(ctx: Context, id: String): List<File> {
        val d = dir(ctx, id)
        val out = ArrayList<File>()
        var k = 0
        while (k < 1000) {
            val f = File(d, "part$k.ogg")
            if (!f.isFile) { if (k > 0 && !File(d, "part${k + 1}.ogg").isFile) break }
            else if (f.length() > 0) out.add(f)
            k++
            if (k > 0 && !File(d, "part$k.ogg").isFile) break
        }
        return out
    }

    /** Les dictées PRÊTES (arrêtées, pas encore reçues par le serveur), la plus ancienne d'abord. */
    fun ready(ctx: Context): JSONArray {
        val out = ArrayList<JSONObject>()
        root(ctx).listFiles()?.forEach { d ->
            if (!d.isDirectory || !Rules.isRecId(d.name)) return@forEach
            val m = readMeta(ctx, d.name) ?: return@forEach
            if (m.optString("state") != "ready") return@forEach
            val ps = parts(ctx, d.name)
            if (ps.isEmpty()) return@forEach
            m.put("id", d.name).put("parts", ps.size).put("size", ps.sumOf { it.length() })
            out.add(m)
        }
        out.sortBy { it.optLong("t0") }
        return JSONArray(out)
    }

    fun journal(ctx: Context, id: String): JSONArray = try {
        val f = File(dir(ctx, id), "journal.json")
        if (f.isFile) JSONArray(f.readText()) else JSONArray()
    } catch (_: Exception) { JSONArray() }

    fun discard(ctx: Context, id: String): Boolean {
        if (!Rules.isRecId(id)) return false
        return dir(ctx, id).deleteRecursively()
    }

    /** Une dictée laissée « recording » par un arrêt brutal (app tuée, batterie) devient prête : rien ne se perd. */
    fun adoptOrphans(ctx: Context, exceptId: String?): Int {
        var n = 0
        root(ctx).listFiles()?.forEach { d ->
            if (!d.isDirectory || !Rules.isRecId(d.name) || d.name == exceptId) return@forEach
            val m = readMeta(ctx, d.name) ?: return@forEach
            val st = m.optString("state")
            if (st == "recording" || st == "paused") {
                val ps = parts(ctx, d.name)
                if (ps.isEmpty()) { d.deleteRecursively(); return@forEach }
                m.put("state", "ready").put("orphan", true)
                writeMeta(ctx, d.name, m)
                n++
            }
        }
        return n
    }
}
