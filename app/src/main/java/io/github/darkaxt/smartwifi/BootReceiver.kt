package io.github.darkaxt.smartwifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            StateHub.init(context)
            val what = if (intent.action == Intent.ACTION_BOOT_COMPLETED) "Phone booted" else "App updated"
            val enabled = Prefs(context).enabled
            StateHub.log(if (enabled) "$what; starting service" else "$what; switching is off")
            if (enabled) SwitchService.start(context)
        }
    }
}
