/* #20: pattern chain sequences, as on the Circuit 1.7. While stopped, hold Shift in the
 * Patterns view and tap patterns, or hold one and tap another for a chain: each tap adds
 * an entry to that track's sequence, in any order and with repeats. A single pattern
 * counts one unit, a chain two; a sequence holds 32 units, more taps are ignored. Playback
 * walks the entries in order and loops. A plain tap (or Shift + tap while playing, the
 * stock instant switch) drops the track's sequence. Shift + Patterns toggles Append: the
 * Patterns button lights orange, and Shift + tap adds to the end of the sequence, also while
 * playing. Append is off at power-on. Sequences are saved in the session (free bytes at
 * 0x155E0, docs/session-format.md).
 *
 * How: the sequence only ever drives the stock chain cell. Stopped, the chain is the first
 * entry. Playing, once a track reaches the last pattern of its entry, the chain becomes the
 * next entry with the track's cued flag set, so the stock step advance jumps to it at the
 * end of the pattern, exactly like a chain tapped while playing. */
#include <firmware.h>

#define FIELD(obj, off, type) (*(type *)((char *)(obj) + (off)))

/* ui_root (see overlays/randomise/randomise.c) */
#define UI_SHIFT        0x10    /* object whose cell +0x10 is the Shift state */
#define UI_GLOBAL       (*(void **)0x20001494)

/* Patterns view (vtable 0x0802793C) */
#define PV_CHAIN        0x1c    /* chain cell, = sequencer +0x4c */
#define PV_TRANSPORT    0x20    /* transport cell: field 0 playing, field 2 switching */
#define PV_MODE         0x30    /* cell: bit 0 Clear, bit 1 Duplicate held */
#define PV_SHIFT        0x3c    /* Shift cell */
#define PV_HELD         0x4c    /* u16 per track: held pattern pads */
#define PV_CURRENT(t)   (0x10 + 4 * (t)) /* cell: playing pattern of track t */

/* Sequencer (transport object, vtable 0x08027208; docs/sequencer.md) */
#define SEQ_CLOCK       0x0c    /* clock state; its +0x18 is the tick cell */
#define SEQ_PATTERNS    0x18    /* -> 3 pattern-array pointers, in the session */
#define SEQ_TRANSPORT   0x1c
#define SEQ_STATE(t)    (0x20 + 4 * (t)) /* cell: field 0 = playing pattern */
#define SEQ_CHAIN       0x4c
/* Chain cell fields 0-2: {first, last, 0, 0} per track; fields 3-5: cued, jump to the
 * chain at the end of the pattern. */
#define CUED(t)         ((t) + 3)

/* Session image (docs/session-format.md): patterns of track 0 at 0x58, the pattern-array
 * pointers at 0x16F5C. */
#define SESSION_PATTERNS 0x58
#define SESSION_ARRAY   0x16f5c
#define SESSION_SEQ     0x155e0 /* zero in stock sessions */

#define BUTTON_PATTERNS 0x1e
#define LED_PATTERNS    46      /* RGB LED of the Patterns button */
#define COLOUR_APPEND   0x23    /* orange (0xFF8000), apart from the stock white */

#define TRACKS          3
#define UNITS           32      /* per sequence; a single pattern is 1, a chain 2 */

static const uint8_t pattern_count[TRACKS] = {16, 8, 8};

struct entry { uint8_t first, last; };
struct sequences {              /* in the session at SESSION_SEQ, 196 bytes */
    uint8_t count[TRACKS];      /* entries; 0 = no sequence */
    uint8_t pad;
    struct entry e[TRACKS][UNITS];
};

/* CCM (docs/hardware.md "Free RAM"): not cleared at boot, so it carries a magic word.
 * Clear of transpose (0x10000000), drive (0x10000400) and randomise (0x10000800). */
#define STATE_MAGIC     0x51455353u /* "SSEQ": changes with the layout */
struct state {
    uint32_t magic;
    void *seq;                  /* the sequencer, from its observer */
    void *view;                 /* the Patterns view, from its pad handler */
    uint8_t idx[TRACKS];        /* entry playing */
    uint8_t pending[TRACKS];    /* the chain holds the next entry, cued */
    uint8_t entering[TRACKS];   /* Shift held since this sequence's first entry */
    uint8_t append;
    uint8_t shift;              /* Shift state at the last LED frame */
    uint8_t busy;               /* in our own chain commit */
};
#define STATE ((struct state *)0x10000c00)

static struct state *state(void)
{
    struct state *s = STATE;
    if (s->magic != STATE_MAGIC) {
        memset(s, 0, sizeof *s);
        s->magic = STATE_MAGIC;
    }
    return s;
}

static unsigned units(const struct entry *e)
{
    return e->first == e->last ? 1 : 2;
}

/* The session's sequences, 0 while the sequencer is unknown or its pattern arrays aren't
 * where a session keeps them. Bad entries (a session from elsewhere) drop the sequence. */
