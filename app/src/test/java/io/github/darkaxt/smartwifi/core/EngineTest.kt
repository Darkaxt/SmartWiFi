package io.github.darkaxt.smartwifi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    private val saved = listOf(
        SavedNetwork(18, "DIGIFIBRA-PLUS-skt2", setOf(Security.PSK, Security.SAE)),
        SavedNetwork(20, "DIGIFIBRA-skt2", setOf(Security.PSK, Security.SAE)),
        SavedNetwork(29, "GL-6G", setOf(Security.SAE)),
        SavedNetwork(63, "GL-5G", setOf(Security.PSK, Security.SAE)),
        SavedNetwork(64, "GL", setOf(Security.PSK, Security.SAE)),
        SavedNetwork(2, "Airport", setOf(Security.OPEN, Security.OWE)),
    )
    private val psk = "[RSN-PSK-CCMP-128][ESS]"
    private val sae = "[RSN-SAE-CCMP-128][ESS]"

    private fun bss(ssid: String, freq: Int, rssi: Int, flags: String = psk, age: Double = 5.0) =
        ScanEntry("00:00:00:00:00:%02x".format(ssid.hashCode() and 0xff), freq, rssi, age, ssid, flags)

    private fun connected(ssid: String, id: Int, freq: Int, rssi: Int) =
        WifiStatus(enabled = true, connected = true, ssid = ssid, bssid = "aa:aa:aa:aa:aa:aa", rssi = rssi, frequency = freq, networkId = id)

    @Test fun prefers5GhzOverSlightlyStronger24GhzOnSameRouter() {
        // The live case: on GL 2.4 GHz at -24 while GL 5 GHz is at -25.
        val e = Engine()
        val status = connected("GL", 64, 2422, -24)
        val scans = listOf(bss("GL-5G", 5220, -25), bss("GL-6G", 6215, -35, sae), bss("DIGIFIBRA-PLUS-skt2", 5260, -56))
        assertTrue(e.evaluate(0, status, scans, saved, null).decision is Decision.Stay) // first confirmation
        val d = e.evaluate(10_000, status, scans, saved, null).decision
        assertTrue(d is Decision.Switch)
        assertEquals(63, (d as Decision.Switch).target.networkId)
    }

    @Test fun switchesAcrossTheHouseWhenCurrentFadesAndOtherIsStronger() {
        val e = Engine()
        val status = connected("GL-6G", 29, 6215, -84) // 6 GHz below the bonus floor
        val scans = listOf(bss("DIGIFIBRA-PLUS-skt2", 5260, -58), bss("GL-5G", 5220, -79))
        e.evaluate(0, status, scans, saved, null)
        val d = e.evaluate(10_000, status, scans, saved, null).decision as Decision.Switch
        assertEquals(18, d.target.networkId)
    }

    @Test fun staysWithinMarginToAvoidFlapping() {
        val e = Engine()
        val status = connected("DIGIFIBRA-PLUS-skt2", 18, 5260, -60) // score -50
        val scans = listOf(bss("GL-5G", 5220, -55)) // score -45, only 5 better
        repeat(5) { assertTrue(e.evaluate(it * 10_000L, status, scans, saved, null).decision is Decision.Stay) }
    }

    @Test fun switchesImmediatelyWhenCurrentHasNoInternet() {
        val e = Engine()
        val status = connected("GL-5G", 63, 5220, -50)
        val scans = listOf(bss("DIGIFIBRA-PLUS-skt2", 5260, -65))
        val d = e.evaluate(20_000, status, scans, saved, unvalidatedSince = 0).decision
        assertTrue(d is Decision.Switch)
    }

    @Test fun ignoresOpenStaleWeakAndPenalizedNetworks() {
        val e = Engine()
        val status = connected("DIGIFIBRA-skt2", 20, 2442, -80)
        val scans = listOf(
            bss("Airport", 2412, -40, "[ESS]"),
            bss("GL-5G", 5220, -40, age = 300.0),
            bss("GL", 2422, -90),
            bss("GL-6G", 6215, -40, sae),
        )
        e.penalize(29, now = 0)
        assertTrue(e.candidates(status, scans, saved, now = 1_000).isEmpty())
    }

    @Test fun securityMustMatchSavedProfile() {
        val e = Engine()
        val status = connected("DIGIFIBRA-skt2", 20, 2442, -80)
        // A WPA2-only AP broadcasting the name of a WPA3-only saved network is not a candidate.
        val scans = listOf(bss("GL-6G", 6215, -40, psk))
        assertTrue(e.candidates(status, scans, saved, now = 0).isEmpty())
    }

    @Test fun cooldownBlocksBackToBackSwitches() {
        val e = Engine()
        e.recordSwitch(now = 0)
        val status = connected("GL", 64, 2422, -80)
        val scans = listOf(bss("GL-5G", 5220, -40))
        assertTrue(e.evaluate(5_000, status, scans, saved, null).decision is Decision.Stay)
        e.evaluate(31_000, status, scans, saved, null)
        assertTrue(e.evaluate(41_000, status, scans, saved, null).decision is Decision.Switch)
    }

    @Test fun highBandBonusOnlyWhileUsable() {
        val s = EngineSettings()
        assertEquals(-50 + 10, score(s, 5200, -50))
        assertEquals(-75, score(s, 5200, -75))
        assertEquals(-60 + 12, score(s, 6215, -60))
        assertEquals(-60, score(s, 2412, -60))
    }
}
