/* Mod settings store (overlay.toml, docs/mod-settings.md). */
#include <firmware.h>
#include <mod_settings.h>

/* External flash: the last 4 KB subsector of the 8 MiB chip. */
#define STORE_SECTOR    0x7ff           /* ext_flash_read takes the 4 KB sector number */
#define STORE_BYTES     0x1000
#define STORE_HEADER    8               /* MODS, version, 0, entries length u16 LE */
#define STORE_VERSION   1
#define FORMAT_VERSION  1               /* of the 0x7C descriptions */

/* SRAM3 above the heap (docs/hardware.md "Free RAM"): not cleared at boot, reachable by
 * DMA. The store is read into STORE_BUF, the values live in STATE, replies are built in
 * REPLY. */
#define STORE_BUF       ((uint8_t *)0x20024000)
#define REPLY           ((uint8_t *)0x20025000)
#define REPLY_MAX       512
#define STATE           ((struct state *)0x20025200)

#define STATE_MAGIC     0x56444f4du     /* "MODV" */
enum { STORE_NONE, STORE_READ, STORE_DAMAGED };
struct state {
    uint32_t magic;                     /* STATE_MAGIC ^ the table's address: this build */
    uint8_t store;                      /* STORE_* */
    int16_t value[MOD_SETTING_COUNT + 1];
    char text[MOD_TEXT_BYTES + 1];
};

#define MS_PREFIX_LEN   6               /* F0 00 20 29 01 61 */
#define CMD_SETTINGS    0x7c            /* byte 6; unused by the stock dispatcher */
enum { OP_DESCRIBE, OP_GET, OP_SET, OP_BUILD };
enum { SET_OK, SET_UNKNOWN, SET_RANGE, SET_REBOOT };

static const uint8_t ms_prefix[MS_PREFIX_LEN] = {0xf0, 0x00, 0x20, 0x29, 0x01, 0x61};
static const uint8_t store_magic[4] = {'M', 'O', 'D', 'S'};

int stock_ext_flash_read(unsigned sector, unsigned offset, void *buf, unsigned len);
void stock_sysex_dispatch(unsigned port, const uint8_t *msg, unsigned len);
/* midi_tx_sysex with mode in r3: port mask bit 0 USB, bit 1 DIN; mode 1 copies the
 * message into the queue (the stock dispatcher replies from its stack). */
void sysex_send_thunk(unsigned ports, const uint8_t *msg, unsigned len, unsigned mode);

static uint32_t state_magic(void)
{
    return STATE_MAGIC ^ (uint32_t)(uintptr_t)mod_settings;
}

static int find(unsigned id)
{
    int i;

    for (i = 0; i < MOD_SETTING_COUNT; i++)
        if (mod_settings[i].id == id)
            return i;
    return -1;
}

static int printable(uint8_t c)
{
    return c >= 0x20 && c < 0x7f;
}

static int clamp(const struct mod_setting *s, int v)
{
    return v < s->min ? s->min : v > s->max ? s->max : v;
}

/* Text i to its default, or to `len` bytes of `src` (printable ones only, else the default). */
static void set_text(int i, const uint8_t *src, unsigned len)
{
    const struct mod_setting *s = &mod_settings[i];
    char *dst = &STATE->text[s->text_at];
    unsigned n;

    for (n = 0; src && n < len; n++)
        if (!printable(src[n]))
            src = 0;
    if (!src || len > (unsigned)s->max) {
        src = (const uint8_t *)s->text_default;
        for (len = 0; src[len]; len++)
            ;
    }
    for (n = 0; n < len; n++)
        dst[n] = (char)src[n];
    dst[len] = 0;
}

int mod_setting(unsigned id)
{
    int i = find(id);

    if (i < 0)
        return 0;
    return STATE->magic == state_magic() ? STATE->value[i] : mod_settings[i].def;
}

const char *mod_setting_text(unsigned id)
{
    int i = find(id);

    if (i < 0 || mod_settings[i].kind != MOD_KIND_TEXT)
        return "";
    return STATE->magic == state_magic() ? &STATE->text[mod_settings[i].text_at]
                                         : mod_settings[i].text_default;
}

static uint32_t crc32(const uint8_t *p, unsigned n)
{
    uint32_t crc = 0xffffffffu;
    unsigned k;

    while (n--) {
        crc ^= *p++;
        for (k = 0; k < 8; k++)
            crc = crc >> 1 ^ (0xedb88320u & -(crc & 1));
    }
    return ~crc;
}

