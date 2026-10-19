cmake_minimum_required(VERSION 3.22.1)
project("fxsound_dsp" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# Definitions required by FxSound DSP core
add_definitions(
    -D__ANDROID__
    -DPT_NON_MFC
    -DDSPSOFT_TARGET
    -DPT_DSP_BUILD=PT_DSP_DFX
    -D_LIB
)

# Optimization flags for ARM64 and real-time audio stability
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -ffast-math -Wall -include pt_windows_compat.h")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -Wall -include pt_windows_compat.h")

# Shared source and include lists. Also used by the standalone native test build in
# app/src/test-native, so the test binary always exercises exactly the sources that
# ship in the APK.
set(FXSOUND_DSP_ROOT ${CMAKE_CURRENT_SOURCE_DIR})
include(${CMAKE_CURRENT_SOURCE_DIR}/dsp_sources.cmake)

include_directories(${FXSOUND_DSP_INCLUDES})

# The DSP core is compiled once into an object library and then linked into both the
# shipped shared library and the native test executable.
add_library(fxsound_dsp_core OBJECT
    ${FXSOUND_DSP_CORE_SOURCES}
    ${FXSOUND_DSP_ROOT}/engine/fxsound_engine.cpp
)
set_target_properties(fxsound_dsp_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(fxsound_dsp SHARED
    $<TARGET_OBJECTS:fxsound_dsp_core>
    ${FXSOUND_DSP_ROOT}/jni/fxsound_jni.cpp
)

target_link_libraries(fxsound_dsp
    log
    android
)

# Native DSP numerical test executable.
#
# Off by default so the Android Gradle build is unaffected. The standalone native
# test project (app/src/test-native) turns it on and runs the binary on a device.
option(FXSOUND_BUILD_NATIVE_TESTS "Build the native DSP numerical test executable" OFF)

if(FXSOUND_BUILD_NATIVE_TESTS)
    add_executable(fxsound_dsp_test
        ${FXSOUND_DSP_ROOT}/tests/test_dsp_numerical.cpp
        $<TARGET_OBJECTS:fxsound_dsp_core>
    )
    target_link_libraries(fxsound_dsp_test
        log
        android
    )
endif()

# Response measurement tool.
#
# Measures the upstream DSP's magnitude and level response so the Android companion
# engine can be a measured emulation of it rather than a hand-authored guess. Off by
# default; the standalone native test project turns it on.
option(FXSOUND_BUILD_MEASUREMENT_TOOL "Build the DSP response measurement tool" OFF)

if(FXSOUND_BUILD_MEASUREMENT_TOOL)
    add_executable(fxsound_response_measure
        ${FXSOUND_DSP_ROOT}/tests/measure_fx_response.cpp
        $<TARGET_OBJECTS:fxsound_dsp_core>
    )
    target_link_libraries(fxsound_response_measure
        log
        android
    )
endif()
