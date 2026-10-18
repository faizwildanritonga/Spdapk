package com.uni.spdusbcheck

object NativeBridge {
    init {
        System.loadLibrary("spd_native")
    }

    // service dioper supaya native bisa panggil balik service.onNativeLogLine()
    // buat tiap baris output spd_dump -- WAJIB dipanggil di background thread.
    external fun runSpdDump(usbFd: Int, extraArgs: Array<String>, service: UsbWatchService): Int
}
