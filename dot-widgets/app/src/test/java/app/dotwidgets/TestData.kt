package app.dotwidgets

import java.net.HttpURLConnection
import java.net.URL

object TestData {
    val forecastJson = """
        {"latitude":-33.44,"longitude":-70.65,"timezone":"America/Santiago",
         "current":{"time":"2026-10-13T11:15","interval":900,"temperature_2m":17.6,"weather_code":2,"is_day":1},
         "hourly":{"time":["2026-10-13T11:00","2026-10-13T12:00","2026-10-13T13:00"],
                   "temperature_2m":[17.1,18.9,null],"weather_code":[2,1,0],
                   "precipitation_probability":[0,null,35],"is_day":[1,1,1]},
         "daily":{"time":["2026-10-13","2026-10-14"],"weather_code":[2,61],
                  "temperature_2m_max":[22.4,19.0],"temperature_2m_min":[8.6,10.2]}}
    """.trimIndent()

    val live: Boolean get() = System.getenv("LIVE_API") == "1"

    fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        check(connection.responseCode == 200) { "HTTP ${connection.responseCode} for $url" }
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    /**
     * Real forecast in CI (LIVE_API=1), otherwise the canned sample. Rendering tests only need
     * realistic data, so a network hiccup falls back to the sample instead of failing them;
     * the API contract itself is checked by OpenMeteoTest.
     */
    fun forecast(place: String = "Santiago de Chile"): WeatherData {
        val json = if (live) {
            runCatching { get("${OpenMeteo.FORECAST_URL}?${OpenMeteo.forecastQuery(-33.45, -70.66)}") }
                .onFailure { println("Live forecast unavailable, using sample: $it") }
                .getOrDefault(forecastJson)
        } else {
            forecastJson
        }
        return OpenMeteo.parseForecast(json, place, System.currentTimeMillis())
    }
}
