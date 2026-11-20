package io.thalheim.tincr.ondemand

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.NetworkInterface
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Phone (this app's service, OnDemand) and gate (plain tincd whose tun
// is an in-process ICMP echo responder) on 127.0.0.1 in the emulator.
@RunWith(AndroidJUnit4::class)
class OnDemandTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = instr.targetContext
    private val settings = Settings(ctx)
    private val phoneDir = settings.dir
    private val gateDir = File(ctx.filesDir, "gate")
    private var gate: Tincd? = null
    private var echo: IcmpEcho? = null
    private val ctl = TincCtl(phoneDir)

    private fun status() = Status.query(ctl)

    // stdout followed by stderr.
    private fun shell(cmd: String): String {
        val (out, stdin, err) = instr.uiAutomation.executeShellCommandRwe(cmd)
        stdin.close()
        val read = { p: ParcelFileDescriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(p).use { String(it.readBytes()) }
        }
        var errText = ""
        val errThread = thread { errText = read(err) }
        val outText = read(out)
        errThread.join()
        return outText + errText
    }

    private fun diag(): String {
        val logcat = shell("logcat -d -s tincd tincr-ondemand AndroidRuntime:E")
            .lines().takeLast(40).joinToString("\n")
        return "status: ${status()} error: ${OnDemandService.error}\n" +
            "phone conns: ${ctl.connections()}\n" +
            "gate conns: ${TincCtl(gateDir).use { it.connections() }}\n== logcat ==\n$logcat"
    }

    private fun poll(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(250)
        }
        throw AssertionError("timeout: $what\n${diag()}")
    }

    // Assets under `mesh/phone`, zipped below a top-level dir as a user would.
    private fun zipAssets(from: String): ByteArray {
        val assets = instr.context.assets
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun add(path: String, rel: String) {
                val children = assets.list(path).orEmpty()
                if (children.isEmpty()) {
                    zip.putNextEntry(ZipEntry("phone/$rel"))
                    assets.open(path).use { it.copyTo(zip) }
                    zip.closeEntry()
                } else {
                    children.forEach { add("$path/$it", if (rel.isEmpty()) it else "$rel/$it") }
                }
            }
            add(from, "")
        }
        return out.toByteArray()
    }

    private fun copyAssets(from: String, to: File) {
        val assets = instr.context.assets
        for (name in assets.list(from).orEmpty()) {
            val src = "$from/$name"
            if (assets.list(src).orEmpty().isNotEmpty()) {
                copyAssets(src, File(to, name))
            } else {
                File(to, name).apply { parentFile!!.mkdirs() }
                    .outputStream().use { assets.open(src).copyTo(it) }
            }
        }
    }

    private fun tunUp() = NetworkInterface.getNetworkInterfaces().asSequence()
        .any { ni -> ni.inetAddresses.asSequence().any { it.hostAddress == "10.243.42.42" } }

    @Test
    fun wakesOnTrafficAndSleepsWhenIdle() {
        gateDir.deleteRecursively()
        copyAssets("mesh/gate", gateDir)
        // Upstream's gate has a dummy device; this one answers pings on an fd tun.
        File(gateDir, "tinc.conf").apply {
            writeText(readText().replace("DeviceType = dummy", "DeviceType = fd\nDevice = @tincr-ondemand-gate"))
        }
        ConfigImport.fromZip(ByteArrayInputStream(zipAssets("mesh/phone")), phoneDir)
        // Like most real configs, no Port: the app must avoid tinc's default 655.
        File(phoneDir, "tinc.conf").apply {
            writeText(readLines().filterNot { it.startsWith("Port") }.joinToString("\n", postfix = "\n"))
        }
        settings.peers = listOf("gate")
        settings.idleSeconds = 5

        val responder = IcmpEcho("tincr-ondemand-gate").also { echo = it }
        val gateD = Tincd(ctx, gateDir).also { gate = it }
        gateD.start {}
        assertTrue("gate took the echo tun", responder.handOver { gateD.alive })

        shell("appops set ${ctx.packageName} ACTIVATE_VPN allow")
        poll(10_000, "VPN consent") { VpnService.prepare(ctx) == null }
        OnDemandService.start(ctx)
        poll(20_000, "Sleeping after start") { status() == Status.Sleeping }
        val conf = File(phoneDir, "tinc.conf").readText()
        assertTrue(conf, conf.contains("OnDemand = yes\nIdleTimeout = 5\n"))
        // Android forbids binding tinc's default 655.
        assertTrue(OnDemandService.notice.orEmpty(), OnDemandService.notice.orEmpty().contains("Port"))

        // Netd applies the VPN's uid routing after establish(); until then the
        // shell's packet leaves via wlan0. Inspecting the route sends nothing.
        poll(20_000, "shell traffic routed into the VPN") {
            shell("ip route get 10.243.1.1").contains(" dev tun")
        }
        // One packet must wake tincd and survive until the tunnel is up.
        val ping = shell("ping -c 1 -W 10 10.243.1.1")
        assertTrue("$ping\n${diag()}", ping.contains(" 1 received"))
        poll(5_000, "Connected to gate") { status() == Status.Connected("gate") }
        poll(30_000, "Sleeping after idle timeout") { status() == Status.Sleeping }

        OnDemandService.stop(ctx)
        poll(15_000, "Off") { status() == Status.Off }
        assertEquals(emptyList<Int>(), Tincd.pids(phoneDir))
        poll(10_000, "VPN interface gone") { !tunUp() }
    }

    @After
    fun tearDown() {
        if (OnDemandService.running) {
            OnDemandService.stop(ctx)
            poll(15_000, "phone off in teardown") { !OnDemandService.running }
        }
        ctl.close()
        gate?.stop()
        echo?.close()
        assertEquals("libtincd.so processes left", emptyList<Int>(), Tincd.pids())
    }
}
