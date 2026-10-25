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

/**
 * The user's personal Banco Central API token. Stored only in the app's private storage: it is not
 * part of the settings export, and Android cloud backup is disabled for the app (allowBackup=false).
 */
object RatesSource {
    private const val PREFS = "rates_source"

    fun token(context: Context): String? = prefs(context).getString("token", null)?.takeIf { it.isNotBlank() }

    fun setToken(context: Context, token: String?) {
        prefs(context).edit().apply { if (token.isNullOrBlank()) remove("token") else putString("token", token.trim()) }.remove("status").apply()
    }

    /** Last result of using the token: null if not used yet, "ok", or an error description. */
    fun status(context: Context): String? = prefs(context).getString("status", null)

    fun setStatus(context: Context, status: String) {
        prefs(context).edit().putString("status", status).apply()
    }

    /** Tries the token against the real API with the dollar series; returns a message for the user. Blocking. */
    fun test(token: String): Result<RateSeries> = runCatching {
        BcchApi.parse(Http.get(BcchApi.url(token, Indicator.DOLAR, java.time.LocalDate.now())), Indicator.DOLAR)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

object RatesApi {
    /**
     * Downloads every indicator (one small request each), stores them and redraws. Blocking.
     * With a token it asks the Banco Central directly and falls back to mindicador.cl per indicator.
     */
    fun refresh(context: Context): Boolean {
        val previous = RatesStore.data(context)?.series.orEmpty()
        val series = mutableMapOf<Indicator, RateSeries>()
        var networkFailed = false
        val token = RatesSource.token(context)
        var tokenError: String? = null
        var tokenWorked = false
        for (indicator in Indicator.entries) {
            if (token != null) {
                try {
                    series[indicator] = BcchApi.parse(Http.get(BcchApi.url(token, indicator, java.time.LocalDate.now())), indicator)
                    tokenWorked = true
                    continue
                } catch (error: BcchApi.ApiError) {
                    tokenError = tokenError ?: "${indicator.label}: ${error.description}"
                } catch (error: java.io.IOException) {
                    // Fall through to mindicador.cl.
                } catch (error: Throwable) {
                    CrashLog.record(context, "Banco Central (${indicator.code})", error)
                }
            }
            try {
                series[indicator] = Mindicador.parseSeries(Http.get(Mindicador.seriesUrl(indicator)), indicator)
            } catch (error: java.io.IOException) {
                networkFailed = true
            } catch (error: Throwable) {
                CrashLog.record(context, "tipo de cambio (${indicator.code})", error)
            }
        }
        if (token != null && (tokenWorked || tokenError != null)) RatesSource.setStatus(context, tokenError ?: "ok")
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
