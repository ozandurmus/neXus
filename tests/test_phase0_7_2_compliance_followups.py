"""0.7.2 — password / banner / services projection + enrichment controls.

AC-1..AC-4 of docs/history/phase/0_7_2_COMPLIANCE_FOLLOWUPS.md. AC-5 (render)
and AC-6 (full regression) are out-of-band evidence steps. No network, no
server, no real-environment gate — the projection reads already-stored config.
"""
import json

from configuration.checkpoint_config_collector import (
    build_checkpoint_current_configuration,
    _sanitize_configuration,
)
from configuration.current_config_projection import build_pan_current_configuration
from utils.compliance_catalog import (
    CATALOG_VERSION,
    all_subject_control_ids,
    catalog_baseline_controls,
    catalog_enrichment_controls,
)
from utils.compliance_evaluators_ext import evaluate_enrichment_control
from utils.compliance_posture import build_compliance_posture

from test_phase0_6_1b_1_5_compliance_posture import _sample_project_plan_payload
import pytest

pytestmark = pytest.mark.compliance

_NEW_IDS = {
    "password_min_length", "password_complexity_enabled", "password_history_depth",
    "password_lockout_policy", "login_banner_text_present", "unused_services_disabled",
}

_BANNER_BODY = "AUTHORIZED CONTOSO ACCESS ONLY - INTERNAL SITE ALPHA"
_PAN_PHASH = "$1$abcdefgh$THISisAsecretHASHvalue0987654321"


# --- AC-1: catalog shape ------------------------------------------------

def test_ac1_catalog_has_the_six_new_controls():
    assert CATALOG_VERSION == "0.7.2"
    enrichment = catalog_enrichment_controls()
    ids = {c["control_id"] for c in enrichment}
    assert _NEW_IDS <= ids
    assert len(enrichment) == 14  # 8 (0.7.1b) + 6 (0.7.2)
    by_id = {c["control_id"]: c for c in enrichment}
    for cid in _NEW_IDS:
        c = by_id[cid]
        assert c["severity"] in ("informational", "low", "medium", "high", "critical")
        assert c["evaluator"] == cid
        assert c["evidence_fields"]
        assert {f["framework"] for f in c["frameworks"]} == {"CIS", "PCI-DSS", "BDDK"}
        for f in c["frameworks"]:
            assert isinstance(f["applies"], bool)
            assert str(f["reference"]).strip()
    baseline_ids = {c["control_id"] for c in catalog_baseline_controls()}
    assert all_subject_control_ids() == baseline_ids | ids


# --- AC-2: additive; missing sections → UNKNOWN, no inferred PASS -------

def _configuration_payload(pan_sections):
    return {
        "available": True,
        "raw_configuration_blob_included": False,
        "privacy": {"raw_configuration_blob_included": False, "credentials_included": False,
                    "secret_values_redacted": True},
        "fleet": {"tls_verify": True},
        "devices": [
            {
                "vendor_key": "palo_alto", "name": "pan-fw-7", "serial": "SN-PAN",
                "management_ip": "192.0.2.20", "connected": True,
                "current_configuration": {"status": "available", "sections": pan_sections},
                "alignment": {"counts": {}},
            },
        ],
    }


def test_ac2_missing_projection_sections_are_unknown_not_pass():
    payload = build_compliance_posture(
        _configuration_payload([{"id": "system", "settings": [{"setting": "Hostname", "value": "PAN-FW-7"}]}]),
        _sample_project_plan_payload(),
    )
    subj = next(s for s in payload["subjects"] if s["vendor_key"] == "palo_alto")
    ext = {c["control_id"]: c["status"] for c in subj["extended_controls"]}
    for cid in _NEW_IDS:
        assert ext[cid] == "UNKNOWN"
    # top-level keys still only the 0.7.1 additive set
    assert set(payload) >= {"compliance_overview", "assignment_policy"}


def test_ac2_good_sections_reach_pass():
    good = [
        {"id": "password_policy", "settings": [
            {"setting": "Minimum Length", "value": "14"},
            {"setting": "Complexity Required", "value": "yes"},
            {"setting": "History Depth", "value": "5"},
            {"setting": "Lockout: Failed Attempts", "value": "5"},
        ]},
        {"id": "banner", "settings": [{"setting": "Login Banner", "value": "present (<80 chars)"}]},
        {"id": "services", "settings": [
            {"setting": "Telnet Disabled", "value": "yes"},
            {"setting": "HTTP Disabled", "value": "yes"},
        ]},
    ]
    payload = build_compliance_posture(_configuration_payload(good), _sample_project_plan_payload())
    subj = next(s for s in payload["subjects"] if s["vendor_key"] == "palo_alto")
    ext = {c["control_id"]: c["status"] for c in subj["extended_controls"]}
    assert ext["password_min_length"] == "PASS"
    assert ext["password_complexity_enabled"] == "PASS"
    assert ext["password_history_depth"] == "PASS"
    assert ext["password_lockout_policy"] == "PASS"
    assert ext["login_banner_text_present"] == "PASS"
    assert ext["unused_services_disabled"] == "PASS"


