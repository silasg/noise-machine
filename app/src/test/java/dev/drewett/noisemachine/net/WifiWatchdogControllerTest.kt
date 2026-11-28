package dev.drewett.noisemachine.net

import dev.drewett.noisemachine.net.WifiWatchdogController.Link
import dev.drewett.noisemachine.net.WifiWatchdogStatus.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiWatchdogControllerTest {
    private val network = SavedWifiNetwork("\"Home\"", 7)
    private val wifi = FakeWifi().apply {
        link = Link.Connected(network.id)
        networks = listOf(network)
    }
    private val store = FakeStore()
    private var elapsed = 0L
    private val wallClockStart = 1_700_000_000_000L
    private val watchdog = newWatchdog()

    private fun newWatchdog() = WifiWatchdogController(wifi, store, { elapsed }, { wallClockStart + elapsed }, {})

    private fun checkAt(seconds: Long, controller: WifiWatchdogController = watchdog): Long? {
        elapsed = seconds * 1000
        return controller.check()
    }

    private fun enable() {
        assertNull(watchdog.setEnabled(true))
        watchdog.check()
    }

    @Test fun offByDefaultAndDisablingCancelsPendingRecoveryAndClearsSavedTarget() {
        watchdog.restore()
        assertFalse(watchdog.snapshot.enabled)
        assertEquals(0, wifi.linkReads)
        enable()
        assertEquals(network, store.saved)
        assertEquals("Home", watchdog.snapshot.targetSsid)
        wifi.link = Link.Disconnected
        assertEquals(16_000L, checkAt(1))
        assertNull(watchdog.setEnabled(false))
        val readsAtDisable = wifi.linkReads
        assertNull(checkAt(60))
        assertEquals(readsAtDisable, wifi.linkReads)
        assertTrue(wifi.requests.isEmpty())
        assertNull(store.saved)
        assertEquals(Status.DISABLED, watchdog.snapshot.status)
        assertFalse(newWatchdog().apply { restore() }.snapshot.enabled)
    }

    @Test fun restartUsesPersistedTargetAndResolvesAChangedNetworkId() {
        enable()
        wifi.link = Link.Disconnected
        wifi.networks = listOf(network.copy(id = 13), SavedWifiNetwork("\"Other\"", network.id))
        val restarted = newWatchdog().apply { restore() }
        assertTrue(restarted.snapshot.enabled)
        assertEquals("Home", restarted.snapshot.targetSsid)
        assertEquals(25_000L, checkAt(10, restarted))
        checkAt(25, restarted)
        assertEquals(listOf(13), wifi.requests)
        assertEquals(network.copy(id = 13), store.saved)
    }

    @Test fun aShortDisconnectRecoversWithoutMakingARequest() {
        enable()
        wifi.link = Link.Disconnected
        assertEquals(16_000L, checkAt(1))
        assertEquals(16_000L, checkAt(15))
        wifi.link = Link.Connected(network.id)
        assertNull(checkAt(16))
        assertEquals(Status.CONNECTED, watchdog.snapshot.status)
        assertTrue(wifi.requests.isEmpty())
    }

    @Test fun aRepairIsSuccessfulOnlyAfterTheTargetConnectsAndResetsBackoff() {
        enable()
        wifi.accepted = false
        wifi.link = Link.Disconnected
        checkAt(1)
        assertEquals(46_000L, checkAt(16))
        wifi.accepted = true
        assertEquals(76_000L, checkAt(46))
        assertEquals(Status.RECONNECTING, watchdog.snapshot.status)
        assertEquals(wallClockStart, watchdog.snapshot.lastConnectedAtMs)
        assertEquals(wallClockStart + 46_000L, watchdog.snapshot.lastAttemptAtMs)
        wifi.link = Link.Connected(network.id)
        assertNull(checkAt(50))
        assertEquals(Status.CONNECTED, watchdog.snapshot.status)
        assertEquals(wallClockStart + 50_000L, watchdog.snapshot.lastConnectedAtMs)
        assertNull(watchdog.snapshot.lastError)

        wifi.accepted = false
        wifi.link = Link.Disconnected
        assertEquals(66_000L, checkAt(51))
        assertEquals(96_000L, checkAt(66))
    }

    @Test fun failedRequestsRetryAtThirtySixtyAndOneHundredTwentySecondsThenCoolDown() {
        enable()
        wifi.accepted = false
        wifi.link = Link.Disconnected
        checkAt(1)
        assertEquals(46_000L, checkAt(16))
        assertEquals(106_000L, checkAt(46))
        assertEquals(226_000L, checkAt(106))
        assertEquals(526_000L, checkAt(226))
        for (time in listOf(286L, 346L, 406L, 466L, 525L)) {
            assertEquals(526_000L, checkAt(time))
            assertEquals(Status.WAITING_TO_RETRY, watchdog.snapshot.status)
        }
        assertEquals(4, wifi.requests.size)
        assertEquals(556_000L, checkAt(526))
        assertEquals(5, wifi.requests.size)
    }

    @Test fun acceptedRequestTimesOutWithoutConfirmationAndWaitsIfAndroidIsConnecting() {
        enable()
        wifi.link = Link.Connecting
        assertEquals(16_000L, checkAt(1))
        assertNull(checkAt(16))
        assertTrue(wifi.requests.isEmpty())
        wifi.link = Link.Disconnected
        assertEquals(47_000L, checkAt(17))
        wifi.link = Link.Connecting
        assertEquals(47_000L, checkAt(30))
        assertEquals(77_000L, checkAt(47))
        assertNull(checkAt(77))
        assertEquals(listOf(network.id), wifi.requests)
        wifi.link = Link.Disconnected
        assertEquals(108_000L, checkAt(78))
    }

    @Test fun missingOrAmbiguousConfigurationNeverSelectsAnotherNetwork() {
        wifi.networks = emptyList()
        assertTrue(watchdog.setEnabled(true)!!.contains("saved configuration"))
        assertFalse(watchdog.snapshot.enabled)
        assertNull(store.saved)
        wifi.networks = listOf(network)
        enable()
        wifi.link = Link.Disconnected
        for (networks in listOf(emptyList(), listOf(network.copy(id = 5), network.copy(id = 6)))) {
            wifi.networks = networks
            checkAt(1)
            assertNull(checkAt(16))
            assertEquals(Status.ERROR, watchdog.snapshot.status)
            assertTrue(watchdog.snapshot.lastError!!.contains("missing or ambiguous"))
        }
        assertTrue(wifi.requests.isEmpty())
    }

    @Test fun permissionsFailureIsVisibleWithoutRepeatedRequests() {
        wifi.readDenied = true
        assertTrue(watchdog.setEnabled(true)!!.contains("denied Wi-Fi access"))
        assertFalse(watchdog.snapshot.enabled)
        wifi.readDenied = false
        enable()
        wifi.link = Link.Disconnected
        wifi.selectDenied = true
        checkAt(1)
        assertEquals(46_000L, checkAt(16))
        assertEquals(Status.ERROR, watchdog.snapshot.status)
        assertTrue(watchdog.snapshot.lastError!!.contains("denied Wi-Fi access"))
        checkAt(45)
        assertEquals(1, wifi.requests.size)
    }

    @Test fun wifiTurnedOffOrAnotherWorkingNetworkIsLeftAlone() {
        enable()
        wifi.link = Link.Off
        assertNull(checkAt(60))
        assertEquals(Status.WIFI_OFF, watchdog.snapshot.status)
        wifi.link = Link.Connected(2)
        assertNull(checkAt(120))
        assertEquals(Status.OTHER_NETWORK, watchdog.snapshot.status)
        wifi.link = Link.Disconnected
        assertEquals(136_000L, checkAt(121))
        assertTrue(wifi.requests.isEmpty())
    }

    private class FakeStore : WifiWatchdogController.Store {
        var saved: SavedWifiNetwork? = null
        override fun load() = saved
        override fun save(target: SavedWifiNetwork?) { saved = target }
    }

    private class FakeWifi : WifiWatchdogController.Wifi {
        var link: Link = Link.Disconnected
        var networks = emptyList<SavedWifiNetwork>()
        var accepted = true
        var readDenied = false
        var selectDenied = false
        var linkReads = 0
        val requests = mutableListOf<Int>()

        override fun link(): Link {
            linkReads++
            if (readDenied) throw SecurityException()
            return link
        }

        override fun savedNetworks() = networks

        override fun selectNetwork(id: Int): Boolean {
            requests += id
            if (selectDenied) throw SecurityException()
            return accepted
        }
    }
}
