package com.uni.spdusbcheck

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat

/**
 * Alur dipangkas minimum: device attached -> openDevice() -> langsung
 * serahkan fd ke spd_dump native. Tidak ada log perantara lagi di antara
 * attach dan handoff fd -- spd_dump sendiri yang akan ambil alih (retry
 * HELLO, dst) begitu fd diterima.
 */
class UsbWatchService : Service() {

    companion object {
        const val CHANNEL_ID = "spd_usb_watch"
        const val NOTIF_ID = 1
        const val ACTION_LOG = "com.uni.spdusbcheck.LOG"
        const val EXTRA_LOG_LINE = "line"
        const val ACTION_START = "com.uni.spdusbcheck.START"
        const val ACTION_STOP = "com.uni.spdusbcheck.STOP"
        @Volatile var t0ElapsedMs: Long = 0L
    }

    private lateinit var usbManager: UsbManager
    private var receiverRegistered = false
    private var openConnection: android.hardware.usb.UsbDeviceConnection? = null

    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val tCatch = SystemClock.elapsedRealtime()
            if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return

            val device: UsbDevice? =
                if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (device == null) return

            val deltaFromStart = if (t0ElapsedMs > 0) tCatch - t0ElapsedMs else -1
            broadcastLog("attach +${deltaFromStart}ms, VID=0x%04X PID=0x%04X".format(
                device.vendorId, device.productId
            ))

            try {
                val connection = usbManager.openDevice(device) ?: run {
                    broadcastLog("openDevice() gagal (null)")
                    return
                }
                openConnection = connection
                val fd = connection.fileDescriptor
                val tHandoff = SystemClock.elapsedRealtime()
                broadcastLog("fd=$fd siap, ${tHandoff - tCatch}ms dari attach -> jalankan spd_dump")

                Thread {
                    val ret = try {
                        NativeBridge.runSpdDump(fd, arrayOf("--wait", "5"), this@UsbWatchService)
                    } catch (e: Throwable) {
                        broadcastLog("NATIVE EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
                        -999
                    }
                    broadcastLog("=== spd_dump selesai, ret=$ret ===")
                    connection.close()
                    openConnection = null
                }.start()
            } catch (e: Throwable) {
                broadcastLog("EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // Dipanggil dari native (jni_bridge.cpp) tiap spd_dump nulis satu baris
    // output. Harus public & nama/signature ini persis (dicari via JNI
    // GetMethodID "(Ljava/lang/String;)V").
    fun onNativeLogLine(line: String) {
        broadcastLog(line)
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        createNotifChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopWatching(); stopSelf(); return START_NOT_STICKY }
            else -> startWatching()
        }
        return START_STICKY
    }

    private fun startWatching() {
        t0ElapsedMs = SystemClock.elapsedRealtime()
        startForeground(NOTIF_ID, buildNotif("Waiting for device..."))
        if (!receiverRegistered) {
            val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(attachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(attachReceiver, filter)
            }
            receiverRegistered = true
        }
        broadcastLog("listener aktif, menunggu device di-colok...")
    }

    private fun stopWatching() {
        if (receiverRegistered) {
            unregisterReceiver(attachReceiver)
            receiverRegistered = false
        }
        broadcastLog("listener dihentikan")
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(attachReceiver)
            receiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun broadcastLog(msg: String) {
        sendBroadcast(Intent(ACTION_LOG).putExtra(EXTRA_LOG_LINE, msg).setPackage(packageName))
    }

    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "SPD USB Watch", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotif(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SPD USB Check")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
}
