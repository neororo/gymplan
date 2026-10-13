package app.dotwidgets

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.dotwidgets.Ui.dp
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** The chosen place (shared by every weather widget) and the last forecast. */
object WeatherStore {
    private const val PREFS = "weather"

    data class Place(val name: String, val latitude: Double, val longitude: Double)

    fun place(context: Context): Place? {
        val p = prefs(context)
        val name = p.getString("name", null) ?: return null
        return Place(name, p.getFloat("lat", 0f).toDouble(), p.getFloat("lon", 0f).toDouble())
    }

    fun setPlace(context: Context, place: Place) {
        prefs(context).edit()
            .putString("name", place.name)
            .putFloat("lat", place.latitude.toFloat())
            .putFloat("lon", place.longitude.toFloat())
            .remove("data")
            .apply()
    }

    fun data(context: Context): WeatherData? = prefs(context).getString("data", null)?.let(WeatherData::fromJson)

    fun saveData(context: Context, data: WeatherData) {
        prefs(context).edit().putString("data", data.toJson()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

object WeatherApi {
    private const val TIMEOUT_MS = 10_000

    /** Downloads and stores a fresh forecast, then redraws the weather widgets. Blocking. */
    fun refresh(context: Context): Boolean {
        val place = WeatherStore.place(context) ?: return true
        return try {
            val json = get("${OpenMeteo.FORECAST_URL}?${OpenMeteo.forecastQuery(place.latitude, place.longitude)}")
            WeatherStore.saveData(context, OpenMeteo.parseForecast(json, place.name, System.currentTimeMillis()))
            Scheduler.update(context, listOf(WidgetKind.WEATHER))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun search(query: String): List<OpenMeteo.Place> {
        val name = URLEncoder.encode(query.trim(), "UTF-8")
        val language = Locale.getDefault().language
        return OpenMeteo.parseGeocoding(get("${OpenMeteo.GEOCODING_URL}?name=$name&count=8&language=$language&format=json"))
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        try {
            if (connection.responseCode != 200) throw IllegalStateException("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Background refresh through JobScheduler: every ~30 minutes when there is a network,
 * batched by the system to save battery, plus one-off runs on demand.
 */
class WeatherJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val ok = WeatherApi.refresh(this)
            jobFinished(params, !ok)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val PERIODIC_ID = 4201
        private const val NOW_ID = 4202
        private const val PERIOD_MS = 30 * 60 * 1000L

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(PERIODIC_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(PERIODIC_ID, ComponentName(context, WeatherJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .build(),
            )
        }

        fun runNow(context: Context) {
            context.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(NOW_ID, ComponentName(context, WeatherJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build(),
            )
        }

        fun cancel(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            scheduler.cancel(PERIODIC_ID)
            scheduler.cancel(NOW_ID)
        }

        /** Refresh on first placement, or when the stored forecast is older than one period. */
        fun refreshIfStale(context: Context) {
            val data = WeatherStore.data(context)
            if (WeatherStore.place(context) != null && (data == null || System.currentTimeMillis() - data.fetchedAt > PERIOD_MS)) {
                runNow(context)
            }
        }
    }
}

/** Tapping a weather widget asks for a fresh forecast. */
class WeatherRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = WeatherJob.runNow(context)

    companion object {
        fun pending(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                30,
                Intent(context, WeatherRefreshReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}

/** Location controls shared by the main screen and the weather widget editor. */
object WeatherUi {
    const val LOCATION_REQUEST = 71

    fun section(activity: Activity, onChanged: () -> Unit): LinearLayout {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val label = Ui.body(activity, "", Ui.WHITE)
        fun showPlace() {
            val place = WeatherStore.place(activity)
            label.text = if (place == null) activity.getString(R.string.weather_no_place) else activity.getString(R.string.weather_place, place.name)
        }
        showPlace()
        val changed = {
            showPlace()
            onChanged()
        }
        listener = changed
        box.addView(label)
        box.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(activity, R.string.weather_search) { searchCity(activity, changed) })
            addView(Button(activity).apply {
                setText(R.string.weather_my_location)
                setTextColor(Ui.WHITE)
                setBackgroundColor(0)
                setOnClickListener { useMyLocation(activity) }
            })
        })
        box.addView(TextView(activity).apply {
            setText(R.string.weather_attribution)
            textSize = 11f
            setTextColor(Ui.MUTED)
        })
        return box
    }

    /** Called back after a place is set from a permission result; the latest section listens. */
    private var listener: (() -> Unit)? = null

    private fun button(activity: Activity, text: Int, onClick: () -> Unit) = Button(activity).apply {
        setText(text)
        setTextColor(Ui.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(Ui.RED)
        setOnClickListener { onClick() }
    }

    private fun searchCity(activity: Activity, onDone: () -> Unit) {
        val input = EditText(activity).apply {
            setHint(R.string.weather_search_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        val frame = LinearLayout(activity).apply {
            setPadding(activity.dp(20), activity.dp(8), activity.dp(20), 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.weather_search)
            .setView(frame)
            .setPositiveButton(R.string.weather_search) { _, _ ->
                val query = input.text.toString()
                if (query.isBlank()) return@setPositiveButton
                Thread {
                    val results = runCatching { WeatherApi.search(query) }
                    activity.runOnUiThread {
                        val places = results.getOrNull()
                        when {
                            places == null -> toast(activity, R.string.weather_offline)
                            places.isEmpty() -> toast(activity, R.string.weather_not_found)
                            else -> pickPlace(activity, places, onDone)
                        }
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickPlace(activity: Activity, places: List<OpenMeteo.Place>, onDone: () -> Unit) {
        val labels = places.map { if (it.detail.isBlank()) it.name else "${it.name} · ${it.detail}" }.toTypedArray()
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.weather_pick)
            .setItems(labels) { _, which ->
                val place = places[which]
                setPlace(activity, WeatherStore.Place(place.name, place.latitude, place.longitude))
                onDone()
            }
            .show()
    }

    private fun useMyLocation(activity: Activity) {
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locate(activity)
        } else {
            activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_REQUEST)
        }
    }

    /** Forward from Activity.onRequestPermissionsResult. */
    fun onPermissionResult(activity: Activity, requestCode: Int, grantResults: IntArray) {
        if (requestCode != LOCATION_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            locate(activity)
        } else {
            toast(activity, R.string.weather_location_denied)
        }
    }

    /** One-off, foreground-only location: a recent last-known fix, or a single fresh one on Android 11+. */
    @SuppressLint("MissingPermission")
    private fun locate(activity: Activity) {
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val manager = activity.getSystemService(LocationManager::class.java)
        val last = manager.getProviders(true)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        val recent = last?.takeIf { System.currentTimeMillis() - it.time < 60 * 60 * 1000L }
        if (recent != null) {
            placeFromLocation(activity, recent)
            return
        }
        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && provider != null) {
            toast(activity, R.string.weather_locating)
            manager.getCurrentLocation(provider, null, activity.mainExecutor) { location ->
                val fix = location ?: last
                if (fix != null) placeFromLocation(activity, fix) else toast(activity, R.string.weather_location_failed)
            }
        } else if (last != null) {
            placeFromLocation(activity, last)
        } else {
            toast(activity, R.string.weather_location_failed)
        }
    }

    private fun placeFromLocation(activity: Activity, location: Location) {
        Thread {
            val name = runCatching {
                @Suppress("DEPRECATION")
                Geocoder(activity, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
            }.getOrNull() ?: activity.getString(R.string.weather_my_location)
            activity.runOnUiThread {
                setPlace(activity, WeatherStore.Place(name, location.latitude, location.longitude))
                listener?.invoke()
            }
        }.start()
    }

    private fun setPlace(context: Context, place: WeatherStore.Place) {
        WeatherStore.setPlace(context, place)
        Scheduler.update(context, listOf(WidgetKind.WEATHER))
        Scheduler.refresh(context)
        // Fetch right away rather than waiting for the job.
        Thread { WeatherApi.refresh(context.applicationContext) }.start()
        toast(context, R.string.weather_place_set)
    }

    private fun toast(context: Context, text: Int) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
