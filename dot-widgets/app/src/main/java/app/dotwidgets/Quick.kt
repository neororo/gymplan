package app.dotwidgets

import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.RemoteViews
import kotlin.math.min

/** The four quick toggles. Wi-Fi and Bluetooth can only be opened, not switched, by a normal app. */
enum class QuickToggle { TORCH, WIFI, BLUETOOTH, DND }

object QuickState {
    fun isOn(context: Context, toggle: QuickToggle): Boolean = runCatching {
        when (toggle) {
            QuickToggle.TORCH -> context.getSharedPreferences("quick", Context.MODE_PRIVATE).getBoolean("torch", false)
            QuickToggle.WIFI -> context.applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled
            QuickToggle.BLUETOOTH -> context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
            QuickToggle.DND -> context.getSystemService(NotificationManager::class.java).currentInterruptionFilter >
                NotificationManager.INTERRUPTION_FILTER_ALL
        }
    }.getOrDefault(false)

    fun hasDndAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun setTorch(context: Context, on: Boolean) {
        context.getSharedPreferences("quick", Context.MODE_PRIVATE).edit().putBoolean("torch", on).apply()
    }
}

/** Taps on the torch and Do Not Disturb buttons. */
class QuickActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TORCH -> toggleTorch(context)
            ACTION_DND -> {
                runCatching {
                    val manager = context.getSystemService(NotificationManager::class.java)
                    val on = QuickState.isOn(context, QuickToggle.DND)
                    manager.setInterruptionFilter(
                        if (on) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                    )
                }.onFailure { CrashLog.record(context, "No molestar", it) }
                Scheduler.update(context, listOf(WidgetKind.QUICK))
            }
        }
    }

    /** Asks the system for the real torch state first, so the button never gets out of sync. */
    private fun toggleTorch(context: Context) {
        val camera = context.getSystemService(CameraManager::class.java)
        val id = runCatching {
            camera.cameraIdList.firstOrNull {
                camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: return
        val pending = goAsync()
        val handler = Handler(Looper.getMainLooper())
        var done = false
        lateinit var callback: CameraManager.TorchCallback
        fun finish() {
            if (done) return
            done = true
            camera.unregisterTorchCallback(callback)
            Scheduler.update(context, listOf(WidgetKind.QUICK))
            pending.finish()
        }
        callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId != id || done) return
                runCatching { camera.setTorchMode(id, !enabled) }
                    .onSuccess { QuickState.setTorch(context, !enabled) }
                    .onFailure { CrashLog.record(context, "linterna", it) }
                finish()
            }

            override fun onTorchModeUnavailable(cameraId: String) {
                if (cameraId == id) finish()
            }
        }
        camera.registerTorchCallback(callback, handler)
        handler.postDelayed({ finish() }, 1_500)
    }

    companion object {
        const val ACTION_TORCH = "app.dotwidgets.action.TORCH"
        const val ACTION_DND = "app.dotwidgets.action.DND"
    }
}

/** Four round buttons, each a separate tap target, drawn as dot glyphs. */
object QuickViews {
    private val IDS = intArrayOf(R.id.q0, R.id.q1, R.id.q2, R.id.q3)

    fun build(context: Context, options: Bundle?, config: WidgetConfig, palette: Palette): RemoteViews {
        val (widthDp, heightDp) = WidgetViews.sizeDp(options, WidgetKind.QUICK)
        val density = context.resources.displayMetrics.density
        val cellDp = min((widthDp - 24) / 4f, (heightDp - 24).toFloat()).coerceAtLeast(24f)
        val size = (cellDp * min(density, 2.5f)).toInt()
        val renderer = Renderer(context, config, palette)
        return RemoteViews(context.packageName, R.layout.widget_quick).apply {
            setImageViewResource(R.id.bg, WidgetViews.cornerDrawable(config.corner))
            setInt(R.id.bg, "setColorFilter", palette.bg)
            setInt(R.id.bg, "setImageAlpha", config.bgAlpha * 255 / 100)
            QuickToggle.entries.forEachIndexed { i, toggle ->
                setImageViewBitmap(IDS[i], renderer.quickButton(toggle, QuickState.isOn(context, toggle), size))
                setOnClickPendingIntent(IDS[i], tapIntent(context, toggle))
            }
        }
    }

    private fun tapIntent(context: Context, toggle: QuickToggle): PendingIntent = when (toggle) {
        QuickToggle.TORCH -> broadcast(context, QuickActionReceiver.ACTION_TORCH, 40)
        QuickToggle.WIFI -> activity(
            context,
            41,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS),
        )
        QuickToggle.BLUETOOTH -> activity(context, 42, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        QuickToggle.DND ->
            if (QuickState.hasDndAccess(context)) {
                broadcast(context, QuickActionReceiver.ACTION_DND, 43)
            } else {
                activity(context, 43, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
    }

    private fun broadcast(context: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        context,
        code,
        Intent(context, QuickActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun activity(context: Context, code: Int, intent: Intent) = PendingIntent.getActivity(
        context,
        code,
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
