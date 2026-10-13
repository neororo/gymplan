package app.dotwidgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Draws the bitmap-based widgets on a transparent canvas; the rounded background is a separate view. */
class DotRenderer(context: Context, private val palette: Palette) {

    private val bold: Typeface = context.resources.getFont(R.font.doto_bold)
    private val regular: Typeface = context.resources.getFont(R.font.doto_regular)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun battery(width: Int, height: Int, level: Int, charging: Boolean): Bitmap {
        val (bitmap, canvas) = blank(width, height)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f * 0.86f
        val dots = 24
        val dotRadius = radius * 0.08f
        val filled = (level.coerceIn(0, 100) * dots / 100f).roundToInt()
        val activeColor = if (level <= 20 && !charging) palette.accent else palette.fg
        for (i in 0 until dots) {
            val angle = Math.toRadians(-90.0 + i * 360.0 / dots)
            dotPaint.color = if (i < filled) activeColor else palette.dim
            canvas.drawCircle(
                cx + (radius * cos(angle)).toFloat(),
                cy + (radius * sin(angle)).toFloat(),
                dotRadius,
                dotPaint,
            )
        }
        textPaint.typeface = bold
        textPaint.color = palette.fg
        textPaint.textSize = radius * 0.62f
        drawCentered(canvas, level.toString(), cx, cy - radius * 0.06f)
        textPaint.typeface = regular
        textPaint.textSize = radius * 0.2f
        textPaint.color = if (charging) palette.accent else palette.dim
        drawCentered(canvas, if (charging) "CHG" else "%", cx, cy + radius * 0.42f)
        return bitmap
    }

    fun calendar(width: Int, height: Int, today: LocalDate, locale: Locale = Locale.getDefault()): Bitmap {
        val (bitmap, canvas) = blank(width, height)
        val headerH = height * 0.16f
        val weekdayH = height * 0.12f
        val firstDay = WeekFields.of(locale).firstDayOfWeek
        val monthStart = today.withDayOfMonth(1)
        val offset = (monthStart.dayOfWeek.value - firstDay.value + 7) % 7
        val days = today.lengthOfMonth()
        val rows = ceil((offset + days) / 7f).toInt()
        val cellW = width / 7f
        val cellH = (height - headerH - weekdayH) / rows

        textPaint.typeface = bold
        textPaint.color = palette.fg
        textPaint.textSize = headerH * 0.7f
        textPaint.textAlign = Paint.Align.LEFT
        val month = today.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).uppercase(locale)
        canvas.drawText(month, cellW * 0.2f, baseline(headerH / 2f), textPaint)
        dotPaint.color = palette.accent
        canvas.drawCircle(width - cellW * 0.5f, headerH / 2f, headerH * 0.14f, dotPaint)
        textPaint.textAlign = Paint.Align.CENTER

        textPaint.typeface = regular
        textPaint.color = palette.dim
        textPaint.textSize = weekdayH * 0.6f
        for (col in 0 until 7) {
            val day = firstDay.plus(col.toLong())
            val label = day.getDisplayName(TextStyle.NARROW_STANDALONE, locale).uppercase(locale)
            drawCentered(canvas, label, cellW * (col + 0.5f), headerH + weekdayH / 2f)
        }

        textPaint.typeface = bold
        textPaint.textSize = min(cellH, cellW) * 0.5f
        for (day in 1..days) {
            val index = offset + day - 1
            val x = cellW * (index % 7 + 0.5f)
            val y = headerH + weekdayH + cellH * (index / 7 + 0.5f)
            textPaint.color = if (day < today.dayOfMonth) palette.dim else palette.fg
            if (day == today.dayOfMonth) {
                dotPaint.color = palette.accent
                canvas.drawCircle(x, y, min(cellH, cellW) * 0.42f, dotPaint)
                textPaint.color = Color.WHITE
            }
            drawCentered(canvas, day.toString(), x, y)
        }
        return bitmap
    }

    fun year(width: Int, height: Int, today: LocalDate): Bitmap {
        val (bitmap, canvas) = blank(width, height)
        val total = today.lengthOfYear()
        val todayIndex = today.dayOfYear - 1
        val footerH = height * 0.2f
        val gridW = width.toFloat()
        val gridH = height - footerH

        // Pick the column count that gives the largest square cell for this aspect ratio.
        var bestCols = 1
        var bestCell = 0f
        val guess = sqrt(total * gridW / gridH).roundToInt().coerceAtLeast(1)
        for (cols in (guess - 6).coerceAtLeast(1)..guess + 6) {
            val rows = ceil(total / cols.toFloat())
            val cell = min(gridW / cols, gridH / rows)
            if (cell > bestCell) {
                bestCell = cell
                bestCols = cols
            }
        }
        val rows = ceil(total / bestCols.toFloat()).toInt()
        val startX = (gridW - bestCols * bestCell) / 2f
        val startY = (gridH - rows * bestCell) / 2f
        val dotRadius = bestCell * 0.32f
        for (i in 0 until total) {
            dotPaint.color = when {
                i < todayIndex -> palette.fg
                i == todayIndex -> palette.accent
                else -> palette.dim
            }
            canvas.drawCircle(
                startX + bestCell * (i % bestCols + 0.5f),
                startY + bestCell * (i / bestCols + 0.5f),
                dotRadius,
                dotPaint,
            )
        }

        val percent = (today.dayOfYear * 100f / total).roundToInt()
        textPaint.typeface = bold
        textPaint.textSize = footerH * 0.7f
        val footerY = baseline(gridH + footerH / 2f)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = palette.fg
        canvas.drawText(today.year.toString(), startX, footerY, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = palette.accent
        canvas.drawText("$percent%", width - startX, footerY, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        return bitmap
    }

    /** Static rendering of the clock widget, used only for the in-app preview. */
    fun clockPreview(width: Int, height: Int, time: String, date: String): Bitmap {
        val (bitmap, canvas) = blank(width, height)
        textPaint.typeface = bold
        textPaint.color = palette.fg
        textPaint.textSize = height * 0.42f
        drawCentered(canvas, time, width / 2f, height * 0.42f)
        textPaint.color = palette.accent
        textPaint.textSize = height * 0.13f
        drawCentered(canvas, date, width / 2f, height * 0.8f)
        return bitmap
    }

    private fun blank(width: Int, height: Int): Pair<Bitmap, Canvas> {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        return bitmap to Canvas(bitmap)
    }

    private fun baseline(centerY: Float): Float =
        centerY - (textPaint.descent() + textPaint.ascent()) / 2f

    private fun drawCentered(canvas: Canvas, text: String, x: Float, centerY: Float) {
        canvas.drawText(text, x, baseline(centerY), textPaint)
    }
}
