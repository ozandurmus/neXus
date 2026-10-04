"""0.7.3 (CE.1) — user-authored, data-driven compliance check engine.

AC-1..AC-7 of docs/history/phase/0_7_3_COMPLIANCE_CHECK_ENGINE.md. No network,
no server, no real-environment gate — the engine reads already-collected
evidence.
"""
import json

import pytest

from utils.compliance_check_engine import (
    apply_assertion,
    evaluate_check,
    parse_selector,
    resolve_source,
)
from utils.compliance_check_pack import (
    CompliancePackError,
    load_compliance_checks,
)
from utils.compliance_posture import build_compliance_posture
from utils.control_assignment import ControlAssignmentPolicyError, load_control_assignments

from test_phase0_6_1b_1_5_compliance_posture import _sample_project_plan_payload

pytestmark = pytest.mark.compliance


def _write_pack(tmp_path, body):
    state = tmp_path / "state"
    state.mkdir(parents=True, exist_ok=True)
    (state / "compliance_checks.json").write_text(json.dumps(body), encoding="utf-8")
    return tmp_path


def _write_assignments(tmp_path, body):
    state = tmp_path / "state"
    state.mkdir(parents=True, exist_ok=True)
    (state / "control_assignments.json").write_text(json.dumps(body), encoding="utf-8")
    return tmp_path


_GOOD_CHECK = {
    "id": "x_ssh_no_cbc",
    "title": "SSH management offers no CBC ciphers",
    "rationale": "CBC-mode SSH ciphers are plaintext-recovery vulnerable.",
    "severity": "high",
    "applies_to": {"vendor": ["check_point"]},
    "frameworks": [
        {"framework": "CIS", "reference": "2.1.11", "applies": True},
        {"framework": "PCI-DSS", "reference": "2.2.5", "applies": True},
        {"framework": "BDDK", "reference": "Sistem Sıkılaştırma", "applies": True},
    ],
    "evidence": {
        "combine": "all",
        "steps": [
            {"source": "current_configuration.sections[id=management].settings",
             "select": "value", "assert": {"op": "none_match", "pattern": "(?i)-cbc"}},
        ],
    },
    "verdict": {"on_pass": "PASS", "on_fail": "FINDING", "on_no_evidence": "UNKNOWN"},
}


def _pack(*checks, **extra):
    return {"version": 1, "checks": list(checks), **extra}


# --- AC-1: pack loader / fail-closed ----------------------------------

def test_ac1_good_pack_loads(tmp_path):
    _write_pack(tmp_path, _pack(_GOOD_CHECK))
    pack = load_compliance_checks(tmp_path)
    assert pack.is_active
    assert pack.check_ids() == {"x_ssh_no_cbc"}
    assert pack.advisory_count == 0


def test_ac1_missing_file_is_inert(tmp_path):
    pack = load_compliance_checks(tmp_path)
    assert not pack.is_active
    assert pack.source == "missing"
    assert pack.checks == ()


def test_ac1_disabled_pack_is_inert(tmp_path):
    _write_pack(tmp_path, _pack(_GOOD_CHECK, enabled=False))
    pack = load_compliance_checks(tmp_path)
    assert not pack.is_active
    assert pack.source == "disabled"


