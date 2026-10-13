package app.dotwidgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import kotlin.math.min
import kotlin.math.sqrt

/** What is playing right now, read from the system media session (Spotify or any other player). */
data class MediaInfo(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val sessionActivity: PendingIntent?,
)

object MediaState {
    private const val SPOTIFY = "com.spotify.music"

    private fun listener(context: Context) = ComponentName(context, MediaListenerService::class.java)

    /** Android only shares media sessions with apps that hold notification access. */
    fun hasAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        val me = listener(context)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun accessIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener(context).flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }

    fun openAccessSettings(context: Context) {
        try {
            context.startActivity(accessIntent(context))
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    /** The playing session if there is one, otherwise the most recent paused one. */
    fun controller(context: Context): MediaController? {
        if (!hasAccess(context)) return null
        val sessions = try {
            context.getSystemService(MediaSessionManager::class.java).getActiveSessions(listener(context))
        } catch (e: SecurityException) {
            return null
        }
        return sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
    }

    fun current(context: Context): MediaInfo? {
        val controller = controller(context) ?: return null
        val metadata = controller.metadata ?: return null
        val state = controller.playbackState
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val position = state?.let {
            if (playing) it.position + ((SystemClock.elapsedRealtime() - it.lastPositionUpdateTime) * it.playbackSpeed).toLong() else it.position
        } ?: 0L
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty()
        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        return MediaInfo(
            title = title,
            artist = artist,
            art = art,
            playing = playing,
            positionMs = position.coerceAtLeast(0L),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            sessionActivity = controller.sessionActivity,
        )
    }

    /** Spotify if installed (declared in <queries>), otherwise this app. */
    fun launchIntent(context: Context): Intent =
        context.packageManager.getLaunchIntentForPackage(SPOTIFY) ?: Intent(context, MainActivity::class.java)
}

/** Keeps a callback on every active media session so the widgets change the moment the song does. */
class MediaListenerService : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private val tracked = mutableListOf<MediaController>()
    private val refresh = Runnable { Scheduler.update(this, listOf(WidgetKind.MUSIC)) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = scheduleRefresh()

        override fun onPlaybackStateChanged(state: PlaybackState?) = scheduleRefresh()

        override fun onSessionDestroyed() = scheduleRefresh()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { track(it.orEmpty()) }

    override fun onListenerConnected() {
        val manager = getSystemService(MediaSessionManager::class.java)
        val me = ComponentName(this, MediaListenerService::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, me, handler)
            track(manager.getActiveSessions(me))
        } catch (e: SecurityException) {
            scheduleRefresh()
        }
    }

    override fun onListenerDisconnected() {
        getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionsListener)
        tracked.forEach { it.unregisterCallback(callback) }
        tracked.clear()
        scheduleRefresh()
    }

    private fun track(controllers: List<MediaController>) {
        tracked.forEach { it.unregisterCallback(callback) }
        tracked.clear()
        controllers.forEach {
            it.registerCallback(callback, handler)
            tracked.add(it)
        }
        scheduleRefresh()
    }

    // Players can report state several times in a row; redraw once they settle.
    private fun scheduleRefresh() {
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 250)
    }
}

