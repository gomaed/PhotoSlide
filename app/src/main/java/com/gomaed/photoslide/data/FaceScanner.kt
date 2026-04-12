package com.gomaed.photoslide.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.PointF
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Single authority for all face-scan work.
 *
 * Owns the ML Kit detector, the in-memory face-focus cache, the on-disk faces-only
 * cache, and a persistent CoroutineScope so scans survive fragment/activity
 * lifecycle changes.  Both the wallpaper service and the settings UI call
 * [startScan]; there is never more than one concurrent scan.
 */
object FaceScanner {

    // ── Coroutine scope ───────────────────────────────────────────────────────

    /** Long-lived scope — not tied to any activity or fragment. */
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var scanJob: Job? = null

    val isScanning: Boolean get() = scanJob?.isActive == true

    // ── ML Kit detector ───────────────────────────────────────────────────────

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.1f)
                .build()
        )
    }

    // ── In-memory face-focus cache ────────────────────────────────────────────
    // Maps URI string → face centre PointF (x < 0 = no-face sentinel).
    // Used by both the scan loop (to skip images already analysed) and the
    // wallpaper renderer (to centre bitmaps on the detected face).

    val faceCache: MutableMap<String, PointF> =
        java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<String, PointF>(1024, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, PointF>) = size > 1000
            }
        )

    // ── On-disk cache ─────────────────────────────────────────────────────────

    fun cacheFile(context: Context): File = File(context.filesDir, "faces_only_cache.txt")

    fun loadCache(context: Context, prefs: AppPreferences): List<Uri>? = try {
        val file = cacheFile(context)
        if (!file.exists()) null
        else {
            val lines = file.readLines()
            if (lines.isEmpty() || lines[0] != ImageScanner.folderHash(prefs)) null
            else lines.drop(1).filter { it.isNotBlank() }.map { Uri.parse(it) }
        }
    } catch (_: Exception) { null }

    fun saveCache(context: Context, uris: List<Uri>, prefs: AppPreferences) {
        try {
            cacheFile(context).bufferedWriter().use { w ->
                w.write(ImageScanner.folderHash(prefs)); w.newLine()
                uris.forEach { w.write(it.toString()); w.newLine() }
            }
        } catch (_: Exception) {}
    }

    fun clearCache(context: Context) {
        try { cacheFile(context).delete() } catch (_: Exception) {}
    }

    // ── Face detection (also used by the renderer for bitmap centering) ───────

    /**
     * Return the normalized face-centre for [uri], using the in-memory cache when
     * available (instant).  On a cache miss, a small thumbnail (≤320 px) is decoded
     * and processed by ML Kit — the same fast path used by the bulk scan — so the
     * result is always cheap to compute regardless of display resolution.
     *
     * The returned [PointF] uses 0–1 normalized coordinates and is valid for any
     * bitmap size, so the display-resolution bitmap never needs to be passed in.
     */
    suspend fun detectFocus(context: Context, uri: Uri): PointF? {
        val key = uri.toString()
        faceCache[key]?.let { return if (it.x < 0f) null else it }
        val bitmap = decodeSmall(context, uri) ?: return null
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = suspendCancellableCoroutine { cont ->
                detector.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(emptyList()) }
            }
            val focus = if (faces.isEmpty()) null else {
                val l = faces.minOf { it.boundingBox.left }
                val t = faces.minOf { it.boundingBox.top }
                val r = faces.maxOf { it.boundingBox.right }
                val b = faces.maxOf { it.boundingBox.bottom }
                PointF((l + r) / 2f / bitmap.width, (t + b) / 2f / bitmap.height)
            }
            faceCache[key] = focus ?: PointF(-1f, -1f)
            focus
        } catch (_: Exception) { null } finally {
            bitmap.recycle()
        }
    }

    // ── Scanning ──────────────────────────────────────────────────────────────

    /** Cancel any in-progress scan without starting a new one. */
    fun cancelScan() { scanJob?.cancel() }

    /**
     * Cancel any in-progress scan, clear the on-disk cache, then start a fresh scan
     * in FaceScanner's own scope (survives navigation / app background).
     *
     * When finished the on-disk cache is written and [AppPreferences.faceCacheUpdated]
     * is incremented so the wallpaper engine picks up the result automatically.
     */
    fun startScan(context: Context, images: List<Uri>, prefs: AppPreferences) {
        scanJob?.cancel()
        val ctx = context.applicationContext
        scanJob = scope.launch {
            clearCache(ctx)
            scan(ctx, images, prefs)
            prefs.faceCacheUpdated = prefs.faceCacheUpdated + 1
        }
    }

    private suspend fun scan(context: Context, images: List<Uri>, prefs: AppPreferences): List<Uri> {
        prefs.isFaceScanning = true
        prefs.faceScanProgress = 0
        return try {
            val total = images.size
            val faceImages = mutableListOf<Uri>()
            var processed = 0
            var lastPercent = -1
            for (uri in images) {
                if (!currentCoroutineContext().isActive) break
                val key = uri.toString()
                val hasFace = detectFocus(context, uri) != null
                if (hasFace) faceImages.add(uri)
                processed++
                if (total > 0) {
                    val percent = processed * 100 / total
                    if (percent != lastPercent) {
                        lastPercent = percent
                        prefs.faceScanProgress = percent
                    }
                }
            }
            withContext(Dispatchers.IO) { saveCache(context, faceImages, prefs) }
            faceImages
        } finally {
            prefs.isFaceScanning = false
        }
    }

    private suspend fun decodeSmall(context: Context, uri: Uri): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val scale = 320f / maxOf(info.size.width, info.size.height)
                    if (scale < 1f) decoder.setTargetSize(
                        (info.size.width * scale).toInt(),
                        (info.size.height * scale).toInt()
                    )
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (_: Exception) { null }
        }
}
