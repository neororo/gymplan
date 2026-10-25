package app.dotwidgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate

class BcchApiTest {

    private val sample = """
        {"Codigo":0,"Descripcion":"Success",
         "Series":{"descripEsp":"Tipo de cambio del dólar observado diario","descripIng":"Observed dollar","seriesId":"F073.TCO.PRE.Z.D",
           "Obs":[{"indexDateString":"21-10-2026","value":"976.82","statusCode":"OK"},
                  {"indexDateString":"22-10-2026","value":"NaN","statusCode":"ND"},
                  {"indexDateString":"23-10-2026","value":"986.58","statusCode":"OK"}]},
         "SeriesInfos":[]}
    """.trimIndent()

    @Test
    fun parsesObservationsNewestFirstSkippingGaps() {
        val series = BcchApi.parse(sample, Indicator.DOLAR)
        assertEquals(LocalDate.of(2026, 10, 23), series.latest?.date)
        assertEquals(986.58, series.latest!!.value, 0.001)
        assertEquals(2, series.points.size)
        assertEquals(9.76, series.change!!, 0.001)
    }

    @Test
    fun errorCodeBecomesApiError() {
        try {
            BcchApi.parse("""{"Codigo":-5,"Descripcion":"Invalid username or password","Series":{"Obs":null}}""", Indicator.DOLAR)
            fail("expected ApiError")
        } catch (error: BcchApi.ApiError) {
            assertEquals(-5, error.code)
        }
    }

    @Test
    fun urlCarriesTokenSeriesAndRange() {
        val url = BcchApi.url(" abc+123 ", Indicator.UF, LocalDate.of(2026, 10, 25))
        assertTrue(url.contains("token=abc%2B123"))
        assertTrue(url.contains("timeseries=F073.UFF.PRE.Z.D"))
        assertTrue(url.contains("firstdate=2026-09-25&lastdate=2026-10-25"))
    }

    /**
     * Uses the repository secret BCCH_TOKEN (never printed; GitHub masks it) to check the token
     * parameter, the series codes and the response format against the real API. Skipped without it.
     */
    @Test
    fun liveWithToken() {
        val token = System.getenv("BCCH_TOKEN").orEmpty()
        assumeTrue("BCCH_TOKEN not configured", token.isNotBlank())
        for (indicator in Indicator.entries) {
            val json = TestData.get(BcchApi.url(token, indicator, LocalDate.now()))
            val series = try {
                BcchApi.parse(json, indicator)
            } catch (error: BcchApi.ApiError) {
                throw AssertionError("${indicator.code} (${indicator.bcchSeries}): ${error.message}")
            }
            val latest = series.latest!!
            println("BCCh ${indicator.code} ${indicator.bcchSeries}: ${indicator.format(latest.value)} on ${latest.date} (${series.points.size} points)")
            assertTrue(latest.value > 0)
        }
    }
}
