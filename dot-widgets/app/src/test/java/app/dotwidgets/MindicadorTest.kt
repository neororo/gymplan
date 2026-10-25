package app.dotwidgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate

class MindicadorTest {

    private val sample = """
        {"version":"1.7.0","autor":"mindicador.cl","codigo":"dolar","nombre":"Dólar observado","unidad_medida":"Pesos",
         "serie":[{"fecha":"2026-10-23T03:00:00.000Z","valor":945.32},
                  {"fecha":"2026-10-22T03:00:00.000Z","valor":943.17},
                  {"fecha":"2026-10-21T03:00:00.000Z","valor":null},
                  {"fecha":"2026-10-20T03:00:00.000Z","valor":940.05}]}
    """.trimIndent()

    @Test
    fun parsesSeriesNewestFirstInChileanDates() {
        val series = Mindicador.parseSeries(sample, Indicator.DOLAR)
        assertEquals(LocalDate.of(2026, 10, 23), series.latest?.date)
        assertEquals(945.32, series.latest!!.value, 0.001)
        assertEquals("Null values are skipped", 3, series.points.size)
        assertEquals(2.15, series.change!!, 0.001)
    }

    @Test
    fun formatsLikeChile() {
        assertEquals("$945,32", Indicator.DOLAR.format(945.32))
        assertEquals("$39.485,67", Indicator.UF.format(39485.67))
        assertEquals("$68.785", Indicator.UTM.format(68785.0))
    }

    @Test
    fun cacheRoundTrip() {
        val data = RatesData(7L, mapOf(Indicator.DOLAR to Mindicador.parseSeries(sample, Indicator.DOLAR)))
        assertEquals(data, RatesData.fromJson(data.toJson()))
    }

    /** Hits the real service with the app's own URLs. CI sets LIVE_API=1; skipped elsewhere. */
    @Test
    fun liveIndicators() {
        assumeTrue(TestData.live)
        for (indicator in Indicator.entries) {
            val series = Mindicador.parseSeries(TestData.get(Mindicador.seriesUrl(indicator)), indicator)
            val latest = series.latest!!
            println("Live ${indicator.code}: ${indicator.format(latest.value)} on ${latest.date} (${series.points.size} points, change ${series.change})")
            assertTrue("${indicator.code} value looks real", latest.value > 0)
            assertTrue("${indicator.code} is recent", latest.date.isAfter(LocalDate.now().minusDays(45)))
        }
    }
}