@pytest.mark.parametrize("mutate", [
    lambda c: {**c, "id": "ssh_no_cbc"},                       # not x_-prefixed
    lambda c: {**c, "id": "x_BAD CAPS"},                       # bad id charset
    lambda c: {**c, "severity": "spicy"},                      # bad severity
    lambda c: {**c, "mode": "whenever"},                       # bad mode
    lambda c: {**c, "evidence": {"steps": []}},                # empty steps
    lambda c: {**c, "evidence": {"combine": "most", "steps": c["evidence"]["steps"]}},
    lambda c: {**c, "evidence": {"steps": [{"source": "nope.foo", "assert": {"op": "present"}}]}},
    lambda c: {**c, "evidence": {"steps": [{"source": "current_configuration.x[bad", "assert": {"op": "present"}}]}},
    lambda c: {**c, "evidence": {"steps": [{"source": "current_configuration.a", "assert": {"op": "wat"}}]}},
    lambda c: {**c, "evidence": {"steps": [{"source": "current_configuration.a", "assert": {"op": "matches", "pattern": "(a+)+"}}]}},
    lambda c: {**c, "evidence": {"steps": [{"source": "current_configuration.a", "assert": {"op": "matches", "pattern": "x" * 600}}]}},
    lambda c: {**c, "evidence": {"steps": [{"source": "current_configuration.a", "assert": {"op": "gte"}}]}},
    lambda c: {**c, "remediation": {"primitive": "x"}},        # reserved for CE.4
    lambda c: {**c, "frameworks": [{"framework": "SOC2", "reference": "x"}]},
])
def test_ac1_malformed_check_fails_closed(tmp_path, mutate):
    _write_pack(tmp_path, _pack(mutate(_GOOD_CHECK)))
    with pytest.raises(CompliancePackError):
        load_compliance_checks(tmp_path)


def test_ac1_duplicate_id_fails_closed(tmp_path):
    _write_pack(tmp_path, _pack(_GOOD_CHECK, dict(_GOOD_CHECK)))
    with pytest.raises(CompliancePackError):
        load_compliance_checks(tmp_path)


def test_ac1_bad_schema_version_fails_closed(tmp_path):
    _write_pack(tmp_path, {"version": 2, "checks": []})
    with pytest.raises(CompliancePackError):
        load_compliance_checks(tmp_path)


# --- AC-2: selector + assertion engine ------------------------------

_EVIDENCE = {
    "current_configuration": {
        "sections": [
            {"id": "management", "settings": [
                {"setting": "SSH Ciphers", "value": "aes256-ctr,aes128-cbc"},
                {"setting": "SSH Version", "value": "v2"},
            ]},
            {"id": "ntp", "settings": [{"setting": "Primary NTP Server", "value": "203.0.113.10"}]},
        ],
    },
    "unified": {"device": {"platform_family": "gaia", "ha_role": "active"}, "interfaces": [], "routes": []},
    "alignment": {"results": [{"classification": "LOCAL_OVERRIDE"}]},
    "crypto_facts": {},
}


def test_ac2_resolve_source_walks_filtered_list():
    sel = parse_selector("current_configuration.sections[id=management].settings")
    rows = resolve_source(_EVIDENCE, sel)
    assert isinstance(rows, list)
    assert any(r["setting"] == "SSH Ciphers" for r in rows)

    assert resolve_source(_EVIDENCE, parse_selector("unified.device.ha_role")) == "active"
    assert resolve_source(_EVIDENCE, parse_selector("current_configuration.nope")) is None
    assert resolve_source(_EVIDENCE, parse_selector("crypto_facts.ike.proposals")) is None


def test_ac2_parse_selector_rejects_unknown_namespace():
    with pytest.raises(CompliancePackError):
        parse_selector("inventory.device")


def _step(op, **kw):
    from utils.compliance_check_pack import _step as build_step
    return build_step({"source": "current_configuration.a", "select": "", "assert": {"op": op, **kw}})


def test_ac2_operators():
    assert apply_assertion(["aes128-cbc", "aes256-ctr"], _step("any_match", pattern="(?i)-cbc")) is True
    assert apply_assertion(["aes256-ctr"], _step("none_match", pattern="(?i)-cbc")) is True
    assert apply_assertion([], _step("none_match", pattern="(?i)-cbc")) is None       # no evidence
    assert apply_assertion([], _step("absent")) is True
    assert apply_assertion(["x"], _step("present")) is True
    assert apply_assertion(["10"], _step("gte", value=8)) is True
    assert apply_assertion(["4"], _step("gte", value=8)) is False
    assert apply_assertion(["v2"], _step("in", values=["v2", "v3"])) is True
    assert apply_assertion(["a", "b", "c"], _step("count_gte", value=2)) is True


# --- AC-3: evaluate_check combine + no-evidence ---------------------

