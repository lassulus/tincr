package io.thalheim.tincr.ondemand

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

// Control-socket client: greeting `0 ^COOKIE 0` (two reply
// lines), then `18 <req>` acked with `18 <req> <result>`.
class TincCtl(private val dir: File) {
    companion object {
        const val REQ_STOP = 0
        const val REQ_DUMP_CONNECTIONS = 6
        const val REQ_RETRY = 10
        private const val STATUS_ACTIVE = 1 shl 1
        private const val STATUS_CONTROL = 1 shl 9
    }

    private fun cookie(): String? =
        File(dir, "tincd.pid").takeIf { it.isFile }
            ?.readText()?.split(Regex("\\s+"))?.getOrNull(1)

    fun request(req: Int): Boolean = converse(req)?.lastOrNull()?.startsWith("18 $req 0") ?: false

    // Peer name -> active, null when the daemon does not answer. Rows are
    // `18 6 NAME HOST port PORT OPTIONS FD STATUS`; control conns are skipped.
    fun connections(): Map<String, Boolean>? = converse(REQ_DUMP_CONNECTIONS)
        ?.map { it.split(' ') }
        ?.filter { it.size >= 9 }
        ?.mapNotNull { row ->
            val status = row[8].toIntOrNull(16) ?: return@mapNotNull null
            if (status and STATUS_CONTROL != 0) null else row[2] to (status and STATUS_ACTIVE != 0)
        }
        ?.toMap()

    // Lines up to and including the `18 <req> ...` terminator.
    private fun converse(req: Int): List<String>? {
        val cookie = cookie() ?: return null
        return try {
            LocalSocket().use { sock ->
                sock.connect(
                    LocalSocketAddress(
                        File(dir, "tincd.socket").absolutePath,
                        LocalSocketAddress.Namespace.FILESYSTEM,
                    )
                )
                sock.soTimeout = 5_000
                val r = BufferedReader(InputStreamReader(sock.inputStream))
                sock.outputStream.write("0 ^$cookie 0\n".toByteArray())
                r.readLine() ?: return null
                r.readLine() ?: return null
                sock.outputStream.write("18 $req\n".toByteArray())
                val out = mutableListOf<String>()
                while (true) {
                    val line = r.readLine() ?: return null
                    out.add(line)
                    if (line.split(' ').size <= 3) break
                }
                out
            }
        } catch (_: IOException) {
            null
        }
    }
}
