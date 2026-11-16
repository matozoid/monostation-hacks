# Release test

Run this list on the unit before a build goes on the flasher page. Note the build, the date
and anything that fails in the release's issue comment.

Versions count up from 750 and stay below 800, so Components still offers stock 1.2.
Test builds take the next odd number, releases (the flasher page) the next even one. Test
an odd build with this list; when it passes, build the same source as the next even number
for the page.

Build:

```
python3 tools/fwsrc.py generate private/firmware/monostation-firmware-800.bin build/rel \
    --overlay overlays/randomise --overlay overlays/transpose --overlay overlays/drive --version N
python3 tools/fwsrc.py build build/rel build/rel/fw.bin
python3 tools/bin2syx.py build/rel/fw.bin private/firmware/mods-N.syx --version N
```

## Flash

- [ ] The flasher page flashes it (Firefox): reboot into the bootloader, send, the unit reports N.
- [ ] Components shows firmware N and offers 1.2 as an update.

## Mild Mutate on steps (#13, #60)

Shift + Pattern Settings is Mutate. Use an Osc 1 pattern with notes on about half the steps.

- [ ] Velocity view: one press changes every note's velocity a little; the rhythm stays.
- [ ] Gate view: gates move a little; one note keeps the longest gate; nothing goes past it.
- [ ] Glide view: glide amounts move; some steps switch glide on or off.
- [ ] Expand view: notes move to nearby notes of the scale, never off the scale.
- [ ] The same on the Osc 2 track.
- [ ] Note view and the mod-seq track: Mutate is stock (shuffles whole steps).
- [ ] Empty steps stay empty in every view.

## Patch randomiser (#45, #60)

- [ ] Patches view + Mutate: the sound changes a little and glides over about half a second; the patch shows as edited.
- [ ] Each press changes it about as much as the last.
- [ ] Turn a knob (cutoff) during a glide: the knob wins, no jump back.
- [ ] Hold a note while pressing: no clicks or stuck notes; MIDI out (if monitored) shows the param changes.
- [ ] Select another patch right after a press: the new patch loads as saved, no leftover glide.
- [ ] Save the mutated patch, reload it: it sounds the same.
- [ ] Record on, playing: Mutate's changes are recorded as automation and play back.

## Live transpose (#21)

- [ ] Note view, Osc 1, playing: Shift + key transposes both Osc tracks by scale degrees; the key doesn't sound.
- [ ] While Shift is held the reset key blinks cyan, the current key red.
- [ ] Tapping the first note's key resets.
- [ ] Loading a session resets the transpose.
- [ ] No hanging notes when transposing while notes play.

## Drive (#61)

- [ ] Overdrive and Distortion at 0: the sound is stock.
- [ ] Turned up: clearly stronger than stock; all three distortion types sound distinct.
- [ ] `F0 00 20 29 01 61 7C 10 10 F7` does nothing (the tuning SysEx is gone).

## Stock behaviour

- [ ] Sessions: save, load, switch while playing.
- [ ] Patches: save, load, Initialise (Shift + Patches).
- [ ] Power off and on: settings and the last session come back.
- [ ] MIDI clock in and out, DIN and USB; notes in from a keyboard.
- [ ] CV/gate out and sync out.
- [ ] Components: read and write a patch, back up sessions.
- [ ] One hour of playing with patterns, Mutate and transpose: no freeze or reset.
- [ ] Restore stock 1.2 from the flasher page, then the build again.
