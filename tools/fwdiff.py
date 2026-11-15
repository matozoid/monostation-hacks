#!/usr/bin/env python3
"""Firmware diffs against stock: ship our builds without Novation's bytes (#63).

A diff rebuilds a target image from the stock firmware .syx the user already has. Every
target byte that also sits in stock (in place, or moved: >= MIN_MATCH bytes found anywhere
in the stock image) becomes a copy from stock; only the rest travels as data. `make`
reports how much of the data is ours and how much stock was moved.

    fwdiff.py make  STOCK.syx TARGET.syx OUT.fwdiff
    fwdiff.py apply STOCK.syx DIFF.fwdiff OUT.syx
    fwdiff.py show  DIFF.fwdiff

Format (text, one op per line, `#` comments; spec in docs/firmware-diff.md):

    fwdiff 1
    base-syx-sha256 <hex>      the stock .syx file, byte for byte
    target-version <n>         start message version of the rebuilt .syx
    target-length <n>          target image bytes (a whole number of 32-byte blocks)
    target-sha256 <hex>        the target image
    copy <offset> <length>     append stock image bytes (hex offset, decimal length)
    data <hex>                 append these bytes
"""

import argparse
import hashlib
import sys
from pathlib import Path

from fwsyx import MONOSTATION_PRODUCT_ID, decode_image, encode_firmware, parse_start, split_messages

MAGIC = "fwdiff 1"
MIN_MATCH = 16      # shorter matches with stock stay data: a coincidence, not a copy
DATA_LINE = 64      # bytes per data line


def build_ops(base, target):
    """Greedy: same-offset run from stock, else the longest stock match >= MIN_MATCH, else data."""
    index = {}
    for i in range(len(base) - MIN_MATCH + 1):
        index.setdefault(base[i:i + MIN_MATCH], []).append(i)

    ops = []
    data = bytearray()

    def flush():
        if data:
            ops.append(("data", bytes(data)))
            data.clear()

    i = 0
    while i < len(target):
        n = 0
        while i + n < len(target) and i + n < len(base) and target[i + n] == base[i + n]:
            n += 1
        src = i
        if n < MIN_MATCH:
            n = 0
            for start in index.get(target[i:i + MIN_MATCH], ())[:64]:
                m = MIN_MATCH
                while i + m < len(target) and start + m < len(base) and target[i + m] == base[start + m]:
                    m += 1
                if m > n:
                    src, n = start, m
        if n >= MIN_MATCH or (n and src == i):
            flush()
            ops.append(("copy", src, n))
            i += n
        else:
            data.append(target[i])
            i += 1
    flush()
    return ops


def apply_ops(base, ops):
    out = bytearray()
    for op in ops:
        if op[0] == "copy":
            _, src, n = op
            if src + n > len(base):
                raise ValueError(f"copy 0x{src:x} {n} runs past the stock image")
            out += base[src:src + n]
        else:
            out += op[1]
    return bytes(out)


def write_diff(path, header, ops):
    lines = [MAGIC]
    lines += [f"{key} {value}" for key, value in header.items()]
    for op in ops:
        if op[0] == "copy":
            lines.append(f"copy {op[1]:x} {op[2]}")
        else:
            payload = op[1]
            lines += [f"data {payload[i:i + DATA_LINE].hex()}" for i in range(0, len(payload), DATA_LINE)]
    path.write_text("\n".join(lines) + "\n")


def read_diff(text):
    lines = [line.strip() for line in text.splitlines()]
    lines = [line for line in lines if line and not line.startswith("#")]
    if not lines or lines[0] != MAGIC:
        raise ValueError(f"not a firmware diff (expected '{MAGIC}')")
    header, ops = {}, []
    for line in lines[1:]:
        key, _, rest = line.partition(" ")
        if key == "copy":
            src, n = rest.split()
            ops.append(("copy", int(src, 16), int(n)))
        elif key == "data":
            payload = bytes.fromhex(rest)
            if ops and ops[-1][0] == "data":
                ops[-1] = ("data", ops[-1][1] + payload)
            else:
                ops.append(("data", payload))
        elif ops:
            raise ValueError(f"header line after the ops: {line}")
        else:
            header[key] = rest
    for key in ("base-syx-sha256", "target-version", "target-length", "target-sha256"):
        if key not in header:
            raise ValueError(f"missing {key}")
    return header, ops


def apply(base_syx, diff_text):
    """Return the target .syx, after checking the stock file and the rebuilt image."""
    header, ops = read_diff(diff_text)
    if hashlib.sha256(base_syx).hexdigest() != header["base-syx-sha256"]:
        raise ValueError("this isn't the stock firmware file the diff was made for "
                         "(monostation-firmware-800.syx, firmware 1.2)")
    target = apply_ops(decode_image(base_syx), ops)
    if len(target) != int(header["target-length"]) or hashlib.sha256(target).hexdigest() != header["target-sha256"]:
        raise ValueError("the rebuilt image doesn't match the diff's checksum")
    return encode_firmware(target, MONOSTATION_PRODUCT_ID, int(header["target-version"]))


def describe(ops):
    in_place = moved = data = 0
    pos = 0
    for op in ops:
        if op[0] == "copy":
            if op[1] == pos:
                in_place += op[2]
            else:
                moved += op[2]
            pos += op[2]
        else:
            data += len(op[1])
            pos += len(op[1])
    return (f"{len(ops)} ops: {in_place} bytes copied in place, {moved} bytes of stock moved "
            f"(copied from another offset), {data} bytes of data")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    m = sub.add_parser("make")
    m.add_argument("stock", type=Path)
    m.add_argument("target", type=Path)
    m.add_argument("out", type=Path)
    a = sub.add_parser("apply")
    a.add_argument("stock", type=Path)
    a.add_argument("diff", type=Path)
    a.add_argument("out", type=Path)
    s = sub.add_parser("show")
    s.add_argument("diff", type=Path)
    args = parser.parse_args()

    if args.cmd == "make":
        base_syx = args.stock.read_bytes()
        target_syx = args.target.read_bytes()
        base, target = decode_image(base_syx), decode_image(target_syx)
        _, version = parse_start(split_messages(target_syx)[0])
        ops = build_ops(base, target)
        assert apply_ops(base, ops) == target
        header = {
            "base-syx-sha256": hashlib.sha256(base_syx).hexdigest(),
            "target-version": version,
            "target-length": len(target),
            "target-sha256": hashlib.sha256(target).hexdigest(),
        }
        write_diff(args.out, header, ops)
        if apply(base_syx, args.out.read_text()) != encode_firmware(target, MONOSTATION_PRODUCT_ID, version):
            sys.exit("internal error: the diff doesn't rebuild the target")
        print(f"{args.out}: firmware {version}, {describe(ops)}", file=sys.stderr)
    elif args.cmd == "apply":
        try:
            syx = apply(args.stock.read_bytes(), args.diff.read_text())
        except ValueError as e:
            sys.exit(f"{args.diff}: {e}")
        args.out.write_bytes(syx)
        print(f"{args.out}: {len(syx)} bytes", file=sys.stderr)
    else:
        header, ops = read_diff(args.diff.read_text())
        for key, value in header.items():
            print(f"{key} {value}")
        print(describe(ops))


if __name__ == "__main__":
    main()
