package io.github.darkaxt.smartwifi

import android.content.Context
import io.github.darkaxt.smartwifi.core.EngineSettings

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("smartwifi", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = p.getBoolean("enabled", false)
        set(v) = p.edit().putBoolean("enabled", v).apply()

    var marginDb: Int
        get() = p.getInt("margin", 8)
        set(v) = p.edit().putInt("margin", v).apply()

    var bonus5Db: Int
        get() = p.getInt("bonus5", 10)
        set(v) = p.edit().putInt("bonus5", v).apply()

    var bonus6Db: Int
        get() = p.getInt("bonus6", 12)
        set(v) = p.edit().putInt("bonus6", v).apply()

    var includeOpen: Boolean
        get() = p.getBoolean("includeOpen", false)
        set(v) = p.edit().putBoolean("includeOpen", v).apply()

    var preferHighBand: Boolean
        get() = p.getBoolean("preferHighBand", true)
        set(v) = p.edit().putBoolean("preferHighBand", v).apply()

    fun engineSettings() = EngineSettings(
        marginDb = marginDb,
        bonus5Db = bonus5Db,
        bonus6Db = bonus6Db,
        includeOpenNetworks = includeOpen,
        preferHighBand = preferHighBand,
    )
}
