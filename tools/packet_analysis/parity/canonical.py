"""Canonical forms for the Packet Analysis parity harness (plain Python, no PacketBreaker import).

Level 3 (report / FindingsExport) canonicalization is a verbatim port of the frozen PacketBreaker golden
test rules (``tests/test_golden.py`` at the frozen commit recorded in the corpus manifest):

1. ``_strip_volatile``: drop ``generated_at`` / ``exported_at`` keys anywhere; replace every capture id
   (exact string or substring, in keys and values) with ``<capture:FILE_NAME>``.
2. ``_canonical``: dict keys have 32-hex ids masked as ``<id>``; every list is sorted by the
   ``json.dumps(item, sort_keys=True, ensure_ascii=False)`` text of its canonical items; floats are
   rounded to 9 significant digits (``float(f"{x:.9g}")``); strings that look like JSON objects/arrays
   are parsed and canonicalized; other strings get backslashes turned into ``/``, 32-hex ids masked as
   ``<id>`` and runner-local time renderings masked as ``<local time>``.
3. Serialization: ``json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False)`` as UTF-8.

Levels 1 and 2 (DuckDB tables) are hashed by SQL that runs in DuckDB itself, so the Java side can run the
identical statements through ``org.duckdb:duckdb_jdbc`` of the same DuckDB version (see ``L1_SQL`` and
``l2_hash_sql``).

``mask_for_commit`` is only applied to the canonical key sections that are committed to the repository:
literal IPv4 addresses that the repository privacy gate classifies as private/link-local are replaced by
``<ipv4:SHA256_12>`` (first 12 hex digits of the SHA-256 of the literal). Hashes are always computed on the
unmasked canonical form.
"""

from __future__ import annotations

import hashlib
import ipaddress
import json
import re

VOLATILE_KEYS = {"generated_at", "exported_at"}
HEX_ID = re.compile(r"(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f])")
# Local-time renderings depend on the runner's time zone; the UTC renderings are kept.
LOCAL_TIME = re.compile(r"(?:\d{4}-\d\d-\d\d )?\d\d:\d\d:\d\d(?:\.\d+)?[+-]\d\d:\d\d(?: local)?")

# Report sections committed in full (masked form) per golden case.
KEY_SECTIONS = ("verdict", "findings", "quality", "segments", "finding_count", "flow_count")


def capture_token_map(id_to_name: dict[str, str]) -> dict[str, str]:
    """Capture id -> ``<capture:NAME>`` token, the PacketBreaker golden form."""
    return {cid: f"<capture:{name}>" for cid, name in id_to_name.items()}


def _normalize_str(val: str, cid_map: dict[str, str]) -> str:
    if val in cid_map:
        return cid_map[val]
    for cid, token in cid_map.items():
        if cid in val:
            val = val.replace(cid, token)
    return val


def strip_volatile(obj, cid_map: dict[str, str]):
    if isinstance(obj, dict):
        out = {}
        for k, v in obj.items():
            if k in VOLATILE_KEYS:
                continue
            nk = _normalize_str(k, cid_map) if isinstance(k, str) else k
            out[nk] = strip_volatile(v, cid_map)
        return out
    if isinstance(obj, list):
        return [strip_volatile(x, cid_map) for x in obj]
    if isinstance(obj, str):
        return _normalize_str(obj, cid_map)
    return obj


def canonical(obj):
    """Platform-independent form: the decision content is kept, run-to-run noise is removed."""
    if isinstance(obj, dict):
        return {HEX_ID.sub("<id>", k): canonical(v) for k, v in obj.items()}
    if isinstance(obj, list):
        items = [canonical(v) for v in obj]
        return sorted(items, key=lambda v: json.dumps(v, sort_keys=True, ensure_ascii=False))
    if isinstance(obj, float):
        return float(f"{obj:.9g}")
    if isinstance(obj, str):
        stripped = obj.strip()
        if stripped[:1] in "[{" and stripped[-1:] in "]}":
            try:
                return canonical(json.loads(stripped))
            except ValueError:
                pass
        return LOCAL_TIME.sub("<local time>", HEX_ID.sub("<id>", obj.replace("\\", "/")))
    return obj


