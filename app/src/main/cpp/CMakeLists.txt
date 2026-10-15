cmake_minimum_required(VERSION 3.22)
project(spd_native)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# --- libusb (official repo, sudah ada CMake + dukungan Android resmi) ---
# Opsi ini disable contoh/testsuite bawaan libusb biar build lebih cepat
# dan nggak nyoba compile kode contoh yang nggak relevan buat Android.
set(LIBUSB_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LIBUSB_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(LIBUSB_INSTALL_TARGETS OFF CACHE BOOL "" FORCE)
add_subdirectory(libusb)
# Target yang diexport libusb/CMakeLists.txt resmi adalah "usb-1.0"

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
    ${CMAKE_CURRENT_SOURCE_DIR}/libusb/libusb
)
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
