package app.dotwidgets

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.CalendarContract
import java.time.LocalDate

/**
 * Today's steps from the hardware step counter. The sensor counts steps since boot, so the app keeps
 * a daily baseline; readings are taken briefly on each refresh (no service running in the background).
 */
object StepCounter {
    private const val PREFS = "steps"
    private const val TIMEOUT_MS = 4_000L

    enum class Status { OK, NO_PERMISSION, NO_SENSOR }

    fun status(context: Context): Status = when {
        sensor(context) == null -> Status.NO_SENSOR
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED -> Status.NO_PERMISSION
        else -> Status.OK
    }

    /** Steps counted today, or 0 when there is no reading for today yet. */
    fun today(context: Context): Int {
        val p = prefs(context)
        if (p.getLong("day", -1) != LocalDate.now().toEpochDay()) return 0
        return (p.getFloat("last", 0f) - p.getFloat("baseline", 0f)).toInt().coerceAtLeast(0)
    }

    /** Takes one reading (asynchronously) and calls [onDone] on the main thread either way. */
    fun read(context: Context, onDone: () -> Unit) {
        val app = context.applicationContext
        val sensor = sensor(app)
        if (sensor == null || status(app) != Status.OK) {
            onDone()
            return
        }
        val manager = app.getSystemService(SensorManager::class.java)
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        lateinit var listener: SensorEventListener
        val finish = Runnable {
            if (!finished) {
                finished = true
                manager.unregisterListener(listener)
                onDone()
            }
        }
        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                record(app, event.values[0])
                handler.post(finish)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
        handler.postDelayed(finish, TIMEOUT_MS)
    }

    private fun record(context: Context, counter: Float) {
        val p = prefs(context)
        val today = LocalDate.now().toEpochDay()
        val now = System.currentTimeMillis()
        val day = p.getLong("day", -1)
        val last = p.getFloat("last", counter)
        var baseline = p.getFloat("baseline", counter)
        if (counter < last) {
            // The counter restarts at boot: keep the steps already counted today.
            baseline = counter - (last - baseline)
        }
        if (day != today) {
            // New day: if the previous reading was recent, steps since then belong to today.
            val recent = day == today - 1 && now - p.getLong("time", 0) < 2 * 60 * 60 * 1000L && counter >= last
            baseline = if (recent) last else counter
        }
        p.edit().putLong("day", today).putFloat("baseline", baseline).putFloat("last", counter).putLong("time", now).apply()
    }

    private fun sensor(context: Context): Sensor? =
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

data class CalendarEvent(
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String,
    val eventId: Long,
)

object CalendarEvents {
    private const val DAYS_AHEAD = 7L

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Upcoming (or ongoing) events from visible calendars over the next week; null without permission. */
    fun upcoming(context: Context, limit: Int = 5): List<CalendarEvent>? {
        if (!hasPermission(context)) return null
        val now = System.currentTimeMillis()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, now - 24 * 60 * 60 * 1000L)
        ContentUris.appendId(builder, now + DAYS_AHEAD * 24 * 60 * 60 * 1000L)
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val events = mutableListOf<CalendarEvent>()
        val cursor = try {
            context.contentResolver.query(
                builder.build(),
                projection,
                "${CalendarContract.Instances.VISIBLE} = 1 AND ${CalendarContract.Instances.END} > ?",
                arrayOf(now.toString()),
                "${CalendarContract.Instances.BEGIN} ASC",
            )
        } catch (e: SecurityException) {
            return null
        } ?: return emptyList()
        cursor.use {
            while (it.moveToNext() && events.size < limit) {
                if (it.getInt(6) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                events += CalendarEvent(
                    title = it.getString(0)?.takeIf(String::isNotBlank) ?: "(Sin título)",
                    begin = it.getLong(1),
                    end = it.getLong(2),
                    allDay = it.getInt(3) == 1,
                    location = it.getString(4).orEmpty(),
                    eventId = it.getLong(5),
                )
            }
        }
        return events
    }
}

object Storage {
    /** Used and total bytes of the internal storage. */
    fun usage(): Pair<Long, Long> {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.totalBytes
        return (total - stat.availableBytes) to total
    }
}
