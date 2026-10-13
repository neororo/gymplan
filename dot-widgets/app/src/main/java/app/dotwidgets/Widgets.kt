package app.dotwidgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.BatteryManager
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.widget.RemoteViews
import java.time.LocalDateTime
import kotlin.math.min
import kotlin.math.sqrt

/** One provider per widget kind; they only differ in which [WidgetKind] they render. */
abstract class KindWidget(private val kind: WidgetKind) : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, WidgetViews.build(context, manager, it, kind)) }
        Scheduler.refresh(context)
        if (kind == WidgetKind.STEPS) {
            // Take a fresh step reading right away instead of waiting for the next tick.
            val pending = goAsync()
            StepCounter.read(context) {
                Scheduler.update(context, listOf(WidgetKind.STEPS))
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        manager.updateAppWidget(id, WidgetViews.build(context, manager, id, kind))
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetStore.delete(context, it) }
    }

    override fun onEnabled(context: Context) = Scheduler.refresh(context)

    override fun onDisabled(context: Context) = Scheduler.refresh(context)
}

class ClockWidget : KindWidget(WidgetKind.CLOCK)
class BatteryWidget : KindWidget(WidgetKind.BATTERY)
class CalendarWidget : KindWidget(WidgetKind.CALENDAR)
class DateWidget : KindWidget(WidgetKind.DATE)

/** Keeps its original class name so progress widgets placed with v0.1 ("Año en puntos") survive the update. */
class YearWidget : KindWidget(WidgetKind.PROGRESS)
class CountdownWidget : KindWidget(WidgetKind.COUNTDOWN)
class NoteWidget : KindWidget(WidgetKind.NOTE)
class MusicWidget : KindWidget(WidgetKind.MUSIC)
class WeatherWidget : KindWidget(WidgetKind.WEATHER)
class EventsWidget : KindWidget(WidgetKind.EVENTS)
class StepsWidget : KindWidget(WidgetKind.STEPS)
class PhotoWidget : KindWidget(WidgetKind.PHOTO)
class StorageWidget : KindWidget(WidgetKind.STORAGE)
class QuickWidget : KindWidget(WidgetKind.QUICK)

object WidgetViews {
    // RemoteViews cross binder (~1 MB transaction limit), so keep the bitmap under ~720 KB.
    private const val MAX_PIXELS = 180_000f
    private const val PADDING_DP = 12

    /** Never throws: a widget that fails to draw shows the error instead of crashing the app. */
    fun build(context: Context, manager: AppWidgetManager, id: Int, kind: WidgetKind): RemoteViews =
        try {
            buildOrThrow(context, manager, id, kind)
        } catch (error: Throwable) {
            CrashLog.record(context, "widget ${kind.name}", error)
            errorViews(context, kind, error)
        }

