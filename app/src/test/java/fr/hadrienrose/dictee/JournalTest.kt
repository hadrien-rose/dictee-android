package fr.hadrienrose.dictee

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class JournalTest {
    @Test fun ecritSurDisqueAChaqueEvenementEtCapeLesBattements() {
        val dir = Files.createTempDirectory("dictee-journal").toFile()
        val f = java.io.File(dir, "journal.json")
        val j = Journal(f, t0 = System.currentTimeMillis() - 1500)
        j.ev("start", JSONObject().put("source", "MIC"))
        assertTrue(f.isFile)
        val first = org.json.JSONArray(f.readText()).getJSONObject(0)
        assertEquals("start", first.getString("ev"))
        assertTrue(first.getDouble("t") >= 1.4)
        assertEquals("MIC", first.getJSONObject("x").getString("source"))
        for (i in 0 until Journal.MAX + 20) j.ev("beat", JSONObject().put("i", i))
        j.ev("stop")
        assertEquals(Journal.MAX, j.size())
        val arr = j.snapshot()
        assertEquals("start", arr.getJSONObject(0).getString("ev"))        // un geste n'est jamais sacrifié
        assertEquals("stop", arr.getJSONObject(arr.length() - 1).getString("ev"))
        dir.deleteRecursively()
    }
}
