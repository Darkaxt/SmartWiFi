package io.github.darkaxt.smartwifi.core

data class EngineSettings(
    /** Score a candidate must beat the current network by before switching. */
    val marginDb: Int = 8,
    /** Score bonus for 5 GHz and 6 GHz links, applied only while their RSSI is usable. */
    val bonus5Db: Int = 10,
    val bonus6Db: Int = 12,
    /** High bands drop off fast; below this RSSI they get no bonus. */
    val highBandMinRssi: Int = -72,
    /** Candidates weaker than this are never considered. */
    val minCandidateRssi: Int = -78,
    /** Scan entries older than this are ignored. */
    val maxScanAgeSeconds: Double = 45.0,
    /** Consecutive evaluations that must agree before a signal-based switch. */
    val confirmations: Int = 2,
    val cooldownMs: Long = 30_000,
    val failurePenaltyMs: Long = 5 * 60_000,
    /** How long the current Wi-Fi may lack validated internet before an immediate switch. */
    val unvalidatedGraceMs: Long = 15_000,
    val includeOpenNetworks: Boolean = false,
    /**
     * Treat 2.4 GHz as a fallback: never move to it while a 5/6 GHz link (current or candidate)
     * is usable, and move up from 2.4 GHz as soon as a usable 5/6 GHz network appears.
     */
    val preferHighBand: Boolean = true,
    /** On 5/6 GHz, keep refusing 2.4 GHz until the current signal is this far below [highBandMinRssi]. */
    val highBandHysteresisDb: Int = 4,
)

data class Candidate(
    val networkId: Int,
    val ssid: String,
    val bssid: String,
    val frequency: Int,
    val rssi: Int,
    val score: Int,
) {
    val band: Band get() = Band.of(frequency)
}

sealed interface Decision {
    val reason: String

    data class Stay(override val reason: String) : Decision
    data class Switch(val target: Candidate, override val reason: String) : Decision
}

data class Evaluation(
    val decision: Decision,
    val currentScore: Int?,
    /** Best candidate per saved network, strongest first (excludes the current SSID). */
    val candidates: List<Candidate>,
)

fun score(settings: EngineSettings, frequency: Int, rssi: Int): Int {
    val bonus = when (Band.of(frequency)) {
        Band.GHZ_2_4 -> 0
        Band.GHZ_5 -> settings.bonus5Db
        Band.GHZ_6 -> settings.bonus6Db
    }
    return if (rssi >= settings.highBandMinRssi) rssi + bonus else rssi
}

/**
 * Pure switching policy. Holds only debounce, cooldown and failure-penalty state, so it can
 * be exercised deterministically in unit tests with explicit timestamps.
 */
class Engine(var settings: EngineSettings = EngineSettings()) {
    private var pendingTarget: Int? = null
    private var pendingCount = 0
    private var lastSwitchAt = Long.MIN_VALUE / 2
    private val penalties = HashMap<Int, Long>()

    fun penalize(networkId: Int, now: Long) {
        penalties[networkId] = now + settings.failurePenaltyMs
    }

    fun penalizedIds(now: Long): Set<Int> {
        penalties.entries.removeAll { it.value <= now }
        return penalties.keys.toSet()
    }

    fun recordSwitch(now: Long) {
        lastSwitchAt = now
        pendingTarget = null
        pendingCount = 0
    }

    fun candidates(status: WifiStatus, scans: List<ScanEntry>, saved: List<SavedNetwork>, now: Long): List<Candidate> {
        val penalized = penalizedIds(now)
        val bySsid = saved.groupBy { it.ssid }
        val best = HashMap<Int, Candidate>()
        for (s in scans) {
            if (s.ssid.isEmpty() || s.ssid == status.ssid) continue
            if (s.ageSeconds > settings.maxScanAgeSeconds || s.rssi < settings.minCandidateRssi) continue
            val scanSec = s.securities
            val match = bySsid[s.ssid]?.firstOrNull { net ->
                (settings.includeOpenNetworks || !net.isOpen) && net.securities.any { it in scanSec }
            } ?: continue
            if (match.id in penalized) continue
            val c = Candidate(match.id, s.ssid, s.bssid, s.frequency, s.rssi, score(settings, s.frequency, s.rssi))
            val prev = best[match.id]
            if (prev == null || c.score > prev.score) best[match.id] = c
        }
        return best.values.sortedByDescending { it.score }
    }

