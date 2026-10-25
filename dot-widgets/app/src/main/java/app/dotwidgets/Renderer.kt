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
data class DeviceState(
    val batteryLevel: Int,
    val charging: Boolean,
    val media: MediaInfo? = null,
    val weather: WeatherData? = null,
    val weatherPlaceSet: Boolean = true,
    val steps: Int = 0,
    val stepsStatus: StepCounter.Status = StepCounter.Status.OK,
    /** Null means no calendar permission. */
    val events: List<CalendarEvent>? = emptyList(),
    /** Used and total bytes. */
    val storage: Pair<Long, Long> = 0L to 1L,
    val rates: RatesData? = null,
)

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
    private val visual: VisualStyle = ThemeStore.current,
) {
    private val bold: Typeface
    private val light: Typeface
    /** Big numbers (time, temperature, values). Thin in the soft style, like One UI's clock. */
    private val display: Typeface
    private val soft = visual == VisualStyle.SOFT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
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
            else -> if (soft) {
                bold = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                light = Typeface.create("sans-serif", Typeface.NORMAL)
            } else {
                bold = context.resources.getFont(R.font.doto_bold)
                light = context.resources.getFont(R.font.doto_regular)
            }
        }
        display = if (soft && config.font == WidgetConfig.FONT_DOTS) Typeface.create("sans-serif-light", Typeface.NORMAL) else bold
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
            WidgetKind.WEATHER -> weather(canvas, area, device, now)
            WidgetKind.EVENTS -> events(canvas, area, device.events, now)
            WidgetKind.STEPS -> steps(canvas, area, device)
            WidgetKind.PHOTO -> photo(canvas, area)
            WidgetKind.STORAGE -> storage(canvas, area, device.storage)
            WidgetKind.QUICK -> quickPreview(canvas, area)
            WidgetKind.RATES -> rates(canvas, area, device.rates, now.toLocalDate())
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
        if (soft) {
            fitText(canvas, "$hours:$minutes", box, display, fg, measureAs = "00:00")
            return
        }
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
        fitText(canvas, hourText(now), RectF(left.left, left.top, left.right, left.top + half), display, fg, Paint.Align.LEFT, "00")
        fitText(canvas, minuteText(now), RectF(left.left, left.bottom - half, left.right, left.bottom), display, accent, Paint.Align.LEFT, "00")
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
        if (soft) return softAnalogClock(canvas, area, now)
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
        dotRing(canvas, cx, cy, r, device.batteryLevel.coerceIn(0, 100) / 100f, batteryColor(device))
        fitText(canvas, device.batteryLevel.toString(), RectF(cx - r * 0.55f, cy - r * 0.38f, cx + r * 0.55f, cy + r * 0.18f), display, fg, measureAs = "100")
        val label = if (device.charging) "CHG" else "%"
        fitText(canvas, label, RectF(cx - r * 0.3f, cy + r * 0.3f, cx + r * 0.3f, cy + r * 0.48f), bold, if (device.charging) accent else dim, measureAs = "CHG")
    }

    private fun batteryBar(canvas: Canvas, area: RectF, device: DeviceState) {
        val textBox = RectF(area.left, area.top, area.right, area.top + area.height() * 0.55f)
        fitText(canvas, "${device.batteryLevel}%", textBox, display, fg, Paint.Align.LEFT, "100%")
        dotBar(canvas, RectF(area.left, area.top + area.height() * 0.7f, area.right, area.bottom), device.batteryLevel.coerceIn(0, 100) / 100f, batteryColor(device), cols = 10)
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
        fitText(canvas, today.dayOfMonth.toString(), RectF(area.left, area.top, area.left + area.width() * 0.42f, area.top + topH), display, fg, Paint.Align.LEFT, "00")
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
        fitText(canvas, today.dayOfMonth.toString(), RectF(area.left, area.top, area.left + area.width() * 0.5f, area.bottom), display, fg, Paint.Align.LEFT, "00")
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
            if (soft) {
                dotBar(canvas, RectF(area.left, area.top + area.height() * 0.6f, area.right, area.bottom), fraction, accent)
                return
            }
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
        if (soft) {
            // Rounded tiles instead of dots.
            val half = bestCell * 0.39f
            for (i in 0 until total) {
                paint.color = when {
                    i < current -> fg
                    i == current -> accent
                    else -> dim
                }
                val x = startX + bestCell * (i % bestCols + 0.5f)
                val y = startY + bestCell * (i / bestCols + 0.5f)
                canvas.drawRoundRect(x - half, y - half, x + half, y + half, half * 0.5f, half * 0.5f, paint)
            }
            return
        }
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
        fitText(canvas, number, numberBox, display, fg, Paint.Align.LEFT, "000")
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
        if (soft) return softGlyph(canvas, glyph, box, color)
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
        if (source == null && soft) {
            paint.color = dim
            canvas.drawRoundRect(left, top, left + side, top + side, side * 0.12f, side * 0.12f, paint)
            return
        }
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
        if (soft) {
            dotBar(canvas, RectF(x, y - metrics.dotR * 1.2f, x + width, y + metrics.dotR * 1.2f), fraction, if (info == null) dim else accent)
            return
        }
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

    // ---- Weather -----------------------------------------------------------------------------

    /** 9x9 dot icons: '#' foreground dot, '@' accent dot (sun, lightning). */
    private object WeatherIcons {
        val SUN = listOf(
            "....@....", ".@.....@.", "...@@@...", "..@@@@@..", "@.@@@@@.@", "..@@@@@..", "...@@@...", ".@.....@.", "....@....",
        )
        val MOON = listOf(
            "..####...", ".###.....", "###......", "###......", "###......", "###......", "###......", ".###.....", "..####...",
        )
        val PARTLY_DAY = listOf(
            "@..@.....", ".@@@.....", "@@@@@....", ".@@@.###.", "@..#####.", "..#######", ".########", ".#######.", ".........",
        )
        val PARTLY_NIGHT = listOf(
            ".##......", "##.......", "##.......", "##...###.", ".##.#####", "...######", "..#######", "..######.", ".........",
        )
        val CLOUD = listOf(
            ".........", ".........", "...###...", "..#####..", ".#######.", "#########", "#########", ".#######.", ".........",
        )
        val FOG = listOf(
            ".........", "#######..", ".........", "..#######", ".........", "#######..", ".........", "..#######", ".........",
        )
        val RAIN = listOf(
            "...###...", "..#####..", ".#######.", "#########", ".#######.", ".........", ".#..#..#.", "#..#..#..", ".........",
        )
        val DRIZZLE = listOf(
            "...###...", "..#####..", ".#######.", "#########", ".#######.", ".........", "..#...#..", ".........", "....#....",
        )
        val SNOW = listOf(
            "...###...", "..#####..", ".#######.", "#########", ".#######.", ".........", "#.#.#.#.#", ".#.#.#.#.", ".........",
        )
        val STORM = listOf(
            "...###...", "..#####..", ".#######.", "#########", ".##@####.", "...@@....", "..@@.....", "...@@....", "....@....",
        )

        fun of(code: Int, isDay: Boolean): List<String> = when (Condition.of(code)) {
            Condition.CLEAR -> if (isDay) SUN else MOON
            Condition.PARTLY -> if (isDay) PARTLY_DAY else PARTLY_NIGHT
            Condition.CLOUDY -> CLOUD
            Condition.FOG -> FOG
            Condition.DRIZZLE -> DRIZZLE
            Condition.RAIN -> RAIN
            Condition.SNOW -> SNOW
            Condition.STORM -> STORM
        }
    }

    private fun drawWeatherIcon(canvas: Canvas, box: RectF, code: Int, isDay: Boolean) {
        if (soft) return softWeatherIcon(canvas, box, code, isDay)
        val rows = WeatherIcons.of(code, isDay)
        val cell = min(box.width(), box.height()) / 9f
        val left = box.centerX() - cell * 4.5f
        val top = box.centerY() - cell * 4.5f
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, ch ->
                if (ch == '.') return@forEachIndexed
                paint.color = if (ch == '@') accent else fg
                canvas.drawCircle(left + cell * (c + 0.5f), top + cell * (r + 0.5f), cell * 0.38f, paint)
            }
        }
    }

    private fun degrees(celsius: Double): String {
        val value = if (config.fahrenheit) celsius * 9 / 5 + 32 else celsius
        return "${value.roundToInt()}°"
    }

    private fun weather(canvas: Canvas, area: RectF, device: DeviceState, now: LocalDateTime) {
        val data = device.weather
        if (!device.weatherPlaceSet || data == null) {
            val title = if (!device.weatherPlaceSet) "ELIGE UBICACIÓN" else "CARGANDO…"
            val hint = if (!device.weatherPlaceSet) "Abre Dot Widgets" else "Toca para actualizar"
            val icon = RectF(area.left, area.top, area.left + area.height() * 0.5f, area.top + area.height() * 0.5f)
            paint.alpha = 255
            drawWeatherIcon(canvas, icon, 3, true)
            fitText(canvas, title, RectF(area.left, area.top + area.height() * 0.6f, area.right, area.top + area.height() * 0.78f), bold, fg, Paint.Align.LEFT, "ELIGE UBICACIÓN")
            fitText(canvas, hint, RectF(area.left, area.top + area.height() * 0.84f, area.right, area.bottom), light, secondary, Paint.Align.LEFT, "Toca para actualizar")
            return
        }
        when (config.style) {
            1 -> weatherHourly(canvas, area, data, now)
            2 -> weatherWeek(canvas, area, data)
            else -> weatherNow(canvas, area, data)
        }
    }

    private fun todayRange(data: WeatherData): String =
        data.days.firstOrNull()?.let { "${degrees(it.max)} / ${degrees(it.min)}" } ?: ""

    /** "act. 10:30": shown when the forecast is old enough that the user should know. */
    private fun staleLabel(data: WeatherData): String? {
        val age = System.currentTimeMillis() - data.fetchedAt
        if (age < 90 * 60 * 1000L) return null
        val time = java.time.Instant.ofEpochMilli(data.fetchedAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        return "act. %02d:%02d".format(time.hour, time.minute)
    }

    private fun weatherNow(canvas: Canvas, area: RectF, data: WeatherData) {
        // Wide and short (e.g. 4x1): one row, icon | temperature | place, condition and range.
        if (area.width() >= area.height() * WIDE_RATIO) {
            val rowH = min(area.height() * 0.9f, area.width() / 5f)
            weatherHeader(canvas, RectF(area.left, area.centerY() - rowH / 2f, area.right, area.centerY() + rowH / 2f), data, tempWidth = 2.4f)
            return
        }
        val h = area.height()
        fitText(canvas, data.place.uppercase(locale), RectF(area.left, area.top, area.right, area.top + h * 0.14f), bold, secondary, Paint.Align.LEFT, "MMMMMMMMMMMM")
        val iconSide = h * 0.42f
        val iconBox = RectF(area.left, area.top + h * 0.2f, area.left + iconSide, area.top + h * 0.2f + iconSide)
        drawWeatherIcon(canvas, iconBox, data.now.code, data.now.isDay)
        fitText(canvas, degrees(data.now.temp), RectF(iconBox.right + h * 0.06f, iconBox.top, area.right, iconBox.bottom), display, fg, Paint.Align.LEFT, "-00°")
        val bottom = RectF(area.left, area.top + h * 0.72f, area.right, area.top + h * 0.86f)
        fitText(canvas, Condition.of(data.now.code).label.uppercase(locale), RectF(bottom.left, bottom.top, bottom.centerX(), bottom.bottom), bold, accent, Paint.Align.LEFT, "MMMMMMMM")
        fitText(canvas, todayRange(data), RectF(bottom.centerX(), bottom.top, bottom.right, bottom.bottom), bold, fg, Paint.Align.RIGHT, "-00° / -00°")
        staleLabel(data)?.let {
            fitText(canvas, it, RectF(area.left, area.top + h * 0.9f, area.right, area.bottom), light, dim, Paint.Align.LEFT, "act. 00:00")
        }
    }

    /** Big current conditions across the top, used by the hourly and weekly styles. */
    private fun weatherHeader(canvas: Canvas, box: RectF, data: WeatherData, tempWidth: Float = 1.7f) {
        val iconBox = RectF(box.left, box.top, box.left + box.height(), box.bottom)
        drawWeatherIcon(canvas, iconBox, data.now.code, data.now.isDay)
        val tempRight = iconBox.right + box.height() * tempWidth
        fitText(canvas, degrees(data.now.temp), RectF(iconBox.right + box.height() * 0.15f, box.top, tempRight, box.bottom), display, fg, Paint.Align.LEFT, "-00°")
        val textLeft = tempRight + box.height() * 0.15f
        fitText(canvas, data.place.uppercase(locale), RectF(textLeft, box.top, box.right, box.top + box.height() * 0.3f), bold, secondary, Paint.Align.RIGHT, "MMMMMMMMMMMM")
        fitText(canvas, Condition.of(data.now.code).label.uppercase(locale), RectF(textLeft, box.top + box.height() * 0.37f, box.right, box.top + box.height() * 0.63f), bold, accent, Paint.Align.RIGHT, "MMMMMMMM")
        val range = staleLabel(data) ?: todayRange(data)
        fitText(canvas, range, RectF(textLeft, box.top + box.height() * 0.7f, box.right, box.bottom), light, fg, Paint.Align.RIGHT, "-00° / -00°")
    }

    private fun weatherHourly(canvas: Canvas, area: RectF, data: WeatherData, now: LocalDateTime) {
        val h = area.height()
        weatherHeader(canvas, RectF(area.left, area.top, area.right, area.top + h * 0.36f), data)
        val upcoming = data.hours.filter { !it.time.isBefore(now.withMinute(0).withSecond(0).withNano(0)) }.take(6)
        weatherColumns(canvas, RectF(area.left, area.top + h * 0.46f, area.right, area.bottom), upcoming.size) { i, col ->
            val hour = upcoming[i]
            val label = if (i == 0) "AHORA" else "%02d".format(hour.time.hour)
            val extra = if (hour.precipitation >= 20) "${hour.precipitation}%" else ""
            weatherColumn(canvas, col, label, hour.code, hour.isDay, degrees(hour.temp), extra, accent)
        }
    }

    private fun weatherWeek(canvas: Canvas, area: RectF, data: WeatherData) {
        val h = area.height()
        weatherHeader(canvas, RectF(area.left, area.top, area.right, area.top + h * 0.36f), data)
        val days = data.days.take(5)
        weatherColumns(canvas, RectF(area.left, area.top + h * 0.46f, area.right, area.bottom), days.size) { i, col ->
            val day = days[i]
            val label = if (i == 0) "HOY" else day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).trimEnd('.').uppercase(locale)
            weatherColumn(canvas, col, label, day.code, true, degrees(day.max), degrees(day.min), secondary)
        }
    }

    private fun weatherColumns(canvas: Canvas, box: RectF, count: Int, draw: (Int, RectF) -> Unit) {
        if (count == 0) return
        val w = box.width() / count
        for (i in 0 until count) draw(i, RectF(box.left + w * i, box.top, box.left + w * (i + 1), box.bottom))
    }

    /** Label, icon, main value and a small extra line, stacked and centred in [col]. */
    private fun weatherColumn(canvas: Canvas, col: RectF, label: String, code: Int, isDay: Boolean, value: String, extra: String, extraColor: Int) {
        val h = col.height()
        val pad = col.width() * 0.08f
        fitText(canvas, label, RectF(col.left + pad, col.top, col.right - pad, col.top + h * 0.16f), light, secondary, measureAs = "AHORA")
        val iconSide = min(h * 0.34f, col.width() * 0.7f)
        drawWeatherIcon(canvas, RectF(col.centerX() - iconSide / 2f, col.top + h * 0.22f, col.centerX() + iconSide / 2f, col.top + h * 0.22f + iconSide), code, isDay)
        fitText(canvas, value, RectF(col.left + pad, col.top + h * 0.62f, col.right - pad, col.top + h * 0.8f), bold, fg, measureAs = "-00°")
        if (extra.isNotEmpty()) {
            fitText(canvas, extra, RectF(col.left + pad, col.top + h * 0.85f, col.right - pad, col.bottom), light, extraColor, measureAs = "-00°")
        }
    }

    // ---- Steps and storage -------------------------------------------------------------------

    /** Ring of [dots] dots filled to [fraction]; the shared shape of the battery, steps and storage rings. */
    private fun dotRing(canvas: Canvas, cx: Float, cy: Float, r: Float, fraction: Float, active: Int, dots: Int = 24) {
        if (soft) {
            // Continuous arc over a faint track, rounded ends.
            stroke.strokeWidth = r * 0.13f
            stroke.color = dim
            canvas.drawCircle(cx, cy, r, stroke)
            val sweep = 360f * fraction.coerceIn(0f, 1f)
            if (sweep > 0f) {
                stroke.color = active
                canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, sweep, false, stroke)
            }
            return
        }
        val filled = (fraction.coerceIn(0f, 1f) * dots).roundToInt()
        for (i in 0 until dots) {
            val angle = Math.toRadians(-90.0 + i * 360.0 / dots)
            paint.color = if (i < filled) active else dim
            canvas.drawCircle(cx + (r * cos(angle)).toFloat(), cy + (r * sin(angle)).toFloat(), r * 0.075f, paint)
        }
    }

    private fun dotBar(canvas: Canvas, box: RectF, fraction: Float, active: Int, cols: Int = 20) {
        if (soft) {
            val h = min(box.height() * 0.5f, 14f * pxPerDp)
            val top = box.centerY() - h / 2f
            paint.color = dim
            canvas.drawRoundRect(box.left, top, box.right, top + h, h / 2f, h / 2f, paint)
            val w = box.width() * fraction.coerceIn(0f, 1f)
            if (w > 0f) {
                paint.color = active
                canvas.drawRoundRect(box.left, top, box.left + w.coerceAtLeast(h), top + h, h / 2f, h / 2f, paint)
            }
            return
        }
        val filled = (fraction.coerceIn(0f, 1f) * cols).roundToInt()
        val step = box.width() / cols
        for (i in 0 until cols) {
            paint.color = if (i < filled) active else dim
            canvas.drawCircle(box.left + step * (i + 0.5f), box.centerY(), min(step, box.height()) * 0.34f, paint)
        }
    }

    /** "Permission needed"-style message used by widgets that depend on a permission or sensor. */
    private fun notice(canvas: Canvas, area: RectF, title: String, hint: String) {
        fitText(canvas, title, RectF(area.left, area.top + area.height() * 0.3f, area.right, area.top + area.height() * 0.5f), bold, fg, Paint.Align.LEFT, "ACTIVA EL PERMISO")
        fitText(canvas, hint, RectF(area.left, area.top + area.height() * 0.58f, area.right, area.top + area.height() * 0.72f), light, secondary, Paint.Align.LEFT, "Toca para configurar")
    }

    private fun steps(canvas: Canvas, area: RectF, device: DeviceState) {
        when (device.stepsStatus) {
            StepCounter.Status.NO_SENSOR -> return notice(canvas, area, "SIN SENSOR", "Este teléfono no cuenta pasos")
            StepCounter.Status.NO_PERMISSION -> return notice(canvas, area, "ACTIVA EL PERMISO", "Toca para configurar")
            StepCounter.Status.OK -> Unit
        }
        val fraction = device.steps / config.goal.toFloat()
        val color = if (fraction >= 1f) accent else fg
        if (config.style == 1) {
            fitText(canvas, "%,d".format(locale, device.steps), RectF(area.left, area.top, area.right, area.top + area.height() * 0.5f), display, fg, Paint.Align.LEFT, "00.000")
            fitText(canvas, "PASOS · META %,d".format(locale, config.goal), RectF(area.left, area.top + area.height() * 0.56f, area.right, area.top + area.height() * 0.7f), light, secondary, Paint.Align.LEFT, "PASOS · META 00.000")
            dotBar(canvas, RectF(area.left, area.top + area.height() * 0.78f, area.right, area.bottom), fraction, color)
            return
        }
        val cx = area.centerX()
        val cy = area.centerY()
        val r = min(area.width(), area.height()) / 2f * 0.88f
        dotRing(canvas, cx, cy, r, fraction, color)
        fitText(canvas, "%,d".format(locale, device.steps), RectF(cx - r * 0.62f, cy - r * 0.32f, cx + r * 0.62f, cy + r * 0.12f), display, fg, measureAs = "00.000")
        fitText(canvas, "PASOS", RectF(cx - r * 0.4f, cy + r * 0.26f, cx + r * 0.4f, cy + r * 0.44f), bold, if (fraction >= 1f) accent else dim, measureAs = "PASOS")
    }

    private fun gigabytes(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return if (gb >= 100) "%.0f".format(locale, gb) else "%.1f".format(locale, gb)
    }

    private fun storage(canvas: Canvas, area: RectF, usage: Pair<Long, Long>) {
        val (used, total) = usage
        val fraction = used.toFloat() / total.coerceAtLeast(1)
        val color = if (fraction >= 0.9f) accent else fg
        val percent = "${(fraction * 100).roundToInt()}%"
        val detail = "${gigabytes(used)} / ${gigabytes(total)} GB"
        if (config.style == 1) {
            fitText(canvas, percent, RectF(area.left, area.top, area.left + area.width() * 0.55f, area.top + area.height() * 0.5f), display, fg, Paint.Align.LEFT, "100%")
            fitText(canvas, "USADO", RectF(area.left + area.width() * 0.6f, area.top + area.height() * 0.08f, area.right, area.top + area.height() * 0.24f), bold, accent, Paint.Align.RIGHT, "USADO")
            fitText(canvas, detail, RectF(area.left + area.width() * 0.5f, area.top + area.height() * 0.3f, area.right, area.top + area.height() * 0.46f), light, secondary, Paint.Align.RIGHT, "000 / 000 GB")
            dotBar(canvas, RectF(area.left, area.top + area.height() * 0.66f, area.right, area.bottom), fraction, color)
            return
        }
        val cx = area.centerX()
        val cy = area.centerY()
        val r = min(area.width(), area.height()) / 2f * 0.88f
        dotRing(canvas, cx, cy, r, fraction, color)
        fitText(canvas, percent, RectF(cx - r * 0.55f, cy - r * 0.34f, cx + r * 0.55f, cy + r * 0.12f), display, fg, measureAs = "100%")
        fitText(canvas, detail, RectF(cx - r * 0.55f, cy + r * 0.24f, cx + r * 0.55f, cy + r * 0.4f), light, secondary, measureAs = "000 / 000 GB")
    }

    // ---- Calendar events ---------------------------------------------------------------------

    /** "AHORA", "HOY · 16:00", "MAÑANA · TODO EL DÍA", "MIÉ 15 · 09:30". */
    private fun whenLabel(event: CalendarEvent, now: LocalDateTime): String {
        val zone = if (event.allDay) java.time.ZoneOffset.UTC else java.time.ZoneId.systemDefault()
        val start = java.time.Instant.ofEpochMilli(event.begin).atZone(zone).toLocalDateTime()
        val nowMillis = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (!event.allDay && event.begin <= nowMillis && nowMillis < event.end) return "AHORA"
        val day = when (start.toLocalDate()) {
            now.toLocalDate() -> "HOY"
            now.toLocalDate().plusDays(1) -> "MAÑANA"
            else -> "${start.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).trimEnd('.')} ${start.dayOfMonth}"
        }.uppercase(locale)
        val time = if (event.allDay) "TODO EL DÍA" else "%02d:%02d".format(start.hour, start.minute)
        return "$day · $time"
    }

    private fun events(canvas: Canvas, area: RectF, events: List<CalendarEvent>?, now: LocalDateTime) {
        if (events == null) return notice(canvas, area, "ACTIVA EL PERMISO", "Toca para configurar")
        if (events.isEmpty()) return notice(canvas, area, "SIN EVENTOS", "Nada en los próximos 7 días")
        val h = area.height()
        if (config.style == 1) {
            fitText(canvas, "AGENDA", RectF(area.left, area.top, area.right, area.top + h * 0.14f), bold, accent, Paint.Align.LEFT, "AGENDA")
            val rows = events.take(4)
            val rowH = (h * 0.82f) / 4f
            val labelW = area.width() * 0.36f
            rows.forEachIndexed { i, event ->
                val top = area.top + h * 0.18f + rowH * i
                val label = whenLabel(event, now)
                fitText(canvas, label, RectF(area.left, top + rowH * 0.2f, area.left + labelW - area.width() * 0.03f, top + rowH * 0.62f), light, if (label == "AHORA") accent else secondary, Paint.Align.LEFT, "MIÉ 00 · 00:00")
                drawLine(canvas, event.title, area.left + labelW, top + rowH * 0.1f, area.width() - labelW, (rowH * 0.5f).coerceAtMost(16f * pxPerDp), rowH * 0.62f, bold, fg)
            }
            return
        }
        val event = events.first()
        val label = whenLabel(event, now)
        fitText(canvas, label, RectF(area.left, area.top, area.right, area.top + h * 0.16f), bold, accent, Paint.Align.LEFT, "MAÑANA · TODO EL DÍA")
        // Title wraps over up to the middle of the widget, as large as fits.
        val titleBox = RectF(area.left, area.top + h * 0.24f, area.right, area.top + h * (if (event.location.isBlank()) 0.92f else 0.76f))
        wrappedText(canvas, event.title, titleBox, bold, fg, maxSize = 26f * pxPerDp)
        if (event.location.isNotBlank()) {
            drawLine(canvas, event.location, area.left, area.top + h * 0.8f, area.width(), min(h * 0.12f, 12f * pxPerDp), h * 0.16f, light, secondary)
        }
        if (events.size > 1) {
            paint.color = dim
            val dotR = h * 0.025f
            for (i in 1 until min(events.size, 5)) {
                canvas.drawCircle(area.right - dotR - (i - 1) * dotR * 3f, area.top + h * 0.08f, dotR, paint)
            }
        }
    }

    /** Multi-line text, largest size up to [maxSize] that fits [box] without breaking words. */
    private fun wrappedText(canvas: Canvas, text: String, box: RectF, typeface: Typeface, color: Int, maxSize: Float) {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            this.color = color
        }
        val width = box.width().toInt().coerceAtLeast(1)
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var lo = 4f
        var hi = min(maxSize, box.height())
        repeat(14) {
            val mid = (lo + hi) / 2f
            textPaint.textSize = mid
            val layout = staticLayout(text, textPaint, width, Layout.Alignment.ALIGN_NORMAL)
            if (layout.height <= box.height() && words.all { textPaint.measureText(it) <= width }) lo = mid else hi = mid
        }
        textPaint.textSize = lo
        val layout = staticLayout(text, textPaint, width, Layout.Alignment.ALIGN_NORMAL)
        canvas.save()
        canvas.translate(box.left, box.top)
        layout.draw(canvas)
        canvas.restore()
    }

    // ---- Photo -------------------------------------------------------------------------------

    private fun photo(canvas: Canvas, area: RectF) {
        val source = loadPhoto()
        if (source == null) {
            val n = 8
            val cell = min(area.width(), area.height()) / n
            paint.color = dim
            for (r in 0 until (area.height() / cell).toInt()) for (c in 0 until (area.width() / cell).toInt()) {
                canvas.drawCircle(area.left + cell * (c + 0.5f), area.top + cell * (r + 0.5f), cell * 0.15f, paint)
            }
            val hint = if (config.photoUri.isBlank()) "ELIGE UNA FOTO" else "FOTO NO DISPONIBLE"
            fitText(canvas, hint, RectF(area.left, area.centerY() - area.height() * 0.08f, area.right, area.centerY() + area.height() * 0.08f), bold, fg, measureAs = "FOTO NO DISPONIBLE")
            return
        }
        // Fill: crop the photo to the widget. Fit: show the whole photo inside it.
        val srcAspect = source.width.toFloat() / source.height
        val boxAspect = area.width() / area.height()
        val crop: Rect
        val target: RectF
        if (config.style == 1) {
            crop = Rect(0, 0, source.width, source.height)
            target = if (srcAspect > boxAspect) {
                val h = area.width() / srcAspect
                RectF(area.left, area.centerY() - h / 2f, area.right, area.centerY() + h / 2f)
            } else {
                val w = area.height() * srcAspect
                RectF(area.centerX() - w / 2f, area.top, area.centerX() + w / 2f, area.bottom)
            }
        } else {
            target = RectF(area)
            crop = if (srcAspect > boxAspect) {
                val w = (source.height * boxAspect).toInt()
                Rect((source.width - w) / 2, 0, (source.width + w) / 2, source.height)
            } else {
                val h = (source.width / boxAspect).toInt()
                Rect(0, (source.height - h) / 2, source.width, (source.height + h) / 2)
            }
        }
        if (config.artMode == WidgetConfig.ART_PHOTO) {
            val radius = min(target.width(), target.height()) * 0.06f
            val clip = Path().apply { addRoundRect(target, radius, radius, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawBitmap(source, crop, target, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
            return
        }
        val cols = config.dotCount
        val cell = target.width() / cols
        val rows = (target.height() / cell).toInt().coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(source, crop.left, crop.top, crop.width().coerceAtLeast(1), crop.height().coerceAtLeast(1))
        val small = Bitmap.createScaledBitmap(cropped, cols, rows, true)
        val darkBackground = luminance(palette.bg) < 0.5
        val top = target.top + (target.height() - rows * cell) / 2f
        for (r in 0 until rows) for (c in 0 until cols) {
            val pixel = small.getPixel(c, r)
            val cx = target.left + cell * (c + 0.5f)
            val cy = top + cell * (r + 0.5f)
            if (config.artMode == WidgetConfig.ART_DOTS_COLOR) {
                paint.color = pixel or (0xFF shl 24)
                canvas.drawCircle(cx, cy, cell * 0.42f, paint)
                continue
            }
            val l = luminance(pixel).toFloat()
            val radius = cell * 0.5f * sqrt(if (darkBackground) l else 1f - l)
            if (radius >= cell * 0.1f) {
                paint.color = fg
                canvas.drawCircle(cx, cy, radius, paint)
            }
        }
    }

    /** Decodes the chosen photo at a small size (it only feeds a dot grid or a widget-sized image). */
    private fun loadPhoto(): Bitmap? {
        if (config.photoUri.isBlank()) return null
        return runCatching {
            val uri = android.net.Uri.parse(config.photoUri)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val longest = maxOf(info.size.width, info.size.height)
                    decoder.setTargetSampleSize((longest / 640).coerceAtLeast(1))
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
            }
        }.getOrNull()
    }

    // ---- Exchange rates ----------------------------------------------------------------------

    private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "HOY"
        today.minusDays(1) -> "AYER"
        else -> "${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.SHORT, locale).trimEnd('.')}".uppercase(locale)
    }

    /** "SUBE $2,15 · 0,23%", "BAJA …" or "SIN CAMBIO", with the colour to use. */
    private fun changeText(series: RateSeries): Pair<String, Int> {
        val delta = series.change ?: return "SIN DATO ANTERIOR" to dim
        val previous = series.previous?.value ?: return "SIN DATO ANTERIOR" to dim
        if (abs(delta) < 1e-9) return "SIN CAMBIO" to secondary
        val percent = java.text.NumberFormat.getNumberInstance(Indicator.CHILE).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(abs(delta) / previous * 100)
        val word = if (delta > 0) "SUBE" else "BAJA"
        return "$word $${series.indicator.formatChange(delta)} · $percent%" to (if (delta > 0) accent else secondary)
    }

    private fun rates(canvas: Canvas, area: RectF, data: RatesData?, today: LocalDate) {
        if (data == null || data.series.isEmpty()) return notice(canvas, area, "CARGANDO…", "Toca para actualizar")
        if (config.style == 1) return ratesList(canvas, area, data, today)
        val series = data.series[Indicator.of(config.indicator)] ?: data.series.values.first()
        val latest = series.latest ?: return
        val h = area.height()
        fitText(canvas, series.indicator.longLabel, RectF(area.left, area.top, area.left + area.width() * 0.68f, area.top + h * 0.14f), bold, secondary, Paint.Align.LEFT, "UNIDAD TRIBUTARIA")
        fitText(canvas, dayLabel(latest.date, today), RectF(area.left + area.width() * 0.72f, area.top, area.right, area.top + h * 0.14f), bold, dim, Paint.Align.RIGHT, "00 MMM")
        fitText(canvas, series.indicator.format(latest.value), RectF(area.left, area.top + h * 0.2f, area.right, area.top + h * 0.58f), display, fg, Paint.Align.LEFT, "$00.000,00")
        val (change, color) = changeText(series)
        fitText(canvas, change, RectF(area.left, area.top + h * 0.63f, area.right, area.top + h * 0.75f), bold, color, Paint.Align.LEFT, "SUBE $00,00 · 0,00%")
        sparkline(canvas, RectF(area.left, area.top + h * 0.82f, area.right, area.bottom), series)
    }

    /** Recent values as dots, oldest on the left; the latest in the accent colour. */
    private fun sparkline(canvas: Canvas, box: RectF, series: RateSeries) {
        val values = series.points.take(20).reversed().map { it.value }
        if (values.size < 2) return
        val lo = values.minOrNull() ?: return
        val hi = values.maxOrNull() ?: return
        if (soft) {
            // A smooth line with the latest value marked.
            val step = box.width() / (values.size - 1)
            val pad = box.height() * 0.15f
            fun yOf(v: Double) = box.bottom - pad - (if (hi - lo < 1e-9) 0.5f else ((v - lo) / (hi - lo)).toFloat()) * (box.height() - 2 * pad)
            val path = Path()
            values.forEachIndexed { i, v -> if (i == 0) path.moveTo(box.left, yOf(v)) else path.lineTo(box.left + step * i, yOf(v)) }
            stroke.strokeWidth = box.height() * 0.08f
            stroke.color = fg
            canvas.drawPath(path, stroke)
            paint.color = accent
            canvas.drawCircle(box.right, yOf(values.last()), box.height() * 0.13f, paint)
            return
        }
        val step = box.width() / values.size
        val r = min(step * 0.3f, box.height() * 0.16f)
        values.forEachIndexed { i, v ->
            val t = if (hi - lo < 1e-9) 0.5f else ((v - lo) / (hi - lo)).toFloat()
            val y = box.bottom - r - t * (box.height() - 2 * r)
            paint.color = if (i == values.lastIndex) accent else fg
            canvas.drawCircle(box.left + step * (i + 0.5f), y, if (i == values.lastIndex) r * 1.3f else r, paint)
        }
    }

    private fun ratesList(canvas: Canvas, area: RectF, data: RatesData, today: LocalDate) {
        val h = area.height()
        fitText(canvas, "BANCO CENTRAL", RectF(area.left, area.top, area.left + area.width() * 0.66f, area.top + h * 0.14f), bold, accent, Paint.Align.LEFT, "BANCO CENTRAL")
        data.series[Indicator.DOLAR]?.latest?.let {
            fitText(canvas, dayLabel(it.date, today), RectF(area.left + area.width() * 0.7f, area.top, area.right, area.top + h * 0.14f), bold, dim, Paint.Align.RIGHT, "00 MMM")
        }
        val rows = Indicator.entries
        val top = area.top + h * 0.22f
        val rowH = (area.bottom - top) / rows.size
        rows.forEachIndexed { i, indicator ->
            val y = top + rowH * i
            val series = data.series[indicator]
            val textBox = RectF(area.left, y + rowH * 0.15f, area.right, y + rowH * 0.85f)
            fitText(canvas, indicator.label, RectF(textBox.left, textBox.top, textBox.left + area.width() * 0.3f, textBox.bottom), bold, secondary, Paint.Align.LEFT, "DÓLAR")
            val value = series?.latest?.let { indicator.format(it.value) } ?: "—"
            fitText(canvas, value, RectF(textBox.left + area.width() * 0.38f, textBox.top, textBox.right, textBox.bottom), bold, fg, Paint.Align.RIGHT, "$00.000,00")
            // Small change marker between label and value: accent when the value went up.
            val delta = series?.change
            paint.color = when {
                delta == null || abs(delta) < 1e-9 -> dim
                delta > 0 -> accent
                else -> fg
            }
            canvas.drawCircle(area.left + area.width() * 0.33f, textBox.centerY(), rowH * 0.09f, paint)
        }
    }

    // ---- Quick settings ----------------------------------------------------------------------

    private object QuickIcons {
        val TORCH = listOf(".#######.", ".#######.", "..#####..", "...###...", "...#.#...", "...###...", "...###...", "...###...", "...###...")
        val WIFI = listOf(".........", ".#######.", "#.......#", "..#####..", ".#.....#.", "...###...", ".........", "....#....", ".........")
        val BLUETOOTH = listOf("...##....", "...#.#...", ".#.#..#..", "..##.#...", "...##....", "..##.#...", ".#.#..#..", "...#.#...", "...##....")
        val MOON = listOf("..####...", ".###.....", "###......", "###......", "###......", "###......", "###......", ".###.....", "..####...")

        fun of(toggle: QuickToggle) = when (toggle) {
            QuickToggle.TORCH -> TORCH
            QuickToggle.WIFI -> WIFI
            QuickToggle.BLUETOOTH -> BLUETOOTH
            QuickToggle.DND -> MOON
        }
    }

    private fun drawPattern(canvas: Canvas, box: RectF, rows: List<String>, color: Int) {
        val cell = min(box.width(), box.height()) / 9f
        val left = box.centerX() - cell * 4.5f
        val top = box.centerY() - cell * 4.5f
        paint.color = color
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, ch ->
                if (ch != '.') canvas.drawCircle(left + cell * (c + 0.5f), top + cell * (r + 0.5f), cell * 0.38f, paint)
            }
        }
    }

    private fun drawQuickButton(canvas: Canvas, box: RectF, toggle: QuickToggle, on: Boolean) {
        val r = min(box.width(), box.height()) / 2f * 0.92f
        paint.color = if (on) palette.accent else (fg and 0x00FFFFFF) or (0x22 shl 24)
        canvas.drawCircle(box.centerX(), box.centerY(), r, paint)
        val half = r * 0.55f
        if (soft) {
            softQuickIcon(canvas, RectF(box.centerX() - half, box.centerY() - half, box.centerX() + half, box.centerY() + half), toggle, if (on) contrastOn(palette.accent) else fg)
            return
        }
        drawPattern(
            canvas,
            RectF(box.centerX() - half, box.centerY() - half, box.centerX() + half, box.centerY() + half),
            QuickIcons.of(toggle),
            if (on) contrastOn(palette.accent) else fg,
        )
    }

    /** One round toggle button as its own bitmap (each is a separate tap target in the widget). */
    fun quickButton(toggle: QuickToggle, on: Boolean, size: Int): Bitmap {
        val side = size.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        drawQuickButton(Canvas(bitmap), RectF(0f, 0f, side.toFloat(), side.toFloat()), toggle, on)
        return bitmap
    }

    private fun quickPreview(canvas: Canvas, area: RectF) {
        val w = area.width() / 4f
        QuickToggle.entries.forEachIndexed { i, toggle ->
            drawQuickButton(canvas, RectF(area.left + w * i, area.top, area.left + w * (i + 1), area.bottom), toggle, i == 0 || i == 1)
        }
    }

    // ---- Soft style: vector shapes ------------------------------------------------------------

    private fun softAnalogClock(canvas: Canvas, area: RectF, now: LocalDateTime) {
        val cx = area.centerX()
        val cy = area.centerY()
        val r = min(area.width(), area.height()) / 2f * 0.94f
        for (i in 0 until 12) {
            val angle = Math.toRadians(i * 30.0 - 90)
            val major = i % 3 == 0
            stroke.color = if (major) fg else dim
            stroke.strokeWidth = r * if (major) 0.045f else 0.03f
            val inner = r * if (major) 0.8f else 0.86f
            canvas.drawLine(
                cx + (inner * cos(angle)).toFloat(), cy + (inner * sin(angle)).toFloat(),
                cx + (r * 0.95f * cos(angle)).toFloat(), cy + (r * 0.95f * sin(angle)).toFloat(),
                stroke,
            )
        }
        val minutes = now.minute + now.second / 60f
        val hours = now.hour % 12 + minutes / 60f
        fun hand(degrees: Float, length: Float, width: Float) {
            val angle = Math.toRadians(degrees - 90.0)
            stroke.color = fg
            stroke.strokeWidth = width
            canvas.drawLine(cx, cy, cx + (length * cos(angle)).toFloat(), cy + (length * sin(angle)).toFloat(), stroke)
        }
        hand(hours * 30f, r * 0.5f, r * 0.08f)
        hand(minutes * 6f, r * 0.74f, r * 0.05f)
        paint.color = accent
        canvas.drawCircle(cx, cy, r * 0.065f, paint)
        if (config.showDate) {
            fitText(canvas, now.dayOfMonth.toString(), RectF(cx + r * 0.3f, cy - r * 0.1f, cx + r * 0.62f, cy + r * 0.1f), bold, accent, measureAs = "00")
        }
    }

    private fun softGlyph(canvas: Canvas, glyph: Glyph, box: RectF, color: Int) {
        val s = min(box.width(), box.height()) * 0.8f
        val cx = box.centerX()
        val cy = box.centerY()
        paint.color = color
        stroke.color = color
        stroke.strokeWidth = s * 0.12f
        fun triangle(left: Float, right: Float, pointsRight: Boolean) {
            val path = Path()
            if (pointsRight) {
                path.moveTo(left, cy - s * 0.42f); path.lineTo(right, cy); path.lineTo(left, cy + s * 0.42f)
            } else {
                path.moveTo(right, cy - s * 0.42f); path.lineTo(left, cy); path.lineTo(right, cy + s * 0.42f)
            }
            path.close()
            canvas.drawPath(path, paint)
            canvas.drawPath(path, stroke)
        }
        when (glyph) {
            Glyph.PLAY -> triangle(cx - s * 0.3f, cx + s * 0.4f, true)
            Glyph.PAUSE -> {
                val w = s * 0.2f
                canvas.drawRoundRect(cx - s * 0.32f, cy - s * 0.42f, cx - s * 0.32f + w, cy + s * 0.42f, w / 2f, w / 2f, paint)
                canvas.drawRoundRect(cx + s * 0.12f, cy - s * 0.42f, cx + s * 0.12f + w, cy + s * 0.42f, w / 2f, w / 2f, paint)
            }
            Glyph.NEXT -> {
                triangle(cx - s * 0.4f, cx + s * 0.2f, true)
                canvas.drawLine(cx + s * 0.36f, cy - s * 0.38f, cx + s * 0.36f, cy + s * 0.38f, stroke)
            }
            Glyph.PREVIOUS -> {
                triangle(cx - s * 0.2f, cx + s * 0.4f, false)
                canvas.drawLine(cx - s * 0.36f, cy - s * 0.38f, cx - s * 0.36f, cy + s * 0.38f, stroke)
            }
        }
    }

    private fun softSun(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.color = accent
        canvas.drawCircle(cx, cy, r, paint)
        stroke.color = accent
        stroke.strokeWidth = r * 0.28f
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            canvas.drawLine(
                cx + (r * 1.45f * cos(a)).toFloat(), cy + (r * 1.45f * sin(a)).toFloat(),
                cx + (r * 1.85f * cos(a)).toFloat(), cy + (r * 1.85f * sin(a)).toFloat(),
                stroke,
            )
        }
    }

    private fun softMoon(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val moon = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        val bite = Path().apply { addCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.85f, Path.Direction.CW) }
        moon.op(bite, Path.Op.DIFFERENCE)
        paint.color = color
        canvas.drawPath(moon, paint)
    }

    /** A cloud [w] wide centred on (cx, cy): three bumps on a rounded base. */
    private fun softCloud(canvas: Canvas, cx: Float, cy: Float, w: Float) {
        paint.color = fg
        canvas.drawCircle(cx - w * 0.2f, cy + w * 0.02f, w * 0.2f, paint)
        canvas.drawCircle(cx + w * 0.04f, cy - w * 0.1f, w * 0.27f, paint)
        canvas.drawCircle(cx + w * 0.27f, cy + w * 0.04f, w * 0.17f, paint)
        canvas.drawRoundRect(cx - w * 0.4f, cy + w * 0.02f, cx + w * 0.44f, cy + w * 0.22f, w * 0.1f, w * 0.1f, paint)
    }

    private fun softWeatherIcon(canvas: Canvas, box: RectF, code: Int, isDay: Boolean) {
        val s = min(box.width(), box.height())
        val cx = box.centerX()
        val cy = box.centerY()
        when (Condition.of(code)) {
            Condition.CLEAR -> if (isDay) softSun(canvas, cx, cy, s * 0.22f) else softMoon(canvas, cx, cy, s * 0.36f, fg)
            Condition.PARTLY -> {
                if (isDay) softSun(canvas, cx - s * 0.14f, cy - s * 0.14f, s * 0.15f) else softMoon(canvas, cx - s * 0.14f, cy - s * 0.14f, s * 0.22f, dim)
                softCloud(canvas, cx + s * 0.05f, cy + s * 0.1f, s * 0.7f)
            }
            Condition.CLOUDY -> softCloud(canvas, cx, cy, s * 0.9f)
            Condition.FOG -> {
                stroke.color = fg
                stroke.strokeWidth = s * 0.07f
                listOf(-0.22f, 0f, 0.22f).forEachIndexed { i, dy ->
                    val inset = if (i == 1) 0.05f else 0.15f
                    canvas.drawLine(box.left + s * inset, cy + s * dy, box.right - s * (0.2f - inset), cy + s * dy, stroke)
                }
            }
            Condition.DRIZZLE, Condition.RAIN -> {
                softCloud(canvas, cx, cy - s * 0.12f, s * 0.8f)
                stroke.color = accent
                stroke.strokeWidth = s * 0.06f
                val drops = if (Condition.of(code) == Condition.RAIN) listOf(-0.2f, 0f, 0.2f) else listOf(-0.1f, 0.12f)
                drops.forEach { dx -> canvas.drawLine(cx + s * (dx + 0.04f), cy + s * 0.22f, cx + s * (dx - 0.04f), cy + s * 0.38f, stroke) }
            }
            Condition.SNOW -> {
                softCloud(canvas, cx, cy - s * 0.12f, s * 0.8f)
                paint.color = fg
                listOf(-0.2f, 0f, 0.2f).forEach { dx -> canvas.drawCircle(cx + s * dx, cy + s * 0.32f, s * 0.045f, paint) }
            }
            Condition.STORM -> {
                softCloud(canvas, cx, cy - s * 0.14f, s * 0.8f)
                paint.color = accent
                canvas.drawPath(Path().apply {
                    moveTo(cx + s * 0.04f, cy + s * 0.08f)
                    lineTo(cx - s * 0.1f, cy + s * 0.3f)
                    lineTo(cx + s * 0.02f, cy + s * 0.3f)
                    lineTo(cx - s * 0.06f, cy + s * 0.46f)
                    lineTo(cx + s * 0.14f, cy + s * 0.22f)
                    lineTo(cx + s * 0.02f, cy + s * 0.22f)
                    close()
                }, paint)
            }
        }
    }

    private fun softQuickIcon(canvas: Canvas, box: RectF, toggle: QuickToggle, color: Int) {
        val s = min(box.width(), box.height())
        val cx = box.centerX()
        val cy = box.centerY()
        paint.color = color
        stroke.color = color
        stroke.strokeWidth = s * 0.1f
        when (toggle) {
            QuickToggle.TORCH -> {
                canvas.drawPath(Path().apply {
                    moveTo(cx - s * 0.32f, cy - s * 0.42f)
                    lineTo(cx + s * 0.32f, cy - s * 0.42f)
                    lineTo(cx + s * 0.18f, cy - s * 0.12f)
                    lineTo(cx - s * 0.18f, cy - s * 0.12f)
                    close()
                }, paint)
                canvas.drawRoundRect(cx - s * 0.16f, cy - s * 0.1f, cx + s * 0.16f, cy + s * 0.46f, s * 0.06f, s * 0.06f, paint)
            }
            QuickToggle.WIFI -> {
                listOf(0.46f, 0.31f, 0.16f).forEach { r ->
                    val rr = s * r
                    canvas.drawArc(cx - rr, cy + s * 0.3f - rr, cx + rr, cy + s * 0.3f + rr, 225f, 90f, false, stroke)
                }
                canvas.drawCircle(cx, cy + s * 0.3f, s * 0.07f, paint)
            }
            QuickToggle.BLUETOOTH -> {
                stroke.strokeWidth = s * 0.08f
                canvas.drawPath(Path().apply {
                    moveTo(cx - s * 0.22f, cy - s * 0.2f)
                    lineTo(cx + s * 0.2f, cy + s * 0.2f)
                    lineTo(cx, cy + s * 0.42f)
                    lineTo(cx, cy - s * 0.42f)
                    lineTo(cx + s * 0.2f, cy - s * 0.2f)
                    lineTo(cx - s * 0.22f, cy + s * 0.2f)
                }, stroke)
            }
            QuickToggle.DND -> softMoon(canvas, cx, cy, s * 0.4f, color)
        }
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

/** Width/height ratio from which the weather "Ahora" style switches to its single-row layout. */
private const val WIDE_RATIO = 2.2f

private fun column(col: Int, rows: IntRange): List<Pair<Int, Int>> = rows.map { col to it }
