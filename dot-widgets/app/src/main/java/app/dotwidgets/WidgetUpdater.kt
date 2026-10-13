package app.dotwidgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

object WidgetUpdater {

    private const val ALARM_REQUEST = 0
    private const val TAP_REQUEST = 1

    private fun widgets(): List<DotWidget> =
        listOf(ClockWidget(), BatteryWidget(), CalendarWidget(), YearWidget())

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        widgets().forEach { it.updateAll(context, manager) }
    }

    /** Inexact 15-minute alarm: cheap on battery, and enough for battery level and date changes. */
    fun schedule(context: Context) {
        val existing = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST,
            Intent(context, TickReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )
        if (existing != null) return
        context.getSystemService(AlarmManager::class.java).setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            tickIntent(context, ALARM_REQUEST),
        )
    }

    fun cancelIfUnused(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        if (widgets().any { it.hasInstances(context, manager) }) return
        val intent = tickIntent(context, ALARM_REQUEST)
        context.getSystemService(AlarmManager::class.java).cancel(intent)
        intent.cancel()
    }

    /** Same receiver for both, but distinct request codes so a tap target never looks like a scheduled alarm. */
    fun tickIntent(context: Context, requestCode: Int = TAP_REQUEST): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, TickReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WidgetUpdater.updateAll(context)
    }
}
