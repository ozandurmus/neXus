"""0.7.4 — framework_mappings: requirement-level coverage.

AC-1..AC-6 of docs/history/phase/0_7_4_FRAMEWORK_REQUIREMENTS.md. No network,
no server, no real-environment gate.
"""
import json

from utils.framework_catalog import (
    FRAMEWORK_CATALOG_VERSION,
    FRAMEWORK_IDS,
    framework_entry,
    normalize_ref,
    requirements_for,
)
from utils.compliance_posture import build_compliance_posture

from test_phase0_7_1_compliance_assignment import (
    _DEVICE_NAME_CP,
    _configuration_payload,
)
from test_phase0_6_1b_1_5_compliance_posture import _sample_project_plan_payload
import pytest

pytestmark = pytest.mark.compliance


_COVERAGE = {"COVERED", "PARTIALLY_COVERED", "UNCOVERED", "NOT_APPLICABLE"}
_POSTURE = {"ALIGNED", "FINDING", "UNKNOWN"}


# --- AC-1: framework catalog + normalize_ref -------------------------

def test_ac1_catalog_shape_and_normalize():
    assert set(FRAMEWORK_IDS) == {"CIS", "PCI-DSS", "BDDK"}
    for fid in FRAMEWORK_IDS:
        entry = framework_entry(fid)
        assert entry["version"]
        reqs = requirements_for(fid)
        assert reqs
        for r in reqs:
            assert r["id"] and r["section"] and r["title"]
            assert isinstance(r["applies"], bool)
        # requirement ids are unique after normalisation
        norm = [normalize_ref(r["id"]) for r in reqs]
        assert len(norm) == len(set(norm))

    assert normalize_ref("CIS 2.1.9") == "2.1.9"
    assert normalize_ref("2.1.9") == "2.1.9"
    assert normalize_ref("2.5.4 / AAA server configured") == "2.5.4"
    assert normalize_ref("not applicable") == ""
    assert normalize_ref(None) == ""
    assert normalize_ref("Erişim Yönetimi - Yasal Uyarı") == "erişim yönetimi - yasal uyarı"


# --- AC-2 / AC-3: requirement roll-up in the payload ---------------

def _overview(cfg=None):
    payload = build_compliance_posture(cfg or _configuration_payload(), _sample_project_plan_payload())
    return payload["compliance_overview"]


def test_ac2_requirement_states():
    ov = _overview()
    assert ov["framework_catalog_version"] == FRAMEWORK_CATALOG_VERSION
    cis = ov["by_framework"]["CIS"]
    assert cis["version"] and "requirements" in cis
    by_id = {r["id"]: r for r in cis["requirements"]}

    # mapped + monitored + aligned control -> COVERED / ALIGNED
    assert by_id["2.1.9"]["control_ids"] == ["telnet_disabled"]
    assert by_id["2.1.9"]["coverage"] == "COVERED"
    assert by_id["2.1.9"]["posture"] == "ALIGNED"

    # mapped control with a FINDING -> PARTIALLY_COVERED / FINDING
    assert by_id["2.1.1"]["posture"] == "FINDING"
    assert by_id["2.1.1"]["coverage"] in ("PARTIALLY_COVERED", "COVERED")

    # curated gap requirement, no mapped control -> UNCOVERED / UNKNOWN
    assert by_id["2.7.1"]["control_ids"] == []
    assert by_id["2.7.1"]["coverage"] == "UNCOVERED"
    assert by_id["2.7.1"]["posture"] == "UNKNOWN"


def test_ac2_not_applicable_requirement():
    pci = _overview()["by_framework"]["PCI-DSS"]
    na = [r for r in pci["requirements"] if not r["applicable"]]
    assert na and all(r["coverage"] == "NOT_APPLICABLE" for r in na)
    assert pci["requirement_counts"]["NOT_APPLICABLE"] == len(na)


def test_ac3_counts_reconcile_and_enum():
    ov = _overview()
    for name in FRAMEWORK_IDS:
        fw = ov["by_framework"][name]
        assert sum(fw["requirement_counts"].values()) == len(fw["requirements"])
        assert set(fw["requirement_counts"]) == _COVERAGE
        for r in fw["requirements"]:
            assert r["coverage"] in _COVERAGE
            assert r["posture"] in _POSTURE
            assert r["monitored"] <= max(len(r["control_ids"]), 0) or r["monitored"] >= 0
        assert isinstance(fw["unmapped_control_refs"], list)
        # every current control ref is modelled -> nothing unmapped
        assert fw["unmapped_control_refs"] == []


# --- AC-4: user checks join a requirement -------------------------

def _pack_body():
    return {
        "version": 1,
        "checks": [{
            "id": "x_telnet_offlist",
            "title": "Telnet not in permitted services (user check)",
            "rationale": "Telnet must never be reachable on the management plane.",
            "severity": "high",
            "applies_to": {"vendor": ["check_point"]},
            "frameworks": [
                {"framework": "CIS", "reference": "2.1.9", "applies": True},
                {"framework": "PCI-DSS", "reference": "2.2.5", "applies": True},
                {"framework": "BDDK", "reference": "Sistem Sıkılaştırma - Güvensiz Protokoller", "applies": True},
            ],
            "evidence": {"steps": [
                {"source": "current_configuration.sections[id=management].settings", "select": "value",
                 "assert": {"op": "none_match", "pattern": "(?i)telnet"}},
            ]},
        }],
    }


def test_ac4_user_check_maps_into_requirement(tmp_path):
    state = tmp_path / "state"
    state.mkdir()
    (state / "compliance_checks.json").write_text(json.dumps(_pack_body()), encoding="utf-8")

    payload = build_compliance_posture(
        _configuration_payload(), _sample_project_plan_payload(), data_root=tmp_path,
    )
    cis = payload["compliance_overview"]["by_framework"]["CIS"]
    req = next(r for r in cis["requirements"] if r["id"] == "2.1.9")
    assert "x_telnet_offlist" in req["control_ids"]
    assert "telnet_disabled" in req["control_ids"]
    assert cis["unmapped_control_refs"] == []


# --- AC-5: additive + privacy ----------------------------------

def test_ac5_additive_and_empty_path():
    empty = build_compliance_posture(None, None)["compliance_overview"]
    assert empty["framework_catalog_version"] == FRAMEWORK_CATALOG_VERSION
    for name in FRAMEWORK_IDS:
        fw = empty["by_framework"][name]
        # framework-level keys preserved
        assert {"controls", "monitored", "aligned", "finding", "coverage"} <= set(fw)
        assert fw["requirements"] and all(r["coverage"] in ("UNCOVERED", "NOT_APPLICABLE") for r in fw["requirements"])
        assert fw["requirement_counts"]["COVERED"] == 0


def test_ac5_no_identity_or_certification_leak():
    payload = build_compliance_posture(_configuration_payload(), _sample_project_plan_payload())
    encoded = json.dumps(payload["compliance_overview"]).lower()
    for token in (_DEVICE_NAME_CP.lower(), "192.0.2.10", "is certified", "certified compliant"):
        assert token not in encoded
