#!/usr/bin/env python3
"""Turns Flipper-IRDB TV remote files (.ir) into one compact table for the app.

Usage: build_tv_codes.py <TVs folder> <output tvcodes.json>

Only the buttons the app needs are kept: Mute, Play and Fast-forward. Every
code is converted to raw on/off timings in microseconds, so the app never has
to know about remote-control protocols.

Output format:
  {"v": 1,
   "codes": ["38000,9000,4500,...", ...],            # carrier Hz, then timings
   "brands": [{"n": "Samsung",
               "s": [["file_name", mute, play, ff], ...]}]}   # indexes, -1 = none
"""
import json
import os
import re
import sys
from collections import Counter

MAX_TIMINGS = 400

MUTE_NAMES = {"mute", "muteunmute", "tvmute"}
PLAY_NAMES = {"play", "playpause", "pauseplay"}
FF_NAMES = {"ff", "fastforward", "fastfwd", "fforward", "forward", "fwd"}


# ----------------------------------------------------------------- parsing

def parse_ir(text):
    """Returns a list of dicts, one per button block, with lowercase keys."""
    signals = []
    cur = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or ":" not in line:
            continue
        key, value = line.split(":", 1)
        key = key.strip().lower()
        value = value.strip()
        if key == "name":
            if "name" in cur:
                signals.append(cur)
            cur = {}
        cur[key] = value
    if "name" in cur:
        signals.append(cur)
    return signals


def hex_bytes(s):
    return [int(x, 16) for x in s.split()]


def bits_lsb(value, n):
    return [(value >> i) & 1 for i in range(n)]


# ---------------------------------------------------------------- encoders
# Each encoder takes (address_bytes, command_bytes) and returns
# (carrier_hz, timings) where timings start with an "on" burst.

def _pulse_distance(preamble, mark, one_space, zero_space, data_bytes):
    t = list(preamble)
    for b in data_bytes:
        for bit in bits_lsb(b, 8):
            t += [mark, one_space if bit else zero_space]
    t.append(mark)
    return t


def enc_nec(addr, cmd):
    a, c = addr[0], cmd[0]
    return 38000, _pulse_distance((9000, 4500), 560, 1690, 560, [a, a ^ 0xFF, c, c ^ 0xFF])


def enc_necext(addr, cmd):
    return 38000, _pulse_distance((9000, 4500), 560, 1690, 560, [addr[0], addr[1], cmd[0], cmd[1]])


def enc_samsung32(addr, cmd):
    a0 = addr[0]
    a1 = addr[1] if len(addr) > 1 and addr[1] != 0 else a0
    c = cmd[0]
    return 38000, _pulse_distance((4500, 4500), 560, 1690, 560, [a0, a1, c, c ^ 0xFF])


def _sirc(addr_value, addr_bits, cmd_value):
    bits = bits_lsb(cmd_value & 0x7F, 7) + bits_lsb(addr_value, addr_bits)
    frame = [2400, 600]
    for b in bits:
        frame += [1200 if b else 600, 600]
    frame = frame[:-1]  # end on a burst, the gap comes from the repeat spacing
    total = sum(frame)
    out = []
    for i in range(3):  # Sony TVs expect the frame three times
        if i:
            out.append(max(45000 - total, 5000))
        out += frame
    return 40000, out


def enc_sirc(addr, cmd):
    return _sirc(addr[0] & 0x1F, 5, cmd[0])


def enc_sirc15(addr, cmd):
    return _sirc(addr[0], 8, cmd[0])


def enc_sirc20(addr, cmd):
    a = addr[0] | ((addr[1] if len(addr) > 1 else 0) << 8)
    return _sirc(a & 0x1FFF, 13, cmd[0])


def _rc5(addr, cmd, extended):
    a = addr[0] & 0x1F
    c = cmd[0] & (0x7F if extended else 0x3F)
    s2 = (0 if (c & 0x40) else 1) if extended else 1
    bits = [1, s2, 0]  # start, second start (or inverted command bit 6), toggle
    bits += [(a >> i) & 1 for i in range(4, -1, -1)]
    bits += [(c >> i) & 1 for i in range(5, -1, -1)]
    levels = []
    for b in bits:  # Manchester: 1 = off then on, 0 = on then off
        levels += [False, True] if b else [True, False]
    out = []
    cur, run = levels[0], 1
    for lv in levels[1:]:
        if lv == cur:
            run += 1
        else:
            out.append((cur, run * 889))
            cur, run = lv, 1
    out.append((cur, run * 889))
    if not out[0][0]:
        out = out[1:]  # a leading gap cannot be sent
    return 36000, [d for _, d in out]


