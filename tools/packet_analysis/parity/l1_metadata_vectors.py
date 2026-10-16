#!/usr/bin/env python3
"""Reference vectors for the Java capture container walker (synthetic bytes, no real capture).

Run with the frozen PacketBreaker interpreter, from the repository root:

    ~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/l1_metadata_vectors.py

Writes ui2/packet-analysis/src/test/resources/packetanalysis/ingest/metadata_vectors.json.gz: small pcap, pcapng
and snoop files built here, every truncation of each, zero-filled tails, and seeded single-byte corruptions, each
with json.dumps(metadata(file)) or the exception name. Deterministic (fixed seed, fixed gzip mtime).
"""

from __future__ import annotations

import gzip
import io
import json
import random
import struct
import tempfile
from pathlib import Path

from packetbreaker.metadata import metadata

OUT = Path(__file__).resolve().parents[3] / "ui2/packet-analysis/src/test/resources/packetanalysis/ingest"
rng = random.Random(20261015)


def pcap(endian, magic, snaplen, link, caplens, body=b"\x5a"):
    out = struct.pack(endian + "IHHIIII", magic, 2, 4, 0, 0, snaplen, link)
    for i, caplen in enumerate(caplens):
        out += struct.pack(endian + "IIII", 1_700_000_000 + i, i, caplen, caplen + 4) + body * caplen
    return out


def block(endian, kind, body):
    n = 12 + len(body)
    return struct.pack(endian + "II", kind, n) + body + struct.pack(endian + "I", n)


def shb(endian):
    return block(endian, 0x0A0D0D0A, struct.pack(endian + "IHHq", 0x1A2B3C4D, 1, 0, -1))


def opt(endian, code, value):
    return struct.pack(endian + "HH", code, len(value)) + value + b"\0" * ((4 - len(value) % 4) % 4)


def idb(endian, link, snaplen, name=None):
    body = struct.pack(endian + "HHI", link, 0, snaplen)
    if name is not None:
        body += opt(endian, 2, name) + opt(endian, 0, b"")
    return block(endian, 1, body)


def epb(endian, iface, caplen, fill=b"\x33"):
    body = struct.pack(endian + "IIIII", iface, 0, 0, caplen, caplen) + fill * caplen
    body += b"\0" * ((4 - caplen % 4) % 4)
    return block(endian, 6, body)


def spb(endian, caplen):
    return block(endian, 3, struct.pack(endian + "I", caplen) + b"\x44" * ((caplen + 3) & ~3))


def isb(endian, iface, ifdrop, osdrop):
    body = struct.pack(endian + "III", iface, 0, 0) + opt(endian, 5, struct.pack(endian + "Q", ifdrop))
    body += opt(endian, 7, struct.pack(endian + "Q", osdrop)) + opt(endian, 0, b"")
    return block(endian, 5, body)


def snoop(records):
    out = b"snoop\0\0\0" + struct.pack(">II", 2, 4)
    for included, drops in records:
        length = 24 + ((included + 3) & ~3)
        out += struct.pack(">6I", included, included, length, drops, 1_700_000_000, 0) + b"\x11" * (length - 24)
    return out


def bases():
    le, be = "<", ">"
    yield "pcap_le", pcap(le, 0xA1B2C3D4, 65535, 1, [20, 33, 7])
    yield "pcap_be", pcap(be, 0xA1B2C3D4, 96, 0x00AB0071, [12, 16])
    yield "pcap_ns", pcap(le, 0xA1B23C4D, 262144, 101, [9, 0, 40])
    yield "pcap_zero_bodies", pcap(le, 0xA1B2C3D4, 65535, 1, [30, 30], body=b"\0")
    yield "pcapng_le", shb(le) + idb(le, 1, 262144, "eth0".encode()) + idb(le, 113, 96, "anyé".encode()) + \
        epb(le, 0, 21) + epb(le, 1, 40) + spb(le, 5) + isb(le, 0, 3, 4) + isb(le, 0, 5, 1) + isb(le, 1, 7, 0)
    yield "pcapng_two_sections", shb(le) + idb(le, 1, 100) + epb(le, 0, 10) + shb(be) + idb(be, 105, 200, b"wl\xff") + \
        epb(be, 0, 10) + epb(be, 0, 12) + isb(be, 0, 2**63 + 5, 9)
    yield "pcapng_unknown_block", shb(le) + idb(le, 1, 100) + block(le, 0x0BAD, b"\1\2\3\4") + epb(le, 0, 8)
    yield "snoop", snoop([(10, 5), (11, 2), (0, 9)])


def cases():
    for name, data in bases():
        yield name, data
        for n in range(len(data)):
            yield f"{name}_cut{n}", data[:n]
        for z in (1, 4, 11, 12, 16, 24, 40, 100):
            yield f"{name}_zero{z}", data + b"\0" * z
        for k in range(40):
            i = rng.randrange(len(data))
            b = bytearray(data)
            b[i] ^= 1 << rng.randrange(8)
            yield f"{name}_flip{k}_{i}", bytes(b)
    yield "empty", b""
    yield "garbage", b"\1\2\3\4\5\6\7\x08"
    yield "all_zero", b"\0" * 64


def main() -> None:
    out = []
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "capture"
        for name, data in cases():
            path.write_bytes(data)
            case = dict(name=name, hex=data.hex())
            try:
                case["result"] = json.dumps(metadata(path))
            except Exception as exc:  # noqa: BLE001 - the vector records the exception type
                case["error"] = type(exc).__name__
            out.append(case)
    OUT.mkdir(parents=True, exist_ok=True)
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", mtime=0) as gz:
        gz.write((json.dumps(out, indent=0) + "\n").encode())
    (OUT / "metadata_vectors.json.gz").write_bytes(buf.getvalue())
    print(len(out), "cases,", sum("error" in c for c in out), "errors")


if __name__ == "__main__":
    main()
