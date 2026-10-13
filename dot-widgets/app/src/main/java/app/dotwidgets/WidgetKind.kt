package app.dotwidgets

/** Every widget type the app offers, with its provider, picker texts, styles and default size. */
enum class WidgetKind(
    val provider: Class<out KindWidget>,
    val title: Int,
    val description: Int,
    val styles: List<Int>,
    val widthDp: Int,
    val heightDp: Int,
) {
    CLOCK(
        ClockWidget::class.java, R.string.clock_name, R.string.clock_desc,
        listOf(R.string.style_digital, R.string.style_stacked, R.string.style_analog), 250, 110,
    ),
    BATTERY(
        BatteryWidget::class.java, R.string.battery_name, R.string.battery_desc,
        listOf(R.string.style_ring, R.string.style_bar), 110, 110,
    ),
    CALENDAR(
        CalendarWidget::class.java, R.string.calendar_name, R.string.calendar_desc,
        listOf(R.string.style_month, R.string.style_week), 180, 180,
    ),
    DATE(
        DateWidget::class.java, R.string.date_name, R.string.date_desc,
        listOf(R.string.style_big, R.string.style_line), 180, 110,
    ),
    PROGRESS(
        YearWidget::class.java, R.string.progress_name, R.string.progress_desc,
        listOf(R.string.style_grid, R.string.style_bar), 250, 110,
    ),
    COUNTDOWN(
        CountdownWidget::class.java, R.string.countdown_name, R.string.countdown_desc,
        listOf(R.string.style_days), 180, 110,
    ),
    NOTE(
        NoteWidget::class.java, R.string.note_name, R.string.note_desc,
        listOf(R.string.style_centered, R.string.style_left), 180, 110,
    ),
    MUSIC(
        MusicWidget::class.java, R.string.music_name, R.string.music_desc,
        listOf(R.string.style_compact, R.string.style_large, R.string.style_cover), 250, 110,
    ),
    WEATHER(
        WeatherWidget::class.java, R.string.weather_name, R.string.weather_desc,
        listOf(R.string.style_now, R.string.style_hourly, R.string.style_week), 180, 180,
    );

    companion object {
        fun forProvider(className: String?): WidgetKind? = entries.firstOrNull { it.provider.name == className }
    }
}
