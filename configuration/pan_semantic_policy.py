from __future__ import annotations

from dataclasses import dataclass, asdict
from typing import Any


SEMANTIC_POLICY_SCHEMA_VERSION = "0.6.0A4.2.2"

TRUSTED_EXPECTED_SOURCE_CONFIDENCE = {"AUTHORITATIVE", "INHERITED"}

# A4.2.2 is intentionally conservative. These are not generic "HA is special"
# guesses: they are narrowly-scoped settings whose PAN representation was
# manually shown to be member-relative in the validation environment.
_MEMBER_SPECIFIC_EXACT_SUFFIXES = {
    "/deviceconfig/high-availability/group/peer-ip",
    "/deviceconfig/high-availability/group/peer-ipv6",
}

# Device telemetry appeared as compiled Template intent in the Panorama XML,
# while the operator could not verify the same expected source in the Template
# UI. Until the vendor/source semantics are independently resolved, a mismatch
# must not be promoted to LOCAL_OVERRIDE or EFFECTIVE_DRIFT.
_PROVENANCE_UNVERIFIED_PREFIXES = {
    "/deviceconfig/system/device-telemetry/",
}

# The expected side may use a logical VSYS name while a firewall can expose an
# internal identifier such as vsys1. A string mismatch is not an override proof.
_IDENTITY_TRANSLATION_EXACT_SUFFIXES = {
    "/shared/user-id-hub/vsys",
}


@dataclass(frozen=True)
class SemanticPolicy:
    policy: str
    directly_comparable: bool
    mismatch_classification: str | None
    expected_source_confidence: str
    override_eligible: bool
    drift_eligible: bool
    reason: str

    def as_dict(self) -> dict[str, Any]:
        return asdict(self)


def _source_confidence(source_kind: str | None) -> str:
    kind = str(source_kind or "").strip().lower()
    if kind == "template_stack_override":
        return "AUTHORITATIVE"
    if kind == "template":
        # The scalar is directly present in a named Template but reaches the
        # firewall through Template-Stack inheritance/precedence.
        return "INHERITED"
    if kind in {"device_group", "shared"}:
        # A4.2 aligns Template-Stack device/network settings, not DG policy.
        return "INFERRED"
    return "UNVERIFIED"


def semantic_policy_for_setting(
    key: str,
    *,
    source_kind: str | None = None,
) -> SemanticPolicy:
    normalized = str(key or "")
    confidence = _source_confidence(source_kind)

    if any(normalized.endswith(suffix) for suffix in _MEMBER_SPECIFIC_EXACT_SUFFIXES):
        return SemanticPolicy(
            policy="MEMBER_SPECIFIC_HA",
            directly_comparable=False,
            mismatch_classification="MEMBER_SPECIFIC",
            expected_source_confidence=confidence,
            override_eligible=False,
            drift_eligible=False,
            reason="ha_peer_address_is_member_relative_and_not_a_cluster_scalar_override_proof",
        )

    if any(normalized.endswith(suffix) for suffix in _IDENTITY_TRANSLATION_EXACT_SUFFIXES):
        return SemanticPolicy(
            policy="IDENTITY_TRANSLATION_REQUIRED",
            directly_comparable=False,
            mismatch_classification="IDENTITY_TRANSLATION_REQUIRED",
            expected_source_confidence=confidence,
            override_eligible=False,
            drift_eligible=False,
            reason="logical_and_internal_vsys_identifiers_require_identity_resolution_before_comparison",
        )

    if any(prefix in normalized for prefix in _PROVENANCE_UNVERIFIED_PREFIXES):
        return SemanticPolicy(
            policy="PROVENANCE_GUARD",
            directly_comparable=False,
            mismatch_classification="PROVENANCE_UNVERIFIED",
            expected_source_confidence="UNVERIFIED",
            override_eligible=False,
            drift_eligible=False,
            reason="compiled_telemetry_source_exists_in_xml_but_expected_template_provenance_was_not_operator_verified",
        )

    trusted = confidence in TRUSTED_EXPECTED_SOURCE_CONFIDENCE
    return SemanticPolicy(
        policy="DIRECT_SCALAR",
        directly_comparable=True,
        mismatch_classification=None,
        expected_source_confidence=confidence,
        override_eligible=trusted,
        drift_eligible=trusted,
        reason=(
            "scalar_setting_is_directly_comparable_with_trusted_compiled_template_provenance"
            if trusted
            else "scalar_setting_path_is_comparable_but_expected_source_provenance_is_not_trusted"
        ),
    )


def semantic_policy_contract() -> dict[str, Any]:
    return {
        "schema_version": SEMANTIC_POLICY_SCHEMA_VERSION,
        "trusted_expected_source_confidence": sorted(TRUSTED_EXPECTED_SOURCE_CONFIDENCE),
        "policies": {
            "DIRECT_SCALAR": "value comparison is permitted; override/drift requires trusted expected provenance",
            "MEMBER_SPECIFIC_HA": "member-relative HA peer addressing is excluded from direct override/drift claims",
            "PROVENANCE_GUARD": "mismatch is held at PROVENANCE_UNVERIFIED until expected source semantics are verified",
            "IDENTITY_TRANSLATION_REQUIRED": "VSYS display-name/internal-ID resolver runs first; unresolved identities remain non-findings",
        },
        "manual_validation_basis": {
            "ha_peer_ip": "validated_as_false_positive_for_generic_local_override",
            "permitted_ip_description": "validated_as_true_local_override_pattern",
            "device_telemetry": "expected_template_provenance_not_verified_in_operator_ui",
            "user_id_hub_vsys": "manual validation confirmed display-name vs internal vsysN can represent the same VSYS; resolver must run before override claims",
        },
        "raw_values_included": False,
        "environment_specific_names_included": False,
    }
