package io.github.darkaxt.smartwifi

import android.content.Context
import io.github.darkaxt.smartwifi.core.Candidate
import io.github.darkaxt.smartwifi.core.EventLogFile
import io.github.darkaxt.smartwifi.core.WifiStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
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
    val events: List<String> = emptyList(), // newest first
)

/** In-process state shared between [SwitchService] and the UI, backed by a persistent event log. */
object StateHub {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    @Volatile private var file: EventLogFile? = null

    /** Opens the on-disk log and restores recent history. Safe to call repeatedly. */
    fun init(context: Context) {
        if (file != null) return
        synchronized(this) {
            if (file != null) return
            val f = EventLogFile(File(context.applicationContext.filesDir, "logs"))
            file = f
            val restored = runCatching { f.tail(MAX_EVENTS).reversed() }.getOrDefault(emptyList())
            _state.update { it.copy(events = (it.events + restored).take(MAX_EVENTS)) }
        }
    }

    fun update(block: (UiState) -> UiState) = _state.update(block)

    fun log(message: String) {
        val line = "${synchronized(fmt) { fmt.format(Date()) }}  $message"
        android.util.Log.i("SmartWiFi", message)
        runCatching { file?.append(line) }.onFailure { android.util.Log.w("SmartWiFi", "log write failed", it) }
        _state.update { it.copy(events = (listOf(line) + it.events).take(MAX_EVENTS)) }
    }

    fun tail(n: Int): List<String> = runCatching { file?.tail(n) }.getOrNull().orEmpty()

    fun exportText(): String = runCatching { file?.readAll() }.getOrNull().orEmpty()

    private const val MAX_EVENTS = 60
}
