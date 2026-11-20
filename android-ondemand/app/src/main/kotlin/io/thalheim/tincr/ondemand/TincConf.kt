package io.thalheim.tincr.ondemand

import java.io.File

// The tinc.conf keys this app owns; every other line is kept as is.
object TincConf {
    const val TUN_SOCKET = "tincr-ondemand-tun"
    private val OWNED = setOf("ondemand", "idletimeout", "autoconnect", "connectto", "devicetype", "device")

    // tinc keys end at whitespace or '='.
    private fun key(line: String) = line.trim().takeWhile { !it.isWhitespace() && it != '=' }.lowercase()

    // Every ConnectTo value, in file order.
    fun connectTo(dir: File): List<String> = File(dir, "tinc.conf").takeIf { it.isFile }?.readLines().orEmpty()
        .filter { key(it) == "connectto" }
        .map { it.trim().drop("ConnectTo".length).trim().removePrefix("=").trim() }
        .filter { it.isNotEmpty() }

    // tincd races all peers on wake and keeps the closest one.
    fun enforce(dir: File, peers: List<String>, idleSeconds: Int) {
        val f = File(dir, "tinc.conf")
        val kept = f.readLines().filter { key(it) !in OWNED }
        val owned = listOf(
            "DeviceType = fd",
            "Device = @$TUN_SOCKET",
            "AutoConnect = no",
            "OnDemand = yes",
            "IdleTimeout = $idleSeconds",
        ) + peers.map { "ConnectTo = $it" }
        f.writeText((kept + owned).joinToString("\n", postfix = "\n"))
    }
}