def test_ac2_weak_sections_are_findings_not_unknown():
    weak = [
        {"id": "password_policy", "settings": [
            {"setting": "Minimum Length", "value": "4"},
            {"setting": "Complexity Required", "value": "disabled"},
            {"setting": "History Depth", "value": "0"},
        ]},
        {"id": "banner", "settings": [{"setting": "Login Banner", "value": "absent"}]},
        {"id": "services", "settings": [{"setting": "Finger", "value": "enabled"}]},
    ]
    statuses = {cid: evaluate_enrichment_control(_device(weak), cid)[0] for cid in _NEW_IDS}
    assert statuses["password_min_length"] == "FINDING"
    assert statuses["password_complexity_enabled"] == "FINDING"
    assert statuses["password_history_depth"] == "FINDING"
    assert statuses["password_lockout_policy"] == "FINDING"   # no lockout knob at all
    assert statuses["login_banner_text_present"] == "FINDING"
    assert statuses["unused_services_disabled"] == "FINDING"  # finger enabled


def _device(sections):
    return {"vendor_key": "palo_alto", "connected": True,
            "current_configuration": {"status": "available", "sections": sections}}


# --- AC-3 / AC-4: PAN projection extracts knobs, never the body/hash ---

_PAN_XML = f"""<config>
  <mgt-config>
    <password-complexity>
      <enabled>yes</enabled>
      <minimum-length>12</minimum-length>
      <minimum-uppercase-letters>1</minimum-uppercase-letters>
      <minimum-lowercase-letters>1</minimum-lowercase-letters>
      <minimum-numeric-letters>1</minimum-numeric-letters>
      <minimum-special-characters>1</minimum-special-characters>
      <password-history-count>4</password-history-count>
    </password-complexity>
    <users><entry name="admin"><phash>{_PAN_PHASH}</phash></entry></users>
    <login-banner>{_BANNER_BODY}</login-banner>
  </mgt-config>
  <devices><entry name="localhost.localdomain"><deviceconfig><system>
    <hostname>PAN-FW-TEST</hostname>
    <service>
      <disable-telnet>yes</disable-telnet>
      <disable-http>yes</disable-http>
      <disable-icmp>no</disable-icmp>
    </service>
  </system></deviceconfig></entry></devices>
</config>
"""


def test_ac3_pan_projection_password_banner_services(tmp_path):
    xml_path = tmp_path / "effective.xml"
    xml_path.write_text(_PAN_XML, encoding="utf-8")
    row = {"direct": {"effective": {"status": "success", "artifact_object": "effective.xml"}}}
    result = build_pan_current_configuration(base_dir=tmp_path, row=row, alignment_detail=None)

    sections = {s["id"]: s for s in result["sections"]}
    assert "password_policy" in sections and "banner" in sections and "services" in sections

    pw = {r["setting"]: r["value"] for r in sections["password_policy"]["settings"]}
    assert pw["Minimum Length"] == "12"
    assert pw["History Depth"] == "4"

    banner_vals = [r["value"] for r in sections["banner"]["settings"]]
    assert all(v.startswith("present (") for v in banner_vals)

    svc = {r["setting"]: r["value"] for r in sections["services"]["settings"]}
    assert svc["Telnet Disabled"] == "yes"

    # AC-4: neither the banner body nor the admin password hash is anywhere in
    # the projection payload.
    encoded = json.dumps(result)
    assert _BANNER_BODY not in encoded
    assert "CONTOSO" not in encoded
    assert _PAN_PHASH not in encoded
    assert "THISisAsecretHASH" not in encoded
    # service toggles must not have been double-projected under Management
    mgmt = sections.get("management", {}).get("settings", [])
    assert not any("telnet" in str(r.get("setting", "")).lower() for r in mgmt)


# --- AC-4: CP sanitizer keeps policy knobs, drops secrets + banner body -

_CP_STDOUT = """
set hostname CP-GW-TEST
set password-controls min-password-length 10
set password-controls complexity 3
set password-controls password-history 5
set password-controls deny-on-fail enable true
set user admin password s0me-secret-hash-value
set expert-password-hash $1$deadbeef$xxxxxxxxxxxxxxxxxxxx
set message banner on msgvalue "AUTHORIZED CONTOSO ACCESS ONLY"
set message motd msgvalue "internal only - site alpha"
"""


def test_ac4_cp_sanitizer_and_projection():
    sanitized = _sanitize_configuration(_CP_STDOUT)
    safe = sanitized["safe_set_lines"]
    joined = "\n".join(safe)

    assert any("min-password-length 10" in ln for ln in safe)      # policy knob kept
    assert any("password-history 5" in ln for ln in safe)
    assert "s0me-secret-hash-value" not in joined                  # user password withheld
    assert "deadbeef" not in joined                                # expert hash withheld
    assert "AUTHORIZED CONTOSO ACCESS ONLY" not in joined          # banner body withheld
    assert "internal only" not in joined
    assert sanitized["secret_bearing_line_count"] == 2

    current = build_checkpoint_current_configuration(
        safe, secret_bearing_line_count=sanitized["secret_bearing_line_count"],
        entity_type="gateway",
    )
    sections = {s["id"]: s for s in current["sections"]}
    assert "password_policy" in sections and "banner" in sections
    pw = {r["setting"]: r["value"] for r in sections["password_policy"]["settings"]}
    assert pw["Password · Min Password Length"] == "10"
    banner_vals = [r["value"] for r in sections["banner"]["settings"]]
    assert set(banner_vals) == {"present"}
    assert "CONTOSO" not in json.dumps(current)

    device = {"vendor_key": "check_point", "connected": True, "current_configuration": current}
    assert evaluate_enrichment_control(device, "password_min_length")[0] == "PASS"
    assert evaluate_enrichment_control(device, "password_lockout_policy")[0] == "PASS"
    assert evaluate_enrichment_control(device, "login_banner_text_present")[0] == "PASS"
    # CP has no services section → real evidence gap, not NOT_APPLICABLE
    assert evaluate_enrichment_control(device, "unused_services_disabled")[0] == "UNKNOWN"
