package app.dotwidgets

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Installs the crash recorder as early as possible, before any widget or receiver runs. */
class DotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

/**
 * Keeps the last few errors (crashes and failures the app recovered from) in a small file, so they
 * can be shown in the app and copied. The device can't be debugged from here otherwise.
 */
object CrashLog {
    private const val FILE = "errors.log"
    private const val MAX_CHARS = 12_000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(app, "CIERRE en hilo ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun record(context: Context, where: String, error: Throwable) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = "[$time] v$version · $where\n${stack.lines().take(25).joinToString("\n")}\n\n"
        val file = file(context)
        val old = if (file.exists()) file.readText() else ""
        file.writeText((entry + old).take(MAX_CHARS))
    }

    fun read(context: Context): String = file(context).takeIf { it.exists() }?.readText().orEmpty()

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)
}
