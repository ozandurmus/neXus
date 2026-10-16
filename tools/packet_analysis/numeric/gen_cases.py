#!/usr/bin/env python3
"""Generate CPython differential vectors for the Java numeric layer of Packet Analysis.

Run with the frozen PacketBreaker interpreter (CPython 3.12), from the repository root:

    ~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/numeric/gen_cases.py

Writes ui2/packet-analysis/src/test/resources/packetanalysis/numeric/reference_vectors.json.gz. Output is
deterministic (fixed seed, fixed gzip mtime): rerunning on the same CPython must give a byte-identical file.

Encoding: "pool" is the shared float input list; "repr", "round0" and "float_to_int" rows are aligned with it by
index. A float is "f:<16 hex digits of its IEEE-754 bits>", an int is "i:<decimal>", a Python exception is
"!<ExceptionName>". Strings (repr, format output) are plain JSON strings.
"""

from __future__ import annotations

import datetime
import gzip
import io
import json
import math
import platform
import random
import statistics
import struct
import sys
from pathlib import Path

SEED = 20261015
OUT = Path(__file__).resolve().parents[3] / "ui2/packet-analysis/src/test/resources/packetanalysis/numeric"
rng = random.Random(SEED)


def bits(x: float) -> str:
    return "f:" + struct.pack(">d", x).hex()


def from_bits(h: str) -> float:
    return struct.unpack(">d", bytes.fromhex(h))[0]


def enc(v) -> str:
    if isinstance(v, bool):
        return f"i:{int(v)}"
    if isinstance(v, int):
        # The Java layer holds ints in a long and must raise instead of returning a wider int.
        return f"i:{v}" if -(2 ** 63) <= v < 2 ** 63 else "!IntTooWide"
    if isinstance(v, float):
        return bits(v)
    raise TypeError(type(v))


def guard(fn, *args, **kwargs):
    try:
        return fn(*args, **kwargs), None
    except Exception as e:  # noqa: BLE001 - the exception class is the expected output
        return None, "!" + type(e).__name__


def random_bits_float() -> float:
    return from_bits(rng.getrandbits(64).to_bytes(8, "big").hex())


def interesting_floats() -> list[float]:
    vals = [0.0, -0.0, math.inf, -math.inf, math.nan, 5e-324, -5e-324, 2.2250738585072014e-308,
            2.225073858507201e-308, 1.7976931348623157e308, -1.7976931348623157e308, 1e-05, 1e-5 * 3, 0.0001,
            0.001, 0.1, 0.2, 0.3, 1 / 3, 2 / 3, 100.0, 1e15, 1e16, 1e17, 9007199254740992.0, 9007199254740993.0,
            1e22, 1e23, 2e23, 5e-310, 1.5, 2.5, -2.5, 0.5, -0.5, 2.675, 1.005, 0.125, 0.375, 1e300, 123456789.0,
            999999.5, 9999999.5, 0.00001234, 12345.678, 4.35, 0.045, 1e-7, 6.02214076e23]
    vals += [2.0 ** e for e in range(-1074, 1024)]
    vals += [-(2.0 ** e) for e in range(-1074, 1024, 7)]
    vals += [math.nextafter(2.0 ** e, math.inf) for e in range(-1070, 1023, 3)]
    vals += [math.nextafter(2.0 ** e, 0.0) for e in range(-1070, 1023, 3)]
    vals += [float(f"1e{e}") for e in range(-323, 309)]
    vals += [float(f"{d}e{e}") for d in range(1, 10) for e in range(-320, 300, 13)]
    return vals


def nice_decimals(count: int) -> list[float]:
    out = []
    for _ in range(count):
        digits = rng.randint(1, 17)
        mantissa = rng.randrange(10 ** (digits - 1), 10 ** digits)
        exp = rng.randint(-30, 30) if rng.random() < 0.8 else rng.randint(-320, 300)
        sign = -1 if rng.random() < 0.3 else 1
        out.append(sign * float(f"{mantissa}e{exp}"))
    return out


