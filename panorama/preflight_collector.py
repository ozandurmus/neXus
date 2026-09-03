"""SecurityExpert -- OP.0b S6, Palo Alto dedicated preflight collector.

Contract chain: `docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
(FROZEN WITH REAL-ENV VALIDATION GATES) -> `docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md`
(APPROVED (2026-09-03), "Approval record") -- the sole implementation
authorities for this module. `checkpoint/preflight_collector.py` (`S5`) is
the collector-architecture reference for this build's shape only -- no
vendor semantics are borrowed from it.

Responsibility (task S6 §1):

    one explicitly selected PAN HA operational entity
        -> bounded two physical members (caller-supplied)
        -> one preflight_run_id
        -> one controlled API session/member (P1, P2, P4 all reuse it)
        -> P1 + P2 + P4
        -> existing S2 extraction (P1/P2) + new P4 extraction
        -> S1 PreflightFact / Provenance
        -> PreflightSnapshot

Evidence only. No readiness verdict, no action eligibility, no failover
execution, no `CLASS 2`. No raw response persisted. No new SSH/API session
shape, credential path, or TLS policy -- P1/P2/P4 all resolve one API key
against the member's own direct endpoint
(`configuration.panorama_config_collector.get_firewall_api_key`/`api_post`,
unmodified) and reuse it for every read; `D-T1` (direct vs. Panorama proxy)
is resolved here as **direct for every row**, since `P1`'s own frozen plane
is unconditionally direct (`OP_0B_0` §24: "direct API (identity gate)") and
the gate's own PAN preamble requires `P2`/`P4` not to split transport from
whichever plane `P2` uses in this collector.
"""
from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Sequence

from configuration.panorama_config_collector import (
    _parse_pan_ha_preflight_fields,
    _pan_ha_group_text,
    api_post,
    get_direct_system_info,
    get_firewall_api_key,
    fix_host,
)
from panorama.pan_preflight_battery import COMMAND_TEXT, PANPreflightRead, build_member_schedule
from panorama.pan_preflight_extraction import parse_pan_path_monitoring
from panorama.pan_preflight_projection import (
    project_pan_identity_fact,
    project_pan_path_monitoring_facts,
    project_pan_preflight_facts,
)
from utils.failover.preflight_model import (
    OpaqueToken,
    Outcome,
    PreflightFact,
    PreflightMemberEvidence,
    PreflightSnapshot,
    Transport,
)
from utils.runtime_auth import RuntimeAuth

__all__ = [
    "MAX_PHYSICAL_MEMBERS",
    "PANPreflightCollectionError",
    "PANPhysicalMemberTarget",
    "collect_member",
    "run_pan_preflight",
]

#: Bounded, caller-selected physical members only -- one PAN HA pair. No
#: fleet-wide, first-N, or implicit expansion (task S6 §3/§25).
MAX_PHYSICAL_MEMBERS = 2


class PANPreflightCollectionError(RuntimeError):
    """Raised for a configuration error in how this collector was invoked
    (e.g. too many members) -- never for an ordinary device-read failure,
    which is represented as evidence (`FactState.COLLECTION_FAILED`), not an
    exception."""


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


@dataclass(frozen=True)
class _CfgShim:
    """The narrowest possible object `get_firewall_api_key` needs
    (`cfg.auth.principal`/`cfg.auth.secret`) -- reuses the existing
    `RuntimeAuth` carrier, not a new credential type (task S6 §8)."""

    auth: RuntimeAuth


def _api_key(username: str, secret: str, host: str, *, verify: bool | str, timeout: float) -> str:
    return get_firewall_api_key(_CfgShim(auth=RuntimeAuth(principal=username, secret=secret)), host, verify=verify, timeout=timeout)