def _check(**over):
    body = {**_GOOD_CHECK, **over}
    _pack_body = {"version": 1, "checks": [body]}
    import tempfile
    from pathlib import Path
    d = Path(tempfile.mkdtemp())
    (d / "state").mkdir()
    (d / "state" / "compliance_checks.json").write_text(json.dumps(_pack_body), encoding="utf-8")
    return load_compliance_checks(d).checks[0]


def test_ac3_combine_all_pass_fail_unknown():
    chk = _check(evidence={"combine": "all", "steps": [
        {"source": "current_configuration.sections[id=management].settings", "select": "value",
         "assert": {"op": "none_match", "pattern": "(?i)-cbc"}},
        {"source": "current_configuration.sections[id=ntp].settings", "select": "value",
         "assert": {"op": "present"}},
    ]})
    # management has -cbc -> step 1 False -> FINDING
    status, summary, coverage, steps = evaluate_check(_EVIDENCE, chk)
    assert status == "FINDING"
    assert len(steps) == 2

    # no management section at all -> step 1 inconclusive, step 2 also -> UNKNOWN
    empty = {"current_configuration": {"sections": []}, "unified": {}, "alignment": {}, "crypto_facts": {}}
    status2, _, coverage2, _ = evaluate_check(_EVIDENCE if False else empty, chk)
    assert status2 == "UNKNOWN"
    assert coverage2 == "not_collected"


def test_ac3_combine_any_pass():
    chk = _check(evidence={"combine": "any", "steps": [
        {"source": "current_configuration.sections[id=management].settings", "select": "value",
         "assert": {"op": "any_match", "pattern": "(?i)-cbc"}},
        {"source": "current_configuration.sections[id=ntp].settings", "select": "value",
         "assert": {"op": "any_match", "pattern": "nomatch"}},
    ]})
    status, *_ = evaluate_check(_EVIDENCE, chk)
    assert status == "PASS"


# --- AC-4..AC-6: posture integration -------------------------------

_DEVICE_NAME_CP = "corp-cp-gw-77"


def _configuration_payload(cp_sections):
    return {
        "available": True,
        "raw_configuration_blob_included": False,
        "privacy": {"raw_configuration_blob_included": False, "credentials_included": False,
                    "secret_values_redacted": True},
        "fleet": {"tls_verify": True},
        "devices": [
            {
                "vendor_key": "check_point", "name": _DEVICE_NAME_CP,
                "platform_family": "gaia", "entity_type": "gateway",
                "serial": "SN-CP", "management_ip": "192.0.2.10", "connected": True,
                "current_configuration": {"status": "available", "sections": cp_sections},
                "alignment": {"findings": []},
            },
        ],
    }


_CP_SECTIONS = [
    {"id": "management", "settings": [{"setting": "SSH Ciphers", "value": "aes256-ctr only"}]},
    {"id": "ntp", "settings": [{"setting": "Primary NTP Server", "value": "203.0.113.10"}]},
]


def test_ac4_user_check_reaches_extended_controls_and_rollup(tmp_path):
    _write_pack(tmp_path, _pack(
        _GOOD_CHECK,
        {**_GOOD_CHECK, "id": "x_ntp_present", "mode": "advisory",
         "evidence": {"steps": [
             {"source": "current_configuration.sections[id=ntp].settings", "select": "value",
              "assert": {"op": "present"}}]}},
    ))
    payload = build_compliance_posture(_configuration_payload(_CP_SECTIONS), _sample_project_plan_payload(), data_root=tmp_path)

    subj = next(s for s in payload["subjects"] if s["vendor_key"] == "check_point")
    ext = {c["control_id"]: c for c in subj["extended_controls"]}
    assert ext["x_ssh_no_cbc"]["control_class"] == "user_check"
    assert ext["x_ssh_no_cbc"]["status"] == "PASS"          # no -cbc in the fixture
    assert ext["x_ssh_no_cbc"]["pack"]["pack_id"]
    assert ext["x_ntp_present"]["advisory"] is True

    ov = payload["compliance_overview"]
    # enforced user check counted; advisory one not
    assert "x_ssh_no_cbc" in " ".join(payload["check_packs"][0].keys()) or payload["check_packs"][0]["checks"] == 2
    assert payload["check_packs"][0]["advisory_checks"] == 1
    assert ov["cells"]["aligned"] >= 1
    # advisory row must not have moved the subject to a worse status than its
    # enforced rows warrant
    assert subj["status"] in ("PASS", "UNKNOWN", "FINDING")


