# SPD USB Check (Fase 1)

Terminal kecil buat validasi transport USB sebelum integrasi `spd_dump`
(https://github.com/TomKing062/spreadtrum_flash) penuh. Tujuan: ukur
delay dari device SPD dicolok sampai `fd` USB siap dipakai, dan
pastikan auto-permission (tanpa popup) jalan lewat `device_filter.xml`.

## Cara pakai
1. Buka project ini di Android Studio (buka folder root, bukan `app/`).
2. Sambungkan HP (USB debugging aktif, atau langsung install APK dari
   workflow build).
3. Jalankan app, pencet tombol bulat ▶ untuk start listener.
4. Colok device SPD (mode BROM/download) via OTG.
5. Baca log: VID/PID yang terdeteksi + delay (ms) tiap tahap.

## Build via GitHub Actions
Push ke branch `main`, workflow `.github/workflows/build.yml` otomatis
build APK debug dan upload sebagai artifact (tab Actions -> run terakhir
-> Artifacts -> `spd-usb-check-debug`).

## Status
- [x] Fase 1: deteksi device attach + baca VID/PID + timing log
- [x] Fase 1.5: kirim HELLO 0x7E mentah via Android bulkTransfer (tanpa libusb)
- [ ] Fase 2: integrasi `spd_dump` + `libusb` native (JNI) via `--usb-fd`
      -- sedang dikerjakan, lihat catatan di bawah
- [ ] Fase 3: forward output spd_dump dari logcat ke UI terminal app,
      service lifecycle final, UI interaktif kirim command

## Fase 2 -- catatan integrasi native
- `app/src/main/cpp/libusb` dan `app/src/main/cpp/spd_dump` adalah git
  submodule (official `libusb/libusb` dan `TomKing062/spreadtrum_flash`).
  Clone project ini dengan `git clone --recursive`, atau kalau sudah
  terlanjur clone biasa: `git submodule update --init --recursive`.
- `spd_dump.c` di-compile dengan `-Dmain=spd_dump_main` supaya `main()`
  aslinya bisa dipanggil dari jembatan JNI (`jni_bridge.cpp`) tanpa
  bentrok dengan proses Android. Argumen yang dikirim otomatis:
  `--usb-fd <fd> --wait 5` (fd didapat dari `UsbManager.openDevice()`
  via `connection.fileDescriptor`, BUKAN dari `--usb-fd` berisi path).
- Output `printf` dari spd_dump di-redirect ke logcat (tag `spd_native`),
  belum ditampilkan di UI terminal app -- cek dengan
  `adb logcat -s spd_native` dulu buat tahap ini.
- `NativeBridge.runSpdDump()` WAJIB dipanggil dari background thread
  (bukan di `onReceive`/main thread) karena ini operasi blocking.
- Kemungkinan perlu penyesuaian nama file di `CMakeLists.txt`
  (`SPD_DUMP_SOURCES`) tergantung struktur source `spd_dump` terbaru --
  cek isi folder submodule-nya kalau build gagal karena file tidak ada
  atau ada `main()` ganda dari file lain.

## Catatan VID/PID
VID Unisoc/Spreadtrum `0x1782` relatif konsisten, tapi PID bisa beda
per device/mode (BROM/FDL1/FDL2/recovery). `device_filter.xml` sengaja
cuma filter VID, validasi PID dilakukan dari log aplikasi.
