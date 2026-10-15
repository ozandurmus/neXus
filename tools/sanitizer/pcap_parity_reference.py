#!/usr/bin/env python3
"""Generate the pcap parity goldens for ui2/sanitizer from the frozen PacketBreaker reference.

Run with the frozen PacketBreaker venv (reference commit 5b608e6; read-only):

    PB=~/Codo/PacketBreaker
    PYTHONPATH=$PB/src:$PB/tests $PB/.venv/bin/python tools/sanitizer/pcap_parity_reference.py \
        --tshark /Applications/Wireshark.app/Contents/MacOS/tshark \
        --out ui2/sanitizer/src/test/resources/pcap-parity/manifest.json \
        [--scenario-dir /tmp/pb-scenarios]

Every input is synthetic (RFC 5737 / RFC 3849 documentation ranges) and tiny; captures are stored base64-encoded
inside the JSON manifest (the repository refuses .pcap files), with the SHA-256 of the reference output, or the
word "error" when the reference refuses the capture. ``--scenario-dir`` additionally writes the larger
PacketBreaker scenario captures and their hashes outside the repository for the opt-in Java parity run
(``PCAP_PARITY_SCENARIO_DIR``).
"""

import argparse
import base64
import hashlib
import json
import socket
import struct
import tempfile
from pathlib import Path

from packetbreaker.anonymize import anonymize
from packetbreaker.realistic import datagram
from packetbreaker.synthetic import checksum, tcp_packet, write_pcap

from capture_realism_fixtures import fragments, tunnel
from test_anonymize import KEY, planted

SECRET = b"PLANTED-private-host-secret"
MODES = [("zero", True), ("headers-only", True), ("zero", False), ("headers-only", False)]


def pcap_with_linktype(path, linktype, frames, magic=0xA1B2C3D4):
    with Path(path).open("wb") as f:
        f.write(struct.pack("<IHHIIII", magic, 2, 4, 0, 0, 65535, linktype))
        for i, frame in enumerate(frames):
            f.write(struct.pack("<IIII", 1700000000 + i, i * 1000, len(frame), len(frame)) + frame)


def arp(op, sha, spa, tha, tpa):
    body = struct.pack("!HHBBH6s4s6s4s", 1, 0x0800, 6, 4, op, sha, socket.inet_aton(spa), tha, socket.inet_aton(tpa))
    dst = b"\xff" * 6 if op == 1 else tha
    return dst + sha + b"\x08\x06" + body + b"\0" * 18


def vlan(frame, vid=42):
    return frame[:12] + struct.pack("!HH", 0x8100, vid) + frame[12:]


def icmp(src, dst, kind, payload, v6=False):
    message = bytes([kind, 0, 0, 0]) + struct.pack("!HH", 7, 1) + payload
    if not v6:
        message = message[:2] + struct.pack("!H", checksum(message)) + message[4:]
    return datagram(src, dst, 58 if v6 else 1, message, 99)


def udp(src, dst, sport, dport, payload):
    return datagram(src, dst, 17, struct.pack("!HHHH", sport, dport, len(payload) + 8, 0) + payload, 11)


def dns_query(name):
    labels = b"".join(bytes([len(p)]) + p.encode() for p in name.split(".")) + b"\0"
    return struct.pack("!HHHHHH", 0x1234, 0x0100, 1, 0, 0, 0) + labels + struct.pack("!HH", 1, 1)


def ipv6_with_extensions(src, dst):
    inner = tcp_packet(src, dst, 40000, 22, 1, 2, 0x18, SECRET, 0)
    ip, tcp = inner[14:54], inner[54:]
    hop = struct.pack("!BB6s", 60, 0, b"\x01\x04\0\0\0\0")
    dest = struct.pack("!BB6s", 6, 0, b"\x01\x04\0\0\0\0")
    payload = hop + dest + tcp
    header = bytearray(ip)
    header[4:6] = struct.pack("!H", len(payload))
    header[6] = 0
    return inner[:14] + bytes(header) + payload