static struct sequences *sequences(void)
{
    void *seq = state()->seq;
    if (!seq)
        return 0;
    char **arr = FIELD(seq, SEQ_PATTERNS, char **);
    if (!arr)
        return 0;
    char *session = arr[0] - SESSION_PATTERNS;
    if ((char *)arr != session + SESSION_ARRAY)
        return 0;
    struct sequences *q = (struct sequences *)(session + SESSION_SEQ);
    for (unsigned t = 0; t < TRACKS; t++) {
        unsigned total = 0, n = q->count[t];
        for (unsigned i = 0; i < n && i < UNITS; i++) {
            const struct entry *e = &q->e[t][i];
            if (e->first > e->last || e->last >= pattern_count[t])
                total = UNITS + 1;
            total += units(e);
        }
        if (n > UNITS || total > UNITS)
            q->count[t] = 0;
    }
    return q;
}

static unsigned total_units(const struct sequences *q, unsigned t)
{
    unsigned total = 0;
    for (unsigned i = 0; i < q->count[t]; i++)
        total += units(&q->e[t][i]);
    return total;
}

static int playing(void *transport)
{
    return *(uint8_t *)cell_read(transport) && !*(uint8_t *)cell_read_other(transport);
}

/* Points the track's chain at an entry, optionally cued; returns 1 when it wrote. */
static int set_chain(void *chain, unsigned t, const struct entry *e, int cue)
{
    const uint8_t *now = cell_write_indexed(chain, t);
    int same = now[0] == e->first && now[1] == e->last;
    int cued = *(uint8_t *)cell_write_indexed(chain, CUED(t)) != 0;
    if (same && cued == cue)
        return 0;
    if (!same) {
        uint8_t *c = cell_write(chain, t);
        c[0] = e->first;
        c[1] = e->last;
        c[2] = c[3] = 0;
    }
    if (cued != cue)
        *(uint8_t *)cell_write(chain, CUED(t)) = cue;
    return 1;
}

static void commit_chain(void *chain)
{
    struct state *s = state();
    s->busy = 1;
    cell_commit(chain);
    s->busy = 0;
}

/* Sets the song clock and recomputes every track's position: Start, Song Position, chain
 * edits while stopped. At clock 0 the sequences start over at their first entries (Stop
 * leaves the position alone; Start sets playing before it gets here). */
void seq_set_clock(void *seq, unsigned clock)
{
    struct state *s = state();
    s->seq = seq;
    struct sequences *q = sequences();
    if (q && clock == 0 && !s->busy) {
        void *chain = FIELD(seq, SEQ_CHAIN, void *);
        int dirty = 0;
        for (unsigned t = 0; t < TRACKS; t++) {
            if (!q->count[t])
                continue;
            s->idx[t] = 0;
            s->pending[t] = 0;
            dirty |= set_chain(chain, t, &q->e[t][0], 0);
        }
        if (dirty)
            commit_chain(chain);
    }
    stock_seq_set_clock(seq, clock);
}

/* Each clock tick, after the stock advance: steps each track's sequence. */
void transport_on_tick_cell(void *seq, void *cell)
{
    struct state *s = state();
    s->seq = seq;
    stock_transport_on_tick_cell(seq, cell);
    if (s->busy || cell != FIELD(FIELD(seq, SEQ_CLOCK, void *), 0x18, void *))
        return;
    if (!playing(FIELD(seq, SEQ_TRANSPORT, void *)))
        return;
    struct sequences *q = sequences();
    if (!q)
        return;
    void *chain = FIELD(seq, SEQ_CHAIN, void *);
    int dirty = 0;
    for (unsigned t = 0; t < TRACKS; t++) {
        unsigned n = q->count[t];
        if (!n)
            continue;
        const struct entry *e = q->e[t];
        int cued = *(uint8_t *)cell_write_indexed(chain, CUED(t)) != 0;
        if (s->idx[t] >= n)
            s->idx[t] = 0;
        if (s->pending[t]) {
            if (!cued) {                /* the jump happened */
                s->idx[t] = (s->idx[t] + 1) % n;
                s->pending[t] = 0;
            } else {                    /* still waiting; an append may change "next" */
                dirty |= set_chain(chain, t, &e[(s->idx[t] + 1) % n], 1);
                continue;
            }
        }
        const uint8_t *c = cell_write_indexed(chain, t);
        if (c[0] != e[s->idx[t]].first || c[1] != e[s->idx[t]].last) {
            unsigned i;                 /* somebody moved the chain: follow it if we can */
            for (i = 0; i < n; i++)
                if (c[0] == e[i].first && c[1] == e[i].last)
                    break;
            if (i == n)
                continue;
            s->idx[t] = i;
        }
        if (cued)
            continue;
        unsigned now = *(uint8_t *)cell_read(FIELD(seq, SEQ_STATE(t), void *));
        if (now == e[s->idx[t]].last) {
            dirty |= set_chain(chain, t, &e[(s->idx[t] + 1) % n], 1);
            s->pending[t] = 1;
        }
    }
    if (dirty)
        commit_chain(chain);
}

static unsigned pad_track(unsigned pad)
{
    unsigned column = pad & 7;
    return column < 4 ? 0 : column < 6 ? 1 : 2;
}

