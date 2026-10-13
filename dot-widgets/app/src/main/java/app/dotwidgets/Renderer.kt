package app.dotwidgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
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
data class DeviceState(val batteryLevel: Int, val charging: Boolean, val media: MediaInfo? = null)

/**
 * Draws a widget's content on a transparent bitmap. The rounded background is drawn separately
 * (an ImageView in the widget, or [Previews] in the app) so its opacity and corners stay crisp.
 */
class Renderer(
    private val context: Context,
    private val config: WidgetConfig,
    private val palette: Palette,
    private val locale: Locale = Locale.getDefault(),
    /** Bitmap pixels per dp; lower than the screen density when a bitmap is drawn small and scaled up. */
    private val pxPerDp: Float = context.resources.displayMetrics.density,
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
            WidgetKind.MUSIC -> musicPreview(canvas, area, device.media ?: demoMedia())
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

    // ---- Music -------------------------------------------------------------------------------

    enum class Glyph(val cols: Int, val rows: Int, val dots: List<Pair<Int, Int>>) {
        PLAY(4, 7, column(0, 0..6) + column(1, 1..5) + column(2, 2..4) + column(3, 3..3)),
        PAUSE(3, 7, column(0, 0..6) + column(2, 0..6)),
        NEXT(6, 7, column(0, 0..6) + column(1, 1..5) + column(2, 2..4) + column(3, 3..3) + column(5, 0..6)),
        PREVIOUS(6, 7, column(0, 0..6) + column(2, 3..3) + column(3, 2..4) + column(4, 1..5) + column(5, 0..6)),
    }

    /** A control icon at a fixed dot size ([cellPx] per dot), so it never stretches with its button. */
    fun glyph(glyph: Glyph, cellPx: Float): Bitmap {
        val side = ceil(cellPx * 7f).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        drawGlyph(Canvas(bitmap), glyph, RectF(0f, 0f, side.toFloat(), side.toFloat()), fg)
        return bitmap
    }

    private fun drawGlyph(canvas: Canvas, glyph: Glyph, box: RectF, color: Int) {
        val cell = min(box.width() / 7f, box.height() / 7f)
        val left = box.centerX() - glyph.cols * cell / 2f
        val top = box.centerY() - glyph.rows * cell / 2f
        paint.color = color
        for ((col, row) in glyph.dots) {
            canvas.drawCircle(left + cell * (col + 0.5f), top + cell * (row + 0.5f), cell * 0.4f, paint)
        }
    }

    fun albumArt(source: Bitmap?, size: Int): Bitmap {
        val side = size.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        drawAlbumArt(Canvas(bitmap), RectF(0f, 0f, side.toFloat(), side.toFloat()), source)
        return bitmap
    }

    /** Album art as a halftone of dots (mono or in colour), or the photo with rounded corners. */
    private fun drawAlbumArt(canvas: Canvas, box: RectF, source: Bitmap?) {
        val side = min(box.width(), box.height())
        val left = box.centerX() - side / 2f
        val top = box.centerY() - side / 2f
        if (source == null) {
            val n = 10
            val cell = side / n
            paint.color = dim
            for (r in 0 until n) for (c in 0 until n) {
                canvas.drawCircle(left + cell * (c + 0.5f), top + cell * (r + 0.5f), cell * 0.18f, paint)
            }
            return
        }
        if (config.artMode == WidgetConfig.ART_PHOTO) {
            val radius = side * 0.08f
            val clip = Path().apply { addRoundRect(RectF(left, top, left + side, top + side), radius, radius, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawBitmap(source, null, RectF(left, top, left + side, top + side), Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
            return
        }
        val n = (side / 10f).toInt().coerceIn(14, 28)
        val small = Bitmap.createScaledBitmap(source, n, n, true)
        val cell = side / n
        val darkBackground = luminance(palette.bg) < 0.5
        for (r in 0 until n) for (c in 0 until n) {
            val pixel = small.getPixel(c, r)
            val cx = left + cell * (c + 0.5f)
            val cy = top + cell * (r + 0.5f)
            if (config.artMode == WidgetConfig.ART_DOTS_COLOR) {
                paint.color = pixel or (0xFF shl 24)
                canvas.drawCircle(cx, cy, cell * 0.42f, paint)
                continue
            }
            val l = luminance(pixel).toFloat()
            val strength = if (darkBackground) l else 1f - l
            val radius = cell * 0.5f * sqrt(strength)
            if (radius < cell * 0.14f) {
                paint.color = dim
                canvas.drawCircle(cx, cy, cell * 0.12f, paint)
            } else {
                paint.color = fg
                canvas.drawCircle(cx, cy, radius, paint)
            }
        }
    }

    /** Font sizes for the music text, derived from the height next to the album art. */
    private class MusicMetrics(areaHeight: Float, progress: Boolean, pxPerDp: Float) {
        val title = (areaHeight * if (progress) 0.2f else 0.28f)
            .coerceIn(10f * pxPerDp, 16f * pxPerDp)
            .coerceAtMost(areaHeight * 0.4f)
        val subtitle = title * 0.8f
        val titleLine = title * 1.25f
        val subtitleLine = subtitle * 1.3f
        val dotR = title * 0.13f
        val rowGap = title * 0.5f
        val details = subtitleLine + if (progress) rowGap + dotR * 2f + dotR else 0f
    }

    private fun musicTexts(info: MediaInfo?, access: Boolean): Pair<String, String> = when {
        !access -> "ACTIVA EL ACCESO" to "Toca para configurar"
        info == null -> "NADA SONANDO" to "Toca play para reanudar"
        else -> info.title.uppercase(locale) to info.artist
    }

    /** Artist line in the foreground colour at reduced alpha: readable, but quieter than the title. */
    private val secondary: Int get() = (fg and 0x00FFFFFF) or (0xB0 shl 24)

    /**
     * The title split into pages that each fit one line, word by word. The widget flips through
     * them like a ticker, so long titles are shown whole instead of cut off.
     */
    fun musicTitlePages(width: Int, areaHeight: Int, info: MediaInfo?, access: Boolean, progress: Boolean): List<Bitmap> {
        val metrics = MusicMetrics(areaHeight.toFloat(), progress, pxPerDp)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = bold
            textSize = metrics.title
        }
        val lineH = ceil(metrics.titleLine).toInt().coerceAtLeast(1)
        return splitPages(musicTexts(info, access).first, textPaint, width.toFloat()).map { page ->
            val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), lineH, Bitmap.Config.ARGB_8888)
            drawLine(Canvas(bitmap), page, 0f, 0f, width.toFloat(), metrics.title, metrics.titleLine, bold, fg)
            bitmap
        }
    }

    /** Artist line plus, in the large style, the progress row. */
    fun musicDetails(width: Int, areaHeight: Int, info: MediaInfo?, access: Boolean, progress: Boolean): Bitmap {
        val metrics = MusicMetrics(areaHeight.toFloat(), progress, pxPerDp)
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), ceil(metrics.details).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        drawDetails(Canvas(bitmap), 0f, 0f, width.toFloat(), metrics, info, access, progress)
        return bitmap
    }

    private fun drawDetails(canvas: Canvas, x: Float, top: Float, width: Float, metrics: MusicMetrics, info: MediaInfo?, access: Boolean, progress: Boolean) {
        drawLine(canvas, musicTexts(info, access).second, x, top, width, metrics.subtitle, metrics.subtitleLine, light, secondary)
        if (!progress) return
        val y = top + metrics.subtitleLine + metrics.rowGap + metrics.dotR
        val fraction = if (info != null && info.durationMs > 0) (info.positionMs.toFloat() / info.durationMs).coerceIn(0f, 1f) else 0f
        val cols = (width / (metrics.dotR * 3.2f)).toInt().coerceIn(8, 60)
        val step = width / cols
        val filled = (fraction * cols).toInt()
        for (i in 0 until cols) {
            paint.color = when {
                info == null -> dim
                i < filled -> fg
                i == filled -> accent
                else -> dim
            }
            canvas.drawCircle(x + step * (i + 0.5f), y, metrics.dotR, paint)
        }
    }

    /** Greedy word wrap into one-line pages; at most [MAX_TITLE_PAGES], the last one ellipsized if needed. */
    private fun splitPages(text: String, textPaint: TextPaint, width: Float): List<String> {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf(" ")
        val pages = mutableListOf<String>()
        var line = ""
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isEmpty() || textPaint.measureText(candidate) <= width) {
                line = candidate
            } else {
                pages += line
                line = word
            }
        }
        pages += line
        if (pages.size <= MAX_TITLE_PAGES) return pages
        return pages.take(MAX_TITLE_PAGES - 1) + pages.drop(MAX_TITLE_PAGES - 1).joinToString(" ")
    }

    /** Static version for previews: first title page (ellipsized) and details, vertically centred. */
    private fun drawMusicInfo(canvas: Canvas, box: RectF, info: MediaInfo?, access: Boolean, progress: Boolean) {
        val metrics = MusicMetrics(box.height(), progress, pxPerDp)
        val top = box.centerY() - (metrics.titleLine + metrics.details) / 2f
        drawLine(canvas, musicTexts(info, access).first, box.left, top, box.width(), metrics.title, metrics.titleLine, bold, fg)
        drawDetails(canvas, box.left, top + metrics.titleLine, box.width(), metrics, info, access, progress)
    }

    /** One line of text at a fixed size, cut with an ellipsis when too long. */
    private fun drawLine(
        canvas: Canvas,
        text: String,
        x: Float,
        top: Float,
        width: Float,
        size: Float,
        lineHeight: Float,
        typeface: Typeface,
        color: Int,
    ) {
        if (width <= 0f) return
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            this.color = color
            textSize = size
        }
        val shown = TextUtils.ellipsize(text, textPaint, width, TextUtils.TruncateAt.END).toString()
        val metrics = textPaint.fontMetrics
        val baseline = top + (lineHeight - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
        canvas.drawText(shown, x, baseline, textPaint)
    }

    /** All three music styles drawn into one image, for previews (the widget itself uses layouts). */
    private fun musicPreview(canvas: Canvas, area: RectF, info: MediaInfo) {
        val gap = area.height() * 0.08f
        when (config.style) {
            MusicViews.STYLE_COMPACT -> {
                val art = RectF(area.left, area.top, area.left + area.height(), area.bottom)
                drawAlbumArt(canvas, art, info.art)
                val controlW = min(area.height() * 1.6f, area.width() * 0.35f)
                val controls = RectF(area.right - controlW, area.top, area.right, area.bottom)
                drawControls(canvas, controls, info.playing)
                drawMusicInfo(canvas, RectF(art.right + gap, area.top, controls.left - gap, area.bottom), info, true, false)
            }
            MusicViews.STYLE_COVER -> {
                drawAlbumArt(canvas, area, info.art)
                val r = min(area.width(), area.height()) * 0.17f
                paint.color = (palette.bg and 0x00FFFFFF) or (0xD2 shl 24)
                canvas.drawCircle(area.centerX(), area.centerY(), r, paint)
                val half = min(r * 0.7f, 3.5f * 4f * pxPerDp)
                drawGlyph(canvas, if (info.playing) Glyph.PAUSE else Glyph.PLAY, RectF(area.centerX() - half, area.centerY() - half, area.centerX() + half, area.centerY() + half), fg)
            }
            else -> {
                val rowH = min(area.height() * 0.3f, 40f * pxPerDp)
                val top = RectF(area.left, area.top, area.right, area.bottom - rowH)
                val art = RectF(top.left, top.top, top.left + top.height(), top.bottom)
                drawAlbumArt(canvas, art, info.art)
                drawMusicInfo(canvas, RectF(art.right + gap, top.top, top.right, top.bottom), info, true, true)
                drawControls(canvas, RectF(area.left, area.bottom - rowH * 0.9f, area.right, area.bottom), info.playing)
            }
        }
    }

    private fun drawControls(canvas: Canvas, box: RectF, playing: Boolean) {
        val w = box.width() / 3f
        val glyphs = listOf(Glyph.PREVIOUS, if (playing) Glyph.PAUSE else Glyph.PLAY, Glyph.NEXT)
        glyphs.forEachIndexed { i, glyph ->
            val cx = box.left + w * (i + 0.5f)
            // Same cap as the widget: at most 3.4 dp per dot, 7 dots tall.
            val half = min(min(w, box.height()) * 0.38f, 3.5f * 3.4f * pxPerDp)
            drawGlyph(canvas, glyph, RectF(cx - half, box.centerY() - half, cx + half, box.centerY() + half), fg)
        }
    }

    /** Sample track for previews when nothing is playing: a sun over a horizon, to show the halftone. */
    private fun demoMedia(): MediaInfo {
        val size = 48
        val art = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(art)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(0xFF1E2A5A.toInt())
        p.color = 0xFFFF7A3D.toInt()
        c.drawCircle(size * 0.5f, size * 0.55f, size * 0.28f, p)
        p.color = 0xFF0B1020.toInt()
        c.drawRect(0f, size * 0.68f, size.toFloat(), size.toFloat(), p)
        return MediaInfo("Canción", "Artista", art, playing = true, positionMs = 72_000, durationMs = 200_000, sessionActivity = null)
    }

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

    private fun luminance(color: Int): Double =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255

    private fun contrastOn(color: Int): Int = if (luminance(color) > 0.6) Color.BLACK else Color.WHITE
}

private const val MAX_TITLE_PAGES = 5

private fun column(col: Int, rows: IntRange): List<Pair<Int, Int>> = rows.map { col to it }
