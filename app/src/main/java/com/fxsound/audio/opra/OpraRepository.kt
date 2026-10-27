package com.fxsound.audio.opra

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads, caches, indexes and applies the OPRA headphone-tuning dataset.
 *
 * Data-usage policy (per docs/CONSUMING and the OPRA licence):
 *  - The dump is fetched from the official mirror `opra.roonlabs.net` (open-source /
 *    non-commercial guidance) into app-private storage; it is never re-shared to other
 *    apps, which keeps us out of "redistributing the dataset" territory while the
 *    CC BY-SA 4.0 redistribution review for bundling is still pending. No OPRA data is
 *    bundled with the APK.
 *  - Versioned updating: the HTTP ETag is stored; re-check uses If-None-Match so the
 *    dataset refreshes cheaply when OPRA publishes changes.
 *  - Selection persistence: the user's chosen profile is serialized into DataStore
 *    (see FxSoundPreferencesDataStore) and restored across restarts.
 */
class OpraRepository(private val context: Context) {

    sealed class LoadState {
        data object NotLoaded : LoadState()
        data class Loading(val firstLoad: Boolean) : LoadState()
        data class Ready(val catalogue: OpraCatalogue, val summaryCount: Int, val databaseEtag: String?) : LoadState()
        data class Failed(val message: String) : LoadState()
    }

    sealed class SelectionUiState {
        data object None : SelectionUiState()
        data class Pending(val plan: HeadphoneEqPlan) : SelectionUiState()
        data class Applied(val plan: HeadphoneEqPlan) : SelectionUiState()

        val planOrNull: HeadphoneEqPlan?
            get() = when (this) {
                is Applied -> plan
                is Pending -> plan
                else -> null
            }
    }

    private val cacheDir: File get() = File(context.filesDir, "opra")
    private val databaseFile: File get() = File(cacheDir, "database_v1.jsonl")
    private val metaFile: File get() = File(cacheDir, "version_info")

    private var indexedCache: OpraJson.IndexResult? = null

    companion object {
        const val MIRROR_URL = "http://opra.roonlabs.net/database_v1.jsonl"
        const val OPRA_URL = "https://github.com/opra-project/OPRA"
        const val LICENSE_NAME = "CC BY-SA 4.0"
    }

    private val _loadState = kotlinx.coroutines.flow.MutableStateFlow<LoadState>(LoadState.NotLoaded)
    val loadState = _loadState

    private val _selectionState = kotlinx.coroutines.flow.MutableStateFlow<SelectionUiState>(SelectionUiState.None)
    val selectionState = _selectionState

