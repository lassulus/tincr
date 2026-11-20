package io.thalheim.tincr.ondemand

import android.content.Context
import java.io.File

// User choices; applied to tinc.conf each time the VPN is switched on.
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val dir = File(context.filesDir, "networks/default")

    var hub: String
        get() = prefs.getString("hub", "").orEmpty()
        set(v) = prefs.edit().putString("hub", v.trim()).apply()

    var idleSeconds: Int
        get() = prefs.getInt("idle_seconds", 300)
        set(v) = prefs.edit().putInt("idle_seconds", v).apply()
}
