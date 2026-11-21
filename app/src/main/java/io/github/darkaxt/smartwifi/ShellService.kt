package io.github.darkaxt.smartwifi

import android.content.AttributionSource
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Shizuku user service. Shizuku starts this class in its own process with the shell identity,
 * which holds NETWORK_SETTINGS — the privilege the Settings app uses to connect to saved networks.
 */
class ShellService : IShellService.Stub() {

    override fun destroy() {
        System.exit(0)
    }

    override fun wifiCommand(subcommand: String): String {
        require(subcommand in ALLOWED) { "subcommand not allowed: $subcommand" }
        val process = ProcessBuilder("cmd", "wifi", subcommand).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroy()
            return "124\n$output"
        }
        return "${process.exitValue()}\n$output"
    }

    override fun connectNetwork(networkId: Int): String = try {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        val wifiBinder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "wifi") as IBinder
        val wifi = Class.forName("android.net.wifi.IWifiManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, wifiBinder)!!

        val latch = CountDownLatch(1)
        val result = AtomicReference("timeout")
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) {
                    reply?.writeString(LISTENER_DESCRIPTOR)
                    return true
                }
                runCatching { data.enforceInterface(LISTENER_DESCRIPTOR) }
                result.set(when (code) {
                    FIRST_CALL_TRANSACTION -> "success"
                    FIRST_CALL_TRANSACTION + 1 -> "failure:" + runCatching { data.readInt() }.getOrDefault(-1)
                    else -> "unknown-callback:$code"
                })
                latch.countDown()
                return true
            }
        }
        val listener = Class.forName("android.net.wifi.IActionListener\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, callback)

        val connect: Method = wifi.javaClass.methods
            .filter { it.name == "connect" && it.parameterTypes.contains(Int::class.javaPrimitiveType) }
            .maxByOrNull { it.parameterCount }
            ?: return "error:IWifiManager.connect not found"

        val args = connect.parameterTypes.map { type ->
            when {
                type == Int::class.javaPrimitiveType -> networkId
                type == String::class.java -> SHELL_PACKAGE
                type == Bundle::class.java -> attributionExtras()
                type.name == "android.net.wifi.IActionListener" -> listener
                else -> null // WifiConfiguration: null means "connect to saved netId"
            }
        }.toTypedArray()
        connect.invoke(wifi, *args)
        latch.await(15, TimeUnit.SECONDS)
        result.get()
    } catch (t: Throwable) {
        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        "error:${cause.javaClass.simpleName}: ${cause.message}"
    }

    private fun attributionExtras(): Bundle {
        val key = runCatching {
            Class.forName("android.net.wifi.WifiManager")
                .getField("EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE").get(null) as String
        }.getOrDefault("EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE")
        return Bundle().apply {
            putParcelable(key, AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build())
        }
    }

    companion object {
        private const val SHELL_PACKAGE = "com.android.shell"
        private const val LISTENER_DESCRIPTOR = "android.net.wifi.IActionListener"
        private val ALLOWED = setOf("status", "list-scan-results", "list-networks", "start-scan")
    }
}
