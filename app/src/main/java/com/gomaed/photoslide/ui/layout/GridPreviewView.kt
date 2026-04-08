package com.gomaed.photoslide.ui.layout

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.google.android.material.R as MaterialR

class GridPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var cols: Int = 2
        set(value) { field = value; invalidate() }

    var rows: Int = 2
        set(value) { field = value; invalidate() }

    var staggerRatio: Int = 50
        set(value) { field = value; invalidate() }

    var isLandscape: Boolean = false
        set(value) { field = value; requestLayout() }

private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resolveAttrColor(MaterialR.attr.colorSecondaryContainer)
        style = Paint.Style.FILL
    }

    private val dividerSize = context.resources.displayMetrics.density * 3

    private fun resolveAttrColor(attrRes: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attrRes, tv, true)
        return tv.data
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (isLandscape) w * 9 / 16 else w * 4 / 3
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0 || cols == 0 || rows == 0) return

        val totalDivW = dividerSize * (cols - 1)
        val totalDivH = dividerSize * (rows - 1)
        val cellW = (width - totalDivW) / cols
        val availH = height - totalDivH
        val ratio = staggerRatio / 100f

        if (ratio <= 0.5f || rows <= 1 || cols <= 1) {
            val cellH = availH / rows
            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    val left = col * (cellW + dividerSize)
                    val top = row * (cellH + dividerSize)
                    canvas.drawRect(left, top, left + cellW, top + cellH, cellPaint)
                }
            }
        } else {
            for (col in 0 until cols) {
                val tallCount = (0 until rows).count { row -> (row + col) % 2 == 0 }
                val shortCount = rows - tallCount
                val k = availH / (tallCount * ratio + shortCount * (1f - ratio))
                val tallH = ratio * k
                val shortH = (1f - ratio) * k
                val left = col * (cellW + dividerSize)
                var top = 0f
                for (row in 0 until rows) {
                    val cellH = if ((row + col) % 2 == 0) tallH else shortH
                    canvas.drawRect(left, top, left + cellW, top + cellH, cellPaint)
                    top += cellH + dividerSize
                }
            }
        }
    }
}