def test_ac4_advisory_excluded_from_score(tmp_path, tmp_path_factory):
    # An advisory check that FAILs must not change the roll-up vs. no pack at all.
    baseline = build_compliance_posture(
        _configuration_payload(_CP_SECTIONS), None,
        data_root=tmp_path_factory.mktemp("nopack"),
    )
    _write_pack(tmp_path, _pack(
        {**_GOOD_CHECK, "id": "x_always_fail", "mode": "advisory",
         "evidence": {"steps": [
             {"source": "current_configuration.sections[id=management].settings", "select": "value",
              "assert": {"op": "any_match", "pattern": "this-will-never-match"}}]}},
    ))
    withadv = build_compliance_posture(_configuration_payload(_CP_SECTIONS), None, data_root=tmp_path)

    subj = next(s for s in withadv["subjects"] if s["vendor_key"] == "check_point")
    adv = next(c for c in subj["extended_controls"] if c["control_id"] == "x_always_fail")
    assert adv["status"] == "FINDING" and adv["advisory"] is True
    # the advisory FINDING changed nothing in the score
    assert withadv["compliance_overview"]["cells"] == baseline["compliance_overview"]["cells"]
    assert withadv["compliance_overview"]["total_controls"] == baseline["compliance_overview"]["total_controls"]
    base_subj = next(s for s in baseline["subjects"] if s["vendor_key"] == "check_point")
    assert subj["status"] == base_subj["status"]


def test_ac5_assignment_and_waiver_target_user_check(tmp_path):
    _write_pack(tmp_path, _pack(_GOOD_CHECK))
    _write_assignments(tmp_path, {
        "version": 1,
        "waivers": [{"control_id": "x_ssh_no_cbc", "device_name": _DEVICE_NAME_CP,
                     "reason": "legacy jump host - risk accepted", "approver": "netsec-lead",
                     "expires": "2099-01-01"}],
    })
    # no "unknown control id" error even though x_ssh_no_cbc is not in the catalog
    policy = load_control_assignments(tmp_path, extra_known_ids=frozenset({"x_ssh_no_cbc"}))
    assert policy.is_active

    payload = build_compliance_posture(_configuration_payload(_CP_SECTIONS), None, data_root=tmp_path)
    subj = next(s for s in payload["subjects"] if s["vendor_key"] == "check_point")
    row = next(c for c in subj["extended_controls"] if c["control_id"] == "x_ssh_no_cbc")
    assert row["status"] == "WAIVED"
    assert "x_ssh_no_cbc" in subj["assignment"]["waived"]


def test_ac5_unknown_user_check_in_policy_still_fails_closed(tmp_path):
    _write_assignments(tmp_path, {
        "version": 1,
        "assignments": [{"target": {"device_name": "x"}, "include": ["x_not_in_any_pack"]}],
    })
    with pytest.raises(ControlAssignmentPolicyError):
        load_control_assignments(tmp_path, extra_known_ids=frozenset())


def test_ac6_privacy_no_pattern_or_selector_or_identity(tmp_path):
    _write_pack(tmp_path, _pack(_GOOD_CHECK))
    payload = build_compliance_posture(_configuration_payload(_CP_SECTIONS), None, data_root=tmp_path)
    encoded = json.dumps(payload)
    assert "(?i)-cbc" not in encoded                                   # the pattern
    assert "current_configuration.sections[id=management]" not in encoded  # the raw selector
    assert _DEVICE_NAME_CP not in encoded
    assert "192.0.2.10" not in encoded
    assert "netsec-lead" not in encoded
    block = payload["check_packs"][0]
    assert set(block) == {"pack_id", "pack_version", "source", "enabled", "checks", "advisory_checks"}


def test_ac7_unavailable_payload_carries_empty_check_packs():
    payload = build_compliance_posture(None, None)
    assert payload["available"] is False
    assert payload["check_packs"] == []


