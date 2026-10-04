from __future__ import annotations

import hashlib

from configuration.pan_semantic_policy import semantic_policy_for_setting
from configuration.pan_setting_alignment import align_expected_to_effective
import pytest

pytestmark = pytest.mark.configuration


def _h(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _compiler(path: str, value: str, *, stack: str = "S") -> dict:
    return {
        "template_stacks": {
            stack: {
                "manifest": [
                    {
                        "path": path,
                        "value_sha256": _h(value),
                        "value_kind": "scalar",
                        "alignment_ready": True,
                        "source_kind": "template",
                        "source_name": "T",
                        "source_priority": 1,
                    }
                ]
            }
        }
    }


def _row(stack: str = "S") -> dict:
    return {"primary_template_stack": stack}


def _xml(device_body: str, *, shared: str = "") -> bytes:
    return (
        "<config><shared>" + shared + "</shared><devices><entry name='localhost.localdomain'>"
        + device_body
        + "</entry></devices></config>"
    ).encode()


def test_ha_peer_ip_is_member_specific_not_override():
    path = "/config/devices/entry[@name='localhost.localdomain']/deviceconfig/high-availability/group/peer-ip"
    compiler = _compiler(path, "192.0.2.254")
    body = "<deviceconfig><high-availability><group><peer-ip>192.0.2.253</peer-ip></group></high-availability></deviceconfig>"
    content = _xml(body)
    result = align_expected_to_effective(
        serial="SERIAL",
        expected_compiler=compiler,
        expected_row=_row(),
        effective_content=content,
        merged_content=content,
        active_content=content,
        panorama_sync={"panorama_template_sync": "in_sync"},
    )
    assert result["summary"]["classification_counts"].get("MEMBER_SPECIFIC") == 1
    assert result["summary"]["classification_counts"].get("LOCAL_OVERRIDE", 0) == 0


def test_telemetry_mismatch_is_provenance_unverified_not_override():
    path = "/config/devices/entry[@name='localhost.localdomain']/deviceconfig/system/device-telemetry/region"
    compiler = _compiler(path, "europe")
    body = "<deviceconfig><system><device-telemetry><region>americas</region></device-telemetry></system></deviceconfig>"
    content = _xml(body)
    result = align_expected_to_effective(
        serial="SERIAL",
        expected_compiler=compiler,
        expected_row=_row(),
        effective_content=content,
        merged_content=content,
        active_content=content,
        panorama_sync={"panorama_template_sync": "in_sync"},
    )
    assert result["summary"]["classification_counts"].get("PROVENANCE_UNVERIFIED") == 1
    assert result["summary"]["classification_counts"].get("LOCAL_OVERRIDE", 0) == 0


def test_verified_permitted_ip_description_can_remain_local_override():
    path = "/config/devices/entry[@name='localhost.localdomain']/deviceconfig/system/permitted-ip/entry[@name='10.0.0.1/32']/description"
    compiler = _compiler(path, "panorama-description")
    body = (
        "<deviceconfig><system><permitted-ip><entry name='10.0.0.1/32'>"
        "<description>local-description</description></entry></permitted-ip></system></deviceconfig>"
    )
    content = _xml(body)
    result = align_expected_to_effective(
        serial="SERIAL",
        expected_compiler=compiler,
        expected_row=_row(),
        effective_content=content,
        merged_content=content,
        active_content=content,
        panorama_sync={"panorama_template_sync": "in_sync"},
    )
    assert result["summary"]["classification_counts"].get("LOCAL_OVERRIDE") == 1


def test_vsys_display_name_and_internal_id_value_are_same_identity():
    path = "/config/shared/user-id-hub/vsys"
    compiler = _compiler(path, "Friendly-VSYS-A")
    body = "<vsys><entry name='vsys1'><display-name>Friendly-VSYS-A</display-name></entry></vsys>"
    content = _xml(body, shared="<user-id-hub><vsys>vsys1</vsys></user-id-hub>")
    result = align_expected_to_effective(
        serial="SERIAL",
        expected_compiler=compiler,
        expected_row=_row(),
        effective_content=content,
        merged_content=content,
        active_content=content,
        panorama_sync={"panorama_template_sync": "in_sync"},
    )
    assert result["summary"]["classification_counts"].get("ALIGNED") == 1
    assert result["summary"]["classification_counts"].get("LOCAL_OVERRIDE", 0) == 0
    assert result["summary"]["identity_value_normalized_settings"] == 1
    row = next(r for r in result["results"] if r["alignment_key"] == path)
    assert row["identity_value_normalized"] is True
    assert row["reason"] == "vsys_internal_id_and_display_name_resolve_to_same_identity"


def test_vsys_display_name_in_expected_path_is_canonicalized_to_internal_id():
    path = (
        "/config/devices/entry[@name='localhost.localdomain']/vsys/entry[@name='Friendly-VSYS-B']"
        "/redistribution-agent/entry[@name='panorama']/ip-tags"
    )
    compiler = _compiler(path, "yes")
    body = (
        "<vsys><entry name='vsys5'><display-name>Friendly-VSYS-B</display-name>"
        "<redistribution-agent><entry name='panorama'><ip-tags>yes</ip-tags></entry></redistribution-agent>"
        "</entry></vsys>"
    )
    content = _xml(body)
    result = align_expected_to_effective(
        serial="SERIAL",
        expected_compiler=compiler,
        expected_row=_row(),
        effective_content=content,
        merged_content=content,
        active_content=content,
        panorama_sync={"panorama_template_sync": "in_sync"},
    )
    assert result["summary"]["classification_counts"].get("ALIGNED") == 1
    assert result["summary"]["classification_counts"].get("EXPECTED_ONLY", 0) == 0
    assert result["summary"]["identity_path_normalized_settings"] == 1
    result_row = result["results"][0]
    assert "entry[@name='vsys5']" in result_row["alignment_key"]
    assert result_row["identity_path_normalized"] is True


def test_unresolved_vsys_identity_stays_non_finding():
    path = "/config/shared/user-id-hub/vsys"
    compiler = _compiler(path, "Friendly-VSYS")
    body = "<vsys><entry name='vsys1'><display-name>Other-VSYS</display-name></entry></vsys>"
    content = _xml(body, shared="<user-id-hub><vsys>vsys1</vsys></user-id-hub>")
    result = align_expected_to_effective(
        serial="SERIAL",
        expected_compiler=compiler,
        expected_row=_row(),
        effective_content=content,
        merged_content=content,
        active_content=content,
        panorama_sync={"panorama_template_sync": "in_sync"},
    )
    assert result["summary"]["classification_counts"].get("IDENTITY_TRANSLATION_REQUIRED") == 1
    assert result["summary"]["classification_counts"].get("LOCAL_OVERRIDE", 0) == 0


def test_semantic_policy_contracts_are_conservative():
    member = semantic_policy_for_setting(
        "/config/devices/entry[@name='__DEVICE__']/deviceconfig/high-availability/group/peer-ip",
        source_kind="template",
    )
    assert member.policy == "MEMBER_SPECIFIC_HA"
    assert member.override_eligible is False

    telemetry = semantic_policy_for_setting(
        "/config/devices/entry[@name='__DEVICE__']/deviceconfig/system/device-telemetry/region",
        source_kind="template",
    )
    assert telemetry.policy == "PROVENANCE_GUARD"
    assert telemetry.expected_source_confidence == "UNVERIFIED"

    vsys = semantic_policy_for_setting("/config/shared/user-id-hub/vsys", source_kind="template")
    assert vsys.policy == "IDENTITY_TRANSLATION_REQUIRED"
    assert vsys.override_eligible is False
