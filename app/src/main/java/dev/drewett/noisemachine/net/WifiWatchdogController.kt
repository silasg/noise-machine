package dev.drewett.noisemachine.net

import dev.drewett.noisemachine.net.WifiWatchdogStatus.Status

internal data class SavedWifiNetwork(val ssid: String, val id: Int)

internal data class WifiWatchdogStatus(
    val enabled: Boolean = false,
    val targetSsid: String? = null,
    val status: Status = Status.DISABLED,
    val lastConnectedAtMs: Long? = null,
    val lastAttemptAtMs: Long? = null,
    val lastError: String? = null
) {
    enum class Status {
        DISABLED, CONNECTED, DISCONNECTED, RECONNECTING, WAITING_TO_RETRY, WIFI_OFF, OTHER_NETWORK, ERROR
    }
}

/** Recovery policy, called serially on the watchdog worker. */
internal class WifiWatchdogController(
    private val wifi: Wifi,
    private val store: Store,
    private val elapsedMs: () -> Long,
    private val wallTimeMs: () -> Long,
    private val log: (String) -> Unit
) {
    interface Wifi {
        fun link(): Link
        fun savedNetworks(): List<SavedWifiNetwork>
        fun selectNetwork(id: Int): Boolean
    }

    interface Store {
        fun load(): SavedWifiNetwork?
        fun save(target: SavedWifiNetwork?)
    }

    sealed interface Link {
        data class Connected(val id: Int) : Link
        object Off : Link
        object Connecting : Link
        object Disconnected : Link
    }

    @Volatile var snapshot = WifiWatchdogStatus()
        private set

    private var target: SavedWifiNetwork? = null
    private var retryAt: Long? = null
    private var verifyAt: Long? = null
    private var attempts = 0

    fun restore() {
        try {
            store.load()?.let { start(it, restored = true) }
        } catch (_: RuntimeException) {
            reportError("Could not restore Wi-Fi watchdog settings. Select a saved Wi-Fi network and enable the watchdog again.")
        }
    }

    /** Returns an explanation if the change could not be saved or activated. */
    fun setEnabled(enabled: Boolean): String? {
        if (enabled == (target != null)) {
            if (!enabled) snapshot = WifiWatchdogStatus()
            return null
        }
        return try {
            if (enabled) {
                val link = wifi.link() as? Link.Connected
                    ?: error("Connect the tablet to a saved Wi-Fi network in Android Settings, then try again.")
                val network = wifi.savedNetworks().firstOrNull { it.id == link.id }
                    ?: error("Android did not expose a saved configuration for the connected Wi-Fi network. Select a saved network in Android Settings and try again.")
                store.save(network)
                start(network, restored = false)
            } else {
                store.save(null)
                target = null
                resetRecovery()
                snapshot = WifiWatchdogStatus()
                log("Monitoring disabled")
            }
            null
        } catch (e: RuntimeException) {
            reportError(errorMessage(e))
        }
    }

    private fun start(network: SavedWifiNetwork, restored: Boolean) {
        target = network
        resetRecovery()
        snapshot = WifiWatchdogStatus(
            enabled = true,
            targetSsid = network.ssid.removeSurrounding("\""),
            status = Status.DISCONNECTED
        )
        log(if (restored) "Monitoring restored for saved target" else "Monitoring enabled and target selected")
    }

    /** Returns an elapsed-realtime deadline for the next time-sensitive check, if one is needed. */
    fun check(): Long? {
        val saved = target ?: return null
        return try {
            checkLink(saved, wifi.link())
        } catch (e: RuntimeException) {
            reportError(errorMessage(e))
            null
        }
    }

    private fun checkLink(saved: SavedWifiNetwork, link: Link): Long? {
        val now = elapsedMs()
        when (link) {
            is Link.Connected -> {
                if (snapshot.status == Status.CONNECTED && link.id == saved.id) return null
                resetRecovery()
                val resolved = resolveTarget(saved)
                if (link.id == resolved.id) {
                    snapshot = snapshot.copy(
                        status = Status.CONNECTED,
                        lastConnectedAtMs = wallTimeMs(),
                        lastError = null
                    )
                    log("Target Wi-Fi connection verified")
                } else if (snapshot.status != Status.OTHER_NETWORK) {
                    snapshot = snapshot.copy(status = Status.OTHER_NETWORK)
                    log("Target Wi-Fi disconnected, leaving the current Wi-Fi connection alone")
                }
                return null
            }
            Link.Off -> {
                resetRecovery()
                if (snapshot.status != Status.WIFI_OFF) {
                    snapshot = snapshot.copy(status = Status.WIFI_OFF)
                    log("Wi-Fi is off, recovery suspended")
                }
                return null
            }
            Link.Connecting, Link.Disconnected -> Unit
        }

        if (retryAt == null) {
            log("Target Wi-Fi disconnected, waiting 15 seconds")
            retryAt = now + GRACE_MS
        }
        verifyAt?.let { deadline ->
            if (now < deadline) return deadline
            return failedAttempt("The tablet did not connect to the saved network within 30 seconds.")
        }

        val deadline = checkNotNull(retryAt)
        if (link == Link.Connecting) {
            snapshot = snapshot.copy(status = Status.RECONNECTING)
            return deadline.takeIf { it > now }
        }
        if (now < deadline) {
            snapshot = snapshot.copy(status = if (attempts == 0)
                Status.DISCONNECTED else Status.WAITING_TO_RETRY)
            return deadline
        }

        val resolved = resolveTarget(saved)
        // Reading saved configurations can take time. Check again before selecting a network.
        val latest = wifi.link()
        if (latest != Link.Disconnected) return checkLink(resolved, latest)

        if (attempts > RETRY_MS.size) attempts = 0
        attempts++
        snapshot = snapshot.copy(
            status = Status.RECONNECTING,
            lastAttemptAtMs = wallTimeMs()
        )
        log("Requesting connection to saved Wi-Fi network (attempt $attempts)")
        val accepted = try {
            wifi.selectNetwork(resolved.id)
        } catch (e: RuntimeException) {
            return failedAttempt(errorMessage(e), Status.ERROR)
        }
        if (!accepted) return failedAttempt("Android rejected the saved Wi-Fi connection request.")
        log("Android accepted the request, awaiting a confirmed connection")
        return (elapsedMs() + VERIFY_MS).also { verifyAt = it }
    }

    private fun resolveTarget(saved: SavedWifiNetwork): SavedWifiNetwork {
        val matches = wifi.savedNetworks().filter { it.ssid == saved.ssid }
        val resolved = matches.firstOrNull { it.id == saved.id } ?: matches.singleOrNull()
            ?: error("The target Wi-Fi configuration is missing or ambiguous. Turn off the watchdog, select the saved network in Android Settings, then enable it again.")
        if (resolved != saved) {
            store.save(resolved)
            target = resolved
            log("Resolved updated saved Wi-Fi network ID")
        }
        return resolved
    }

    private fun failedAttempt(
        message: String,
        status: Status = Status.WAITING_TO_RETRY
    ): Long {
        verifyAt = null
        val delay = RETRY_MS.getOrNull(attempts - 1) ?: COOLDOWN_MS
        val deadline = elapsedMs() + delay
        retryAt = deadline
        snapshot = snapshot.copy(status = status, lastError = message)
        log("Repair failed: $message ${if (delay == COOLDOWN_MS) "Cooldown" else "Retry"} in ${delay / 1000}s")
        return deadline
    }

    private fun resetRecovery() {
        retryAt = null
        verifyAt = null
        attempts = 0
    }

    private fun reportError(message: String): String {
        if (snapshot.lastError != message) log(message)
        snapshot = snapshot.copy(status = Status.ERROR, lastError = message)
        return message
    }

    private fun errorMessage(e: RuntimeException): String = when (e) {
        is SecurityException -> "Android denied Wi-Fi access. Check Noise Machine's app permissions and Wi-Fi restrictions on the tablet."
        is IllegalStateException -> e.message ?: "Wi-Fi is currently unavailable."
        else -> "Android could not read or change the Wi-Fi connection."
    }

    private companion object {
        const val GRACE_MS = 15_000L
        const val VERIFY_MS = 30_000L
        val RETRY_MS = listOf(30_000L, 60_000L, 120_000L)
        const val COOLDOWN_MS = 300_000L
    }
}
