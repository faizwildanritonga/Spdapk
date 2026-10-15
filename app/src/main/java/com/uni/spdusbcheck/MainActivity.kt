package com.uni.spdusbcheck

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {

    private lateinit var tvLog: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btnStart: Button
    private var watching = false

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val line = intent.getStringExtra(UsbWatchService.EXTRA_LOG_LINE) ?: return
            appendLog(line)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLog = findViewById(R.id.tvLog)
        scroll = findViewById(R.id.scrollLog)
        btnStart = findViewById(R.id.btnStart)

        btnStart.setOnClickListener { toggleWatch() }

        // Kalau Activity ini dibuka OLEH intent USB_DEVICE_ATTACHED (cold-start
        // fallback, device dicolok saat app belum jalan), langsung mulai
        // service juga supaya attach berikutnya kejar jalur service yang warm.
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            appendLog("App di-cold-start oleh USB attach (device sudah nyolok duluan)")
            toggleWatch()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(UsbWatchService.ACTION_LOG)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(logReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onPause() {
        unregisterReceiver(logReceiver)
        super.onPause()
    }

    private fun toggleWatch() {
        watching = !watching
        if (watching) {
            btnStart.text = "■"
            appendLog("--- START ditekan, service dinyalakan ---")
            startForegroundService(Intent(this, UsbWatchService::class.java).apply {
                action = UsbWatchService.ACTION_START
            })
        } else {
            btnStart.text = "▶"
            appendLog("--- STOP ditekan ---")
            startService(Intent(this, UsbWatchService::class.java).apply {
                action = UsbWatchService.ACTION_STOP
            })
        }
    }

    private fun appendLog(line: String) {
        val ts = timeFmt.format(Date())
        tvLog.append("\n[$ts] $line")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