/** Previous / play-pause / next taps from the widget. */
class MediaActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = MediaState.controller(context)
        if (controller == null) {
            // No session yet: a media key lets the last player (e.g. Spotify) resume.
            if (intent.action == ACTION_PLAY_PAUSE) sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
            return
        }
        val controls = controller.transportControls
        when (intent.action) {
            ACTION_PREVIOUS -> controls.skipToPrevious()
            ACTION_NEXT -> controls.skipToNext()
            ACTION_PLAY_PAUSE ->
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
        }
    }

    private fun sendMediaKey(context: Context, code: Int) {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    companion object {
        const val ACTION_PREVIOUS = "app.dotwidgets.action.MEDIA_PREVIOUS"
        const val ACTION_PLAY_PAUSE = "app.dotwidgets.action.MEDIA_PLAY_PAUSE"
        const val ACTION_NEXT = "app.dotwidgets.action.MEDIA_NEXT"

        fun pending(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, MediaActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}

/** The music widget uses real layouts (not one bitmap) so each control is its own tap target. */
object MusicViews {
    const val STYLE_COMPACT = 0
    const val STYLE_LARGE = 1
    const val STYLE_COVER = 2

    // Art, text and icons together stay well under the ~1 MB RemoteViews transaction limit.
    private const val MAX_PIXELS = 170_000f
    private const val PADDING_DP = 12
    private const val CONTROLS_DP = 96
    private const val CONTROL_ROW_DP = 40
    private const val GAP_DP = 10

    fun build(context: Context, options: android.os.Bundle?, id: Int, config: WidgetConfig, palette: Palette): RemoteViews {
        val (widthDp, heightDp) = WidgetViews.sizeDp(options, WidgetKind.MUSIC)
        val innerW = (widthDp - 2 * PADDING_DP).coerceAtLeast(40)
        val innerH = (heightDp - 2 * PADDING_DP).coerceAtLeast(40)
        val access = MediaState.hasAccess(context)
        val info = if (access) MediaState.current(context) else null

        val layout: Int
        val artDp: Int
        var infoW = 0
        var infoH = 0
        when (config.style) {
            STYLE_COMPACT -> {
                layout = R.layout.widget_music_compact
                artDp = innerH
                infoW = innerW - artDp - CONTROLS_DP - 2 * GAP_DP
                infoH = innerH
            }
            STYLE_COVER -> {
                layout = R.layout.widget_music_cover
                artDp = min(innerW, innerH)
            }
            else -> {
                layout = R.layout.widget_music_large
                artDp = innerH - CONTROL_ROW_DP
                infoW = innerW - artDp - GAP_DP
                infoH = artDp
            }
        }
        val density = context.resources.displayMetrics.density
        // The art is big dots and tolerates a low resolution; the small dotted text does not,
        // so next to text the art gets at most 1.6 px/dp and the text takes the rest of the budget.
        val artPx = if (config.style == STYLE_COVER) min(density, sqrt(MAX_PIXELS) / artDp) else min(density, 1.6f)
        val artPixels = (artDp * artPx) * (artDp * artPx)
        // Text area plus room for a few ticker pages of the title.
        val infoArea = (infoW.coerceAtLeast(1) * infoH.coerceAtLeast(1)).toFloat() * 1.6f
        val px = min(density, sqrt((MAX_PIXELS - artPixels).coerceAtLeast(20_000f) / infoArea))
        val renderer = Renderer(context, config, palette, pxPerDp = px)
        // Icon dots grow with the widget's height but stay between 2.2 and 3.4 dp (4 dp for the cover button).
        // Icons are drawn at full density and shown unscaled (scaleType="center").
        val iconCell = density * if (config.style == STYLE_COVER) (artDp / 24f).coerceIn(2.4f, 4f) else (innerH / 16f).coerceIn(2.2f, 3.4f)

        val views = RemoteViews(context.packageName, layout)
        views.setImageViewResource(R.id.bg, WidgetViews.cornerDrawable(config.corner))
        views.setInt(R.id.bg, "setColorFilter", palette.bg)
        views.setInt(R.id.bg, "setImageAlpha", config.bgAlpha * 255 / 100)
        views.setImageViewBitmap(R.id.art, renderer.albumArt(info?.art, (artDp * artPx).toInt()))

        val playGlyph = if (info?.playing == true) Renderer.Glyph.PAUSE else Renderer.Glyph.PLAY
        views.setImageViewBitmap(R.id.play, renderer.glyph(playGlyph, iconCell))
        views.setOnClickPendingIntent(R.id.play, MediaActionReceiver.pending(context, MediaActionReceiver.ACTION_PLAY_PAUSE, 21))
        if (config.style == STYLE_COVER) {
            views.setInt(R.id.play_bg, "setColorFilter", palette.bg)
            views.setInt(R.id.play_bg, "setImageAlpha", 210)
        } else {
            val progress = config.style == STYLE_LARGE
            val textW = (infoW * px).toInt()
            val areaH = (infoH * px).toInt()
            val pages = renderer.musicTitlePages(textW, areaH, info, access, progress)
            if (pages.size == 1) {
                views.setViewVisibility(R.id.title_static, View.VISIBLE)
                views.setViewVisibility(R.id.title_flipper, View.GONE)
                views.setImageViewBitmap(R.id.title_static, pages[0])
            } else {
                // Long title: pages slide in one after another (ViewFlipper, autoStart in the layout).
                views.setViewVisibility(R.id.title_static, View.GONE)
                views.setViewVisibility(R.id.title_flipper, View.VISIBLE)
                views.removeAllViews(R.id.title_flipper)
                for (page in pages) {
                    views.addView(R.id.title_flipper, RemoteViews(context.packageName, R.layout.music_title_page).apply {
                        setImageViewBitmap(R.id.page, page)
                    })
                }
            }
            views.setImageViewBitmap(R.id.info, renderer.musicDetails(textW, areaH, info, access, progress))
            views.setImageViewBitmap(R.id.prev, renderer.glyph(Renderer.Glyph.PREVIOUS, iconCell))
            views.setImageViewBitmap(R.id.next, renderer.glyph(Renderer.Glyph.NEXT, iconCell))
            views.setOnClickPendingIntent(R.id.prev, MediaActionReceiver.pending(context, MediaActionReceiver.ACTION_PREVIOUS, 20))
            views.setOnClickPendingIntent(R.id.next, MediaActionReceiver.pending(context, MediaActionReceiver.ACTION_NEXT, 22))
        }

        val open = when {
            !access -> activity(context, id, Intent(context, MainActivity::class.java))
            info?.sessionActivity != null -> info.sessionActivity
            else -> activity(context, id, MediaState.launchIntent(context))
        }
        views.setOnClickPendingIntent(R.id.root, open)
        views.setOnClickPendingIntent(R.id.art, open)
        return views
    }

    private fun activity(context: Context, id: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            id,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun hasPlaced(context: Context): Boolean =
        Scheduler.ids(context, AppWidgetManager.getInstance(context), WidgetKind.MUSIC).isNotEmpty()
}
