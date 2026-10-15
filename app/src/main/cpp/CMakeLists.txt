cmake_minimum_required(VERSION 3.22)
project(spd_native)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# --- libusb ---
# Repo resmi libusb/libusb TIDAK punya CMakeLists.txt di root (PR buat itu
# ditolak maintainer-nya). Dukungan Android resmi mereka lewat ndk-build
# (android/jni/Android.mk), jadi kita panggil ndk-build sebagai sub-proses
# dari CMake, hasilnya (libusb1.0.so) baru kita link sebagai imported lib.
set(LIBUSB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/libusb)
set(LIBUSB_SO ${LIBUSB_DIR}/android/libs/${ANDROID_ABI}/libusb1.0.so)

add_custom_command(
    OUTPUT ${LIBUSB_SO}
    COMMAND ${ANDROID_NDK}/ndk-build
        NDK_PROJECT_PATH=${LIBUSB_DIR}/android
        NDK_APPLICATION_MK=${LIBUSB_DIR}/android/jni/Application.mk
        APP_BUILD_SCRIPT=${LIBUSB_DIR}/android/jni/Android.mk
        APP_ABI=${ANDROID_ABI}
        APP_PLATFORM=android-26
        -C ${LIBUSB_DIR}/android/jni
    WORKING_DIRECTORY ${LIBUSB_DIR}/android/jni
    COMMENT "Build libusb via ndk-build untuk ${ANDROID_ABI}"
    VERBATIM
)
add_custom_target(libusb_ndkbuild DEPENDS ${LIBUSB_SO})

add_library(usb-1.0 SHARED IMPORTED)
set_target_properties(usb-1.0 PROPERTIES IMPORTED_LOCATION ${LIBUSB_SO})
add_dependencies(usb-1.0 libusb_ndkbuild)

# --- spd_dump (source TomKing062/spreadtrum_flash) ---
# main() aslinya di spd_dump.c diganti nama jadi spd_dump_main lewat
# -Dmain=spd_dump_main, supaya bisa dipanggil dari jembatan JNI kita
# tanpa bentrok dengan main() proses Android.
file(GLOB SPD_DUMP_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/spd_dump/*.c
)
list(FILTER SPD_DUMP_SOURCES EXCLUDE REGEX ".*/(chsize|BMPlatform)\\.c$")

add_library(spd_dump_core STATIC ${SPD_DUMP_SOURCES})
target_compile_definitions(spd_dump_core PRIVATE main=spd_dump_main)
target_include_directories(spd_dump_core PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/spd_dump
    ${LIBUSB_DIR}/libusb
)
add_dependencies(spd_dump_core usb-1.0)
target_link_libraries(spd_dump_core PUBLIC usb-1.0)

# --- jembatan JNI ---
add_library(spd_native SHARED jni_bridge.cpp)
target_include_directories(spd_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/spd_dump
)
target_link_libraries(spd_native
    spd_dump_core
    usb-1.0
    log
)