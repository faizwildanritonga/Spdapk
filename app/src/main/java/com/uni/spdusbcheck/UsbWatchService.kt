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
 * Service "warm": dijalankan sebagai foreground service begitu user pencet
 * tombol start, supaya proses sudah hidup & receiver sudah terdaftar
 * SEBELUM device dicolok. Ini yang motong cold-start delay dibanding
 * Termux (yang baru exec binary setelah termux-usb grant permission).
 *
 * Target VID Unisoc: 0x1782. PID tidak difilter di sini maupun di
 * device_filter.xml -- semua PID diterima & ditampilkan, validasi
 * "apakah ini PID yang kita harapkan (BROM/FDL1/FDL2)" dilakukan manual
 * dari log, karena PID bisa beda per device/mode (lihat catatan VID/PID).
 */
class UsbWatchService : Service() {

    companion object {
        const val CHANNEL_ID = "spd_usb_watch"
        const val NOTIF_ID = 1
        const val ACTION_LOG = "com.uni.spdusbcheck.LOG"
        const val EXTRA_LOG_LINE = "line"
        const val ACTION_START = "com.uni.spdusbcheck.START"
        const val ACTION_STOP = "com.uni.spdusbcheck.STOP"

        // t0: waktu tombol start dipencet (referensi buat hitung delay)
        @Volatile var t0ElapsedMs: Long = 0L
    }

    private lateinit var usbManager: UsbManager
    private var receiverRegistered = false

    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // tCatch: waktu callback ini benar-benar jalan
            val tCatch = SystemClock.elapsedRealtime()
            if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return

            val device: UsbDevice? =
                if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

            if (device == null) {
                broadcastLog("[${tCatch}] onReceive fired tapi EXTRA_DEVICE null")
                return
            }

            val deltaFromStart = if (t0ElapsedMs > 0) tCatch - t0ElapsedMs else -1
            broadcastLog(
                "device attached, +${deltaFromStart}ms dari start listener"
            )
            broadcastLog(
                "VID: 0x%04X  PID: 0x%04X  (%s)".format(
                    device.vendorId, device.productId, device.deviceName
                )
            )

            val hasPermission = usbManager.hasPermission(device)
            broadcastLog("permission granted otomatis: $hasPermission")

            if (hasPermission) {
                val tOpenStart = SystemClock.elapsedRealtime()
                val connection = usbManager.openDevice(device)
                val tOpenDone = SystemClock.elapsedRealtime()
                if (connection != null) {
                    broadcastLog(
                        "openDevice OK, fd=${connection.fileDescriptor}, " +
                        "ambil ${tOpenDone - tOpenStart}ms " +
                        "(total dari attach: ${tOpenDone - tCatch}ms)"
                    )
                    // Fase 1 cuma sampai sini: fd siap dipakai.
                    // Fase 2 nanti: oper connection.fileDescriptor ke native
                    // (libusb_wrap_sys_device) lalu panggil spd_dump --usb-fd <fd>.
                    connection.close()
                } else {
                    broadcastLog("openDevice GAGAL (connection null)")
                }
            } else {
                // Kalau ini kejadian, berarti device_filter.xml belum match
                // atau VID device ini beda dari 0x1782 -- cek lagi VID aktualnya
                // di log di atas.
                broadcastLog("TIDAK auto-granted -- cek apakah VID match device_filter.xml")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        createNotifChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopWatching()
                stopSelf()
                return START_NOT_STICKY
            }
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
