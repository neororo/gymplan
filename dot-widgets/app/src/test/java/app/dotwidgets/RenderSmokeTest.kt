package app.dotwidgets

import android.content.Context
import android.graphics.Bitmap
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * Runs every widget's drawing code (all kinds, styles, sizes and main options) and opens the main
 * screen, on the JVM through Robolectric. Catches crashes that only show up on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = DotApp::class)
class RenderSmokeTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val sizes = listOf(110 to 110, 250 to 110, 340 to 180, 180 to 180, 80 to 40, 400 to 400)

    private fun configsFor(kind: WidgetKind): List<WidgetConfig> {
        val base = WidgetConfig.defaults(kind)
        return kind.styles.indices.flatMap { style ->
            listOf(
                base.copy(style = style),
                base.copy(style = style, font = WidgetConfig.FONT_SANS, scale = 140, showDate = false, fahrenheit = true),
                base.copy(style = style, font = WidgetConfig.FONT_MONO, scale = 60, hourFormat = WidgetConfig.HOURS_12,
                    period = style % 4, artMode = style % 3),
            )
        }
    }

    @Test
    fun everyKindStyleAndSizeRenders() {
        val weather = TestData.forecast()
        println("Weather used for rendering: now=${weather.now}, hours=${weather.hours.size}, days=${weather.days.size}")
        val art = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val media = MediaInfo("Slipping Through My Fingers (Deluxe Edition Remaster)", "ABBA", art, true, 60_000, 200_000, null)
        val states = listOf(
            DeviceState(57, false, media, weather, true),
            DeviceState(9, true, null, null, false),
            DeviceState(100, false, null, null, true),
        )
        val palettes = Palettes.all(context)
        var count = 0
        for (kind in WidgetKind.entries) {
            for (config in configsFor(kind)) {
                for ((w, h) in sizes) {
                    for (state in states) {
                        val palette = palettes[count % palettes.size]
                        try {
                            Renderer(context, config, palette).render(kind, w * 2, h * 2, LocalDateTime.now(), state)
                        } catch (error: Throwable) {
                            throw AssertionError("$kind style=${config.style} ${w}x$h weather=${state.weather != null}: $error", error)
                        }
                        count++
                    }
                }
            }
        }
        println("Rendered $count widget variants")
        assertTrue(count > 0)
    }

    @Test
    fun musicLayoutsBuild() {
        val palette = Palettes.all(context).first()
        for (style in 0..2) {
            MusicViews.build(context, null, 1, WidgetConfig.defaults(WidgetKind.MUSIC).copy(style = style), palette)
        }
        val renderer = Renderer(context, WidgetConfig(), palette)
        val long = MediaInfo("Slipping Through My Fingers Deluxe Edition", "ABBA", null, true, 0, 0, null)
        assertTrue(renderer.musicTitlePages(400, 200, long, true, true).size > 1)
    }

    @Test
    fun previewsAndMainScreenWithWeatherData() {
        WeatherStore.setPlace(context, WeatherStore.Place("Santiago de Chile", -33.45, -70.66))
        WeatherStore.saveData(context, TestData.forecast())
        for (kind in WidgetKind.entries) {
            Previews.render(context, kind, WidgetConfig.defaults(kind), kind.widthDp, kind.heightDp, 400)
        }
        Robolectric.buildActivity(MainActivity::class.java).setup().pause().resume()
        assertTrue("no errors logged:\n${CrashLog.read(context)}", CrashLog.read(context).isEmpty())
    }
}
