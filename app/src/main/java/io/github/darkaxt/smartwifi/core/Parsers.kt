package io.github.darkaxt.smartwifi.core

/** One BSS line from `cmd wifi list-scan-results`. */
data class ScanEntry(
    val bssid: String,
    val frequency: Int,
    val rssi: Int,
    val ageSeconds: Double,
    val ssid: String,
    val flags: String,
) {
    val securities: Set<Security> get() = securitiesFromScanFlags(flags)
}

/** One saved network from `cmd wifi list-networks` (security variants merged per network id). */
data class SavedNetwork(val id: Int, val ssid: String, val securities: Set<Security>) {
    val isOpen: Boolean get() = securities.isNotEmpty() && securities.all { it == Security.OPEN || it == Security.OWE }
}

/** Primary client state from `cmd wifi status`. */
data class WifiStatus(
    val enabled: Boolean,
    val connected: Boolean,
    val ssid: String? = null,
    val bssid: String? = null,
    val rssi: Int? = null,
    val frequency: Int? = null,
    val networkId: Int? = null,
)

enum class Security { OPEN, OWE, PSK, SAE, EAP, WEP }

enum class Band(val label: String) {
    GHZ_2_4("2.4 GHz"), GHZ_5("5 GHz"), GHZ_6("6 GHz");

    companion object {
        fun of(frequencyMhz: Int): Band = when {
            frequencyMhz < 3000 -> GHZ_2_4
            frequencyMhz < 5925 -> GHZ_5
            else -> GHZ_6
        }
    }
}

private val scanLine = Regex(
    """^\s*([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})\s+(\d+)\s+(-?\d+)\S*\s+([\d.]+)\s+(.*?)\s*(\[.*)?$"""
)

fun parseScanResults(text: String): List<ScanEntry> = text.lineSequence().mapNotNull { line ->
    val m = scanLine.matchEntire(line.trimEnd()) ?: return@mapNotNull null
    val (bssid, freq, rssi, age, ssid) = m.destructured
    ScanEntry(
        bssid = bssid.lowercase(),
        frequency = freq.toInt(),
        rssi = rssi.toInt(),
        ageSeconds = age.toDouble(),
        ssid = ssid.trim(),
        flags = m.groupValues[6],
    )
}.toList()

private val savedLine = Regex("""^(\d+)\s+(.+?)\s+(\S+)$""")

fun parseSavedNetworks(text: String): List<SavedNetwork> {
    val byId = LinkedHashMap<Int, Pair<String, MutableSet<Security>>>()
    for (raw in text.lineSequence()) {
        val m = savedLine.matchEntire(raw.trim()) ?: continue
        val id = m.groupValues[1].toInt()
        val ssid = m.groupValues[2]
        val sec = securityFromSavedType(m.groupValues[3])
        val entry = byId.getOrPut(id) { ssid to mutableSetOf() }
        sec?.let { entry.second += it }
    }
    return byId.map { (id, v) -> SavedNetwork(id, v.first, v.second) }
}

fun securityFromSavedType(type: String): Security? {
    val t = type.trimEnd('^').lowercase()
    return when {
        t == "open" -> Security.OPEN
        t == "owe" -> Security.OWE
        t.contains("sae") || t.startsWith("wpa3") && !t.contains("eap") -> Security.SAE
        t.contains("psk") || t == "wpa2" -> Security.PSK
        t.contains("eap") -> Security.EAP
        t.contains("wep") -> Security.WEP
        else -> null
    }
}

fun securitiesFromScanFlags(flags: String): Set<Security> {
    val f = flags.uppercase()
    val out = mutableSetOf<Security>()
    if (Regex("""-PSK|\+PSK|\[PSK""").containsMatchIn(f)) out += Security.PSK
    if (f.contains("SAE")) out += Security.SAE
    if (f.contains("EAP")) out += Security.EAP
    if (f.contains("OWE")) out += Security.OWE
    if (f.contains("WEP")) out += Security.WEP
    if (out.isEmpty()) out += Security.OPEN
    return out
}

private val connectedTo = Regex("""Wifi is connected to "(.*)"""")
private val wifiInfoSsid = Regex("""WifiInfo: SSID: "(.*?)", BSSID: ([0-9a-fA-F:]{17})""")
private val rssiField = Regex("""\bRSSI: (-?\d+)""")
private val freqField = Regex("""\bFrequency: (\d+)MHz""")
private val netIdField = Regex("""\bNet ID: (-?\d+)""")

fun parseStatus(text: String): WifiStatus {
    val enabled = text.contains("Wifi is enabled")
    // Only the primary ClientModeManager matters; secondary sections follow it.
    val primary = text.substringAfter("Primary ClientModeManager", text)
        .substringBefore("Secondary ClientModeManager")
    val connected = connectedTo.find(primary)
    if (!enabled || connected == null) return WifiStatus(enabled = enabled, connected = false)
    val infoLine = primary.lineSequence().firstOrNull { it.contains("WifiInfo:") } ?: ""
    val info = wifiInfoSsid.find(infoLine)
    return WifiStatus(
        enabled = true,
        connected = true,
        ssid = info?.groupValues?.get(1) ?: connected.groupValues[1],
        bssid = info?.groupValues?.get(2)?.lowercase(),
        rssi = rssiField.find(infoLine)?.groupValues?.get(1)?.toInt()?.takeIf { it > -127 },
        frequency = freqField.find(infoLine)?.groupValues?.get(1)?.toInt(),
        networkId = netIdField.find(infoLine)?.groupValues?.get(1)?.toInt()?.takeIf { it >= 0 },
    )
}
