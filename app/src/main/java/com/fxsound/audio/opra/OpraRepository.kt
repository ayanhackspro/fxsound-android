package com.fxsound.audio.opra

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.sync.withLock

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

        /** C4: downloading and indexing are different waits and are labelled separately. */
        data class Loading(val phase: Phase, val firstLoad: Boolean) : LoadState() {
            enum class Phase { DOWNLOADING, INDEXING }
        }

        data class Ready(
            val catalogue: OpraCatalogue,
            val summaryCount: Int,
            val databaseEtag: String?,
            /** Set when this copy is cached/recovered rather than freshly refreshed. */
            val staleMessage: String? = null
        ) : LoadState()

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
    private val databaseFile: File get() = OpraCache.database(cacheDir)
    private val metaFile: File get() = File(cacheDir, "version_info")

    /**
     * C3: written on the IO dispatcher, read from the UI thread — needs visibility and a
     * single writer. The mutex serialises load/refresh/delete so two concurrent loads can
     * never interleave file swaps.
     */
    @Volatile
    private var indexedCache: OpraJson.IndexResult? = null
    private val cacheMutex = kotlinx.coroutines.sync.Mutex()

    companion object {
        private const val TAG = "OpraRepository"
        const val MIRROR_URL = "http://opra.roonlabs.net/database_v1.jsonl"
        const val OPRA_URL = "https://github.com/opra-project/OPRA"
        const val LICENSE_NAME = "CC BY-SA 4.0"
        const val LICENSE_URL = "https://creativecommons.org/licenses/by-sa/4.0/"
        /** Required D1 statement: the app modifies the published curves when converting them. */
        const val MODIFICATION_NOTICE =
            "Curves are modified by this app: shelving bands are approximated as peaking bands " +
                "because the renderers support peaking filters only."
    }

    private val _loadState = kotlinx.coroutines.flow.MutableStateFlow<LoadState>(LoadState.NotLoaded)
    val loadState = _loadState

    private val _selectionState = kotlinx.coroutines.flow.MutableStateFlow<SelectionUiState>(SelectionUiState.None)
    val selectionState = _selectionState

    /**
     * Performs a conditional (ETag) update, then rebuilds the index. Safe to call
     * repeatedly and from several places: a mutex makes load/refresh/delete single-flight,
     * and a failed or corrupt download never destroys a working cache (C1–C3).
     */
    suspend fun loadOrRefresh(forceNetwork: Boolean): LoadState = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO
    ) {
        cacheMutex.withLock {
            val hadCache = databaseFile.isFile
            _loadState.value = LoadState.Loading(
                phase = if (!hadCache || forceNetwork) LoadState.Loading.Phase.DOWNLOADING
                else LoadState.Loading.Phase.INDEXING,
                firstLoad = !hadCache
            )
            try {
                // A file that does not even validate (truncated / captive portal) is
                // replaced by the backup before we ever try to index it.
                if (hadCache && OpraCache.validate(databaseFile) != null) {
                    OpraCache.restoreBackup(cacheDir)
                }
                val downloaded = if (!databaseFile.isFile || forceNetwork) {
                    fetchIntoCache(existingBefore = databaseFile.isFile)
                } else {
                    null
                }
                val result = downloaded ?: indexFile(databaseFile)
                indexedCache = result
                rebuildSearchIndex(result)
                LoadState.Ready(
                    catalogue = result.catalogue,
                    summaryCount = result.profileOffsets.size,
                    databaseEtag = readMeta()["etag"]
                ).also { _loadState.value = it }
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "OPRA load/refresh failed: $reason")
                val recovered = recoverIndex()
                if (recovered != null) {
                    indexedCache = recovered
                    rebuildSearchIndex(recovered)
                    LoadState.Ready(
                        catalogue = recovered.catalogue,
                        summaryCount = recovered.profileOffsets.size,
                        databaseEtag = readMeta()["etag"],
                        staleMessage = "Showing a cached copy after: $reason"
                    ).also { _loadState.value = it }
                } else {
                    LoadState.Failed("Download failed: $reason").also { _loadState.value = it }
                }
            }
        }
    }

    /**
     * C2 automated recovery: keep the current cache if it still indexes, else fall back to
     * `.bak`, else delete and re-download exactly once.
     */
    private fun recoverIndex(): OpraJson.IndexResult? {
        if (databaseFile.isFile && OpraCache.validate(databaseFile) == null) {
            runCatching { indexFile(databaseFile) }.getOrNull()?.let { return it }
        }
        if (OpraCache.restoreBackup(cacheDir) && OpraCache.validate(databaseFile) == null) {
            runCatching { indexFile(databaseFile) }.getOrNull()?.let { return it }
            Log.w(TAG, "Restored .bak but it still does not index; re-downloading.")
        }
        return try {
            databaseFile.delete()
            fetchIntoCache(existingBefore = false)
            indexFile(databaseFile)
        } catch (e: Exception) {
            Log.w(TAG, "OPRA re-download after corruption also failed: ${e.message}")
            null
        }
    }

    /**
     * Downloads to `.tmp`, validates and fully indexes it, and only then promotes it over
     * the live cache (previous file kept as `.bak`). Returns the index of the new file, or
     * null when the server said 304 Not Modified.
     */
    private fun fetchIntoCache(existingBefore: Boolean): OpraJson.IndexResult? {
        cacheDir.mkdirs()
        val tmp = OpraCache.temporary(cacheDir)
        tmp.delete()
        val conn = URL(MIRROR_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            val storedEtag = readMeta()["etag"]
            if (existingBefore && !storedEtag.isNullOrEmpty()) {
                conn.setRequestProperty("If-None-Match", storedEtag)
            }

            if (conn.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                if (!existingBefore) {
                    throw java.io.IOException("unexpected HTTP 304 without a cached copy")
                }
                return null // cache is already current
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("HTTP ${conn.responseCode} from OPRA mirror")
            }

            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    input.copyTo(out, 64 * 1024)
                }
            }
            val serverEtag = conn.getHeaderField("ETag") ?: ""
            val lastModified = conn.getHeaderField("Last-Modified") ?: ""

            // C1: validate before replacing anything, then prove it indexes.
            OpraCache.validate(tmp)?.let { reason ->
                tmp.delete()
                throw java.io.IOException("Downloaded file rejected ($reason) — keeping the existing cache")
            }
            val result = indexFile(tmp)

            if (!OpraCache.promote(cacheDir)) {
                tmp.delete()
                throw java.io.IOException("Could not finalize OPRA cache")
            }
            writeMeta(mapOf("etag" to serverEtag, "lastModified" to lastModified))
            return result
        } finally {
            conn.disconnect()
        }
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

    /** C4: removes the 12.6 MB cache (and its backup) and returns to NotLoaded. */
    suspend fun deleteDownloadedData() = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO
    ) {
        cacheMutex.withLock {
            indexedCache = null
            searchIndex = emptyList()
            OpraCache.deleteAll(cacheDir)
            _loadState.value = LoadState.NotLoaded
        }
    }

    private fun indexFile(file: File): OpraJson.IndexResult {
        // Byte-exact line offsets: offsets index into the file, so CRLF and multi-byte
        // UTF-8 names must be counted in raw bytes, never in decoded chars.
        return file.inputStream().buffered(128 * 1024).use { input ->
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

    @Volatile
    var adjustDb: Float = 0f

    /** Precomputed lowercase keys so search never lowercases the whole catalogue per keystroke. */
    @Volatile
    private var searchIndex: List<OpraSearch.Item> = emptyList()

    private fun rebuildSearchIndex(result: OpraJson.IndexResult) {
        searchIndex = OpraSearch.items(result.catalogue.entries.values)
    }

    /** E1: ranked search — exact/prefix first, then contains, then vendor. */
    fun search(query: String, limit: Int = 100): List<CatalogueEntry> =
        OpraSearch.rank(searchIndex, query, limit)

    /**
     * Selects a profile for application. The plan is exposed to the companion service,
     * which rebuilds the DynamicsProcessing config; "APPLIED" is only reported when
     * FxSoundRepository receives the attach confirmation (never before).
     *
     * [bandOffsets] are the user's per-band trims (E4), aligned with the converted bands;
     * [adjustmentDb] is the master trim applied on top of every band.
     */
    fun selectProfile(
        fullProfile: OpraEqProfile,
        adjustmentDb: Float = 0f,
        bandOffsets: List<Float> = emptyList()
    ): OpraApplyResult {
        val plan = OpraProfileConverter.convert(fullProfile)
            ?: return OpraApplyResult(false, "Profile needs more than ${OpraProfileConverter.MAX_BANDS} bands or has none at all.")
        adjustDb = adjustmentDb
        val finalPlan = OpraProfileConverter.applyUserAdjustments(plan, adjustmentDb, bandOffsets)
        val effective = finalPlan.userBandOffsets
        com.fxsound.audio.data.FxSoundRepository
            .setHeadphonePlan(finalPlan, fullProfile, adjustmentDb, effective)
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
        return selectProfile(selection.profile, selection.adjustDb, selection.bandOffsets)
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