def _direct_op_read(host: str, key: str, read: PANPreflightRead, *, verify: bool | str, timeout: float):
    """One direct-to-firewall op-command call for a fixed, PO-approved read.
    Reuses `api_post` unmodified -- no `target=` parameter (that shape is
    Panorama-proxying, `P2`'s existing production usage elsewhere in this
    repository; this collector always talks directly to the member's own
    endpoint, task S6 §8/§25). Command text is resolved internally from
    `pan_preflight_battery.COMMAND_TEXT` only -- callers cannot pass
    arbitrary XML through this function (task S6 §15)."""
    return api_post(
        host, key, {"type": "op", "cmd": COMMAND_TEXT[read]},
        verify=verify, timeout=timeout, operation=f"PAN preflight {read.value}",
    )


def _get_direct_ha_state_fields(root) -> dict:
    """`P2`'s five always-parsed leaves + the S2 `preflight_fields` family,
    read from an already-fetched direct `show high-availability state`
    response. Reuses `_pan_ha_group_text`/`_parse_pan_ha_preflight_fields`
    (`configuration.panorama_config_collector`, unmodified) -- the exact
    same parser `P2`'s existing Panorama-proxied production path uses; this
    function differs only in how the response was transported, never in how
    it is parsed (task S6 §10/§24: no P2 parser #2)."""
    # Same top-level (not group-scoped) leaf `get_target_ha_runtime_state`
    # reads -- `_pan_ha_group_text` is `result/group/`-scoped and does not
    # apply to this leaf, so it is read directly here, identically.
    enabled = (root.findtext(".//result/enabled") or root.findtext(".//enabled") or "").strip() or None
    fields = {
        "enabled": enabled,
        "state": _pan_ha_group_text(root, "local-info/state"),
        "mode": _pan_ha_group_text(root, "local-info/mode"),
        "peer_state": _pan_ha_group_text(root, "peer-info/state"),
        "state_sync": _pan_ha_group_text(root, "local-info/state-sync"),
    }
    fields.update(_parse_pan_ha_preflight_fields(root))
    return fields


@dataclass(frozen=True)
class PANPhysicalMemberTarget:
    """One caller-selected physical member of the operational HA entity.
    `physical_device_identity` is the already-tokenized/opaque identity this
    member's evidence is attributed to -- distinct from `expected_serial`
    (the management-plane-known serial the identity gate compares against,
    exact string equality only, task S6 §5)."""

    physical_device_identity: str
    expected_serial: str
    management_ip: str


