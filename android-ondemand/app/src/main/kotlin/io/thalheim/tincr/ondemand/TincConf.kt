package io.thalheim.tincr.ondemand

import java.io.File

// The tinc.conf keys this app owns; every other line is kept as is.
object TincConf {
    const val TUN_SOCKET = "tincr-ondemand-tun"
    private val OWNED = setOf("ondemand", "idletimeout", "autoconnect", "connectto", "devicetype", "device")

    // tinc keys end at whitespace or '='.
    private fun key(line: String) = line.trim().takeWhile { !it.isWhitespace() && it != '=' }.lowercase()

    fun connectTo(dir: File): String? = File(dir, "tinc.conf").takeIf { it.isFile }?.readLines()
        ?.firstOrNull { key(it) == "connectto" }
        ?.trim()?.drop("ConnectTo".length)?.trim()?.removePrefix("=")?.trim()
        ?.takeIf { it.isNotEmpty() }

    fun enforce(dir: File, hub: String, idleSeconds: Int) {
        val f = File(dir, "tinc.conf")
        val kept = f.readLines().filter { key(it) !in OWNED }
        val owned = listOf(
            "DeviceType = fd",
            "Device = @$TUN_SOCKET",
            "AutoConnect = no",
            "ConnectTo = $hub",
            "OnDemand = yes",
            "IdleTimeout = $idleSeconds",
        )
        f.writeText((kept + owned).joinToString("\n", postfix = "\n"))
    }
}
