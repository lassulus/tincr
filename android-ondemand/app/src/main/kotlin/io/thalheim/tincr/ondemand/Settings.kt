package io.thalheim.tincr.ondemand

import android.content.Context
import java.io.File

// User choices; applied to tinc.conf each time the VPN is switched on.
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val dir = File(context.filesDir, "networks/default")

    // ConnectTo candidates; tincd keeps whichever answers first.
    var peers: List<String>
        get() = prefs.getString("peers", "").orEmpty().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        set(v) = prefs.edit().putString("peers", v.joinToString(" ")).apply()

    var idleSeconds: Int
        get() = prefs.getInt("idle_seconds", 300)
        set(v) = prefs.edit().putInt("idle_seconds", v).apply()
}
