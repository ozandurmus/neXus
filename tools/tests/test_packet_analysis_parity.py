"""Packet Analysis parity harness (phase 0): canonicalization, compare and committed-golden consistency."""

import gzip
import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PARITY = ROOT / "tools" / "packet_analysis" / "parity"
GOLDENS = ROOT / "ui2" / "packet-analysis" / "src" / "test" / "resources" / "parity"
FIXTURE = ROOT / "tools" / "tests" / "fixtures" / "packet_analysis_parity" / "canonical_case.json"
sys.path.insert(0, str(PARITY))

import canonical as canon  # noqa: E402
import compare  # noqa: E402


def _fixture():
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


def test_port_reproduces_packetbreaker_golden_serializer_bytes():
    fixture = _fixture()
    actual = canon.serialize_golden(fixture["payload"], fixture["capture_names"])
    assert actual.decode("utf-8") == fixture["expected"]
    assert hashlib.sha256(actual).hexdigest() == fixture["expected_sha256"]


def test_canonical_rules():
    cid = "0123456789abcdef0123456789abcdef"
    raw = {
        "generated_at": "x",
        "a": [{"v": 2.0000000001}, {"v": 1}],
        "b": f"seen at {cid} and 11:22:33.5+02:00 local",
        "c": '{"k": [2, 1]}',
        cid: {"exported_at": "y"},
    }
    result = canon.canonicalize(raw, {cid: "p.pcap"})
    assert result == {
        "a": [{"v": 1}, {"v": 2.0}],
        "b": "seen at <capture:p.pcap> and <local time>",
        "c": {"k": [1, 2]},
        "<capture:p.pcap>": {},
    }
    # Unknown 32-hex ids are masked, not mapped.
    assert canon.canonicalize({"id": "f" * 32}, {}) == {"id": "<id>"}
    # Nine significant digits: epoch seconds keep 10-second resolution only (L2 keeps full precision).
    assert canon.canonical(1700000012.25) == 1700000010.0


def test_section_hashes_are_canonical_serializations():
    obj = {"b": [1, 2], "a": "x"}
    hashes = canon.section_hashes(obj)
    assert list(hashes) == ["a", "b"]
    assert hashes["b"] == hashlib.sha256(b"[1,2]").hexdigest()


def test_commit_mask_hashes_only_gate_flagged_ipv4_literals():
    masked = canon.mask_for_commit(
        {"10.0.0.10->203.0.113.20": ["host 192.168.1.5", "doc 198.51.100.7", "loop 127.0.0.1", "v6 2001:db8::1"]}
    )
    token = "<ipv4:" + hashlib.sha256(b"10.0.0.10").hexdigest()[:12] + ">"
    (key, values), = masked.items()
    assert key == token + "->203.0.113.20"
    assert values[0].startswith("host <ipv4:") and "192.168" not in values[0]
    assert values[1:] == ["doc 198.51.100.7", "loop 127.0.0.1", "v6 2001:db8::1"]


def test_l2_select_replaces_capture_ids_in_varchar_columns():
    sql = canon.l2_select_sql('"obs"', ["capture_id", "note"], {"a" * 32: "x.pcap"})
    assert sql.startswith('SELECT * REPLACE (replace("capture_id", ')
    assert "'<capture:x.pcap>'" in sql and sql.endswith('FROM "obs"')
    assert canon.l2_select_sql('"t"', [], {"a" * 32: "x"}) == 'SELECT * FROM "t"'


def test_differences_lists_first_paths():
    paths = compare.differences({"a": [1, 2], "b": {"c": 1}}, {"a": [1, 3], "b": {"d": 1}})
    assert paths == [
        "$.a[1]: '2' != '3'",
        "$.b.c: present in reference",
        "$.b.d: missing in reference",
    ]


def _write(tmp_path, name, obj):
    path = tmp_path / name
    path.write_text(json.dumps(obj), encoding="utf-8")
    return path


def test_compare_report_against_case_file(tmp_path, capsys):
    report = {"verdict": "Loss observed", "findings": [{"src": "10.0.0.1"}], "flow_count": 2, "other": [1]}
    reference_case = {
        "l3": {
            "report_sections": canon.section_hashes(report),
            "key_sections": canon.mask_for_commit({k: report[k] for k in ("verdict", "findings", "flow_count")}),
        }
    }
    ref = _write(tmp_path, "case.json", reference_case)
    same = _write(tmp_path, "same.json", report)
    assert compare.main(["--reference", str(ref), "--candidate-report", str(same)]) == 0

    changed = dict(report, findings=[{"src": "10.0.0.2"}], other=[2])
    cand = _write(tmp_path, "changed.json", changed)
    assert compare.main(["--reference", str(ref), "--candidate-report", str(cand)]) == 1
    out = capsys.readouterr().out
    assert "MISMATCH  report.findings" in out and "MISMATCH  report.other" in out
    assert "$.findings[0].src" in out and "10.0.0." not in out  # masked like the committed golden


