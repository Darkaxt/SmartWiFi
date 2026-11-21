package io.github.darkaxt.smartwifi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Fixtures are trimmed copies of real Galaxy Z Fold7 (Android 17) `cmd wifi` output.
class ParsersTest {
    private val scan = """
    BSSID              Frequency      RSSI           Age(sec)     SSID                                 Flags
  56:60:f9:f4:17:5e       5180    -82(0:-84/1:-84)   483.538                                      [WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][ESS]
  ca:1f:fc:ef:22:05       5220    -25(0:-35/1:-21)    14.048    GL-BE10000-98a-5G                 [WPA2-PSK-GCMP-256+CCMP-128][RSN-PSK+SAE+SAE_EXT_KEY-GCMP-256+CCMP-128][ESS][MFPC]
  16:e0:32:36:03:3d       2422    -26(0:-31/1:-31)    11.847    GL-BE10000-98a                    [WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][ESS][MFPC]
  ce:fc:57:26:a8:bd       6215    -35(0:-35/1:-32)    12.019    GL-BE10000-98a-6G                 [RSN-SAE+SAE_EXT_KEY-GCMP-256+CCMP-128][ESS][MFPR][MFPC]
  54:78:f0:96:5b:27       5260    -56(0:-60/1:-59)    13.667    DIGIFIBRA-PLUS-skt2               [WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][ESS][WPS]
  4c:cf:7c:2a:eb:48       2437    -82(0:-88/1:-82)   483.316    DIRECT-46-HP DeskJet 2900 series  [WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][WPA-PSK-CCMP-128][ESS][WPS]
  b8:e3:b1:63:94:72       5240    -87(0:-88/1:-92)   483.257    ASISA-INVITADOS                   [ESS]
    """.trimIndent()

    private val saved = """
Network Id      SSID                         Security type
2            AIRPORT FREE WIFI AENA           open
2            AIRPORT FREE WIFI AENA           owe
18           DIGIFIBRA-PLUS-skt2              wpa2-psk
18           DIGIFIBRA-PLUS-skt2              wpa3-sae^
29           GL-BE10000-98a-6G                wpa3-sae
63           GL-BE10000-98a-5G                wpa2-psk
63           GL-BE10000-98a-5G                wpa3-sae^
64           GL-BE10000-98a                   wpa2-psk
64           GL-BE10000-98a                   wpa3-sae^
    """.trimIndent()

    private val status = """
Wifi is enabled
Wifi scanning is always available
==== Primary ClientModeManager instance ====
Wifi is connected to "GL-BE10000-98a"
WifiInfo: SSID: "GL-BE10000-98a", BSSID: 16:e0:32:36:03:3d, MAC: a2:20:69:13:09:4b, IP: /192.168.8.140, Security type: 2, Supplicant state: COMPLETED, Wi-Fi standard: 11ax, RSSI: -24, Link speed: 258Mbps, Frequency: 2422MHz, Net ID: 64, Metered hint: false, score: 60
successfulTxPackets: 120268
    """.trimIndent()

    @Test fun parsesScanLinesIncludingHiddenAndSpacedSsids() {
        val r = parseScanResults(scan)
        assertEquals(7, r.size)
        assertEquals("", r[0].ssid)
        assertEquals("GL-BE10000-98a-5G", r[1].ssid)
        assertEquals(5220, r[1].frequency)
        assertEquals(-25, r[1].rssi)
        assertEquals(14.048, r[1].ageSeconds, 0.001)
        assertEquals("DIRECT-46-HP DeskJet 2900 series", r[5].ssid)
        assertEquals(setOf(Security.PSK, Security.SAE), r[1].securities)
        assertEquals(setOf(Security.SAE), r[3].securities)
        assertEquals(setOf(Security.OPEN), r[6].securities)
    }

    @Test fun mergesSavedSecurityVariants() {
        val r = parseSavedNetworks(saved).associateBy { it.id }
        assertEquals(5, r.size)
        assertEquals(setOf(Security.PSK, Security.SAE), r.getValue(18).securities)
        assertEquals(setOf(Security.SAE), r.getValue(29).securities)
        assertEquals("AIRPORT FREE WIFI AENA", r.getValue(2).ssid)
        assertTrue(r.getValue(2).isOpen)
        assertFalse(r.getValue(64).isOpen)
    }

    @Test fun parsesConnectedStatus() {
        val s = parseStatus(status)
        assertTrue(s.enabled && s.connected)
        assertEquals("GL-BE10000-98a", s.ssid)
        assertEquals("16:e0:32:36:03:3d", s.bssid)
        assertEquals(-24, s.rssi)
        assertEquals(2422, s.frequency)
        assertEquals(64, s.networkId)
    }

    @Test fun parsesDisabledAndDisconnectedStatus() {
        assertFalse(parseStatus("Wifi is disabled\n").enabled)
        val s = parseStatus("Wifi is enabled\n==== Primary ClientModeManager instance ====\nWifi is not connected\n")
        assertTrue(s.enabled)
        assertFalse(s.connected)
        assertNull(s.ssid)
    }
}
