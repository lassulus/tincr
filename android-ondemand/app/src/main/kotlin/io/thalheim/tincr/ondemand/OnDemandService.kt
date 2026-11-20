package io.thalheim.tincr.ondemand

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.concurrent.thread

// Starts tincd once when switched on; tincd itself sleeps while idle.
// Start and stop run in order on one worker thread.
class OnDemandService : VpnService() {
    companion object {
        private const val ACTION_STOP = "io.thalheim.tincr.ondemand.STOP"
        private const val TAG = "tincr-ondemand"
        private const val CHANNEL = "vpn"
        private val worker = Executors.newSingleThreadExecutor()

        @Volatile
        var running = false
            private set

        @Volatile
        var stopping = false
            private set

        // Last reason the VPN went off by itself, for the UI.
        @Volatile
        var error: String? = null
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OnDemandService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OnDemandService::class.java).setAction(ACTION_STOP))
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var daemon: Tincd? = null

    @Volatile
    private var fdServer: TunFdServer? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            turnOff(startId, null)
            return START_NOT_STICKY
        }
        startForeground(1, notification())
        running = true
        error = null
        worker.execute { startVpn(startId) }
        return START_STICKY
    }

    override fun onRevoke() = turnOff(null, "VPN permission revoked")

    override fun onDestroy() {
        worker.execute {
            if (daemon != null) {
                stopVpn()
                running = false
            }
        }
        super.onDestroy()
    }

    private fun startVpn(startId: Int) {
        if (daemon != null) return
        // A stop queued before this start removed the notification.
        startForeground(1, notification())
        running = true
        try {
            launch(startId)
        } catch (e: IOException) {
            fail(startId, "start failed: ${e.message}")
        }
    }

    private fun launch(startId: Int) {
        val settings = Settings(this)
        val dir = settings.dir
        if (!File(dir, "tinc.conf").isFile) return fail(startId, "No config imported")
        val hub = settings.hub.ifBlank { TincConf.connectTo(dir).orEmpty() }
        if (hub.isEmpty()) return fail(startId, "No hub set")
        TincConf.enforce(dir, hub, settings.idleSeconds)

        val config = NetworkConfig.load(dir)
        val tun = establish(config) ?: return fail(startId, "VPN permission missing")
        this.tun = tun
        val server = TunFdServer(TincConf.TUN_SOCKET, tun.fileDescriptor).also { fdServer = it }
        val runner = Tincd(this, dir).also { daemon = it }
        runner.start { code ->
            worker.execute { if (daemon === runner) fail(null, "tincd exited ($code)") }
        }
        val served = server.serveOnce(30_000) { runner.alive }
        fdServer = null
        // A user stop closed the server; its queued job cleans up.
        if (stopping) return
        if (!served) return fail(startId, "tincd did not take the tun device")
        watchNetwork(dir)
    }

    private fun establish(config: NetworkConfig): ParcelFileDescriptor? =
        Builder()
            .setSession("tincr on-demand")
            .setMtu(config.mtu)
            // tincd runs under our UID. Its own sockets must not loop into the tun.
            .addDisallowedApplication(packageName)
            .apply {
                config.addresses.forEach { addAddress(it.address, it.prefix) }
                config.routes.forEach { addRoute(it.address, it.prefix) }
                config.dnsServers.forEach { addDnsServer(it) }
                config.searchDomains.forEach { addSearchDomain(it) }
            }
            .establish()

    // On network change let tincd redial past its backoff; a no-op while it sleeps.
    private fun watchNetwork(dir: File) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                thread(name = "tincr-retry") { TincCtl(dir).request(TincCtl.REQ_RETRY) }
            }
        }
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            cb,
        )
        netCallback = cb
    }

    // The one way off: UI, notification action, revoke and tincd death.
    private fun turnOff(startId: Int?, reason: String?) {
        stopping = running
        fdServer?.close() // unblocks a start still waiting for tincd
        worker.execute { fail(startId, reason) }
    }

    private fun fail(startId: Int?, reason: String?) {
        reason?.let { Log.w(TAG, it) }
        error = reason
        stopVpn()
        running = false
        stopping = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        // A later start keeps the service alive.
        if (startId != null) stopSelf(startId) else stopSelf()
    }

    private fun stopVpn() {
        netCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        netCallback = null
        fdServer?.close()
        fdServer = null
        // Without a child this still reaps orphans of a killed app process.
        val d = daemon ?: Tincd(this, Settings(this).dir)
        daemon = null
        d.stop()
        tun?.close()
        tun = null
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW)
        )
        val off = PendingIntent.getService(
            this, 0,
            Intent(this, OnDemandService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("tincr on-demand is on")
            .setContentText("tinc connects when traffic needs it")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Turn off", off,
                ).build()
            )
            .build()
    }
}
