package app.dotwidgets

import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Chilean indicators published daily by the Banco Central (dólar observado, euro, UF) and monthly (UTM). */
enum class Indicator(
    val code: String,
    val label: String,
    val longLabel: String,
    val decimals: Int,
    /** Series code in the Banco Central's statistics database (BDE). */
    val bcchSeries: String,
    /** How far back to ask the BDE so there are enough points for the change and the sparkline. */
    val bcchDays: Long,
) {
    DOLAR("dolar", "DÓLAR", "DÓLAR OBSERVADO", 2, "F073.TCO.PRE.Z.D", 45),
    EURO("euro", "EURO", "EURO", 2, "F072.CLP.EUR.N.O.D", 45),
    UF("uf", "UF", "UNIDAD DE FOMENTO", 2, "F073.UFF.PRE.Z.D", 30),
    UTM("utm", "UTM", "UNIDAD TRIBUTARIA", 0, "F073.UTR.PRE.Z.M", 400);

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

/**
 * The Banco Central de Chile's own REST API (BDE, "SieteRestWS"), used when the user enters their
 * personal API token. Plain JVM code; CI tests it live when the BCCH_TOKEN secret is configured.
 */
object BcchApi {
    const val URL = "https://si3.bcentral.cl/SieteRestWS/SieteRestWS.ashx"
    private val DAY: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")

    /** The API answers HTTP 200 even on errors; [code] and [description] come from its body. */
    class ApiError(val code: Int, val description: String) : Exception("Banco Central $code: $description")

    fun url(token: String, indicator: Indicator, today: LocalDate): String {
        val first = today.minusDays(indicator.bcchDays)
        return URL +
            "?token=" + java.net.URLEncoder.encode(token.trim(), "UTF-8") +
            "&firstdate=" + first + "&lastdate=" + today +
            "&timeseries=" + indicator.bcchSeries +
            "&function=GetSeries"
    }

    /**
     * `{"Codigo": 0, "Descripcion": "Success", "Series": {"seriesId": "...", "Obs": [
     *   {"indexDateString": "23-10-2026", "value": "986.58", "statusCode": "OK"}, ...]}}`.
     * "Series" may also come as a one-element array; days without data have statusCode "ND".
     */
    fun parse(json: String, indicator: Indicator): RateSeries {
        val root = JSONObject(json)
        val code = root.optInt("Codigo", -1)
        if (code != 0) throw ApiError(code, root.optString("Descripcion", "Sin descripción"))
        val series = root.optJSONObject("Series") ?: root.optJSONArray("Series")?.optJSONObject(0)
            ?: throw ApiError(code, "Respuesta sin serie")
        val obs = series.optJSONArray("Obs") ?: JSONArray()
        val points = (0 until obs.length()).mapNotNull { i ->
            val o = obs.getJSONObject(i)
            if (!o.optString("statusCode", "OK").equals("OK", ignoreCase = true)) return@mapNotNull null
            val value = o.optString("value").replace(",", ".").toDoubleOrNull() ?: return@mapNotNull null
            if (value.isNaN()) return@mapNotNull null
            RateSeries.Point(LocalDate.parse(o.getString("indexDateString"), DAY), value)
        }.sortedByDescending { it.date }.distinctBy { it.date }.take(30)
        if (points.isEmpty()) throw ApiError(code, "Sin valores para ${indicator.bcchSeries}")
        return RateSeries(indicator, points)
    }
}
