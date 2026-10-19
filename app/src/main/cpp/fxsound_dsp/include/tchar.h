#ifndef _TCHAR_H_
#define _TCHAR_H_

#if defined(__has_include)
#if __has_include(<wchar.h>)
#include <wchar.h> // IWYU pragma: export
#endif
#else
#include <wchar.h> // IWYU pragma: export
#endif

#ifndef _T
#define _T(x) L ## x
#endif

#ifndef _TEXT
#define _TEXT(x) L ## x
#endif

#ifndef _tcslen
#define _tcslen wcslen
#endif

#ifndef _tcscpy
#define _tcscpy wcscpy
#endif

#endif // _TCHAR_H_
