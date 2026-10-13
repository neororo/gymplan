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
import android.widget.Toast
import app.dotwidgets.Ui.dp

/** Catalogue of widgets to add, the widgets already on the home screen, and the clock permission hint. */
class MainActivity : Activity() {

    private lateinit var content: LinearLayout
    private var wallpaperStyle = WallpaperStyle.BLACK
    private var wallpaperTarget = Wallpapers.TARGET_BOTH

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = Ui.column(this)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BLACK)
            fitsSystemWindows = true
            addView(content)
        })
    }

    // Rebuilt on every resume: previews show the current time and any widget added meanwhile.
    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()
        content.addView(Ui.title(this, getString(R.string.app_name)))
        content.addView(Ui.body(this, getString(R.string.hint)))

        if (!Scheduler.canTickExactly(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            content.addView(Ui.body(this, getString(R.string.exact_alarm_hint), Ui.WHITE).apply { setPadding(0, dp(16), 0, 0) })
            content.addView(button(getString(R.string.exact_alarm_button)) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            })
        }

        if (MusicViews.hasPlaced(this) && !MediaState.hasAccess(this)) {
            content.addView(Ui.body(this, getString(R.string.music_access_hint), Ui.WHITE).apply { setPadding(0, dp(16), 0, 0) })
            content.addView(button(getString(R.string.music_access_button)) { MediaState.openAccessSettings(this) })
            content.addView(Ui.body(this, getString(R.string.music_access_restricted)))
        }

        if (Wallpapers.canSet(this)) addWallpaperSection()

        val manager = AppWidgetManager.getInstance(this)
        val placed = WidgetKind.entries.flatMap { kind -> Scheduler.ids(this, manager, kind).map { kind to it } }
        content.addView(Ui.section(this, R.string.section_my_widgets))
        if (placed.isEmpty()) {
            content.addView(Ui.body(this, getString(R.string.no_widgets)))
        }
        for ((kind, id) in placed) {
            val config = WidgetStore.load(this, id, kind)
            val (w, h) = WidgetViews.sizeDp(manager.getAppWidgetOptions(id), kind)
            content.addView(row(kind, Previews.render(this, kind, config, w, h, dp(140)), getString(R.string.edit)) {
                startActivity(ConfigActivity.intent(this, id))
            })
        }

        content.addView(Ui.section(this, R.string.section_add))
        for (kind in WidgetKind.entries) {
            val preview = Previews.render(this, kind, WidgetConfig.defaults(kind), kind.widthDp, kind.heightDp, dp(140))
            content.addView(row(kind, preview, getString(R.string.add_widget)) { pin(kind) })
        }
    }

    /** Plain wallpapers (black by default) so the widgets stand out on the home screen. */
    private fun addWallpaperSection() {
        content.addView(Ui.section(this, R.string.section_wallpaper))
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

    private fun row(kind: WidgetKind, preview: android.graphics.Bitmap, action: String, onClick: () -> Unit): LinearLayout {
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
            addView(Ui.title(this@MainActivity, getString(kind.title), 18f))
            addView(Ui.body(this@MainActivity, getString(kind.description)))
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
}
