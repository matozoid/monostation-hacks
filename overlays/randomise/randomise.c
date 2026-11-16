/* #13: context-sensitive Mutate, #45: Mutate in the Patches view nudges the patch, which
 * glides to its new values, and #60: Mutate moves values a little from where they are
 * (steps mildly, the patch a bit more).
 * The offsets are those stock ui_button_event uses (docs/source-map.md ui_button_event;
 * Shift+Oct pattern transpose walks the same steps). */
#include <firmware.h>

#define FIELD(obj, off, type) (*(type *)((char *)(obj) + (off)))

/* ui_root */
#define UI_SHIFT        0x10    /* object whose cell +0x10 is the Shift state */
#define UI_MUTATE_GATE  0x14    /* cell: Mutate is ignored unless byte 0 is 0 and byte 2 is not 5/6 */
#define UI_MODE_VIEW    0x2c
#define UI_VIEW_38      0x38    /* view whose cell +0x10 every other button clears */
#define UI_PATCH_VIEW   0x3c    /* Patches view; the arrows page it */
#define UI_FILTER_VIEW  0x70    /* filter section view: its pots set params through +0x14 */
#define UI_ACTIVE_VIEW  0x8c
#define UI_LOCK         0xb0    /* cell: bit 1 blocks most buttons */
#define UI_FORWARD      0x15e   /* byte: forward every button to the view at +0x60 */

/* mode view */
#define MV_KEYBOARDS    0x20    /* per-track keyboard views (Note and Expand) */
#define MV_TRACKS       0x68    /* per-track step objects: Osc 1, Osc 2, Mod Seq */
#define MV_TRACK        0x74
#define MV_SUBVIEW      0x75
#define TRACK_STEPS     0x0c    /* cell holding a pointer to the pattern's 16 steps */

/* keyboard view */
#define KV_LAYOUT       0x30
#define LAYOUT_NOTES    0x14    /* pad-to-note table, indexed by keyboard_pad_to_note */
#define EXPAND_PADS     32

#define BUTTON_MUTATE   0x1d    /* Pattern Settings; Mutate with Shift */

/* The param sink every section view's pots call: slot 0 forwards to param_set, which
 * stores the value in the live patch, updates the synth and sends it to MIDI out.
 * Values are raw, before the param table's centre is subtracted. */
#define VIEW_PARAM_SINK 0x14
#define SOURCE_PANEL    1
typedef void (*param_set_fn)(void *sink, unsigned id, unsigned value, unsigned source);

/* Mode view subviews. On the Mod Seq track, Velocity is the level view; Dual is the two
 * Osc keyboards (Osc 1 + Osc 2 pressed together), with MV_TRACK 0 or 1. */
enum { SUBVIEW_NOTE = 0, SUBVIEW_VELOCITY = 1, SUBVIEW_GATE = 2, SUBVIEW_GLIDE = 3, SUBVIEW_EXPAND = 5,
       SUBVIEW_DUAL = 6 };
enum { MOD_SEQ = 2, TRACKS = 3 };

struct step {                   /* docs/session-format.md "Pattern" */
    uint8_t note;               /* 0 = empty */
    uint8_t velocity;           /* Mod Seq: the level, 0 = none */
    uint8_t gate;               /* micro-steps, 6 per step */
    uint8_t glide;              /* 0xff = none */
    uint8_t patch;
    uint8_t unused[3];
};

static unsigned random_below(unsigned n)
{
    return rng_read() % n;
}

/* #60: how far a Mutate moves values from where they are. Steps move +-1..STEP_LEVEL,
 * patch params by up to span * PATCH_LEVEL / 16. */
#define STEP_LEVEL      1
#define PATCH_LEVEL     2

/* +-(1..STEP_LEVEL), each sign equally likely. */
static int random_offset(void)
{
    int n = (int)random_below(STEP_LEVEL) + 1;
    return random_below(2) ? n : -n;
}

static int clamp(int value, int lo, int hi)
{
    return value < lo ? lo : value > hi ? hi : value;
}

#define MILLISECONDS    (*(volatile uint32_t *)0x200007e4) /* TIM5 ch3 1 ms count */

/* #45: a patch Mutate glides each changed param to its new value over one beat (four
 * 1/16 steps) at the session tempo, and a held Mutate repeats once a beat. The clock state
 * is the context of soft timer 0 (docs/sequencer.md "Time base"); its tempo cell +0x14
 * holds the BPM in field 0. With external sync that is the session's own tempo, not the
 * incoming one. */