def test_compare_raw_report_with_capture_map(tmp_path):
    cid = "0123456789abcdef0123456789abcdef"
    canonical_report = canon.canonicalize({"clocks": {cid: 0.5}, "verdict": "x"}, {cid: "a.pcap"})
    ref = _write(tmp_path, "ref.json", canonical_report)
    raw = _write(tmp_path, "raw.json", {"clocks": {cid: 0.5}, "verdict": "x", "generated_at": "now"})
    cmap = _write(tmp_path, "map.json", {cid: "a.pcap"})
    assert compare.main(["--reference", str(ref), "--candidate-report", str(raw), "--capture-map", str(cmap)]) == 0
    assert compare.main(["--reference", str(ref), "--candidate-report", str(raw)]) == 1


def test_compare_case_levels(tmp_path):
    case = {"inputs": {"a.pcap": "1"}, "l1": {"a.pcap": {"rows": 3, "sha256": "x"}}, "l2": {"obs": {"rows": 1, "sha256": "y"}}}
    ref = _write(tmp_path, "ref.json", case)
    assert compare.main(["--reference", str(ref), "--candidate-case", str(_write(tmp_path, "c.json", case))]) == 0
    bad = dict(case, l1={"a.pcap": {"rows": 3, "sha256": "z"}})
    assert compare.main(["--reference", str(ref), "--candidate-case", str(_write(tmp_path, "b.json", bad))]) == 1


def _committed_cases():
    manifest = json.loads((GOLDENS / "manifest.json").read_text(encoding="utf-8"))
    for entry in manifest["cases"]:
        case = json.loads((GOLDENS / entry["file"]).read_text(encoding="utf-8"))
        sidecar = (GOLDENS / entry["file"]).parent / case["l3"]["key_sections_file"]
        yield entry, case, gzip.decompress(sidecar.read_bytes())


def test_committed_goldens_are_consistent():
    manifest = json.loads((GOLDENS / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["determinism"]["identical"] is True
    assert manifest["tier1_reproduces_packetbreaker_goldens"] is True
    reference = manifest["reference"]
    assert reference["packetbreaker_commit"] == "5b608e64003ee1f20a3f8dc79385fbffee1c84e7"
    assert reference["duckdb_jdbc"] == "org.duckdb:duckdb_jdbc:" + reference["duckdb"] + ".0"
    assert len(manifest["archive"]["sha256"]) == 64
    assert {c["tier"] for c in manifest["cases"]} == {1, 2}
    assert {c["name"] for c in manifest["cases"] if c["tier"] == 1} == {
        "demo", "nat", "offload", "integrity_all", "dns_diagnosis",
        "fifty_flow_replica", "folder_b_replica", "oct8_replica",
    }
    for entry, case, key_bytes in _committed_cases():
        assert case["name"] == entry["name"]
        assert case["inputs"] and case["l1"] and case["l3"]["report_sections"]
        assert hashlib.sha256(key_bytes).hexdigest() == case["l3"]["key_sections_masked_sha256"]
        assert set(json.loads(key_bytes)) <= set(case["l3"]["report_sections"])
        if case["tier"] == 1:
            assert case["l3"]["pb_serializer_identical"] and case["l3"]["pb_committed_golden_identical"]
        for section, digest in case["l3"]["report_sections"].items():
            assert len(digest) == 64, section


def test_compressed_key_sections_pass_repository_privacy_rules():
    """The gate does not read .gz files; apply its own IPv4 and e-mail rules to the decompressed text."""
    sys.path.insert(0, str(ROOT))
    from tools.privacy import repository_privacy as gate

    for entry, _, key_bytes in _committed_cases():
        text = key_bytes.decode("utf-8")
        flagged = [
            m.group(0) for m in gate._IPV4_RE.finditer(text)
            if not gate._is_repository_safe_ip(m.group(0), test_fixture=False)
        ]
        assert not flagged, entry["name"]  # values intentionally not printed beyond the case name
        assert not gate._EMAIL_RE.search(text), entry["name"]
        assert not gate._WINDOWS_USER_PATH_RE.search(text), entry["name"]


def test_compare_accepts_committed_case_against_itself(tmp_path):
    entry, case, key_bytes = next(iter(_committed_cases()))
    candidate = dict(case, l3=dict(case["l3"], key_sections=json.loads(key_bytes)))
    cand = _write(tmp_path, "candidate.json", candidate)
    assert compare.main(["--reference", str(GOLDENS / entry["file"]), "--candidate-case", str(cand)]) == 0
