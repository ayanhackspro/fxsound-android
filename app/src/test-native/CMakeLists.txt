cmake_minimum_required(VERSION 3.22.1)
project("fxsound_dsp_test" LANGUAGES C CXX)

# Standalone native DSP test build.
#
# This project adds the app's own CMake project as a subdirectory with the native
# test executable enabled. Because both share app/src/main/cpp/dsp_sources.cmake,
# the test binary is always built from exactly the sources that ship in the APK.
#
# The executable is a normal Android binary, so it is run on a connected device via
# adb (see run-native-test.sh). There is no host C++ toolchain in this environment
# and no Android emulator image with a usable AVD, so an on-device run is the
# only way to execute the native suite.

set(FXSOUND_BUILD_NATIVE_TESTS ON CACHE BOOL "Build the native DSP numerical test executable" FORCE)
set(FXSOUND_BUILD_MEASUREMENT_TOOL ON CACHE BOOL "Build the DSP response measurement tool" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../main/cpp ${CMAKE_CURRENT_BINARY_DIR}/dsp)
