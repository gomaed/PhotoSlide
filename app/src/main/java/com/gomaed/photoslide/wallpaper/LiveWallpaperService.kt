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
import com.gomaed.photoslide.data.AppPreferences
import com.gomaed.photoslide.data.FaceScanner
import com.gomaed.photoslide.data.ImageScanner
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
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
import kotlinx.coroutines.withContext

class LiveWallpaperService : WallpaperService() {

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

    /** The image pool engines should use — respects the faces-only toggle. */
    val effectiveImages: List<Uri>
        get() = if (servicePrefs.facesOnlyEnabled && facesOnlyImages.isNotEmpty()) facesOnlyImages
                else images

    override fun onCreateEngine(): Engine = WallpaperEngine()

    override fun onDestroy() {
        super.onDestroy()
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
            } else if (ImageScanner.isScanning) {
                // A fragment-triggered scan is already writing the cache — don't start a
                // duplicate. KEY_URI_CACHE_UPDATED will fire when it finishes and the
                // engine will call startLoading() to retry with the fresh cache.
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
        FaceScanner.clearCache(this)
        FaceScanner.cancelScan()
        scanJob?.cancel()
        // Folder set changed — saved URIs are stale; clear so reboot starts fresh random.
        servicePrefs.portraitCellUris = ""
        servicePrefs.landscapeCellUris = ""
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

        private var refreshJob: Job? = null

        // ── Preloaded bitmaps for the other orientation ───────────────────────────
        // Decoded in the background while the current orientation is displayed.
        // On rotation: if ready, swap in immediately (no decode, no flash).
        private var otherBitmaps: Array<Bitmap?> = emptyArray()
        private var otherFocusPoints: Array<android.graphics.PointF?> = emptyArray()
        private var otherCellIndices: IntArray = IntArray(0)
        private var otherPreloadJob: Job? = null

        // ── Preloaded next bitmaps for the current orientation ────────────────────
        // One bitmap per cell — the image that will appear on the next advance.
        // On advance: if ready, use immediately (no decode, instant crossfade start).
        private var nextBitmaps: Array<Bitmap?> = emptyArray()
        private var nextFocusPoints: Array<android.graphics.PointF?> = emptyArray()
        private var nextPreloadJob: Job? = null

        private var cellIndices: IntArray = IntArray(0)
        private var portraitCellIndices: IntArray = IntArray(0)
        private var landscapeCellIndices: IntArray = IntArray(0)
        private var advanceCellPos = 0
        private var advanceCellQueue: ArrayDeque<Int> = ArrayDeque()
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
                                prefs.lastAdvanceTime = System.currentTimeMillis()
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

        private fun cellRectsFor(width: Int, height: Int, cols: Int, rows: Int): List<RectF> {
            val cellW = width / cols.toFloat()
            val uniformCellH = height / rows.toFloat()
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
                val k = height / (tallCount * ratio + shortCount * (1f - ratio))
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

        private fun cellRects() = cellRectsFor(surfaceWidth, surfaceHeight, currentCols, currentRows)

        private val advanceRunnable = object : Runnable {
            override fun run() {
                try {
                    val imgs = this@LiveWallpaperService.effectiveImages
                    // Snapshot cellIndices locally — the main thread can replace the array
                    // between the isNotEmpty() check and the % size operation, which would
                    // cause an ArithmeticException (divide by zero) and break the chain.
                    val indices = cellIndices
                    if (imgs.isNotEmpty() && indices.isNotEmpty()) {
                        if (advanceCellQueue.isEmpty())
                            advanceCellQueue.addAll(indices.indices.shuffled())
                        advanceCellPos = advanceCellQueue.removeFirst()
                        indices[advanceCellPos] = (indices[advanceCellPos] + 1) % imgs.size
                        val cell = advanceCellPos
                        scope.launch { reloadCell(cell, prefs.fadeDuration.toLong()) }
                    }
                } catch (_: Exception) {
                    // Swallow any unexpected error so the finally block always runs.
                } finally {
                    // Always reschedule — even if something above threw, the timer must
                    // keep ticking so pictures don't freeze permanently.
                    prefs.lastAdvanceTime = System.currentTimeMillis()
                    if (isVisible) scheduleNextAdvance()
                }
            }
        }

        private fun scheduleNextAdvance() {
            handler.removeCallbacks(advanceRunnable)
            val interval = prefs.slideInterval
            if (interval == AppPreferences.INTERVAL_NEVER) return
            val intervalMs = interval * 1000L
            val elapsed = System.currentTimeMillis() - prefs.lastAdvanceTime
            val delay = (intervalMs - elapsed).coerceIn(0L, intervalMs)
            handler.postDelayed(advanceRunnable, delay)
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
            otherPreloadJob?.cancel()
            nextPreloadJob?.cancel()
            // Sync live cellIndices (may have advanced since last full reload) then persist.
            if (cellIndices.isNotEmpty()) {
                if (isLandscape) landscapeCellIndices = cellIndices.copyOf()
                else portraitCellIndices = cellIndices.copyOf()
            }
            saveCellUris(this@LiveWallpaperService.effectiveImages)
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
                    // Guard against a duplicate startLoading() — onSurfaceChanged may have
                    // already started loading while images are still empty (e.g. during the
                    // initial startup sequence).  Two concurrent loads produce two different
                    // random shuffles, causing the second reloadAllBitmaps() to map the
                    // restored indices against the wrong shuffle and show new photos.
                    if (!isLoading) startLoading()
                } else if (refreshJob?.isActive != true) {
                    // onSurfaceChanged already launched a refresh during rotation — skip the
                    // duplicate so the two decodes don't race each other and cause jank.
                    refreshJob = scope.launch { refreshBitmaps() }
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
            val svc = this@LiveWallpaperService
            if (svc.images.isNotEmpty()) {
                val imgs = svc.effectiveImages
                val newCols = if (isLandscape) prefs.landscapeCols else prefs.portraitCols
                val newRows = if (isLandscape) prefs.landscapeRows else prefs.portraitRows
                val needed  = newCols * newRows

                if (otherBitmaps.size == needed && otherBitmaps.any { it != null }) {
                    // ── Instant swap — preloaded bitmaps are ready ────────────────
                    refreshJob?.cancel()
                    nextPreloadJob?.cancel()

                    bitmaps.forEach { it?.recycle() }
                    fadingBitmaps.forEach { it?.recycle() }
                    nextBitmaps.forEach { it?.recycle() }

                    cellIndices      = otherCellIndices
                    bitmaps          = otherBitmaps
                    focusPoints      = otherFocusPoints
                    fadingBitmaps    = Array(needed) { null }
                    fadingFocusPoints = Array(needed) { null }
                    fadeAlphas       = FloatArray(needed) { 1f }
                    fadeStartTimes   = LongArray(needed) { 0L }
                    fadeDurations    = LongArray(needed) { 0L }

                    otherBitmaps    = emptyArray()
                    otherFocusPoints = emptyArray()
                    otherCellIndices = IntArray(0)
                    otherPreloadJob = null

                    if (isLandscape) landscapeCellIndices = cellIndices.copyOf()
                    else portraitCellIndices = cellIndices.copyOf()
                    advanceCellPos = if (needed > 0) advanceCellPos % needed else 0
                    advanceCellQueue.clear()

                    nextBitmaps    = Array(needed) { null }
                    nextFocusPoints = Array(needed) { null }

                    saveCellUris(imgs)
                    drawFrame()
                    preloadNextBitmaps(imgs)
                    preloadOtherOrientation(imgs)
                } else {
                    // ── Standard decode — preloaded bitmaps not ready yet ─────────
                    refreshJob?.cancel()
                    nextPreloadJob?.cancel()
                    otherPreloadJob?.cancel()
                    nextBitmaps.forEach { it?.recycle() }
                    nextBitmaps    = emptyArray()
                    nextFocusPoints = emptyArray()
                    otherBitmaps.forEach { it?.recycle() }
                    otherBitmaps    = emptyArray()
                    otherFocusPoints = emptyArray()
                    otherCellIndices = IntArray(0)
                    refreshJob = scope.launch { refreshBitmaps() }
                }
            } else if (!isLoading) {
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
                    if (prefs.centerFacesEnabled) {
                        // Sync saved indices from live state so reloadAllBitmaps() keeps
                        // the same images visible — just re-decoded with face-centering.
                        if (isLandscape) landscapeCellIndices = cellIndices.copyOf()
                        else portraitCellIndices = cellIndices.copyOf()
                        scope.launch { reloadAllBitmaps() }
                    } else drawFrame()
                }

                AppPreferences.KEY_FACES_ONLY -> {
                    val svc = this@LiveWallpaperService
                    if (prefs.facesOnlyEnabled) {
                        // Only start a new scan if no URI scan and no face scan are running.
                        if (svc.scanJob?.isActive != true && svc.images.isNotEmpty()
                            && !FaceScanner.isScanning) {
                            FaceScanner.startScan(svc, svc.images, prefs)
                        }
                    } else {
                        FaceScanner.cancelScan()
                        svc.facesOnlyImages = emptyList()
                        FaceScanner.clearCache(svc)
                        scope.launch { reloadAllBitmaps() }
                    }
                }

                AppPreferences.KEY_RESCAN -> {
                    if (prefs.rescan) {
                        prefs.rescan = false
                        val svc = this@LiveWallpaperService
                        // Cancel any running jobs and wipe all cached data
                        svc.scanJob?.cancel()
                        FaceScanner.cancelScan()
                        FaceScanner.faceCache.clear()
                        svc.facesOnlyImages = emptyList()
                        FaceScanner.clearCache(svc)
                        try { ImageScanner.cacheFile(svc).delete() } catch (_: Exception) {}
                        svc.rawImages = emptyList()
                        svc.images = emptyList()
                        svc.scanComplete = false
                        // Wipe saved selections — library changed, old URIs are stale.
                        prefs.portraitCellUris = ""
                        prefs.landscapeCellUris = ""
                        // Cancel decode/preload jobs and clear bitmaps immediately so the
                        // old photos are not shown behind the loading spinner during rescan.
                        refreshJob?.cancel(); otherPreloadJob?.cancel(); nextPreloadJob?.cancel()
                        handler.removeCallbacks(fadeRunnable)
                        recycleBitmaps()
                        focusPoints = emptyArray(); fadingFocusPoints = emptyArray()
                        otherFocusPoints = emptyArray(); nextFocusPoints = emptyArray()
                        otherCellIndices = IntArray(0)
                        fadeAlphas = FloatArray(0); fadeStartTimes = LongArray(0); fadeDurations = LongArray(0)
                        drawFrame()
                        // Fresh scan — startLoading will check facesOnlyEnabled on completion
                        startLoading()
                    }
                }

                AppPreferences.KEY_URI_CACHE_UPDATED -> {
                    // ImageScanner.startScan() just wrote the cache. If the engine is still
                    // waiting for images (ensureImages skipped rescan while scan was running),
                    // retry now that the cache is ready.
                    if (this@LiveWallpaperService.images.isEmpty()) {
                        startLoading()
                    }
                }

                AppPreferences.KEY_FACE_CACHE_UPDATED -> {
                    // FaceScanner finished a scan (from fragment or service path).
                    // Reload facesOnlyImages from the freshly written cache and redraw.
                    // Note: do NOT check FaceScanner.isScanning here — the counter increment
                    // happens inside the still-active scan coroutine, so isScanning is still
                    // true at this point. The counter changing IS the completion signal.
                    val svc = this@LiveWallpaperService
                    if (prefs.facesOnlyEnabled &&
                        svc.images.isNotEmpty()) {
                        scope.launch {
                            val cached = withContext(Dispatchers.IO) {
                                FaceScanner.loadCache(svc, prefs)
                            }
                            if (cached != null) {
                                svc.facesOnlyImages = cached
                                portraitCellIndices = IntArray(0)
                                landscapeCellIndices = IntArray(0)
                                reloadAllBitmaps()
                            }
                        }
                    }
                }

                AppPreferences.KEY_SLIDE_INTERVAL -> {
                    if (isVisible) scheduleNextAdvance()
                }
            }
        }

        // Start the loading sequence: show spinner, then decode once images are ready.
        private fun startLoading() {
            // A rescan may have been requested while the engine was not running
            // (prefs.rescan = true was never handled because no listener was registered).
            // Honour it now so caches are wiped and a fresh face scan will follow.
            if (prefs.rescan) {
                prefs.rescan = false
                val svc = this@LiveWallpaperService
                svc.scanJob?.cancel()
                FaceScanner.cancelScan()
                FaceScanner.faceCache.clear()
                svc.facesOnlyImages = emptyList()
                FaceScanner.clearCache(svc)
                try { ImageScanner.cacheFile(svc).delete() } catch (_: Exception) {}
                svc.rawImages = emptyList()
                svc.images = emptyList()
                svc.scanComplete = false
            }
            portraitCellIndices = IntArray(0)
            landscapeCellIndices = IntArray(0)
            advanceCellPos = 0
            isLoading = true
            startLoadingAnimation()
            this@LiveWallpaperService.ensureImages(prefs) {
                scope.launch {
                    if (prefs.facesOnlyEnabled) {
                        val svc = this@LiveWallpaperService
                        val cached = withContext(Dispatchers.IO) {
                            FaceScanner.loadCache(svc, prefs)
                        }
                        if (cached != null) {
                            // Restore from disk — no scan needed, instant startup
                            svc.facesOnlyImages = cached
                            reloadAllBitmaps()
                        } else {
                            // No valid cache — show full set immediately, scan in background
                            reloadAllBitmaps()
                            // Don't interrupt a scan already in progress (e.g. started from
                            // the fragment before the wallpaper was set). KEY_FACE_CACHE_UPDATED
                            // will trigger a reload when it finishes.
                            if (!FaceScanner.isScanning) {
                                FaceScanner.startScan(svc, svc.images, prefs)
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

        /**
         * Decode bitmaps for the other orientation in the background.
         * Swapped surface dimensions are used to approximate cell sizes — close enough
         * for power-of-2 sampling; the renderer scales to exact cell bounds at draw time.
         * On rotation, [onSurfaceChanged] swaps these in instantly if they are ready.
         */
        private fun preloadOtherOrientation(imgs: List<Uri>) {
            otherPreloadJob?.cancel()
            if (imgs.isEmpty() || surfaceWidth == 0 || surfaceHeight == 0) return
            otherPreloadJob = scope.launch {
                val otherLandscape = !isLandscape
                val otherCols = if (otherLandscape) prefs.landscapeCols else prefs.portraitCols
                val otherRows = if (otherLandscape) prefs.landscapeRows else prefs.portraitRows
                val rects = cellRectsFor(surfaceHeight, surfaceWidth, otherCols, otherRows)
                val needed = rects.size

                val savedInMemory = if (otherLandscape) landscapeCellIndices else portraitCellIndices
                val indices = if (savedInMemory.size == needed) {
                    IntArray(needed) { i -> savedInMemory[i] % imgs.size }
                } else {
                    restoreCellIndices(needed, imgs, otherLandscape) ?: run {
                        val step = if (imgs.size > needed) imgs.size / needed else 1
                        val offset = (0 until imgs.size).random()
                        IntArray(needed) { i -> (offset + i * step) % imgs.size }
                    }
                }

                val cellData = withContext(Dispatchers.IO) {
                    coroutineScope {
                        (0 until needed).map { i ->
                            async {
                                val r = rects[i]
                                val bmp = try { decodeSampledBitmap(imgs[indices[i]], r.width().toInt(), r.height().toInt()) }
                                          catch (_: Exception) { null }
                                val focus = if (bmp != null && prefs.centerFacesEnabled)
                                    FaceScanner.detectFocus(this@LiveWallpaperService, imgs[indices[i]]) else null
                                bmp to focus
                            }
                        }.awaitAll()
                    }
                }

                if (!isActive) { cellData.forEach { it.first?.recycle() }; return@launch }

                val oldOther = otherBitmaps
                otherCellIndices = indices
                otherBitmaps = Array(needed) { cellData[it].first }
                otherFocusPoints = Array(needed) { cellData[it].second }
                oldOther.forEach { it?.recycle() }

                // If the selection was randomly chosen (orientation never visited this session),
                // persist it so a rotation shows the same images we just preloaded.
                val alreadySaved = if (otherLandscape) landscapeCellIndices else portraitCellIndices
                if (alreadySaved.isEmpty()) {
                    if (otherLandscape) landscapeCellIndices = indices else portraitCellIndices = indices
                    saveCellUris(imgs)
                }
            }
        }

        /**
         * Decode the next-up bitmap for every cell in the background.
         * On advance (timer or double-tap), [reloadCell] uses these immediately
         * instead of decoding — enabling instant crossfade starts.
         */
        private fun preloadNextBitmaps(imgs: List<Uri>) {
            nextPreloadJob?.cancel()
            if (imgs.isEmpty() || cellIndices.isEmpty()) return
            val capturedIndices = cellIndices.copyOf()
            nextPreloadJob = scope.launch {
                val rects = cellRects()
                val cellData = withContext(Dispatchers.IO) {
                    coroutineScope {
                        capturedIndices.indices.map { i ->
                            async {
                                if (i >= rects.size) return@async null to null
                                val nextIdx = (capturedIndices[i] + 1) % imgs.size
                                val r = rects[i]
                                val bmp = try { decodeSampledBitmap(imgs[nextIdx], r.width().toInt(), r.height().toInt()) }
                                          catch (_: Exception) { null }
                                val focus = if (bmp != null && prefs.centerFacesEnabled)
                                    FaceScanner.detectFocus(this@LiveWallpaperService, imgs[nextIdx]) else null
                                bmp to focus
                            }
                        }.awaitAll()
                    }
                }

                if (!isActive) { cellData.forEach { it?.first?.recycle() }; return@launch }

                val oldNext = nextBitmaps
                nextBitmaps = Array(capturedIndices.size) { cellData.getOrNull(it)?.first }
                nextFocusPoints = Array(capturedIndices.size) { cellData.getOrNull(it)?.second }
                oldNext.forEach { it?.recycle() }
            }
        }

        /**
         * Preload the next bitmap for a single cell after it has just advanced.
         * Called by [reloadCell] once the advance bitmap has been consumed.
         */
        private fun preloadNextBitmapForCell(cell: Int, imgs: List<Uri>) {
            if (cell >= cellIndices.size || imgs.isEmpty()) return
            val nextIdx = (cellIndices[cell] + 1) % imgs.size
            scope.launch {
                val rects = cellRects()
                if (cell >= rects.size) return@launch
                val r = rects[cell]
                val bmp = withContext(Dispatchers.IO) {
                    try { decodeSampledBitmap(imgs[nextIdx], r.width().toInt(), r.height().toInt()) }
                    catch (_: Exception) { null }
                }
                val focus = if (bmp != null && prefs.centerFacesEnabled)
                    FaceScanner.detectFocus(this@LiveWallpaperService, imgs[nextIdx]) else null
                // Only store if the cell still expects this exact next image.
                if (cell < nextBitmaps.size && cell < cellIndices.size &&
                    (cellIndices[cell] + 1) % imgs.size == nextIdx) {
                    nextBitmaps[cell]?.recycle()
                    nextBitmaps[cell] = bmp
                    nextFocusPoints[cell] = focus
                } else {
                    bmp?.recycle()
                }
            }
        }

        /**
         * Persist both orientation URI lists so the wallpaper can restore the same
         * selection after a reboot.  Only saves orientations that have been visited
         * (non-empty index arrays).
         */
        private fun saveCellUris(imgs: List<Uri>) {
            if (imgs.isEmpty()) return
            if (portraitCellIndices.isNotEmpty()) {
                prefs.portraitCellUris = portraitCellIndices.joinToString("\n") { idx ->
                    imgs[idx % imgs.size].toString()
                }
            }
            if (landscapeCellIndices.isNotEmpty()) {
                prefs.landscapeCellUris = landscapeCellIndices.joinToString("\n") { idx ->
                    imgs[idx % imgs.size].toString()
                }
            }
        }

        /**
         * Try to map [needed] saved URIs back to indices in [imgs].
         * Returns null if the saved list is missing, the wrong size, or any URI is
         * no longer in the image pool — callers fall back to random selection.
         */
        private fun restoreCellIndices(needed: Int, imgs: List<Uri>, landscape: Boolean): IntArray? {
            val raw = if (landscape) prefs.landscapeCellUris else prefs.portraitCellUris
            if (raw.isBlank()) return null
            val uriStrings = raw.split("\n").filter { it.isNotBlank() }
            if (uriStrings.size != needed) return null
            val uriToIndex = HashMap<String, Int>(imgs.size * 2)
            imgs.forEachIndexed { idx, uri -> uriToIndex[uri.toString()] = idx }
            val indices = IntArray(needed)
            for (i in uriStrings.indices) {
                indices[i] = uriToIndex[uriStrings[i]] ?: return null
            }
            return indices
        }

        private suspend fun reloadAllBitmaps() {
            if (surfaceWidth == 0 || surfaceHeight == 0) return
            // Cancel any in-flight preloads — indices and images are about to change.
            otherPreloadJob?.cancel(); otherBitmaps.forEach { it?.recycle() }
            nextPreloadJob?.cancel();  nextBitmaps.forEach  { it?.recycle() }
            otherBitmaps = emptyArray(); otherFocusPoints = emptyArray(); otherCellIndices = IntArray(0)
            nextBitmaps  = emptyArray(); nextFocusPoints  = emptyArray()
            val imgs = this@LiveWallpaperService.effectiveImages
            val rects = cellRects()
            val needed = rects.size

            val saved = if (isLandscape) landscapeCellIndices else portraitCellIndices
            cellIndices = if (saved.size == needed && imgs.isNotEmpty()) {
                // In-memory selection from a previous rotation — use it directly.
                IntArray(needed) { i -> saved[i] % imgs.size }
            } else if (imgs.isNotEmpty()) {
                // No in-memory selection: try to restore from the persisted URI list.
                // Falls back to a fresh random spread if saved URIs don't match (reboot
                // after folder change, grid resize, or first launch).
                restoreCellIndices(needed, imgs, isLandscape) ?: run {
                    val step = if (imgs.size > needed) imgs.size / needed else 1
                    val offset = (0 until imgs.size).random()
                    IntArray(needed) { i -> (offset + i * step) % imgs.size }
                }
            } else {
                IntArray(needed) { 0 }
            }
            if (isLandscape) landscapeCellIndices = cellIndices.copyOf()
            else portraitCellIndices = cellIndices.copyOf()
            saveCellUris(imgs)
            advanceCellPos = if (needed > 0) advanceCellPos % needed else 0
            advanceCellQueue.clear()

            // ③ Parallel decode + face detection — all cells processed concurrently
            val cellData = withContext(Dispatchers.IO) {
                coroutineScope {
                    (0 until needed).map { i ->
                        async {
                            if (imgs.isEmpty()) return@async null to null
                            val r = rects[i]
                            val bmp = try { decodeSampledBitmap(imgs[cellIndices[i]], r.width().toInt(), r.height().toInt()) }
                                      catch (_: Exception) { null }
                            val focus = if (bmp != null && prefs.centerFacesEnabled) FaceScanner.detectFocus(this@LiveWallpaperService, imgs[cellIndices[i]]) else null
                            bmp to focus
                        }
                    }.awaitAll()
                }
            }
            val newBitmaps: Array<Bitmap?> = Array(needed) { cellData[it]?.first }
            val newFocusPoints: Array<android.graphics.PointF?> = Array(needed) { cellData[it]?.second }

            handler.removeCallbacks(fadeRunnable)
            val oldBitmaps     = bitmaps;      val oldFading      = fadingBitmaps
            val oldFocusPoints = focusPoints
            bitmaps      = newBitmaps
            focusPoints  = newFocusPoints
            val fadeDur = prefs.fadeDuration.toLong()
            // Cross-fade from old photos when they exist and sizes match; otherwise fade
            // from placeholder (initial load after spinner where bitmaps were cleared).
            val canCrossFade = fadeDur > 0 && oldBitmaps.size == needed && oldBitmaps.any { it != null }
            if (canCrossFade) {
                fadingBitmaps     = oldBitmaps   // recycled by fadeRunnable on completion
                fadingFocusPoints = if (oldFocusPoints.size == needed) oldFocusPoints else Array(needed) { null }
                val now = SystemClock.uptimeMillis()
                fadeAlphas     = FloatArray(needed) { 0f }
                fadeStartTimes = LongArray(needed) { now }
                fadeDurations  = LongArray(needed) { fadeDur }
            } else if (fadeDur > 0) {
                fadingBitmaps     = Array(needed) { null }
                fadingFocusPoints = Array(needed) { null }
                val now = SystemClock.uptimeMillis()
                fadeAlphas     = FloatArray(needed) { 0f }
                fadeStartTimes = LongArray(needed) { now }
                fadeDurations  = LongArray(needed) { fadeDur }
                oldBitmaps.forEach { it?.recycle() }
            } else {
                fadingBitmaps     = Array(needed) { null }
                fadingFocusPoints = Array(needed) { null }
                fadeAlphas     = FloatArray(needed) { 1f }
                fadeStartTimes = LongArray(needed) { 0L }
                fadeDurations  = LongArray(needed) { 0L }
                oldBitmaps.forEach { it?.recycle() }
            }
            oldFading.forEach { it?.recycle() }
            isLoading = false
            stopLoadingAnimation()
            nextBitmaps    = Array(needed) { null }
            nextFocusPoints = Array(needed) { null }
            drawFrame()
            if (fadeDur > 0) handler.post(fadeRunnable)
            preloadNextBitmaps(imgs)
            preloadOtherOrientation(imgs)
        }

        private suspend fun refreshBitmaps() {
            if (surfaceWidth == 0 || surfaceHeight == 0) return
            // Cancel any in-flight preloads — cell sizes are about to change.
            otherPreloadJob?.cancel(); otherBitmaps.forEach { it?.recycle() }
            nextPreloadJob?.cancel();  nextBitmaps.forEach  { it?.recycle() }
            otherBitmaps = emptyArray(); otherFocusPoints = emptyArray(); otherCellIndices = IntArray(0)
            nextBitmaps  = emptyArray(); nextFocusPoints  = emptyArray()
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
                            val focus = if (bmp != null && prefs.centerFacesEnabled) FaceScanner.detectFocus(this@LiveWallpaperService, imgs[cellIndices[i]]) else null
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
            saveCellUris(imgs)
            nextBitmaps    = Array(needed) { null }
            nextFocusPoints = Array(needed) { null }
            drawFrame()
            preloadNextBitmaps(imgs)
            preloadOtherOrientation(imgs)
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

            // Use preloaded next bitmap if available — no decode needed, instant start.
            val preloaded = nextBitmaps.getOrNull(cellPos)
            val newBitmap: Bitmap?
            val newFocus: android.graphics.PointF?
            if (preloaded != null) {
                newBitmap = preloaded
                newFocus  = nextFocusPoints.getOrNull(cellPos)
                nextBitmaps[cellPos]    = null
                nextFocusPoints[cellPos] = null
                preloadNextBitmapForCell(cellPos, imgs)
            } else {
                newBitmap = withContext(Dispatchers.IO) {
                    try { decodeSampledBitmap(imgs[imageIdx], r.width().toInt(), r.height().toInt()) }
                    catch (_: Exception) { null }
                }
                newFocus = if (newBitmap != null && prefs.centerFacesEnabled) FaceScanner.detectFocus(this@LiveWallpaperService, imgs[imageIdx]) else null
                preloadNextBitmapForCell(cellPos, imgs)
            }
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
                } else if (alpha < 1f) {
                    // Fading in from placeholder — draw placeholder colour behind incoming bitmap
                    placeholderPaint.color = placeholderColors[idx % 4]
                    canvas.drawRect(dest, placeholderPaint)
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
            otherBitmaps.forEach { it?.recycle() }; otherBitmaps = emptyArray()
            nextBitmaps.forEach { it?.recycle() }; nextBitmaps = emptyArray()
        }
    }
}
