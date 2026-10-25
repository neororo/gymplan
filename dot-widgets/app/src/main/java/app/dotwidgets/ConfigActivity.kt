package app.dotwidgets

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import app.dotwidgets.Ui.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Widget editor with a live preview. Launched by the launcher when a widget is added or
 * reconfigured, from the app's "Tus widgets" list, and by tapping some widgets.
 */
class ConfigActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var kind: WidgetKind
    private lateinit var config: WidgetConfig
    private lateinit var preview: ImageView
    private var accessBox: LinearLayout? = null
    /** Redraws the permission section of the current widget kind (after returning from Settings). */
    private var renderPermission: (() -> Unit)? = null
    private var previewWidthDp = 0
    private var previewHeightDp = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // If the user backs out of the first configuration, the launcher drops the widget.
        setResult(RESULT_CANCELED, resultIntent())
        val manager = AppWidgetManager.getInstance(this)
        val found = WidgetKind.forProvider(manager.getAppWidgetInfo(widgetId)?.provider?.className)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || found == null) {
            finish()
            return
        }
        kind = found
        config = WidgetStore.load(this, widgetId, kind)
        val (w, h) = WidgetViews.sizeDp(manager.getAppWidgetOptions(widgetId), kind)
        previewWidthDp = w
        previewHeightDp = h
        setContentView(buildContent())
        updatePreview()
    }

    // Back from the notification-access screen: show the new state and the real track.
    override fun onResume() {
        super.onResume()
        if (::preview.isInitialized) {
            renderAccess()
            renderPermission?.invoke()
            updatePreview()
        }
    }

    private fun renderAccess() {
        val box = accessBox ?: return
        box.removeAllViews()
        if (MediaState.hasAccess(this)) {
            box.addView(Ui.body(this, getString(R.string.music_access_ok)))
            return
        }
        box.addView(Ui.body(this, getString(R.string.music_access_hint), Ui.WHITE))
        box.addView(Button(this).apply {
            setText(R.string.music_access_button)
            setTextColor(Ui.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Ui.RED)
            setOnClickListener { MediaState.openAccessSettings(this@ConfigActivity) }
        })
        box.addView(Ui.body(this, getString(R.string.music_access_restricted)))
    }

    private fun buildContent(): LinearLayout {
        val form = Ui.column(this).apply {
            addView(Ui.title(this@ConfigActivity, getString(kind.title), 28f))
        }

        preview = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = dp(188)
            maxWidth = resources.displayMetrics.widthPixels - dp(32)
        }
        val previewFrame = FrameLayout(this).apply {
            setBackgroundColor(0xFF2A2A2A.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(preview, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        }

        if (kind.styles.size > 1) {
            form.addView(Ui.section(this, R.string.section_style))
            form.addView(Ui.choices(this, kind.styles.map(::getString), config.style) { set(config.copy(style = it)) })
        }

        addKindOptions(form)

        form.addView(Ui.section(this, R.string.section_colors))
        form.addView(Ui.palettes(this, Palettes.all(this), config.palette) { set(config.copy(palette = it.id)) })
        form.addView(Ui.toggle(this, R.string.option_accent, config.accent) { set(config.copy(accent = it)) })

        form.addView(Ui.section(this, R.string.section_font))
        form.addView(
            Ui.choices(
                this,
                listOf(getString(R.string.font_dots), getString(R.string.font_sans), getString(R.string.font_mono)),
                config.font,
            ) { set(config.copy(font = it)) },
        )

        form.addView(Ui.section(this, R.string.section_shape))
        form.addView(Ui.slider(this, R.string.option_background, 0, 100, config.bgAlpha, "%") { set(config.copy(bgAlpha = it)) })
        if (kind != WidgetKind.MUSIC && kind != WidgetKind.QUICK) {
            form.addView(Ui.slider(this, R.string.option_size, 60, 140, config.scale, "%") { set(config.copy(scale = it)) })
        }
        form.addView(Ui.body(this, getString(R.string.option_corners), Ui.WHITE))
        form.addView(
            Ui.choices(
                this,
                listOf(getString(R.string.corner_square), getString(R.string.corner_soft), getString(R.string.corner_round), getString(R.string.corner_rounder)),
                config.corner,
            ) { set(config.copy(corner = it)) },
        )

        val save = Button(this).apply {
            setText(R.string.save)
            setTextColor(Ui.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Ui.RED)
            setOnClickListener { save() }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BLACK)
            fitsSystemWindows = true
            addView(previewFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
            addView(ScrollView(this@ConfigActivity).apply { addView(form) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), dp(8), dp(16), dp(12))
            })
        }
    }

    private fun addKindOptions(form: LinearLayout) {
        when (kind) {
            WidgetKind.CLOCK -> {
                form.addView(Ui.section(this, R.string.section_clock))
                form.addView(Ui.toggle(this, R.string.option_show_date, config.showDate) { set(config.copy(showDate = it)) })
                form.addView(
                    Ui.choices(
                        this,
                        listOf(getString(R.string.hours_auto), getString(R.string.hours_12), getString(R.string.hours_24)),
                        config.hourFormat,
                    ) { set(config.copy(hourFormat = it)) },
                )
            }
            WidgetKind.PROGRESS -> {
                form.addView(Ui.section(this, R.string.section_period))
                form.addView(
                    Ui.choices(
                        this,
                        listOf(getString(R.string.period_day), getString(R.string.period_week), getString(R.string.period_month), getString(R.string.period_year)),
                        config.period,
                    ) { set(config.copy(period = it)) },
                )
            }
            WidgetKind.COUNTDOWN -> {
                form.addView(Ui.section(this, R.string.section_event))
                form.addView(textField(config.text, multiLine = false) { set(config.copy(text = it)) })
                val dateButton = Button(this)
                fun label() {
                    dateButton.text = getString(R.string.event_date, config.target().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
                }
                label()
                dateButton.setOnClickListener {
                    val current = config.target()
                    DatePickerDialog(this, { _, y, m, d ->
                        set(config.copy(targetEpochDay = LocalDate.of(y, m + 1, d).toEpochDay()))
                        label()
                    }, current.year, current.monthValue - 1, current.dayOfMonth).show()
                }
                form.addView(dateButton)
            }
            WidgetKind.MUSIC -> {
                form.addView(Ui.section(this, R.string.section_music))
                accessBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                form.addView(accessBox)
                renderAccess()
                form.addView(
                    Ui.choices(
                        this,
                        listOf(getString(R.string.art_dots), getString(R.string.art_dots_color), getString(R.string.art_photo)),
                        config.artMode,
                    ) { set(config.copy(artMode = it)) },
                )
            }
            WidgetKind.WEATHER -> {
                form.addView(Ui.section(this, R.string.section_weather))
                form.addView(WeatherUi.section(this) { updatePreview() })
                form.addView(
                    Ui.choices(this, listOf("°C", "°F"), if (config.fahrenheit) 1 else 0) { set(config.copy(fahrenheit = it == 1)) },
                )
            }
            WidgetKind.STEPS -> {
                permissionSection(form) { box ->
                    when (StepCounter.status(this)) {
                        StepCounter.Status.OK -> box.addView(Ui.body(this, getString(R.string.permission_granted)))
                        StepCounter.Status.NO_SENSOR -> box.addView(Ui.body(this, getString(R.string.steps_no_sensor), Ui.WHITE))
                        StepCounter.Status.NO_PERMISSION -> requestRow(box, R.string.steps_permission_hint, R.string.permission_button) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), STEPS_REQUEST)
                            }
                        }
                    }
                }
                form.addView(Ui.slider(this, R.string.option_goal, 2, 60, config.goal / 500, "", step = 500) { set(config.copy(goal = it.coerceAtLeast(500))) })
            }
            WidgetKind.EVENTS -> permissionSection(form) { box ->
                if (CalendarEvents.hasPermission(this)) {
                    box.addView(Ui.body(this, getString(R.string.permission_granted)))
                } else {
                    requestRow(box, R.string.events_permission_hint, R.string.permission_button) {
                        requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), EVENTS_REQUEST)
                    }
                }
            }
            WidgetKind.QUICK -> permissionSection(form) { box ->
                if (QuickState.hasDndAccess(this)) {
                    box.addView(Ui.body(this, getString(R.string.permission_granted)))
                } else {
                    requestRow(box, R.string.quick_dnd_hint, R.string.quick_dnd_button) {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    }
                }
            }
            WidgetKind.PHOTO -> {
                form.addView(Ui.section(this, R.string.photo_name))
                form.addView(Button(this).apply {
                    setText(if (config.photoUri.isBlank()) R.string.photo_pick else R.string.photo_change)
                    setTextColor(Ui.WHITE)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Ui.RED)
                    setOnClickListener {
                        startActivityForResult(
                            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),
                            PHOTO_REQUEST,
                        )
                    }
                })
                form.addView(
                    Ui.choices(
                        this,
                        listOf(getString(R.string.art_dots), getString(R.string.art_dots_color), getString(R.string.art_photo)),
                        config.artMode,
                    ) { set(config.copy(artMode = it)) },
                )
                form.addView(Ui.slider(this, R.string.option_dots, 8, 96, config.dotCount, "") { set(config.copy(dotCount = it.coerceAtLeast(8))) })
            }
            WidgetKind.RATES -> {
                form.addView(Ui.section(this, R.string.section_indicator))
                form.addView(
                    Ui.choices(this, listOf("Dólar", "Euro", "UF", "UTM"), Indicator.entries.indexOf(Indicator.of(config.indicator))) {
                        set(config.copy(indicator = Indicator.entries[it].code))
                    },
                )
                form.addView(Ui.body(this, getString(R.string.rates_source)))
            }
            WidgetKind.NOTE -> {
                form.addView(Ui.section(this, R.string.section_text))
                form.addView(textField(config.text, multiLine = true) { set(config.copy(text = it)) })
            }
            else -> Unit
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        WeatherUi.onPermissionResult(this, requestCode, grantResults)
        renderPermission?.invoke()
        if (requestCode == STEPS_REQUEST) {
            StepCounter.read(this) {
                updatePreview()
                Scheduler.update(this, listOf(WidgetKind.STEPS))
            }
        } else {
            updatePreview()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != PHOTO_REQUEST || resultCode != RESULT_OK || uri == null) return
        // Keep read access across reboots so the widget can redraw the photo later.
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onFailure { CrashLog.record(this, "permiso de la foto", it) }
        set(config.copy(photoUri = uri.toString()))
    }

    /** A section whose content depends on a permission; redrawn whenever the screen comes back. */
    private fun permissionSection(form: LinearLayout, fill: (LinearLayout) -> Unit) {
        form.addView(Ui.section(this, R.string.section_permission))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form.addView(box)
        renderPermission = {
            box.removeAllViews()
            fill(box)
        }
        renderPermission?.invoke()
    }

    private fun requestRow(box: LinearLayout, hint: Int, button: Int, onClick: () -> Unit) {
        box.addView(Ui.body(this, getString(hint), Ui.WHITE))
        box.addView(Button(this).apply {
            setText(button)
            setTextColor(Ui.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Ui.RED)
            setOnClickListener { onClick() }
        })
        box.addView(Ui.body(this, getString(R.string.permission_settings)))
    }

    private fun textField(value: String, multiLine: Boolean, onChange: (String) -> Unit): EditText =
        EditText(this).apply {
            setText(value)
            setTextColor(Ui.WHITE)
            setHintTextColor(Ui.MUTED)
            setHint(R.string.text_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                (if (multiLine) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
            })
        }

    private fun set(updated: WidgetConfig) {
        config = updated
        updatePreview()
    }

    private fun updatePreview() {
        val maxWidth = resources.displayMetrics.widthPixels - dp(32)
        preview.setImageBitmap(Previews.safeRender(this, kind, config, previewWidthDp, previewHeightDp, maxWidth))
    }

    private fun save() {
        WidgetStore.save(this, widgetId, config)
        val manager = AppWidgetManager.getInstance(this)
        manager.updateAppWidget(widgetId, WidgetViews.build(this, manager, widgetId, kind))
        Scheduler.refresh(this)
        setResult(RESULT_OK, resultIntent())
        finish()
    }

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    companion object {
        private const val STEPS_REQUEST = 72
        private const val EVENTS_REQUEST = 73
        private const val PHOTO_REQUEST = 74

        fun intent(context: Context, widgetId: Int): Intent =
            Intent(context, ConfigActivity::class.java)
                .setAction("app.dotwidgets.action.EDIT_$widgetId")
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
    }
}
