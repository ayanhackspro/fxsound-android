package com.fxsound.audio.opra

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * C1/C2: a download may only replace a working cache after validation, and a damaged
 * cache must recover from `.bak` instead of bricking the catalogue until app data is
 * cleared. Runs on plain temp directories — no Android context involved.
 */
class OpraCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dump(dir: File, lines: Int = OpraCache.MIN_LINES + 5): File {
        // One filler line per iteration, padded past MIN_BYTES by the comment body.
        val pad = "x".repeat(320)
        return File(dir, OpraCache.DATABASE_NAME).apply {
            bufferedWriter().use { w ->
                repeat(lines) { w.write("""{"type":"vendor","id":"v$it","data":{"name":"$pad"}}""" + "\n") }
            }
        }
    }

    @Test
    fun `validates the real dump shape`() {
        val file = dump(tmp.newFolder("ok"))
        assertNull("a JSONL dump with enough lines must be accepted", OpraCache.validate(file))
    }

    @Test
    fun `rejects a captive portal page`() {
        val file = File(tmp.newFolder("portal"), OpraCache.DATABASE_NAME)
        file.writeText("<html><body>Sign in to Wi-Fi to continue</body></html>".repeat(20000))
        val reason = OpraCache.validate(file)
        assertTrue("HTML must be rejected, got: $reason", reason!!.contains("JSON object"))
    }

    @Test
    fun `rejects a truncated transfer`() {
        val dir = tmp.newFolder("short")
        val file = File(dir, OpraCache.DATABASE_NAME)
        file.writeText("""{"type":"vendor","id":"a","data":{"name":"A"}}""" + "\n")
        // Small file: caught by the size bound before the line count.
        assertTrue(OpraCache.validate(file)!!.contains("too small"))
    }

    @Test
    fun `rejects a large file with too few lines`() {
        val dir = tmp.newFolder("few")
        val file = File(dir, OpraCache.DATABASE_NAME)
        // Greater than MIN_BYTES but only a handful of lines: a stalled/partial transfer.
        file.writeText("{" + "x".repeat((OpraCache.MIN_BYTES + 1).toInt()) + "}\n")
        val reason = OpraCache.validate(file)
        assertTrue("got: $reason", reason!!.contains("truncated"))
    }

    @Test
    fun `promote keeps the previous cache as backup`() {
        val dir = tmp.newFolder("promote")
        val old = dump(dir)
        val oldContent = old.readText()
        val fresh = OpraCache.temporary(dir).apply {
            writeText(oldContent.replace("\"v0\"", "\"new0\""))
            setLastModified(System.currentTimeMillis() + 5000)
        }

        assertTrue(OpraCache.promote(dir))
        assertFalse("the tmp file must be gone after promotion", fresh.exists())
        assertTrue(OpraCache.database(dir).readText().contains("\"new0\""))
        assertTrue(OpraCache.backup(dir).isFile)
        assertEquals("the previous cache must be recoverable", oldContent, OpraCache.backup(dir).readText())
    }

    @Test
    fun `restoring the backup recovers from a corrupt live file`() {
        val dir = tmp.newFolder("restore")
        val good = dump(dir).readText()
        // Simulate a promotion that left a broken live file behind.
        OpraCache.backup(dir).writeText(good)
        OpraCache.database(dir).writeText("<html>truncated</html>")

        assertTrue("the backup must be a valid fallback", OpraCache.restoreBackup(dir))
        assertEquals(good, OpraCache.database(dir).readText())
        assertNull(OpraCache.validate(OpraCache.database(dir)))
    }

    @Test
    fun `deleteAll removes database, temp and backup`() {
        val dir = tmp.newFolder("delete")
        dump(dir)
        OpraCache.temporary(dir).writeText("tmp")
        OpraCache.backup(dir).writeText("bak")
        File(dir, "version_info").writeText("etag = abc")

        OpraCache.deleteAll(dir)

        assertTrue(OpraCache.database(dir).exists().not())
        assertTrue(OpraCache.temporary(dir).exists().not())
        assertTrue(OpraCache.backup(dir).exists().not())
        assertTrue(File(dir, "version_info").exists().not())
    }
}
