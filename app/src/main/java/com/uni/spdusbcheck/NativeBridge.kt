package com.uni.spdusbcheck

object NativeBridge {
    init {
        System.loadLibrary("spd_native")
    }

    /**
     * Panggil spd_dump dengan fd USB yang sudah di-permission oleh UsbManager.
     * Di dalam native, ini setara dengan:
     *   spd_dump --usb-fd <usbFd> [extraArgs...]
     * extraArgs contoh: ["--wait", "5", "fdl", "/path/fdl1", "0x5500", ...]
     *
     * PENTING: panggil ini di background thread, BUKAN di main/UI thread
     * atau di dalam BroadcastReceiver.onReceive -- spd_dump itu blocking
     * (nunggu respons device dkk) dan bisa lama, sedangkan onReceive harus
     * selesai cepat (ada batas waktu dari sistem).
     */
    external fun runSpdDump(usbFd: Int, extraArgs: Array<String>): Int
}
