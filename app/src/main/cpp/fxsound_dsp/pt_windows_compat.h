#ifndef _PT_WINDOWS_COMPAT_H_
#define _PT_WINDOWS_COMPAT_H_

// Cross-platform compatibility definitions for FxSound DSP on Android

#ifdef __cplusplus
extern "C" {
#endif

#if defined(__has_include)
#if __has_include(<stdint.h>)
#include <stdint.h>
#endif
#if __has_include(<stddef.h>)
#include <stddef.h>
#endif
#if __has_include(<stdbool.h>)
#include <stdbool.h>
#endif
#if __has_include(<string.h>)
#include <string.h>
#endif
#if __has_include(<wchar.h>)
#include <wchar.h>
#endif
#if __has_include(<time.h>)
#include <time.h>
#endif
#if __has_include(<stdio.h>)
#include <stdio.h>
#endif
#if __has_include(<stdlib.h>)
#include <stdlib.h>
#endif
#else
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <wchar.h>

#endif

#ifndef WIN32
#ifndef __int64
#define __int64 long long
#endif

typedef uint8_t BYTE;
typedef uint16_t WORD;
typedef uint32_t DWORD;
typedef int32_t BOOL;
typedef int32_t LONG;
typedef uint32_t UINT;
typedef unsigned long ULONG;
typedef void *HANDLE;
typedef void *HWND;
typedef void *HINSTANCE;
typedef void *HMODULE;
typedef void *LPVOID;
typedef const void *LPCVOID;
typedef char *LPSTR;
typedef const char *LPCSTR;
typedef wchar_t *LPWSTR;
typedef const wchar_t *LPCWSTR;

#ifdef UNICODE
typedef LPCWSTR LPCTSTR;
typedef LPWSTR LPTSTR;
#else
typedef LPCSTR LPCTSTR;
typedef LPSTR LPTSTR;
#endif

typedef uintptr_t WPARAM;
typedef intptr_t LPARAM;
typedef intptr_t LRESULT;
typedef void *LPSECURITY_ATTRIBUTES;

typedef struct _FILETIME {
  DWORD dwLowDateTime;
  DWORD dwHighDateTime;
} FILETIME, *PFILETIME, *LPFILETIME;

#ifndef TRUE
#define TRUE 1
#endif

#ifndef FALSE
#define FALSE 0
#endif

typedef union _LARGE_INTEGER {
  struct {
    DWORD LowPart;
    LONG HighPart;
  };
  int64_t QuadPart;
} LARGE_INTEGER;

static inline int QueryPerformanceCounter(LARGE_INTEGER *lpPerformanceCount) {
  if (!lpPerformanceCount)
    return 0;
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  lpPerformanceCount->QuadPart = (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
  return 1;
}

#define MB_OK 0
#define MB_ICONERROR 0
#define MB_TASKMODAL 0
#define MB_ICONINFORMATION 0
#define MB_TOPMOST 0

static inline int MessageBoxA(void *hWnd, const char *lpText,
                              const char *lpCaption, unsigned int uType) {
  (void)hWnd;
  (void)lpText;
  (void)lpCaption;
  (void)uType;
  return 1;
}

static inline int MessageBoxW(void *hWnd, const wchar_t *lpText,
                              const wchar_t *lpCaption, unsigned int uType) {
  (void)hWnd;
  (void)lpText;
  (void)lpCaption;
  (void)uType;
  return 1;
}

#define MessageBox MessageBoxW

static inline int IsDebuggerPresent(void) { return 0; }
static inline void DebugBreak(void) {}

static inline int FreeLibrary(void *hLibModule) {
  (void)hLibModule;
  return 1;
}
static inline void *GetProcAddress(void *hModule, const char *lpProcName) {
  (void)hModule;
  (void)lpProcName;
  return NULL;
}
static inline void *LoadLibraryA(const char *lpLibFileName) {
  (void)lpLibFileName;
  return NULL;
}
static inline void *LoadLibraryW(const wchar_t *lpLibFileName) {
  (void)lpLibFileName;
  return NULL;
}
#ifndef LoadLibrary
#define LoadLibrary LoadLibraryW
#endif

#ifndef _wtoi
#define _wtoi(str) ((int)wcstol((str), NULL, 10))
#endif

#ifndef _wtol
#define _wtol(str) (wcstol((str), NULL, 10))
#endif

#ifndef _wtof
#define _wtof(str) (wcstof((str), NULL))
#endif

static inline int _wfopen_s(FILE **pFile, const wchar_t *filename,
                            const wchar_t *mode) {
  if (!pFile || !filename || !mode)
    return 22;
  char c_filename[1024];
  char c_mode[32];
  wcstombs(c_filename, filename, sizeof(c_filename) - 1);
  c_filename[sizeof(c_filename) - 1] = '\0';
  wcstombs(c_mode, mode, sizeof(c_mode) - 1);
  c_mode[sizeof(c_mode) - 1] = '\0';
  *pFile = fopen(c_filename, c_mode);
  return (*pFile != NULL) ? 0 : 2;
}

#endif // !WIN32

#ifdef __cplusplus
}

#include <cstdarg>
#include <cwchar>
#include <type_traits>

inline int swprintf_compat(wchar_t *buf, const wchar_t *fmt, ...) {
  va_list args;
  va_start(args, fmt);
  int res = vswprintf(buf, 2048, fmt, args);
  va_end(args);
  return res;
}

template <typename SizeT, typename = typename std::enable_if<
                              std::is_integral<SizeT>::value>::type>
inline int swprintf_compat(wchar_t *buf, SizeT count, const wchar_t *fmt, ...) {
  va_list args;
  va_start(args, fmt);
  int res = vswprintf(buf, static_cast<size_t>(count), fmt, args);
  va_end(args);
  return res;
}

#ifndef swprintf
#define swprintf swprintf_compat
#endif

#endif // __cplusplus

#endif // _PT_WINDOWS_COMPAT_H_
