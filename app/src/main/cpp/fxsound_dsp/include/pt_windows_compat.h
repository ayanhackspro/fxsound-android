#ifndef _INCLUDE_PT_WINDOWS_COMPAT_H_
#define _INCLUDE_PT_WINDOWS_COMPAT_H_

#if defined(__has_include)
#if __has_include("../pt_windows_compat.h")
#include "../pt_windows_compat.h" // IWYU pragma: export
#elif __has_include("pt_windows_compat.h")
#include "pt_windows_compat.h" // IWYU pragma: export
#endif
#else
#include "../pt_windows_compat.h" // IWYU pragma: export
#endif

#endif // _INCLUDE_PT_WINDOWS_COMPAT_H_