    /**
     * Performs a conditional (ETag) update, then rebuilds the index. Safe to call
     * repeatedly; IO runs on the caller's dispatcher (UI callers use Dispatchers.IO).
     */
    suspend fun loadOrRefresh(forceNetwork: Boolean): LoadState = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO
    ) {
        val existing = databaseFile.exists()
        _loadState.value = LoadState.Loading(firstLoad = !existing)
        try {
            if (!existing || forceNetwork) {
                fetchIntoCache(existingBefore = existing)
            }
            val (result, meta) = indexCache()
            indexedCache = result
            val ready = LoadState.Ready(
                catalogue = result.catalogue,
                summaryCount = result.profileOffsets.size,
                databaseEtag = meta["etag"]
            )
            _loadState.value = ready
            ready
        } catch (e: Exception) {
            val msg = if (databaseFile.exists()) {
                // Stale cache is better than nothing, but tell the user it is stale.
                "Refresh failed (${e.message ?: e.javaClass.simpleName}) — showing cached copy."
            } else {
                "Download failed: ${e.message ?: e.javaClass.simpleName}"
            }
            if (databaseFile.exists()) {
                try {
                    val (result, _) = indexCache()
                    indexedCache = result
                    _loadState.value = LoadState.Ready(result.catalogue, result.profileOffsets.size, readMeta()["etag"])
                } catch (ie: Exception) {
                    _loadState.value = LoadState.Failed(msg)
                }
            } else {
                _loadState.value = LoadState.Failed(msg)
            }
            _loadState.value
        }
    }

    private fun fetchIntoCache(existingBefore: Boolean) {
        cacheDir.mkdirs()
        val tmp = File(cacheDir, "database_v1.jsonl.tmp")
        val conn = URL(MIRROR_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        val storedEtag = readMeta()["etag"]
        if (existingBefore && !storedEtag.isNullOrEmpty()) {
            conn.setRequestProperty("If-None-Match", storedEtag)
        }

        if (conn.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
            conn.disconnect()
            return // cache is already current
        }
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw java.io.IOException("HTTP ${conn.responseCode} from OPRA mirror")
        }

        conn.inputStream.use { input ->
            tmp.outputStream().use { out ->
                input.copyTo(out, 64 * 1024)
            }
        }
        val serverEtag = conn.getHeaderField("ETag") ?: ""
        val lastModified = conn.getHeaderField("Last-Modified") ?: ""
        conn.disconnect()
        if (!tmp.renameTo(databaseFile)) {
            tmp.delete()
            throw java.io.IOException("Could not finalize OPRA cache")
        }
        writeMeta(mapOf("etag" to serverEtag, "lastModified" to lastModified))
    }

    private fun readMeta(): Map<String, String> {
        if (!metaFile.exists()) return emptyMap()
        return try {
            metaFile.readLines().mapNotNull { l ->
                val i = l.indexOf(" = ")
                if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 3).trim()
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun writeMeta(meta: Map<String, String>) {
        try {
            metaFile.writeText(meta.entries.joinToString("\n") { "${it.key} = ${it.value}" })
        } catch (e: Exception) {
            // non-fatal
        }
    }

    private fun indexCache(): Pair<OpraJson.IndexResult, Map<String, String>> {
        // Byte-exact line offsets: offsets index into the file, so CRLF and multi-byte
        // UTF-8 names must be counted in raw bytes, never in decoded chars.
        val result = databaseFile.inputStream().buffered(128 * 1024).use { input ->
            var offset = 0L
            OpraJson.index(
                sequence {
                    val line = java.io.ByteArrayOutputStream(4096)
                    var lineBytes = 0L
                    while (true) {
                        val b = input.read()
                        if (b == -1) {
                            if (lineBytes > 0) yield(offset to String(line.toByteArray(), Charsets.UTF_8))
                            break
                        }
                        if (b == '\n'.code) {
                            yield(offset to String(line.toByteArray(), Charsets.UTF_8))
                            offset += lineBytes + 1
                            line.reset()
                            lineBytes = 0
                        } else {
                            lineBytes++
                            if (b != '\r'.code) line.write(b)
                        }
                    }
                }
            )
        }
        return result to readMeta()
    }

    /** Reads one UTF-8 line starting at a byte offset (used for lazy band parsing). */
    private fun readLineAt(offset: Long): String? {
        return try {
            RandomAccessFile(databaseFile, "r").use { raf ->
                raf.seek(offset)
                val out = java.io.ByteArrayOutputStream(4096)
                while (true) {
                    val b = raf.read()
                    if (b == -1 || b == '\n'.code) break
                    if (b != '\r'.code) out.write(b)
                }
                String(out.toByteArray(), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Lazily parses the profile's bands from the byte offset recorded at index time. */
    fun readProfile(summaryId: String): OpraEqProfile? {
        val result = indexedCache ?: return null
        val offset = result.profileOffsets[summaryId] ?: return null
        return readLineAt(offset)?.let { OpraJson.parseProfile(it) }
    }

    // ---- selection / apply plumbing ----------------------------------------------

    var adjustDb: Float = 0f

    /** Searches products by product name or vendor name; empty query = all. */
    fun search(query: String, limit: Int = 100): List<CatalogueEntry> {
        val result = indexedCache ?: return emptyList()
        val q = query.trim().lowercase()
        val all = result.catalogue.entries.values
        if (q.isEmpty()) {
            return all.filter { it.summaries.isNotEmpty() }.sortedBy { it.product.name }.take(limit)
        }
        return all.filter {
            it.summaries.isNotEmpty() &&
                (it.product.name.lowercase().contains(q) || it.product.vendorName.lowercase().contains(q))
        }.sortedBy { it.product.name }.take(limit)
    }

    /**
     * Selects a profile for application. The plan is exposed to the companion service,
     * which rebuilds the DynamicsProcessing config; "APPLIED" is only reported when
     * FxSoundRepository receives the attach confirmation (never before).
     */
    fun selectProfile(fullProfile: OpraEqProfile, adjustmentDb: Float = 0f): OpraApplyResult {
        val plan = OpraProfileConverter.convert(fullProfile)
            ?: return OpraApplyResult(false, "Profile needs more than ${OpraProfileConverter.MAX_BANDS} bands or has none at all.")
        adjustDb = adjustmentDb
        val finalPlan = if (adjustmentDb != 0f) {
            plan.copy(
                bands = plan.bands.map { it.copy(gainDb = (it.gainDb + adjustmentDb).coerceIn(-20f, 20f)) },
                conversionNotes = plan.conversionNotes +
                    "User adjustment: ${if (adjustmentDb > 0) "+" else ""}$adjustmentDb dB applied to every band."
            )
        } else {
            plan
        }
        com.fxsound.audio.data.FxSoundRepository.setHeadphonePlan(finalPlan, fullProfile, adjustmentDb)
        _selectionState.value = SelectionUiState.Pending(finalPlan)
        return OpraApplyResult(true, "Pending application to the next active session.")
    }

    /**
     * Restores the persisted selection at startup. Returns the plan when the persisted
     * profile parses and converts.
     */
    fun restoreSelection(persistedJson: String?): OpraApplyResult {
        if (persistedJson.isNullOrBlank()) return OpraApplyResult(false, "No persisted selection.")
        val selection = OpraJson.decodeFromPersistence(persistedJson)
            ?: return OpraApplyResult(false, "Persisted selection is corrupt; deselecting.")
        return selectProfile(selection.profile, selection.adjustDb)
    }

    /** The companion service acknowledges a successful DynamicsProcessing attach. */
    fun confirmApplied(success: Boolean) {
        _selectionState.value = when (val current = _selectionState.value) {
            is SelectionUiState.Pending -> if (success) SelectionUiState.Applied(current.plan) else current
            else -> current
        }
    }

    fun clearSelection() {
        _selectionState.value = SelectionUiState.None
        com.fxsound.audio.data.FxSoundRepository.setHeadphonePlan(null, null, 0f)
        adjustDb = 0f
    }
}
