package com.gomaed.photoslide.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ImageScanner {

    fun cacheFile(context: Context) = File(context.filesDir, "uri_cache.txt")

    fun folderHash(prefs: AppPreferences): String =
        prefs.selectedFolderUris.sorted().joinToString("|")

    suspend fun scanToCache(context: Context, prefs: AppPreferences): List<Uri> {
        prefs.isScanning = true
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
        prefs.isScanning = false
        return scanned
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
