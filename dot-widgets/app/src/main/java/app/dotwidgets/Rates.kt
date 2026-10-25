package app.dotwidgets

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

object RatesStore {
    private const val PREFS = "rates"

    fun data(context: Context): RatesData? = prefs(context).getString("data", null)?.let(RatesData::fromJson)

    fun save(context: Context, data: RatesData) {
        prefs(context).edit().putString("data", data.toJson()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

object RatesApi {
    /** Downloads every indicator (one small request each), stores them and redraws. Blocking. */
    fun refresh(context: Context): Boolean {
        val previous = RatesStore.data(context)?.series.orEmpty()
        val series = mutableMapOf<Indicator, RateSeries>()
        var networkFailed = false
        for (indicator in Indicator.entries) {
            try {
                series[indicator] = Mindicador.parseSeries(Http.get(Mindicador.seriesUrl(indicator)), indicator)
            } catch (error: java.io.IOException) {
                networkFailed = true
            } catch (error: Throwable) {
                CrashLog.record(context, "tipo de cambio (${indicator.code})", error)
            }
        }
        if (series.isEmpty()) return false
        // Keep older values for any indicator that failed this time.
        RatesStore.save(context, RatesData(System.currentTimeMillis(), previous + series))
        Scheduler.update(context, listOf(WidgetKind.RATES))
        return !networkFailed
    }
}

/** Refreshes the rates every few hours with network (values change once per business day). */
class RatesJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val ok = RatesApi.refresh(this)
            jobFinished(params, !ok)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val PERIODIC_ID = 4301
        private const val NOW_ID = 4302
        private const val PERIOD_MS = 3 * 60 * 60 * 1000L

        fun schedule(context: Context) {
            if (context.getSystemService(JobScheduler::class.java).getPendingJob(PERIODIC_ID) != null) return
            submit(
                context,
                JobInfo.Builder(PERIODIC_ID, ComponentName(context, RatesJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .build(),
            )
        }

        fun runNow(context: Context) {
            submit(
                context,
                JobInfo.Builder(NOW_ID, ComponentName(context, RatesJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build(),
            )
        }

        fun cancel(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            scheduler.cancel(PERIODIC_ID)
            scheduler.cancel(NOW_ID)
        }

        /** Keep the periodic job only while a rates widget exists; fetch at once if data is old. */
        fun sync(context: Context, manager: AppWidgetManager) {
            if (Scheduler.ids(context, manager, WidgetKind.RATES).isEmpty()) {
                cancel(context)
                return
            }
            schedule(context)
            val data = RatesStore.data(context)
            if (data == null || System.currentTimeMillis() - data.fetchedAt > PERIOD_MS) runNow(context)
        }

        private fun submit(context: Context, job: JobInfo) {
            try {
                context.getSystemService(JobScheduler::class.java).schedule(job)
            } catch (error: Exception) {
                CrashLog.record(context, "programar tipo de cambio", error)
            }
        }
    }
}

/** Tapping the widget asks for fresh values. */
class RatesRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = RatesJob.runNow(context)

    companion object {
        fun pending(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                31,
                Intent(context, RatesRefreshReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
