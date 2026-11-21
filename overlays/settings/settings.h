/* Mod settings (docs/mod-settings.md): what an overlay needs to read its settings.
 * Include the generated <mod_settings.h>, which includes this and defines the
 * SETTING_<NAME> ids of the build. */
#ifndef SETTINGS_H
#define SETTINGS_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum { MOD_KIND_FLAG, MOD_KIND_NUMBER, MOD_KIND_SLIDER, MOD_KIND_ENUM, MOD_KIND_TEXT };
enum { MOD_WHEN_LIVE, MOD_WHEN_BOOT };

/* One entry of the generated description table. Flags have min 0, max 1; enums min 0,
 * max the last label; texts max the longest text and their default in text_default. */
struct mod_setting {
    uint16_t id;
    uint8_t kind, when, group;      /* group: index into mod_groups */
    int16_t min, max, def;
    const char *name;
    const char *const *labels;      /* enum */
    const char *text_default;       /* text */
    uint16_t text_at;               /* text: offset of its value in the RAM text buffer */
};

extern const struct mod_setting mod_settings[];
extern const char *const mod_groups[];

/* The value of a flag, number, slider or enum: from the store (clamped to the range) or
 * set through SysEx; the default for an id the store lacks, before the store is read, and
 * 0 for an id the build doesn't have. A `boot` setting should be read once at start-up. */
int mod_setting(unsigned id);

/* A text setting's value, NUL-terminated; "" for an unknown id. */
const char *mod_setting_text(unsigned id);

#ifdef __cplusplus
}
#endif

#endif