/* The store's entries: id u16 LE, length u8, value. Unknown ids are skipped; numbers are
 * 1 or 2 bytes (u8, s16 LE) and clamped. Returns 0 if the entries don't add up. */
static int apply(const uint8_t *p, unsigned n)
{
    unsigned at = 0, id, len;
    int i, v;

    while (at + 3 <= n) {
        id = p[at] | p[at + 1] << 8;
        len = p[at + 2];
        at += 3;
        if (at + len > n)
            return 0;
        i = find(id);
        if (i >= 0) {
            if (mod_settings[i].kind == MOD_KIND_TEXT)
                set_text(i, &p[at], len);
            else if (len == 1 || len == 2) {
                v = len == 1 ? p[at] : (int16_t)(p[at] | p[at + 1] << 8);
                STATE->value[i] = (int16_t)clamp(&mod_settings[i], v);
            }
        }
        at += len;
    }
    return at == n;
}

/* Defaults, then the store over them. Runs once per boot, from app_construct. */
static void load(void)
{
    uint8_t *b = STORE_BUF;
    unsigned i, n;

    for (i = 0; i < MOD_SETTING_COUNT; i++) {
        STATE->value[i] = mod_settings[i].def;
        if (mod_settings[i].kind == MOD_KIND_TEXT)
            set_text(i, 0, 0);
    }
    STATE->store = STORE_NONE;
    stock_ext_flash_read(STORE_SECTOR, 0, b, STORE_HEADER);
    for (i = 0; i < 4 && b[i] == store_magic[i]; i++)
        ;
    if (i == 4) {
        STATE->store = STORE_DAMAGED;
        n = b[6] | b[7] << 8;
        if (b[4] == STORE_VERSION && n <= STORE_BYTES - STORE_HEADER - 4) {
            stock_ext_flash_read(STORE_SECTOR, STORE_HEADER, b + STORE_HEADER, n + 4);
            if (crc32(b, STORE_HEADER + n) == (uint32_t)(b[STORE_HEADER + n]
                    | b[STORE_HEADER + n + 1] << 8 | b[STORE_HEADER + n + 2] << 16
                    | (uint32_t)b[STORE_HEADER + n + 3] << 24)) {
                if (apply(b + STORE_HEADER, n))
                    STATE->store = STORE_READ;
                else            /* entries before the bad one stay; start again clean */
                    for (i = 0; i < MOD_SETTING_COUNT; i++) {
                        STATE->value[i] = mod_settings[i].def;
                        if (mod_settings[i].kind == MOD_KIND_TEXT)
                            set_text((int)i, 0, 0);
                    }
            }
        }
    }
    STATE->magic = state_magic();
}

/* app_construct reads the System Settings record (sector 1, offset 0) once at boot, with
 * the NOR driver idle: read the store right after it. Every other read passes through. */
int ext_flash_read(unsigned sector, unsigned offset, void *buf, unsigned len)
{
    int r = stock_ext_flash_read(sector, offset, buf, len);

    if (sector == 1 && offset == 0)
        load();
    return r;
}

/* Replies: values as 4-bit nibbles, most significant first; strings as a 2-nibble length
 * and one byte per character. */
static unsigned put_nibbles(uint8_t *r, unsigned at, unsigned v, unsigned count)
{
    while (count--)
        r[at++] = (uint8_t)(v >> (4 * count) & 0xf);
    return at;
}

static unsigned put_string(uint8_t *r, unsigned at, const char *s)
{
    unsigned n = 0;

    while (s[n])
        n++;
    at = put_nibbles(r, at, n, 2);
    while (*s)
        r[at++] = (uint8_t)*s++;
    return at;
}

static unsigned get_nibbles(const uint8_t *p, unsigned count)
{
    unsigned v = 0;

    while (count--)
        v = v << 4 | (*p++ & 0xf);
    return v;
}

static unsigned put_value(uint8_t *r, unsigned at, int i)
{
    const struct mod_setting *s = &mod_settings[i];

    if (s->kind == MOD_KIND_TEXT)
        return put_string(r, at, mod_setting_text(s->id));
    return put_nibbles(r, at, (uint16_t)mod_setting(s->id), 4);
}