    fun evaluate(
        now: Long,
        status: WifiStatus,
        scans: List<ScanEntry>,
        saved: List<SavedNetwork>,
        unvalidatedSince: Long?,
        /** Screen off: only a lost internet connection justifies a switch. */
        passive: Boolean = false,
    ): Evaluation {
        val list = candidates(status, scans, saved, now)
        val currentScore = currentScore(status, scans)
        val curBand = (status.frequency ?: scans.firstOrNull { it.bssid == status.bssid }?.frequency)?.let(Band::of)
        val curRssi = status.rssi ?: scans.firstOrNull { it.bssid == status.bssid }?.rssi

        // 5 GHz floor: 2.4 GHz candidates only count when no 5/6 GHz link is usable.
        val usableHigh = list.filter { it.band != Band.GHZ_2_4 && it.rssi >= settings.highBandMinRssi }
        val holdingHigh = curBand != null && curBand != Band.GHZ_2_4 && curRssi != null &&
            curRssi >= settings.highBandMinRssi - settings.highBandHysteresisDb
        val eligible = if (settings.preferHighBand && (holdingHigh || usableHigh.isNotEmpty())) {
            list.filter { it.band != Band.GHZ_2_4 }
        } else list
        val upgrade = settings.preferHighBand && curBand == Band.GHZ_2_4 && usableHigh.isNotEmpty()
        val best = if (upgrade) usableHigh.first() else eligible.firstOrNull()

        fun stay(reason: String, resetPending: Boolean = true): Evaluation {
            if (resetPending) { pendingTarget = null; pendingCount = 0 }
            return Evaluation(Decision.Stay(reason), currentScore, list)
        }

        if (!status.enabled) return stay("Wi-Fi is off")
        if (!status.connected) return stay("Not connected; leaving auto-join to Android")
        if (now - lastSwitchAt < settings.cooldownMs) return stay("Cooling down after a switch", resetPending = false)
        if (best == null) {
            return stay(if (eligible.size < list.size) "Staying on ${curBand?.label}; 2.4 GHz is fallback only" else "No better saved network in range")
        }

        if (unvalidatedSince != null && now - unvalidatedSince >= settings.unvalidatedGraceMs) {
            return Evaluation(Decision.Switch(best, "Current Wi-Fi has no internet"), currentScore, list)
        }
        if (passive) return stay("Screen off; only switching if internet is lost")

        val cur = currentScore ?: return stay("Current signal unknown")
        if (!upgrade && best.score < cur + settings.marginDb) return stay("Current network is best (score $cur)")

        if (pendingTarget == best.networkId) pendingCount++ else { pendingTarget = best.networkId; pendingCount = 1 }
        if (pendingCount < settings.confirmations) {
            return stay("Confirming ${best.ssid} (${best.score} vs $cur)", resetPending = false)
        }
        val why = if (upgrade) "leaving 2.4 GHz for ${best.band.label} ${best.ssid} (${best.rssi} dBm)"
            else "${best.ssid} scores ${best.score} vs $cur"
        return Evaluation(
            Decision.Switch(best, why),
            currentScore,
            list,
        )
    }

    private fun currentScore(status: WifiStatus, scans: List<ScanEntry>): Int? {
        val rssi = status.rssi ?: scans.firstOrNull { it.bssid == status.bssid }?.rssi ?: return null
        val freq = status.frequency ?: scans.firstOrNull { it.bssid == status.bssid }?.frequency ?: return null
        return score(settings, freq, rssi)
    }
}