def enc_rc5(addr, cmd):
    return _rc5(addr, cmd, False)


def enc_rc5x(addr, cmd):
    return _rc5(addr, cmd, True)


ENCODERS = {
    "NEC": enc_nec,
    "NECext": enc_necext,
    "Samsung32": enc_samsung32,
    "SIRC": enc_sirc,
    "SIRC15": enc_sirc15,
    "SIRC20": enc_sirc20,
    "RC5": enc_rc5,
    "RC5X": enc_rc5x,
}


def signal_pattern(sig, stats):
    """Returns 'freq,t1,t2,...' for one button block, or None if unusable."""
    try:
        if sig.get("type", "").lower() == "raw":
            freq = int(float(sig.get("frequency", "38000")))
            data = [int(x) for x in sig.get("data", "").split()]
            if len(data) < 4 or len(data) > MAX_TIMINGS or min(data) <= 0:
                stats["skipped raw"] += 1
                return None
            timings = data
        else:
            proto = sig.get("protocol", "")
            enc = ENCODERS.get(proto)
            if enc is None:
                stats["skipped protocol " + proto] += 1
                return None
            freq, timings = enc(hex_bytes(sig["address"]), hex_bytes(sig["command"]))
        if not 20000 <= freq <= 60000:
            freq = 38000
        return str(freq) + "," + ",".join(str(int(x)) for x in timings)
    except (KeyError, ValueError, IndexError):
        stats["skipped malformed"] += 1
        return None


# ------------------------------------------------------------------ table

def norm(name):
    return re.sub("[^a-z]", "", name.lower())


def build(tv_dir, stats):
    codes = []
    index = {}

    def intern(pattern):
        if pattern not in index:
            index[pattern] = len(codes)
            codes.append(pattern)
        return index[pattern]

    brands = {}
    for root, _, files in os.walk(tv_dir):
        for fname in sorted(files):
            if not fname.lower().endswith(".ir"):
                continue
            stats["files"] += 1
            path = os.path.join(root, fname)
            rel = os.path.relpath(path, tv_dir)
            parts = rel.split(os.sep)
            stem = os.path.splitext(fname)[0]
            brand = parts[0] if len(parts) > 1 else stem.split("_")[0]
            brand = brand.replace("_", " ").strip() or "Other"

            with open(path, encoding="utf-8", errors="replace") as fh:
                signals = parse_ir(fh.read())
            found = {}
            for sig in signals:
                n = norm(sig.get("name", ""))
                kind = "mute" if n in MUTE_NAMES else "play" if n in PLAY_NAMES else "ff" if n in FF_NAMES else None
                if kind is None or kind in found:
                    continue
                pattern = signal_pattern(sig, stats)
                if pattern is not None:
                    found[kind] = pattern
            if "mute" not in found:
                stats["files without usable mute"] += 1
                continue
            entry = [stem, intern(found["mute"]),
                     intern(found["play"]) if "play" in found else -1,
                     intern(found["ff"]) if "ff" in found else -1]
            key = brand.lower()
            bucket = brands.setdefault(key, {"n": brand, "seen": set(), "s": []})
            triple = tuple(entry[1:])
            if triple in bucket["seen"]:
                stats["duplicate sets dropped"] += 1
                continue
            bucket["seen"].add(triple)
            bucket["s"].append(entry)
            stats["sets"] += 1
            if entry[2] >= 0 and entry[3] >= 0:
                stats["sets with play and fast-forward"] += 1

    out_brands = [{"n": b["n"], "s": b["s"]} for _, b in sorted(brands.items())]
    return {"v": 1, "codes": codes, "brands": out_brands}


def main(argv):
    if len(argv) != 3:
        print(__doc__)
        return 2
    stats = Counter()
    table = build(argv[1], stats)
    os.makedirs(os.path.dirname(os.path.abspath(argv[2])), exist_ok=True)
    with open(argv[2], "w", encoding="utf-8") as fh:
        json.dump(table, fh, separators=(",", ":"))
    print("TV code table written to", argv[2], "(%d bytes)" % os.path.getsize(argv[2]))
    print("brands:", len(table["brands"]), " unique codes:", len(table["codes"]))
    for k, v in sorted(stats.items()):
        print("  %s: %d" % (k, v))
    if stats["sets"] == 0:
        print("No usable code sets found.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
