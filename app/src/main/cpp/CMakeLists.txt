cmake_minimum_required(VERSION 3.22)
project(spd_native)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_EXTENSIONS OFF)
set(CMAKE_CXX_STANDARD 17)
# C_EXTENSIONS OFF -> mode ISO C ketat, mematikan macro lama seperti
# "unix" (=1) yang bentrok dengan nama variabel di common.c -- sama
# seperti flag -std=c99 -pedantic di Makefile aslinya.

# --- libusb (ndk-build, lihat catatan sebelumnya) ---
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

# --- libxml2 ---
# Beda dari libusb, libxml2 resmi punya CMakeLists.txt sendiri -- bisa
# add_subdirectory() langsung. Matikan fitur yang nggak perlu/nambah
# dependency ekstra (python, http/ftp, zlib, lzma, iconv, tools cli).
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_PYTHON OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_TESTS OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_PROGRAMS OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_ZLIB OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_LZMA OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_ICONV OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_HTTP OFF CACHE BOOL "" FORCE)
set(LIBXML2_WITH_FTP OFF CACHE BOOL "" FORCE)
add_subdirectory(libxml2)

# --- spd_dump (source TomKing062/spreadtrum_flash) ---

# GITVER.h biasanya di-generate Makefile aslinya dari info git. Kita generate
# manual dari metadata git submodule-nya saat CMake configure.
set(SPD_DUMP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/spd_dump)
execute_process(
    COMMAND git rev-list HEAD --count
    WORKING_DIRECTORY ${SPD_DUMP_DIR}
    OUTPUT_VARIABLE SPD_GIT_VER
    OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_QUIET
)
execute_process(
    COMMAND git rev-parse HEAD
    WORKING_DIRECTORY ${SPD_DUMP_DIR}
    OUTPUT_VARIABLE SPD_GIT_SHA1
    OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_QUIET
)
if(NOT SPD_GIT_VER)
    set(SPD_GIT_VER "0")
endif()
if(NOT SPD_GIT_SHA1)
    set(SPD_GIT_SHA1 "unknown")
endif()
file(WRITE ${SPD_DUMP_DIR}/GITVER.h
    "#define GIT_VER \"${SPD_GIT_VER}\"\n#define GIT_SHA1 \"${SPD_GIT_SHA1}\"\n"
)

file(GLOB SPD_DUMP_SOURCES ${SPD_DUMP_DIR}/*.c)
list(FILTER SPD_DUMP_SOURCES EXCLUDE REGEX ".*/(chsize|BMPlatform)\\.c$")

# spd_dump expect header di <libusb-1.0/libusb.h>, punya kita letaknya
# langsung di libusb/libusb/. Symlink folder biar path include-nya cocok.
file(MAKE_DIRECTORY ${CMAKE_BINARY_DIR}/libusb_compat_include)
file(CREATE_LINK ${LIBUSB_DIR}/libusb
    ${CMAKE_BINARY_DIR}/libusb_compat_include/libusb-1.0
    SYMBOLIC
)

add_library(spd_dump_core STATIC ${SPD_DUMP_SOURCES})
target_compile_definitions(spd_dump_core PRIVATE
    main=spd_dump_main
    USE_LIBUSB=1
)
target_include_directories(spd_dump_core PRIVATE
    ${SPD_DUMP_DIR}
    ${CMAKE_BINARY_DIR}/libusb_compat_include
)
add_dependencies(spd_dump_core usb-1.0)
target_link_libraries(spd_dump_core PUBLIC usb-1.0 LibXml2::LibXml2)

# --- jembatan JNI ---
add_library(spd_native SHARED jni_bridge.cpp)
target_include_directories(spd_native PRIVATE ${SPD_DUMP_DIR})
target_link_libraries(spd_native
    spd_dump_core
    usb-1.0
    LibXml2::LibXml2
    log
)
