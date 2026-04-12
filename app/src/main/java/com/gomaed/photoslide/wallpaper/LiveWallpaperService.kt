package com.gomaed.photoslide.wallpaper

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.DocumentsContract
import android.service.wallpaper.WallpaperService
import com.gomaed.photoslide.data.ImageScanner
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.gomaed.photoslide.data.AppPreferences
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class LiveWallpaperService : WallpaperService() {

    // ── Face detection ────────────────────────────────────────────────────────
    // Results cached by URI so detection only runs once per image.
    // PointF(-1,-1) is used as a sentinel for "no faces detected".
    private val faceCache: MutableMap<String, android.graphics.PointF> =
        java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<String, android.graphics.PointF>(1024, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, android.graphics.PointF>) = size > 1000
            }
        )
    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.1f)
                .build()
        )
    }

    suspend fun detectFocusPoint(bitmap: Bitmap, uri: Uri): android.graphics.PointF? {
        val key = uri.toString()
        faceCache[key]?.let { return if (it.x < 0f) null else it }
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = suspendCancellableCoroutine { cont ->
                faceDetector.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(emptyList()) }
            }
            val focus = if (faces.isEmpty()) null else {
                val l = faces.minOf { it.boundingBox.left }
                val t = faces.minOf { it.boundingBox.top }
                val r = faces.maxOf { it.boundingBox.right }
                val b = faces.maxOf { it.boundingBox.bottom }
                android.graphics.PointF(
                    (l + r) / 2f / bitmap.width,
                    (t + b) / 2f / bitmap.height
                )
            }
            faceCache[key] = focus ?: android.graphics.PointF(-1f, -1f)
            focus
        } catch (_: Exception) { null }
    }

    // ── Service-level image pool ──────────────────────────────────────────────
    // Shared across all engine instances (homescreen + preview).
    // @Volatile so the HandlerThread in each engine sees the latest reference.
    private val servicePrefs by lazy { AppPreferences(this) }
    @Volatile var images: List<Uri> = emptyList()
    private var rawImages: List<Uri> = emptyList()   // unsorted — re-sort on demand
    private var scanComplete = false
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var scanJob: Job? = null

    // ── Faces-only filter ─────────────────────────────────────────────────────
    @Volatile var facesOnlyImages: List<Uri> = emptyList()
    private var faceScanJob: Job? = null

    /** The image pool engines should use — respects the faces-only toggle. */
    val effectiveImages: List<Uri>
        get() = if (servicePrefs.facesOnlyEnabled && facesOnlyImages.isNotEmpty()) facesOnlyImages
                else images

    /** Scan every image at low resolution and populate [facesOnlyImages].
     *  Calls [onComplete] on the main thread when finished.
     *  Shows the toolbar scan spinner in SettingsActivity while running. */
    fun startFaceScan(onComplete: (List<Uri>) -> Unit) {
        faceScanJob?.cancel()
        servicePrefs.isFaceScanning = true
        servicePrefs.faceScanProgress = 0
        faceScanJob = serviceScope.launch {
            try {
                val allImages = images
                val total = allImages.size
                val faceImages = mutableListOf<Uri>()
                var processed = 0
                var lastPercent = -1
                for (uri in allImages) {
                    if (!isActive) break
                    val key = uri.toString()
                    val cached = faceCache[key]
                    if (cached != null) {
                        if (cached.x >= 0f) faceImages.add(uri)
                    } else {
                        val bmp = decodeSmallBitmap(uri)
                        if (bmp != null) {
                            val focus = detectFocusPoint(bmp, uri)
                            bmp.recycle()
                            if (focus != null) faceImages.add(uri)
                        }
                    }
                    processed++
                    if (total > 0) {
                        val percent = processed * 100 / total
                        if (percent != lastPercent) {
                            lastPercent = percent
                            servicePrefs.faceScanProgress = percent
                        }
                    }
                }
                facesOnlyImages = faceImages
                withContext(Dispatchers.IO) { saveFacesOnlyCache(faceImages) }
                withContext(Dispatchers.Main) { onComplete(faceImages) }
            } finally {
                servicePrefs.isFaceScanning = false
            }
        }
    }

    // ── Faces-only disk cache ─────────────────────────────────────────────────
    // Survives reboots. Invalidated whenever folders change (hash mismatch).

    private fun facesOnlyCacheFile() = java.io.File(filesDir, "faces_only_cache.txt")

    private fun saveFacesOnlyCache(uris: List<Uri>) {
        try {
            facesOnlyCacheFile().bufferedWriter().use { w ->
                w.write(ImageScanner.folderHash(servicePrefs)); w.newLine()
                uris.forEach { w.write(it.toString()); w.newLine() }
            }
        } catch (_: Exception) {}
    }

    private fun loadFacesOnlyCache(): List<Uri>? = try {
        val file = facesOnlyCacheFile()
        if (!file.exists()) null
        else {
            val lines = file.readLines()
            if (lines.isEmpty() || lines[0] != ImageScanner.folderHash(servicePrefs)) null
            else lines.drop(1).filter { it.isNotBlank() }.map { Uri.parse(it) }
        }
    } catch (_: Exception) { null }

    private fun clearFacesOnlyCache() {
        try { facesOnlyCacheFile().delete() } catch (_: Exception) {}
    }

    private suspend fun decodeSmallBitmap(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val source = ImageDecoder.createSource(contentResolver, uri)
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

    override fun onCreateEngine(): Engine = WallpaperEngine()

    override fun onDestroy() {
        super.onDestroy()
        faceScanJob?.cancel()
        serviceScope.cancel()
    }

    /**
     * Called by an engine when it needs images.
     * - If we already have a complete scan: re-apply sort and call onReady immediately.
     * - If a disk cache exists: load it instantly → call onReady → rescan silently in background.
     * - Otherwise: scan folders first, then call onReady.
     */
    fun ensureImages(prefs: AppPreferences, onReady: () -> Unit) {
        if (scanComplete && rawImages.isNotEmpty()) {
            images = applySort(prefs)
            onReady()
            return
        }
        serviceScope.launch {
            val cached = withContext(Dispatchers.IO) { ImageScanner.loadCache(this@LiveWallpaperService, prefs) }
            if (cached != null) {
                rawImages = cached
                scanComplete = true
                images = applySort(prefs)
                onReady()
            } else {
                rescan(prefs, silent = false, onComplete = onReady)
            }
        }
    }

    /** Re-apply sort to rawImages (e.g. after sort-order pref change). */
    fun reapplySort(prefs: AppPreferences) {
        images = applySort(prefs)
    }

    /** Discard cached data and force a fresh scan on next ensureImages() call. */
    fun invalidate() {
        scanComplete = false
        rawImages = emptyList()
        images = emptyList()
        facesOnlyImages = emptyList()
        clearFacesOnlyCache()
        scanJob?.cancel()
        faceScanJob?.cancel()
    }

    private fun applySort(prefs: AppPreferences): List<Uri> = when (prefs.sortOrder) {
        AppPreferences.SORT_RANDOM    -> rawImages.shuffled()
        AppPreferences.SORT_DATE_DESC -> rawImages.reversed()
        else                          -> rawImages.sortedBy { it.lastPathSegment }
    }

    private fun rescan(
        prefs: AppPreferences,
        silent: Boolean,
        onComplete: (() -> Unit)? = null
    ) {
        scanJob?.cancel()
        scanJob = serviceScope.launch {
            val scanned = ImageScanner.scanToCache(this@LiveWallpaperService, prefs)
            rawImages = scanned
            images = applySort(prefs)
            scanComplete = true
            if (!silent) onComplete?.invoke()
        }
    }

    // ── Engine ────────────────────────────────────────────────────────────────

    inner class WallpaperEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val handlerThread = HandlerThread("WallpaperRender")
        private lateinit var handler: Handler
        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private val prefs by lazy { AppPreferences(this@LiveWallpaperService) }

        private var cellIndices: IntArray = IntArray(0)
        private var portraitCellIndices: IntArray = IntArray(0)
        private var landscapeCellIndices: IntArray = IntArray(0)
        private var advanceCellPos = 0
        private var bitmaps: Array<Bitmap?> = emptyArray()
        private var fadingBitmaps: Array<Bitmap?> = emptyArray()
        private var focusPoints: Array<android.graphics.PointF?> = emptyArray()
        private var fadingFocusPoints: Array<android.graphics.PointF?> = emptyArray()
        private var fadeAlphas: FloatArray = FloatArray(0)
        private var fadeStartTimes: LongArray = LongArray(0)
        private var fadeDurations: LongArray = LongArray(0)

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val bgPaint = Paint()
        private val placeholderPaint = Paint()

        // 4 tonal shades from the Material You dynamic palette (API 31+),
        // falling back to neutral greys on older devices.
        private val placeholderColors: IntArray by lazy {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                intArrayOf(
                    resources.getColor(android.R.color.system_accent1_200, null),
                    resources.getColor(android.R.color.system_accent2_400, null),
                    resources.getColor(android.R.color.system_accent1_400, null),
                    resources.getColor(android.R.color.system_accent2_200, null),
                )
            } else {
                intArrayOf(0xFF616161.toInt(), 0xFF757575.toInt(),
                           0xFF9E9E9E.toInt(), 0xFF424242.toInt())
            }
        }

        private var isLoading = false
        private var loadingRotation = 0f
        private var loadingSweep = 10f
        private var loadingStartTime = 0L
        private var loadingHeadAngle = 0f
        private var loadingTailAngle = 0f
        private var loadingPrevTime  = 0L

        // Drives the loading spinner — runs on handlerThread, independent of Choreographer.
        // Matches Material CircularProgressIndicator: both head and tail always move forward.
        // Each frame the sweep error is applied to the head (grow) or tail (shrink), so the
        // arc extends from the front and retracts from the back — neither end ever stops.
        private val spinnerRunnable = object : Runnable {
            private val cycleDuration = 1600f
            private val baseSpeed     = 0.18f   // deg/ms — base rotation both ends share
            private val minSweep      = 10f
            private val maxSweep      = 270f

            override fun run() {
                if (!isLoading) return
                val now = SystemClock.uptimeMillis()
                val dt = if (loadingPrevTime == 0L) 16f
                         else (now - loadingPrevTime).coerceIn(1L, 100L).toFloat()
                loadingPrevTime = now

                val elapsed = (now - loadingStartTime).toFloat()
                val t = (elapsed % cycleDuration) / cycleDuration
                val targetSweep = if (t < 0.5f) {
                    lerp(minSweep, maxSweep, smoothStep(t / 0.5f))
                } else {
                    lerp(maxSweep, minSweep, smoothStep((t - 0.5f) / 0.5f))
                }

                val base       = baseSpeed * dt
                val sweepDelta = targetSweep - (loadingHeadAngle - loadingTailAngle)
                if (sweepDelta >= 0f) {
                    // Growing: head gets the extra push, tail advances at base speed
                    loadingTailAngle += base
                    loadingHeadAngle += base + sweepDelta
                } else {
                    // Shrinking: tail gets the extra push (catches up), head at base speed
                    loadingHeadAngle += base
                    loadingTailAngle += base - sweepDelta
                }

                loadingRotation = loadingTailAngle
                loadingSweep    = loadingHeadAngle - loadingTailAngle

                drawFrame()
                handler.postDelayed(this, 16L)
            }
        }

        // Drives crossfade animations — runs on handlerThread, independent of Choreographer.
        private val fadeRunnable = object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                var anyActive = false
                for (idx in fadeStartTimes.indices) {
                    val start = fadeStartTimes[idx]
                    if (start == 0L) continue
                    val dur = fadeDurations[idx]
                    val elapsed = now - start
                    if (elapsed >= dur) {
                        fadeAlphas[idx] = 1f
                        fadeStartTimes[idx] = 0L
                        fadingBitmaps[idx]?.recycle()
                        fadingBitmaps[idx] = null
                        if (idx < fadingFocusPoints.size) fadingFocusPoints[idx] = null
                    } else {
                        fadeAlphas[idx] = elapsed.toFloat() / dur
                        anyActive = true
                    }
                }
                drawFrame()
                if (anyActive) handler.postDelayed(this, 16L)
            }
        }

        private val noImagesScrimPaint = Paint()
        private val noImagesTextPaint: Paint by lazy {
            val ctx = android.view.ContextThemeWrapper(
                this@LiveWallpaperService,
                com.gomaed.photoslide.R.style.Theme_PhotoSlide
            )
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = android.graphics.Typeface.create(
                    android.widget.TextView(ctx).typeface,
                    android.graphics.Typeface.BOLD
                )
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
            }
        }

        private val spinnerTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(50, 255, 255, 255)
        }
        private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }

        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var isVisible = false
        private var pillRect = RectF()

        private val gestureDetector by lazy {
            GestureDetector(this@LiveWallpaperService,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        if (images.isEmpty() && pillRect.contains(e.x, e.y)) {
                            val intent = android.content.Intent(
                                this@LiveWallpaperService,
                                com.gomaed.photoslide.ui.SettingsActivity::class.java
                            ).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                         android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                putExtra("go_to_folders", true)
                            }
                            startActivity(intent)
                            return true
                        }
                        return false
                    }
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        val imgs = this@LiveWallpaperService.effectiveImages
                        if (imgs.isEmpty() || cellIndices.isEmpty()) return true
                        val cell = cellAtPosition(e.x, e.y)
                        when (prefs.doubleTapAction) {
                            AppPreferences.DOUBLE_TAP_SWAP -> {
                                cellIndices[cell] = (cellIndices[cell] + 1) % imgs.size
                                scope.launch { reloadCell(cell, if (prefs.fadeDuration == 0) 0L else 200L) }
                            }
                            AppPreferences.DOUBLE_TAP_OPEN -> {
                                val uri = imgs[cellIndices[cell] % imgs.size]
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "image/*")
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                try { startActivity(intent) } catch (_: Exception) {}
                            }
                            // DOUBLE_TAP_NONE → do nothing
                        }
                        return true
                    }
                })
        }

        private val isLandscape  get() = surfaceWidth > surfaceHeight
        private val currentCols  get() = if (isLandscape) prefs.landscapeCols else prefs.portraitCols
        private val currentRows  get() = if (isLandscape) prefs.landscapeRows else prefs.portraitRows

        private fun cellRects(): List<RectF> {
            val cols = currentCols
            val rows = currentRows
            val cellW = surfaceWidth / cols.toFloat()
            val uniformCellH = surfaceHeight / rows.toFloat()
            val ratio = prefs.staggerRatio / 100f

            if (ratio <= 0.5f || rows <= 1) {
                return List(cols * rows) { i ->
                    val col = i % cols; val row = i / cols
                    RectF(col * cellW, row * uniformCellH, (col + 1) * cellW, (row + 1) * uniformCellH)
                }
            }

            val colTops = Array(cols) { col ->
                val tallCount = (0 until rows).count { row -> (row + col) % 2 == 0 }
                val shortCount = rows - tallCount
                val k = surfaceHeight / (tallCount * ratio + shortCount * (1f - ratio))
                val tallH = ratio * k; val shortH = (1f - ratio) * k
                FloatArray(rows + 1).also { tops ->
                    tops[0] = 0f
                    for (row in 0 until rows)
                        tops[row + 1] = tops[row] + if ((row + col) % 2 == 0) tallH else shortH
                }
            }

            return List(cols * rows) { i ->
                val col = i % cols; val row = i / cols
                RectF(col * cellW, colTops[col][row], (col + 1) * cellW, colTops[col][row + 1])
            }
        }

        private val advanceRunnable = object : Runnable {
            override fun run() {
                val imgs = this@LiveWallpaperService.effectiveImages
                if (imgs.isNotEmpty() && cellIndices.isNotEmpty()) {
                    cellIndices[advanceCellPos] = (cellIndices[advanceCellPos] + 1) % imgs.size
                    val cell = advanceCellPos
                    advanceCellPos = (advanceCellPos + 1) % cellIndices.size
                    scope.launch { reloadCell(cell, prefs.fadeDuration.toLong()) }
                }
                if (isVisible) scheduleNextAdvance()
            }
        }

        private fun scheduleNextAdvance() {
            handler.removeCallbacks(advanceRunnable)
            val interval = prefs.slideInterval
            if (interval != AppPreferences.INTERVAL_NEVER)
                handler.postDelayed(advanceRunnable, interval * 1000L)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            handlerThread.start()
            handler = Handler(handlerThread.looper)
            prefs.registerChangeListener(this)
            setTouchEventsEnabled(true)
        }

        override fun onTouchEvent(event: MotionEvent) { gestureDetector.onTouchEvent(event) }

        override fun onDestroy() {
            super.onDestroy()
            prefs.unregisterChangeListener(this)
            handler.removeCallbacksAndMessages(null)
            handlerThread.quitSafely()
            scope.cancel()
            recycleBitmaps()
            stopLoadingAnimation()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            if (visible) {
                if (this@LiveWallpaperService.images.isEmpty()) {
                    startLoading()
                } else {
                    scope.launch { refreshBitmaps() }
                }
                scheduleNextAdvance()
            } else {
                handler.removeCallbacks(advanceRunnable)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            // Persist the live cellIndices for the orientation we're leaving,
            // while isLandscape still reflects the old orientation.
            if (cellIndices.isNotEmpty()) {
                if (isLandscape) landscapeCellIndices = cellIndices.copyOf()
                else portraitCellIndices = cellIndices.copyOf()
            }
            surfaceWidth = width
            surfaceHeight = height
            if (this@LiveWallpaperService.images.isNotEmpty()) {
                scope.launch { refreshBitmaps() }
            } else {
                startLoading()
            }
        }

        // Called by the system once the surface is stable and ready to draw —
        // e.g. after rotation completes. Ensures the frame is painted even if
        // lockHardwareCanvas() returned null during the surface transition.
        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            drawFrame()
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            when (key) {
                AppPreferences.KEY_FOLDER_URIS -> {
                    this@LiveWallpaperService.invalidate()
                    startLoading()
                }
                AppPreferences.KEY_SORT_ORDER -> {
                    // No rescan needed — just re-sort rawImages and reload bitmaps
                    this@LiveWallpaperService.reapplySort(prefs)
                    scope.launch { reloadAllBitmaps() }
                }
                AppPreferences.KEY_PORTRAIT_COLS,
                AppPreferences.KEY_PORTRAIT_ROWS,
                AppPreferences.KEY_LANDSCAPE_COLS,
                AppPreferences.KEY_LANDSCAPE_ROWS,
                AppPreferences.KEY_STAGGER_RATIO -> scope.launch { reloadAllBitmaps() }

                AppPreferences.KEY_GRID_SPACING,
                AppPreferences.KEY_GRID_COLOR,
                AppPreferences.KEY_GRID_CORNER_RADIUS -> drawFrame()

                AppPreferences.KEY_CENTER_FACES -> {
                    if (prefs.centerFacesEnabled) scope.launch { reloadAllBitmaps() }
                    else drawFrame()
                }

                AppPreferences.KEY_FACES_ONLY -> {
                    val svc = this@LiveWallpaperService
                    if (prefs.facesOnlyEnabled) {
                        // If a URI scan is still running, don't start the face scan now —
                        // the scan completion callback checks facesOnlyEnabled and picks it up.
                        if (svc.scanJob?.isActive != true && svc.images.isNotEmpty()) {
                            svc.startFaceScan {
                                portraitCellIndices = IntArray(0)
                                landscapeCellIndices = IntArray(0)
                                scope.launch { reloadAllBitmaps() }
                            }
                        }
                    } else {
                        svc.faceScanJob?.cancel()
                        svc.facesOnlyImages = emptyList()
                        svc.clearFacesOnlyCache()
                        scope.launch { reloadAllBitmaps() }
                    }
                }

                AppPreferences.KEY_RESCAN -> {
                    if (prefs.rescan) {
                        prefs.rescan = false
                        val svc = this@LiveWallpaperService
                        // Cancel any running jobs and wipe all cached data
                        svc.scanJob?.cancel()
                        svc.faceScanJob?.cancel()
                        svc.faceCache.clear()
                        svc.facesOnlyImages = emptyList()
                        svc.clearFacesOnlyCache()
                        try { ImageScanner.cacheFile(svc).delete() } catch (_: Exception) {}
                        svc.rawImages = emptyList()
                        svc.images = emptyList()
                        svc.scanComplete = false
                        // Fresh scan — startLoading will check facesOnlyEnabled on completion
                        startLoading()
                    }
                }

                AppPreferences.KEY_SLIDE_INTERVAL -> {
                    if (isVisible) scheduleNextAdvance()
                }
            }
        }

        // Start the loading sequence: show spinner, then decode once images are ready.
        private fun startLoading() {
            portraitCellIndices = IntArray(0)
            landscapeCellIndices = IntArray(0)
            advanceCellPos = 0
            isLoading = true
            startLoadingAnimation()
            this@LiveWallpaperService.ensureImages(prefs) {
                scope.launch {
                    if (prefs.facesOnlyEnabled) {
                        val cached = withContext(Dispatchers.IO) {
                            this@LiveWallpaperService.loadFacesOnlyCache()
                        }
                        if (cached != null) {
                            // Restore from disk — no scan needed, instant startup
                            this@LiveWallpaperService.facesOnlyImages = cached
                            reloadAllBitmaps()
                        } else {
                            // No valid cache — show full set immediately, scan in background
                            reloadAllBitmaps()
                            this@LiveWallpaperService.startFaceScan {
                                portraitCellIndices = IntArray(0)
                                landscapeCellIndices = IntArray(0)
                                scope.launch { reloadAllBitmaps() }
                            }
                        }
                    } else {
                        reloadAllBitmaps()
                    }
                }
            }
        }

        private fun startLoadingAnimation() {
            loadingStartTime = SystemClock.uptimeMillis()
            loadingHeadAngle = 0f
            loadingTailAngle = 0f
            loadingPrevTime  = 0L
            handler.removeCallbacks(spinnerRunnable)
            handler.post(spinnerRunnable)
        }

        private fun stopLoadingAnimation() {
            handler.removeCallbacks(spinnerRunnable)
        }

        private suspend fun reloadAllBitmaps() {
            if (surfaceWidth == 0 || surfaceHeight == 0) return
            val imgs = this@LiveWallpaperService.effectiveImages
            val rects = cellRects()
            val needed = rects.size

            val saved = if (isLandscape) landscapeCellIndices else portraitCellIndices
            cellIndices = if (saved.size == needed && imgs.isNotEmpty()) {
                IntArray(needed) { i -> saved[i] % imgs.size }
            } else {
                val step = if (imgs.size > needed) imgs.size / needed else 1
                val offset = if (imgs.isEmpty()) 0 else (0 until imgs.size).random()
                IntArray(needed) { i -> if (imgs.isEmpty()) 0 else (offset + i * step) % imgs.size }
            }
            if (isLandscape) landscapeCellIndices = cellIndices.copyOf()
            else portraitCellIndices = cellIndices.copyOf()
            advanceCellPos = if (needed > 0) advanceCellPos % needed else 0

            // ③ Parallel decode + face detection — all cells processed concurrently
            val cellData = withContext(Dispatchers.IO) {
                coroutineScope {
                    (0 until needed).map { i ->
                        async {
                            if (imgs.isEmpty()) return@async null to null
                            val r = rects[i]
                            val bmp = try { decodeSampledBitmap(imgs[cellIndices[i]], r.width().toInt(), r.height().toInt()) }
                                      catch (_: Exception) { null }
                            val focus = if (bmp != null && prefs.centerFacesEnabled) detectFocusPoint(bmp, imgs[cellIndices[i]]) else null
                            bmp to focus
                        }
                    }.awaitAll()
                }
            }
            val newBitmaps: Array<Bitmap?> = Array(needed) { cellData[it]?.first }
            val newFocusPoints: Array<android.graphics.PointF?> = Array(needed) { cellData[it]?.second }

            handler.removeCallbacks(fadeRunnable)
            val oldBitmaps = bitmaps; val oldFading = fadingBitmaps
            bitmaps = newBitmaps
            fadingBitmaps = Array(needed) { null }
            focusPoints = newFocusPoints
            fadingFocusPoints = Array(needed) { null }
            fadeAlphas = FloatArray(needed) { 1f }
            fadeStartTimes = LongArray(needed) { 0L }
            fadeDurations = LongArray(needed) { 0L }
            oldBitmaps.forEach { it?.recycle() }
            oldFading.forEach { it?.recycle() }
            isLoading = false
            stopLoadingAnimation()
            drawFrame()
        }

        private suspend fun refreshBitmaps() {
            if (surfaceWidth == 0 || surfaceHeight == 0) return
            val imgs = this@LiveWallpaperService.effectiveImages
            val rects = cellRects()
            val needed = rects.size
            if (cellIndices.size != needed) { reloadAllBitmaps(); return }

            val cellData = withContext(Dispatchers.IO) {
                coroutineScope {
                    (0 until needed).map { i ->
                        async {
                            if (imgs.isEmpty()) return@async null to null
                            val r = rects[i]
                            val bmp = try { decodeSampledBitmap(imgs[cellIndices[i]], r.width().toInt(), r.height().toInt()) }
                                      catch (_: Exception) { null }
                            val focus = if (bmp != null && prefs.centerFacesEnabled) detectFocusPoint(bmp, imgs[cellIndices[i]]) else null
                            bmp to focus
                        }
                    }.awaitAll()
                }
            }
            val newBitmaps: Array<Bitmap?> = Array(needed) { cellData[it]?.first }
            val newFocusPoints: Array<android.graphics.PointF?> = Array(needed) { cellData[it]?.second }

            handler.removeCallbacks(fadeRunnable)
            val oldBitmaps = bitmaps; val oldFading = fadingBitmaps
            bitmaps = newBitmaps
            fadingBitmaps = Array(needed) { null }
            focusPoints = newFocusPoints
            fadingFocusPoints = Array(needed) { null }
            fadeAlphas = FloatArray(needed) { 1f }
            fadeStartTimes = LongArray(needed) { 0L }
            fadeDurations = LongArray(needed) { 0L }
            oldBitmaps.forEach { it?.recycle() }
            oldFading.forEach { it?.recycle() }
            if (isLandscape) landscapeCellIndices = cellIndices.copyOf()
            else portraitCellIndices = cellIndices.copyOf()
            drawFrame()
        }

        private fun cellAtPosition(x: Float, y: Float): Int {
            val rects = cellRects()
            val idx = rects.indexOfFirst { it.contains(x, y) }
            if (idx >= 0) return idx
            return rects.indices.minByOrNull { i ->
                val cx = rects[i].centerX() - x; val cy = rects[i].centerY() - y
                cx * cx + cy * cy
            }?.coerceIn(0, cellIndices.size - 1) ?: 0
        }

        private suspend fun reloadCell(cellPos: Int, fadeDuration: Long = 800) {
            if (surfaceWidth == 0 || surfaceHeight == 0) return
            if (cellPos >= bitmaps.size || cellPos >= cellIndices.size) return
            val rects = cellRects()
            if (cellPos >= rects.size) return
            val imgs = this@LiveWallpaperService.effectiveImages
            if (imgs.isEmpty()) return
            val r = rects[cellPos]
            val imageIdx = cellIndices[cellPos] % imgs.size

            val newBitmap = withContext(Dispatchers.IO) {
                try { decodeSampledBitmap(imgs[imageIdx], r.width().toInt(), r.height().toInt()) }
                catch (_: Exception) { null }
            }
            val newFocus = if (newBitmap != null && prefs.centerFacesEnabled) detectFocusPoint(newBitmap, imgs[imageIdx]) else null
            val oldFocus = focusPoints.getOrNull(cellPos)
            if (cellPos < focusPoints.size) focusPoints[cellPos] = newFocus
            if (cellPos < fadingFocusPoints.size) fadingFocusPoints[cellPos] = oldFocus

            if (fadeDuration <= 0L) {
                fadingBitmaps[cellPos]?.recycle()
                fadingBitmaps[cellPos] = null
                bitmaps[cellPos]?.recycle()
                bitmaps[cellPos] = newBitmap
                fadeAlphas[cellPos] = 1f
                fadeStartTimes[cellPos] = 0L
                drawFrame()
            } else {
                fadingBitmaps[cellPos]?.recycle()
                fadingBitmaps[cellPos] = bitmaps[cellPos]
                bitmaps[cellPos] = newBitmap
                fadeAlphas[cellPos] = 0f
                fadeStartTimes[cellPos] = SystemClock.uptimeMillis()
                fadeDurations[cellPos] = fadeDuration
                handler.removeCallbacks(fadeRunnable)
                handler.post(fadeRunnable)
            }
        }

        private fun decodeSampledBitmap(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
            return try {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    // info.size already reflects EXIF rotation — dimensions are correct orientation
                    val sampleSize = calculateSampleSize(
                        info.size.width, info.size.height, reqWidth, reqHeight
                    )
                    if (sampleSize > 1) decoder.setTargetSampleSize(sampleSize)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (_: Exception) { null }
        }

        private fun calculateSampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Int {
            var sampleSize = 1
            if (srcHeight > reqHeight || srcWidth > reqWidth) {
                val halfH = srcHeight / 2; val halfW = srcWidth / 2
                while ((halfH / sampleSize) >= reqHeight && (halfW / sampleSize) >= reqWidth)
                    sampleSize *= 2
            }
            return sampleSize
        }

        private fun drawFrame() {
            val holder = surfaceHolder; var canvas: Canvas? = null
            try { canvas = holder.lockHardwareCanvas(); canvas?.let { drawScene(it) } }
            finally { canvas?.let { holder.unlockCanvasAndPost(it) } }
        }

        private fun drawScene(canvas: Canvas) {
            bgPaint.color = prefs.gridColor
            canvas.drawRect(0f, 0f, surfaceWidth.toFloat(), surfaceHeight.toFloat(), bgPaint)

            val spacing = prefs.gridSpacing.toFloat()
            val cornerRadius = prefs.gridCornerRadius.toFloat()
            val rects = cellRects()
            val cols = currentCols
            val rows = currentRows

            bitmaps.forEachIndexed { idx, bitmap ->
                if (idx >= rects.size) return@forEachIndexed
                val col = idx % cols; val row = idx / cols
                val dest = cellDest(rects[idx], col, row, cols, rows, spacing)

                canvas.save()
                canvas.clipRoundedCell(dest, col, row, cols, rows, cornerRadius)

                val alpha = fadeAlphas.getOrElse(idx) { 1f }
                val fading = fadingBitmaps.getOrNull(idx)

                val centerFaces = prefs.centerFacesEnabled
                val focus       = if (centerFaces) focusPoints.getOrNull(idx) else null
                val fadingFocus = if (centerFaces) fadingFocusPoints.getOrNull(idx) else null

                if (fading != null) {
                    paint.alpha = 255
                    canvas.drawBitmap(fading, centerCropRect(fading.width, fading.height, dest.width().toInt(), dest.height().toInt(), fadingFocus?.x ?: 0.5f, fadingFocus?.y ?: 0.5f), dest, paint)
                }
                if (bitmap != null) {
                    paint.alpha = (alpha * 255).toInt()
                    canvas.drawBitmap(bitmap, centerCropRect(bitmap.width, bitmap.height, dest.width().toInt(), dest.height().toInt(), focus?.x ?: 0.5f, focus?.y ?: 0.5f), dest, paint)
                    paint.alpha = 255
                } else if (fading == null) {
                    placeholderPaint.color = placeholderColors[idx % 4]
                    canvas.drawRect(dest, placeholderPaint)
                }

                canvas.restore()
            }

            if (bitmaps.isEmpty()) {
                rects.forEachIndexed { idx, r ->
                    val col = idx % cols; val row = idx / cols
                    val dest = cellDest(r, col, row, cols, rows, spacing)
                    canvas.save()
                    canvas.clipRoundedCell(dest, col, row, cols, rows, cornerRadius)
                    placeholderPaint.color = placeholderColors[idx % 4]
                    canvas.drawRect(dest, placeholderPaint)
                    canvas.restore()
                }
            }

            if (!isLoading && images.isEmpty()) drawNoImagesLabel(canvas)
            if (isLoading) drawLoadingPill(canvas)
        }

        private fun cellDest(r: RectF, col: Int, row: Int, cols: Int, rows: Int, spacing: Float) = RectF(
            if (col > 0) r.left + spacing else r.left,
            if (row > 0) r.top + spacing else r.top,
            if (col < cols - 1) r.right - spacing else r.right,
            if (row < rows - 1) r.bottom - spacing else r.bottom
        )

        private fun Canvas.clipRoundedCell(dest: RectF, col: Int, row: Int, cols: Int, rows: Int, cornerRadius: Float) {
            if (cornerRadius <= 0f) return
            val tlR = if (col > 0 && row > 0) cornerRadius else 0f
            val trR = if (col < cols - 1 && row > 0) cornerRadius else 0f
            val brR = if (col < cols - 1 && row < rows - 1) cornerRadius else 0f
            val blR = if (col > 0 && row < rows - 1) cornerRadius else 0f
            clipPath(Path().apply {
                addRoundRect(dest, floatArrayOf(tlR, tlR, trR, trR, brR, brR, blR, blR), Path.Direction.CW)
            })
        }

        private fun drawLoadingPill(canvas: Canvas) {
            val text = this@LiveWallpaperService.getString(com.gomaed.photoslide.R.string.loading)
            noImagesTextPaint.textSize = 20f * resources.displayMetrics.density * resources.configuration.fontScale
            val fm = noImagesTextPaint.fontMetrics
            val textW = noImagesTextPaint.measureText(text)
            val textH = fm.descent - fm.ascent

            val spinnerSize = textH * 0.85f
            val gap = textH * 0.5f
            val padH = textH * 1.1f
            val padV = textH * 0.6f
            val contentW = spinnerSize + gap + textW
            val pillW = contentW + padH * 2f
            val pillH = textH + padV * 2f
            val radius = pillH / 2f

            val cx = surfaceWidth / 2f
            val cy = surfaceHeight / 2f
            val left = cx - pillW / 2f
            val top = cy - pillH / 2f
            val right = cx + pillW / 2f
            val bottom = cy + pillH / 2f

            // Shadow — three concentric semi-transparent layers for a soft even glow
            noImagesScrimPaint.color = Color.argb(40, 0, 0, 0)
            canvas.drawRoundRect(left - 2.4f, top - 2.4f, right + 2.4f, bottom + 2.4f, radius + 2.4f, radius + 2.4f, noImagesScrimPaint)
            noImagesScrimPaint.color = Color.argb(25, 0, 0, 0)
            canvas.drawRoundRect(left - 4f, top - 4f, right + 4f, bottom + 4f, radius + 4f, radius + 4f, noImagesScrimPaint)
            noImagesScrimPaint.color = Color.argb(15, 0, 0, 0)
            canvas.drawRoundRect(left - 5.6f, top - 5.6f, right + 5.6f, bottom + 5.6f, radius + 5.6f, radius + 5.6f, noImagesScrimPaint)

            // Pill background
            val pillColor = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                resources.getColor(android.R.color.system_neutral1_800, null)
            else Color.argb(230, 40, 38, 46)
            noImagesScrimPaint.color = pillColor
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, noImagesScrimPaint)

            // Small spinner on the left of the content
            val contentLeft = cx - contentW / 2f
            val spinnerCx = contentLeft + spinnerSize / 2f
            val spinnerRadius = spinnerSize / 2f
            val strokeWidth = spinnerSize * 0.15f
            spinnerTrackPaint.strokeWidth = strokeWidth
            spinnerPaint.strokeWidth = strokeWidth
            val oval = RectF(spinnerCx - spinnerRadius, cy - spinnerRadius,
                             spinnerCx + spinnerRadius, cy + spinnerRadius)
            canvas.drawArc(oval, 0f, 360f, false, spinnerTrackPaint)
            canvas.drawArc(oval, loadingRotation - 90f, loadingSweep, false, spinnerPaint)

            // Text to the right of the spinner
            val textCenterX = contentLeft + spinnerSize + gap + textW / 2f
            val baselineY = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText(text, textCenterX, baselineY, noImagesTextPaint)
        }

        private fun drawNoImagesLabel(canvas: Canvas) {
            val text = this@LiveWallpaperService.getString(com.gomaed.photoslide.R.string.no_pictures_selected)
            noImagesTextPaint.textSize = 20f * resources.displayMetrics.density * resources.configuration.fontScale
            val fm = noImagesTextPaint.fontMetrics
            val textW = noImagesTextPaint.measureText(text)
            val textH = fm.descent - fm.ascent

            val padH = textH * 1.1f
            val padV = textH * 0.6f
            val pillW = textW + padH * 2f
            val pillH = textH + padV * 2f
            val radius = pillH / 2f

            val cx = surfaceWidth / 2f
            val cy = surfaceHeight / 2f
            val left = cx - pillW / 2f
            val top = cy - pillH / 2f
            val right = cx + pillW / 2f
            val bottom = cy + pillH / 2f

            // Store pill bounds for tap detection
            pillRect.set(left, top, right, bottom)

            // Pill background — Material You neutral surface color
            val pillColor = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                resources.getColor(android.R.color.system_neutral1_800, null)
            else
                Color.argb(230, 40, 38, 46)
            noImagesScrimPaint.color = pillColor
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, noImagesScrimPaint)

            val baselineY = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText(text, cx, baselineY, noImagesTextPaint)
        }

        private fun centerCropRect(bw: Int, bh: Int, dw: Int, dh: Int, focusX: Float = 0.5f, focusY: Float = 0.5f): Rect {
            if (dw <= 0 || dh <= 0) return Rect(0, 0, bw, bh)
            val srcAspect = bw.toFloat() / bh; val dstAspect = dw.toFloat() / dh
            return if (srcAspect > dstAspect) {
                val scaledW = (bh * dstAspect).toInt()
                val xOff = (focusX * bw - scaledW / 2f).toInt().coerceIn(0, (bw - scaledW).coerceAtLeast(0))
                Rect(xOff, 0, xOff + scaledW, bh)
            } else {
                val scaledH = (bw / dstAspect).toInt()
                val yOff = (focusY * bh - scaledH / 2f).toInt().coerceIn(0, (bh - scaledH).coerceAtLeast(0))
                Rect(0, yOff, bw, yOff + scaledH)
            }
        }

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        private fun smoothStep(t: Float) = t * t * (3f - 2f * t)

        private fun recycleBitmaps() {
            bitmaps.forEach { it?.recycle() }; bitmaps = emptyArray()
            fadingBitmaps.forEach { it?.recycle() }; fadingBitmaps = emptyArray()
        }
    }
}