def ties(count: int) -> list[float]:
    """Values that look like (or are) .5 ties at a few decimal places."""
    out = []
    for _ in range(count):
        n = rng.randint(0, 6)
        k = rng.randint(-10 ** 6, 10 ** 6)
        out.append((k + 0.5) / 10 ** n)
        out.append(float(f"{k}.{'0' * max(0, n - 1)}5") if n else k + 0.5)
        out.append(k / 2 ** rng.randint(1, 12))
    return out


def measurement_like(count: int) -> list[float]:
    """Magnitudes PacketBreaker sees: epoch seconds, milliseconds, ratios, percents."""
    out = []
    for _ in range(count):
        kind = rng.randrange(5)
        if kind == 0:
            out.append(1_700_000_000 + rng.random() * 10 ** 7)
        elif kind == 1:
            out.append(rng.random() * 1000)
        elif kind == 2:
            out.append(rng.random())
        elif kind == 3:
            out.append(rng.random() * 100 - 50)
        else:
            out.append(rng.choice([1, -1]) * rng.random() * 10 ** rng.randint(-9, 12))
    return out


def float_pool() -> list[float]:
    return (interesting_floats() + [random_bits_float() for _ in range(4000)] + nice_decimals(3000)
            + ties(600) + measurement_like(2500))


def typed_list(length: int, mode: str) -> list:
    if mode == "int":
        span = rng.choice([3, 10, 1000, 10 ** 15])
        return [rng.randint(-span, span) for _ in range(length)]
    if mode == "smallfloat":
        return [rng.choice([0.0, -0.0, 0.5, 1.0, -1.0, 2.5, 3.0]) for _ in range(length)]
    if mode == "nan":
        return [rng.choice([math.nan, rng.random(), -rng.random(), 0.0, -0.0, math.inf]) for _ in range(length)]
    if mode == "mixed":
        return [rng.randint(-50, 50) if rng.random() < 0.5 else round(rng.uniform(-50, 50), rng.randint(0, 3))
                for _ in range(length)]
    if mode == "bigmixed":
        return [rng.choice([2 ** 53 + 1, 2 ** 53, 2 ** 60 + 7, -(2 ** 61) - 3]) if rng.random() < 0.5
                else rng.choice([9007199254740992.0, 1.152921504606847e18, -2.305843009213694e18, math.nan])
                for _ in range(length)]
    return [rng.choice(measurement_like(1) + [random_bits_float()]) for _ in range(length)]


