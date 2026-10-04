"""0.6.6B — Compliance rule-pack transition foundation.

A minimal, in-repository, static, versioned declarative boundary over the
existing ten deterministic CP/PAN compliance controls. The pack is a local
evaluation contract, not a policy source of truth and not a certification
engine. Dynamic/remote/signed packs and tenant overrides are deferred to
0.7.x deployment-era governance.

`BASELINE_CONTROLS` is the single source of truth for the ten controls;
`utils.compliance_posture` derives its evaluators and payload from
`DEFAULT_RULE_PACK`. No I/O, no network, no runtime mutation.
"""
from __future__ import annotations

from typing import Any

RULE_PACK_SCHEMA_VERSION = "1.0"
DEFAULT_RULE_PACK_ID = "securityexpert.baseline.cp-pan"
DEFAULT_RULE_PACK_VERSION = "0.6.6B"
# The ten deterministic vendor-neutral controls now live in
# utils.compliance_catalog as a versioned declarative catalog (0.7.1a). This
# derived view keeps the exact 5-key shape and order the pack and its
# evaluators consume; the added catalog fields (severity, rationale,
# frameworks, lifecycle) are surfaced by compliance_posture, not here.
from utils.compliance_catalog import catalog_baseline_controls

BASELINE_CONTROLS: tuple[dict[str, Any], ...] = catalog_baseline_controls()


def _rule(control: dict[str, Any]) -> dict[str, Any]:
    """One declarative rule for one baseline control.

    Keeps the exact keys the existing deterministic evaluators read
    (`control_id`, `title`, `control_area`, `cis_reference`, `evidence_fields`)
    so routing evaluation through the pack cannot change evaluation inputs, and
    adds the pack traceability metadata.
    """
    return {
        "rule_id": f"{DEFAULT_RULE_PACK_ID}::{control['control_id']}",
        "control_id": control["control_id"],
        "title": control["title"],
        "control_area": control["control_area"],
        "cis_reference": control["cis_reference"],
        "evidence_fields": list(control["evidence_fields"]),
        "benchmark": "CIS",
        "benchmark_reference": control["cis_reference"],
        "applicability": {"vendors": ["check_point", "palo_alto"], "scope": "SUBJECT"},
        "evaluator": control["control_id"],
    }


DEFAULT_RULE_PACK: dict[str, Any] = {
    "pack_id": DEFAULT_RULE_PACK_ID,
    "pack_version": DEFAULT_RULE_PACK_VERSION,
    "schema_version": RULE_PACK_SCHEMA_VERSION,
    "title": "SecurityExpert baseline CP/PAN control pack",
    "source": "in_repository_static",
    "certification_claim": False,
    "disclaimer": (
        "Evidence-backed control-area evaluation only. Not a certification, "
        "attestation or complete framework assessment."
    ),
    "rules": tuple(_rule(control) for control in BASELINE_CONTROLS),
}


def rule_pack_summary(pack: dict[str, Any] = DEFAULT_RULE_PACK) -> dict[str, Any]:
    """Safe, static metadata block for the compliance payload — pack identity,
    version and the no-certification contract. Contains no evidence values."""
    return {
        "pack_id": pack["pack_id"],
        "pack_version": pack["pack_version"],
        "schema_version": pack["schema_version"],
        "title": pack["title"],
        "source": pack["source"],
        "certification_claim": pack["certification_claim"],
        "disclaimer": pack["disclaimer"],
        "rule_count": len(pack["rules"]),
    }