static unsigned describe(uint8_t *r, unsigned at, unsigned index)
{
    const struct mod_setting *s = &mod_settings[index];
    int k;

    at = put_nibbles(r, at, index, 2);
    at = put_nibbles(r, at, MOD_SETTING_COUNT, 2);
    at = put_nibbles(r, at, s->id, 4);
    r[at++] = s->kind;
    r[at++] = s->when;
    at = put_nibbles(r, at, s->group, 2);
    switch (s->kind) {
    case MOD_KIND_FLAG:
        r[at++] = (uint8_t)s->def;
        break;
    case MOD_KIND_NUMBER:
    case MOD_KIND_SLIDER:
        at = put_nibbles(r, at, (uint16_t)s->min, 4);
        at = put_nibbles(r, at, (uint16_t)s->max, 4);
        at = put_nibbles(r, at, (uint16_t)s->def, 4);
        break;
    case MOD_KIND_ENUM:
        at = put_nibbles(r, at, (unsigned)s->max + 1, 2);
        at = put_nibbles(r, at, (unsigned)s->def, 2);
        for (k = 0; k <= s->max; k++)
            at = put_string(r, at, s->labels[k]);
        break;
    default:
        at = put_nibbles(r, at, (unsigned)s->max, 2);
        at = put_string(r, at, s->text_default);
        break;
    }
    return put_string(r, at, s->name);
}

/* Set from a request's value (p, n bytes up to the F7); returns a SET_* status. */
static unsigned set(int i, const uint8_t *p, unsigned n)
{
    const struct mod_setting *s = &mod_settings[i];
    unsigned len, k;
    int v;

    if (STATE->magic != state_magic())  /* before the store is read: nothing to set */
        return SET_RANGE;
    if (s->kind == MOD_KIND_TEXT) {
        if (n < 2)
            return SET_RANGE;
        len = get_nibbles(p, 2);
        if (len > (unsigned)s->max || n < 2 + len)
            return SET_RANGE;
        for (k = 0; k < len; k++)
            if (!printable(p[2 + k]))
                return SET_RANGE;
        set_text(i, p + 2, len);
    } else {
        if (n < 4)
            return SET_RANGE;
        v = (int16_t)get_nibbles(p, 4);
        if (v != clamp(s, v))
            return SET_RANGE;
        STATE->value[i] = (int16_t)v;
    }
    return s->when == MOD_WHEN_BOOT ? SET_REBOOT : SET_OK;
}

static void settings_sysex(unsigned port, const uint8_t *msg, unsigned len)
{
    uint8_t *r = REPLY;
    const uint8_t *p = msg + 8;
    unsigned n = len > 9 ? len - 9 : 0;     /* request bytes between the op and F7 */
    unsigned at = 8, id, g;
    int i;

    for (at = 0; at < MS_PREFIX_LEN; at++)
        r[at] = ms_prefix[at];
    r[at++] = CMD_SETTINGS;
    r[at++] = msg[7];
    switch (msg[7]) {
    case OP_DESCRIBE:
        if (n < 2)
            return;
        g = get_nibbles(p, 2);
        if (g < MOD_SETTING_COUNT)
            at = describe(r, at, g);
        else
            r[at++] = 0x7f;
        break;
    case OP_GET:
        if (n < 4)
            return;
        id = get_nibbles(p, 4);
        at = put_nibbles(r, at, id, 4);
        i = find(id);
        r[at++] = i < 0 ? SET_UNKNOWN : SET_OK;
        if (i >= 0)
            at = put_value(r, at, i);
        break;
    case OP_SET:
        if (n < 4)
            return;
        id = get_nibbles(p, 4);
        at = put_nibbles(r, at, id, 4);
        i = find(id);
        r[at++] = (uint8_t)(i < 0 ? SET_UNKNOWN : set(i, p + 4, n - 4));
        break;
    case OP_BUILD:
        at = put_nibbles(r, at, FORMAT_VERSION, 2);
        at = put_nibbles(r, at, MOD_SETTING_COUNT, 2);
        r[at++] = STATE->magic == state_magic() ? STATE->store : STORE_NONE;
        at = put_nibbles(r, at, MOD_GROUP_COUNT, 2);
        for (g = 0; g < MOD_GROUP_COUNT; g++)
            at = put_string(r, at, mod_groups[g]);
        break;
    default:
        return;
    }
    r[at++] = 0xf7;
    sysex_send_thunk(1u << port, r, at, 1);
}

void sysex_dispatch(unsigned port, const uint8_t *msg, unsigned len)
{
    unsigned i;

    for (i = 0; i < MS_PREFIX_LEN && i < len; i++)
        if (msg[i] != ms_prefix[i])
            break;
    if (i < MS_PREFIX_LEN || len < 9 || msg[6] != CMD_SETTINGS) {
        stock_sysex_dispatch(port, msg, len);
        return;
    }
    settings_sysex(port, msg, len);
}
