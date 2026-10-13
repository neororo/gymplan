package app.dotwidgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class OpenMeteoTest {

    private val sample = TestData.forecastJson

    @Test
    fun parsesForecast() {
        val data = OpenMeteo.parseForecast(sample, "Santiago", 1L)
        assertEquals(17.6, data.now.temp, 0.001)
        assertEquals(Condition.PARTLY, Condition.of(data.now.code))
        assertEquals("Hours with a null temperature are skipped", 2, data.hours.size)
        assertEquals("Null precipitation reads as 0", 0, data.hours[1].precipitation)
        assertEquals(2, data.days.size)
        assertEquals(Condition.RAIN, Condition.of(data.days[1].code))
    }

    @Test
    fun cacheRoundTrip() {
        val data = OpenMeteo.parseForecast(sample, "Santiago", 42L)
        assertEquals(data, WeatherData.fromJson(data.toJson()))
    }

    @Test
    fun parsesGeocodingWithAndWithoutResults() {
        val places = OpenMeteo.parseGeocoding(
            """{"results":[{"name":"Santiago","latitude":-33.45,"longitude":-70.65,"country":"Chile","admin1":"Santiago Metropolitan"}]}""",
        )
        assertEquals("Santiago Metropolitan, Chile", places.single().detail)
        assertTrue(OpenMeteo.parseGeocoding("""{"generationtime_ms":0.5}""").isEmpty())
    }

    /** Hits the real API with the app's own query. CI sets LIVE_API=1; skipped elsewhere. */
    @Test
    fun liveForecastAndGeocoding() {
        assumeTrue(System.getenv("LIVE_API") == "1")
        val forecast = get("${OpenMeteo.FORECAST_URL}?${OpenMeteo.forecastQuery(-33.45, -70.66)}")
        val data = OpenMeteo.parseForecast(forecast, "Santiago", System.currentTimeMillis())
        assertTrue("current temperature looks real", data.now.temp in -60.0..60.0)
        assertTrue("at least 6 hours, got ${data.hours.size}", data.hours.size >= 6)
        assertTrue("at least 5 days, got ${data.days.size}", data.days.size >= 5)
        assertNotNull(WeatherData.fromJson(data.toJson()))
        println("Live forecast: now=${data.now}, hours=${data.hours.take(2)}, days=${data.days.take(2)}")

        val places = OpenMeteo.parseGeocoding(get("${OpenMeteo.GEOCODING_URL}?name=Santiago&count=8&language=es&format=json"))
        assertFalse("geocoding returns places", places.isEmpty())
        println("Live geocoding: ${places.take(3)}")
    }

    private fun get(url: String): String = TestData.get(url)
}
