package io.github.darkaxt.smartwifi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.os.PowerManager
import io.github.darkaxt.smartwifi.core.Band
import io.github.darkaxt.smartwifi.core.Decision
import io.github.darkaxt.smartwifi.core.Engine
import io.github.darkaxt.smartwifi.core.SavedNetwork
import io.github.darkaxt.smartwifi.core.WifiStatus
import io.github.darkaxt.smartwifi.core.parseSavedNetworks
import io.github.darkaxt.smartwifi.core.parseScanResults
import io.github.darkaxt.smartwifi.core.parseStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileDescriptor
import java.io.PrintWriter

/**
 * Foreground service that keeps the phone on the best saved Wi-Fi network.
 *
 * Loop: read `cmd wifi status` + cached scan results (requesting a fresh scan only when the
 * cache is stale and the situation warrants it), score candidates, and connect through the
 * shell-identity user service when the engine decides to switch.
 */
class SwitchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private lateinit var prefs: Prefs
    private val engine = Engine()

    @Volatile private var wifiValidated: Boolean? = null
    @Volatile private var unvalidatedSince: Long? = null
    private var saved: List<SavedNetwork> = emptyList()
    private var savedAt = 0L
    private var verify: Pair<Int, Long>? = null // target network id to deadline
    private var lastScanRequestAt = 0L
    private var lastConnKey: String? = null
    private var lastShizuku: ShizukuState? = null
    private var lastHeartbeatAt = 0L

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (validated) unvalidatedSince = null
            else if (unvalidatedSince == null) unvalidatedSince = now()
            if (wifiValidated != validated) {
                StateHub.log(if (validated) "Internet check: OK" else "Internet check: no internet on Wi-Fi")
                wifiValidated = validated
                wake.trySend(Unit)
            }
        }

        override fun onLost(network: Network) {
            wifiValidated = null
            unvalidatedSince = null
            wake.trySend(Unit)
        }
    }

    /** Unlocking the phone triggers an immediate fresh scan and evaluation. */
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            lastScanRequestAt = 0
            wake.trySend(Unit)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        StateHub.init(this)
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            netCallback,
        )
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        StateHub.update { it.copy(running = true) }
        StateHub.log("Service started")
        scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        wake.trySend(Unit)
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) }
        runCatching { unregisterReceiver(screenOnReceiver) }
        scope.cancel()
        ShizukuShell.unbind()
        StateHub.update { it.copy(running = false) }
        StateHub.log("Service stopped")
        super.onDestroy()
    }

    private suspend fun loop() {
        while (true) {
            runCatching { tick() }.onFailure { StateHub.log("Tick failed: ${it.message}") }
            val screenOn = getSystemService(PowerManager::class.java).isInteractive
            // Screen off only matters for lost internet, which the network callback wakes us for.
            withTimeoutOrNull(if (screenOn || wifiValidated == false) 10_000L else 60_000L) { wake.receive() }
        }
    }

    private suspend fun tick() {
        val shizuku = ShizukuShell.state()
        StateHub.update { it.copy(shizuku = shizuku) }
        if (shizuku != lastShizuku) {
            StateHub.log("Shizuku: ${shizuku.name.lowercase().replace('_', ' ')}")
            lastShizuku = shizuku
        }
        if (shizuku != ShizukuState.READY) {
            updateNotification("Waiting for Shizuku")
            return
        }
        engine.settings = prefs.engineSettings()
        val status = ShizukuShell.wifi("status")?.let(::parseStatus) ?: return
        val t = now()

        logConnectionChange(status)
        checkPendingVerification(status, t)

        if (!status.enabled) {
            publish(status, null, emptyList(), "Wi-Fi is off", null)
            return
        }
        if (saved.isEmpty() || t - savedAt > 5 * 60_000) {
            ShizukuShell.wifi("list-networks")?.let { saved = parseSavedNetworks(it); savedAt = t }
        }

        var scans = ShizukuShell.wifi("list-scan-results")?.let(::parseScanResults).orEmpty()
        val newest = scans.minOfOrNull { it.ageSeconds }
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        val staleAfter = if (screenOn) 20.0 else 60.0
        if ((newest == null || newest > staleAfter) && (screenOn || wifiValidated == false) && t - lastScanRequestAt > 15_000) {
            lastScanRequestAt = t
            ShizukuShell.wifi("start-scan")
            delay(4_000)
            scans = ShizukuShell.wifi("list-scan-results")?.let(::parseScanResults).orEmpty()
        }

        val unvalidated = if (wifiValidated == false) unvalidatedSince else null
        val eval = engine.evaluate(now(), status, scans, saved, unvalidated, passive = !screenOn)
        publish(status, eval.currentScore, eval.candidates, eval.decision.reason, scans.minOfOrNull { it.ageSeconds })

        if (t - lastHeartbeatAt >= HEARTBEAT_MS) {
            lastHeartbeatAt = t
            val best = eval.candidates.firstOrNull()?.let { "${it.ssid} ${it.band.label} ${it.rssi} dBm score ${it.score}" } ?: "none"
            val where = if (status.connected) "${status.ssid} ${status.frequency?.let { Band.of(it).label }} ${status.rssi} dBm score ${eval.currentScore}" else "not connected"
            StateHub.log("Status: $where; best alternative: $best; saved networks: ${saved.size}")
        }

        val decision = eval.decision
        if (decision is Decision.Switch && verify == null) {
            val target = decision.target
            StateHub.log("Switch ${status.ssid} → ${target.ssid} (${target.band.label}, ${target.rssi} dBm): ${decision.reason}")
            val result = ShizukuShell.connect(target.networkId)
            engine.recordSwitch(now())
            if (result == "success") {
                verify = target.networkId to now() + 30_000
            } else {
                StateHub.log("Connect to ${target.ssid} failed: $result")
                engine.penalize(target.networkId, now())
            }
        }
    }

    /** Logs every connection change, attributing it to Smart WiFi when it matches our pending switch. */
    private fun logConnectionChange(status: WifiStatus) {
        val band = status.frequency?.let { Band.of(it).label } ?: "?"
        val key = when {
            !status.enabled -> "off"
            status.connected -> "${status.networkId}|${status.ssid}|$band"
            else -> "disconnected"
        }
        if (key == lastConnKey) return
        val first = lastConnKey == null
        lastConnKey = key
        when (key) {
            "off" -> StateHub.log("Wi-Fi is off")
            "disconnected" -> if (!first) StateHub.log("Wi-Fi disconnected")
            else -> {
                val cause = when {
                    first -> "at service start"
                    verify?.first == status.networkId -> "by Smart WiFi"
                    else -> "by Android or you"
                }
                StateHub.log("Connected to ${status.ssid} ($band, ${status.rssi} dBm) $cause")
                savedAt = 0 // pick up a network that was just saved
            }
        }
    }

    private fun checkPendingVerification(status: WifiStatus, t: Long) {
        val (target, deadline) = verify ?: return
        val onTarget = status.connected && status.networkId == target
        if (onTarget && wifiValidated == true) {
            StateHub.log("Now on ${status.ssid} (${status.rssi} dBm), internet OK")
            verify = null
        } else if (t > deadline) {
            StateHub.log("Switch to network $target not confirmed; avoiding it for 5 min")
            engine.penalize(target, t)
            verify = null
        }
    }

    private fun publish(status: WifiStatus, score: Int?, candidates: List<io.github.darkaxt.smartwifi.core.Candidate>, decision: String, age: Double?) {
        StateHub.update {
            it.copy(status = status, currentScore = score, candidates = candidates, decision = decision,
                validated = wifiValidated, lastScanAgeSeconds = age)
        }
        val text = if (status.connected) {
            val band = status.frequency?.let { Band.of(it).label } ?: "?"
            "${status.ssid} · $band · ${status.rssi} dBm" + if (wifiValidated == false) " · no internet" else ""
        } else if (status.enabled) "Not connected" else "Wi-Fi off"
        updateNotification(text)
    }

    // --- Diagnostics: `adb shell dumpsys activity service <pkg>/.SwitchService [evaluate|log [n]|connect <id>]`
    override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        when (args?.firstOrNull()) {
            "evaluate" -> { wake.trySend(Unit); writer.println("evaluation requested") }
            "log" -> {
                val n = args.getOrNull(1)?.toIntOrNull() ?: 200
                StateHub.tail(n).forEach(writer::println)
                return
            }
            "connect" -> {
                val id = args.getOrNull(1)?.toIntOrNull()
                if (id == null) writer.println("usage: connect <networkId>")
                else {
                    val result = runBlocking { ShizukuShell.connect(id) }
                    StateHub.log("Diagnostic connect to network $id: $result")
                    writer.println("connect $id: $result")
                }
            }
        }
        val s = StateHub.state.value
        writer.println("running=${s.running} shizuku=${s.shizuku} validated=${s.validated} unvalidatedSince=$unvalidatedSince")
        writer.println("status=${s.status}")
        writer.println("currentScore=${s.currentScore} scanAge=${s.lastScanAgeSeconds} decision=${s.decision}")
        writer.println("settings=${engine.settings}")
        writer.println("savedNetworks=${saved.size} penalized=${engine.penalizedIds(now())} verify=$verify")
        writer.println("candidates:")
        s.candidates.forEach { writer.println("  ${it.score}\t${it.rssi}\t${it.band.label}\t#${it.networkId}\t${it.ssid}\t${it.bssid}") }
        writer.println("events:")
        s.events.forEach { writer.println("  $it") }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN)
        )
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_wifi)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private var lastNotificationText: String? = null
    private fun updateNotification(text: String) {
        if (text == lastNotificationText) return
        lastNotificationText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        const val ACTION_STOP = "io.github.darkaxt.smartwifi.STOP"
        private const val CHANNEL = "status"
        private const val NOTIFICATION_ID = 1
        private const val HEARTBEAT_MS = 30 * 60_000L
        private fun now() = System.currentTimeMillis()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SwitchService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SwitchService::class.java).setAction(ACTION_STOP))
        }
    }
}
