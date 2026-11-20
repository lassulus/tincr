package io.thalheim.tincr.ondemand

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.util.Log
import java.io.File

// Plain framework views; status is polled only while resumed.
class MainActivity : Activity() {
    companion object {
        private const val TAG = "tincr-ondemand"
        private const val REQ_VPN = 1
        private const val REQ_IMPORT = 2

        // Each poll wakes tincd: fast only while a state change is due.
        private const val POLL_FAST_MS = 1_000L
        private const val POLL_STEADY_MS = 15_000L
    }

    private val settings by lazy { Settings(this) }
    private val worker = HandlerThread("tincr-ui").apply { start() }
    private val bg = Handler(worker.looper)
    private lateinit var status: TextView
    private lateinit var toggle: Switch
    private lateinit var peers: EditText
    private lateinit var idle: EditText
    private lateinit var message: TextView

    // Outcome of the last import; wins over the status-derived hint.
    private var note: String? = null

    // The VPN consent dialog is up or just answered; resolved in onResume.
    private var consentPending = false

    // One control session while visible, closed in onPause.
    private val ctl by lazy { TincCtl(settings.dir) }

    private val poll = object : Runnable {
        override fun run() {
            val s = Status.query(ctl)
            val hasConfig = File(settings.dir, "tinc.conf").isFile
            runOnUiThread { show(s, hasConfig) }
            val steady = s == Status.Off || s == Status.Sleeping || s is Status.Connected
            bg.postDelayed(this, if (steady) POLL_STEADY_MS else POLL_FAST_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { textSize = 22f }
        toggle = Switch(this).apply {
            text = "VPN"
            setOnClickListener {
                if (isChecked) switchOn() else OnDemandService.stop(this@MainActivity)
                refresh()
            }
        }
        peers = EditText(this).apply {
            hint = "ConnectTo node names, space-separated"
            setSingleLine()
        }
        idle = EditText(this).apply {
            hint = "idle timeout (minutes)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        message = TextView(this)
        val importButton = Button(this).apply {
            text = "Import config (zip)"
            setOnClickListener { pickZip() }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            listOf(
                status, toggle, label("Peers to dial (closest wins)", pad), peers,
                label("Idle timeout (minutes)", pad), idle, importButton, message,
            ).forEach { addView(it) }
        }
        // fitsSystemWindows replaces the padding of the view it is set on.
        setContentView(
            ScrollView(this).apply {
                fitsSystemWindows = true
                addView(column)
            }
        )
        peers.setText(settings.peers.joinToString(" "))
        idle.setText(((settings.idleSeconds + 59) / 60).toString())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    private fun label(text: String, pad: Int) = TextView(this).apply {
        this.text = text
        setPadding(0, pad, 0, 0)
    }

    override fun onResume() {
        super.onResume()
        // Both paths schedule exactly one poll chain.
        if (consentPending) consentAnswered() else bg.post(poll)
    }

    override fun onPause() {
        bg.removeCallbacks(poll)
        bg.post { ctl.close() }
        save()
        super.onPause()
    }

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }

    private fun show(s: Status, hasConfig: Boolean) {
        status.text = if (consentPending) "Waiting for VPN permission…" else s.label
        toggle.isChecked = consentPending || (s != Status.Off && s != Status.Stopping)
        message.text = note ?: OnDemandService.error ?: OnDemandService.notice
            ?: if (hasConfig) "" else "No config imported yet."
    }

    // The service applies start/stop asynchronously; look again shortly.
    private fun refresh() {
        note = null
        bg.removeCallbacks(poll)
        bg.postDelayed(poll, 300)
    }

    private fun save() {
        settings.peers = peers.text.toString().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        idle.text.toString().toIntOrNull()?.takeIf { it > 0 }?.let { settings.idleSeconds = it * 60 }
    }

    private fun switchOn() {
        save()
        val consent = VpnService.prepare(this)
        if (consent == null) return start()
        Log.i(TAG, "asking for VPN consent")
        consentPending = true
        startActivityForResult(consent, REQ_VPN)
    }

    // Decided by the grant itself, not the result code, and only once
    // resumed: onActivityResult runs before onResume, while the app may
    // still count as background for starting a foreground service.
    private fun consentAnswered() {
        consentPending = false
        refresh()
        if (VpnService.prepare(this) == null) {
            Log.i(TAG, "VPN consent granted")
            start()
        } else {
            Log.i(TAG, "VPN consent not granted")
            toggle.isChecked = false
            setNote("VPN permission not granted.")
        }
    }

    private fun start() {
        try {
            OnDemandService.start(this)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "start refused", e)
            setNote("Could not start: ${e.message}")
        }
    }

    private fun pickZip() {
        if (OnDemandService.running) {
            setNote("Turn the VPN off before importing.")
            return
        }
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip"),
            REQ_IMPORT,
        )
    }

    private fun setNote(text: String) {
        note = text
        message.text = text
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            // Handled in onResume, which follows right after.
            REQ_VPN -> Log.i(TAG, "VPN consent dialog closed ($resultCode)")
            REQ_IMPORT -> data?.data?.takeIf { resultCode == RESULT_OK }?.let { uri ->
                setNote("Importing…")
                bg.post {
                    val result = runCatching {
                        contentResolver.openInputStream(uri)!!.use { ConfigImport.fromZip(it, settings.dir) }
                        TincConf.connectTo(settings.dir)
                    }
                    runOnUiThread {
                        result.onSuccess { found ->
                            if (found.isNotEmpty()) peers.setText(found.joinToString(" "))
                            save()
                            setNote("Config imported.")
                        }.onFailure { setNote("Import failed: ${it.message}") }
                    }
                }
            }
        }
    }
}
