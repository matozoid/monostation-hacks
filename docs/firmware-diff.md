# Firmware diffs

The flasher page (#63) must not ship Novation's firmware. It ships a diff per build instead
(`flasher/src/jsMain/resources/builds/*.fwdiff`); the user downloads the stock
`monostation-firmware-800.syx` (firmware 1.2) from Novation and the page rebuilds the
modified `.syx` from it before flashing.

Tools: `tools/fwdiff.py` (reference) and `flasher/.../FirmwareDiff.kt` (the page and the
JVM tests). Both rebuild `mods-751.syx` byte for byte (`tests/test_fwdiff.py`,
`FirmwareDiffFileTest`).

## Format `fwdiff 1`

Text, one item per line; blank lines and lines starting with `#` are ignored.

```
fwdiff 1
base-syx-sha256 <hex>      SHA-256 of the stock .syx file, byte for byte
target-version <n>         version in the rebuilt .syx start message
target-length <n>          target image bytes (a whole number of 32-byte blocks)
target-sha256 <hex>        SHA-256 of the target image
copy <offset> <length>     append stock image bytes (hex offset, decimal length)
data <hex>                 append these bytes (64 per line; consecutive lines join)
```

Header lines come before the first op. Ops work on decoded images (`syx2bin.py`), so the
stock file's packing and checksum don't matter; apply then:

1. checks the stock file's SHA-256 (any other file is refused: the copies would be wrong);
2. decodes it and runs the ops in order;
3. checks the target length and SHA-256;
4. encodes the image as `bin2syx.py` does (FF padding, spare-bit lookahead, checksum) with
   `target-version`.

## Making one

`make` is greedy: a run that matches stock at the same offset becomes `copy`, else the
longest match of at least 16 bytes anywhere in stock (moved code or tables), else `data`.
Shorter matches stay data: they are coincidences, not stock code.

```
python3 tools/fwdiff.py make private/firmware/monostation-firmware-800.syx private/firmware/mods-751.syx \
    flasher/src/jsMain/resources/builds/mods-751.fwdiff
python3 tools/fwdiff.py show flasher/src/jsMain/resources/builds/mods-751.fwdiff
```

`mods-751` (randomise + transpose + drive overlays): 32 ops, 129,761 bytes copied in place,
none moved, 3,679 bytes of data. The data is our bytes at the hook sites plus
our code after the image; it holds no relocated stock code.

`mods-751.syx` is built with
`fwsrc.py generate private/firmware/monostation-firmware-800.bin OUT --overlay overlays/randomise --overlay overlays/transpose --overlay overlays/drive --version 751`,
`fwsrc.py build`, `bin2syx.py --version 751`.

The page holds two diffs: the latest release and the current test build
(`docs/release-test.md`). Rebuild the test diff in place while testing; on a release, add
the release's diff, drop the previous release's, and update the `Build` entries in
`flasher/src/jsMain/kotlin/flasher/Main.kt`.