#define TIMER_CONTEXTS  ((void *volatile *)0x200032f4)
#define CLOCK_TEMPO     0x14
#define DEFAULT_BEAT_MS 500

/* The distinct notes on the Expand view's pads (scale_range takes the scale from them): the current scale over its four octaves
 * (two for Chromatic), relative to C; the router adds the root note on playback. */
static unsigned expand_notes(void *keyboard_view, uint8_t *notes)
{
    const uint8_t *table = (const uint8_t *)FIELD(keyboard_view, KV_LAYOUT, void *) + LAYOUT_NOTES;
    unsigned count = 0;
    for (unsigned pad = 0; pad < EXPAND_PADS; pad++) {
        uint8_t note = table[keyboard_pad_to_note(keyboard_view, pad)];
        unsigned i = 0;
        while (i < count && notes[i] != note)
            i++;
        if (note && i == count) {
            /* Insert sorted, so a nudge can step to the next note of the scale. */
            while (i && notes[i - 1] > note) {
                notes[i] = notes[i - 1];
                i--;
            }
            notes[i] = note;
            count++;
        }
    }
    return count;
}

/* The notes a note Mutate may move to: the scale's notes from the start of the octave
 * holding the pattern's lowest note to the end of the octave holding its highest. Notes
 * are relative to the root, so octaves start at the root. Sorted; 0 when no note. */
static unsigned scale_range(void *keyboard_view, const struct step *steps, uint8_t *notes)
{
    uint8_t pads[EXPAND_PADS];
    unsigned pad_count = expand_notes(keyboard_view, pads);
    unsigned classes = 0, lo = 127, hi = 0;
    for (unsigned i = 0; i < pad_count; i++)
        classes |= 1u << (pads[i] % 12);
    for (unsigned i = 0; i < 16; i++)
        if (steps[i].note) {
            if (steps[i].note < lo)
                lo = steps[i].note;
            if (steps[i].note > hi)
                hi = steps[i].note;
        }
    unsigned count = 0;
    if (hi < lo)
        return 0;
    for (unsigned note = lo / 12 * 12; note <= hi / 12 * 12 + 11 && note < 128; note++)
        if (note && (classes & (1u << (note % 12))))
            notes[count++] = note;
    return count;
}

/* Index of the pad level nearest to value (glide and velocity pads). */
static unsigned pad_index(unsigned value)
{
    unsigned best = 0;
    for (unsigned i = 1; i < 16; i++)
        if ((pad_levels[i] > value ? pad_levels[i] - value : value - pad_levels[i]) <
            (pad_levels[best] > value ? pad_levels[best] - value : value - pad_levels[best]))
            best = i;
    return best;
}

/* Whether Mutate changes this step. On the Osc tracks a step is a note. A Mod Seq step
 * can hold a level without a note (it lights and plays that level): levels change only
 * where there is one, notes and gates only on notes, glide on either. */
static int step_used(const struct step *step, unsigned track, unsigned subview)
{
    if (track == MOD_SEQ && subview == SUBVIEW_VELOCITY)
        return step->velocity != 0;
    if (track == MOD_SEQ && subview == SUBVIEW_GLIDE)
        return step->note || step->velocity;
    return step->note != 0;
}

/* #60: move one step's field a little from where it is. */
static void nudge_step(struct step *step, unsigned subview, unsigned max_gate,
                       const uint8_t *notes, unsigned note_count)
{
    switch (subview) {
    case SUBVIEW_EXPAND: {
        /* A nearby note of the scale: notes are sorted, so step the index. */
        unsigned i = 0;
        while (i + 1 < note_count && notes[i] < step->note)
            i++;
        step->note = notes[clamp((int)i + random_offset(), 0, (int)note_count - 1)];
        break;
    }
    case SUBVIEW_VELOCITY:
        /* The velocity view's pad levels: (pad + 1) * 8, 127 for the top pad. */
        step->velocity = pad_levels[clamp((int)pad_index(step->velocity) + random_offset(), 1, 15)];
        break;
    case SUBVIEW_GATE: {
        int unit = (int)max_gate / 8 + 1;
        step->gate = clamp((int)step->gate + unit * random_offset(), 1, (int)max_gate);
        break;
    }
    case SUBVIEW_GLIDE:
        if (random_below(8) < STEP_LEVEL)       /* on/off flips with chance STEP_LEVEL/8 */
            step->glide = step->glide == 0xff ? pad_levels[random_below(4 * STEP_LEVEL) + 1] : 0xff;
        else if (step->glide != 0xff)
            step->glide = pad_levels[clamp((int)pad_index(step->glide) + random_offset(), 1, 15)];
        break;
    }
}

