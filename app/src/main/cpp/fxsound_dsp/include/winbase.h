#ifndef _WINBASE_H_
#define _WINBASE_H_

#if defined(__has_include)
#if __has_include("../pt_windows_compat.h")
#include "../pt_windows_compat.h" // IWYU pragma: export
#elif __has_include("pt_windows_compat.h")
#include "pt_windows_compat.h" // IWYU pragma: export
#endif
#else
#include "../pt_windows_compat.h" // IWYU pragma: export
#endif

#endif // _WINBASE_H_
