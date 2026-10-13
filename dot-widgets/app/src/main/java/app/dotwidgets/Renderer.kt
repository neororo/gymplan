package app.dotwidgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.format.DateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Live values the renderer needs besides the clock. */
data class DeviceState(val batteryLevel: Int, val charging: Boolean)

/**
 * Draws a widget's content on a transparent bitmap. The rounded background is drawn separately
 * (an ImageView in the widget, or [Previews] in the app) so its opacity and corners stay crisp.
 */
class Renderer(
    private val context: Context,
    private val config: WidgetConfig,
    private val palette: Palette,
    private val locale: Locale = Locale.getDefault(),
) {
    private val bold: Typeface
    private val light: Typeface
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = Rect()
    private val fg = palette.fg
    private val dim = palette.dim
    private val accent = if (config.accent) palette.accent else palette.fg

    init {
        when (config.font) {
            WidgetConfig.FONT_SANS -> {
                bold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                light = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            }
            WidgetConfig.FONT_MONO -> {
                bold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                light = Typeface.MONOSPACE
            }
            else -> {
                bold = context.resources.getFont(R.font.doto_bold)
                light = context.resources.getFont(R.font.doto_regular)
            }
        }
    }

    fun render(kind: WidgetKind, width: Int, height: Int, now: LocalDateTime, device: DeviceState): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val s = config.scale / 100f
        // Scale the drawing area itself so text and dots re-fit instead of being clipped.
        val w = width / s
        val h = height / s
        canvas.translate((width - w * s) / 2f, (height - h * s) / 2f)
        canvas.scale(s, s)
        val area = RectF(0f, 0f, w, h)
        when (kind) {
            WidgetKind.CLOCK -> when (config.style) {
                1 -> stackedClock(canvas, area, now)
                2 -> analogClock(canvas, area, now)
                else -> digitalClock(canvas, area, now)
            }
            WidgetKind.BATTERY ->
                if (config.style == 1) batteryBar(canvas, area, device) else batteryRing(canvas, area, device)
            WidgetKind.CALENDAR ->
                if (config.style == 1) weekCalendar(canvas, area, now.toLocalDate()) else monthCalendar(canvas, area, now.toLocalDate())
            WidgetKind.DATE ->
                if (config.style == 1) dateLine(canvas, area, now.toLocalDate()) else dateBig(canvas, area, now.toLocalDate())
            WidgetKind.PROGRESS -> progress(canvas, area, now)
            WidgetKind.COUNTDOWN -> countdown(canvas, area, now.toLocalDate())
            WidgetKind.NOTE -> note(canvas, area)
        }
        return bitmap
    }

    // ---- Clock -------------------------------------------------------------------------------

    private fun is24h(): Boolean = when (config.hourFormat) {
        WidgetConfig.HOURS_12 -> false
        WidgetConfig.HOURS_24 -> true
        else -> DateFormat.is24HourFormat(context)
    }

    private fun hourText(now: LocalDateTime): String {
        val hour = if (is24h()) now.hour else (now.hour % 12).let { if (it == 0) 12 else it }
        return "%02d".format(hour)
    }

    private fun minuteText(now: LocalDateTime) = "%02d".format(now.minute)

    private fun shortDate(date: LocalDate): String {
        val day = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).trimEnd('.')
        val month = date.month.getDisplayName(TextStyle.SHORT, locale).trimEnd('.')
        return "$day ${date.dayOfMonth} $month".uppercase(locale)
    }

    private fun digitalClock(canvas: Canvas, area: RectF, now: LocalDateTime) {
        val suffix = if (is24h()) "" else if (now.hour < 12) " AM" else " PM"
        if (config.showDate) {
            val timeBox = RectF(area.left, area.top, area.right, area.top + area.height() * 0.66f)
            drawTime(canvas, timeBox, hourText(now), minuteText(now))
            val dateBox = RectF(area.left, area.top + area.height() * 0.76f, area.right, area.bottom)
            fitText(canvas, shortDate(now.toLocalDate()) + suffix, dateBox, bold, accent, measureAs = "MMM 00 MMM AM")
        } else {
            drawTime(canvas, area.inset(0.04f), hourText(now), minuteText(now))
        }
    }

    /** HH:MM with the colon drawn as two accent dots; the font's own colon glyph reads poorly. */
    private fun drawTime(canvas: Canvas, box: RectF, hours: String, minutes: String) {
        paint.typeface = bold
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 100f
        val digitsW = paint.measureText("00")
        paint.getTextBounds("00", 0, 2, bounds)
        val capH = bounds.height().coerceAtLeast(1)
        val colonW = digitsW * 0.32f
        paint.textSize = min(100f * box.width() / (2 * digitsW + colonW), 100f * box.height() / capH)
        val k = paint.textSize / 100f
        val cx = box.centerX()
        val cy = box.centerY()
        paint.getTextBounds("00", 0, 2, bounds)
        val baseline = cy - bounds.exactCenterY()
        paint.color = fg
        canvas.drawText(hours, cx - colonW * k / 2f - paint.measureText(hours), baseline, paint)
        canvas.drawText(minutes, cx + colonW * k / 2f, baseline, paint)
        paint.color = accent
        val dotR = capH * k * 0.07f
        canvas.drawCircle(cx, cy - capH * k * 0.2f, dotR, paint)
        canvas.drawCircle(cx, cy + capH * k * 0.2f, dotR, paint)
    }

    private fun stackedClock(canvas: Canvas, area: RectF, now: LocalDateTime) {
        val sideW = if (config.showDate) area.width() * 0.3f else 0f
        val left = RectF(area.left, area.top, area.right - sideW, area.bottom)
        val gap = area.height() * 0.06f
        val half = (left.height() - gap) / 2f
        fitText(canvas, hourText(now), RectF(left.left, left.top, left.right, left.top + half), bold, fg, Paint.Align.LEFT, "00")
        fitText(canvas, minuteText(now), RectF(left.left, left.bottom - half, left.right, left.bottom), bold, accent, Paint.Align.LEFT, "00")
        if (config.showDate) {
            val date = now.toLocalDate()
            val x = area.right - sideW + sideW * 0.12f
            val day = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).trimEnd('.').uppercase(locale)
            fitText(canvas, day, RectF(x, area.top, area.right, area.top + half * 0.45f), bold, accent, Paint.Align.RIGHT, "MMM")
            fitText(canvas, date.dayOfMonth.toString(), RectF(x, area.top + half * 0.55f, area.right, area.top + half), bold, fg, Paint.Align.RIGHT, "00")
            if (!is24h()) {
                val ampm = if (now.hour < 12) "AM" else "PM"
                fitText(canvas, ampm, RectF(x, area.bottom - half * 0.4f, area.right, area.bottom), light, dim, Paint.Align.RIGHT, "AM")
            }
        }
    }

    private fun analogClock(canvas: Canvas, area: RectF, now: LocalDateTime) {
        val cx = area.centerX()
        val cy = area.centerY()
        val r = min(area.width(), area.height()) / 2f * 0.94f
        for (i in 0 until 60) {
            val angle = Math.toRadians(i * 6.0 - 90)
            val major = i % 5 == 0
            paint.color = when {
                i == 0 -> accent
                major -> fg
                else -> dim
            }
            val dotR = if (major) r * 0.045f else r * 0.018f
            canvas.drawCircle(cx + (r * cos(angle)).toFloat(), cy + (r * sin(angle)).toFloat(), dotR, paint)
        }
        val minutes = now.minute + now.second / 60f
        val hours = now.hour % 12 + minutes / 60f
        drawDottedHand(canvas, cx, cy, hours * 30f, r * 0.52f, r * 0.05f, fg)
        drawDottedHand(canvas, cx, cy, minutes * 6f, r * 0.8f, r * 0.04f, fg)
        paint.color = accent
        canvas.drawCircle(cx, cy, r * 0.07f, paint)
        if (config.showDate) {
            val box = RectF(cx - r * 0.3f, cy + r * 0.32f, cx + r * 0.3f, cy + r * 0.5f)
            fitText(canvas, now.dayOfMonth.toString(), box, bold, dim, measureAs = "00")
        }
    }

    private fun drawDottedHand(canvas: Canvas, cx: Float, cy: Float, degrees: Float, length: Float, dotR: Float, color: Int) {
        val angle = Math.toRadians(degrees - 90.0)
        val step = dotR * 2.6f
        val dots = (length / step).toInt()
        paint.color = color
        for (i in 2..dots) {
            val d = i * step
            canvas.drawCircle(cx + (d * cos(angle)).toFloat(), cy + (d * sin(angle)).toFloat(), dotR, paint)
        }
    }

    // ---- Battery -----------------------------------------------------------------------------

    private fun batteryColor(device: DeviceState) =
        if (device.charging || device.batteryLevel <= 20) accent else fg

    private fun batteryRing(canvas: Canvas, area: RectF, device: DeviceState) {
        val cx = area.centerX()
        val cy = area.centerY()
        val r = min(area.width(), area.height()) / 2f * 0.88f
        val dots = 24
        val filled = (device.batteryLevel.coerceIn(0, 100) * dots / 100f).roundToInt()
        for (i in 0 until dots) {
            val angle = Math.toRadians(-90.0 + i * 360.0 / dots)
            paint.color = if (i < filled) batteryColor(device) else dim
            canvas.drawCircle(cx + (r * cos(angle)).toFloat(), cy + (r * sin(angle)).toFloat(), r * 0.075f, paint)
        }
        fitText(canvas, device.batteryLevel.toString(), RectF(cx - r * 0.55f, cy - r * 0.38f, cx + r * 0.55f, cy + r * 0.18f), bold, fg, measureAs = "100")
        val label = if (device.charging) "CHG" else "%"
        fitText(canvas, label, RectF(cx - r * 0.3f, cy + r * 0.3f, cx + r * 0.3f, cy + r * 0.48f), bold, if (device.charging) accent else dim, measureAs = "CHG")
    }

    private fun batteryBar(canvas: Canvas, area: RectF, device: DeviceState) {
        val textBox = RectF(area.left, area.top, area.right, area.top + area.height() * 0.55f)
        fitText(canvas, "${device.batteryLevel}%", textBox, bold, fg, Paint.Align.LEFT, "100%")
        val rowTop = area.top + area.height() * 0.7f
        val rowH = area.height() * 0.3f
        val cols = 10
        val cell = min(area.width() / cols, rowH)
        val filled = (device.batteryLevel.coerceIn(0, 100) * cols / 100f).roundToInt()
        for (i in 0 until cols) {
            paint.color = if (i < filled) batteryColor(device) else dim
            val x = area.left + area.width() / cols * (i + 0.5f)
            canvas.drawCircle(x, rowTop + rowH / 2f, cell * 0.36f, paint)
        }
        if (device.charging) {
            fitText(canvas, "CHG", RectF(area.right - area.width() * 0.3f, area.top, area.right, area.top + area.height() * 0.2f), bold, accent, Paint.Align.RIGHT, "CHG")
        }
    }

    // ---- Calendar and date -------------------------------------------------------------------

    private fun firstDayOfWeek(): DayOfWeek = WeekFields.of(locale).firstDayOfWeek

    private fun monthCalendar(canvas: Canvas, area: RectF, today: LocalDate) {
        val headerH = area.height() * 0.16f
        val weekdayH = area.height() * 0.11f
        val firstDay = firstDayOfWeek()
        val offset = (today.withDayOfMonth(1).dayOfWeek.value - firstDay.value + 7) % 7
        val days = today.lengthOfMonth()
        val rows = ceil((offset + days) / 7f).toInt()
        val cellW = area.width() / 7f
        val cellH = (area.height() - headerH - weekdayH) / rows

        val month = today.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).uppercase(locale)
        fitText(canvas, month, RectF(area.left + cellW * 0.2f, area.top, area.right - cellW, area.top + headerH * 0.8f), bold, fg, Paint.Align.LEFT, "SEPTIEMBRE")
        paint.color = accent
        canvas.drawCircle(area.right - cellW * 0.5f, area.top + headerH * 0.4f, headerH * 0.14f, paint)

        for (col in 0 until 7) {
            val label = firstDay.plus(col.toLong()).getDisplayName(TextStyle.NARROW_STANDALONE, locale).uppercase(locale)
            val x = area.left + cellW * col
            fitText(canvas, label, RectF(x, area.top + headerH, x + cellW, area.top + headerH + weekdayH * 0.7f), light, dim, measureAs = "M")
        }

        val numberH = min(cellH, cellW) * 0.5f
        for (day in 1..days) {
            val index = offset + day - 1
            val cx = area.left + cellW * (index % 7 + 0.5f)
            val cy = area.top + headerH + weekdayH + cellH * (index / 7 + 0.5f)
            var color = if (day < today.dayOfMonth) dim else fg
            if (day == today.dayOfMonth) {
                paint.color = accent
                canvas.drawCircle(cx, cy, min(cellH, cellW) * 0.44f, paint)
                color = contrastOn(accent)
            }
            val box = RectF(cx - cellW / 2f, cy - numberH / 2f, cx + cellW / 2f, cy + numberH / 2f)
            fitText(canvas, day.toString(), box, bold, color, measureAs = "00")
        }
    }

    private fun weekCalendar(canvas: Canvas, area: RectF, today: LocalDate) {
        val topH = area.height() * 0.58f
        fitText(canvas, today.dayOfMonth.toString(), RectF(area.left, area.top, area.left + area.width() * 0.42f, area.top + topH), bold, fg, Paint.Align.LEFT, "00")
        val rightX = area.left + area.width() * 0.48f
        val weekday = today.dayOfWeek.getDisplayName(TextStyle.FULL_STANDALONE, locale).uppercase(locale)
        fitText(canvas, weekday, RectF(rightX, area.top + topH * 0.1f, area.right, area.top + topH * 0.45f), bold, accent, Paint.Align.LEFT, "MIÉRCOLES")
        val monthYear = "${today.month.getDisplayName(TextStyle.SHORT_STANDALONE, locale).trimEnd('.')} ${today.year}".uppercase(locale)
        fitText(canvas, monthYear, RectF(rightX, area.top + topH * 0.55f, area.right, area.top + topH * 0.85f), light, fg, Paint.Align.LEFT, "MMM 0000")

        val start = today.minusDays(((today.dayOfWeek.value - firstDayOfWeek().value + 7) % 7).toLong())
        val cellW = area.width() / 7f
        val rowTop = area.top + topH + area.height() * 0.06f
        val rowH = area.bottom - rowTop
        for (i in 0 until 7) {
            val day = start.plusDays(i.toLong())
            val x = area.left + cellW * i
            val label = day.dayOfWeek.getDisplayName(TextStyle.NARROW_STANDALONE, locale).uppercase(locale)
            fitText(canvas, label, RectF(x, rowTop, x + cellW, rowTop + rowH * 0.32f), light, dim, measureAs = "M")
            val cy = rowTop + rowH * 0.7f
            var color = if (day.isBefore(today)) dim else fg
            if (day == today) {
                paint.color = accent
                canvas.drawCircle(x + cellW / 2f, cy, min(cellW, rowH * 0.6f) * 0.46f, paint)
                color = contrastOn(accent)
            }
            fitText(canvas, day.dayOfMonth.toString(), RectF(x, cy - rowH * 0.15f, x + cellW, cy + rowH * 0.15f), bold, color, measureAs = "00")
        }
    }

    private fun dateBig(canvas: Canvas, area: RectF, today: LocalDate) {
        fitText(canvas, today.dayOfMonth.toString(), RectF(area.left, area.top, area.left + area.width() * 0.5f, area.bottom), bold, fg, Paint.Align.LEFT, "00")
        val x = area.left + area.width() * 0.55f
        val weekday = today.dayOfWeek.getDisplayName(TextStyle.FULL_STANDALONE, locale).uppercase(locale)
        val month = today.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).uppercase(locale)
        fitText(canvas, weekday, RectF(x, area.top + area.height() * 0.2f, area.right, area.top + area.height() * 0.45f), bold, accent, Paint.Align.LEFT, "MIÉRCOLES")
        fitText(canvas, month, RectF(x, area.top + area.height() * 0.55f, area.right, area.top + area.height() * 0.8f), light, fg, Paint.Align.LEFT, "SEPTIEMBRE")
    }

    private fun dateLine(canvas: Canvas, area: RectF, today: LocalDate) {
        val weekday = today.dayOfWeek.getDisplayName(TextStyle.FULL_STANDALONE, locale).uppercase(locale)
        fitText(canvas, weekday, RectF(area.left, area.top + area.height() * 0.1f, area.right, area.top + area.height() * 0.55f), bold, fg, measureAs = "MIÉRCOLES")
        fitText(canvas, shortDate(today).substringAfter(' '), RectF(area.left, area.top + area.height() * 0.65f, area.right, area.top + area.height() * 0.9f), bold, accent, measureAs = "00 MMM")
    }

    // ---- Progress, countdown, note -----------------------------------------------------------

    private fun progress(canvas: Canvas, area: RectF, now: LocalDateTime) {
        val today = now.toLocalDate()
        val total: Int
        val done: Int
        val fraction: Float
        val label: String
        when (config.period) {
            WidgetConfig.PERIOD_DAY -> {
                total = 24
                done = now.hour
                fraction = (now.hour * 60 + now.minute) / 1440f
                label = "HOY"
            }
            WidgetConfig.PERIOD_WEEK -> {
                val index = (today.dayOfWeek.value - firstDayOfWeek().value + 7) % 7
                total = 7
                done = index
                fraction = (index + now.hour / 24f) / 7f
                label = "SEM ${today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
            }
            WidgetConfig.PERIOD_MONTH -> {
                total = today.lengthOfMonth()
                done = today.dayOfMonth - 1
                fraction = (done + now.hour / 24f) / total
                label = today.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).uppercase(locale)
            }
            else -> {
                total = today.lengthOfYear()
                done = today.dayOfYear - 1
                fraction = (done + now.hour / 24f) / total
                label = today.year.toString()
            }
        }
        val percent = "${(fraction * 100).toInt()}%"
        if (config.style == 1) {
            val textBox = RectF(area.left, area.top, area.right, area.top + area.height() * 0.4f)
            fitText(canvas, label, RectF(textBox.left, textBox.top, textBox.centerX(), textBox.bottom), bold, fg, Paint.Align.LEFT, "SEPTIEMBRE")
            fitText(canvas, percent, RectF(textBox.centerX(), textBox.top, textBox.right, textBox.bottom), bold, accent, Paint.Align.RIGHT, "100%")
            val cols = 20
            val rowTop = area.top + area.height() * 0.55f
            val rowH = area.height() * 0.45f
            val cell = min(area.width() / cols, rowH)
            val filled = (fraction * cols).toInt()
            for (i in 0 until cols) {
                paint.color = when {
                    i < filled -> fg
                    i == filled -> accent
                    else -> dim
                }
                canvas.drawCircle(area.left + area.width() / cols * (i + 0.5f), rowTop + rowH / 2f, cell * 0.34f, paint)
            }
            return
        }
        val footerH = area.height() * 0.2f
        val grid = RectF(area.left, area.top, area.right, area.bottom - footerH)
        dotGrid(canvas, grid, total, done)
        val footer = RectF(area.left, area.bottom - footerH * 0.85f, area.right, area.bottom)
        fitText(canvas, label, RectF(footer.left, footer.top, footer.centerX(), footer.bottom), bold, fg, Paint.Align.LEFT, "SEPTIEMBRE")
        fitText(canvas, percent, RectF(footer.centerX(), footer.top, footer.right, footer.bottom), bold, accent, Paint.Align.RIGHT, "100%")
    }

    /** Lays out [total] dots in the grid shape that gives the biggest square cells. */
    private fun dotGrid(canvas: Canvas, area: RectF, total: Int, current: Int) {
        var bestCols = 1
        var bestCell = 0f
        val guess = sqrt(total * area.width() / area.height()).roundToInt().coerceAtLeast(1)
        for (cols in (guess - 6).coerceIn(1, total)..(guess + 6).coerceIn(1, total)) {
            val rows = ceil(total / cols.toFloat())
            val cell = min(area.width() / cols, area.height() / rows)
            if (cell > bestCell) {
                bestCell = cell
                bestCols = cols
            }
        }
        val rows = ceil(total / bestCols.toFloat()).toInt()
        val startX = area.left + (area.width() - bestCols * bestCell) / 2f
        val startY = area.top + (area.height() - rows * bestCell) / 2f
        for (i in 0 until total) {
            paint.color = when {
                i < current -> fg
                i == current -> accent
                else -> dim
            }
            canvas.drawCircle(
                startX + bestCell * (i % bestCols + 0.5f),
                startY + bestCell * (i / bestCols + 0.5f),
                bestCell * 0.33f,
                paint,
            )
        }
    }

    private fun countdown(canvas: Canvas, area: RectF, today: LocalDate) {
        val target = config.target()
        val days = ChronoUnit.DAYS.between(today, target)
        val title = config.text.ifBlank { "Evento" }.uppercase(locale)
        fitText(canvas, title, RectF(area.left, area.top, area.right, area.top + area.height() * 0.22f), bold, accent, Paint.Align.LEFT, "MMMMMMMMMM")
        val number = if (days == 0L) "HOY" else abs(days).toString()
        val numberBox = RectF(area.left, area.top + area.height() * 0.3f, area.left + area.width() * 0.68f, area.bottom)
        fitText(canvas, number, numberBox, bold, fg, Paint.Align.LEFT, "000")
        if (days != 0L) {
            val unit = when {
                days < 0 -> "DÍAS\nDESDE"
                days == 1L -> "DÍA"
                else -> "DÍAS"
            }
            val lines = unit.split('\n')
            val boxH = area.height() * 0.16f
            lines.forEachIndexed { i, line ->
                val top = area.bottom - boxH * (lines.size - i)
                fitText(canvas, line, RectF(area.left + area.width() * 0.72f, top, area.right, top + boxH * 0.85f), bold, dim, Paint.Align.RIGHT, "DESDE")
            }
        }
    }

    private fun note(canvas: Canvas, area: RectF) {
        val text = config.text.ifBlank { " " }
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = bold
            color = fg
        }
        val width = area.width().toInt().coerceAtLeast(1)
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val align = if (config.style == 1) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_CENTER
        // Largest size where the wrapped text fits the box and no single word has to break.
        var lo = 4f
        var hi = area.height()
        var layout = staticLayout(text, textPaint, width, align)
        repeat(18) {
            val mid = (lo + hi) / 2f
            textPaint.textSize = mid
            val candidate = staticLayout(text, textPaint, width, align)
            val wordsFit = words.all { textPaint.measureText(it) <= width }
            if (candidate.height <= area.height() && wordsFit) {
                lo = mid
                layout = candidate
            } else {
                hi = mid
            }
        }
        textPaint.textSize = lo
        layout = staticLayout(text, textPaint, width, align)
        canvas.save()
        val top = if (config.style == 1) area.top else area.top + (area.height() - layout.height) / 2f
        canvas.translate(area.left, top)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun staticLayout(text: String, paint: TextPaint, width: Int, align: Layout.Alignment): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setIncludePad(false)
            .setLineSpacing(0f, 0.95f)
            .build()

    // ---- Helpers -----------------------------------------------------------------------------

    /**
     * Draws [text] as large as fits in [box]. Size is computed from [measureAs] so the glyphs
     * don't change size every time the digits change.
     */
    private fun fitText(
        canvas: Canvas,
        text: String,
        box: RectF,
        typeface: Typeface,
        color: Int,
        align: Paint.Align = Paint.Align.CENTER,
        measureAs: String = text,
    ) {
        if (text.isEmpty() || box.width() <= 0f || box.height() <= 0f) return
        paint.typeface = typeface
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 100f
        val reference = if (measureAs.length >= text.length) measureAs else text
        val refWidth = paint.measureText(reference)
        paint.getTextBounds(reference, 0, reference.length, bounds)
        val refHeight = bounds.height().coerceAtLeast(1)
        if (refWidth <= 0f) return
        paint.textSize = min(100f * box.width() / refWidth, 100f * box.height() / refHeight)
        paint.color = color
        var textWidth = paint.measureText(text)
        if (textWidth > box.width()) {
            paint.textSize *= box.width() / textWidth
            textWidth = paint.measureText(text)
        }
        paint.getTextBounds(text, 0, text.length, bounds)
        val x = when (align) {
            Paint.Align.LEFT -> box.left
            Paint.Align.RIGHT -> box.right - textWidth
            else -> box.centerX() - textWidth / 2f
        }
        val y = box.centerY() - bounds.exactCenterY()
        canvas.drawText(text, x, y, paint)
    }

    private fun RectF.inset(fraction: Float) =
        RectF(left + width() * fraction, top + height() * fraction, right - width() * fraction, bottom - height() * fraction)

    private fun contrastOn(color: Int): Int {
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        return if (luminance > 0.6) Color.BLACK else Color.WHITE
    }
}
