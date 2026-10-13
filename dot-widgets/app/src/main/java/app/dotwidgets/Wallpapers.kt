package app.dotwidgets

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.min

/** Plain wallpapers that make the dot widgets stand out. */
enum class WallpaperStyle(val label: Int, val bg: Int, val dot: Int) {
    BLACK(R.string.wallpaper_black, 0xFF000000.toInt(), 0),
    BLACK_DOTS(R.string.wallpaper_black_dots, 0xFF000000.toInt(), 0xFF2A2A2A.toInt()),
    GRAPHITE(R.string.wallpaper_graphite, 0xFF141414.toInt(), 0xFF303030.toInt()),
    LIGHT_DOTS(R.string.wallpaper_light_dots, 0xFFF2F2F2.toInt(), 0xFFD4D4D4.toInt()),
}

object Wallpapers {
    const val TARGET_BOTH = 0
    const val TARGET_HOME = 1
    const val TARGET_LOCK = 2

    private const val DOT_SPACING_DP = 22f
    private const val DOT_RADIUS_DP = 1.6f
    private const val MAX_SIDE = 4096

    /** [scale] shrinks the dot grid for thumbnails so they look like the full-size wallpaper. */
    fun render(context: Context, style: WallpaperStyle, width: Int, height: Int, scale: Float = 1f): Bitmap {
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(style.bg)
        if (style.dot != 0) {
            val density = context.resources.displayMetrics.density * scale
            val spacing = DOT_SPACING_DP * density
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.dot }
            var y = spacing / 2f
            while (y < height) {
                var x = spacing / 2f
                while (x < width) {
                    canvas.drawCircle(x, y, DOT_RADIUS_DP * density, paint)
                    x += spacing
                }
                y += spacing
            }
        }
        return bitmap
    }

    /** Blocking (encodes and writes a full-screen image): call off the main thread. */
    fun apply(context: Context, style: WallpaperStyle, target: Int) {
        val manager = WallpaperManager.getInstance(context)
        val (width, height) = screenSize(context, manager)
        val flags = when (target) {
            TARGET_HOME -> WallpaperManager.FLAG_SYSTEM
            TARGET_LOCK -> WallpaperManager.FLAG_LOCK
            else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        }
        manager.setBitmap(render(context, style, width, height), null, true, flags)
    }

    /** Back to the phone's default wallpaper (the previous custom one can't be read back). */
    fun restoreDefault(context: Context) {
        WallpaperManager.getInstance(context).clear()
    }

    fun canSet(context: Context): Boolean {
        val manager = WallpaperManager.getInstance(context)
        return manager.isWallpaperSupported && manager.isSetWallpaperAllowed
    }

    fun screenSize(context: Context, manager: WallpaperManager = WallpaperManager.getInstance(context)): Pair<Int, Int> {
        val (screenW, screenH) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
        // Some launchers ask for a wider image to scroll across home pages.
        val width = maxOf(screenW, manager.desiredMinimumWidth)
        val height = maxOf(screenH, manager.desiredMinimumHeight)
        return min(width, MAX_SIDE) to min(height, MAX_SIDE)
    }
}
