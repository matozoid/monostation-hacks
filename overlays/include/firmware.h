/* Stock firmware functions for C overlays (docs/source-build.md). Every name in src/symbols.tsv is a
 * global in the generated source, so declaring it here is enough to call it; a replaced
 * function's original is stock_<name>. Add prototypes as docs/source-map.md pins down
 * the arguments. Overlays cannot have .data or .bss: use const data, or RAM the stock
 * firmware leaves free (docs/hardware.md "Free RAM"). */
#ifndef FIRMWARE_H
#define FIRMWARE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {                        /* stock names are C symbols, also from C++ overlays */
#endif

void stock_main(void);              /* main: init, then the soft-timer main loop */

/* Cells (docs/source-map.md "Cells"): double-buffered values with subscribers. */
void *cell_read(void *cell);        /* front buffer */
void *cell_read_other(void *cell);  /* front buffer, field 2 */
void *cell_value(void *cell);       /* word 0 of the front buffer: a pointer for array cells */
void *cell_write_field0(void *cell); /* back buffer, field 0, marked dirty */
void cell_sync(void *cell);         /* copy front into back, mark every field dirty */
void cell_commit(void *cell);       /* swap and notify subscribers */

uint32_t rng_read(void);            /* hardware RNG, 0 on timeout */

/* Raw value of a synth param of the live patch (id < 0x4c), the value param_set takes. */
uint8_t synth_param_get(unsigned id);

extern const uint8_t pad_levels[16]; /* 0, 8, 17, ..., 127: glide and velocity pad levels */

/* Index into the keyboard layout's pad-to-note table (layout = keyboard view +0x30,
 * notes at layout +0x14, 0 = no key) for a pad of the 16 (32 in Expand view). */
uint8_t keyboard_pad_to_note(void *keyboard_view, unsigned pad);

/* Keyboard view LEDs: one entry of the layout (0-0xdf) in its scale/sounding colour. */
void keyboard_leds_render(void *keyboard_view, unsigned entry);
void stock_keyboard_leds_render(void *keyboard_view, unsigned entry);

/* Cell subscriber of a flag LED (this = LED object +0xc): colour 0x0d/0x0e from its flag.
 * The Shift LED (ui +0x10) is one. */
void flag_led_render_thunk(void *subscriber);
void stock_flag_led_render_thunk(void *subscriber);

/* Per LED frame (from the main loop): animates the palette, resolves the colour indices
 * of the LED buffers; returns 1. */
int led_frame_compose(void *leds, unsigned frame, void *ui);
int stock_led_frame_compose(void *leds, unsigned frame, void *ui);

/* Callback of the session read from flash (session control in r3; r0-r2 from the
 * flash driver): checks the magic, then loads the session's globals or a default one. */
void session_ctl_on_save_done(unsigned r0, unsigned r1, unsigned r2, void *session_ctl);
void stock_session_ctl_on_save_done(unsigned r0, unsigned r1, unsigned r2, void *session_ctl);

/* ui_button_event(ui, button): the UI's button-down router (docs/source-map.md). */
void ui_button_event(void *ui, unsigned button);
void stock_ui_button_event(void *ui, unsigned button);

/* ui_root_pad_down(ui, pad, velocity): pads 0-31, forwarded to the active view. */
void ui_root_pad_down(void *ui, unsigned pad, unsigned velocity);
void stock_ui_root_pad_down(void *ui, unsigned pad, unsigned velocity);

/* The child view that gets a pad event (pads 16-31 may go to an override view). */
void *mode_view_target(void *mode_view, unsigned pad);
int mode_view_has_override(void *mode_view);   /* pads 16..31 go to the override view, as pad - 16 */

/* Router (docs/sequencer.md): channels 0-2 keys, 3-5 sequencer, 6-7 MIDI in; notes are
 * relative to the root. */
void router_note_on(void *router, unsigned note, unsigned velocity, unsigned channel);
void stock_router_note_on(void *router, unsigned note, unsigned velocity, unsigned channel);
void router_note_off(void *router, unsigned note, unsigned channel);
void stock_router_note_off(void *router, unsigned note, unsigned channel);

/* Field n of a multi-field cell (4 bytes each): cell_write marks it dirty and returns the
 * back buffer (copied from the front first); cell_write_indexed returns it to read. */
void *cell_write(void *cell, unsigned field);
void *cell_write_indexed(void *cell, unsigned field);

/* Patterns view (docs/sequencer.md "Pattern chains"). */
void patterns_view_pad_down(void *view, unsigned pad, unsigned velocity);
void stock_patterns_view_pad_down(void *view, unsigned pad, unsigned velocity);
void patterns_view_chain_leds(void *view, unsigned track);
void stock_patterns_view_chain_leds(void *view, unsigned track);
uint8_t patterns_pad_bit(unsigned pad);     /* pad -> pattern index of its track */
/* One LED state bit of a pattern pad; kind 0 = in the chain, 1 = outside it, 3 = playing,
 * 5 = held. */
void pattern_pad_bit_set(void *view, unsigned track, unsigned pattern, unsigned kind,
                         unsigned on);
void step_leds_render(void *view, unsigned track);

/* Transport (docs/sequencer.md): the clock tick and chain cell observer, the song
 * position of one track from the clock and its chain, and setting the clock (then every
 * track's position). */
void transport_on_tick_cell(void *seq, void *cell);
void stock_transport_on_tick_cell(void *seq, void *cell);
void seq_track_position(void *seq, unsigned track);
void stock_seq_track_position(void *seq, unsigned track);
void seq_set_clock(void *seq, unsigned clock);
void stock_seq_set_clock(void *seq, unsigned clock);

/* Each LED frame (from led_frame_compose): gathers the views' colour indices into the
 * 82-byte RGB LED buffer (one byte per LED, docs/inventory.md "LED map"). */
void mono_leds_compose(void *ui, uint8_t *colours);
void stock_mono_leds_compose(void *ui, uint8_t *colours);

void *memcpy(void *dst, const void *src, size_t n);
void *memset(void *dst, int c, size_t n);

#ifdef __cplusplus
}
#endif

#endif
