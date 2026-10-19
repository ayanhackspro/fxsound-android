/*
FxSound
Copyright (C) 2025  FxSound LLC

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

/*
 * pstrWide.cpp — Android/POSIX port
 * Replaces Win32 MultiByteToWideChar / WideCharToMultiByte with
 * POSIX mbstowcs / wcstombs. On Android the C locale uses UTF-8 so
 * the UTF-8 <-> wide-char conversions are equivalent.
 */

#include <string.h>
#include <stdlib.h>
#include <wchar.h>
#include <locale.h>

#include "codedefs.h"
#include "slout.h"
#include "pstr.h"

/*
 * pstrConvertToWideCharString()
 *
 * Converts a narrow (UTF-8) string to a wide character string.
 * The output buffer must already be allocated with at least
 * i_wide_char_buffer_size wchar_t entries.
 */
int PT_DECLSPEC pstrConvertToWideCharString(char *cp_original_string,
                                             wchar_t *wcp_converted_string,
                                             int i_wide_char_buffer_size)
{
    if (cp_original_string == NULL)
        return NOT_OKAY;
    if (wcp_converted_string == NULL)
        return NOT_OKAY;

    size_t n = mbstowcs(wcp_converted_string, cp_original_string,
                        (size_t)i_wide_char_buffer_size);
    if (n == (size_t)-1)
        return NOT_OKAY;

    return OKAY;
}

/*
 * pstrConvertToWideCharString_WithAlloc()
 *
 * Converts a narrow (UTF-8) string to a newly-allocated wide string.
 * Caller is responsible for free()-ing *wcpp_converted_string.
 */
int PT_DECLSPEC pstrConvertToWideCharString_WithAlloc(char *cp_original_string,
                                                       wchar_t **wcpp_converted_string,
                                                       int *ip_wide_char_buffer_size)
{
    if (cp_original_string == NULL)
        return NOT_OKAY;
    if (wcpp_converted_string == NULL)
        return NOT_OKAY;

    /* Measure required wide-char count (including null terminator) */
    size_t needed = mbstowcs(NULL, cp_original_string, 0);
    if (needed == (size_t)-1)
        return NOT_OKAY;

    needed += 1; /* include null terminator */
    *ip_wide_char_buffer_size = (int)needed;

    *wcpp_converted_string = (wchar_t *)calloc(needed, sizeof(wchar_t));
    if (*wcpp_converted_string == NULL)
        return NOT_OKAY;

    if (pstrConvertToWideCharString(cp_original_string,
                                    *wcpp_converted_string,
                                    *ip_wide_char_buffer_size) != OKAY)
    {
        free(*wcpp_converted_string);
        *wcpp_converted_string = NULL;
        return NOT_OKAY;
    }

    return OKAY;
}

/*
 * pstrConvertWideCharStringToAnsiCharString()
 *
 * Converts a wide string to a narrow (UTF-8) string.
 * The output buffer must already be allocated.
 */
int PT_DECLSPEC pstrConvertWideCharStringToAnsiCharString(wchar_t *wcp_original_string,
                                                           char *cp_converted_string,
                                                           int i_ansi_char_buffer_size)
{
    if (wcp_original_string == NULL)
        return NOT_OKAY;
    if (cp_converted_string == NULL)
        return NOT_OKAY;

    size_t n = wcstombs(cp_converted_string, wcp_original_string,
                        (size_t)i_ansi_char_buffer_size);
    if (n == (size_t)-1)
        return NOT_OKAY;

    return OKAY;
}

/*
 * pstrCovertWideCharStringToUTF8String_WithAlloc()
 *
 * Converts a wide string to a newly-allocated UTF-8 string.
 * *ip_number_of_bytes is set to the byte count including the null terminator.
 * Caller is responsible for free()-ing *cpp_converted_utf8_string.
 */
int PT_DECLSPEC pstrCovertWideCharStringToUTF8String_WithAlloc(wchar_t *wcp_original_string,
                                                                char **cpp_converted_utf8_string,
                                                                int *ip_number_of_bytes)
{
    *cpp_converted_utf8_string = NULL;
    *ip_number_of_bytes = 0;

    if (wcp_original_string == NULL)
        return NOT_OKAY;

    /* Measure required byte count (excluding null terminator) */
    size_t needed = wcstombs(NULL, wcp_original_string, 0);
    if (needed == (size_t)-1)
        return NOT_OKAY;

    needed += 1; /* include null terminator */
    *ip_number_of_bytes = (int)needed;

    *cpp_converted_utf8_string = (char *)calloc(needed, sizeof(char));
    if (*cpp_converted_utf8_string == NULL)
        return NOT_OKAY;

    size_t n = wcstombs(*cpp_converted_utf8_string, wcp_original_string, needed);
    if (n == (size_t)-1) {
        free(*cpp_converted_utf8_string);
        *cpp_converted_utf8_string = NULL;
        *ip_number_of_bytes = 0;
        return NOT_OKAY;
    }

    return OKAY;
}

/*
 * pstrCovertUTF8StringToWideCharString_WithAlloc()
 *
 * Converts a UTF-8 string to a newly-allocated wide string.
 * *ip_number_of_wchar is set to the wchar_t count including the null terminator.
 * Caller is responsible for free()-ing *wcpp_converted_string.
 */
int PT_DECLSPEC pstrCovertUTF8StringToWideCharString_WithAlloc(char *cp_original_utf8_string,
                                                                wchar_t **wcpp_converted_string,
                                                                int *ip_number_of_wchar)
{
    *wcpp_converted_string = NULL;
    *ip_number_of_wchar = 0;

    if (cp_original_utf8_string == NULL)
        return NOT_OKAY;

    /* Measure required wide-char count (excluding null terminator) */
    size_t needed = mbstowcs(NULL, cp_original_utf8_string, 0);
    if (needed == (size_t)-1)
        return NOT_OKAY;

    needed += 1; /* include null terminator */
    *ip_number_of_wchar = (int)needed;

    *wcpp_converted_string = (wchar_t *)calloc(needed, sizeof(wchar_t));
    if (*wcpp_converted_string == NULL)
        return NOT_OKAY;

    size_t n = mbstowcs(*wcpp_converted_string, cp_original_utf8_string, needed);
    if (n == (size_t)-1) {
        free(*wcpp_converted_string);
        *wcpp_converted_string = NULL;
        *ip_number_of_wchar = 0;
        return NOT_OKAY;
    }

    return OKAY;
}