def structured(length: int) -> list[float]:
    """Runs, reversed runs and duplicates so the merge/gallop paths of timsort run, with NaN sprinkled in."""
    out: list[float] = []
    while len(out) < length:
        run = sorted(rng.random() * 100 for _ in range(rng.randint(1, 200)))
        if rng.random() < 0.3:
            run.reverse()
        if rng.random() < 0.3:
            run = [round(v) for v in run]
        out.extend(float(v) for v in run)
    out = out[:length]
    for _ in range(rng.randint(0, max(1, length // 50))):
        out[rng.randrange(length)] = rng.choice([math.nan, -0.0, 0.0])
    return out


def section_repr(pool):
    """repr(pool[i]); str() and json.dumps() are asserted equal to repr() for finite values here."""
    rows = []
    for x in pool:
        assert str(x) == repr(x)
        assert not math.isfinite(x) or json.dumps(x) == repr(x) == json.dumps(x, allow_nan=False)
        rows.append(repr(x))
    return rows


def section_json(pool):
    rows = []
    for x in [math.nan, -math.nan, math.inf, -math.inf, 0.0, -0.0] + rng.sample(pool, 300):
        strict, err = guard(json.dumps, x, allow_nan=False)
        rows.append([bits(x), json.dumps(x), err or strict])
    return rows


def section_round(pool):
    rows_n, rows_0 = [], []
    for x in pool:
        r, err = guard(round, x)
        rows_0.append(err or enc(r))
    for x in pool[::3] + ties(1200):
        for n in rng.sample([-400, -309, -308, -20, -3, -2, -1, 0, 1, 2, 3, 4, 6, 9, 15, 17, 20, 300, 323, 324], 3):
            r, err = guard(round, x, n)
            rows_n.append([bits(x), n, err or enc(r)])
    ints = []
    for _ in range(600):
        i = rng.choice([rng.randint(-10 ** 6, 10 ** 6), rng.randint(-10 ** 18, 10 ** 18), rng.randint(-99, 99) * 5])
        for n in (-1, -2, -3, 0, 2):
            ints.append([enc(i), n, enc(round(i, n))])
    return rows_0, rows_n, ints


FLOAT_SPECS = [".1f", ".2f", ".3f", ".9f", "g", ".1%", "", ",", ".0f", ".6f", "f", "e", ".3e", ".10g", ".17g",
               ".3g", "G", "E", ">10.2f", "<10.1f", "^12.3f", "+.3f", " .2f", "010.3f", ",.2f", "_.1f", "%", ".0%",
               "*>12g", "=+10.1f", "15", ".2F"]
INT_SPECS = ["", "d", "02d", "03d", "04d", "05d", "03", "x", "04x", "X", "#x", "#06x", ",", "_", ",d", "_x", "b",
             "o", "+d", " d", ">6", "<6d", "^7", "=+8d", ".3f", ".1f", "g", ".1%", "e"]


def section_format(pool):
    floats = []
    for x in pool[::4]:
        for spec in rng.sample(FLOAT_SPECS, 5):
            out, err = guard(format, x, spec)
            floats.append([bits(x), spec, err or out])
    for x in (0.125, 0.375, 1.005, 2.675, -0.0, 0.0, -0.0001, 0.0005, 0.0015, 1e-10, 1e300, math.nan, -math.nan,
              math.inf, -math.inf, 999999.5, 0.5, 1.5, 2.5, 12345.6789):
        for spec in FLOAT_SPECS:
            out, err = guard(format, x, spec)
            floats.append([bits(x), spec, err or out])
    ints = []
    values = [0, 1, -1, 7, 255, 256, -255, 1000, 65535, 1234567, -1234567, 2 ** 31, 2 ** 63 - 1, -(2 ** 63),
              4294967295] + [rng.randint(-10 ** 12, 10 ** 12) for _ in range(300)]
    for i in values:
        for spec in INT_SPECS:
            out, err = guard(format, i, spec)
            ints.append([enc(i), spec, err or out])
    return floats, ints


def section_stats():
    median_rows, quantile_rows = [], []
    modes = ["int", "smallfloat", "nan", "mixed", "bigmixed", "float"]
    for length in list(range(0, 41)) + [63, 64, 65, 100, 127, 128, 129, 257, 600]:
        for mode in modes:
            data = typed_list(length, mode)
            m, err = guard(statistics.median, data)
            median_rows.append([[enc(v) for v in data], err or enc(m)])
    for _ in range(20):
        data = structured(rng.randint(64, 1200))
        median_rows.append([[enc(v) for v in data], enc(statistics.median(data))])
    for length in list(range(0, 12)) + [20, 21, 50, 99, 100, 101, 150, 333]:
        for mode in modes:
            data = typed_list(length, mode)
            for n, method in ((100, "inclusive"), (20, "inclusive"), (4, "inclusive"), (4, "exclusive"),
                              (100, "exclusive"), (1, "inclusive"), (0, "inclusive"), (3, "exclusive")):
                q, err = guard(statistics.quantiles, data, n=n, method=method)
                quantile_rows.append([[enc(v) for v in data], n, method, err or [enc(v) for v in q]])
    return median_rows, quantile_rows


def section_sort():
    rows = []
    for length in list(range(0, 70)) + [100, 128, 200, 255, 256, 500, 1000, 2047, 3000]:
        data = structured(length) if length >= 64 and rng.random() < 0.6 else typed_list(length, "nan")
        rows.append([[enc(v) for v in data], False, [enc(v) for v in sorted(data)]])
        rows.append([[enc(v) for v in data], True, [enc(v) for v in sorted(data, reverse=True)]])
    for length in (10, 70, 300):
        for mode in ("mixed", "bigmixed", "int"):
            data = typed_list(length, mode)
            rows.append([[enc(v) for v in data], False, [enc(v) for v in sorted(data)]])
    return rows


def section_sum_minmax():
    sums, mins = [], []
    for length in list(range(0, 30)) + [100, 1000]:
        for mode in ("float", "smallfloat", "nan", "mixed", "int"):
            data = typed_list(length, mode)
            sums.append([[enc(v) for v in data], enc(sum(data))])
            lo, e1 = guard(min, data)
            hi, e2 = guard(max, data)
            mins.append([[enc(v) for v in data], e1 or enc(lo), e2 or enc(hi)])
    special = [[1e308, 1e308, -1e308], [1e16, 1.0, -1e16], [0.1] * 10, [-0.0], [-0.0, -0.0], [0.0, -0.0],
               [-0.0, 0.0], [math.inf, -math.inf], [math.inf, 1.0], [1e100, 1.0, -1e100, 1e-100], [1, 0.5, 2],
               [3, -0.0], [0.5, 1, 1e16, -1e16], [2 ** 53, 1.0, 1.0], [math.nan, 1.0], [1.0, math.nan]]
    for data in special:
        sums.append([[enc(v) for v in data], enc(sum(data))])
        mins.append([[enc(v) for v in data], enc(min(data)), enc(max(data))])
    return sums, mins


def section_division(pool):
    ints = []
    for _ in range(1500):
        a = rng.choice([rng.randint(-100, 100), rng.randint(-10 ** 18, 10 ** 18), -rng.randint(0, 2 ** 32)])
        b = rng.choice([rng.randint(-20, 20), 4, 65536, 4294967296, rng.randint(-10 ** 18, 10 ** 18), 2 ** 53 + 1])
        fd, e1 = guard(lambda: a // b)
        md, e2 = guard(lambda: a % b)
        td, e3 = guard(lambda: a / b)
        ints.append([enc(a), enc(b), e1 or enc(fd), e2 or enc(md), e3 or enc(td)])
    floats = []
    sample = pool[::5] + [0.0, -0.0, math.inf, -math.inf, math.nan, 1.0, -1.0, 3.0, -3.0, 0.1]
    for _ in range(2500):
        a, b = rng.choice(sample), rng.choice(sample)
        fd, e1 = guard(lambda: a // b)
        md, e2 = guard(lambda: a % b)
        floats.append([bits(a), bits(b), e1 or enc(fd), e2 or enc(md)])
    convert = []
    for x in pool:
        f, e1 = guard(math.floor, x)
        c, e2 = guard(math.ceil, x)
        t, e3 = guard(int, x)
        convert.append([e1 or enc(f), e2 or enc(c), e3 or enc(t)])
    return ints, floats, convert


FLOAT_STRINGS = ["1", "1.5", " 1.5 ", "\t-2.25\n", "1_000.5", "1__0", "_1", "1_", "1_.5", "1._5", ".5", "5.", ".",
                 "", " ", "1e5", "1E-5", "1e", "e5", "1e+400", "-1e400", "1e-400", "inf", "-inf", "+Infinity",
                 "INF", "nan", "-nan", "NaN", "infinit", "0x10", "1.5d", "1.5f", "1,5", "+.5e-3", "--1", "0.1",
                 "1696.123456789", "1700000000.000001234", "4.9e-324", "2.4703282292062328e-324",
                 "2.4703282292062327e-324", "1.7976931348623158e308", "1.7976931348623159e308", "00012", "1e0_1",
                 "9007199254740993", "0.30000000000000004"]
INT_STRINGS = [("12", 10), (" 12 ", 10), ("-7", 10), ("+7", 10), ("1_000", 10), ("1__0", 10), ("0x1f", 16),
               ("1f", 16), ("0X1F", 16), ("0x1f", 0), ("0o17", 0), ("0b101", 0), ("017", 0), ("00", 0), ("0", 0),
               ("0_0", 0), ("0x_1f", 0), ("0x", 16), ("", 10), ("1.5", 10), ("0b1", 16), ("ff", 16), ("FF", 16),
               ("0x00000002", 16), ("0x00000002", 0), ("9223372036854775807", 10), ("-9223372036854775808", 10),
               ("z", 36), ("12", 8), ("19", 8), ("-0x10", 0), ("0x10", 10), (" 0x1f\n", 0), ("1_", 10),
               ("_1", 10), ("0o_7", 0)]


def section_parse():
    floats = []
    for s in FLOAT_STRINGS + [repr(x) for x in rng.sample(float_pool(), 1500)]:
        v, err = guard(float, s)
        floats.append([s, err or enc(v)])
    ints = []
    for s, base in INT_STRINGS:
        v, err = guard(int, s, base)
        ints.append([s, base, err or enc(v)])
    return floats, ints


def section_time(pool):
    rows = []
    stamps = [1_700_000_000.0000005, 1_700_000_000.0000015, 1_700_000_000.9999995, -1.5e-7, -0.0000005, 0.0000005,
              0.0000015, -1.0000005, 1_696_000_000.123456789]
    stamps += [1_700_000_000 + rng.random() * 10 ** 7 for _ in range(1500)]
    stamps += [rng.randint(0, 2 * 10 ** 9) + rng.randint(0, 2 * 10 ** 6) / 2 ** 21 for _ in range(500)]
    stamps += [rng.uniform(-10 ** 9, 4 * 10 ** 9) for _ in range(300)]
    stamps += [round(rng.uniform(0, 2 * 10 ** 9), 3) for _ in range(300)]
    for t in stamps:
        dt = datetime.datetime.fromtimestamp(t, datetime.timezone.utc)
        epoch = datetime.datetime(1970, 1, 1, tzinfo=datetime.timezone.utc)
        delta = dt - epoch
        micros = (delta.days * 86400 + delta.seconds) * 1_000_000 + delta.microseconds
        rows.append([bits(t), enc(micros)])
    return rows


def main() -> int:
    if sys.version_info[:2] != (3, 12):
        print(f"expected CPython 3.12 (the frozen PacketBreaker venv), got {platform.python_version()}",
              file=sys.stderr)
        return 2
    pool = float_pool()
    rounds0, rounds_n, round_int = section_round(pool)
    fmt_float, fmt_int = section_format(pool)
    medians, quantiles = section_stats()
    sums, minmax = section_sum_minmax()
    div_int, div_float, convert = section_division(pool)
    parse_float, parse_int = section_parse()
    doc = {
        "generator": "tools/packet_analysis/numeric/gen_cases.py",
        "interpreter": platform.python_version(),
        "implementation": platform.python_implementation(),
        "machine": platform.machine(),
        "seed": SEED,
        "pool": [bits(x) for x in pool],
        "repr": section_repr(pool),
        "json": section_json(pool),
        "round0": rounds0,
        "round_n": rounds_n,
        "round_int": round_int,
        "format_float": fmt_float,
        "format_int": fmt_int,
        "median": medians,
        "quantiles": quantiles,
        "sort": section_sort(),
        "sum": sums,
        "minmax": minmax,
        "div_int": div_int,
        "div_float": div_float,
        "float_to_int": convert,
        "parse_float": parse_float,
        "parse_int": parse_int,
        "fromtimestamp": section_time(pool),
    }
    counts = {k: len(v) for k, v in doc.items() if isinstance(v, list)}
    doc["counts"] = counts
    raw = json.dumps(doc, separators=(",", ":"), ensure_ascii=True).encode()
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", compresslevel=9, mtime=0, filename="") as gz:
        gz.write(raw)
    OUT.mkdir(parents=True, exist_ok=True)
    target = OUT / "reference_vectors.json.gz"
    target.write_bytes(buf.getvalue())
    print(f"{target}: {len(buf.getvalue())} bytes gzip, {len(raw)} bytes json, {sum(counts.values())} cases")
    for k, v in counts.items():
        print(f"  {k}: {v}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