/* One synth param of the patch randomiser (param ids and raw ranges: the table at
 * 0x0802A62A, tools/patchbank.py PARAMS). lo..hi is the playable range a nudge stays in;
 * a param at its rest value stays there with chance rest/8. A switch (range under 8)
 * changes with chance 1/8, to rest with chance rest/8, else to any value in lo..hi. A
 * param with lo == hi is never changed. Params not listed keep their value: the
 * category and genre, the octaves and ranges (the patch stays in its register), patch
 * level, audio-in level, the env-to-amp switch and the selected routing. */
struct param_rule {
    uint8_t id, lo, hi, value, rest;
};

/* A mod-matrix depth: mostly off, else up to +-depth around the centre. */
#define DEPTH(id, centre, depth, rest) { id, (centre) - (depth), (centre) + (depth), centre, rest }

enum { FILTER_CUTOFF = 0x1b, FILTER_SHAPE = 0x1c, FILTER_KEY_TRACK = 0x1f,
       ENV_DECAY = 0x21, ENV_SUSTAIN = 0x22 };
enum { SHAPE_BAND_PASS = 1, SHAPE_HIGH_PASS = 2 };

static const struct param_rule patch_rules[] = {
    { 0x02,   0,   1,   0, 0 },     /* env retrigger */
    { 0x04,   0,   3,   0, 0 },     /* osc 1 waveform */
    { 0x05,  16, 112,   0, 0 },     /* osc 1 pulse width: no silent extremes */
    { 0x06, 128, 128, 128, 8 },     /* osc 1 coarse: centred */
    { 0x07, 100, 100, 100, 8 },     /* osc 1 fine: centred */
    { 0x09,   0,  60,   0, 6 },     /* osc 1 glide */
    { 0x0b,   0,   3,   0, 0 },     /* osc 2 waveform */
    { 0x0c,  16, 112,   0, 0 },     /* osc 2 pulse width */
    { 0x0d, 128, 128, 128, 8 },     /* osc 2 coarse: centred */
    { 0x0e,  88, 112, 100, 3 },     /* osc 2 fine: a little detune */
    { 0x10,   0,  60,   0, 6 },     /* osc 2 glide */
    { 0x11,   0,   1,   0, 6 },     /* osc sync */
    { 0x13, 128, 255,   0, 0 },     /* mixer: osc 1, always audible */
    { 0x14,   0, 255,   0, 0 },     /* osc 2 */
    { 0x15,   0, 200,   0, 4 },     /* sub */
    { 0x16,   0, 120,   0, 6 },     /* noise */
    { 0x18,   0, 160,   0, 6 },     /* ring mod */
    { 0x19,   0,  90,   0, 0 },     /* filter overdrive */
    { 0x1a,   0, 100,   0, 0 },     /* resonance: short of self-oscillation */
    { 0x1b,  60, 255,   0, 0 },     /* cutoff: never closed */
    { 0x1c,   1,   2,   0, 4 },     /* shape: low pass half the time, else band or high pass */
    { 0x1d,   0,   1,   0, 0 },     /* slope */
    { 0x1e,   0,   0,   0, 8 },     /* filter bypass: off */
    { 0x1f,   0,   7,   0, 0 },     /* key tracking */
    { 0x20,   0,  90,   0, 4 },     /* attack */
    { 0x21,  10, 127,   0, 0 },     /* decay */
    { 0x22,   0, 127,   0, 0 },     /* sustain */
    { 0x23,   0,  90,   0, 0 },     /* release */
    { 0x24,   0,   3,   0, 0 },     /* LFO waveform */
    { 0x25,   0, 255,   0, 0 },     /* LFO rate */
    { 0x26,   0,  34,   0, 0 },     /* LFO sync rate */
    { 0x27,   0,   1,   0, 0 },     /* LFO clock sync */
    { 0x28,   0,   1,   0, 0 },     /* LFO key sync */
    { 0x29,   0,  80,   0, 4 },     /* distortion */
    { 0x2a,   0,   2,   0, 0 },     /* distortion type */
    /* Mod matrix: envelope, LFO, mod seq and velocity to osc 1 pitch, osc 1 PW, osc 2
     * pitch, osc 2 PW, amp, cutoff, distortion and aux. */
    DEPTH(0x2b, 128,  40, 7), DEPTH(0x2c,  64,  40, 6), DEPTH(0x2d, 128,  40, 7),
    DEPTH(0x2e,  64,  40, 6), DEPTH(0x30, 128, 100, 3), DEPTH(0x31,  64,  40, 6),
    DEPTH(0x32,  64,  63, 6),
    DEPTH(0x33, 128,  20, 7), DEPTH(0x34,  64,  40, 5), DEPTH(0x35, 128,  20, 7),
    DEPTH(0x36,  64,  40, 5), DEPTH(0x37,  64,  30, 7), DEPTH(0x38, 128,  80, 5),
    DEPTH(0x39,  64,  40, 6), DEPTH(0x3a,  64,  63, 6),
    DEPTH(0x3b, 128,  20, 7), DEPTH(0x3c,  64,  40, 6), DEPTH(0x3d, 128,  20, 7),
    DEPTH(0x3e,  64,  40, 6), DEPTH(0x3f,  64,  30, 7), DEPTH(0x40, 128,  80, 6),
    DEPTH(0x41,  64,  40, 6), DEPTH(0x42,  64,  63, 6),
    DEPTH(0x43,  64,  20, 7), DEPTH(0x44,  64,  40, 6), DEPTH(0x45,  64,  20, 7),
    DEPTH(0x46,  64,  40, 6), DEPTH(0x47,  64,  40, 5), DEPTH(0x48,  64,  50, 5),
    DEPTH(0x49,  64,  40, 6), DEPTH(0x4a,  64,  63, 6),
};

