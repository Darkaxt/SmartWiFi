package io.github.darkaxt.smartwifi

import io.github.darkaxt.smartwifi.core.Candidate
import io.github.darkaxt.smartwifi.core.WifiStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiState(
    val running: Boolean = false,
    val shizuku: ShizukuState = ShizukuState.NOT_RUNNING,
    val status: WifiStatus? = null,
    val currentScore: Int? = null,
    val validated: Boolean? = null,
    val candidates: List<Candidate> = emptyList(),
    val decision: String = "",
    val lastScanAgeSeconds: Double? = null,
    val events: List<String> = emptyList(),
)

/** In-process state shared between [SwitchService] and the UI. */
object StateHub {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun update(block: (UiState) -> UiState) = _state.update(block)

    fun log(message: String) {
        val line = "${fmt.format(Date())}  $message"
        android.util.Log.i("SmartWiFi", message)
        _state.update { it.copy(events = (listOf(line) + it.events).take(MAX_EVENTS)) }
    }

    private const val MAX_EVENTS = 60
}
