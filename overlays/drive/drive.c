/* Drive boost (overlay.toml). TIM6_DAC_IRQHandler feeds the modulation S&H from a table
 * of {value pointer, select} entries at 0x20000880. The mixer levels and the overdrive CV
 * 0x20003378 (written by synth_update_mixer) and the distortion drives (written by
 * synth_engine_update every 2 kHz tick) start at the calibrated rest value 0x20000838 and
 * fall as the drive rises. After each engine tick we compute boosted copies in CCM and
 * point the table entries at them, so the DAC never sees the stock values. */
#include <firmware.h>

#define CV_REST         (*(volatile uint16_t *)0x20000838) /* calibrated, 0xfff - 2*cal[0] */
#define CV_MAX          0xfff
#define DAC_TABLE       ((volatile uint32_t *)0x20000880) /* 8-byte entries, pointer first */

#define PARAM_OVERDRIVE 0x19
#define PARAM_DIST_TYPE 0x2a    /* 0-2: the panel's types 1-3 */
#define DIST_GAIN       (*(volatile uint16_t *)0x20000718) /* calibrated, cal[12] */
#define DIST_CURVE_MAX  16129   /* synth_engine_update's curve at amount 127 */
#define KNOB_MAX        127
#define UNITY           16      /* boost factors are in sixteenths */

/* Chosen by ear on the unit (2026-10-28), tuned then through a SysEx message this release
 * no longer has. Boost factors are in sixteenths. */
#define OVERDRIVE       48      /* x3 */
#define DISTORTION      48      /* x3 */
#define OD_FLOOR        400     /* 0x20003378 at full Overdrive */
#define DIST_SHAPE      8       /* types 1 and 3: halfway between even and the stock shape */

struct boosted {
    uint8_t entry;              /* DAC table entry */
    uint16_t *stock;            /* cell the stock code writes */
};

/* Mixer Osc 1, Osc 2, Sub, Noise, Ring, Audio In (synth_update_mixer). */
static const struct boosted mixer[] = {
    {9, (uint16_t *)0x2000354a}, {10, (uint16_t *)0x2000338e}, {11, (uint16_t *)0x20003324},
    {12, (uint16_t *)0x2000335a}, {13, (uint16_t *)0x20003500}, {14, (uint16_t *)0x20003358},
};
/* The three distortion stages; the type value (param 0x2a) picks which move and how far:
 * 0 drives the first fully and the second a quarter, 1 the third by half, 2 the second a
 * quarter and the third by half, with the first fully (synth_engine_update). */
static const struct boosted dist[] = {
    {23, (uint16_t *)0x2000337e}, {24, (uint16_t *)0x20003380}, {26, (uint16_t *)0x20003382},
};
/* Overdrive CV that falls a little as the knob turns up; lower gives more grit. Its
 * partner 0x2000337A (entry 28) rises with the knob and stays stock. */
static const struct boosted od_cv = {27, (uint16_t *)0x20003378};
#define MIXER_CELLS     (sizeof mixer / sizeof mixer[0])
#define DIST_CELLS      (sizeof dist / sizeof dist[0])

/* CCM (docs/hardware.md "Free RAM"): the boosted copies the DAC table points at. Kept
 * clear of 0x10000000 (overlays/transpose, overlays/ledtest) and 0x10000800
 * (overlays/randomise). Written every tick, so it needs no initialisation. */
struct state {
    uint16_t mixer[MIXER_CELLS];
    uint16_t dist[DIST_CELLS];
    uint16_t od_cv;
};
#define STATE (*(volatile struct state *)0x10000400)

void stock_synth_engine_update(void);

/* The rest value minus `distance` (how far the stock code moved the CV) × num / den,
 * clamped to the DAC range. */
