package com.fxsound.audio.opra

import java.io.File
import java.io.IOException

/**
 * File-level safety for the downloaded OPRA dump (C1/C2/C4).
 *
 * Kept free of Android APIs so the rules can be tested on the JVM with temp directories:
 *  - a download is validated (JSON object, plausible size and line count) *before* it is
 *    allowed to replace a working cache;
 *  - the previous file is kept as `.bak` and restored when the current one cannot be
 *    indexed;
 *  - a captive-portal HTML page or a truncated transfer is rejected instead of poisoning
 *    the cache.
 */
internal object OpraCache {

    const val DATABASE_NAME = "database_v1.jsonl"
    const val TMP_SUFFIX = ".tmp"
    const val BACKUP_SUFFIX = ".bak"

    /** The real dump is 12.6 MB / 19.5 k lines; these bounds are far below it. */
    const val MIN_LINES = 1000
    const val MIN_BYTES = 256L * 1024L

    fun database(dir: File): File = File(dir, DATABASE_NAME)
    fun temporary(dir: File): File = File(dir, DATABASE_NAME + TMP_SUFFIX)
    fun backup(dir: File): File = File(dir, DATABASE_NAME + BACKUP_SUFFIX)

    /** Null when [file] looks like the published JSONL dump; otherwise the reason it does not. */
    fun validate(file: File): String? {
        if (!file.isFile) return "missing"
        if (file.length() < MIN_BYTES) return "too small (${file.length()} bytes)"

        val first = try {
            file.inputStream().use { input ->
                var b = input.read()
                while (b != -1 && Character.isWhitespace(b.toChar())) b = input.read()
                b
            }
        } catch (e: IOException) {
            return "unreadable (${e.message})"
        }
        if (first == -1 || first.toChar() != '{') {
            return "not a JSON object (first byte ${if (first == -1) "EOF" else "'${first.toChar()}'"}), " +
                "probably a captive-portal page"
        }

        var lines = 0
        try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                while (lines < MIN_LINES) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) lines++
                }
            }
        } catch (e: IOException) {
            return "unreadable (${e.message})"
        }
        if (lines < MIN_LINES) return "truncated (only $lines lines)"
        return null
    }

    /**
     * Promotes `database_v1.jsonl.tmp` to the live cache, keeping the previous file as
     * `.bak`. Returns false (leaving the old cache in place) if the swap fails.
     */
    fun promote(dir: File): Boolean {
        val tmp = temporary(dir)
        val live = database(dir)
        val bak = backup(dir)
        if (!tmp.isFile) return false

        bak.delete()
        if (live.isFile && !live.renameTo(bak)) return false
        if (!tmp.renameTo(live)) {
            // Put the previous cache back rather than leaving the app with nothing.
            if (bak.isFile) bak.renameTo(live)
            return false
        }
        return true
    }

    /** Restores `.bak` over a damaged live file. */
    fun restoreBackup(dir: File): Boolean {
        val live = database(dir)
        val bak = backup(dir)
        if (!bak.isFile) return false
        if (live.isFile && !live.delete()) return false
        return bak.renameTo(live)
    }

    fun deleteAll(dir: File) {
        database(dir).delete()
        temporary(dir).delete()
        backup(dir).delete()
        File(dir, "version_info").delete()
    }
}
