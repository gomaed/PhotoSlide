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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
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
        scanJob?.cancel()
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
        private var loadingSweep = 15f
        private var loadingStep = 0

        // Drives the loading spinner — runs on handlerThread, independent of Choreographer.
        private val spinnerRunnable = object : Runnable {
            override fun run() {
                if (!isLoading) return
                loadingRotation = (loadingRotation + 5f) % 360f
                val step = loadingStep % 160
                loadingSweep = if (step < 80) 15f + step * (265f / 80f)
                               else 280f - (step - 80) * (265f / 80f)
                loadingStep++
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
                        val imgs = this@LiveWallpaperService.images
                        if (prefs.doubleTapAdvance && imgs.isNotEmpty() && cellIndices.isNotEmpty()) {
                            val cell = cellAtPosition(e.x, e.y)
                            cellIndices[cell] = (cellIndices[cell] + 1) % imgs.size
                            scope.launch { reloadCell(cell, if (prefs.fadeDuration == 0) 0L else 200L) }
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
                val imgs = this@LiveWallpaperService.images
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
                scope.launch { reloadAllBitmaps() }
            }
        }

        private fun startLoadingAnimation() {
            loadingRotation = 0f; loadingSweep = 15f; loadingStep = 0
            handler.removeCallbacks(spinnerRunnable)
            handler.post(spinnerRunnable)
        }

        private fun stopLoadingAnimation() {
            handler.removeCallbacks(spinnerRunnable)
        }

        private suspend fun reloadAllBitmaps() {
            if (surfaceWidth == 0 || surfaceHeight == 0) return
            val imgs = this@LiveWallpaperService.images
            val rects = cellRects()
            val needed = rects.size

            val saved = if (isLandscape) landscapeCellIndices else portraitCellIndices
            cellIndices = if (saved.size == needed && imgs.isNotEmpty()) {
                saved.copyOf()
            } else {
                val step = if (imgs.size > needed) imgs.size / needed else 1
                val offset = if (imgs.isEmpty()) 0 else (0 until imgs.size).random()
                IntArray(needed) { i -> if (imgs.isEmpty()) 0 else (offset + i * step) % imgs.size }
            }
            if (isLandscape) landscapeCellIndices = cellIndices.copyOf()
            else portraitCellIndices = cellIndices.copyOf()
            advanceCellPos = if (needed > 0) advanceCellPos % needed else 0

            // ③ Parallel decode — all cells decoded concurrently on the IO thread pool
            val newBitmaps = withContext(Dispatchers.IO) {
                coroutineScope {
                    (0 until needed).map { i ->
                        async {
                            if (imgs.isEmpty()) return@async null
                            val r = rects[i]
                            try { decodeSampledBitmap(imgs[cellIndices[i]], r.width().toInt(), r.height().toInt()) }
                            catch (_: Exception) { null }
                        }
                    }.awaitAll().toTypedArray()
                }
            }

            handler.removeCallbacks(fadeRunnable)
            val oldBitmaps = bitmaps; val oldFading = fadingBitmaps
            bitmaps = newBitmaps
            fadingBitmaps = Array(needed) { null }
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
            val imgs = this@LiveWallpaperService.images
            val rects = cellRects()
            val needed = rects.size
            if (cellIndices.size != needed) { reloadAllBitmaps(); return }

            val newBitmaps = withContext(Dispatchers.IO) {
                coroutineScope {
                    (0 until needed).map { i ->
                        async {
                            if (imgs.isEmpty()) return@async null
                            val r = rects[i]
                            try { decodeSampledBitmap(imgs[cellIndices[i]], r.width().toInt(), r.height().toInt()) }
                            catch (_: Exception) { null }
                        }
                    }.awaitAll().toTypedArray()
                }
            }

            handler.removeCallbacks(fadeRunnable)
            val oldBitmaps = bitmaps; val oldFading = fadingBitmaps
            bitmaps = newBitmaps
            fadingBitmaps = Array(needed) { null }
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
            val imgs = this@LiveWallpaperService.images
            if (imgs.isEmpty()) return
            val r = rects[cellPos]
            val imageIdx = cellIndices[cellPos] % imgs.size

            val newBitmap = withContext(Dispatchers.IO) {
                try { decodeSampledBitmap(imgs[imageIdx], r.width().toInt(), r.height().toInt()) }
                catch (_: Exception) { null }
            }

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
                val r = rects[idx]
                val col = idx % cols; val row = idx / cols
                val dest = RectF(
                    if (col > 0) r.left + spacing else r.left,
                    if (row > 0) r.top + spacing else r.top,
                    if (col < cols - 1) r.right - spacing else r.right,
                    if (row < rows - 1) r.bottom - spacing else r.bottom
                )

                canvas.save()
                if (cornerRadius > 0f) {
                    val tlR = if (col > 0 && row > 0) cornerRadius else 0f
                    val trR = if (col < cols - 1 && row > 0) cornerRadius else 0f
                    val brR = if (col < cols - 1 && row < rows - 1) cornerRadius else 0f
                    val blR = if (col > 0 && row < rows - 1) cornerRadius else 0f
                    canvas.clipPath(Path().apply {
                        addRoundRect(dest, floatArrayOf(tlR, tlR, trR, trR, brR, brR, blR, blR), Path.Direction.CW)
                    })
                }

                val alpha = fadeAlphas.getOrElse(idx) { 1f }
                val fading = fadingBitmaps.getOrNull(idx)

                if (fading != null) {
                    paint.alpha = 255
                    canvas.drawBitmap(fading, centerCropRect(fading.width, fading.height, dest.width().toInt(), dest.height().toInt()), dest, paint)
                }
                if (bitmap != null) {
                    paint.alpha = (alpha * 255).toInt()
                    canvas.drawBitmap(bitmap, centerCropRect(bitmap.width, bitmap.height, dest.width().toInt(), dest.height().toInt()), dest, paint)
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
                    val dest = RectF(
                        if (col > 0) r.left + spacing else r.left,
                        if (row > 0) r.top + spacing else r.top,
                        if (col < cols - 1) r.right - spacing else r.right,
                        if (row < rows - 1) r.bottom - spacing else r.bottom
                    )
                    canvas.save()
                    if (cornerRadius > 0f) {
                        val tlR = if (col > 0 && row > 0) cornerRadius else 0f
                        val trR = if (col < cols - 1 && row > 0) cornerRadius else 0f
                        val brR = if (col < cols - 1 && row < rows - 1) cornerRadius else 0f
                        val blR = if (col > 0 && row < rows - 1) cornerRadius else 0f
                        canvas.clipPath(Path().apply {
                            addRoundRect(dest, floatArrayOf(tlR, tlR, trR, trR, brR, brR, blR, blR), Path.Direction.CW)
                        })
                    }
                    placeholderPaint.color = placeholderColors[idx % 4]
                    canvas.drawRect(dest, placeholderPaint)
                    canvas.restore()
                }
            }

            if (!isLoading && images.isEmpty()) drawNoImagesLabel(canvas)
            if (isLoading) drawLoadingPill(canvas)
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

        private fun centerCropRect(bw: Int, bh: Int, dw: Int, dh: Int): Rect {
            if (dw <= 0 || dh <= 0) return Rect(0, 0, bw, bh)
            val srcAspect = bw.toFloat() / bh; val dstAspect = dw.toFloat() / dh
            return if (srcAspect > dstAspect) {
                val scaledW = (bh * dstAspect).toInt(); val xOff = (bw - scaledW) / 2
                Rect(xOff, 0, xOff + scaledW, bh)
            } else {
                val scaledH = (bw / dstAspect).toInt(); val yOff = (bh - scaledH) / 2
                Rect(0, yOff, bw, yOff + scaledH)
            }
        }

        private fun recycleBitmaps() {
            bitmaps.forEach { it?.recycle() }; bitmaps = emptyArray()
            fadingBitmaps.forEach { it?.recycle() }; fadingBitmaps = emptyArray()
        }
    }
}