static uint16_t stretch(uint16_t rest, uint16_t stock, uint32_t num, uint32_t den)
{
    int32_t distance = (int32_t)rest - (int16_t)stock;
    int32_t cv;

    if (distance <= 0)
        return stock;
    cv = rest - (int32_t)((uint32_t)distance * num / den);
    return cv < 0 ? 0 : cv > CV_MAX ? CV_MAX : (uint16_t)cv;
}

/* Types 1 and 3 drive the first stage fully, so a straight stretch reaches the bottom of
 * the DAC halfway up the Level knob. Instead map them so the bottom lands at full Level.
 * The stock swing is full·(1 - u²), u = 1 - amount/127 (synth_engine_update's curve), so
 * u = sqrt(1 - d/full) recovers the knob position; the output is rest·(1 - g(u)) with
 * g(u) = (1 - w)·u + w·u², w = shape/16: 0 grows evenly with the knob, 16 keeps the stock
 * shape (fast at the start). */
static uint32_t isqrt(uint32_t v)
{
    uint32_t r = 0, bit = 1u << 30;

    while (bit > v)
        bit >>= 2;
    while (bit) {
        if (v >= r + bit) {
            v -= r + bit;
            r = (r >> 1) + bit;
        } else {
            r >>= 1;
        }
        bit >>= 2;
    }
    return r;
}

static uint16_t bend(uint16_t rest, uint16_t stock, uint32_t shape, uint32_t full)
{
    int32_t distance = (int32_t)rest - (int16_t)stock;
    uint32_t u, g;

    if (distance <= 0 || full == 0)
        return stock;
    if ((uint32_t)distance > full)
        distance = (int32_t)full;
    if (shape > UNITY)
        shape = UNITY;
    u = isqrt((full - (uint32_t)distance) * 4096 / full * 4096); /* Q12 */
    g = ((UNITY - shape) * u + shape * (u * u >> 12)) / UNITY;
    return (uint16_t)((uint32_t)rest * g >> 12);
}

void synth_engine_update(void)
{
    uint32_t knob, scale, boosted, full;
    unsigned type;
    uint16_t rest, peak, cv;
    unsigned i;

    stock_synth_engine_update();
    rest = CV_REST;

    /* synth_update_mixer scales every level by 32 + overdrive * 7/4 (32..254); stretch
     * only what the knob adds above 32. */
    knob = synth_param_get(PARAM_OVERDRIVE);
    scale = (((knob * 0x1c00000u) >> 24) + 0x20) & 0xff;
    boosted = 32 + (scale - 32) * OVERDRIVE / UNITY;
    for (i = 0; i < MIXER_CELLS; i++) {
        STATE.mixer[i] = stretch(rest, *mixer[i].stock, boosted, scale);
        DAC_TABLE[2 * mixer[i].entry] = (uint32_t)&STATE.mixer[i];
    }

    /* From the stock value at knob 0 to the floor at full knob. */
    cv = *od_cv.stock;
    if (knob <= KNOB_MAX)
        cv = (uint16_t)((int32_t)cv + ((int32_t)OD_FLOOR - (int32_t)cv) * (int32_t)knob / KNOB_MAX);
    STATE.od_cv = cv;
    DAC_TABLE[2 * od_cv.entry] = (uint32_t)&STATE.od_cv;

    type = synth_param_get(PARAM_DIST_TYPE);
    full = DIST_GAIN * DIST_CURVE_MAX >> 10;
    peak = rest;
    for (i = 0; i < DIST_CELLS; i++)
        if ((int16_t)*dist[i].stock < (int16_t)peak)
            peak = *dist[i].stock;
    /* Every stage the type drives gets the full swing, so the three types stay distinct. */
    for (i = 0; i < DIST_CELLS; i++) {
        cv = *dist[i].stock;
        if ((int16_t)cv < (int16_t)rest)
            cv = peak;
        STATE.dist[i] = type == 1 ? stretch(rest, cv, DISTORTION, UNITY)
                                  : bend(rest, cv, DIST_SHAPE, full);
        DAC_TABLE[2 * dist[i].entry] = (uint32_t)&STATE.dist[i];
    }
}
