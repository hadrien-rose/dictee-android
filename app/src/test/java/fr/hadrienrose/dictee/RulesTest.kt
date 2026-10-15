package fr.hadrienrose.dictee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RulesTest {
    @Test fun seulNotreSiteVoitLePont() {
        assertTrue(Rules.isSiteHost("voice.hadrien-rose.com"))
        assertTrue(Rules.isSiteHost("VOICE.hadrien-rose.com"))
        assertFalse(Rules.isSiteHost("chat.hadrien-rose.com"))
        assertFalse(Rules.isSiteHost("voice.hadrien-rose.com.evil.net"))
        assertFalse(Rules.isSiteHost(null))
    }

    @Test fun navigationLimiteeAuSiteEtAuLoginCloudflare() {
        assertTrue(Rules.isAllowedHost("voice.hadrien-rose.com"))
        assertTrue(Rules.isAllowedHost("hadrien-rose.cloudflareaccess.com"))
        assertFalse(Rules.isAllowedHost("chat.hadrien-rose.com"))
        assertFalse(Rules.isAllowedHost("evilcloudflareaccess.com"))
        assertFalse(Rules.isAllowedHost("cloudflareaccess.com.evil.net"))
        assertFalse(Rules.isAllowedHost("accounts.google.com"))
        assertFalse(Rules.isAllowedHost(""))
    }

    @Test fun identifiantDeDictee() {
        assertTrue(Rules.isRecId("m2k9x1abq7z3"))
        assertFalse(Rules.isRecId("short"))
        assertFalse(Rules.isRecId("../../etc/passwd"))
        assertFalse(Rules.isRecId("ABCDEFGHIJ"))
        assertFalse(Rules.isRecId("abcdefgh/..")); assertFalse(Rules.isRecId(null))
        val id = Rules.newRecId()
        assertTrue(id, Rules.isRecId(id))
        assertTrue(Rules.newRecId() != Rules.newRecId())
    }

    @Test fun idLuDansLeChemin() {
        assertEquals("m2k9x1abq7z3", Rules.recIdFromPath("/__native/rec/m2k9x1abq7z3"))
        assertEquals("m2k9x1abq7z3", Rules.recIdFromPath("/__native/rec/m2k9x1abq7z3/"))
        assertNull(Rules.recIdFromPath("/__native/rec/../meta"))
        assertNull(Rules.recIdFromPath("/api/voice/list"))
        assertNull(Rules.recIdFromPath(null))
    }

    @Test fun fichierConfineAuDossierDesDictees() {
        val root = Files.createTempDirectory("dictee-rules").toFile()
        val f = Rules.partFile(root, "m2k9x1abq7z3", "2")
        assertNotNull(f)
        assertEquals(File(File(root, "m2k9x1abq7z3"), "part2.ogg").canonicalPath, f!!.canonicalPath)
        assertNull(Rules.partFile(root, "../../etc", "0"))
        assertNull(Rules.partFile(root, "m2k9x1abq7z3", "-1"))
        assertNull(Rules.partFile(root, "m2k9x1abq7z3", "abc"))
        assertNull(Rules.partFile(root, "m2k9x1abq7z3", "1000"))
        assertEquals("part0.ogg", Rules.partFile(root, "m2k9x1abq7z3", null)!!.name)
        root.deleteRecursively()
    }

    @Test fun versionPlusRecente() {
        assertTrue(Rules.isNewer(102, 101))
        assertFalse(Rules.isNewer(101, 101))
        assertFalse(Rules.isNewer(100, 101))
    }

    @Test fun niveauDuMicroEnDb() {
        assertEquals(-100.0, Rules.ampToDb(0), 0.0)
        assertEquals(0.0, Rules.ampToDb(32767), 0.05)
        assertEquals(-6.0, Rules.ampToDb(16384), 0.1)
        assertEquals(-60.2, Rules.ampToDb(32), 0.3)
    }

    @Test fun minuteur() {
        assertEquals("00:07", Rules.fmtSecs(7))
        assertEquals("24:17", Rules.fmtSecs(24 * 60 + 17))
        assertEquals("1:28:05", Rules.fmtSecs(3600 + 28 * 60 + 5))
    }
}
