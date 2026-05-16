cmake_minimum_required(VERSION 3.22.1)
project("srtla_android")

# Path to bond-bunny root (4 levels up from this CMakeLists.txt: cpp/main/src/srtla-lib)
set(BOND_BUNNY_ROOT ${CMAKE_SOURCE_DIR}/../../../../)
set(SRTLA_DIR ${BOND_BUNNY_ROOT}/srtla)
set(APP_JNI_DIR ${BOND_BUNNY_ROOT}/src/main/cpp)

# Suppress the unused variable warning from the srtla sources
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Wno-unused-variable -Wno-unused-function")

# 16KB page size alignment for Android 15+ compatibility
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

add_library(
    srtla_android
    SHARED
    # Bond-bunny's main JNI wrapper (stats, start/stop, network socket API)
    ${APP_JNI_DIR}/srtla_android_jni.cpp
    # SRTLA C sources
    ${SRTLA_DIR}/srtla_send.c
    ${SRTLA_DIR}/common.c
)

# Android compatibility definitions required by srtla_send.c
target_compile_definitions(srtla_android PRIVATE
    ANDROID=1
    VERSION="android-fork"
)

find_library(log-lib log)

target_link_libraries(
    srtla_android
    ${log-lib}
)

target_include_directories(
    srtla_android PRIVATE
    ${SRTLA_DIR}
)
