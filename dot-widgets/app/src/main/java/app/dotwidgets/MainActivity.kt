package app.dotwidgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.BatteryManager
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {

    private class Entry(
        val provider: Class<out DotWidget>,
        val name: Int,
        val description: Int,
        val widthDp: Int,
        val heightDp: Int,
        val render: (DotRenderer, Int, Int) -> Bitmap,
    )

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.widget_list)
        setUpThemePicker()
    }

    // Re-rendered on every resume so the previews show the current time and battery level.
    override fun onResume() {
        super.onResume()
        renderList()
    }

    private fun setUpThemePicker() {
        val group = findViewById<RadioGroup>(R.id.theme_group)
        findViewById<TextView>(R.id.theme_dynamic).isEnabled = Palette.dynamicSupported()
        group.check(
            when (Palette.themeMode(this)) {
                ThemeMode.DARK -> R.id.theme_dark
                ThemeMode.LIGHT -> R.id.theme_light
                ThemeMode.DYNAMIC -> R.id.theme_dynamic
            },
        )
        group.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.theme_light -> ThemeMode.LIGHT
                R.id.theme_dynamic -> ThemeMode.DYNAMIC
                else -> ThemeMode.DARK
            }
            Palette.setThemeMode(this, mode)
            WidgetUpdater.updateAll(this)
            renderList()
        }
    }

    private fun entries(): List<Entry> {
        val today = LocalDate.now()
        val locale = Locale.getDefault()
        val timePattern = if (DateFormat.is24HourFormat(this)) "HH:mm" else "hh:mm"
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern(timePattern, locale))
        val date = today.format(DateTimeFormatter.ofPattern("EEE d MMM", locale)).uppercase(locale)
        val battery = getSystemService(BatteryManager::class.java)
        return listOf(
            Entry(ClockWidget::class.java, R.string.clock_name, R.string.clock_desc, 300, 140) { r, w, h ->
                r.clockPreview(w, h, time, date)
            },
            Entry(BatteryWidget::class.java, R.string.battery_name, R.string.battery_desc, 150, 150) { r, w, h ->
                r.battery(w, h, battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), battery.isCharging)
            },
            Entry(CalendarWidget::class.java, R.string.calendar_name, R.string.calendar_desc, 240, 240) { r, w, h ->
                r.calendar(w, h, today)
            },
            Entry(YearWidget::class.java, R.string.year_name, R.string.year_desc, 300, 140) { r, w, h ->
                r.year(w, h, today)
            },
        )
    }

    private fun renderList() {
        val palette = Palette.current(this)
        val renderer = DotRenderer(this, palette)
        val inflater = LayoutInflater.from(this)
        list.removeAllViews()
        for (entry in entries()) {
            val item = inflater.inflate(R.layout.item_widget, list, false)
            item.findViewById<ImageView>(R.id.preview).setImageBitmap(preview(entry, renderer, palette))
            item.findViewById<TextView>(R.id.name).setText(entry.name)
            item.findViewById<TextView>(R.id.description).setText(entry.description)
            item.findViewById<Button>(R.id.add).setOnClickListener { pin(entry.provider) }
            list.addView(item)
        }
    }

    /** Widget preview: content bitmap on the same rounded background the home-screen widget uses. */
    private fun preview(entry: Entry, renderer: DotRenderer, palette: Palette): Bitmap {
        val density = resources.displayMetrics.density
        val width = (entry.widthDp * density).toInt()
        val height = (entry.heightDp * density).toInt()
        val padding = 12 * density
        val radius = 24 * density
        val content = entry.render(renderer, (width - 2 * padding).toInt(), (height - 2 * padding).toInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.bg }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
        canvas.drawBitmap(content, padding, padding, null)
        return bitmap
    }

    private fun pin(provider: Class<out DotWidget>) {
        val manager = AppWidgetManager.getInstance(this)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(ComponentName(this, provider), null, null)
        } else {
            Toast.makeText(this, R.string.pin_unsupported, Toast.LENGTH_LONG).show()
        }
    }
}
