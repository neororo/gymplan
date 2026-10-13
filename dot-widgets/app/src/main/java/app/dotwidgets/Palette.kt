package app.dotwidgets

import android.content.Context
import android.graphics.Color
import android.os.Build

enum class ThemeMode(val key: String) {
    DARK("dark"),
    LIGHT("light"),
    DYNAMIC("dynamic");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: DARK
    }
}

/** Colours shared by every widget: background, foreground dots, inactive dots and the accent. */
data class Palette(val bg: Int, val fg: Int, val dim: Int, val accent: Int) {

    companion object {
        private const val PREFS = "dot_prefs"
        private const val KEY_THEME = "theme"
        private const val RED = 0xFFD71921.toInt()

        fun themeMode(context: Context): ThemeMode =
            ThemeMode.fromKey(prefs(context).getString(KEY_THEME, null))

        fun setThemeMode(context: Context, mode: ThemeMode) {
            prefs(context).edit().putString(KEY_THEME, mode.key).apply()
        }

        fun current(context: Context): Palette = of(context, themeMode(context))

        fun of(context: Context, mode: ThemeMode): Palette = when (mode) {
            ThemeMode.DARK -> Palette(
                bg = Color.BLACK,
                fg = Color.WHITE,
                dim = withAlpha(Color.WHITE, 0x40),
                accent = RED,
            )
            ThemeMode.LIGHT -> Palette(
                bg = 0xFFF2F2F2.toInt(),
                fg = Color.BLACK,
                dim = withAlpha(Color.BLACK, 0x30),
                accent = RED,
            )
            ThemeMode.DYNAMIC -> dynamic(context) ?: of(context, ThemeMode.DARK)
        }

        fun dynamicSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        private fun dynamic(context: Context): Palette? {
            if (!dynamicSupported()) return null
            val fg = context.getColor(android.R.color.system_accent1_100)
            return Palette(
                bg = context.getColor(android.R.color.system_neutral1_900),
                fg = fg,
                dim = withAlpha(fg, 0x40),
                accent = context.getColor(android.R.color.system_accent3_300),
            )
        }

        private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