def inputs(tmp):
    """(name, path) of every small synthetic input."""
    out = []
    for pcapng, endian, ipv6, label in [
        (False, "<", False, "planted-pcap-v4"),
        (False, "<", True, "planted-pcap-v6"),
        (True, "<", False, "planted-pcapng-le-v4"),
        (True, ">", True, "planted-pcapng-be-v6"),
    ]:
        path = tmp / f"{label}.cap"
        planted(path, pcapng, ipv6, endian)
        out.append((label, path))
    original = tcp_packet("192.0.2.123", "198.51.100.231", 12345, 443, 1234, 5555, 24, SECRET, 0)
    quote = datagram("192.0.2.10", "192.0.2.20", 1, b"\x03\x01\0\0" + b"\0" * 4 + original[14:42], 7)
    for label, frames in [
        ("short-icmp-quote", [quote]),
        ("tcp-checksum", [original]),
        ("udp-erased", [udp("192.0.2.1", "198.51.100.2", 42000, 42001, SECRET)]),
        ("dns-query", [udp("192.0.2.1", "198.51.100.53", 53000, 53, dns_query("www.example.com"))]),
        ("arp", [arp(1, bytes.fromhex("001122334455"), "192.0.2.1", b"\0" * 6, "192.0.2.2"),
                 arp(2, bytes.fromhex("66778899aabb"), "192.0.2.2", bytes.fromhex("001122334455"), "192.0.2.1")]),
        ("vlan", [vlan(original)]),
        ("icmp-echo", [icmp("192.0.2.1", "198.51.100.9", 8, SECRET),
                       icmp("2001:db8:1::1", "2001:db8:2::2", 128, SECRET, v6=True)]),
        ("ipv6-extensions", [ipv6_with_extensions("2001:db8:1::5", "2001:db8:2::6")]),
        ("tunnels-v4", [tunnel(original, k) for k in ("erspan2", "erspan3", "gre", "vxlan", "geneve")]),
        ("fragments-v4", fragments(tcp_packet("192.0.2.1", "198.51.100.2", 41001, 80, 1001, 2001, 24, b"x" * 180, 777))),
        ("not-a-packet", [b"\0" * 60 + SECRET]),
    ]:
        path = tmp / f"{label}.pcap"
        write_pcap(path, [(1700000000 + i * 0.001, f) for i, f in enumerate(frames)])
        out.append((label, path))
    # Other link types: Linux cooked (113), BSD loopback (0), raw IP (101), and a nanosecond-resolution header.
    tcp4 = original[14:]
    sll = struct.pack("!HHH8sH", 0, 1, 6, bytes.fromhex("0011223344550000"), 0x0800) + tcp4
    for label, linktype, frames, magic in [
        ("linktype-sll", 113, [sll], 0xA1B2C3D4),
        ("linktype-null", 0, [struct.pack("<I", 2) + tcp4], 0xA1B2C3D4),
        ("linktype-raw", 101, [tcp4], 0xA1B2C3D4),
        ("nanosecond", 1, [original], 0xA1B23C4D),
    ]:
        path = tmp / f"{label}.pcap"
        pcap_with_linktype(path, linktype, frames, magic)
        out.append((label, path))
    damaged = tmp / "damaged-tail.pcap"
    write_pcap(damaged, [(1700000000, original)])
    with damaged.open("ab") as f:
        f.write(b"partial")
    out.append(("damaged-tail", damaged))
    return out


def run(path, mode, map_ips, tshark, tmp):
    target = tmp / f"out-{hashlib.sha256(f'{path}{mode}{map_ips}'.encode()).hexdigest()[:12]}.pcap"
    try:
        anonymize(path, target, mode=mode, key=KEY if map_ips else None, map_ips=map_ips, tshark=tshark)
    except ValueError:
        return "error"
    return hashlib.sha256(target.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tshark", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--scenario-dir")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory() as directory:
        tmp = Path(directory)
        manifest = dict(
            reference="PacketBreaker 5b608e6 src/packetbreaker/anonymize.py",
            key_hex=KEY.hex(),
            note="Synthetic inputs only; outputs regenerated by tools/sanitizer/pcap_parity_reference.py.",
            fixtures=[],
        )
        for name, path in inputs(tmp):
            data = path.read_bytes()
            cases = [dict(mode=m, map_ips=i, expect=run(path, m, i, args.tshark, tmp)) for m, i in MODES]
            manifest["fixtures"].append(dict(name=name, input_base64=base64.b64encode(data).decode(), cases=cases))
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(json.dumps(manifest, indent=1) + "\n", encoding="utf-8")
        if args.scenario_dir:
            scenarios(Path(args.scenario_dir), args.tshark, tmp)


def scenarios(target, tshark, tmp):
    """The PacketBreaker scenario captures test_anonymize.py's equivalence test anonymizes (kept outside Git)."""
    from packetbreaker.synthetic import generate

    target.mkdir(parents=True, exist_ok=True)
    entries = []
    for scenario, ip_id, ipv6 in [("recovered_loss", "zero", False), ("recovered_loss", "constant", True),
                                  ("pilot", "zero", False)]:
        directory = tmp / f"{scenario}-{ip_id}-{ipv6}"
        truth, _ = generate(directory, scenario=scenario, ip_id=ip_id, ipv6=ipv6)
        for file in truth["files"]:
            name = f"{scenario}-{ip_id}-{'v6' if ipv6 else 'v4'}-{Path(file).name}"
            (target / name).write_bytes(Path(file).read_bytes())
            cases = [dict(mode=m, map_ips=i, expect=run(Path(file), m, i, tshark, tmp)) for m, i in MODES]
            entries.append(dict(file=name, cases=cases))
    (target / "manifest.json").write_text(json.dumps(dict(key_hex=KEY.hex(), files=entries), indent=1) + "\n")


if __name__ == "__main__":
    main()
