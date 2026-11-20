package io.thalheim.tincr.ondemand

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.FileDescriptor
import kotlin.concurrent.thread

// Stands in for the gate's tun: one end of a socketpair goes to tincd
// (Device = @NAME), this end answers the ICMP echo requests it writes.
class IcmpEcho(name: String) : Closeable {
    private val ours = FileDescriptor()
    private val theirs = FileDescriptor()
    private val server: TunFdServer

    init {
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_SEQPACKET, 0, ours, theirs)
        server = TunFdServer(name, theirs)
    }

    fun handOver(alive: () -> Boolean): Boolean {
        val ok = server.serveOnce(10_000, alive)
        Os.close(theirs)
        if (ok) thread(name = "icmp-echo", isDaemon = true) { loop() }
        return ok
    }

    private fun loop() {
        val buf = ByteArray(2048)
        while (true) {
            val n = try {
                Os.read(ours, buf, 0, buf.size)
            } catch (_: ErrnoException) {
                return
            }
            if (n <= 0) return
            if (reply(buf, n)) Os.write(ours, buf, 0, n)
        }
    }

    // IPv4 echo request -> echo reply in place.
    private fun reply(p: ByteArray, n: Int): Boolean {
        if (n < 28 || p[0].toInt() shr 4 != 4 || p[9].toInt() != 1) return false
        val ihl = (p[0].toInt() and 0xf) * 4
        if (n < ihl + 8 || p[ihl].toInt() != 8) return false
        for (i in 0 until 4) {
            val t = p[12 + i]
            p[12 + i] = p[16 + i]
            p[16 + i] = t
        }
        p[ihl] = 0
        p[ihl + 2] = 0
        p[ihl + 3] = 0
        var sum = 0
        for (i in ihl until n step 2) {
            val hi = p[i].toInt() and 0xff
            val lo = if (i + 1 < n) p[i + 1].toInt() and 0xff else 0
            sum += (hi shl 8) or lo
        }
        while (sum shr 16 != 0) sum = (sum and 0xffff) + (sum shr 16)
        sum = sum.inv() and 0xffff
        p[ihl + 2] = (sum shr 8).toByte()
        p[ihl + 3] = sum.toByte()
        return true
    }

    override fun close() {
        server.close()
        try {
            Os.shutdown(ours, OsConstants.SHUT_RDWR)
            Os.close(ours)
        } catch (_: ErrnoException) {
        }
    }
}