static void repaint(void *view, unsigned t)
{
    patterns_view_chain_leds(view, t);
    step_leds_render(view, t);
}

void patterns_view_pad_down(void *view, unsigned pad, unsigned velocity)
{
    struct state *s = state();
    s->view = view;
    struct sequences *q = sequences();
    if (!q || (*(uint8_t *)cell_read(FIELD(view, PV_MODE, void *)) & 3)) {
        stock_patterns_view_pad_down(view, pad, velocity);
        return;
    }
    unsigned t = pad_track(pad);
    unsigned p = patterns_pad_bit(pad);
    uint16_t *held = &FIELD(view, PV_HELD + 2 * t, uint16_t);
    void *transport = FIELD(view, PV_TRANSPORT, void *);
    int shift = *(uint8_t *)cell_read(FIELD(view, PV_SHIFT, void *)) != 0;
    int stopped = !*(uint8_t *)cell_read(transport);

    if (!shift || !(stopped || s->append)) {
        /* A plain tap, or the stock instant switch: the chain replaces the sequence. */
        if (!*held)
            q->count[t] = 0;
        stock_patterns_view_pad_down(view, pad, velocity);
        return;
    }

    void *chain = FIELD(view, PV_CHAIN, void *);
    unsigned n = q->count[t];
    if (!*held) {
        if (!s->append && !s->entering[t])
            n = 0;                      /* a new sequence */
        if (!n) {                       /* starts with... */
            if (s->append) {            /* ...the chain playing now, when appending */
                const uint8_t *c = cell_write_indexed(chain, t);
                q->e[t][0].first = c[0];
                q->e[t][0].last = c[1];
                n = 1;
            }
            s->idx[t] = 0;
            s->pending[t] = 0;
        }
        q->count[t] = n;
        s->entering[t] = 1;
        if (n >= UNITS || total_units(q, t) + 1 > UNITS) {
            repaint(view, t);
            return;                     /* full: ignored, not held */
        }
        q->e[t][n].first = q->e[t][n].last = p;
        q->count[t] = n + 1;
    } else if (n) {                     /* a second pad: widen the last entry */
        struct entry *e = &q->e[t][n - 1];
        struct entry wide = *e;
        if (p < wide.first)
            wide.first = p;
        if (p > wide.last)
            wide.last = p;
        if (total_units(q, t) - units(e) + units(&wide) <= UNITS)
            *e = wide;
    }
    *held |= 1u << p;
    if (stopped) {
        set_chain(chain, t, &q->e[t][0], 0);
        for (unsigned c = 0; c < TRACKS; c++)
            if (*(uint8_t *)cell_write_indexed(chain, CUED(c)))
                *(uint8_t *)cell_write(chain, CUED(c)) = 0;
        commit_chain(chain);
    }
    repaint(view, t);
}

/* Pattern pad LEDs of a track: the whole sequence lights as the chain, or, while Shift is
 * held to enter one, its last entry. */
void patterns_view_chain_leds(void *view, unsigned track)
{
    struct state *s = state();
    struct sequences *q = sequences();
    unsigned n = q && track < TRACKS ? q->count[track] : 0;
    if (!n) {
        stock_patterns_view_chain_leds(view, track);
        return;
    }
    int shift = *(uint8_t *)cell_read(FIELD(view, PV_SHIFT, void *)) != 0;
    unsigned from = shift && (s->entering[track] || s->append) ? n - 1 : 0;
    unsigned now = *(uint8_t *)cell_read(FIELD(view, PV_CURRENT(track), void *));
    for (unsigned p = 0; p < pattern_count[track]; p++) {
        int in = 0;
        for (unsigned i = from; i < n; i++)
            if (q->e[track][i].first <= p && p <= q->e[track][i].last)
                in = 1;
        pattern_pad_bit_set(view, track, p, 1, !in);
        pattern_pad_bit_set(view, track, p, 0, in);
        pattern_pad_bit_set(view, track, p, 3, now == p);
    }
}

/* Each LED frame: repaints the pattern pads when Shift changes, lights Patterns in Append. */
void mono_leds_compose(void *ui, uint8_t *colours)
{
    stock_mono_leds_compose(ui, colours);
    struct state *s = state();
    uint8_t shift = *(uint8_t *)cell_read(FIELD(FIELD(ui, UI_SHIFT, void *), 0x10, void *)) != 0;
    if (shift != s->shift) {
        s->shift = shift;
        if (!shift)
            for (unsigned t = 0; t < TRACKS; t++)
                s->entering[t] = 0;
        if (s->view)
            for (unsigned t = 0; t < TRACKS; t++)
                patterns_view_chain_leds(s->view, t);
    }
    if (s->append)
        colours[LED_PATTERNS] = COLOUR_APPEND;
}

/* From overlays/randomise ui_button_event: Shift + Patterns toggles Append. */
int sequences_button(void *ui, unsigned button)
{
    if (button != BUTTON_PATTERNS ||
        !*(uint8_t *)cell_read(FIELD(FIELD(ui, UI_SHIFT, void *), 0x10, void *)))
        return 0;
    state()->append ^= 1;
    return 1;
}
