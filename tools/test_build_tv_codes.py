import json
import os
import tempfile
import unittest
from collections import Counter

import build_tv_codes as b


def nec_value(timings, preamble=2):
    """Reads the 32 data bits back out of a pulse-distance frame, first bit as the top bit."""
    pairs = timings[preamble:preamble + 64]
    value = 0
    for i in range(0, 64, 2):
        value = (value << 1) | (1 if pairs[i + 1] > 1000 else 0)
    return value


SAMPLE = """Filetype: IR signals file
Version: 1
#
# Model: Test TV
name: Power
type: parsed
protocol: NEC
address: 04 00 00 00
command: 08 00 00 00
#
name: Mute
type: parsed
protocol: NEC
address: 04 00 00 00
command: 09 00 00 00
#
name: Play
type: raw
frequency: 38000
duty_cycle: 0.330000
data: 9000 4500 560 560 560 1690 560 560 560 1690
#
name: Fast_forward
type: parsed
protocol: SIRC
address: 01 00 00 00
command: 1C 00 00 00
#
name: Input
type: parsed
protocol: Kaseikyo
address: 80 02 20 00
command: D0 03 00 00
"""


class EncoderTests(unittest.TestCase):
    def test_nec_matches_lg_codes_the_app_already_uses(self):
        _, power = b.enc_nec([0x04], [0x08])
        self.assertEqual(nec_value(power), 0x20DF10EF)
        _, mute = b.enc_nec([0x04], [0x09])
        self.assertEqual(nec_value(mute), 0x20DF906F)

    def test_samsung32_matches_samsung_codes_the_app_already_uses(self):
        _, mute = b.enc_samsung32([0x07], [0x0F])
        self.assertEqual(nec_value(mute), 0xE0E0F00F)
        _, play = b.enc_samsung32([0x07], [0x07])
        self.assertEqual(nec_value(play), 0xE0E0E01F)
        _, ff = b.enc_samsung32([0x07], [0x47])
        self.assertEqual(nec_value(ff), 0xE0E0E21D)

    def test_nec_frame_shape(self):
        freq, t = b.enc_nec([0x04], [0x08])
        self.assertEqual(freq, 38000)
        self.assertEqual(len(t), 67)
        self.assertEqual(t[:2], [9000, 4500])

    def test_sirc_power_bits_and_repeats(self):
        freq, t = b.enc_sirc([0x01], [0x15])
        self.assertEqual(freq, 40000)
        self.assertEqual(t[:2], [2400, 600])
        frame_len = 2 + 12 * 2 - 1
        self.assertEqual(len(t), frame_len * 3 + 2)
        bits = "".join("1" if t[2 + 2 * i] == 1200 else "0" for i in range(12))
        self.assertEqual(bits, "101010010000")

    def test_rc5_starts_with_a_burst_and_is_plausible(self):
        freq, t = b.enc_rc5([0x05], [0x0C])
        self.assertEqual(freq, 36000)
        self.assertTrue(all(x % 889 == 0 for x in t))
        self.assertEqual(sum(t) + 889, 889 * 28)  # 14 bits, the leading gap was dropped

    def test_rc5x_second_start_bit_is_inverted_command_bit_six(self):
        _, with_bit = b.enc_rc5x([0x05], [0x4C])
        _, without = b.enc_rc5x([0x05], [0x0C])
        self.assertNotEqual(with_bit, without)


class ParseAndBuildTests(unittest.TestCase):
    def test_parse_ir_ignores_comments_and_splits_blocks(self):
        sigs = b.parse_ir(SAMPLE)
        self.assertEqual([s["name"] for s in sigs], ["Power", "Mute", "Play", "Fast_forward", "Input"])

    def test_build_keeps_mute_play_ff_and_skips_unsupported(self):
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(os.path.join(d, "Acme"))
            with open(os.path.join(d, "Acme", "Acme_T1.ir"), "w") as fh:
                fh.write(SAMPLE)
            with open(os.path.join(d, "Acme", "Acme_T2.ir"), "w") as fh:
                fh.write(SAMPLE)  # identical codes, should be dropped as a duplicate
            with open(os.path.join(d, "Acme", "Acme_NoMute.ir"), "w") as fh:
                fh.write("Filetype: IR signals file\nVersion: 1\n#\nname: Power\ntype: parsed\nprotocol: NEC\naddress: 04 00 00 00\ncommand: 08 00 00 00\n")
            stats = Counter()
            table = b.build(d, stats)
        self.assertEqual(len(table["brands"]), 1)
        brand = table["brands"][0]
        self.assertEqual(brand["n"], "Acme")
        self.assertEqual(len(brand["s"]), 1)
        _, mute, play, ff = brand["s"][0]
        self.assertTrue(table["codes"][mute].startswith("38000,9000,4500"))
        self.assertEqual(table["codes"][play], "38000,9000,4500,560,560,560,1690,560,560,560,1690")
        self.assertTrue(table["codes"][ff].startswith("40000,2400,600"))
        self.assertEqual(stats["duplicate sets dropped"], 1)
        self.assertEqual(stats["files without usable mute"], 1)
        json.dumps(table)


if __name__ == "__main__":
    unittest.main()
