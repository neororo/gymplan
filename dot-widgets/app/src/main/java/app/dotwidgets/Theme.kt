package app.dotwidgets

import android.content.Context

/** The two visual languages: the original dot matrix, and a soft, rounded one inspired by One UI. */
enum class VisualStyle { DOTS, SOFT }

/**
 * App-wide visual style. Kept in memory as well as in preferences because plain helpers (colours,
 * fonts) read it without a Context; [DotApp] loads it at process start.
 */
object ThemeStore {
    private const val PREFS = "theme"

    @Volatile
    var current: VisualStyle = VisualStyle.DOTS
        private set

    fun load(context: Context) {
        current = VisualStyle.entries.firstOrNull {
            it.name == context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("style", null)
        } ?: VisualStyle.DOTS
    }

    fun set(context: Context, style: VisualStyle) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("style", style.name).apply()
        current = style
    }

    val soft: Boolean get() = current == VisualStyle.SOFT
}
