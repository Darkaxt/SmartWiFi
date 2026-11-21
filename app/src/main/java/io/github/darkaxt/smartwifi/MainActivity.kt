package io.github.darkaxt.smartwifi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.darkaxt.smartwifi.core.Band
import rikka.shizuku.Shizuku
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        StateHub.update { it.copy(shizuku = ShizukuShell.state()) }
        if (Prefs(this).enabled) SwitchService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val prefs = Prefs(this)
        if (prefs.enabled) SwitchService.start(this)
        setContent {
            val ctx = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { Screen(prefs) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        StateHub.update { it.copy(shizuku = ShizukuShell.state()) }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }
}

@Composable
private fun Screen(prefs: Prefs) {
    val ctx = LocalContext.current
    val state by StateHub.state.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(prefs.enabled) }

    Column(
        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Smart WiFi", style = MaterialTheme.typography.headlineMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Smart switching", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(checked = enabled, onCheckedChange = {
                        enabled = it
                        prefs.enabled = it
                        if (it) SwitchService.start(ctx) else SwitchService.stop(ctx)
                    })
                }
                val shizukuText = when (state.shizuku) {
                    ShizukuState.READY -> "Shizuku: ready"
                    ShizukuState.NO_PERMISSION -> "Shizuku: permission needed"
                    ShizukuState.NOT_RUNNING -> "Shizuku: not running — start it in the Shizuku app"
                }
                Text(shizukuText)
                if (state.shizuku == ShizukuState.NO_PERMISSION) {
                    Button(onClick = { ShizukuShell.requestPermission() }) { Text("Grant Shizuku access") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Current", style = MaterialTheme.typography.titleMedium)
                val s = state.status
                if (s == null || !state.running) Text(if (state.running) "Reading Wi-Fi state…" else "Service off")
                else if (!s.enabled) Text("Wi-Fi is off")
                else if (!s.connected) Text("Not connected")
                else {
                    val band = s.frequency?.let { Band.of(it).label } ?: "?"
                    Text("${s.ssid}  ·  $band  ·  ${s.rssi} dBm  ·  score ${state.currentScore ?: "?"}")
                    Text(
                        when (state.validated) { true -> "Internet: OK"; false -> "Internet: not working"; null -> "Internet: unknown" }
                    )
                }
                if (state.decision.isNotEmpty()) Text(state.decision, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (state.candidates.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Other saved networks in range", style = MaterialTheme.typography.titleMedium)
                    state.candidates.forEach {
                        Row {
                            Text(it.ssid, Modifier.weight(1f))
                            Text("${it.band.label}  ${it.rssi} dBm  →  ${it.score}", fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Tuning", style = MaterialTheme.typography.titleMedium)
                IntSlider("Switch margin", prefs.marginDb, 3..20) { prefs.marginDb = it }
                IntSlider("5 GHz bonus", prefs.bonus5Db, 0..25) { prefs.bonus5Db = it }
                IntSlider("6 GHz bonus", prefs.bonus6Db, 0..25) { prefs.bonus6Db = it }
                var open by remember { mutableStateOf(prefs.includeOpen) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Include open networks", Modifier.weight(1f))
                    Switch(checked = open, onCheckedChange = { open = it; prefs.includeOpen = it })
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Recent activity", style = MaterialTheme.typography.titleMedium)
                if (state.events.isEmpty()) Text("Nothing yet")
                state.events.take(25).forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
        }
        Spacer(Modifier.width(1.dp))
    }
}

@Composable
private fun IntSlider(label: String, initial: Int, range: IntRange, onChange: (Int) -> Unit) {
    var value by remember { mutableIntStateOf(initial) }
    Column {
        Text("$label: $value dB")
        Slider(
            value = value.toFloat(),
            onValueChange = { value = it.roundToInt() },
            onValueChangeFinished = { onChange(value) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
        )
    }
}
