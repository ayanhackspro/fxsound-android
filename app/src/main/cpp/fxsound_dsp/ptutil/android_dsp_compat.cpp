/*
 * android_dsp_compat.cpp
 *
 * POSIX/Android implementations for the Win32 and hardware-specific functions used by
 * the FxSound DSP core.
 *
 * Upstream DSP code: https://github.com/fxsound2/fxsound-app
 * Modifications: Android port. The registry is replaced with an in-memory store; the
 * hardware DSP entry points are stubbed. No algorithmic behaviour is changed.
 * License: AGPL-3.0 (same as upstream)
 */

#include "codedefs.h"
#include <stdio.h>
#include <wchar.h>
#include <stdlib.h>
#include <ctype.h>
#include <string.h>
#include "pt_windows_compat.h"

// ---------------------------------------------------------------------------
// Forward declarations for header types we need
// ---------------------------------------------------------------------------
class CSlout;   // forward declare — stubs don't use it

// ---------------------------------------------------------------------------
// File I/O stubs
// ---------------------------------------------------------------------------

// fileExist_Wide — check if a wide-path file exists on Android via POSIX
int PT_DECLSPEC fileExist_Wide(wchar_t *wcp_name, int *ip_exist) {
    if (!wcp_name || !ip_exist) return NOT_OKAY;
    char c_filename[1024];
    wcstombs(c_filename, wcp_name, sizeof(c_filename) - 1);
    c_filename[sizeof(c_filename) - 1] = '\0';
    FILE *f = fopen(c_filename, "r");
    if (f) {
        fclose(f);
        *ip_exist = IS_TRUE;
    } else {
        *ip_exist = IS_FALSE;
    }
    return OKAY;
}

// fileOpen_Wide — open a file by wide path; returns POSIX FILE*
// Signature from File.h: FILE PT_DECLSPEC *fileOpen_Wide(wchar_t*, wchar_t*, CSlout*)
FILE * PT_DECLSPEC fileOpen_Wide(wchar_t *wcp_filename, wchar_t *wcp_mode, CSlout *slout_hdl) {
    (void)slout_hdl;
    if (!wcp_filename || !wcp_mode) return NULL;
    char c_filename[1024];
    char c_mode[32];
    wcstombs(c_filename, wcp_filename, sizeof(c_filename) - 1);
    c_filename[sizeof(c_filename) - 1] = '\0';
    wcstombs(c_mode, wcp_mode, sizeof(c_mode) - 1);
    c_mode[sizeof(c_mode) - 1] = '\0';
    return fopen(c_filename, c_mode);
}

// ---------------------------------------------------------------------------
// In-memory registry
//
// The upstream core keeps the effect knob and button values in the Windows
// registry, and reads them back when it communicates the parameters to the DSP
// kernels:
//
//   dfxpSetKnobValue -> dfxp_SetKnobValue_MIDI
//        -> dfxp_SessionWriteIntegerValue  (writes the value)
//        -> dfxp_CommunicateFidelity       (reads the value back)
//             -> dfxp_GetKnobValue_MIDI -> dfxp_SessionReadIntegerValue
//
// On Windows the write and the read hit the same registry key, so the value round
// trips and the kernel receives the value the user asked for. With a stub registry
// that discarded writes, the read always fell back to the default, so the
// application's effect values never reached the kernels at all: the DSP ran on its
// built-in defaults and the output was bit-identical with the effect sliders at 0
// and at 10.
//
// This store gives the upstream the same round-trip semantics without a registry.
//
// Threading: the store is only touched from the audio thread (the engine applies
// parameter changes inside its processing callback) and from initialisation. The UI
// thread never calls into the DSP directly - it only publishes the target values,
// which the audio thread reads. No locking is therefore taken, and none may be: the
// real-time audio path must not block.
// ---------------------------------------------------------------------------

