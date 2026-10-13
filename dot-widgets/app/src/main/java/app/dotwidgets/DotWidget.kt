package app.dotwidgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.RemoteViews
import java.time.LocalDate
import kotlin.math.min
import kotlin.math.sqrt

/** Common plumbing for every widget: build RemoteViews per instance and keep the refresh alarm alive. */
abstract class DotWidget : AppWidgetProvider() {

    abstract fun build(context: Context, manager: AppWidgetManager, id: Int): RemoteViews

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, build(context, manager, it)) }
        WidgetUpdater.schedule(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        newOptions: Bundle,
    ) {
        manager.updateAppWidget(id, build(context, manager, id))
    }

    override fun onDisabled(context: Context) {
        WidgetUpdater.cancelIfUnused(context)
    }

    fun updateAll(context: Context, manager: AppWidgetManager) {
        val ids = manager.getAppWidgetIds(ComponentName(context, javaClass))
        ids.forEach { manager.updateAppWidget(it, build(context, manager, it)) }
    }

    fun hasInstances(context: Context, manager: AppWidgetManager): Boolean =
        manager.getAppWidgetIds(ComponentName(context, javaClass)).isNotEmpty()

    protected fun activityIntent(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** A widget whose content is a bitmap drawn by [DotRenderer] on top of the shared rounded background. */
abstract class BitmapWidget(private val defaultWidthDp: Int, private val defaultHeightDp: Int) : DotWidget() {

    abstract fun render(context: Context, renderer: DotRenderer, width: Int, height: Int, today: LocalDate): Bitmap

    abstract fun clickIntent(context: Context): PendingIntent

    override fun build(context: Context, manager: AppWidgetManager, id: Int): RemoteViews {
        val palette = Palette.current(context)
        val (width, height) = bitmapSize(context, manager.getAppWidgetOptions(id))
        val bitmap = render(context, DotRenderer(context, palette), width, height, LocalDate.now())
        return RemoteViews(context.packageName, R.layout.widget_image).apply {
            setInt(R.id.bg, "setColorFilter", palette.bg)
            setImageViewBitmap(R.id.content, bitmap)
            setOnClickPendingIntent(R.id.root, clickIntent(context))
        }
    }

    private fun bitmapSize(context: Context, options: Bundle): Pair<Int, Int> {
        // Portrait size: min width x max height. Subtract the 12dp padding on each side.
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: defaultWidthDp
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: defaultHeightDp
        val density = context.resources.displayMetrics.density
        val width = (widthDp - 24).coerceAtLeast(40) * density
        val height = (heightDp - 24).coerceAtLeast(40) * density
        // RemoteViews travel over binder (~1 MB limit), so keep the bitmap under ~640 KB.
        val scale = min(1f, sqrt(MAX_PIXELS / (width * height)))
        return (width * scale).toInt() to (height * scale).toInt()
    }

    private companion object {
        const val MAX_PIXELS = 160_000f
    }
}
