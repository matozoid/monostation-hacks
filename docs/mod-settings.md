# Mod settings

A design (2026-11-21) for one store of settings, tweaks and feature flags shared by every
mod, set from the app (`flasher/`, Settings tab in `docs/app-ui.md`). The first user is the
configurable drive amount (#61). Status: proposed, nothing built yet.

## Decisions (2026-11-21)

- **One store for all mods**, not one per feature. A setting that isn't in the store means
  stock behaviour (a flag means off). Every mod is inert at its defaults.
- **The app writes it, through the bootloader**, with the upload the pack sender uses
  (`docs/session-transport.md` *Upload*). The firmware only reads it, once at boot. It never
  writes external flash for this. Writing from the firmware comes later, if a setting has to
  change from the panel.
- **Live changes through SysEx, in RAM.** Sound tweaks such as the drive sliders are heard
  while they move; Save in the app then writes the store.
- **The firmware describes its own settings.** The page asks the build which settings it
  has and draws the form, so it needs no per-build schema and test builds just work.
- **Settings take a few predefined kinds** (below), so a description is small and the page
  has one control per kind.

## Kinds

| kind | value | described by | control on the page |
|------|-------|--------------|---------------------|
| `flag` | 0 or 1 | default | checkbox (on/off) |
| `number` | signed 16-bit | min, max, default | number field |
| `slider` | signed 16-bit | min, max, default | slider with its value |
| `enum` | index 0…n-1 | labels, default | radio buttons or drop-down |
| `text` | ASCII, up to n bytes | max length, default | text field |

Each setting also has: an **id** (stable, never reused), a **name** (shown on the page,
ASCII, up to 24 characters), a **group** (the mod's name, for headings) and **when** it takes
effect: `live` (at once, from the SysEx set) or `boot` (at the next power-on; the page says
so).

## Declaring a setting

An overlay declares its settings in its `overlay.toml`:

```toml
[[setting]]
id = 0x0101
name = "Overdrive amount"
kind = "slider"
min = 0          # 0 = stock
max = 64
default = 0
when = "live"
```

`fwsrc.py generate` collects the settings of every overlay in the build, checks that the
ids are unique, and generates the description table (const data in the image) and a header
with one `SETTING_<NAME>` id per setting. The overlay reads a value with
`mod_setting(id)`, which returns the stored value clamped to the setting's range, or its
default. Ids are listed in *Ids* below, so two mods never take the same one; the high byte
is the mod, the low byte the setting.

## Store in external flash

One 4 KB subsector at **`0x7FF000`**, the last one of the 8 MiB chip. Everything from
`0x2E8000` up is unused (all `FF`, `docs/session-transport.md` *Storage map*); the far end
keeps clear of the sessions.

| offset | content |
|--------|---------|
| `0x00` | magic `MODS` |
| `0x04` | format version, 1 |
| `0x05` | 0 |
| `0x06` | length of the entries, u16 LE |
| `0x08` | entries: `id` u16 LE, `length` u8, value (`length` bytes; numbers u16/s16 LE) |
| after | zlib CRC-32 (LE) over bytes `0x00` up to the CRC |

The app always writes the whole subsector, `FF` after the CRC. A bad magic, version or CRC
(an erased chip, an interrupted write) means: no settings, everything stock. A build skips
ids it doesn't know and clamps values to its own ranges, so one store works across releases,
test builds and stock firmware (which never reads it).

## SysEx `0x7C`

`F0 00 20 29 01 61 7C <op> … F7`, in the Mono Station family (`docs/sysex-handlers.md`).
The stock dispatcher doesn't use byte 6 `0x7C`: the earlier drive tuning message took it,
and `overlays/drive` dropped that message. `0x7D` is `overlays/ledtest`. Values travel as 4-bit nibbles, most
significant first, like the `p 0x40` replies; text as one byte per character.

| op | request | reply |
|----|---------|-------|
| `0x00` describe | `<index: 2 nibbles>` | `7C 00 <index> <count> <id: 4> <kind> <when> <kind's fields> <name>`, or `7C 00 7F` past the end |
| `0x01` get | `<id: 4 nibbles>` | `7C 01 <id: 4> <value>` (the value in RAM now) |
| `0x02` set | `<id: 4> <value>` | `7C 02 <id: 4> <status>`: 0 ok, 1 unknown id, 2 out of range, 3 needs a reboot |
| `0x03` build | none | `7C 03 <format version> <count> <group names>` |

## Ids

| id | mod | setting | issue |
|----|-----|---------|-------|
| `0x01xx` | drive | `0x0101` Overdrive amount, `0x0102` Distortion amount | #61 |

## In the app

- The Settings tab appears when the connected build answers `7C 03`. Stock and older mod
  builds don't answer it, so the tab stays hidden for them.
- The page reads every description (`7C 00`) and value (`7C 01`), groups the controls by
  mod, and sends `7C 02` as a control changes.
- **Save** writes the store through the bootloader (about 5 s, with a read-back) and comes
  back to the app. **Reset to stock** writes an empty store.
- The backup reads the store too. Where it goes in the backup file is open (below).

## Open

- **Boot read:** where the overlay reads the store with `ext_flash_read` (`0x0801AEF4`,
  blocking), at a point in boot where the NOR driver is idle and the SPI bus is set up. A
  candidate is after `app_construct` reads the settings record at `0x1000`.
- **Backup file:** an extra file in the `.monostationpack` if Components still accepts the
  pack, or a separate file next to it. Needs a check against Components' pack reader
  (`frontend/utils/monostation/is-v1-pack`).
- **Upload at `0x7FF000`:** the bootloader reads anywhere up to `0x7FFFF000`, but uploads
  were tried only on the patch area and the sessions.
- **Size limits:** how long a description can be in one SysEx (enum labels, text
  defaults).
- **Firmware writes:** only once a setting has to change from the panel; then through the
  async NOR driver, with care around patch and session saves.
