#!/usr/bin/env bash
#
# Builds and runs the native FxSound DSP numerical test suite on a connected device.
#
# Usage: run-native-test.sh <android-sdk-dir> [ndk-version]
#
# The test binary is an Android executable (the DSP core has no host C++ toolchain
# available in this environment), so it is pushed to /data/local/tmp and run via adb.
#
# Exit codes:
#   0  all native tests passed
#   1  a native test failed
#   2  the environment is not ready (no device, missing SDK component, build error)

set -u

SDK_DIR="${1:-}"
NDK_VERSION="${2:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
BUILD_DIR="${SCRIPT_DIR}/build"

fail() {
    echo "[nativeTest] ERROR: $*" >&2
    exit 2
}

[ -n "${SDK_DIR}" ] || fail "Android SDK directory not supplied"
[ -d "${SDK_DIR}" ] || fail "Android SDK directory not found: ${SDK_DIR}"

if [ -z "${NDK_VERSION}" ]; then
    NDK_VERSION="$(sed -n 's/.*ndkVersion[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' \
        "${PROJECT_DIR}/app/build.gradle.kts" | head -n 1)"
fi
[ -n "${NDK_VERSION}" ] || fail "Could not determine the NDK version"

CMAKE="${SDK_DIR}/cmake/3.22.1/bin/cmake"
ADB="${SDK_DIR}/platform-tools/adb"
[ -x "${CMAKE}" ] || fail "cmake not found at ${CMAKE}"
[ -x "${ADB}" ] || fail "adb not found at ${ADB}"

TOOLCHAIN="${SDK_DIR}/ndk/${NDK_VERSION}/build/cmake/android.toolchain.cmake"
[ -f "${TOOLCHAIN}" ] || fail "NDK toolchain not found at ${TOOLCHAIN}"

echo "[nativeTest] SDK:    ${SDK_DIR}"
echo "[nativeTest] NDK:    ${NDK_VERSION}"

# --- Device -------------------------------------------------------------------
"${ADB}" start-server >/dev/null 2>&1 || true

DEVICE_SERIAL="${FXSOUND_TEST_DEVICE:-}"
if [ -n "${DEVICE_SERIAL}" ]; then
    ADB_DEVICE=("${ADB}" -s "${DEVICE_SERIAL}")
else
    ADB_DEVICE=("${ADB}")
fi

if ! "${ADB_DEVICE[@]}" get-state >/dev/null 2>&1; then
    fail "No device is connected. Connect the target device (and enable USB debugging), or set FXSOUND_TEST_DEVICE to a device serial. The native suite cannot be run without a device."
fi

ABI="$("${ADB_DEVICE[@]}" shell getprop ro.product.cpu.abi | tr -d '\r\n')"
[ -n "${ABI}" ] || fail "Could not determine the device ABI"
echo "[nativeTest] Device ABI: ${ABI}"

# --- Build --------------------------------------------------------------------
echo "[nativeTest] Configuring native test build..."
"${CMAKE}" -S "${SCRIPT_DIR}" -B "${BUILD_DIR}" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="${TOOLCHAIN}" \
    -DANDROID_ABI="${ABI}" \
    -DANDROID_PLATFORM=android-29 \
    -DCMAKE_BUILD_TYPE=Release \
    || fail "CMake configure failed"

echo "[nativeTest] Building fxsound_dsp_test..."
"${CMAKE}" --build "${BUILD_DIR}" --target fxsound_dsp_test \
    || fail "Native test build failed"

BINARY="${BUILD_DIR}/dsp/fxsound_dsp_test"
[ -f "${BINARY}" ] || fail "Test binary not produced at ${BINARY}"

# --- Run ----------------------------------------------------------------------
REMOTE_PATH="/data/local/tmp/fxsound_dsp_test"
echo "[nativeTest] Pushing test binary..."
"${ADB_DEVICE[@]}" push "${BINARY}" "${REMOTE_PATH}" >/dev/null || fail "adb push failed"
"${ADB_DEVICE[@]}" shell chmod 755 "${REMOTE_PATH}" >/dev/null 2>&1 || true

echo "[nativeTest] Running native DSP test suite on device..."
"${ADB_DEVICE[@]}" shell "${REMOTE_PATH}"
STATUS=$?

if [ "${STATUS}" -eq 0 ]; then
    echo "[nativeTest] PASS - all native DSP tests passed"
else
    echo "[nativeTest] FAIL - native DSP test suite reported failures (exit ${STATUS})" >&2
fi
exit "${STATUS}"
