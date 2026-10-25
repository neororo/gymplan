package app.dotwidgets

import android.content.Context
import android.os.Build

/** Background, foreground and accent colours; inactive dots are the foreground at low alpha. */
data class Palette(val id: String, val name: String, val bg: Int, val fg: Int, val accent: Int) {
    val dim: Int get() = (fg and 0x00FFFFFF) or (0x40 shl 24)
}

object Palettes {
    const val DEFAULT_ID = "nothing_dark"

    private val presets = listOf(
        Palette("nothing_dark", "Nothing", 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFD71921.toInt()),
        Palette("nothing_light", "Nothing claro", 0xFFF2F2F2.toInt(), 0xFF000000.toInt(), 0xFFD71921.toInt()),
        Palette("graphite", "Grafito", 0xFF1C1C1E.toInt(), 0xFFEDEDED.toInt(), 0xFFFF453A.toInt()),
        Palette("paper", "Papel", 0xFFFAF7F0.toInt(), 0xFF2B2B2B.toInt(), 0xFFE4572E.toInt()),
        Palette("red", "Rojo", 0xFFD71921.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt()),
        Palette("amber", "Ámbar retro", 0xFF000000.toInt(), 0xFFFFB000.toInt(), 0xFFFFFFFF.toInt()),
        Palette("terminal", "Terminal", 0xFF000000.toInt(), 0xFF33FF66.toInt(), 0xFFFFFFFF.toInt()),
        Palette("midnight", "Medianoche", 0xFF0B1426.toInt(), 0xFFE6ECFF.toInt(), 0xFF4D8DFF.toInt()),
        Palette("ocean", "Océano", 0xFF0E3B43.toInt(), 0xFFD8F3F0.toInt(), 0xFF3DDC97.toInt()),
        Palette("forest", "Bosque", 0xFF1B2A1F.toInt(), 0xFFE3F2D9.toInt(), 0xFF9BD770.toInt()),
        Palette("sunset", "Atardecer", 0xFF2B1A12.toInt(), 0xFFFFE7D1.toInt(), 0xFFFF7A3D.toInt()),
        Palette("coffee", "Café", 0xFF3E2C23.toInt(), 0xFFF1E3D3.toInt(), 0xFFD9A066.toInt()),
        Palette("slate", "Pizarra", 0xFF3A3A3C.toInt(), 0xFFF2F2F7.toInt(), 0xFFFFD60A.toInt()),
        Palette("sand", "Arena", 0xFFE8DCC8.toInt(), 0xFF3B3226.toInt(), 0xFFC0582B.toInt()),
        Palette("lavender", "Lavanda", 0xFFE9E4F5.toInt(), 0xFF2E2547.toInt(), 0xFF7B5CD6.toInt()),
        Palette("rose", "Rosa", 0xFFF8E1E7.toInt(), 0xFF4A1F2B.toInt(), 0xFFE0457B.toInt()),
        Palette("mint", "Menta", 0xFFDFF5EA.toInt(), 0xFF17382A.toInt(), 0xFF1FA971.toInt()),
        Palette("ice", "Hielo", 0xFFE8F4FA.toInt(), 0xFF0F2A3A.toInt(), 0xFF00A3E0.toInt()),
        // Made for the soft style: calm neutrals with one clear accent.
        Palette("soft_light", "Claro suave", 0xFFF4F6FA.toInt(), 0xFF16181D.toInt(), 0xFF3E91FF.toInt()),
        Palette("soft_dark", "Oscuro suave", 0xFF17191E.toInt(), 0xFFF5F7FA.toInt(), 0xFF4E9BFF.toInt()),
        Palette("soft_mint", "Menta suave", 0xFFE8F6EF.toInt(), 0xFF13261D.toInt(), 0xFF2DBE7E.toInt()),
        Palette("soft_coral", "Coral suave", 0xFFFFF1EC.toInt(), 0xFF2B1611.toInt(), 0xFFFF6B4A.toInt()),
    )

    /** Material You palettes first (Android 12+), then the fixed presets. */
    fun all(context: Context): List<Palette> = dynamic(context) + presets

    fun find(context: Context, id: String): Palette =
        all(context).firstOrNull { it.id == id } ?: presets.first()

    private fun dynamic(context: Context): List<Palette> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        return listOf(
            Palette(
                "you_dark", "Material You",
                context.getColor(android.R.color.system_neutral1_900),
                context.getColor(android.R.color.system_accent1_100),
                context.getColor(android.R.color.system_accent3_300),
            ),
            Palette(
                "you_light", "Material You claro",
                context.getColor(android.R.color.system_accent1_50),
                context.getColor(android.R.color.system_neutral1_900),
                context.getColor(android.R.color.system_accent1_600),
            ),
            Palette(
                "you_vivid", "Material You intenso",
                context.getColor(android.R.color.system_accent1_600),
                context.getColor(android.R.color.system_accent1_50),
                context.getColor(android.R.color.system_accent3_200),
            ),
        )
    }
}
