import shutil
import subprocess
import sys
import tempfile
import tomllib
import unittest
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))

import fwsrc  # noqa: E402
import modsettings as ms  # noqa: E402

FIXTURE = """
[[setting]]
id = 0x0101
name = "Overdrive amount"
kind = "slider"
min = 0
max = 100
default = 0

[[setting]]
id = 0x0201
name = "Wide pads"
kind = "flag"
default = 1
when = "boot"

[[setting]]
id = 0x0202
name = "Offset"
kind = "number"
min = -12
max = 12
default = -3

[[setting]]
id = 0x0203
name = "Mode"
kind = "enum"
labels = ["Off", "Soft", "Hard"]
default = 1

[[setting]]
id = 0x0204
name = "Owner"
kind = "text"
max = 8
default = "me"
"""


def fixture():
    t = tomllib.loads(FIXTURE)["setting"]
    return ms.from_toml(t[:1], "drive", "a") + ms.from_toml(t[1:], "pads", "b")


def nibbles(v, n):
    return bytes((v >> (4 * i)) & 0xF for i in reversed(range(n)))


def string(s):
    return nibbles(len(s), 2) + s.encode()


PREFIX = bytes([0xF0, 0x00, 0x20, 0x29, 0x01, 0x61, 0x7C])


class ParseTest(unittest.TestCase):
    def test_fixture(self):
        s = fixture()
        self.assertEqual([x.kind for x in s], ["slider", "flag", "number", "enum", "text"])
        self.assertEqual((s[2].min, s[2].max, s[2].default), (-12, 12, -3))
        self.assertEqual((s[3].max, s[3].labels), (2, ("Off", "Soft", "Hard")))
        self.assertEqual(s[1].when, "boot")
        self.assertEqual(ms.groups(s), ["drive", "pads"])
        self.assertEqual(ms.macro("Overdrive amount"), "SETTING_OVERDRIVE_AMOUNT")
        self.assertEqual(ms.text_bytes(s), 9)

    def test_bad(self):
        good = {"id": 1, "name": "x", "kind": "slider", "min": 0, "max": 4, "default": 0}
        for change in ({"id": 0}, {"id": 0x10000}, {"name": ""}, {"name": "x" * 25},
                       {"kind": "dial"}, {"when": "later"}, {"min": 5}, {"default": 9},
                       {"labels": ["a"]}, {"max": 0x8000}, {"colour": 1}):
            with self.assertRaises(ValueError, msg=change):
                ms.parse({**good, **change}, "g", "here")
        with self.assertRaises(ValueError):
            ms.parse({"id": 1, "name": "t", "kind": "text", "max": 2, "default": "abc"}, "g", "")
        with self.assertRaises(ValueError):
            ms.parse({"id": 1, "name": "f", "kind": "flag", "min": 0}, "g", "")

    def test_unique(self):
        s = fixture()
        ms.check(s)
        with self.assertRaises(ValueError):
            ms.check(s + [s[0]._replace(name="Other")])        # same id
        with self.assertRaises(ValueError):
            ms.check(s + [s[0]._replace(id=0x0999)])            # same header name

    def test_build_needs_settings_overlay(self):
        drive = fwsrc.load_overlay(ROOT / "overlays" / "drive")
        self.assertEqual([s.id for s in drive.settings], [0x0101, 0x0102, 0x0103, 0x0104])
        with self.assertRaises(ValueError):
            fwsrc.overlay_settings([drive])
        store = fwsrc.load_overlay(ROOT / "overlays" / "settings")
        self.assertEqual(len(fwsrc.overlay_settings([store, drive])), 4)
        self.assertIsNone(fwsrc.overlay_settings([]))


class StoreTest(unittest.TestCase):
    def test_round_trip(self):
        entries = [(0x0101, b"\x32\x00"), (0x0204, b"abc")]
        data = ms.encode_store(entries)
        self.assertEqual(len(data), ms.STORE_BYTES)
        self.assertEqual(data[:8], b"MODS\x01\x00\x0b\x00")
        self.assertEqual(int.from_bytes(data[19:23], "little"), zlib.crc32(data[:19]))
        self.assertEqual(data[23:], b"\xff" * (ms.STORE_BYTES - 23))
        self.assertEqual(ms.decode_store(data), entries)
        self.assertEqual(ms.decode_store(ms.encode_store([])), [])
        self.assertIsNone(ms.decode_store(b"\xff" * ms.STORE_BYTES))
        bad = bytearray(data)
        bad[9] ^= 1
        self.assertIsNone(ms.decode_store(bytes(bad)))


