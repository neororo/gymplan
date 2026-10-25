package app.dotwidgets

import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Chilean indicators published daily by the Banco Central (dólar observado, euro, UF) and monthly (UTM). */
enum class Indicator(val code: String, val label: String, val longLabel: String, val decimals: Int) {
    DOLAR("dolar", "DÓLAR", "DÓLAR OBSERVADO", 2),
    EURO("euro", "EURO", "EURO", 2),
    UF("uf", "UF", "UNIDAD DE FOMENTO", 2),
    UTM("utm", "UTM", "UNIDAD TRIBUTARIA", 0);

    /** Chilean format: "$945,32", "$39.485,67", "$68.785". */
    fun format(value: Double): String {
        val format = NumberFormat.getNumberInstance(CHILE).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }
        return "$" + format.format(value)
    }

    fun formatChange(delta: Double): String {
        val format = NumberFormat.getNumberInstance(CHILE).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }
        return format.format(kotlin.math.abs(delta))
    }

    companion object {
        val CHILE: Locale = Locale.forLanguageTag("es-CL")

        fun of(code: String): Indicator = entries.firstOrNull { it.code == code } ?: DOLAR
    }
}

/** One indicator's recent values, newest first. */
data class RateSeries(val indicator: Indicator, val points: List<Point>) {
    data class Point(val date: LocalDate, val value: Double)

    val latest: Point? get() = points.firstOrNull()
    val previous: Point? get() = points.getOrNull(1)

    /** Change from the previous published value, or null if there is only one. */
    val change: Double? get() = latest?.let { l -> previous?.let { l.value - it.value } }
}

data class RatesData(val fetchedAt: Long, val series: Map<Indicator, RateSeries>) {

    fun toJson(): String {
        val root = JSONObject().put("fetchedAt", fetchedAt)
        val all = JSONObject()
        series.forEach { (indicator, s) ->
            val points = JSONArray()
            s.points.forEach { points.put(JSONObject().put("date", it.date.toString()).put("value", it.value)) }
            all.put(indicator.code, points)
        }
        return root.put("series", all).toString()
    }

    companion object {
        fun fromJson(json: String): RatesData? = runCatching {
            val root = JSONObject(json)
            val all = root.getJSONObject("series")
            val series = Indicator.entries.mapNotNull { indicator ->
                val points = all.optJSONArray(indicator.code) ?: return@mapNotNull null
                indicator to RateSeries(indicator, (0 until points.length()).map { i ->
                    val p = points.getJSONObject(i)
                    RateSeries.Point(LocalDate.parse(p.getString("date")), p.getDouble("value"))
                })
            }.toMap()
            RatesData(root.getLong("fetchedAt"), series)
        }.getOrNull()
    }
}

/**
 * Reads mindicador.cl, which republishes the Banco Central de Chile's daily indicators without an
 * API key. Plain JVM code (org.json + java.time) so CI can test it against the live service.
 */
object Mindicador {
    const val BASE_URL = "https://mindicador.cl/api"
    private val CHILE_ZONE: ZoneId = ZoneId.of("America/Santiago")
    private const val MAX_POINTS = 30

    fun seriesUrl(indicator: Indicator) = "$BASE_URL/${indicator.code}"

    /** Parses `/api/{code}`: `{"codigo": "...", "serie": [{"fecha": ISO instant, "valor": number}, ...]}`. */
    fun parseSeries(json: String, indicator: Indicator): RateSeries {
        val serie = JSONObject(json).getJSONArray("serie")
        val points = (0 until serie.length()).mapNotNull { i ->
            val item = serie.getJSONObject(i)
            if (item.isNull("valor")) return@mapNotNull null
            RateSeries.Point(dateOf(item.getString("fecha")), item.getDouble("valor"))
        }
            // Newest first, one value per day.
            .sortedByDescending { it.date }
            .distinctBy { it.date }
            .take(MAX_POINTS)
        require(points.isNotEmpty()) { "Serie vacía para ${indicator.code}" }
        return RateSeries(indicator, points)
    }

    /** "2026-10-24T03:00:00.000Z" is midnight in Chile, so the value belongs to 24 October. */
    private fun dateOf(fecha: String): LocalDate =
        runCatching { Instant.parse(fecha).atZone(CHILE_ZONE).toLocalDate() }
            .getOrElse { LocalDate.parse(fecha.take(10)) }
}