enum { RULES = sizeof patch_rules / sizeof patch_rules[0] };

/* A small random step away from the param's current value: a continuous param changes
 * with chance PATCH_LEVEL/4, by up to +-(span * PATCH_LEVEL / 16 + 1); a switch with
 * chance PATCH_LEVEL/16. */
static unsigned nudge_param(const struct param_rule *rule, unsigned value)
{
    unsigned span = rule->hi - rule->lo;
    if (span == 0)
        return value;
    if (span < 8) {
        if (random_below(16) >= PATCH_LEVEL)
            return value;
        if (random_below(8) < rule->rest)
            return rule->value;
        return rule->lo + random_below(span + 1);
    }
    if (random_below(4) >= PATCH_LEVEL || (value == rule->value && random_below(8) < rule->rest))
        return value;
    /* Never further out of lo..hi than the value already is. */
    int step = span * PATCH_LEVEL / 16 + 1;
    int next = (int)value + (int)random_below(2 * step + 1) - step;
    if (next > rule->hi)
        next = (int)value > rule->hi ? (int)value : rule->hi;
    if (next < rule->lo)
        next = (int)value < rule->lo ? (int)value : rule->lo;
    return next;
}

/* CCM (docs/hardware.md "Free RAM"): not cleared at boot, so it carries a magic word.
 * Clear of transpose (0x10000000) and drive (0x10000400). */
#define STATE_MAGIC     0x544e5253u /* "SRNT": changes with the layout */
struct state {
    uint32_t magic;
    uint8_t gliding;
    uint32_t glide_start;
    uint32_t glide_ms;
    void *held_ui;              /* Shift + Mutate held: repeat on this ui, else 0 */
    uint32_t next_repeat;       /* patch Mutate: next repeat */
    uint8_t looped;             /* step Mutates: a bit per track that started a new pass */
    void *sink;                 /* param sink of the glide */
    uint8_t active[RULES];      /* param still gliding: no knob has moved it */
    uint8_t from[RULES], to[RULES], last[RULES];
};
#define STATE ((struct state *)0x10000800)

static struct state *state(void)
{
    struct state *s = STATE;
    if (s->magic != STATE_MAGIC) {
        memset(s, 0, sizeof *s);
        s->magic = STATE_MAGIC;
    }
    return s;
}