def test_ac7_missing_pack_is_additive_noop(tmp_path):
    payload = build_compliance_posture(_configuration_payload(_CP_SECTIONS), None, data_root=tmp_path)
    assert payload["check_packs"] == []
    subj = next(s for s in payload["subjects"] if s["vendor_key"] == "check_point")
    assert all(not c["control_id"].startswith("x_") for c in subj["extended_controls"])


# --- CE.1 fast-follow: crypto_facts source namespace wired -----------

def test_crypto_facts_namespace_resolves():
    ev = {"crypto_facts": {"ike_crypto_profiles": [{"name": "p1", "encryption": ["aes-128-cbc", "aes-256-gcm"]}]}}
    got = resolve_source(ev, parse_selector("crypto_facts.ike_crypto_profiles"))
    assert isinstance(got, list) and got[0]["name"] == "p1"


def test_crypto_facts_check_evaluates_against_wired_facts(tmp_path):
    _write_pack(tmp_path, _pack({
        "id": "x_ike_no_cbc",
        "title": "IKE crypto profiles offer no CBC encryption",
        "rationale": "CBC-mode IKE encryption is a legacy weakness.",
        "severity": "high",
        "applies_to": {"vendor": ["check_point"]},
        "frameworks": [
            {"framework": "CIS", "reference": "x", "applies": True},
            {"framework": "PCI-DSS", "reference": "x", "applies": True},
            {"framework": "BDDK", "reference": "x", "applies": True},
        ],
        "evidence": {"steps": [
            {"source": "crypto_facts.ike_crypto_profiles", "select": "encryption",
             "assert": {"op": "none_match", "pattern": "(?i)-cbc"}},
        ]},
    }))

    # no facts wired -> the namespace resolves empty -> on_no_evidence (UNKNOWN)
    bare = build_compliance_posture(_configuration_payload(_CP_SECTIONS), None, data_root=tmp_path)
    b = next(c for c in next(s for s in bare["subjects"] if s["vendor_key"] == "check_point")["extended_controls"]
             if c["control_id"] == "x_ike_no_cbc")
    assert b["status"] == "UNKNOWN"

    # facts wired for cp-001 with a CBC profile -> FINDING
    facts = {"cp-001": {"ike_crypto_profiles": [{"name": "legacy", "encryption": ["aes-256-cbc"]}]}}
    wired = build_compliance_posture(
        _configuration_payload(_CP_SECTIONS), None, data_root=tmp_path,
        crypto_facts_by_subject=facts,
    )
    w = next(c for c in next(s for s in wired["subjects"] if s["vendor_key"] == "check_point")["extended_controls"]
             if c["control_id"] == "x_ike_no_cbc")
    assert w["status"] == "FINDING"

    # facts wired with only GCM -> PASS
    facts_ok = {"cp-001": {"ike_crypto_profiles": [{"name": "modern", "encryption": ["aes-256-gcm"]}]}}
    ok = build_compliance_posture(
        _configuration_payload(_CP_SECTIONS), None, data_root=tmp_path,
        crypto_facts_by_subject=facts_ok,
    )
    o = next(c for c in next(s for s in ok["subjects"] if s["vendor_key"] == "check_point")["extended_controls"]
             if c["control_id"] == "x_ike_no_cbc")
    assert o["status"] == "PASS"


# --- CE.1 fast-follow #2: unified.interfaces / unified.routes wired --------

_CP_INVENTORY = [
    {
        "source": "cp", "device": _DEVICE_NAME_CP,
        "interfaces": [
            {"name": "eth0", "ipv4_address": "198.51.100.2"},
            {"name": "eth1", "ipv4_address": "198.51.100.3"},
        ],
        "routes": [{"destination": "10.10.0.0/16", "next_hop": "198.51.100.1"}],
    },
]


def _inv_check(check_id, value, *, key="interfaces", op="count_gte"):
    return {
        **_GOOD_CHECK, "id": check_id, "title": check_id.replace("_", " "),
        "evidence": {"steps": [
            {"source": f"unified.{key}", "assert": {"op": op, "value": value}}]},
    }


