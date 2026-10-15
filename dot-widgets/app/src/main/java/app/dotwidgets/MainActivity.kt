package app.dotwidgets

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.dotwidgets.Ui.dp

/**
 * Three tabs, as in the approved mockup: the widgets on the home screen (cards, tap to edit), the
 * catalogue (two-column grid with a + button) and settings (rows that open bottom sheets).
 */
class MainActivity : Activity() {

    private enum class Tab { MINE, ADD, SETTINGS }

    /** A permission some placed widget needs, for the notice and the Permisos sheet. */
    private class Need(val title: Int, val description: Int, val granted: Boolean, val needed: Boolean, val unavailable: Int? = null, val action: () -> Unit)

    private lateinit var tabBar: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private var tab: Tab? = null
    private var lastPlacedCount = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }
        scroll = ScrollView(this).apply { addView(content) }
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        tab = savedInstanceState?.getString(STATE_TAB)?.let { name -> Tab.entries.firstOrNull { it.name == name } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BLACK)
            fitsSystemWindows = true
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_name)
                typeface = Design.dots(this@MainActivity)
                textSize = 34f
                isAllCaps = true
                setTextColor(Ui.WHITE)
                setPadding(dp(20), dp(20), dp(20), dp(8))
            })
            addView(tabBar)
            addView(View(this@MainActivity).apply { setBackgroundColor(Design.LINE) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        tab?.let { outState.putString(STATE_TAB, it.name) }
    }

    // Rebuilt on every resume: previews show the current state and any widget added meanwhile.
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
        if (tab == null) tab = if (placed.isEmpty()) Tab.ADD else Tab.MINE
        if (lastPlacedCount >= 0 && placed.size > lastPlacedCount) tab = Tab.MINE
        lastPlacedCount = placed.size

        renderTabs(placed.size)
        content.removeAllViews()
        when (tab) {
            Tab.ADD -> renderAdd(placed)
            Tab.SETTINGS -> renderSettings(placed)
            else -> renderMine(placed)
        }
    }

    private fun selectTab(selected: Tab) {
        if (tab == selected) return
        tab = selected
        render()
        scroll.scrollTo(0, 0)
    }

    // ---- Tabs --------------------------------------------------------------------------------

    private fun renderTabs(placedCount: Int) {
        tabBar.removeAllViews()
        val tabs = listOf(
            Triple(Tab.MINE, getString(R.string.tab_widgets), placedCount.toString()),
            Triple(Tab.ADD, getString(R.string.tab_add), WidgetKind.entries.size.toString()),
            Triple(Tab.SETTINGS, getString(R.string.tab_settings), ""),
        )
        for ((value, label, count) in tabs) {
            val selected = value == tab
            val labelRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(TextView(this@MainActivity).apply {
                    text = label
                    typeface = Design.dots(this@MainActivity)
                    textSize = 15f
                    isAllCaps = true
                    maxLines = 1
                    setTextColor(if (selected) Ui.WHITE else Design.GRAY)
                })
                if (count.isNotEmpty()) {
                    addView(Design.text(this@MainActivity, count, 11f, if (selected) Ui.RED else Design.DIM, medium = true).apply {
                        setPadding(dp(4), 0, 0, dp(6))
                    })
                }
            }
            tabBar.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(12), 0, dp(10))
                contentDescription = label
                addView(labelRow)
                // Nothing-style indicator: a red dot under the active tab.
                addView(View(this@MainActivity).apply {
                    background = Design.rounded(if (selected) Ui.RED else 0, 3f, this@MainActivity)
                }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { topMargin = dp(6) })
                Design.pressable(this, android.graphics.drawable.ColorDrawable(0)) { selectTab(value) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // ---- Tab: your widgets -------------------------------------------------------------------

    private fun renderMine(placed: List<Pair<WidgetKind, Int>>) {
        val pending = needs(placed).count { it.needed && !it.granted && it.unavailable == null }
        if (pending > 0) content.addView(permissionNotice(pending))

        if (placed.isEmpty()) {
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(16), dp(64), dp(16), dp(16))
                addView(Design.dotName(this@MainActivity, getString(R.string.empty_title), 26f))
                addView(Design.text(this@MainActivity, getString(R.string.no_widgets), 15f, Design.GRAY).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(12), 0, dp(24))
                })
                addView(Design.primaryButton(this@MainActivity, getString(R.string.show_available)) { selectTab(Tab.ADD) })
            })
            return
        }

        content.addView(hint(R.string.hint_mine))
        val manager = AppWidgetManager.getInstance(this)
        val previewWidth = resources.displayMetrics.widthPixels - dp(80)
        for ((kind, id) in placed) {
            val config = WidgetStore.load(this, id, kind)
            val (w, h) = WidgetViews.sizeDp(manager.getAppWidgetOptions(id), kind)
            val style = getString(kind.styles[config.style.coerceIn(0, kind.styles.size - 1)])
            val detail = if (kind == WidgetKind.WEATHER) WeatherStore.place(this)?.name else Palettes.find(this, config.palette).name
            content.addView(widgetCard(kind, Previews.safeRender(this, kind, config, w, h, previewWidth), listOfNotNull(style, detail).joinToString(" · ")) {
                startActivity(ConfigActivity.intent(this, id))
            })
        }
    }

    private fun widgetCard(kind: WidgetKind, preview: android.graphics.Bitmap, subtitle: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(12), dp(14))
            Design.pressable(this, Design.rounded(Design.CARD, 18f, this@MainActivity), onClick)
            addView(ImageView(this@MainActivity).apply {
                setImageBitmap(preview)
                adjustViewBounds = true
                maxHeight = dp(170)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Design.dotName(this@MainActivity, getString(kind.title), 18f))
                    addView(Design.text(this@MainActivity, subtitle, 13f, Design.GRAY).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(4), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Design.chevron(this@MainActivity))
            })
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }

    private fun permissionNotice(count: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(12), dp(14))
        Design.pressable(this, Design.rounded(Design.CARD, 16f, this@MainActivity, Ui.RED, 1)) { openPermissions() }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(Design.text(this@MainActivity, getString(R.string.notice_permissions_title), 15f, medium = true))
            addView(Design.text(this@MainActivity, resources.getQuantityString(R.plurals.notice_permissions, count, count), 13f, Design.GRAY))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(Design.chevron(this@MainActivity))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        }
    }

    private fun hint(text: Int) = Design.text(this, getString(text), 14f, Design.GRAY).apply {
        setPadding(dp(4), dp(4), dp(4), dp(14))
    }

    // ---- Tab: add ----------------------------------------------------------------------------

    private fun renderAdd(placed: List<Pair<WidgetKind, Int>>) {
        content.addView(hint(R.string.hint_add))
        val gap = dp(10)
        val cardWidth = (resources.displayMetrics.widthPixels - dp(32) - gap) / 2
        WidgetKind.entries.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, kind ->
                val inUse = placed.count { it.first == kind }
                val preview = Previews.safeRender(this, kind, WidgetConfig.defaults(kind), kind.widthDp, kind.heightDp, cardWidth - dp(24))
                row.addView(catalogueCard(kind, preview, inUse), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i == 0) marginEnd = gap / 2 else marginStart = gap / 2
                })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = gap / 2 })
            content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = gap
            })
        }
    }

    private fun catalogueCard(kind: WidgetKind, preview: android.graphics.Bitmap, inUse: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(10), dp(12))
        Design.pressable(this, Design.rounded(Design.CARD, 18f, this@MainActivity)) { pin(kind) }
        addView(FrameLayout(this@MainActivity).apply {
            addView(ImageView(this@MainActivity).apply {
                setImageBitmap(preview)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)).apply { bottomMargin = dp(10) })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(Design.dotName(this@MainActivity, getString(kind.title), 14f))
                addView(Design.text(this@MainActivity, if (inUse > 0) getString(R.string.in_use_short) else " ", 11f, Design.GRAY).apply {
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "+"
                textSize = 22f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(Ui.WHITE)
                typeface = Design.sansMedium
                contentDescription = getString(R.string.add_widget)
                Design.pressable(this, Design.rounded(Ui.RED, 18f, this@MainActivity)) { pin(kind) }
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
        })
    }

    // ---- Tab: settings -----------------------------------------------------------------------

    private fun renderSettings(placed: List<Pair<WidgetKind, Int>>) {
        content.addView(Design.groupLabel(this, getString(R.string.group_weather)))
        content.addView(Design.group(this, listOf(
            Design.row(this, getString(R.string.row_location), WeatherStore.place(this)?.name ?: getString(R.string.value_not_set)) {
                WeatherUi.chooseLocation(this) { render() }
            },
        )))

        if (Wallpapers.canSet(this)) {
            val applied = Wallpapers.applied(this)
            val value = applied?.let { (style, target) -> "${getString(style.label)} · ${targetLabel(target).lowercase()}" }
                ?: getString(R.string.value_not_applied)
            content.addView(Design.groupLabel(this, getString(R.string.group_screen)))
            content.addView(Design.group(this, listOf(Design.row(this, getString(R.string.section_wallpaper), value) { openWallpaper() })))
        }

        val pending = needs(placed).count { it.needed && !it.granted && it.unavailable == null }
        content.addView(Design.groupLabel(this, getString(R.string.group_data)))
        content.addView(Design.group(this, listOf(
            Design.row(this, getString(R.string.section_backup), getString(R.string.row_backup_value)) { openBackup() },
            Design.row(
                this,
                getString(R.string.row_permissions),
                if (pending == 0) getString(R.string.value_all_set) else resources.getQuantityString(R.plurals.pending_permissions, pending, pending),
                if (pending == 0) Design.GRAY else Ui.RED,
            ) { openPermissions() },
        )))

        val errors = CrashLog.read(this).split("\n\n").count { it.isNotBlank() }
        content.addView(Design.groupLabel(this, getString(R.string.group_help)))
        content.addView(Design.group(this, listOf(
            Design.row(
                this,
                getString(R.string.section_errors),
                if (errors == 0) getString(R.string.value_none) else resources.getQuantityString(R.plurals.error_count, errors, errors),
                if (errors == 0) Design.GRAY else Ui.RED,
            ) { openErrors() },
            Design.row(this, getString(R.string.row_about), getString(R.string.row_about_value, versionName())) { openAbout() },
        )))
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    private fun targetLabel(target: Int): String = getString(
        when (target) {
            Wallpapers.TARGET_HOME -> R.string.wallpaper_home
            Wallpapers.TARGET_LOCK -> R.string.wallpaper_lock
            else -> R.string.wallpaper_both
        },
    )

    // ---- Sheets ------------------------------------------------------------------------------

    private fun openWallpaper() {
        var style = Wallpapers.applied(this)?.first ?: WallpaperStyle.BLACK
        var target = Wallpapers.applied(this)?.second ?: Wallpapers.TARGET_BOTH
        Design.sheet(this, getString(R.string.section_wallpaper), getString(R.string.wallpaper_hint)) { body, dialog ->
            val (screenW, screenH) = Wallpapers.screenSize(this)
            val thumbW = (resources.displayMetrics.widthPixels - dp(48) - dp(30)) / 4
            val thumbH = (thumbW.toFloat() * screenH / screenW).toInt().coerceAtMost(dp(150))
            val frames = mutableListOf<View>()
            val labels = mutableListOf<TextView>()
            fun paint() {
                WallpaperStyle.entries.forEachIndexed { i, s ->
                    val selected = s == style
                    frames[i].background = Design.rounded(0, 14f, this, if (selected) Ui.RED else 0xFF505050.toInt(), if (selected) 3 else 1)
                    labels[i].setTextColor(if (selected) Ui.WHITE else Design.GRAY)
                    labels[i].typeface = if (selected) Design.sansMedium else Design.sans
                }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(20), 0, dp(8))
            }
            WallpaperStyle.entries.forEachIndexed { i, s ->
                val frame = FrameLayout(this).apply {
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    addView(ImageView(this@MainActivity).apply {
                        setImageBitmap(Wallpapers.render(this@MainActivity, s, thumbW, thumbH, thumbW.toFloat() / screenW))
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        clipToOutline = true
                        background = Design.rounded(0xFF000000.toInt(), 10f, this@MainActivity)
                    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, thumbH))
                    setOnClickListener {
                        style = s
                        paint()
                    }
                }
                val label = Design.text(this, getString(s.label), 12f, Design.GRAY).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(0, dp(6), 0, 0)
                }
                frames += frame
                labels += label
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(frame)
                    addView(label)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(10)
                })
            }
            paint()
            body.addView(row)
            body.addView(Design.text(this, getString(R.string.wallpaper_target), 13f, Design.GRAY).apply { setPadding(0, dp(12), 0, dp(8)) })
            body.addView(Design.segmented(
                this,
                listOf(getString(R.string.wallpaper_both), getString(R.string.wallpaper_home_only), getString(R.string.wallpaper_lock_only)),
                target,
            ) { target = it })
            body.addView(Design.primaryButton(this, getString(R.string.wallpaper_apply)) {
                confirm(R.string.wallpaper_apply, R.string.wallpaper_confirm) {
                    dialog.dismiss()
                    val chosen = style
                    val where = target
                    inBackground(R.string.wallpaper_done) { Wallpapers.apply(this, chosen, where) }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
            body.addView(Design.textButton(this, getString(R.string.wallpaper_restore)) {
                confirm(R.string.wallpaper_restore, R.string.wallpaper_restore_confirm) {
                    dialog.dismiss()
                    inBackground(R.string.wallpaper_restored) { Wallpapers.restoreDefault(this) }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
    }

    private fun openBackup() {
        Design.sheet(this, getString(R.string.section_backup), getString(R.string.backup_hint)) { body, dialog ->
            body.addView(Design.primaryButton(this, getString(R.string.backup_export)) {
                dialog.dismiss()
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "dot-widgets-copia.json"),
                    EXPORT_REQUEST,
                )
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
            body.addView(Design.secondaryButton(this, getString(R.string.backup_import)) {
                dialog.dismiss()
                // Some file managers label .json files as text or binary, so accept those too.
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream")),
                    IMPORT_REQUEST,
                )
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
    }

    private fun needs(placed: List<Pair<WidgetKind, Int>>): List<Need> {
        fun has(kind: WidgetKind) = placed.any { it.first == kind }
        val stepsStatus = StepCounter.status(this)
        val list = mutableListOf(
            Need(R.string.perm_music, R.string.perm_music_desc, MediaState.hasAccess(this), has(WidgetKind.MUSIC)) {
                MediaState.openAccessSettings(this)
            },
            Need(
                R.string.perm_steps,
                R.string.perm_steps_desc,
                stepsStatus == StepCounter.Status.OK,
                has(WidgetKind.STEPS),
                unavailable = if (stepsStatus == StepCounter.Status.NO_SENSOR) R.string.perm_no_sensor else null,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), STEPS_REQUEST)
                }
            },
            Need(R.string.perm_calendar, R.string.perm_calendar_desc, CalendarEvents.hasPermission(this), has(WidgetKind.EVENTS)) {
                requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), CALENDAR_REQUEST)
            },
            Need(R.string.perm_dnd, R.string.perm_dnd_desc, QuickState.hasDndAccess(this), has(WidgetKind.QUICK)) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Need(R.string.perm_exact, R.string.perm_exact_desc, Scheduler.canTickExactly(this), has(WidgetKind.CLOCK)) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        return list
    }

    private fun openPermissions() {
        Design.sheet(this, getString(R.string.row_permissions), getString(R.string.perm_hint)) { body, dialog ->
            val rows = needs(placedWidgets()).sortedByDescending { it.needed && !it.granted }.map { need ->
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(14), dp(12), dp(14))
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(Design.text(this@MainActivity, getString(need.title), 16f, medium = true))
                        addView(Design.text(this@MainActivity, getString(need.description), 13f, Design.GRAY))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
                    when {
                        need.unavailable != null -> addView(Design.text(this@MainActivity, getString(need.unavailable), 13f, Design.DIM))
                        need.granted -> addView(Design.text(this@MainActivity, getString(R.string.perm_granted_short), 13f, Design.GRAY))
                        else -> addView(Design.secondaryButton(this@MainActivity, getString(R.string.perm_grant)) {
                            dialog.dismiss()
                            need.action()
                        })
                    }
                }
            }
            body.addView(Design.group(this, rows), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(20)
            })
            body.addView(Design.text(this, getString(R.string.music_access_restricted), 12f, Design.DIM).apply { setPadding(0, dp(14), 0, 0) })
        }
    }

    private fun openErrors() {
        val log = CrashLog.read(this)
        Design.sheet(this, getString(R.string.section_errors), getString(if (log.isEmpty()) R.string.errors_none else R.string.errors_hint)) { body, dialog ->
            if (log.isEmpty()) return@sheet
            body.addView(TextView(this).apply {
                text = log.take(4000)
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setTextColor(Design.GRAY)
                setTextIsSelectable(true)
                background = Design.rounded(Design.CARD, 12f, this@MainActivity)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(16), 0, 0)
                addView(Design.primaryButton(this@MainActivity, getString(R.string.errors_copy)) {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Dot Widgets", log))
                    Toast.makeText(this@MainActivity, R.string.errors_copied, Toast.LENGTH_SHORT).show()
                })
                addView(Design.textButton(this@MainActivity, getString(R.string.errors_clear)) {
                    CrashLog.clear(this@MainActivity)
                    dialog.dismiss()
                    render()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            })
        }
    }

    private fun openAbout() {
        Design.sheet(this, getString(R.string.app_name), getString(R.string.row_about_value, versionName())) { body, _ ->
            body.addView(Design.text(this, getString(R.string.about_text), 14f, Design.GRAY).apply {
                setPadding(0, dp(16), 0, 0)
                setLineSpacing(0f, 1.25f)
            })
        }
    }

    private fun openBackupResult(ok: Boolean, message: String) {
        Toast.makeText(this, message, if (ok) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    // ---- Results -----------------------------------------------------------------------------

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
                openBackupResult(ok, getString(if (ok) R.string.backup_exported else R.string.backup_failed))
            }
            IMPORT_REQUEST -> {
                val applied = runCatching {
                    val json = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                    Backup.import(this, json)
                }.onFailure { CrashLog.record(this, "importar copia", it) }.getOrNull()
                if (applied == null) {
                    openBackupResult(false, getString(R.string.backup_failed))
                } else {
                    openBackupResult(true, getString(R.string.backup_imported, applied))
                    render()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        WeatherUi.onPermissionResult(this, requestCode, grantResults)
        when (requestCode) {
            STEPS_REQUEST -> StepCounter.read(this) { Scheduler.update(this, listOf(WidgetKind.STEPS)) }
            CALENDAR_REQUEST -> Scheduler.update(this, listOf(WidgetKind.EVENTS))
        }
        render()
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private fun confirm(title: Int, message: Int, onConfirm: () -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(title) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // Writing a full-screen wallpaper takes a moment; keep it off the main thread.
    private fun inBackground(doneMessage: Int, work: () -> Unit) {
        Thread {
            val ok = runCatching(work).onFailure { CrashLog.record(this, "fondo de pantalla", it) }.isSuccess
            runOnUiThread {
                Toast.makeText(this, if (ok) doneMessage else R.string.wallpaper_failed, Toast.LENGTH_SHORT).show()
                render()
            }
        }.start()
    }

    private fun pin(kind: WidgetKind) {
        val manager = AppWidgetManager.getInstance(this)
        if (!manager.isRequestPinAppWidgetSupported) {
            Toast.makeText(this, R.string.pin_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        // Open the editor once the widget lands; the launcher fills in EXTRA_APPWIDGET_ID, hence MUTABLE.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val onPinned = PendingIntent.getActivity(
            this,
            kind.ordinal,
            Intent(this, ConfigActivity::class.java).setAction("app.dotwidgets.action.PINNED_${kind.name}"),
            PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        )
        manager.requestPinAppWidget(ComponentName(this, kind.provider), null, onPinned)
    }

    private companion object {
        const val STATE_TAB = "tab"
        const val EXPORT_REQUEST = 81
        const val IMPORT_REQUEST = 82
        const val STEPS_REQUEST = 83
        const val CALENDAR_REQUEST = 84
    }
}
