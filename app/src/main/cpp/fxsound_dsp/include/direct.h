#ifndef _DIRECT_H_
#define _DIRECT_H_

#if defined(__has_include)
#if __has_include(<unistd.h>)
#include <unistd.h> // IWYU pragma: export
#endif
#if __has_include(<sys/stat.h>)
#include <sys/stat.h> // IWYU pragma: export
#endif
#else
#include <unistd.h> // IWYU pragma: export
#include <sys/stat.h> // IWYU pragma: export
#endif

#endif // _DIRECT_H_
