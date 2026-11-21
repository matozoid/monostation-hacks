/* Host harness for overlays/settings/settings.c (tests/test_modsettings.py): the SRAM3
 * addresses are mapped with mmap, the flash read comes from a store file, replies are
 * printed as hex. stdin: one SysEx message per line in hex; "boot" reads the store. */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>

#include <mod_settings.h>

static uint8_t flash[0x800000];

int stock_ext_flash_read(unsigned sector, unsigned offset, void *buf, unsigned len)
{
    memcpy(buf, flash + (sector << 12) + offset, len);
    return 0;
}

void stock_sysex_dispatch(unsigned port, const uint8_t *msg, unsigned len)
{
    (void)msg;
    printf("stock %u %u\n", port, len);
}

void sysex_send_thunk(unsigned ports, const uint8_t *msg, unsigned len, unsigned mode)
{
    printf("reply %u %u ", ports, mode);
    for (unsigned i = 0; i < len; i++)
        printf("%02x", msg[i]);
    printf("\n");
}

int ext_flash_read(unsigned sector, unsigned offset, void *buf, unsigned len);
void sysex_dispatch(unsigned port, const uint8_t *msg, unsigned len);

int main(int argc, char **argv)
{
    char line[4096];
    uint8_t msg[2048], record[0x18];

    if (mmap((void *)0x20024000, 0x2000, PROT_READ | PROT_WRITE,
             MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0) != (void *)0x20024000)
        return 2;
    memset((void *)0x20024000, 0xa5, 0x2000);       /* SRAM3 is noise at power-on */
    memset(flash, 0xff, sizeof flash);
    if (argc > 1) {
        FILE *f = fopen(argv[1], "rb");
        if (!f || fread(flash + 0x7ff000, 1, 0x1000, f) != 0x1000)
            return 3;
        fclose(f);
    }
    while (fgets(line, sizeof line, stdin)) {
        unsigned n = 0;
        line[strcspn(line, "\n")] = 0;
        if (!strcmp(line, "boot")) {
            ext_flash_read(1, 0, record, sizeof record);
            continue;
        }
        if (sscanf(line, "get %x", &n) == 1) {
            printf("value %d\n", mod_setting(n));
            continue;
        }
        if (sscanf(line, "text %x", &n) == 1) {
            printf("text '%s'\n", mod_setting_text(n));
            continue;
        }
        for (n = 0; line[2 * n] && line[2 * n + 1]; n++)
            sscanf(line + 2 * n, "%2hhx", &msg[n]);
        sysex_dispatch(0, msg, n);
    }
    return 0;
}