namespace {

constexpr int REG_MAX_ENTRIES = 256;
constexpr int REG_MAX_KEY_CHARS = 192;
constexpr int REG_MAX_VALUE_CHARS = 64;

struct RegEntry {
    int rootKey;
    wchar_t key[REG_MAX_KEY_CHARS];
    wchar_t value[REG_MAX_VALUE_CHARS];
    bool used;
};

RegEntry g_regStore[REG_MAX_ENTRIES];
int g_regEntryCount = 0;

unsigned int regHash(int rootKey, const wchar_t* text) {
    unsigned int hash = 2166136261u;
    hash ^= static_cast<unsigned int>(rootKey);
    hash *= 16777619u;
    for (; text && *text; ++text) {
        hash ^= static_cast<unsigned int>(*text);
        hash *= 16777619u;
    }
    return hash;
}

void regStoreSet(int rootKey, const wchar_t* key, const wchar_t* value) {
    if (!key) return;
    const unsigned int slot = regHash(rootKey, key) % REG_MAX_ENTRIES;

    for (int i = 0; i < REG_MAX_ENTRIES; ++i) {
        const int index = static_cast<int>((slot + i) % REG_MAX_ENTRIES);
        RegEntry& entry = g_regStore[index];

        if (entry.used && entry.rootKey == rootKey && wcscmp(entry.key, key) == 0) {
            wcsncpy(entry.value, value ? value : L"", REG_MAX_VALUE_CHARS - 1);
            entry.value[REG_MAX_VALUE_CHARS - 1] = L'\0';
            return;
        }

        if (!entry.used) {
            entry.used = true;
            entry.rootKey = rootKey;
            wcsncpy(entry.key, key, REG_MAX_KEY_CHARS - 1);
            entry.key[REG_MAX_KEY_CHARS - 1] = L'\0';
            wcsncpy(entry.value, value ? value : L"", REG_MAX_VALUE_CHARS - 1);
            entry.value[REG_MAX_VALUE_CHARS - 1] = L'\0';
            g_regEntryCount++;
            return;
        }
    }

    // The table is full. The upstream only ever stores a few dozen keys, so this
    // should not be reached; drop the write rather than corrupt an entry.
}

bool regStoreGet(int rootKey, const wchar_t* key, wchar_t* outValue, unsigned long outLength) {
    if (!key) return false;
    const unsigned int slot = regHash(rootKey, key) % REG_MAX_ENTRIES;

    for (int i = 0; i < REG_MAX_ENTRIES; ++i) {
        const int index = static_cast<int>((slot + i) % REG_MAX_ENTRIES);
        const RegEntry& entry = g_regStore[index];

        if (!entry.used) {
            return false;
        }
        if (entry.rootKey == rootKey && wcscmp(entry.key, key) == 0) {
            if (outValue && outLength > 0) {
                wcsncpy(outValue, entry.value, outLength - 1);
                outValue[outLength - 1] = L'\0';
            }
            return true;
        }
    }
    return false;
}

// Builds "<sub_key>\<key_name>" for the regCreateKeyWithKeyname_* helpers.
void regBuildChildPath(wchar_t* out, size_t outChars, const wchar_t* subKey, const wchar_t* keyName) {
    if (!out || outChars == 0) return;
    out[0] = L'\0';
    if (!subKey || !keyName) return;
    swprintf(out, outChars, L"%s\\%s", subKey, keyName);
}

} // namespace

// ---------------------------------------------------------------------------
// Registry entry points
// ---------------------------------------------------------------------------

// regReadKey_Wide — read the default value stored at a key path.
// Reports "not present" rather than an error, which is a normal state that the
// upstream handles (a missing installation date means a first run).
int PT_DECLSPEC regReadKey_Wide(int i_root_key, wchar_t *wcp_sub_key, int *ip_key_exists,
                                wchar_t *wcp_key_value, unsigned long ul_buffer_len) {
    if (ip_key_exists) *ip_key_exists = IS_FALSE;
    if (wcp_key_value && ul_buffer_len > 0) wcp_key_value[0] = L'\0';

    if (regStoreGet(i_root_key, wcp_sub_key, wcp_key_value, ul_buffer_len)) {
        if (ip_key_exists) *ip_key_exists = IS_TRUE;
    }
    return OKAY;
}

// regCreateKey_Wide — store the default value at a key path.
int PT_DECLSPEC regCreateKey_Wide(int i_root_key, wchar_t *wcp_sub_key, wchar_t *wcp_key_name) {
    regStoreSet(i_root_key, wcp_sub_key, wcp_key_name);
    return OKAY;
}

// regCreateKeyTest_Wide — test whether a named value exists under a key path.
int PT_DECLSPEC regCreateKeyTest_Wide(int i_root_key, wchar_t *wcp_sub_key, wchar_t *wcp_key_name, int *ip_exists) {
    if (ip_exists) *ip_exists = IS_FALSE;

    wchar_t childPath[REG_MAX_KEY_CHARS];
    regBuildChildPath(childPath, REG_MAX_KEY_CHARS, wcp_sub_key, wcp_key_name);
    if (regStoreGet(i_root_key, childPath, nullptr, 0)) {
        if (ip_exists) *ip_exists = IS_TRUE;
    }
    return OKAY;
}

// regCreateKeyWithKeyname_Dword_Wide — store a numeric value under a key path.
int PT_DECLSPEC regCreateKeyWithKeyname_Dword_Wide(int i_root_key, wchar_t *wcp_sub_key, wchar_t *wcp_key_name, unsigned long ul_value) {
    wchar_t childPath[REG_MAX_KEY_CHARS];
    wchar_t text[REG_MAX_VALUE_CHARS];

    regBuildChildPath(childPath, REG_MAX_KEY_CHARS, wcp_sub_key, wcp_key_name);
    swprintf(text, REG_MAX_VALUE_CHARS, L"%lu", ul_value);
    regStoreSet(i_root_key, childPath, text);
    return OKAY;
}