    private fun errorViews(context: Context, kind: WidgetKind, error: Throwable): RemoteViews {
        val density = context.resources.displayMetrics.density
        val width = (220 * density).toInt()
        val height = (90 * density).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD71921.toInt(); textSize = 16 * density }
        canvas.drawText("ERROR · ${kind.name}", 0f, 18 * density, paint)
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = 11 * density
        val lines = listOf(error.javaClass.simpleName) + (error.message ?: "").chunked(38).take(3) +
            listOf("Abre Dot Widgets para ver el detalle")
        lines.forEachIndexed { i, line -> canvas.drawText(line, 0f, (36 + i * 14) * density, paint) }
        return RemoteViews(context.packageName, R.layout.widget_image).apply {
            setInt(R.id.bg, "setColorFilter", 0xFF000000.toInt())
            setImageViewBitmap(R.id.content, bitmap)
            setOnClickPendingIntent(R.id.root, activity(context, 999, Intent(context, MainActivity::class.java)))
        }
    }

    private fun buildOrThrow(context: Context, manager: AppWidgetManager, id: Int, kind: WidgetKind): RemoteViews {
        val config = WidgetStore.load(context, id, kind)
        val palette = Palettes.find(context, config.palette)
        if (kind == WidgetKind.MUSIC) return MusicViews.build(context, manager.getAppWidgetOptions(id), id, config, palette)
        if (kind == WidgetKind.QUICK) return QuickViews.build(context, manager.getAppWidgetOptions(id), config, palette)
        val (width, height) = contentSize(context, manager.getAppWidgetOptions(id), kind)
        val bitmap = Renderer(context, config, palette).render(
            kind,
            width,
            height,
            LocalDateTime.now(),
            deviceState(context, kind),
        )
        return RemoteViews(context.packageName, R.layout.widget_image).apply {
            setImageViewResource(R.id.bg, cornerDrawable(config.corner))
            setInt(R.id.bg, "setColorFilter", palette.bg)
            setInt(R.id.bg, "setImageAlpha", config.bgAlpha * 255 / 100)
            setImageViewBitmap(R.id.content, bitmap)
            setOnClickPendingIntent(R.id.root, clickIntent(context, id, kind))
        }
    }

    /** Gathers only what [kind] needs (each source has its own cost or permission). */
    fun deviceState(context: Context, kind: WidgetKind): DeviceState {
        val battery = context.getSystemService(BatteryManager::class.java)
        return DeviceState(
            battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            battery.isCharging,
            media = if (kind == WidgetKind.MUSIC) MediaState.current(context) else null,
            weather = if (kind == WidgetKind.WEATHER) WeatherStore.data(context) else null,
            weatherPlaceSet = kind != WidgetKind.WEATHER || WeatherStore.place(context) != null,
            steps = if (kind == WidgetKind.STEPS) StepCounter.today(context) else 0,
            stepsStatus = if (kind == WidgetKind.STEPS) StepCounter.status(context) else StepCounter.Status.OK,
            events = if (kind == WidgetKind.EVENTS) CalendarEvents.upcoming(context) else emptyList(),
            storage = if (kind == WidgetKind.STORAGE) Storage.usage() else 0L to 1L,
        )
    }

    fun cornerDrawable(corner: Int): Int = when (corner) {
        0 -> R.drawable.bg_r0
        1 -> R.drawable.bg_r12
        3 -> R.drawable.bg_r36
        else -> R.drawable.bg_r24
    }

    fun sizeDp(options: Bundle?, kind: WidgetKind): Pair<Int, Int> {
        // Portrait size: min width x max height.
        val width = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)?.takeIf { it > 0 } ?: kind.widthDp
        val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)?.takeIf { it > 0 } ?: kind.heightDp
        return width to height
    }

    private fun contentSize(context: Context, options: Bundle?, kind: WidgetKind): Pair<Int, Int> {
        val (widthDp, heightDp) = sizeDp(options, kind)
        val density = context.resources.displayMetrics.density
        val width = (widthDp - 2 * PADDING_DP).coerceAtLeast(32) * density
        val height = (heightDp - 2 * PADDING_DP).coerceAtLeast(32) * density
        val scale = min(1f, sqrt(MAX_PIXELS / (width * height)))
        return (width * scale).toInt() to (height * scale).toInt()
    }

    private fun clickIntent(context: Context, id: Int, kind: WidgetKind): PendingIntent = when (kind) {
        WidgetKind.CLOCK -> activity(context, id, Intent(AlarmClock.ACTION_SHOW_ALARMS))
        // Tapping refreshes right away; otherwise the level updates once a minute.
        WidgetKind.BATTERY -> Scheduler.refreshIntent(context)
        WidgetKind.WEATHER ->
            if (WeatherStore.place(context) == null) {
                activity(context, id, Intent(context, MainActivity::class.java))
            } else {
                WeatherRefreshReceiver.pending(context)
            }
        WidgetKind.STEPS ->
            if (StepCounter.status(context) == StepCounter.Status.NO_PERMISSION) {
                activity(context, id, ConfigActivity.intent(context, id))
            } else {
                Scheduler.refreshIntent(context)
            }
        WidgetKind.EVENTS -> {
            val next = if (CalendarEvents.hasPermission(context)) CalendarEvents.upcoming(context, 1)?.firstOrNull() else null
            when {
                !CalendarEvents.hasPermission(context) -> activity(context, id, ConfigActivity.intent(context, id))
                next != null -> activity(
                    context,
                    id,
                    Intent(Intent.ACTION_VIEW, android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, next.eventId)),
                )
                else -> activity(context, id, Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()))
            }
        }
        WidgetKind.STORAGE -> activity(context, id, Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
        WidgetKind.CALENDAR, WidgetKind.DATE -> {
            val uri = CalendarContract.CONTENT_URI.buildUpon()
                .appendPath("time")
                .appendPath(System.currentTimeMillis().toString())
                .build()
            activity(context, id, Intent(Intent.ACTION_VIEW, uri))
        }
        else -> activity(context, id, ConfigActivity.intent(context, id))
    }

    private fun activity(context: Context, id: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            id,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** Renders a widget the way it looks on the home screen, background included, for in-app previews. */
object Previews {
    /** Like [render], but a failure becomes a logged, blank preview instead of crashing the screen. */
    fun safeRender(context: Context, kind: WidgetKind, config: WidgetConfig, widthDp: Int, heightDp: Int, maxWidthPx: Int): Bitmap =
        try {
            render(context, kind, config, widthDp, heightDp, maxWidthPx)
        } catch (error: Throwable) {
            CrashLog.record(context, "vista previa ${kind.name}", error)
            Bitmap.createBitmap(maxWidthPx.coerceAtLeast(1), (maxWidthPx / 2).coerceAtLeast(1), Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xFF2A0000.toInt())
            }
        }

    fun render(context: Context, kind: WidgetKind, config: WidgetConfig, widthDp: Int, heightDp: Int, maxWidthPx: Int): Bitmap {
        val density = context.resources.displayMetrics.density
        val fit = min(1f, maxWidthPx / (widthDp * density))
        val width = (widthDp * density * fit).toInt().coerceAtLeast(1)
        val height = (heightDp * density * fit).toInt().coerceAtLeast(1)
        val padding = 12 * density * fit
        val radius = WidgetConfig.CORNER_RADII[config.corner.coerceIn(0, 3)] * density * fit
        val palette = Palettes.find(context, config.palette)
        val content = Renderer(context, config, palette, pxPerDp = density * fit).render(
            kind,
            (width - 2 * padding).toInt(),
            (height - 2 * padding).toInt(),
            LocalDateTime.now(),
            WidgetViews.deviceState(context, kind),
        )
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.bg
            alpha = config.bgAlpha * 255 / 100
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, bg)
        canvas.drawBitmap(content, padding, padding, null)
        return bitmap
    }
}
