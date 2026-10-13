package app.dotwidgets

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.dotwidgets.Ui.dp

/**
 * Two tabs: the widgets already on the home screen (with permission hints and shared settings)
 * and the catalogue of widgets that can be added.
 */
class MainActivity : Activity() {

    private enum class Tab { MINE, AVAILABLE }

    private lateinit var tabBar: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private var tab: Tab? = null
    private var lastPlacedCount = -1
    private var wallpaperStyle = WallpaperStyle.BLACK
    private var wallpaperTarget = Wallpapers.TARGET_BOTH

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = Ui.column(this)
        scroll = ScrollView(this).apply { addView(content) }
        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tab = savedInstanceState?.getString(STATE_TAB)?.let { Tab.valueOf(it) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BLACK)
            fitsSystemWindows = true
            addView(Ui.title(this@MainActivity, getString(R.string.app_name)).apply {
                setPadding(dp(20), dp(16), dp(20), dp(4))
            })
            addView(tabBar)
            addView(android.view.View(this@MainActivity).apply { setBackgroundColor(0xFF262626.toInt()) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        tab?.let { outState.putString(STATE_TAB, it.name) }
    }

    // Rebuilt on every resume: previews show the current time and any widget added meanwhile.
    override fun onResume() {
        super.onResume()
        render()
    }

    private fun placedWidgets(): List<Pair<WidgetKind, Int>> {
        val manager = AppWidgetManager.getInstance(this)
        return WidgetKind.entries.flatMap { kind -> Scheduler.ids(this, manager, kind).map { kind to it } }
    }

    private fun render() {
        val placed = placedWidgets()
        // First open: start where there is something to see. After adding a widget: show it.
        if (tab == null) tab = if (placed.isEmpty()) Tab.AVAILABLE else Tab.MINE
        if (lastPlacedCount >= 0 && placed.size > lastPlacedCount) tab = Tab.MINE
        lastPlacedCount = placed.size

        renderTabs(placed.size)
        content.removeAllViews()
        when (tab) {
            Tab.AVAILABLE -> renderAvailable(placed)
            else -> renderMine(placed)
        }
    }

    private fun selectTab(selected: Tab) {
        if (tab == selected) return
        tab = selected
        render()
        scroll.scrollTo(0, 0)
    }

    private fun renderTabs(placedCount: Int) {
        tabBar.removeAllViews()
        val labels = listOf(
            Tab.MINE to getString(R.string.tab_mine, placedCount),
            Tab.AVAILABLE to getString(R.string.tab_available, WidgetKind.entries.size),
        )
        for ((value, label) in labels) {
            val selected = value == tab
            tabBar.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(10), 0, dp(8))
                isClickable = true
                contentDescription = label
                setOnClickListener { selectTab(value) }
                addView(TextView(this@MainActivity).apply {
                    text = label
                    typeface = resources.getFont(R.font.doto_bold)
                    textSize = 15f
                    letterSpacing = 0.08f
                    isAllCaps = true
                    setTextColor(if (selected) Ui.WHITE else Ui.MUTED)
                })
                // Nothing-style indicator: a red dot under the active tab.
                addView(android.view.View(this@MainActivity).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(if (selected) Ui.RED else 0)
                    }
                }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { topMargin = dp(6) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun renderMine(placed: List<Pair<WidgetKind, Int>>) {
        addErrorReport()
        if (!Scheduler.canTickExactly(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            content.addView(Ui.body(this, getString(R.string.exact_alarm_hint), Ui.WHITE))
            content.addView(button(getString(R.string.exact_alarm_button)) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            })
        }
        if (MusicViews.hasPlaced(this) && !MediaState.hasAccess(this)) {
            content.addView(Ui.body(this, getString(R.string.music_access_hint), Ui.WHITE).apply { setPadding(0, dp(8), 0, 0) })
            content.addView(button(getString(R.string.music_access_button)) { MediaState.openAccessSettings(this) })
            content.addView(Ui.body(this, getString(R.string.music_access_restricted)))
        }

        if (placed.isEmpty()) {
            content.addView(Ui.body(this, getString(R.string.no_widgets)))
            content.addView(button(getString(R.string.show_available)) { selectTab(Tab.AVAILABLE) })
        } else {
            content.addView(Ui.body(this, getString(R.string.hint_mine)))
        }
        val manager = AppWidgetManager.getInstance(this)
        for ((kind, id) in placed) {
            val config = WidgetStore.load(this, id, kind)
            val (w, h) = WidgetViews.sizeDp(manager.getAppWidgetOptions(id), kind)
            val style = getString(kind.styles[config.style.coerceIn(0, kind.styles.size - 1)])
            content.addView(row(kind, Previews.safeRender(this, kind, config, w, h, dp(140)), style, getString(R.string.edit)) {
                startActivity(ConfigActivity.intent(this, id))
            })
        }

        content.addView(Ui.section(this, R.string.section_settings))
        content.addView(Ui.section(this, R.string.section_weather).apply { textSize = 13f })
        content.addView(WeatherUi.section(this) { render() })
        if (Wallpapers.canSet(this)) addWallpaperSection()
        addBackupSection()
    }

    private fun addBackupSection() {
        content.addView(Ui.section(this, R.string.section_backup).apply { textSize = 13f })
        content.addView(Ui.body(this, getString(R.string.backup_hint)))
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
            addView(button(getString(R.string.backup_export)) {
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "dot-widgets-copia.json"),
                    EXPORT_REQUEST,
                )
            })
            addView(android.view.View(this@MainActivity), LinearLayout.LayoutParams(dp(12), 1))
            addView(button(getString(R.string.backup_import)) {
                // Some file managers label .json files as text or binary, so accept those too.
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream")),
                    IMPORT_REQUEST,
                )
            })
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            EXPORT_REQUEST -> {
                val ok = runCatching {
                    val json = Backup.export(this)
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) }
                }.onFailure { CrashLog.record(this, "exportar copia", it) }.isSuccess
                Toast.makeText(this, if (ok) R.string.backup_exported else R.string.backup_failed, Toast.LENGTH_SHORT).show()
            }
            IMPORT_REQUEST -> {
                val applied = runCatching {
                    val json = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                    Backup.import(this, json)
                }.onFailure { CrashLog.record(this, "importar copia", it) }.getOrNull()
                if (applied == null) {
                    Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.backup_imported, applied), Toast.LENGTH_LONG).show()
                    render()
                }
            }
        }
    }

    /** Recent errors, copyable, so a problem on the phone can be reported without a computer. */
    private fun addErrorReport() {
        val log = CrashLog.read(this)
        if (log.isEmpty()) return
        content.addView(Ui.section(this, R.string.section_errors))
        content.addView(Ui.body(this, getString(R.string.errors_hint), Ui.WHITE))
        content.addView(TextView(this).apply {
            text = log.take(1500)
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Ui.MUTED)
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        })
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(getString(R.string.errors_copy)) {
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Dot Widgets", log))
                Toast.makeText(this@MainActivity, R.string.errors_copied, Toast.LENGTH_SHORT).show()
            })
            addView(Button(this@MainActivity).apply {
                setText(R.string.errors_clear)
                setTextColor(Ui.MUTED)
                setBackgroundColor(0)
                setOnClickListener {
                    CrashLog.clear(this@MainActivity)
                    render()
                }
            })
        })
    }

    private fun renderAvailable(placed: List<Pair<WidgetKind, Int>>) {
        content.addView(Ui.body(this, getString(R.string.hint)))
        for (kind in WidgetKind.entries) {
            val preview = Previews.safeRender(this, kind, WidgetConfig.defaults(kind), kind.widthDp, kind.heightDp, dp(140))
            val inUse = placed.count { it.first == kind }
            val note = if (inUse > 0) getString(R.string.in_use, inUse) else getString(kind.description)
            content.addView(row(kind, preview, note, getString(R.string.add_widget)) { pin(kind) })
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        WeatherUi.onPermissionResult(this, requestCode, grantResults)
    }

    /** Plain wallpapers (black by default) so the widgets stand out on the home screen. */
    private fun addWallpaperSection() {
        content.addView(Ui.section(this, R.string.section_wallpaper).apply { textSize = 13f })
        content.addView(Ui.body(this, getString(R.string.wallpaper_hint)))

        val (screenW, screenH) = Wallpapers.screenSize(this)
        val thumbW = dp(84)
        val thumbH = (thumbW.toFloat() * screenH / screenW).toInt()
        val thumbnail = ImageView(this)
        fun showThumbnail() {
            thumbnail.setImageBitmap(Wallpapers.render(this, wallpaperStyle, thumbW, thumbH, thumbW.toFloat() / screenW))
        }
        showThumbnail()

        val options = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(Ui.choices(this@MainActivity, WallpaperStyle.entries.map { getString(it.label) }, wallpaperStyle.ordinal) {
                wallpaperStyle = WallpaperStyle.entries[it]
                showThumbnail()
            })
            addView(Ui.choices(
                this@MainActivity,
                listOf(getString(R.string.wallpaper_both), getString(R.string.wallpaper_home), getString(R.string.wallpaper_lock)),
                wallpaperTarget,
            ) { wallpaperTarget = it })
            addView(button(getString(R.string.wallpaper_apply)) { confirmWallpaper() })
        }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
            addView(thumbnail, LinearLayout.LayoutParams(thumbW, thumbH))
            addView(options, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        content.addView(Button(this).apply {
            setText(R.string.wallpaper_restore)
            setTextColor(Ui.MUTED)
            setBackgroundColor(0)
            setOnClickListener { confirmRestore() }
        })
    }

    private fun confirmWallpaper() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.wallpaper_apply)
            .setMessage(R.string.wallpaper_confirm)
            .setPositiveButton(R.string.wallpaper_apply) { _, _ ->
                val style = wallpaperStyle
                val target = wallpaperTarget
                inBackground(R.string.wallpaper_done) { Wallpapers.apply(this, style, target) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRestore() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.wallpaper_restore)
            .setMessage(R.string.wallpaper_restore_confirm)
            .setPositiveButton(R.string.wallpaper_restore) { _, _ ->
                inBackground(R.string.wallpaper_restored) { Wallpapers.restoreDefault(this) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // Writing a full-screen wallpaper takes a moment; keep it off the main thread.
    private fun inBackground(doneMessage: Int, work: () -> Unit) {
        Thread {
            val ok = runCatching(work).isSuccess
            runOnUiThread {
                Toast.makeText(this, if (ok) doneMessage else R.string.wallpaper_failed, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun row(kind: WidgetKind, preview: android.graphics.Bitmap, subtitle: String, action: String, onClick: () -> Unit): LinearLayout {
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
            addView(Ui.title(this@MainActivity, getString(kind.title), 18f))
            addView(Ui.body(this@MainActivity, subtitle))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(ImageView(this@MainActivity).apply {
                setImageBitmap(preview)
                adjustViewBounds = true
            }, LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button(action, onClick))
        }
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(Ui.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(Ui.RED)
        setOnClickListener { onClick() }
    }

    private fun pin(kind: WidgetKind) {
        val manager = AppWidgetManager.getInstance(this)
        if (!manager.isRequestPinAppWidgetSupported) {
            Toast.makeText(this, R.string.pin_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        // Open the editor once the widget lands; the launcher fills in EXTRA_APPWIDGET_ID, hence MUTABLE.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or mutable
        val onPinned = PendingIntent.getActivity(
            this,
            kind.ordinal,
            Intent(this, ConfigActivity::class.java).setAction("app.dotwidgets.action.PINNED_${kind.name}"),
            flags,
        )
        manager.requestPinAppWidget(ComponentName(this, kind.provider), null, onPinned)
    }

    private companion object {
        const val STATE_TAB = "tab"
        const val EXPORT_REQUEST = 81
        const val IMPORT_REQUEST = 82
    }
}