def collect_member(
    *,
    username: str,
    secret: str,
    target: PANPhysicalMemberTarget,
    preflight_run_id: str,
    operational_entity_id: str,
    verify: bool | str = False,
    timeout: float = 20.0,
    transport: Transport = Transport.DIRECT_API,
) -> PreflightMemberEvidence:
    """Run the fixed, bounded read battery for one physical member over one
    controlled direct API session (one key, reused for `P1`/`P2`/`P4`) and
    project it into one `PreflightMemberEvidence`. No retry: each read is
    issued exactly once (task S6 §4/§21 test 7)."""
    schedule = build_member_schedule()
    host = fix_host(target.management_ip)
    own_facts: list[PreflightFact] = []

    key = _api_key(username, secret, host, verify=verify, timeout=timeout)

    # P1: identity gate. Exact string comparison only -- no int()/lstrip,
    # no numeric equivalence (task S6 §5, AGENTS.md opaque-identifier law).
    p1_at = _utc_now()
    try:
        system = get_direct_system_info(host, key, verify=verify, timeout=timeout)
        observed_serial = system.get("serial")
        accepted = bool(observed_serial) and observed_serial == target.expected_serial
    except Exception:
        accepted = False
    own_facts.append(
        project_pan_identity_fact(
            accepted, preflight_run_id=preflight_run_id, collected_at=p1_at,
            physical_device_identity=target.physical_device_identity,
            operational_entity_id=operational_entity_id, transport=transport,
        )
    )
    if not accepted:
        # Identity gate failure stops attribution for this member -- no
        # further read is issued or attributed (task S6 §5/§9).
        return PreflightMemberEvidence(
            physical_device_identity=OpaqueToken(target.physical_device_identity),
            own_facts=tuple(own_facts), peer_claim_facts=(),
        )

    # P2: HA state -- existing S2 extraction/projection seam, unchanged.
    p2_at = _utc_now()
    try:
        root = _direct_op_read(host, key, PANPreflightRead.P2_HA_STATE, verify=verify, timeout=timeout)
        p2_fields = _get_direct_ha_state_fields(root)
        p2_outcome_success = True
    except Exception:
        p2_fields = None
        p2_outcome_success = False

    p2_member = project_pan_preflight_facts(
        p2_fields, preflight_run_id=preflight_run_id, collected_at=p2_at,
        physical_device_identity=target.physical_device_identity,
        operational_entity_id=operational_entity_id, transport=transport,
        source_command="P2", outcome=Outcome.SUCCESS if p2_outcome_success else Outcome.FAILED,
    )
    own_facts.extend(p2_member.own_facts)
    peer_claim_facts = p2_member.peer_claim_facts

    # P4: path monitoring -- new pure extraction + projection.
    p4_at = _utc_now()
    try:
        p4_root = _direct_op_read(host, key, PANPreflightRead.P4_PATH_MONITORING, verify=verify, timeout=timeout)
        p4_parsed = parse_pan_path_monitoring(p4_root)
        p4_outcome_success = True
    except Exception:
        p4_parsed = None
        p4_outcome_success = False
    own_facts.extend(
        project_pan_path_monitoring_facts(
            p4_parsed, preflight_run_id=preflight_run_id, collected_at=p4_at,
            physical_device_identity=target.physical_device_identity,
            operational_entity_id=operational_entity_id, transport=transport,
            outcome=Outcome.SUCCESS if p4_outcome_success else Outcome.FAILED,
        )
    )

    assert len(schedule) == 3  # P1 + P2 + P4, the entire fixed battery -- bound proof, not a runtime branch

    return PreflightMemberEvidence(
        physical_device_identity=OpaqueToken(target.physical_device_identity),
        own_facts=tuple(own_facts), peer_claim_facts=peer_claim_facts,
    )


def run_pan_preflight(
    *,
    operational_entity_id: str,
    members: Sequence[PANPhysicalMemberTarget],
    username: str,
    secret: str,
    verify: bool | str = False,
    timeout: float = 20.0,
) -> PreflightSnapshot:
    """Top-level, real-device-contact entry point (task S6 §1). Input is one
    explicitly selected PAN HA operational entity and its bounded physical
    members -- never a fleet/inventory scan (task S6 §3/§25).

    `B2` (bidirectional pair-identity corroboration) is never established
    here -- this collector produces per-member evidence only; pair
    identity/coherence remains `utils.failover.preflight_model`'s and a
    future readiness slice's job (task S6 §6).
    """
    if not members:
        raise PANPreflightCollectionError("run_pan_preflight requires at least one selected physical member")
    if len(members) > MAX_PHYSICAL_MEMBERS:
        raise PANPreflightCollectionError(
            f"run_pan_preflight received {len(members)} members; bounded maximum is {MAX_PHYSICAL_MEMBERS} "
            "(one explicitly selected HA entity, no fleet expansion)"
        )
    preflight_run_id = str(uuid.uuid4())
    member_evidence = [
        collect_member(
            username=username, secret=secret, target=member,
            preflight_run_id=preflight_run_id, operational_entity_id=operational_entity_id,
            verify=verify, timeout=timeout,
        )
        for member in members
    ]
    return PreflightSnapshot(
        operational_unit_id=operational_entity_id,
        vendor="panorama",
        unit_type="ha_pair",
        preflight_run_id=preflight_run_id,
        members=tuple(member_evidence),
        configuration_facts=(),
    )
