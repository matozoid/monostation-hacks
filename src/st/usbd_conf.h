/* USB device library configuration for the firmware build (tools/fwsrc.py,
 * src/libraries.tsv). Ours, not ST's template. */
#ifndef USBD_CONF_H
#define USBD_CONF_H

#include "usb_conf.h"

#define USBD_CFG_MAX_NUM      1
#define USBD_ITF_MAX_NUM      1
/* From the stock image: USBD_StrDesc is at 0x20002517 and the next RAM object the code
 * references is at 0x200025E0, so the buffer holds 198 to 201 bytes; 200 is the round
 * value in that range. No linked function uses it. */
#define USB_MAX_STR_DESC_SIZ  200

#endif
