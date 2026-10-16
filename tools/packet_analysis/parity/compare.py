#!/usr/bin/env python3
"""Compare a candidate (Java) parity result against the frozen PacketBreaker reference.

Plain python3; does not import PacketBreaker.

Reference: a committed golden case file (``ui2/packet-analysis/src/test/resources/parity/cases/<case>.json``,
whose masked key sections are read from the ``<case>.key-sections.json.gz`` file next to it) or a canonical
report from the corpus archive (``run1/<case>/canonical/report.json``).

Candidate, one of:
* ``--candidate-case FILE``: a case result with the golden case shape (any of ``inputs``, ``l1``, ``l2``,
  ``l3``); every level present in the candidate is compared;
* ``--candidate-report FILE``: a report JSON. Canonical already, or raw with ``--capture-map FILE`` (JSON
  object capture id -> capture file name), in which case it is canonicalized here by ``canonical.py``.

Output: per section MATCH / MISMATCH / MISSING (section hashes first), then the first ``--limit`` differing
JSON paths for every mismatching section whose reference content is available. Exit 0 only on full match.
"""

from __future__ import annotations

import argparse
import gzip
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import canonical as canon  # noqa: E402


def load_json(path: Path):
    data = path.read_bytes()
    if path.suffix == ".gz":
        data = gzip.decompress(data)
    return json.loads(data)


def differences(expected, actual, path="$", out=None, limit=20):
    """First differing JSON paths (PacketBreaker tests/test_golden.py::_differences, extended limit)."""
    out = [] if out is None else out
    if len(out) >= limit:
        return out
    if type(expected) is not type(actual):
        out.append(f"{path}: {str(expected)[:80]!r} != {str(actual)[:80]!r}")
    elif isinstance(expected, dict):
        for key in sorted(set(expected) | set(actual)):
            if len(out) >= limit:
                break
            if key not in expected or key not in actual:
                out.append(f"{path}.{key}: {'missing' if key not in expected else 'present'} in reference")
            else:
                differences(expected[key], actual[key], f"{path}.{key}", out, limit)
    elif isinstance(expected, list):
        if len(expected) != len(actual):
            out.append(f"{path}: {len(expected)} items in reference, {len(actual)} in candidate")
        for i, (e, a) in enumerate(zip(expected, actual)):
            if len(out) >= limit:
                break
            differences(e, a, f"{path}[{i}]", out, limit)
    elif expected != actual:
        out.append(f"{path}: {str(expected)[:80]!r} != {str(actual)[:80]!r}")
    return out


def compare_hash_maps(name: str, expected: dict, actual: dict) -> list[tuple[str, str, str]]:
    """[(path, status, detail)] for two {key: hash-or-record} maps."""
    rows = []
    for key in sorted(set(expected) | set(actual)):
        if key not in actual:
            rows.append((f"{name}.{key}", "MISSING", "absent in candidate"))
        elif key not in expected:
            rows.append((f"{name}.{key}", "MISMATCH", "not in reference"))
        elif expected[key] == actual[key]:
            rows.append((f"{name}.{key}", "MATCH", ""))
        else:
            rows.append((f"{name}.{key}", "MISMATCH", json.dumps(actual[key], sort_keys=True)[:120]))
    return rows


def compare_report(reference: dict, candidate_canonical: dict, limit: int):
    """reference: golden case l3 dict or a full canonical report."""
    candidate_sections = canon.section_hashes(candidate_canonical)
    if "report_sections" in reference:
        expected_sections = reference["report_sections"]
        contents = reference.get("key_sections", {})
        masked = True
    else:
        expected_sections = canon.section_hashes(reference)
        contents = reference
        masked = False
    rows = compare_hash_maps("report", expected_sections, candidate_sections)
    details = {}
    for path, status, _ in rows:
        section = path.split(".", 1)[1]
        if status == "MATCH" or section not in contents or section not in candidate_canonical:
            continue
        actual = candidate_canonical[section]
        if masked:
            actual = canon.mask_for_commit(actual)
        details[section] = differences(contents[section], actual, f"$.{section}", limit=limit)
    return rows, details


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--reference", type=Path, required=True)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--candidate-case", type=Path)
    group.add_argument("--candidate-report", type=Path)
    parser.add_argument("--capture-map", type=Path, help="raw report: JSON {capture id: file name}")
    parser.add_argument("--limit", type=int, default=20, help="differing paths shown per section")
    args = parser.parse_args(argv)

    reference = load_json(args.reference)
    sidecar = reference.get("l3", {}).get("key_sections_file") if isinstance(reference, dict) else None
    if sidecar:  # committed golden case: masked key sections live next to the case file
        reference["l3"]["key_sections"] = load_json(args.reference.parent / sidecar)
    rows, details = [], {}
    if args.candidate_report:
        report = load_json(args.candidate_report)
        if args.capture_map:
            report = canon.canonicalize(report, load_json(args.capture_map))
        ref_l3 = reference.get("l3", reference)
        rows, details = compare_report(ref_l3, report, args.limit)
    else:
        candidate = load_json(args.candidate_case)
        for level in ("inputs", "l1", "l2"):
            if level in candidate:
                rows += compare_hash_maps(level, reference.get(level, {}), candidate[level])
        if "l3" in candidate:
            ref_l3, cand_l3 = reference.get("l3", {}), candidate["l3"]
            for key in ("golden_payload_sha256", "report_sha256", "findings_export_sha256"):
                if key in cand_l3:
                    rows += compare_hash_maps("l3", {key: ref_l3.get(key)}, {key: cand_l3[key]})
            for key in ("report_sections", "findings_export_sections"):
                if key in cand_l3:
                    rows += compare_hash_maps(f"l3.{key}", ref_l3.get(key, {}), cand_l3[key])
            if "key_sections" in cand_l3:
                for section, content in ref_l3.get("key_sections", {}).items():
                    actual = canon.mask_for_commit(cand_l3["key_sections"].get(section))
                    if actual != content:
                        details[section] = differences(content, actual, f"$.{section}", limit=args.limit)

    failed = [r for r in rows if r[1] != "MATCH"]
    for path, status, detail in rows:
        if status != "MATCH":
            print(f"{status:9} {path}  {detail}".rstrip())
    print(f"{len(rows) - len(failed)}/{len(rows)} sections MATCH")
    for section, paths in details.items():
        print(f"\nFirst differing paths in {section}:")
        for line in paths:
            print("  " + line)
    return 0 if not failed and not any(details.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