static uint32_t beat_ms(void)
{
    void *clock = TIMER_CONTEXTS[0];
    if (!clock)
        return DEFAULT_BEAT_MS;
    unsigned bpm = *(uint8_t *)cell_read(FIELD(clock, CLOCK_TEMPO, void *));
    return bpm >= 30 ? 60000 / bpm : DEFAULT_BEAT_MS;
}

static param_set_fn sink_set(void *sink)
{
    return **(param_set_fn **)sink;
}

/* #45: one glide step, from the 10 ms input task (where the pots set params too). A param
 * whose live value isn't the one the glide last set was moved by hand: it drops out. */
static void glide_tick(void)
{
    struct state *s = state();
    if (!s->gliding)
        return;
    uint32_t elapsed = MILLISECONDS - s->glide_start;
    if (elapsed >= s->glide_ms) {
        elapsed = s->glide_ms;
        s->gliding = 0;
    }
    for (unsigned i = 0; i < RULES; i++) {
        if (!s->active[i])
            continue;
        unsigned id = patch_rules[i].id;
        if (synth_param_get(id) != s->last[i]) {
            s->active[i] = 0;
            continue;
        }
        int value = s->from[i] + ((int)s->to[i] - (int)s->from[i]) * (int)elapsed / (int)s->glide_ms;
        if (value != s->last[i]) {
            sink_set(s->sink)(s->sink, id, value, SOURCE_PANEL);
            s->last[i] = value;
        }
    }
}

/* Shared prelude of both Mutates: 1 when Shift + Mutate may act on the active view. */
static int mutate_allowed(void *ui)
{
    if (*(uint32_t *)cell_read(FIELD(ui, UI_LOCK, void *)) & 2)
        return 0;
    if (FIELD(ui, UI_FORWARD, uint8_t))
        return 0;
    return *(uint8_t *)cell_read(FIELD(FIELD(ui, UI_SHIFT, void *), 0x10, void *));
}

/* What stock does for every button but 0x14, 0x15 and 0x17 before dispatching. */
static void clear_view_38(void *ui)
{
    void *view = FIELD(ui, UI_VIEW_38, void *);
    *(uint8_t *)cell_write_field0(FIELD(view, 0x10, void *)) = 0;
    cell_commit(FIELD(view, 0x10, void *));
}

/* #45: in the Patches view, move the current patch a small random step, as if a few knobs
 * had been turned a little: the patch shows as edited and can be saved as usual. Switches
 * jump; the rest glide over one beat. A press during a glide starts from where it is. */
static int mutate_patch(void *ui)
{
    if (FIELD(ui, UI_ACTIVE_VIEW, void *) != FIELD(ui, UI_PATCH_VIEW, void *))
        return 0;
    clear_view_38(ui);
    struct state *s = state();
    s->gliding = 0;
    void *sink = FIELD(FIELD(ui, UI_FILTER_VIEW, void *), VIEW_PARAM_SINK, void *);
    uint8_t old[RULES], values[RULES], jump[RULES];
    unsigned cutoff = 0, shape = 0, key_track = 0, decay = 0, sustain = 0;
    for (unsigned i = 0; i < RULES; i++) {
        old[i] = synth_param_get(patch_rules[i].id);
        values[i] = nudge_param(&patch_rules[i], old[i]);
        jump[i] = patch_rules[i].hi - patch_rules[i].lo < 8;
        switch (patch_rules[i].id) {
        case FILTER_CUTOFF: cutoff = i; break;
        case FILTER_SHAPE: shape = i; break;
        case FILTER_KEY_TRACK: key_track = i; break;
        case ENV_DECAY: decay = i; break;
        case ENV_SUSTAIN: sustain = i; break;
        }
    }
    /* A new filter shape loses the notes when the cutoff is far off: keep the band in the
     * middle and the high pass low, both following the keys, and the low pass open. These
     * jump with the shape. */
    if (values[shape] != old[shape]) {
        jump[cutoff] = jump[key_track] = 1;
        if (values[shape] == SHAPE_BAND_PASS) {
            values[cutoff] = 100 + random_below(101);
            values[key_track] = 5 + random_below(3);
        } else if (values[shape] == SHAPE_HIGH_PASS) {
            values[cutoff] = random_below(101);
            values[key_track] = 7;
        } else if (values[cutoff] < 60) {
            values[cutoff] = 60 + random_below(101);
        }
    }
    /* A low sustain after a short decay is only a click: lengthen the decay, unless the
     * patch was already that short. */
    if (values[sustain] < 32 && values[decay] < 40 && !(old[sustain] < 32 && old[decay] < 40))
        values[decay] += 40;
    for (unsigned i = 0; i < RULES; i++) {
        s->active[i] = 0;
        if (values[i] == old[i])
            continue;
        if (jump[i]) {
            sink_set(sink)(sink, patch_rules[i].id, values[i], SOURCE_PANEL);
        } else {
            s->from[i] = s->last[i] = old[i];
            s->to[i] = values[i];
            s->active[i] = 1;
        }
    }
    s->sink = sink;
    s->glide_start = MILLISECONDS;
    s->glide_ms = beat_ms();
    s->gliding = 1;
    glide_tick();
    return 1;
}

