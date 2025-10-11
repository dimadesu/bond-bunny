cmake_minimum_required(VERSION 3.22.1)

project("srtla_android")

find_library(log-lib log)

# Set path to SRTLA source (fork with Android patches)
set(SRTLA_DIR ${CMAKE_SOURCE_DIR}/../../../srtla)

# Build SRTLA with Android patches - minimal JNI wrapper + original code
add_library(srtla_android SHARED
    srtla_android_jni.cpp      # Minimal JNI wrapper (new)
    ${SRTLA_DIR}/srtla_send.c  # Original SRTLA with Android patches
    ${SRTLA_DIR}/common.c      # Original SRTLA common functions
)

# Android build definitions - enables Android compatibility layer
target_compile_definitions(srtla_android PRIVATE 
    ANDROID=1
    VERSION="android-fork"
)

# Original SRTLA compiler flags
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wall")

target_include_directories(srtla_android PUBLIC ${SRTLA_DIR})

target_link_libraries(srtla_android ${log-lib})
