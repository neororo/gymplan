package app.dotwidgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * Two alarms keep the bitmap widgets current:
 * - an exact, non-waking alarm at every minute boundary while a clock or battery widget exists
 *   (it never wakes the phone; a tick missed while asleep fires as soon as the screen turns on);
 * - an inexact 15-minute alarm for everything else (dates, progress, countdowns).
 */
object Scheduler {
    private const val ACTION_MINUTE = "app.dotwidgets.action.MINUTE"
    private const val ACTION_PERIODIC = "app.dotwidgets.action.PERIODIC"
    private const val ACTION_REFRESH = "app.dotwidgets.action.REFRESH"

    private const val MINUTE_REQUEST = 10
    private const val PERIODIC_REQUEST = 11
    private const val REFRESH_REQUEST = 12

    // Music is here only to advance its progress bar; track changes arrive instantly via MediaListenerService.
    private val MINUTE_KINDS = listOf(WidgetKind.CLOCK, WidgetKind.BATTERY, WidgetKind.MUSIC)

    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (MINUTE_KINDS.any { ids(context, manager, it).isNotEmpty() }) {
            scheduleMinute(context)
        } else {
            alarms.cancel(pending(context, ACTION_MINUTE, MINUTE_REQUEST))
        }
        if (WidgetKind.entries.any { ids(context, manager, it).isNotEmpty() }) {
            schedulePeriodic(context)
        } else {
            val periodic = pending(context, ACTION_PERIODIC, PERIODIC_REQUEST)
            alarms.cancel(periodic)
            periodic.cancel()
        }
    }

    fun canTickExactly(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun update(context: Context, kinds: Collection<WidgetKind> = WidgetKind.entries) {
        val manager = AppWidgetManager.getInstance(context)
        for (kind in kinds) {
            for (id in ids(context, manager, kind)) {
                manager.updateAppWidget(id, WidgetViews.build(context, manager, id, kind))
            }
        }
    }

    fun ids(context: Context, manager: AppWidgetManager, kind: WidgetKind): IntArray =
        manager.getAppWidgetIds(ComponentName(context, kind.provider))

    fun refreshIntent(context: Context): PendingIntent = pending(context, ACTION_REFRESH, REFRESH_REQUEST)

    internal fun onTick(context: Context, action: String?) {
        when (action) {
            ACTION_MINUTE -> {
                update(context, MINUTE_KINDS)
                scheduleMinute(context)
            }
            ACTION_PERIODIC, ACTION_REFRESH -> update(context)
            // Time, time zone, locale change, boot or app update: redraw and re-arm everything.
            else -> {
                update(context)
                refresh(context)
            }
        }
    }

    private fun scheduleMinute(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val next = (System.currentTimeMillis() / 60_000L + 1) * 60_000L
        val intent = pending(context, ACTION_MINUTE, MINUTE_REQUEST)
        try {
            if (canTickExactly(context)) {
                alarms.setExact(AlarmManager.RTC, next, intent)
            } else {
                alarms.setWindow(AlarmManager.RTC, next, 60_000L, intent)
            }
        } catch (e: SecurityException) {
            alarms.setWindow(AlarmManager.RTC, next, 60_000L, intent)
        }
    }

    private fun schedulePeriodic(context: Context) {
        val existing = PendingIntent.getBroadcast(
            context,
            PERIODIC_REQUEST,
            Intent(context, TickReceiver::class.java).setAction(ACTION_PERIODIC),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )
        if (existing != null) return
        context.getSystemService(AlarmManager::class.java).setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            pending(context, ACTION_PERIODIC, PERIODIC_REQUEST),
        )
    }

    private fun pending(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, TickReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** The app's own alarms and the refresh tap. Not exported. */
class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Scheduler.onTick(context, intent.action)
}

/** System broadcasts: boot, app update, time and time zone changes, locale change, exact-alarm permission. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Scheduler.onTick(context, intent.action)
}
