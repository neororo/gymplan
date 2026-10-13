package app.dotwidgets

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/** A forecast snapshot. Temperatures are always stored in °C and converted when drawn. */
data class WeatherData(
    val place: String,
    val fetchedAt: Long,
    val now: Now,
    val hours: List<Hour>,
    val days: List<Day>,
) {
    data class Now(val temp: Double, val code: Int, val isDay: Boolean)
    data class Hour(val time: LocalDateTime, val temp: Double, val code: Int, val precipitation: Int, val isDay: Boolean)
    data class Day(val date: LocalDate, val code: Int, val max: Double, val min: Double)

    fun toJson(): String {
        val hourArray = JSONArray()
        hours.forEach {
            hourArray.put(
                JSONObject().put("time", it.time.toString()).put("temp", it.temp).put("code", it.code)
                    .put("precipitation", it.precipitation).put("isDay", it.isDay),
            )
        }
        val dayArray = JSONArray()
        days.forEach {
            dayArray.put(JSONObject().put("date", it.date.toString()).put("code", it.code).put("max", it.max).put("min", it.min))
        }
        return JSONObject()
            .put("place", place)
            .put("fetchedAt", fetchedAt)
            .put("now", JSONObject().put("temp", now.temp).put("code", now.code).put("isDay", now.isDay))
            .put("hours", hourArray)
            .put("days", dayArray)
            .toString()
    }

    companion object {
        fun fromJson(json: String): WeatherData? = runCatching {
            val o = JSONObject(json)
            val now = o.getJSONObject("now")
            val hours = o.getJSONArray("hours")
            val days = o.getJSONArray("days")
            WeatherData(
                place = o.getString("place"),
                fetchedAt = o.getLong("fetchedAt"),
                now = Now(now.getDouble("temp"), now.getInt("code"), now.getBoolean("isDay")),
                hours = (0 until hours.length()).map { i ->
                    val h = hours.getJSONObject(i)
                    Hour(LocalDateTime.parse(h.getString("time")), h.getDouble("temp"), h.getInt("code"), h.getInt("precipitation"), h.getBoolean("isDay"))
                },
                days = (0 until days.length()).map { i ->
                    val d = days.getJSONObject(i)
                    Day(LocalDate.parse(d.getString("date")), d.getInt("code"), d.getDouble("max"), d.getDouble("min"))
                },
            )
        }.getOrNull()
    }
}

/** Reads Open-Meteo responses. Plain JVM code (org.json + java.time) so CI can test it against the live API. */
object OpenMeteo {
    const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
    const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"

    fun forecastQuery(latitude: Double, longitude: Double): String =
        "latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,weather_code,is_day" +
            "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&forecast_days=6&forecast_hours=7"

    fun parseForecast(json: String, place: String, fetchedAt: Long): WeatherData {
        val root = JSONObject(json)
        val current = root.getJSONObject("current")
        val hourly = root.getJSONObject("hourly")
        val daily = root.getJSONObject("daily")

        val hourTimes = hourly.getJSONArray("time")
        val hourTemps = hourly.getJSONArray("temperature_2m")
        val hourCodes = hourly.getJSONArray("weather_code")
        val hourRain = hourly.getJSONArray("precipitation_probability")
        val hourDay = hourly.getJSONArray("is_day")
        val hours = (0 until hourTimes.length()).mapNotNull { i ->
            if (hourTemps.isNull(i) || hourCodes.isNull(i)) return@mapNotNull null
            WeatherData.Hour(
                time = LocalDateTime.parse(hourTimes.getString(i)),
                temp = hourTemps.getDouble(i),
                code = hourCodes.getInt(i),
                precipitation = if (hourRain.isNull(i)) 0 else hourRain.getInt(i),
                isDay = hourDay.optInt(i, 1) == 1,
            )
        }

        val dayDates = daily.getJSONArray("time")
        val dayCodes = daily.getJSONArray("weather_code")
        val dayMax = daily.getJSONArray("temperature_2m_max")
        val dayMin = daily.getJSONArray("temperature_2m_min")
        val days = (0 until dayDates.length()).mapNotNull { i ->
            if (dayMax.isNull(i) || dayMin.isNull(i) || dayCodes.isNull(i)) return@mapNotNull null
            WeatherData.Day(LocalDate.parse(dayDates.getString(i)), dayCodes.getInt(i), dayMax.getDouble(i), dayMin.getDouble(i))
        }

        return WeatherData(
            place = place,
            fetchedAt = fetchedAt,
            now = WeatherData.Now(
                temp = current.getDouble("temperature_2m"),
                code = current.getInt("weather_code"),
                isDay = current.optInt("is_day", 1) == 1,
            ),
            hours = hours,
            days = days,
        )
    }

    data class Place(val name: String, val detail: String, val latitude: Double, val longitude: Double)

    fun parseGeocoding(json: String): List<Place> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            Place(
                name = r.getString("name"),
                detail = listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
                latitude = r.getDouble("latitude"),
                longitude = r.getDouble("longitude"),
            )
        }
    }
}

/** WMO weather codes (as used by Open-Meteo) grouped into the conditions the widget can draw. */
enum class Condition(val label: String) {
    CLEAR("Despejado"),
    PARTLY("Parcial"),
    CLOUDY("Nublado"),
    FOG("Niebla"),
    DRIZZLE("Llovizna"),
    RAIN("Lluvia"),
    SNOW("Nieve"),
    STORM("Tormenta");

    companion object {
        fun of(code: Int): Condition = when (code) {
            0 -> CLEAR
            1, 2 -> PARTLY
            3 -> CLOUDY
            45, 48 -> FOG
            in 51..57 -> DRIZZLE
            in 61..67, in 80..82 -> RAIN
            in 71..77, 85, 86 -> SNOW
            in 95..99 -> STORM
            else -> CLOUDY
        }
    }
}
