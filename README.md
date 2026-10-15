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
- [ ] Fase 2: integrasi `spd_dump` native (JNI) via `--usb-fd`, kirim
      HELLO 0x7E ke BROM
- [ ] Fase 3: service lifecycle final + UI terminal interaktif

## Catatan VID/PID
VID Unisoc/Spreadtrum `0x1782` relatif konsisten, tapi PID bisa beda
per device/mode (BROM/FDL1/FDL2/recovery). `device_filter.xml` sengaja
cuma filter VID, validasi PID dilakukan dari log aplikasi.
