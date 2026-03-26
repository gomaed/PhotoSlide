package com.android.photoslide.wallpaper

import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.provider.DocumentsContract
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.animation.LinearInterpolator
import com.android.photoslide.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = WallpaperEngine()

    inner class WallpaperEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val handlerThread = HandlerThread("WallpaperRender")
        private lateinit var handler: Handler
        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private val prefs by lazy { AppPreferences(this@LiveWallpaperService) }

        private var images: List<Uri> = emptyList()
        // Per-cell image indices into `images`
        private var cellIndices: IntArray = IntArray(0)
        // Which cell advances next
        private var advanceCellPos = 0
        private var bitmaps: Array<Bitmap?> = emptyArray()

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val bgPaint = Paint().apply { color = Color.BLACK }
        private val placeholderPaint = Paint().apply { color = Color.DKGRAY }

        private var isLoading = false
        private var loadingRotation = 0f
        private var loadingSweep = 15f
        private var rotationAnimator: ValueAnimator? = null
        private var sweepAnimator: ValueAnimator? = null

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
        private var loadJob: Job? = null

        private val gestureDetector by lazy {
            GestureDetector(this@LiveWallpaperService,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        if (prefs.doubleTapAdvance && images.isNotEmpty() && cellIndices.isNotEmpty()) {
                            val cell = cellAtPosition(e.x, e.y)
                            cellIndices[cell] = (cellIndices[cell] + 1) % images.size
                            scope.launch { reloadCell(cell) }
                        }
                        return true
                    }
                })
        }

        private val isLandscape get() = surfaceWidth > surfaceHeight
        private val currentCols get() = if (isLandscape) prefs.landscapeCols else prefs.portraitCols
        private val currentRows get() = if (isLandscape) prefs.landscapeRows else prefs.portraitRows

        private fun cellRects(): List<RectF> {
            val cols = currentCols
            val rows = currentRows
            val cellW = surfaceWidth / cols.toFloat()
            val uniformCellH = surfaceHeight / rows.toFloat()
            val ratio = prefs.staggerRatio / 100f  // 0.50f..0.70f

            // No stagger if ratio is neutral or only 1 row
            if (ratio <= 0.5f || rows <= 1) {
                return List(cols * rows) { i ->
                    val col = i % cols
                    val row = i / cols
                    RectF(col * cellW, row * uniformCellH, (col + 1) * cellW, (row + 1) * uniformCellH)
                }
            }

            // Precompute cumulative tops per column — adjacent columns alternate tall/short rows.
            // Scale factor k is computed per column so all rows stagger and heights sum to surfaceHeight.
            val colTops = Array(cols) { col ->
                val tallCount = (0 until rows).count { row -> (row + col) % 2 == 0 }
                val shortCount = rows - tallCount
                val k = surfaceHeight / (tallCount * ratio + shortCount * (1f - ratio))
                val tallH = ratio * k
                val shortH = (1f - ratio) * k
                FloatArray(rows + 1).also { tops ->
                    tops[0] = 0f
                    for (row in 0 until rows) {
                        tops[row + 1] = tops[row] + if ((row + col) % 2 == 0) tallH else shortH
                    }
                }
            }

            return List(cols * rows) { i ->
                val col = i % cols
                val row = i / cols
                RectF(col * cellW, colTops[col][row], (col + 1) * cellW, colTops[col][row + 1])
            }
        }

        // Advances exactly one cell, then schedules the next tick
        private val advanceRunnable = object : Runnable {
            override fun run() {
                if (images.isNotEmpty() && cellIndices.isNotEmpty()) {
                    cellIndices[advanceCellPos] = (cellIndices[advanceCellPos] + 1) % images.size
                    val cell = advanceCellPos
                    advanceCellPos = (advanceCellPos + 1) % cellIndices.size
                    scope.launch { reloadCell(cell) }
                }
                if (isVisible) scheduleNextAdvance()
            }
        }

        private fun scheduleNextAdvance() {
            handler.removeCallbacks(advanceRunnable)
            val interval = prefs.slideInterval
            if (interval != AppPreferences.INTERVAL_NEVER) {
                handler.postDelayed(advanceRunnable, interval * 1000L)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            handlerThread.start()
            handler = Handler(handlerThread.looper)
            prefs.registerChangeListener(this)
            setTouchEventsEnabled(true)
        }

        override fun onTouchEvent(event: MotionEvent) {
            gestureDetector.onTouchEvent(event)
        }

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
                if (images.isEmpty()) {
                    loadImages()
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
            surfaceWidth = width
            surfaceHeight = height
            if (images.isNotEmpty()) {
                scope.launch { refreshBitmaps() }
            } else {
                loadImages()
            }
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            when (key) {
                AppPreferences.KEY_FOLDER_URIS,
                AppPreferences.KEY_SORT_ORDER -> loadImages()

                AppPreferences.KEY_PORTRAIT_COLS,
                AppPreferences.KEY_PORTRAIT_ROWS,
                AppPreferences.KEY_LANDSCAPE_COLS,
                AppPreferences.KEY_LANDSCAPE_ROWS,
                AppPreferences.KEY_STAGGER_RATIO -> scope.launch { reloadAllBitmaps() }

                AppPreferences.KEY_SHOW_SPACING -> drawFrame()

                AppPreferences.KEY_SLIDE_INTERVAL -> {
                    if (isVisible) scheduleNextAdvance()
                }
            }
        }

        private fun collectImages(treeUri: Uri, docId: String, result: MutableList<Uri>) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(0) ?: continue
                    val mime = cursor.getString(1) ?: continue
                    when {
                        mime.startsWith("image/") ->
                            result.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, childId))
                        mime == DocumentsContract.Document.MIME_TYPE_DIR ->
                            collectImages(treeUri, childId, result)
                    }
                }
            }
        }

        private fun startLoadingAnimation() {
            rotationAnimator?.cancel()
            sweepAnimator?.cancel()

            // Continuous rotation — drives the drawFrame() calls
            rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
                addUpdateListener {
                    loadingRotation = it.animatedValue as Float
                    drawFrame()
                }
                start()
            }

            // Sweep grows and shrinks on a different period — creates expressive, non-repeating motion
            sweepAnimator = ValueAnimator.ofFloat(15f, 280f).apply {
                duration = 1400
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { loadingSweep = it.animatedValue as Float }
                start()
            }
        }

        private fun stopLoadingAnimation() {
            rotationAnimator?.cancel()
            sweepAnimator?.cancel()
            rotationAnimator = null
            sweepAnimator = null
        }

        private fun loadImages() {
            isLoading = true
            startLoadingAnimation()
            loadJob?.cancel()
            loadJob = scope.launch {
                val uris = withContext(Dispatchers.IO) {
                    val result = mutableListOf<Uri>()
                    for (uriStr in prefs.selectedFolderUris) {
                        try {
                            val treeUri = Uri.parse(uriStr)
                            collectImages(treeUri, DocumentsContract.getTreeDocumentId(treeUri), result)
                        } catch (_: Exception) {
                        }
                    }
                    when (prefs.sortOrder) {
                        AppPreferences.SORT_RANDOM -> result.shuffled()
                        AppPreferences.SORT_DATE_DESC -> result.reversed()
                        else -> result.sortedBy { it.lastPathSegment }
                    }
                }
                images = uris
                reloadAllBitmaps()
            }
        }

        private suspend fun reloadAllBitmaps() {
            if (surfaceWidth == 0 || surfaceHeight == 0) return
            val rects = cellRects()
            val needed = rects.size

            // Space cells evenly across the full image list so they never show
            // consecutive images — step = images.size / needed keeps them far apart
            val step = if (images.size > needed) images.size / needed else 1
            cellIndices = IntArray(needed) { i ->
                if (images.isEmpty()) 0 else (i * step) % images.size
            }
            advanceCellPos = 0

            val newBitmaps = withContext(Dispatchers.IO) {
                Array(needed) { i ->
                    if (images.isEmpty()) return@Array null
                    val r = rects[i]
                    try {
                        decodeSampledBitmap(images[cellIndices[i]], r.width().toInt(), r.height().toInt())
                    } catch (_: Exception) {
                        null
                    }
                }
            }

            recycleBitmaps()
            bitmaps = newBitmaps
            isLoading = false
            stopLoadingAnimation()
            drawFrame()
        }

        // Re-decode bitmaps from existing cellIndices (preserves which image each cell shows).
        // Falls back to reloadAllBitmaps() when the grid cell count has changed.
        private suspend fun refreshBitmaps() {
            if (surfaceWidth == 0 || surfaceHeight == 0) return
            val rects = cellRects()
            val needed = rects.size
            if (cellIndices.size != needed) {
                reloadAllBitmaps()
                return
            }

            val newBitmaps = withContext(Dispatchers.IO) {
                Array(needed) { i ->
                    if (images.isEmpty()) return@Array null
                    val r = rects[i]
                    try {
                        decodeSampledBitmap(images[cellIndices[i]], r.width().toInt(), r.height().toInt())
                    } catch (_: Exception) {
                        null
                    }
                }
            }

            recycleBitmaps()
            bitmaps = newBitmaps
            drawFrame()
        }

        private fun cellAtPosition(x: Float, y: Float): Int {
            val rects = cellRects()
            val idx = rects.indexOfFirst { it.contains(x, y) }
            if (idx >= 0) return idx
            // fallback: nearest cell centre
            return rects.indices.minByOrNull { i ->
                val cx = rects[i].centerX() - x
                val cy = rects[i].centerY() - y
                cx * cx + cy * cy
            }?.coerceIn(0, cellIndices.size - 1) ?: 0
        }

        // Reload only a single cell after it has been advanced
        private suspend fun reloadCell(cellPos: Int) {
            if (surfaceWidth == 0 || surfaceHeight == 0) return
            if (cellPos >= bitmaps.size || cellPos >= cellIndices.size) return
            val rects = cellRects()
            if (cellPos >= rects.size) return
            val r = rects[cellPos]

            val newBitmap = withContext(Dispatchers.IO) {
                if (images.isEmpty()) return@withContext null
                try {
                    decodeSampledBitmap(images[cellIndices[cellPos]], r.width().toInt(), r.height().toInt())
                } catch (_: Exception) {
                    null
                }
            }

            bitmaps[cellPos]?.recycle()
            bitmaps[cellPos] = newBitmap
            drawFrame()
        }

        private fun decodeSampledBitmap(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            opts.inSampleSize = calculateSampleSize(opts, reqWidth, reqHeight)
            opts.inJustDecodeBounds = false
            return contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }

        private fun calculateSampleSize(
            opts: BitmapFactory.Options,
            reqWidth: Int,
            reqHeight: Int
        ): Int {
            val h = opts.outHeight
            val w = opts.outWidth
            var sampleSize = 1
            if (h > reqHeight || w > reqWidth) {
                val halfH = h / 2
                val halfW = w / 2
                while ((halfH / sampleSize) >= reqHeight && (halfW / sampleSize) >= reqWidth) {
                    sampleSize *= 2
                }
            }
            return sampleSize
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                canvas?.let { drawScene(it) }
            } finally {
                canvas?.let { holder.unlockCanvasAndPost(it) }
            }
        }

        private fun drawScene(canvas: Canvas) {
            canvas.drawRect(
                0f, 0f, surfaceWidth.toFloat(), surfaceHeight.toFloat(), bgPaint
            )
            val spacing = if (prefs.showSpacing) 2f else 0f
            val rects = cellRects()

            bitmaps.forEachIndexed { idx, bitmap ->
                if (idx >= rects.size) return@forEachIndexed
                val r = rects[idx]
                val dest = RectF(
                    r.left + spacing, r.top + spacing,
                    r.right - spacing, r.bottom - spacing
                )

                if (bitmap != null) {
                    val src = centerCropRect(
                        bitmap.width, bitmap.height, dest.width().toInt(), dest.height().toInt()
                    )
                    canvas.drawBitmap(bitmap, src, dest, paint)
                } else {
                    canvas.drawRect(dest, placeholderPaint)
                }
            }

            if (isLoading) drawLoadingSpinner(canvas)
        }

        private fun drawLoadingSpinner(canvas: Canvas) {
            val cx = surfaceWidth / 2f
            val cy = surfaceHeight / 2f
            val radius = minOf(surfaceWidth, surfaceHeight) * 0.09f
            val strokeWidth = radius * 0.18f

            spinnerTrackPaint.strokeWidth = strokeWidth
            spinnerPaint.strokeWidth = strokeWidth

            val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(oval, 0f, 360f, false, spinnerTrackPaint)
            canvas.drawArc(oval, loadingRotation - 90f, loadingSweep, false, spinnerPaint)
        }

        private fun centerCropRect(bw: Int, bh: Int, dw: Int, dh: Int): Rect {
            if (dw <= 0 || dh <= 0) return Rect(0, 0, bw, bh)
            val srcAspect = bw.toFloat() / bh
            val dstAspect = dw.toFloat() / dh
            return if (srcAspect > dstAspect) {
                val scaledW = (bh * dstAspect).toInt()
                val xOff = (bw - scaledW) / 2
                Rect(xOff, 0, xOff + scaledW, bh)
            } else {
                val scaledH = (bw / dstAspect).toInt()
                val yOff = (bh - scaledH) / 2
                Rect(0, yOff, bw, yOff + scaledH)
            }
        }

        private fun recycleBitmaps() {
            bitmaps.forEach { it?.recycle() }
            bitmaps = emptyArray()
        }
    }
}
