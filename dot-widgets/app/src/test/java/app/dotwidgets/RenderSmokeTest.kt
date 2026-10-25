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

    // Includes wide 4x1 shapes (e.g. the weather "Ahora" single-row layout).
    private val sizes = listOf(110 to 110, 250 to 110, 340 to 180, 180 to 180, 80 to 40, 400 to 400, 340 to 70, 250 to 60)

    private fun configsFor(kind: WidgetKind): List<WidgetConfig> {
        val base = WidgetConfig.defaults(kind)
        return kind.styles.indices.flatMap { style ->
            listOf(
                base.copy(style = style),
                base.copy(style = style, font = WidgetConfig.FONT_SANS, scale = 140, showDate = false, fahrenheit = true, indicator = "utm"),
                base.copy(style = style, font = WidgetConfig.FONT_MONO, scale = 60, hourFormat = WidgetConfig.HOURS_12,
                    period = style % 4, artMode = style % 3, goal = 500, dotCount = 8, photoUri = "content://missing/photo"),
            )
        }
    }

    @Test
    fun everyKindStyleAndSizeRenders() {
        val weather = TestData.forecast()
        println("Weather used for rendering: now=${weather.now}, hours=${weather.hours.size}, days=${weather.days.size}")
        val art = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val media = MediaInfo("Slipping Through My Fingers (Deluxe Edition Remaster)", "ABBA", art, true, 60_000, 200_000, null)
        val now = System.currentTimeMillis()
        val hour = 60 * 60 * 1000L
        val events = listOf(
            CalendarEvent("Dentista con un título bastante largo para partir en varias líneas", now - hour, now + hour, false, "Av. Providencia 1234", 1),
            CalendarEvent("Cumpleaños", now + 20 * hour, now + 44 * hour, true, "", 2),
            CalendarEvent("Gimnasio", now + 50 * hour, now + 51 * hour, false, "", 3),
        )
        val day = java.time.LocalDate.now()
        val rates = RatesData(now, Indicator.entries.associateWith { indicator ->
            RateSeries(indicator, (0 until 20).map { RateSeries.Point(day.minusDays(it.toLong()), 900.0 + (it * 7) % 13) })
        })
        val states = listOf(
            DeviceState(57, false, media, weather, true, steps = 5432, events = events, storage = 99_000_000_000L to 128_000_000_000L, rates = rates),
            DeviceState(9, true, null, null, false, steps = 12_000, stepsStatus = StepCounter.Status.NO_PERMISSION, events = null,
                rates = RatesData(now, mapOf(Indicator.UF to RateSeries(Indicator.UF, listOf(RateSeries.Point(day, 39_485.67)))))),
            DeviceState(100, false, null, null, true, stepsStatus = StepCounter.Status.NO_SENSOR, events = emptyList(), storage = 0L to 0L),
        )
        val palettes = Palettes.all(context)
        var count = 0
        for (visual in VisualStyle.entries) for (kind in WidgetKind.entries) {
            for (config in configsFor(kind)) {
                for ((w, h) in sizes) {
                    for (state in states) {
                        val palette = palettes[count % palettes.size]
                        try {
                            Renderer(context, config, palette, visual = visual).render(kind, w * 2, h * 2, LocalDateTime.now(), state)
                        } catch (error: Throwable) {
                            throw AssertionError("$visual $kind style=${config.style} ${w}x$h weather=${state.weather != null}: $error", error)
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
        // Robolectric doesn't measure text, so only check that paging runs and yields pages.
        val renderer = Renderer(context, WidgetConfig(), palette)
        val long = MediaInfo("Slipping Through My Fingers Deluxe Edition", "ABBA", null, true, 0, 0, null)
        assertTrue(renderer.musicTitlePages(400, 200, long, true, true).isNotEmpty())
    }

    @Test
    fun quickSettingsBuild() {
        val palette = Palettes.all(context).first()
        QuickViews.build(context, null, WidgetConfig.defaults(WidgetKind.QUICK), palette)
        for (visual in VisualStyle.entries) {
            val renderer = Renderer(context, WidgetConfig(), palette, visual = visual)
            QuickToggle.entries.forEach { renderer.quickButton(it, it.ordinal % 2 == 0, 120) }
        }
    }

    @Test
    fun backupRoundTripAndPendingImport() {
        WeatherStore.setPlace(context, WeatherStore.Place("Valparaíso", -33.04, -71.61))
        val exported = Backup.export(context)
        val custom = WidgetConfig(style = 1, palette = "amber", bgAlpha = 40, goal = 12_000)
        val json = org.json.JSONObject(exported).put(
            "widgets",
            org.json.JSONArray().put(org.json.JSONObject().put("kind", "STEPS").put("config", org.json.JSONObject(custom.toJson()))),
        ).toString()
        // No widgets are placed in the test, so the settings wait for the next steps widget.
        assertTrue(Backup.import(context, json) == 0)
        assertTrue(WidgetStore.load(context, 4242, WidgetKind.STEPS) == custom)
        assertTrue("pending entry is used once", WidgetStore.load(context, 4243, WidgetKind.STEPS) == WidgetConfig.defaults(WidgetKind.STEPS))
        assertTrue(WeatherStore.place(context)?.name == "Valparaíso")
    }

    @Test
    fun previewsAndMainScreenWithWeatherData() {
        WeatherStore.setPlace(context, WeatherStore.Place("Santiago de Chile", -33.45, -70.66))
        WeatherStore.saveData(context, TestData.forecast())
        for (kind in WidgetKind.entries) {
            Previews.render(context, kind, WidgetConfig.defaults(kind), kind.widthDp, kind.heightDp, 400)
        }
        Robolectric.buildActivity(MainActivity::class.java).setup().pause().resume()
        // Each tab of the redesigned main screen (the tab is restored from saved state).
        for (tab in listOf("MINE", "ADD", "SETTINGS")) {
            val state = android.os.Bundle().apply { putString("tab", tab) }
            Robolectric.buildActivity(MainActivity::class.java).setup(state).pause().resume()
        }
        // The whole app in the soft style, then back.
        ThemeStore.set(context, VisualStyle.SOFT)
        for (kind in WidgetKind.entries) {
            Previews.render(context, kind, WidgetConfig.defaults(kind), kind.widthDp, kind.heightDp, 400)
        }
        for (tab in listOf("MINE", "ADD", "SETTINGS")) {
            Robolectric.buildActivity(MainActivity::class.java).setup(android.os.Bundle().apply { putString("tab", tab) }).pause().resume()
        }
        ThemeStore.load(context)
        assertTrue(ThemeStore.current == VisualStyle.SOFT)
        ThemeStore.set(context, VisualStyle.DOTS)
        CrashLog.record(context, "prueba", IllegalStateException("registro de ejemplo"))
        Robolectric.buildActivity(MainActivity::class.java).setup(android.os.Bundle().apply { putString("tab", "SETTINGS") })
        CrashLog.clear(context)
        assertTrue("no errors logged:\n${CrashLog.read(context)}", CrashLog.read(context).isEmpty())
    }
}
