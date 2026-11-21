package io.github.darkaxt.smartwifi

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

enum class ShizukuState { NOT_RUNNING, NO_PERMISSION, READY }

/** Binds the shell-identity [ShellService] through Shizuku and exposes suspend calls to it. */
object ShizukuShell {
    const val PERMISSION_REQUEST = 4711

    private val lock = Mutex()
    @Volatile private var service: IShellService? = null
    private var pending: CompletableDeferred<IShellService?>? = null

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = binder?.takeIf { it.pingBinder() }?.let(IShellService.Stub::asInterface)
            service = s
            pending?.complete(s)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun state(): ShizukuState = when {
        !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> ShizukuState.NOT_RUNNING
        Shizuku.isPreV11() -> ShizukuState.NOT_RUNNING
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.NO_PERMISSION
        else -> ShizukuState.READY
    }

    fun requestPermission() {
        if (state() == ShizukuState.NO_PERMISSION) Shizuku.requestPermission(PERMISSION_REQUEST)
    }

    private suspend fun obtain(): IShellService? {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        if (state() != ShizukuState.READY) return null
        return lock.withLock {
            service?.takeIf { it.asBinder().pingBinder() }?.let { return@withLock it }
            val deferred = CompletableDeferred<IShellService?>()
            pending = deferred
            withContext(Dispatchers.Main) { Shizuku.bindUserService(args, connection) }
            withTimeoutOrNull(15_000) { deferred.await() }.also { pending = null }
        }
    }

    /** Returns stdout of `cmd wifi <sub>` or null when Shizuku or the command is unavailable. */
    suspend fun wifi(sub: String): String? = withContext(Dispatchers.IO) {
        val s = obtain() ?: return@withContext null
        val raw = runCatching { s.wifiCommand(sub) }.getOrNull() ?: return@withContext null
        val exit = raw.substringBefore('\n').toIntOrNull()
        if (exit == 0) raw.substringAfter('\n') else null
    }

    suspend fun connect(networkId: Int): String = withContext(Dispatchers.IO) {
        val s = obtain() ?: return@withContext "error:Shizuku unavailable"
        runCatching { s.connectNetwork(networkId) }.getOrElse { "error:${it.message}" }
    }

    fun unbind() {
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        service = null
    }
}
