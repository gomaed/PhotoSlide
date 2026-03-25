package com.example.digitalcatalogue.ui.layout

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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        if (cols == 0 || rows == 0) return
        val totalDivW = dividerSize * (cols - 1)
        val totalDivH = dividerSize * (rows - 1)
        val cellW = (width - totalDivW) / cols
        val cellH = (height - totalDivH) / rows

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val left = col * (cellW + dividerSize)
                val top = row * (cellH + dividerSize)
                canvas.drawRect(left, top, left + cellW, top + cellH, cellPaint)
            }
        }
    }
}
