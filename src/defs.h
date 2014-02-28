#ifndef _DEFS_H
#define _DEFS_H

// AM Message id.
enum {
  AM_BLINKMSG = 144
};

typedef nx_struct BlinkMsg {
	// Message ID to blink
	nx_uint32_t msgId;
} BlinkMsg;


	
#endif // _DEFS_H