// regCreateKeyWithKeyname_String_Wide — store a string value under a key path.
int PT_DECLSPEC regCreateKeyWithKeyname_String_Wide(int i_root_key, wchar_t *wcp_sub_key, wchar_t *wcp_key_name, wchar_t *wcp_value) {
    wchar_t childPath[REG_MAX_KEY_CHARS];

    regBuildChildPath(childPath, REG_MAX_KEY_CHARS, wcp_sub_key, wcp_key_name);
    regStoreSet(i_root_key, childPath, wcp_value);
    return OKAY;
}

// ---------------------------------------------------------------------------
// Math utilities (mth module)
// ---------------------------------------------------------------------------

// mthIsLong_Wide — check if a wide string represents a valid integer
int PT_DECLSPEC mthIsLong_Wide(wchar_t *wcp_string, int *ip_is_long) {
    if (!wcp_string || !ip_is_long) return NOT_OKAY;
    *ip_is_long = IS_FALSE;
    if (wcp_string[0] == L'\0') return OKAY;
    wchar_t *endp = NULL;
    wcstol(wcp_string, &endp, 10);
    if (endp && *endp == L'\0') *ip_is_long = IS_TRUE;
    return OKAY;
}

// ---------------------------------------------------------------------------
// Hardware EEPROM stubs — no ISA/PCI DSP cards on Android.
// Declared with C linkage in hutil.h (included via extern "C" { } blocks).
// ---------------------------------------------------------------------------
extern "C" {

int comHrdEepromReadUlong(short unsigned board_address, short unsigned address, unsigned long *ulpdata) {
    (void)board_address; (void)address; (void)ulpdata;
    return NOT_OKAY;
}

int comHrdEepromWriteUlong(short unsigned board_address, short unsigned address, unsigned long uldata) {
    (void)board_address; (void)address; (void)uldata;
    return NOT_OKAY;
}

// ---------------------------------------------------------------------------
// Hardware character I/O stubs (comHrdwrGetChar / PutChar)
// Declared in hrdwr.h inside extern "C" { #include "hrdwr.h" }
// ---------------------------------------------------------------------------

int comHrdwrGetChar(int i_processor_num, long *lp_val) {
    (void)i_processor_num;
    if (lp_val) *lp_val = 0;
    return NOT_OKAY;
}

int comHrdwrPutChar(int i_processor_num, long l_val) {
    (void)i_processor_num; (void)l_val;
    return NOT_OKAY;
}

int comHrdwrWriteParam(int i_processor_num, long l_param, long l_value) {
    (void)i_processor_num; (void)l_param; (void)l_value;
    return NOT_OKAY;
}

int comHrdwrWaitForFlag(int i_processor_num) {
    (void)i_processor_num;
    return NOT_OKAY;
}

int comHrdwrCheckFlag(int i_processor_num, int *ip_flag) {
    (void)i_processor_num;
    if (ip_flag) *ip_flag = 0;
    return NOT_OKAY;
}

// ---------------------------------------------------------------------------
// Software DSP play process stub — referenced by Comsftwr.c function table.
// The Android engine uses Android AudioEffect framework instead.
// ---------------------------------------------------------------------------
struct hardwareMeterValType;  // opaque forward

void dspsPlayProcess(long *lp_data, int i_num_samples, float *fp_params,
                     float *fp_memory, float *fp_freq_resp,
                     struct hardwareMeterValType *meter, int i_stereo_flag) {
    (void)lp_data; (void)i_num_samples; (void)fp_params; (void)fp_memory;
    (void)fp_freq_resp; (void)meter; (void)i_stereo_flag;
    // No-op: audio processing is handled by Android DynamicsProcessing API
}

} // extern "C"

// ---------------------------------------------------------------------------
// String utilities — from pstr.cpp (excluded due to Win32 COM dependencies)
// ---------------------------------------------------------------------------

// pstrCalcLocationOfStrInStr_Wide — pure wcsstr wrapper
int PT_DECLSPEC pstrCalcLocationOfStrInStr_Wide(const wchar_t *wcp_haystack, const wchar_t* wcp_needle,
                                               int i_start_search_index, int *ip_location, int *ip_found) {
    if (!wcp_haystack || !wcp_needle || i_start_search_index < 0) return NOT_OKAY;
    *ip_found = IS_FALSE;
    *ip_location = -1;
    const wchar_t *wcp_index = wcsstr(wcp_haystack + i_start_search_index, wcp_needle);
    if (wcp_index != NULL) {
        *ip_location = (int)(wcp_index - wcp_haystack);
        *ip_found = IS_TRUE;
    }
    return OKAY;
}
