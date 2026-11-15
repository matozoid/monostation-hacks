# Circuit Mono Station firmware mods

New features for the Novation Circuit Mono Station, built on top of Novation's own
firmware 1.2, plus the reverse-engineering tools and notes that make them possible.

- **Flash the mods from your browser:** [matozoid.github.io/monostation-hacks](https://matozoid.github.io/monostation-hacks/)
  (Firefox; you choose Novation's stock firmware 1.2 file and the page builds the modded
  firmware from it).
- **Discussion and announcements:** the Facebook group
  [Novation Circuit Mono Station Users](https://www.facebook.com/groups/2161801177248617).

- **Plays like stock.** Each mod changes only the behaviour it adds; everything else is
  the original firmware, byte for byte.
- **Easy to undo.** The bootloader is never touched, so you can always flash stock
  firmware 1.2 back the same way.
- **No Novation code in this repo.** You supply the stock firmware yourself; the tools
  rebuild it from source generated on your machine.

> Unofficial and unsupported by Novation. Flashing modified firmware is at your own risk.

**Made with Claude.** The reverse engineering, the tools, the docs and the mods in this
repo were all done with [Claude Code](https://claude.com/claude-code). Claude can help
you too: open Claude Code in a checkout of this repo and ask it to walk you through
getting the stock firmware, building a mod and flashing it. `CLAUDE.md` gives it the
project's commands and safety rules.

## Feature: context-sensitive Mutate (#13)

Stock **Mutate** (Shift + Pattern Settings) shuffles whole steps. With this mod, Mutate
in the views below nudges only one property of the steps that hold a note: the rhythm
and everything else stay as they are.

| View | Open with | Mutate now |
|------|-----------|------------|
| Velocity | Velocity | moves velocities up or down by a pad level or a few |
| Gate | Gate | makes notes a little longer or shorter, up to the longest gate already in the pattern; one note keeps that longest gate, so the range holds press after press |
| Glide | Shift + Gate | moves glide amounts a little and now and then switches glide on or off |
| Expand | Shift + Note | moves notes to nearby notes on the 32 Expand pads: the current scale and root over four octaves (two for Chromatic) |

**Press harder by pressing quicker (#60).** One press is mild: about half the notes move
one small step. Press again within half a second and every note moves, further each
time, up to four levels. Pause, and the next press is mild again.

Tips:

- If no note is longer than one step, gates go up to one step. For longer gates, first
  make one note as long as you want the maximum to be.
- In Expand view the pattern is hidden: listen, or press Note to see the new notes.
- Works on the Osc 1 and Osc 2 tracks. In the Note and Pattern Settings views, and on the
  Mod Seq track, Mutate behaves as stock.

## Feature: patch randomiser (#45)

Open the **Patches** view and press **Mutate** (Shift + Pattern Settings): the current
patch takes a small random step, as if a few knobs had been turned a little. The sound
glides to the new setting over half a second instead of jumping. Press again to wander
further.

- One press moves about a quarter of the knobs, each by a sixteenth of its range or
  less. Quick presses (under half a second apart) go up to four levels: at the top every
  knob moves, by up to a quarter of its range. Switches and waveforms change only now and
  then, and jump instead of gliding.
- Turn a knob during the glide and the knob wins: that parameter stops gliding.

Each step stays playable:

- Kept as they are: the octave and range of both oscillators (the patch stays in its
  register), patch level, audio-in level and the envelope-to-amp switch.
- Tuning is never changed, apart from a little Osc 2 detune. Pulse widths stay away
  from the silent extremes.
- Each knob steps within a playable range: Osc 1 stays in the mix, the cutoff stays
  open, resonance stays short of self-oscillation and mod depths stay moderate. A
  setting that was already outside that range is never pushed further out.
- When the filter shape changes, the cutoff moves to where the notes get through: the
  band sits around the middle, the high pass sits low, and both follow the keys.
- A step never makes a sustain-less patch too short to hear: the decay gets longer.

The patch shows as edited, so save it the usual way if you like it, or load another
patch to throw it away. With MIDI out of the synth params on, the new values also go out
as CCs and NRPNs (some may be dropped).

The patch randomiser is part of the Mutate mod above (`overlays/randomise`).

## Feature: live transpose (#21)

Transpose the playing sequence from the keys, the way many monosynth sequencers do.

1. Open the Note view (or Expand: Shift + Note) of Osc 1 or Osc 2.
2. Hold **Shift** and tap a key. The key doesn't sound; both Osc tracks now play their
   patterns moved by that many scale degrees, counted from the first note of the
   track's pattern.
3. Hold Shift and tap the key of the pattern's first note to go back.

While you hold Shift, the key of the pattern's first note blinks cyan, and the key
you transposed to blinks red.

The notes stay in the scale, and nothing is saved: the pattern itself doesn't change,
and loading another session starts it untransposed.
This mod is `overlays/transpose`.

## Feature: stronger drive (#61)

Overdrive and Distortion go further than stock: turned up, they clip harder and the
three distortion types differ more. At 0 they sound as stock. This mod is
`overlays/drive`.

## Firmware versions

Firmware 1.2 is build 800 inside the firmware. Our builds count up from **750**: each
release takes the next number (750, 751, …), and the page shows which one it flashes.
They stay below 800 on purpose: Components offers an update to any unit below 800, so
it offers to put stock 1.2 back.

## What you need

- A Circuit Mono Station and a USB cable.
- Linux or macOS with Python 3.
- `arm-none-eabi-gcc` and `binutils-arm-none-eabi` (Debian/Ubuntu:
  `sudo apt install gcc-arm-none-eabi binutils-arm-none-eabi`).
- For flashing: a virtualenv with `mido` and `python-rtmidi`:

      python3 -m venv .venv
      .venv/bin/pip install mido python-rtmidi

- The stock firmware 1.2, `monostation-firmware-800.syx`. It ships inside Novation
  Components 1.72.5 (`resources/app.asar`; on Windows the installer holds
  `Components-1.72.5-full.nupkg`, which holds `lib/net45/resources/app.asar`). Unpack it
  with an asar extractor and copy the file to `private/firmware/`. Don't run the installer to get
  it; unpack it with `7z`.

## Quick start

The [flasher page](https://matozoid.github.io/monostation-hacks/) is the easy way. To
build the modded firmware yourself (all mods; leave out an `--overlay` to drop one):

    python3 tools/syx2bin.py private/firmware/monostation-firmware-800.syx private/firmware/monostation-firmware-800.bin
    python3 tools/fwsrc.py generate private/firmware/monostation-firmware-800.bin build/mods --overlay overlays/randomise --overlay overlays/transpose --overlay overlays/drive --version 750
    python3 tools/fwsrc.py build build/mods build/mods/firmware.bin
    python3 tools/bin2syx.py build/mods/firmware.bin private/firmware/mods-750.syx --version 750

Flash it:

    .venv/bin/python tools/flash.py private/firmware/mods-750.syx

The flasher checks the file, then waits for the unit's bootloader:

1. Switch the Mono Station off.
2. Hold **Scales + Note + Velocity** and switch it on.
3. The flasher sends the firmware, waits for the unit to restart and checks that it
   reports the new version (750).

Go back to stock firmware 1.2 at any time: connect the unit to Novation Components and
accept its firmware update, or flash it the same way:

    .venv/bin/python tools/flash.py private/firmware/monostation-firmware-800.syx

## Writing your own mods

`tools/fwsrc.py` turns the stock image into Thumb-2 assembly that rebuilds byte for byte.
An **overlay** replaces stock functions by name with your own C or assembly; the original
stays callable as `stock_<name>`. The Mutate feature is one small C file:
`overlays/randomise/randomise.c`, with prototypes of stock functions in
`overlays/include/firmware.h`. See `docs/source-build.md` (*Overlays*) for the format and
`docs/source-map.md` for what each stock function does.

Generated source is Novation's code: it stays in the git-ignored `build/` directory, and
the firmware and other copyrighted files in the git-ignored `private/` directory. Never
commit them.

## Tools

| Tool | What it does |
|------|--------------|
| `tools/syx2bin.py`, `tools/bin2syx.py` | firmware `.syx` ⇄ raw image (`docs/firmware-container.md`) |
| `tools/fwsrc.py` | generate and rebuild firmware source, with overlays (`docs/source-build.md`) |
| `tools/fwpatch.py` | apply small assembly patches with hooks (`docs/patching.md`) |
| `tools/flash.py` | flash a `.syx` through the bootloader and verify it (`docs/update-protocol.md`) |
| `tools/patchbank.py` | list and decode patches in `.monostationpack` banks (`docs/sysex-spec.md`) |
| `tools/sessionsyx.py`, `tools/session.py` | split, rebuild and inspect session dumps (`docs/session-transport.md`, `docs/session-format.md`) |
| `tools/fwmap.py`, `tools/disasm.py`, `tools/ghidra/` | firmware map, quick disassembly, Ghidra headless scripts |

## Documentation

For owners:

- [Official firmware versions](docs/firmware-versions.md): 1.0, 1.1 and 1.2, their build
  numbers and what each added.
- [Release test](docs/release-test.md): what we check on a unit before a build goes on
  the flasher page.
- [Entering the bootloader by hand](docs/update-protocol.md#entering-the-bootloader-by-hand)
  and [Recovery](docs/update-protocol.md#recovery): bootloader mode, reading the version
  lights, and retrying an update that failed.
- [Patch SysEx](docs/sysex-spec.md): the 136-byte patch format, `.monostationpack` banks,
  how to request a patch, and the 64 hidden patch slots.
- [Session format](docs/session-format.md) and [session transport](docs/session-transport.md):
  what is in a session and how session backups travel over SysEx.
- [Patch slots 64–127 on the unit](docs/hw-test-patch-slots.md): what the hidden slots
  hold and what program change can reach.

For modders:

- [Firmware source build](docs/source-build.md): generating source and writing overlays.
- [Source map](docs/source-map.md): what each stock function does.
- [Sequencer](docs/sequencer.md): clock, patterns, note routing.
- [Hardware](docs/hardware.md): MCU, memory map, flash sectors, free RAM.
- [Bootloader](docs/bootloader.md), [update protocol](docs/update-protocol.md) and
  [firmware container](docs/firmware-container.md): how firmware gets onto the unit.
- [Binary patching](docs/patching.md), [SysEx handlers](docs/sysex-handlers.md),
  [firmware map](docs/firmware-map.md).
- [GitHub issues](https://github.com/matozoid/monostation-hacks/issues): feature work (`enhancement`) and open research (`research`).

## Tests

    python3 -m unittest discover -s tests -v

Firmware tests skip when `private/firmware/monostation-firmware-800.syx` is missing; overlay
tests skip without `arm-none-eabi-gcc`.