def dumps(obj) -> bytes:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def canonicalize(payload, id_to_name: dict[str, str]):
    """Raw payload (report, export, ...) -> canonical object."""
    return canonical(strip_volatile(payload, capture_token_map(id_to_name)))


def serialize_golden(payload, id_to_name: dict[str, str]) -> bytes:
    """Byte-identical to PacketBreaker's ``tests/test_golden.py::serialize_golden``."""
    return dumps(canonicalize(payload, id_to_name))


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def section_hashes(canonical_obj: dict) -> dict[str, str]:
    """SHA-256 of the canonical serialization of every top-level section."""
    return {key: sha256(dumps(value)) for key, value in sorted(canonical_obj.items())}


# --- commit masking (privacy gate) ---------------------------------------------------------------

_IPV4_RE = re.compile(r"(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])")
_DOCUMENTATION = tuple(
    ipaddress.ip_network(n) for n in ("192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24")
)


def _gate_flags_ipv4(value: str) -> bool:
    """Same classification as tools/privacy/repository_privacy.py for a non-test-fixture path."""
    try:
        ip = ipaddress.ip_address(value)
    except ValueError:
        return False
    if ip.is_loopback or ip.is_unspecified or any(ip in n for n in _DOCUMENTATION):
        return False
    return ip.is_private or ip.is_link_local


def _mask_text(text: str) -> str:
    def repl(match):
        literal = match.group(0)
        if not _gate_flags_ipv4(literal):
            return literal
        return "<ipv4:" + hashlib.sha256(literal.encode("ascii")).hexdigest()[:12] + ">"

    return _IPV4_RE.sub(repl, text)


def mask_for_commit(obj):
    if isinstance(obj, dict):
        return {_mask_text(k): mask_for_commit(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [mask_for_commit(v) for v in obj]
    if isinstance(obj, str):
        return _mask_text(obj)
    return obj


# --- Level 1 / Level 2 SQL (DuckDB; identical statements are to be used from Java) ----------------

#: Level 1: one row per capture file name: rows and SHA-256 over ``to_json(row)`` lines ordered by frame,
#: with ``capture_id`` replaced by the capture file name.
L1_SQL = """WITH r AS (
  SELECT p.* REPLACE (c.name AS capture_id) FROM packets p JOIN captures c ON c.id = p.capture_id
)
SELECT capture_id AS capture, count(*) AS row_count,
  sha256(string_agg(to_json(r)::VARCHAR, chr(10) ORDER BY frame)) AS sha256
FROM r GROUP BY capture_id ORDER BY capture_id"""


def l2_select_sql(source: str, varchar_columns: list[str], id_to_name: dict[str, str]) -> str:
    """SELECT used to dump an intermediate table: capture ids in every VARCHAR column -> ``<capture:NAME>``."""

    def quote_ident(name: str) -> str:
        return '"' + name.replace('"', '""') + '"'

    def quote_lit(value: str) -> str:
        return "'" + value.replace("'", "''") + "'"

    if not varchar_columns or not id_to_name:
        return f"SELECT * FROM {source}"
    replaced = []
    for column in varchar_columns:
        expr = quote_ident(column)
        for cid, name in sorted(id_to_name.items(), key=lambda kv: kv[1]):
            expr = f"replace({expr}, {quote_lit(cid)}, {quote_lit('<capture:' + name + '>')})"
        replaced.append(f"{expr} AS {quote_ident(column)}")
    return f"SELECT * REPLACE ({', '.join(replaced)}) FROM {source}"


def l2_hash_sql(parquet_path: str) -> str:
    """Level 2: row count and SHA-256 over the sorted ``to_json(row)`` lines of a dumped table."""
    lit = "'" + parquet_path.replace("'", "''") + "'"
    return (
        "SELECT count(*) AS row_count, "
        "sha256(coalesce(string_agg(j, chr(10) ORDER BY j), '')) AS sha256 "
        f"FROM (SELECT to_json(t)::VARCHAR AS j FROM read_parquet({lit}) t)"
    )
