package com.gomaed.photoslide.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object ImageScanner {

    // ── Persistent scope ──────────────────────────────────────────────────────

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var scanJob: Job? = null

    val isScanning: Boolean get() = scanJob?.isActive == true

    // ── Cache helpers ─────────────────────────────────────────────────────────

    fun cacheFile(context: Context) = File(context.filesDir, "uri_cache.txt")

    fun folderHash(prefs: AppPreferences): String =
        prefs.selectedFolderUris.sorted().joinToString("|")

    // ── Scanning ──────────────────────────────────────────────────────────────

    /**
     * Cancel any in-progress scan, then start a fresh URI scan in ImageScanner's own
     * scope (survives navigation / app background).  When finished, automatically
     * starts a face scan if [AppPreferences.facesOnlyEnabled] is set.
     */
    fun startScan(context: Context, prefs: AppPreferences) {
        scanJob?.cancel()
        val ctx = context.applicationContext
        scanJob = scope.launch {
            val uris = scanToCache(ctx, prefs)
            // Signal the wallpaper engine to retry ensureImages() now that the cache is ready.
            prefs.uriCacheUpdated = prefs.uriCacheUpdated + 1
            if (prefs.facesOnlyEnabled) {
                FaceScanner.startScan(ctx, uris, prefs)
            }
        }
    }

    fun cancelScan() { scanJob?.cancel() }

    /**
     * Perform the URI scan synchronously within the caller's coroutine scope.
     * Used by [LiveWallpaperService] which manages its own scan job.
     * [prefs.isScanning] is always reset via try/finally.
     */
    suspend fun scanToCache(context: Context, prefs: AppPreferences): List<Uri> {
        prefs.isScanning = true
        return try {
            val scanned = withContext(Dispatchers.IO) {
                val result = mutableListOf<Uri>()
                for (uriStr in prefs.selectedFolderUris) {
                    try {
                        val treeUri = Uri.parse(uriStr)
                        collectImages(context, treeUri,
                            DocumentsContract.getTreeDocumentId(treeUri), result)
                    } catch (_: Exception) {}
                }
                result
            }
            withContext(Dispatchers.IO) { saveCache(context, scanned, prefs) }
            scanned
        } finally {
            prefs.isScanning = false
        }
    }

    fun loadCache(context: Context, prefs: AppPreferences): List<Uri>? {
        return try {
            val file = cacheFile(context)
            if (!file.exists()) return null
            val lines = file.readLines()
            if (lines.isEmpty() || lines[0] != folderHash(prefs)) return null
            lines.drop(1).filter { it.isNotBlank() }.map { Uri.parse(it) }
        } catch (_: Exception) { null }
    }

    fun saveCache(context: Context, uris: List<Uri>, prefs: AppPreferences) {
        try {
            cacheFile(context).bufferedWriter().use { w ->
                w.write(folderHash(prefs)); w.newLine()
                uris.forEach { w.write(it.toString()); w.newLine() }
            }
        } catch (_: Exception) {}
    }

    private fun collectImages(
        context: Context, treeUri: Uri, docId: String, result: MutableList<Uri>
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val childId = cursor.getString(0) ?: continue
                val mime    = cursor.getString(1) ?: continue
                when {
                    mime.startsWith("image/") ->
                        result.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, childId))
                    mime == DocumentsContract.Document.MIME_TYPE_DIR ->
                        collectImages(context, treeUri, childId, result)
                }
            }
        }
    }
}
