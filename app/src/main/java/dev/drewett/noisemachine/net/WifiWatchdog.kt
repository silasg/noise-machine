package dev.drewett.noisemachine.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.FutureTask

/** Owned by NoiseService. Android queries, preferences and timers run on one worker. */
@Suppress("DEPRECATION")
internal class WifiWatchdog(context: Context) {
    private val app = context.applicationContext
    private val supported = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
    private val worker = HandlerThread(TAG).apply { start() }
    private val handler = Handler(worker.looper)
    @Volatile private var closed = false
    private var registered = false

    private val platform = object : WifiWatchdogController.Wifi {
        private val wifi by lazy { app.getSystemService(WifiManager::class.java) }
        private val connectivity by lazy {
            checkNotNull(app.getSystemService(ConnectivityManager::class.java)) {
                "Android's connectivity service is unavailable."
            }
        }

        override fun link(): WifiWatchdogController.Link {
            check(supported) {
                "Saved-network repair is available on Android 8 and 9. This Android version restricts access to system-saved Wi-Fi networks."
            }
            val manager = checkNotNull(wifi) { "Wi-Fi is unavailable on this device." }
            return when (manager.wifiState) {
                WifiManager.WIFI_STATE_ENABLED -> readLink(manager)
                WifiManager.WIFI_STATE_ENABLING -> WifiWatchdogController.Link.Connecting
                WifiManager.WIFI_STATE_DISABLED, WifiManager.WIFI_STATE_DISABLING -> WifiWatchdogController.Link.Off
                else -> error("Android could not determine whether Wi-Fi is enabled.")
            }
        }

        private fun readLink(manager: WifiManager): WifiWatchdogController.Link {
            val network = connectivity.getNetworkInfo(ConnectivityManager.TYPE_WIFI)
            val info = manager.connectionInfo
            if (network?.isConnected == true) {
                check(info != null && info.networkId >= 0) {
                    "Android did not expose the connected Wi-Fi network ID. Check Wi-Fi in Android Settings."
                }
                return WifiWatchdogController.Link.Connected(info.networkId)
            }
            val transitioning = when (network?.detailedState) {
                NetworkInfo.DetailedState.CONNECTING, NetworkInfo.DetailedState.AUTHENTICATING,
                NetworkInfo.DetailedState.OBTAINING_IPADDR, NetworkInfo.DetailedState.DISCONNECTING,
                NetworkInfo.DetailedState.VERIFYING_POOR_LINK, NetworkInfo.DetailedState.CAPTIVE_PORTAL_CHECK -> true
                else -> false
            }
            val associating = when (info?.supplicantState) {
                SupplicantState.ASSOCIATING, SupplicantState.ASSOCIATED, SupplicantState.AUTHENTICATING,
                SupplicantState.FOUR_WAY_HANDSHAKE, SupplicantState.GROUP_HANDSHAKE, SupplicantState.COMPLETED -> true
                else -> false
            }
            return if (transitioning || associating) WifiWatchdogController.Link.Connecting
                else WifiWatchdogController.Link.Disconnected
        }

        // On AOSP 8/9, ACCESS_WIFI_STATE exposes saved IDs and SSIDs, even if WifiInfo's SSID is redacted.
        override fun savedNetworks(): List<SavedWifiNetwork> =
            wifi?.configuredNetworks.orEmpty().mapNotNull { config ->
                config.SSID?.takeIf { config.networkId >= 0 && it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID }
                    ?.let { SavedWifiNetwork(it, config.networkId) }
            }

        override fun selectNetwork(id: Int): Boolean {
            // Android 8/9's enableNetwork(id, true) already starts a user-selected connection.
            // reconnect() would also ask autojoin to run, which need not select this configuration.
            val manager = wifi ?: return false
            return !closed && manager.isWifiEnabled && manager.enableNetwork(id, true)
        }
    }

    // Store one atomic record independently of concurrent playback and schedule settings writes.
    private val store = object : WifiWatchdogController.Store {
        private val prefs by lazy { app.getSharedPreferences("wifi_watchdog", Context.MODE_PRIVATE) }

        override fun load(): SavedWifiNetwork? = prefs.getString("ssid", null)?.let {
            SavedWifiNetwork(it, prefs.getInt("network_id", -1))
        }

        override fun save(target: SavedWifiNetwork?) {
            val editor = prefs.edit()
            if (target == null) editor.remove("ssid").remove("network_id")
            else editor.putString("ssid", target.ssid).putInt("network_id", target.id)
            check(editor.commit()) { "Could not save Wi-Fi watchdog settings." }
        }
    }

    private val controller = WifiWatchdogController(platform, store, SystemClock::elapsedRealtime,
        System::currentTimeMillis) { Log.i(TAG, it) }
    val snapshot: WifiWatchdogStatus get() = controller.snapshot

    private val timer = Runnable { checkConnection() }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = checkConnection()
    }

    init {
        handler.post {
            if (!closed) {
                controller.restore()
                updateMonitoring()
            }
        }
    }

    /** Called by the HTTP worker so the response includes the result of the change. */
    fun setEnabled(enabled: Boolean): String? {
        val command = FutureTask {
            if (closed) return@FutureTask "Wi-Fi watchdog is stopping."
            val error = controller.setEnabled(enabled)
            if (error == null) updateMonitoring()
            error
        }
        synchronized(this) {
            if (closed || !handler.post(command)) return "Wi-Fi watchdog is stopping."
        }
        return command.get()
    }

    private fun updateMonitoring() {
        if (supported && snapshot.enabled && !registered) {
            try {
                app.registerReceiver(receiver, IntentFilter().apply {
                    addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                    addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                }, null, handler)
                registered = true
            } catch (_: SecurityException) {
                Log.w(TAG, "Android denied Wi-Fi broadcasts, using periodic checks")
            }
        } else if (!snapshot.enabled) {
            stopMonitoring()
        }
        checkConnection()
    }

    private fun checkConnection() {
        if (closed || !snapshot.enabled) return
        handler.removeCallbacks(timer)
        val deadline = controller.check()
        if (!supported) return
        val now = SystemClock.elapsedRealtime()
        val delay = deadline?.minus(now)?.coerceIn(1L, FALLBACK_MS) ?: FALLBACK_MS
        handler.postDelayed(timer, delay)
    }

    private fun stopMonitoring() {
        handler.removeCallbacks(timer)
        if (registered) {
            app.unregisterReceiver(receiver)
            registered = false
        }
    }

    @Synchronized fun close() {
        closed = true
        handler.post {
            stopMonitoring()
            worker.quitSafely()
        }
    }

    private companion object {
        const val TAG = "WifiWatchdog"
        const val FALLBACK_MS = 60_000L
    }
}
