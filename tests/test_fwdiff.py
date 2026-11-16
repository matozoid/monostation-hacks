import hashlib
import random
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))

import fwdiff  # noqa: E402
import fwsyx  # noqa: E402

FIRMWARE_800 = ROOT / "private" / "firmware" / "monostation-firmware-800.syx"
MODS_754 = ROOT / "private" / "firmware" / "mods-754.syx"
BUILDS = ROOT / "flasher" / "src" / "jsMain" / "resources" / "builds"


def synthetic():
    rng = random.Random(1)
    base = bytes(rng.randrange(256) for _ in range(4096))
    target = bytearray(base)
    target[100:104] = b"\x01\x02\x03\x04"                   # changed in place
    target += bytes(rng.randrange(256) for _ in range(200))  # our own code
    target += base[2000:2100]                                # stock moved
    target += b"\xff" * (-len(target) % fwsyx.BLOCK_BYTES)
    return base, bytes(target)


class SyntheticTest(unittest.TestCase):
    def test_round_trip_and_moved_stock(self):
        base, target = synthetic()
        ops = fwdiff.build_ops(base, target)
        self.assertEqual(fwdiff.apply_ops(base, ops), target)
        self.assertIn(("copy", 2000, 100), ops)
        data = sum(len(op[1]) for op in ops if op[0] == "data")
        self.assertLess(data, 4 + 200 + fwsyx.BLOCK_BYTES)

    def test_apply_checks_base_and_result(self):
        base, target = synthetic()
        base_syx = fwsyx.encode_firmware(base, fwsyx.MONOSTATION_PRODUCT_ID, 800)
        ops = fwdiff.build_ops(base, target)
        header = {
            "base-syx-sha256": hashlib.sha256(base_syx).hexdigest(),
            "target-version": 756,
            "target-length": len(target),
            "target-sha256": hashlib.sha256(target).hexdigest(),
        }
        lines = [fwdiff.MAGIC] + [f"{k} {v}" for k, v in header.items()]
        for op in ops:
            lines.append(f"copy {op[1]:x} {op[2]}" if op[0] == "copy" else f"data {op[1].hex()}")
        text = "\n".join(lines) + "\n"
        self.assertEqual(fwdiff.apply(base_syx, text),
                         fwsyx.encode_firmware(target, fwsyx.MONOSTATION_PRODUCT_ID, 756))
        with self.assertRaisesRegex(ValueError, "stock firmware"):
            fwdiff.apply(base_syx + b"\x00", text)
        with self.assertRaisesRegex(ValueError, "checksum"):
            fwdiff.apply(base_syx, text.replace("data ", "data 00", 1))


@unittest.skipUnless(FIRMWARE_800.exists(), "needs private/firmware")
class ShippedBuildsTest(unittest.TestCase):
    def test_every_build_applies(self):
        stock = FIRMWARE_800.read_bytes()
        for diff in sorted(BUILDS.glob("*.fwdiff")):
            with self.subTest(diff.name):
                syx = fwdiff.apply(stock, diff.read_text())
                self.assertEqual(fwsyx.parse_start(fwsyx.split_messages(syx)[0])[0], fwsyx.MONOSTATION_PRODUCT_ID)

    @unittest.skipUnless(MODS_754.exists(), "needs mods-754.syx")
    def test_mods_754_is_byte_exact(self):
        syx = fwdiff.apply(FIRMWARE_800.read_bytes(), (BUILDS / "mods-754.fwdiff").read_text())
        self.assertEqual(syx, MODS_754.read_bytes())


if __name__ == "__main__":
    unittest.main()
