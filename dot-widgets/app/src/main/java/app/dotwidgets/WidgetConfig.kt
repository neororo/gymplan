package app.dotwidgets

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

/** Per-widget settings, stored as JSON keyed by the app widget id. */
data class WidgetConfig(
    val style: Int = 0,
    val palette: String = Palettes.DEFAULT_ID,
    val accent: Boolean = true,
    val font: Int = FONT_DOTS,
    val bgAlpha: Int = 100,
    val corner: Int = 2,
    val scale: Int = 100,
    val showDate: Boolean = true,
    val hourFormat: Int = HOURS_AUTO,
    val period: Int = PERIOD_YEAR,
    val text: String = "",
    val targetEpochDay: Long = NO_DATE,
    val artMode: Int = ART_DOTS,
) {
    fun toJson(): String = JSONObject()
        .put("style", style)
        .put("palette", palette)
        .put("accent", accent)
        .put("font", font)
        .put("bgAlpha", bgAlpha)
        .put("corner", corner)
        .put("scale", scale)
        .put("showDate", showDate)
        .put("hourFormat", hourFormat)
        .put("period", period)
        .put("text", text)
        .put("targetEpochDay", targetEpochDay)
        .put("artMode", artMode)
        .toString()

    fun target(): LocalDate =
        if (targetEpochDay == NO_DATE) LocalDate.now().plusDays(30) else LocalDate.ofEpochDay(targetEpochDay)

    companion object {
        const val FONT_DOTS = 0
        const val FONT_SANS = 1
        const val FONT_MONO = 2

        const val HOURS_AUTO = 0
        const val HOURS_12 = 1
        const val HOURS_24 = 2

        const val PERIOD_DAY = 0
        const val PERIOD_WEEK = 1
        const val PERIOD_MONTH = 2
        const val PERIOD_YEAR = 3

        const val NO_DATE = Long.MIN_VALUE

        const val ART_DOTS = 0
        const val ART_DOTS_COLOR = 1
        const val ART_PHOTO = 2

        /** Corner radius in dp for each `corner` option. */
        val CORNER_RADII = intArrayOf(0, 12, 24, 36)

        fun defaults(kind: WidgetKind): WidgetConfig = when (kind) {
            WidgetKind.NOTE -> WidgetConfig(text = "Hola")
            WidgetKind.COUNTDOWN -> WidgetConfig(text = "Evento")
            WidgetKind.MUSIC -> WidgetConfig(style = MusicViews.STYLE_LARGE)
            else -> WidgetConfig()
        }

        fun fromJson(json: String, fallback: WidgetConfig): WidgetConfig {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return fallback
            return WidgetConfig(
                style = o.optInt("style", fallback.style),
                palette = o.optString("palette", fallback.palette),
                accent = o.optBoolean("accent", fallback.accent),
                font = o.optInt("font", fallback.font),
                bgAlpha = o.optInt("bgAlpha", fallback.bgAlpha),
                corner = o.optInt("corner", fallback.corner).coerceIn(0, CORNER_RADII.size - 1),
                scale = o.optInt("scale", fallback.scale),
                showDate = o.optBoolean("showDate", fallback.showDate),
                hourFormat = o.optInt("hourFormat", fallback.hourFormat),
                period = o.optInt("period", fallback.period),
                text = o.optString("text", fallback.text),
                targetEpochDay = o.optLong("targetEpochDay", fallback.targetEpochDay),
                artMode = o.optInt("artMode", fallback.artMode),
            )
        }
    }
}

object WidgetStore {
    private const val PREFS = "widget_configs"

    fun load(context: Context, id: Int, kind: WidgetKind): WidgetConfig {
        val defaults = WidgetConfig.defaults(kind)
        val json = prefs(context).getString(key(id), null) ?: return defaults
        return WidgetConfig.fromJson(json, defaults)
    }

    fun save(context: Context, id: Int, config: WidgetConfig) {
        prefs(context).edit().putString(key(id), config.toJson()).apply()
    }

    fun delete(context: Context, id: Int) {
        prefs(context).edit().remove(key(id)).apply()
    }

    private fun key(id: Int) = "w_$id"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