/* The tracks our step Mutate acts on in the active view, a bit each, and the field it
 * changes; 0 when the view has no Mutate of ours. */
static unsigned field_tracks(void *ui, unsigned *subview)
{
    void *mode_view = FIELD(ui, UI_MODE_VIEW, void *);
    if (FIELD(ui, UI_ACTIVE_VIEW, void *) != mode_view)
        return 0;
    unsigned track = FIELD(mode_view, MV_TRACK, uint8_t);
    unsigned view = FIELD(mode_view, MV_SUBVIEW, uint8_t);
    /* Byte 2 is the subview of the packed view id: stock ignores Mutate in the Expand view
     * (5), which hides the pattern, and in the dual view (6); we handle both. */
    const uint8_t *gate = cell_read(FIELD(ui, UI_MUTATE_GATE, void *));
    if (gate[0])
        return 0;
    if (view == SUBVIEW_DUAL) {
        /* #65: the two Osc keyboards: both tracks' notes move, as in Expand. */
        *subview = SUBVIEW_EXPAND;
        return 3;
    }
    if (track >= TRACKS || view < SUBVIEW_VELOCITY ||
        (view > SUBVIEW_GLIDE && view != SUBVIEW_EXPAND) ||
        (gate[2] == 5 && view != SUBVIEW_EXPAND) || gate[2] == 6)
        return 0;
    *subview = view;
    return 1u << track;
}

/* The Note view's track, a bit, where stock Mutate shuffles whole steps; 0 elsewhere. */
static unsigned shuffle_tracks(void *ui)
{
    void *mode_view = FIELD(ui, UI_MODE_VIEW, void *);
    if (FIELD(ui, UI_ACTIVE_VIEW, void *) != mode_view)
        return 0;
    unsigned track = FIELD(mode_view, MV_TRACK, uint8_t);
    const uint8_t *gate = cell_read(FIELD(ui, UI_MUTATE_GATE, void *));
    if (gate[0] || gate[2] == 5 || gate[2] == 6 || track >= TRACKS ||
        FIELD(mode_view, MV_SUBVIEW, uint8_t) != SUBVIEW_NOTE)
        return 0;
    return 1u << track;
}

/* Nudge one field of one track's steps; 1 when it acted. */
static int mutate_track(void *ui, unsigned track, unsigned subview)
{
    void *mode_view = FIELD(ui, UI_MODE_VIEW, void *);
    void *steps_cell = FIELD(FIELD(mode_view, MV_TRACKS + 4 * track, void *), TRACK_STEPS, void *);
    struct step *steps = cell_value(steps_cell);
    uint8_t pitches[128];
    unsigned pitch_count = 0;
    if (subview == SUBVIEW_EXPAND) {
        pitch_count = scale_range(FIELD(mode_view, MV_KEYBOARDS + 4 * track, void *), steps, pitches);
        if (!pitch_count)
            return 0;
    }

    clear_view_38(ui);

    /* Gates go from 1/6 step up to the longest gate already on a note, at least one
     * step: set one long step first to allow longer gates. */
    unsigned max_gate = 6, notes = 0;
    for (unsigned i = 0; i < 16; i++)
        if (steps[i].note) {
            notes++;
            if (steps[i].gate > max_gate)
                max_gate = steps[i].gate;
        }
    if (max_gate > 96)
        max_gate = 96;
    for (unsigned i = 0; i < 16; i++)
        if (step_used(&steps[i], track, subview))
            nudge_step(&steps[i], subview, max_gate, pitches, pitch_count);
    /* One random note keeps the longest gate, so the range doesn't shrink press by press. */
    if (subview == SUBVIEW_GATE && notes) {
        unsigned keep = random_below(notes);
        for (unsigned i = 0; i < 16; i++)
            if (steps[i].note && keep-- == 0) {
                steps[i].gate = max_gate;
                break;
            }
    }
    cell_sync(steps_cell);
    cell_commit(steps_cell);
    return 1;
}

