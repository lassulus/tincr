package io.thalheim.tincr.ondemand

import android.content.Context
import android.os.Process.sendSignal
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// Runs libtincd.so in the foreground (-D) so it stays our child.
// RUST_LOG=warn keeps it quiet; its output goes to logcat only.
class Tincd(context: Context, private val dir: File) {
    private val binary = File(context.applicationInfo.nativeLibraryDir, "libtincd.so")
    private var process: Process? = null

    val alive: Boolean
        get() = process?.isAlive == true

    // onExit runs on the log thread once tincd is gone, whoever stopped it.
    fun start(onExit: (Int) -> Unit) {
        reap(dir)
        val pb = ProcessBuilder(
            binary.absolutePath,
            "-D",
            "--config", dir.absolutePath,
            "--pidfile", File(dir, "tincd.pid").absolutePath,
        ).redirectErrorStream(true)
        pb.environment()["RUST_LOG"] = "warn"
        val p = pb.start()
        process = p
        thread(name = "tincd-log", isDaemon = true) {
            p.inputStream.bufferedReader().forEachLine { Log.w(TAG, it) }
            onExit(p.waitFor())
        }
    }

    fun stop() {
        TincCtl(dir).request(TincCtl.REQ_STOP)
        process?.waitFor(3, TimeUnit.SECONDS)
        reap(dir)
        // Collect the zombie; reap() has already signalled it.
        process?.waitFor(2, TimeUnit.SECONDS)
        process = null
    }

    companion object {
        private const val TAG = "tincd"
        private const val SIGTERM = 15
        private const val SIGKILL = 9

        // Live libtincd.so processes of this app (procfs only shows our
        // uid), optionally only those running on dir.
        fun pids(dir: File? = null): List<Int> =
            File("/proc").list().orEmpty().mapNotNull { it.toIntOrNull() }.filter { pid ->
                val argv = runCatching { File("/proc/$pid/cmdline").readText() }
                    .getOrDefault("").split('\u0000')
                argv[0].endsWith("/libtincd.so") && (dir == null || dir.absolutePath in argv)
            }

        // Stops every daemon on dir, also ones orphaned by a dead app
        // process: REQ_STOP, then SIGTERM, then SIGKILL.
        fun reap(dir: File) {
            val pidFile = File(dir, "tincd.pid").takeIf { it.isFile }
                ?.readText()?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
            val left = { (pids(dir) + listOfNotNull(pidFile?.takeIf { it in pids() })).distinct() }
            if (left().isEmpty()) return
            TincCtl(dir).request(TincCtl.REQ_STOP)
            for (signal in listOf(SIGTERM, SIGKILL)) {
                if (waitGone(left, 3_000)) return
                left().forEach {
                    Log.w(TAG, "sending signal $signal to tincd $it")
                    sendSignal(it, signal)
                }
            }
            waitGone(left, 2_000)
        }

        private fun waitGone(left: () -> List<Int>, ms: Long): Boolean {
            val deadline = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < deadline) {
                if (left().isEmpty()) return true
                Thread.sleep(100)
            }
            return left().isEmpty()
        }
    }
}
