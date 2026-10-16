#!/usr/bin/env python3
"""Reference vectors for the Java packet-row parser (synthetic tshark records, no capture needed).

Run with the frozen PacketBreaker interpreter, from the repository root:

    ~/Codo/PacketBreaker/.venv/bin/python -I tools/packet_analysis/parity/l1_parser_vectors.py

Writes ui2/packet-analysis/src/test/resources/packetanalysis/ingest/parser_vectors.json: for every case the tshark
field values, the payload prefix size, the vendor flag, and either the CSV line the reference ingest writes for the
parsed row (csv.writer, the text DuckDB loads) or the exception name it raises. Deterministic output.
"""

from __future__ import annotations

import csv
import io
import json
from pathlib import Path

from packetbreaker.ingest import BASE_FIELDS, parse_packet
from packetbreaker.vendors import CHECKPOINT_FIELDS, F5_FIELDS, HTTP_FIELDS

OUT = Path(__file__).resolve().parents[3] / "ui2/packet-analysis/src/test/resources/packetanalysis/ingest"


def record(fields, **values):
    return [values.get(f.replace(".", "_"), "") for f in fields]


TCP = dict(
    frame_number="7", frame_time_epoch="1700000000.123456789", frame_interface_id="0", ip_src="198.51.100.1",
    ip_dst="192.0.2.9", ip_proto="6", ip_id="0x1a2b", tcp_srcport="50000", tcp_dstport="443", tcp_seq_raw="4294967295",
    tcp_ack_raw="17", tcp_flags="0x0018", tcp_len="12", ip_len="64", frame_len="78", frame_cap_len="78",
    tcp_payload="00:11:22:33:44:55:66:77:88:99:aa:bb", tcp_stream="3", ip_ttl="63", ip_dsfield_dscp="46",
    tcp_options_mss_val="1460", tcp_window_size_value="65535", tcp_analysis_retransmission="1",
    tcp_analysis_ack_rtt="0.000123000", ip_flags_mf="False", ip_frag_offset="0", frame_protocols="eth:ethertype:ip:tcp",
    frame_md5_hash="0123456789abcdef0123456789abcdef", ip_flags_df="True", tcp_options="020405b4",
    tcp_options_sack_perm="1", tcp_options_timestamp_tsval="100", tcp_options_timestamp_tsecr="0",
    tcp_analysis_rto="0.200000000", tcp_analysis_rto_frame="5", tcp_analysis_bytes_in_flight="1200",
    tcp_options_sack_le="1,5", tcp_options_sack_re="2,9", tls_record_content_type="23", http_request_method="GET",
)
CASES = [
    ("tcp", BASE_FIELDS, 64, TCP),
    ("tcp_prefix8_truncated", BASE_FIELDS, 8, {**TCP, "frame_cap_len": "60"}),
    ("udp_zero_payload", BASE_FIELDS, 64, dict(
        frame_number="1", frame_time_epoch="1700000001.5", frame_interface_id="1", ip_src="198.51.100.2",
        ip_dst="198.51.100.3", ip_proto="17", ip_id="7", udp_srcport="53", udp_dstport="5353", udp_length="12",
        udp_payload="00000000", frame_len="46", frame_cap_len="46", frame_protocols="eth:ethertype:ip:udp:dns",
        dns_id="0x1234", dns_flags_response="True", dns_qry_name="example.test", dns_qry_type="1",
        dns_qry_class="0x0001", dns_count_queries="1", dns_flags_rcode="3", ip_flags_df="False")),
    ("icmp_quoted_tcp", BASE_FIELDS, 64, dict(
        frame_number="2", frame_time_epoch="1700000002", ip_src="192.0.2.1,198.51.100.1", ip_dst="198.51.100.1,192.0.2.9",
        ip_proto="1,6", ip_id="0x0000,0x1a2b", icmp_type="3", icmp_code="4", icmp_mtu="1400", icmp_ident="",
        tcp_srcport="50000", tcp_dstport="443", tcp_seq_raw="99", tcp_flags="0x0002", ip_len="56,40",
        frame_len="70", frame_cap_len="70", frame_protocols="eth:ethertype:ip:icmp:ip:tcp", tcp_stream="0")),
    ("ipv6_udp_tunnel", BASE_FIELDS, 64, dict(
        frame_number="3", frame_time_epoch="1700000003.25", ipv6_src="2001:db8::1", ipv6_dst="2001:db8::2",
        ipv6_nxt="17", ipv6_flow="0x000abcde", udp_srcport="4789", udp_dstport="4789", udp_length="9100",
        udp_payload="ff" * 20, ipv6_plen="9100", ipv6_hlim="64", ipv6_tclass_dscp="10", frame_len="9154",
        frame_cap_len="9154", frame_protocols="eth:ethertype:ipv6:udp:vxlan:eth:ethertype:ip:tcp",
        ipv6_fraghdr_more="0")),
    ("icmpv6_echo", BASE_FIELDS, 64, dict(
        frame_number="4", frame_time_epoch="1700000004", ipv6_src="2001:db8::1", ipv6_dst="2001:db8::2",
        ipv6_nxt="58", icmpv6_type="128", icmpv6_code="0", ipv6_plen="16", frame_len="70", frame_cap_len="70",
        frame_protocols="eth:ethertype:ipv6:icmpv6")),
    ("fragment_other", BASE_FIELDS, 64, dict(
        frame_number="5", frame_time_epoch="1700000005", ip_src="198.51.100.1", ip_dst="198.51.100.2", ip_proto="47",
        ip_flags_mf="True", ip_frag_offset="0", ip_len="1500", frame_len="1514", frame_cap_len="1514",
        frame_protocols="eth:ethertype:ip:gre:sip")),
    ("unicode_and_quotes", BASE_FIELDS, 64, dict(
        frame_number="6", frame_time_epoch="1700000006", ip_src="198.51.100.1", ip_dst="198.51.100.2", ip_proto="17",
        udp_srcport="53", udp_dstport="53", udp_length="20", udp_payload="0102", frame_len="60", frame_cap_len="60",
        frame_protocols="eth:ip:udp:dns", dns_qry_name='a"b,c\\dé中\U0001f600\x01', http_request_method="P,OST")),
    ("checkpoint_vendor", BASE_FIELDS + CHECKPOINT_FIELDS, 64, {**TCP, "fw1_direction": "o", "fw1_chain": "e",
        "fw1_interface": "eth1", "fw1_uuid": ""}),
    ("f5_vendor", BASE_FIELDS + F5_FIELDS + HTTP_FIELDS, 64, {**TCP, "f5ethtrailer_flowid": "0x1", "f5ethtrailer_peerid": "0x2",
        "f5ethtrailer_tmm": "0", "f5ethtrailer_slot": "1", "http_request_uri": "/x"}),
    ("bad_number", BASE_FIELDS, 64, {**TCP, "frame_len": "12a"}),
    ("bad_hex", BASE_FIELDS, 64, {**TCP, "tcp_payload": "0g"}),
    ("bad_ts", BASE_FIELDS, 64, {**TCP, "frame_time_epoch": ""}),
]


def main() -> None:
    out = []
    for name, fields, prefix, values in CASES:
        row_values = record(fields, **values)
        case = dict(name=name, fields=len(BASE_FIELDS) if fields is BASE_FIELDS else fields, values=row_values,
                    prefix=prefix)
        if fields is not BASE_FIELDS:
            case["fields"] = list(fields)
        try:
            row = parse_packet(row_values, "cid0", prefix, fields)
            buf = io.StringIO()
            csv.writer(buf).writerow(row)
            case["csv"] = buf.getvalue()
        except Exception as exc:  # noqa: BLE001 - the vector records the exception type
            case["error"] = type(exc).__name__
        out.append(case)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "parser_vectors.json").write_text(json.dumps(out, indent=1, ensure_ascii=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