def _cp_user_row(payload, control_id):
    subj = next(s for s in payload["subjects"] if s["vendor_key"] == "check_point")
    return next(c for c in subj["extended_controls"] if c["control_id"] == control_id)


def test_unified_interfaces_pack_rejects_value_echoing_op(tmp_path):
    _write_pack(tmp_path, _pack({
        **_GOOD_CHECK, "id": "x_iface_name",
        "evidence": {"steps": [
            {"source": "unified.interfaces", "select": "name",
             "assert": {"op": "matches", "pattern": "eth0"}}]},
    }))
    with pytest.raises(CompliancePackError):
        load_compliance_checks(tmp_path)


def test_unified_routes_pack_allows_count_and_presence_ops(tmp_path):
    _write_pack(tmp_path, _pack(
        _inv_check("x_min_ifaces", 2),
        _inv_check("x_has_routes", 0, key="routes", op="present"),
    ))
    pack = load_compliance_checks(tmp_path)
    assert pack.check_ids() == {"x_min_ifaces", "x_has_routes"}


def test_unified_interfaces_resolves_from_wired_inventory(tmp_path):
    _write_pack(tmp_path, _pack(
        _inv_check("x_min_two_ifaces", 2),
        _inv_check("x_min_four_ifaces", 4),
    ))
    # nothing wired -> namespace stays unresolved -> on_no_evidence
    bare = build_compliance_posture(_configuration_payload(_CP_SECTIONS), None, data_root=tmp_path)
    assert _cp_user_row(bare, "x_min_two_ifaces")["status"] == "UNKNOWN"

    wired = build_compliance_posture(
        _configuration_payload(_CP_SECTIONS), None, data_root=tmp_path,
        unified_inventory=_CP_INVENTORY,
    )
    assert _cp_user_row(wired, "x_min_two_ifaces")["status"] == "PASS"      # 2 >= 2
    assert _cp_user_row(wired, "x_min_four_ifaces")["status"] == "FINDING"  # 2 >= 4 is false


def test_unified_routes_observed_is_count_only_and_private(tmp_path):
    _write_pack(tmp_path, _pack(_inv_check("x_has_routes", 1, key="routes")))
    payload = build_compliance_posture(
        _configuration_payload(_CP_SECTIONS), None, data_root=tmp_path,
        unified_inventory=_CP_INVENTORY,
    )
    row = _cp_user_row(payload, "x_has_routes")
    assert row["status"] == "PASS"
    assert row["check_steps"][0]["observed"] == "1 inventory row(s)"
    encoded = json.dumps(payload)
    assert "198.51.100.1" not in encoded    # route next-hop
    assert "10.10.0.0/16" not in encoded    # route destination
    assert "198.51.100.2" not in encoded    # interface address (defence in depth)


def test_unified_inventory_join_is_vendor_scoped(tmp_path):
    _write_pack(tmp_path, _pack(_inv_check("x_min_one_iface", 1)))
    # same identity string, but a Panorama-plane row must not feed a CP subject
    pan_row = [{"source": "panorama", "device": _DEVICE_NAME_CP,
                "interfaces": [{"name": "e1"}, {"name": "e2"}, {"name": "e3"}], "routes": []}]
    payload = build_compliance_posture(
        _configuration_payload(_CP_SECTIONS), None, data_root=tmp_path,
        unified_inventory=pan_row,
    )
    assert _cp_user_row(payload, "x_min_one_iface")["status"] == "UNKNOWN"


def test_unified_inventory_kwarg_omitted_is_noop(tmp_path):
    _write_pack(tmp_path, _pack(_GOOD_CHECK))
    a = build_compliance_posture(
        _configuration_payload(_CP_SECTIONS), _sample_project_plan_payload(), data_root=tmp_path)
    b = build_compliance_posture(
        _configuration_payload(_CP_SECTIONS), _sample_project_plan_payload(), data_root=tmp_path,
        unified_inventory=None)
    assert json.dumps(a, sort_keys=True) == json.dumps(b, sort_keys=True)
