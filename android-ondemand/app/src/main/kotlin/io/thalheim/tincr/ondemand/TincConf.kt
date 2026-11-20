package io.thalheim.tincr.ondemand

import java.io.File

// The tinc.conf keys this app owns; every other line is kept as is.
object TincConf {
    const val TUN_SOCKET = "tincr-ondemand-tun"
    private val OWNED = setOf("ondemand", "idletimeout", "autoconnect", "connectto", "devicetype", "device")

    // tinc keys end at whitespace or '='.
    private fun key(line: String) = line.trim().takeWhile { !it.isWhitespace() && it != '=' }.lowercase()

    private fun lines(f: File) = f.takeIf { it.isFile }?.readLines().orEmpty()

    // Values of `key` (lowercase), in file order.
    private fun values(f: File, k: String) = lines(f)
        .filter { key(it) == k }
        .map { it.trim().drop(k.length).trim().removePrefix("=").trim() }
        .filter { it.isNotEmpty() }

    // Every ConnectTo value, in file order.
    fun connectTo(dir: File): List<String> = values(File(dir, "tinc.conf"), "connectto")

    // `Port` as tincd sees it: tinc.conf, else hosts/<Name>. Null if unset.
    fun port(dir: File): String? {
        val conf = File(dir, "tinc.conf")
        values(conf, "port").firstOrNull()?.let { return it }
        val name = values(conf, "name").firstOrNull() ?: return null
        return values(File(dir, "hosts/$name"), "port").firstOrNull()
    }

    // An app may not bind below 1024 (tinc's default is 655). Port 0 lets
    // the kernel pick; peers learn the real port from the handshake.
    fun needsUnprivilegedPort(port: String?): Boolean =
        port?.toIntOrNull()?.let { it in 1..1023 } ?: true

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
