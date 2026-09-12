cmake_minimum_required(VERSION 3.22.1)

project(bufferbloat_native_engine LANGUAGES CXX)
message(STATUS "Selected Android NDK: ${CMAKE_ANDROID_NDK}; version=${CMAKE_ANDROID_NDK_VERSION}")
option(BB_BUILD_SOCKET_HARNESS "Internal no-route socket experiments" OFF)

# This directory is intentionally an Android-NDK-only scaffold. The app's
# Gradle module packages its unavailable stub for every release ABI. A real
# netstack integration remains blocked until it has been vendored, reviewed,
# and verified for every ABI.
if(NOT ANDROID)
    message(FATAL_ERROR
        "bufferbloat_native_engine must be configured with the Android NDK toolchain")
endif()

set(BUFFERBLOAT_SUPPORTED_ABIS arm64-v8a armeabi-v7a x86_64)
if(NOT ANDROID_ABI IN_LIST BUFFERBLOAT_SUPPORTED_ABIS)
    message(FATAL_ERROR
        "Unsupported ABI '${ANDROID_ABI}'. Supported ABIs: ${BUFFERBLOAT_SUPPORTED_ABIS}")
endif()

option(BUFFERBLOAT_WITH_GVISOR
    "Build a real gVisor-backed engine instead of the safe unavailable stub" OFF)

if(BUFFERBLOAT_WITH_GVISOR)
    message(FATAL_ERROR
        "No pinned gVisor/tun2socks source is vendored yet. Keep "
        "BUFFERBLOAT_WITH_GVISOR=OFF until a reviewed adapter is supplied.")
endif()

add_library(bufferbloat_native_engine SHARED
    src/native_engine_stub.cpp
    src/jni_bridge.cpp
)

target_include_directories(bufferbloat_native_engine
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_features(bufferbloat_native_engine PRIVATE cxx_std_17)

target_compile_options(bufferbloat_native_engine PRIVATE
    -Wall
    -Wextra
    -Wpedantic
)

target_compile_definitions(bufferbloat_native_engine PRIVATE
    BB_NATIVE_ENGINE_STUB=1
)

set_target_properties(bufferbloat_native_engine PROPERTIES
    OUTPUT_NAME "bufferbloat_native_engine"
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)

if(BB_BUILD_SOCKET_HARNESS)
    add_library(bufferbloat_socket_harness SHARED harness/socket_harness.cpp)
    target_compile_features(bufferbloat_socket_harness PRIVATE cxx_std_17)
    target_compile_options(bufferbloat_socket_harness PRIVATE -Wall -Wextra -Werror)
endif()
