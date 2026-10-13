package app.dotwidgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.widget.RemoteViews
import java.time.LocalDate

class ClockWidget : DotWidget() {
    override fun build(context: Context, manager: AppWidgetManager, id: Int): RemoteViews {
        val palette = Palette.current(context)
        return RemoteViews(context.packageName, R.layout.widget_clock).apply {
            setInt(R.id.bg, "setColorFilter", palette.bg)
            setTextColor(R.id.time, palette.fg)
            setTextColor(R.id.date, palette.accent)
            setOnClickPendingIntent(
                R.id.root,
                activityIntent(context, 1, Intent(AlarmClock.ACTION_SHOW_ALARMS)),
            )
        }
    }
}

class BatteryWidget : BitmapWidget(110, 110) {
    override fun render(context: Context, renderer: DotRenderer, width: Int, height: Int, today: LocalDate): Bitmap {
        val battery = context.getSystemService(BatteryManager::class.java)
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return renderer.battery(width, height, level, battery.isCharging)
    }

    // Tapping refreshes every widget so the level is current right away.
    override fun clickIntent(context: Context): PendingIntent = WidgetUpdater.tickIntent(context)
}

class CalendarWidget : BitmapWidget(180, 180) {
    override fun render(context: Context, renderer: DotRenderer, width: Int, height: Int, today: LocalDate): Bitmap =
        renderer.calendar(width, height, today)

    override fun clickIntent(context: Context): PendingIntent {
        val uri = CalendarContract.CONTENT_URI.buildUpon()
            .appendPath("time")
            .appendPath(System.currentTimeMillis().toString())
            .build()
        return activityIntent(context, 3, Intent(Intent.ACTION_VIEW, uri))
    }
}

class YearWidget : BitmapWidget(250, 110) {
    override fun render(context: Context, renderer: DotRenderer, width: Int, height: Int, today: LocalDate): Bitmap =
        renderer.year(width, height, today)

    override fun clickIntent(context: Context): PendingIntent =
        activityIntent(context, 4, Intent(context, MainActivity::class.java))
}