/* The step Mutate on those of the given tracks the active view shows; 1 when it acted. */
static int mutate_field(void *ui, unsigned tracks)
{
    unsigned subview;
    tracks &= field_tracks(ui, &subview);
    int acted = 0;
    for (unsigned track = 0; track < TRACKS; track++)
        if (tracks & (1u << track))
            acted |= mutate_track(ui, track, subview);
    return acted;
}

/* One of our Mutates, if Shift + Mutate may act and the view has one; 1 when it acted. */
static int mutate(void *ui)
{
    return mutate_allowed(ui) && (mutate_patch(ui) || mutate_field(ui, (1u << TRACKS) - 1));
}

void ui_button_event(void *ui, unsigned button)
{
    if (button == BUTTON_MUTATE && mutate_allowed(ui)) {
        /* Ours, or stock's whole-step shuffle in the Note view: both repeat while held. */
        int ours = mutate(ui);
        if (ours || shuffle_tracks(ui)) {
            if (!ours)
                stock_ui_button_event(ui, button);
            struct state *s = state();
            s->held_ui = ui;
            s->next_repeat = MILLISECONDS + beat_ms();
            s->looped = 0;
            return;
        }
    }
    stock_ui_button_event(ui, button);
}

/* A held Mutate repeats until Pattern Settings or Shift is let go, or the view has no
 * Mutate that repeats any more: the patch once a beat, the steps (ours, or stock's
 * whole-step shuffle in the Note view) each time the track's pattern starts again (only
 * while it plays). */
void stock_ui_root_button_up(void *ui, unsigned button);

void ui_root_button_up(void *ui, unsigned button)
{
    if (button == BUTTON_MUTATE)
        state()->held_ui = 0;
    stock_ui_root_button_up(ui, button);
}

static void repeat_tick(void)
{
    struct state *s = state();
    void *ui = s->held_ui;
    if (!ui)
        return;
    unsigned subview;
    if (!mutate_allowed(ui)) {
        s->held_ui = 0;
    } else if (FIELD(ui, UI_ACTIVE_VIEW, void *) == FIELD(ui, UI_PATCH_VIEW, void *)) {
        if ((int32_t)(MILLISECONDS - s->next_repeat) >= 0) {
            s->next_repeat = MILLISECONDS + beat_ms();
            mutate_patch(ui);
        }
    } else if (field_tracks(ui, &subview)) {
        /* Each track on its own loop: in the dual view the Osc tracks can differ. */
        unsigned due = s->looped;
        s->looped = 0;
        if (due)
            mutate_field(ui, due);
    } else if (shuffle_tracks(ui)) {
        unsigned due = s->looped & shuffle_tracks(ui);
        s->looped = 0;
        if (due)
            stock_ui_button_event(ui, BUTTON_MUTATE);
    } else {
        s->held_ui = 0;
    }
}

/* The clock tick moves each track a step through this; it returns 1 when the track went
 * on to the next pattern of its chain, or back to the start of a single pattern: a new
 * loop. dry is set when the tick only looks ahead. */
int stock_seq_step_advance(void *seq, void *pattern_cell, void *step_cell, unsigned track,
                           unsigned jump, unsigned dry, unsigned force_random);

int seq_step_advance(void *seq, void *pattern_cell, void *step_cell, unsigned track,
                     unsigned jump, unsigned dry, unsigned force_random)
{
    int looped = stock_seq_step_advance(seq, pattern_cell, step_cell, track, jump, dry,
                                        force_random);
    if (looped && !(dry & 0xff) && (track & 0xff) < TRACKS)
        state()->looped |= 1u << (track & 0xff);
    return looped;
}

/* #45: the 10 ms main-loop soft timer that scans the pots; the glide and the held Mutate
 * step after it. */
void stock_input_poll_task(unsigned index, unsigned missed, void *context);

void input_poll_task(unsigned index, unsigned missed, void *context)
{
    stock_input_poll_task(index, missed, context);
    glide_tick();
    repeat_tick();
}
