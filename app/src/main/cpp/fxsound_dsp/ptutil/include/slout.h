#ifndef _SLOUT_H_
#define _SLOUT_H_

#include "codedefs.h"
#if defined(__has_include)
#if __has_include(<stdio.h>)
#include <stdio.h>
#endif
#if __has_include(<wchar.h>)
#include <wchar.h>
#endif
#else
#include <stdio.h>
#include <wchar.h>
#endif

#define FIRST_LINE 1
#define NEXT_LINE  2
#define LAST_LINE  3

#define SLOUT_MAX_MSG_STRLEN 1024

#define PT_RETURN_NOT_OKAY return(NOT_OKAY)

inline int sloutDisplayFileAndLine(char *, long) { return NOT_OKAY; }

class PT_DECLSPEC CSlout {
public:
   CSlout(void) : m_linenum(1) { m_msg[0] = '\0'; m_wcp_msg[0] = L'\0'; }
   virtual ~CSlout() {}
   virtual int Display(int, char *) { return OKAY; }
   virtual int Message(int i_linetype, char *cp_msg) {
      if (i_linetype == FIRST_LINE) m_linenum = 1; else m_linenum++;
      return OKAY;
   }
   virtual int Error(int i_linetype, char *cp_msg) { return OKAY; }
   virtual int Warning(int i_linetype, char *cp_msg) { return OKAY; }

   virtual int Display_Wide(int, wchar_t *) { return OKAY; }
   virtual int Message_Wide(int i_linetype, wchar_t *wcp_msg) {
      if (i_linetype == FIRST_LINE) m_linenum = 1; else m_linenum++;
      return OKAY;
   }
   virtual int Error_Wide(int i_linetype, wchar_t *wcp_msg) { return OKAY; }
   virtual int Warning_Wide(int i_linetype, wchar_t *wcp_msg) { return OKAY; }

private:
   char m_msg[SLOUT_MAX_MSG_STRLEN];
   wchar_t m_wcp_msg[SLOUT_MAX_MSG_STRLEN];
   int m_linenum;
};

#endif //_SLOUT_H_