@unittest.skipUnless(shutil.which("gcc"), "no host gcc")
class HostOverlayTest(unittest.TestCase):
    """overlays/settings/settings.c compiled for the host (tests/settings_host/host.c)."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp())
        s = fixture()
        (cls.tmp / ms.HEADER).write_text(ms.header(s))
        (cls.tmp / "table.c").write_text(ms.table(s))
        cls.exe = cls.tmp / "host"
        subprocess.run(["gcc", "-std=gnu17", "-Wall", "-Wextra", "-Werror", "-Wno-type-limits",
                        "-I", str(ROOT / "overlays" / "include"),
                        "-I", str(ROOT / "overlays" / "settings"), "-I", str(cls.tmp),
                        "-o", str(cls.exe), str(ROOT / "tests" / "settings_host" / "host.c"),
                        str(ROOT / "overlays" / "settings" / "settings.c"),
                        str(cls.tmp / "table.c")], check=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp)

    def run_host(self, lines, store=None):
        args = [str(self.exe)]
        if store is not None:
            (self.tmp / "store.bin").write_bytes(store)
            args.append(str(self.tmp / "store.bin"))
        out = subprocess.run(args, input="\n".join(lines) + "\n", capture_output=True,
                             text=True, check=True).stdout.splitlines()
        return [bytes.fromhex(l.split()[3]) if l.startswith("reply") else l for l in out]

    def sysex(self, op, body=b""):
        return (PREFIX + bytes([op]) + body + b"\xf7").hex()

    def test_defaults_before_and_without_store(self):
        out = self.run_host(["get 101", "get 202", "text 204", "boot", "get 202", "get 999",
                             self.sysex(3)])
        self.assertEqual(out[:5], ["value 0", "value -3", "text 'me'", "value -3", "value 0"])
        self.assertEqual(out[5], PREFIX + bytes([3]) + nibbles(1, 2) + nibbles(5, 2)
                         + bytes([0]) + nibbles(2, 2) + string("drive") + string("pads") + b"\xf7")

    def test_store(self):
        store = ms.encode_store([(0x0101, b"\x32\x00"), (0x0999, b"\x01"), (0x0202, b"\x40\x00"),
                                 (0x0203, b"\x02"), (0x0204, b"Zed")])
        out = self.run_host(["boot", "get 101", "get 202", "get 203", "text 204", self.sysex(3)],
                            store)
        self.assertEqual(out[:4], ["value 50", "value 12", "value 2", "text 'Zed'"])  # 64 clamped
        self.assertEqual(out[4][12], 1)                                              # store read
        bad = bytearray(store)
        bad[12] ^= 1
        out = self.run_host(["boot", "get 101", self.sysex(3)], bytes(bad))
        self.assertEqual(out[0], "value 0")
        self.assertEqual(out[1][12], 2)                                              # damaged

    def test_describe(self):
        out = self.run_host(["boot"] + [self.sysex(0, nibbles(i, 2)) for i in range(6)])
        head = PREFIX + bytes([0])
        self.assertEqual(out[0], head + nibbles(0, 2) + nibbles(5, 2) + nibbles(0x0101, 4)
                         + bytes([2, 0]) + nibbles(0, 2) + nibbles(0, 4) + nibbles(100, 4)
                         + nibbles(0, 4) + string("Overdrive amount") + b"\xf7")
        self.assertEqual(out[1][16:21], bytes([0, 1, 0, 1, 1]))   # flag, boot, group 1, on
        self.assertEqual(out[2][20:32], nibbles(0xFFF4, 4) + nibbles(12, 4) + nibbles(0xFFFD, 4))
        self.assertEqual(out[3][20:], nibbles(3, 2) + nibbles(1, 2) + string("Off") + string("Soft")
                         + string("Hard") + string("Mode") + b"\xf7")
        self.assertEqual(out[4][20:], nibbles(8, 2) + string("me") + string("Owner") + b"\xf7")
        self.assertEqual(out[5], head + b"\x7f\xf7")

    def test_get_set(self):
        out = self.run_host([
            "boot",
            self.sysex(2, nibbles(0x0101, 4) + nibbles(75, 4)),
            self.sysex(2, nibbles(0x0101, 4) + nibbles(101, 4)),
            self.sysex(2, nibbles(0x0202, 4) + nibbles(0xFFF6, 4)),
            self.sysex(2, nibbles(0x0201, 4) + nibbles(0, 4)),
            self.sysex(2, nibbles(0x0204, 4) + string("Bob")),
            self.sysex(2, nibbles(0x0204, 4) + string("too long!")),
            self.sysex(2, nibbles(0x0999, 4) + nibbles(1, 4)),
            "get 101", "get 202", "get 201", "text 204",
            self.sysex(1, nibbles(0x0202, 4)),
            self.sysex(1, nibbles(0x0204, 4)),
            self.sysex(1, nibbles(0x0999, 4)),
        ])
        status = [m[12] for m in out[:7]]
        self.assertEqual(status, [0, 2, 0, 3, 0, 2, 1])
        self.assertEqual(out[7:11], ["value 75", "value -10", "value 0", "text 'Bob'"])
        self.assertEqual(out[11], PREFIX + bytes([1]) + nibbles(0x0202, 4) + b"\x00"
                         + nibbles(0xFFF6, 4) + b"\xf7")
        self.assertEqual(out[12][13:], string("Bob") + b"\xf7")
        self.assertEqual(out[13][12:], b"\x01\xf7")

    def test_other_sysex_goes_to_stock(self):
        out = self.run_host(["f0002029016170f7", "f00020290161f7", "f000202900707cf7"])
        self.assertEqual(out, ["stock 0 8", "stock 0 7", "stock 0 8"])


if __name__ == "__main__":
    unittest.main()
