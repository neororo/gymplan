package app.dotwidgets

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Small GET client shared by the online widgets (weather, exchange rates). */
object Http {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 4_000L

    /**
     * GET with a few quick retries for network errors (timeouts, dropped connections, 5xx, 429).
     * Network trouble surfaces as [IOException]; any other HTTP status as [IllegalStateException].
     */
    fun get(url: String): String {
        var lastError: IOException? = null
        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) Thread.sleep(RETRY_DELAY_MS * attempt)
            try {
                return getOnce(url)
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw lastError ?: IOException("Sin respuesta")
    }

    private fun getOnce(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json")
        try {
            val code = connection.responseCode
            if (code in 500..599 || code == 429) throw IOException("HTTP $code")
            if (code != 200) throw IllegalStateException("HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
