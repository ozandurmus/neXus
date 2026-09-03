"""SecurityExpert — OP.0b S2, PAN preflight fact projection.

Contract: `docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
(status: FROZEN WITH REAL-ENV VALIDATION GATES) — Implementation slices, S2.

Pure projection seam: turns the field dict
`configuration.panorama_config_collector._parse_pan_ha_preflight_fields`
(plus the five leaves `get_target_ha_runtime_state` always parses) already
extracted from one already-fetched `show high-availability state` response
into `utils.failover.preflight_model.PreflightFact` instances.

This module:

- performs **no** I/O, issues **no** command; it imports no XML, network, or
  collector code — only `utils.failover.preflight_model` types;
- computes **no** readiness verdict and decides no PASS/FAIL/healthy/
  unhealthy from any parsed value. Every present field becomes a `KNOWN`
  fact carrying exactly the safe value that was read; an absent field
  becomes `UNKNOWN`. Interpreting a value (e.g. "`conn-status == up` means
  healthy") is a future readiness slice's job (S7), never this one's;
- applies **no** `D-F1`/`D-F2`/`D-F3` threshold to any counter it carries;
- does not create, modify, or read PAN pair identity, `_derive_pan_units`,
  or any readiness-verdict path. `peer-info/serial-num` is projected as this
  member's *claim* about its peer (category E, `peer_claim_facts`) — never
  `own_facts`, never used to form or confirm an operational pair.
"""
from __future__ import annotations

from typing import Any, Mapping

from utils.failover.preflight_model import (
    FactCategory,
    FactContext,
    FactState,
    OpaqueToken,
    Outcome,
    PreflightFact,
    PreflightMemberEvidence,
    Provenance,
    SourceOrigin,
    Transport,
)

__all__ = ["project_pan_preflight_facts"]

#: (field key in the merged parsed dict, PreflightFact name, category,
#: is_peer_claim). `is_peer_claim=True` -> lands in `peer_claim_facts`, never
#: `own_facts` (contract domain invariant 4: a member's report about its
#: peer is that member's claim, never an independent observation).
_RUNTIME_STATE_FIELDS: tuple[tuple[str, str, FactCategory, bool], ...] = (
    ("enabled", "ha_enabled", FactCategory.RUNTIME_HA_STATE, False),
    ("state", "local_state", FactCategory.RUNTIME_HA_STATE, False),
    ("mode", "local_mode", FactCategory.RUNTIME_HA_STATE, False),
    ("peer_state", "peer_state_claim", FactCategory.PEER_IDENTITY_RELATIONSHIP, True),
    ("state_sync", "local_state_sync", FactCategory.STATE_SESSION_SYNCHRONIZATION, False),
)

#: `running_sync`/`running_sync_enabled` are grouped with the contract's
#: check-3 "parity" family (config-sync belongs there per the frozen
#: contract's evidence-per-check table), hence H, not G/C -- a judgment call
#: documented here rather than made silently, since the taxonomy has no
#: dedicated "config sync status" letter.
_PREFLIGHT_FIELDS: tuple[tuple[str, str, FactCategory, bool], ...] = (
    ("running_sync", "group_running_sync", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY, False),
    ("running_sync_enabled", "group_running_sync_enabled", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY, False),
    ("local_state_sync_type", "local_state_sync_type", FactCategory.STATE_SESSION_SYNCHRONIZATION, False),
    ("local_preemptive", "local_preemptive", FactCategory.ELECTION_PREEMPTION_BEHAVIOR, False),
    ("local_priority", "local_priority", FactCategory.ELECTION_PREEMPTION_BEHAVIOR, False),
    ("local_preempt_hold", "local_preempt_hold", FactCategory.ELECTION_PREEMPTION_BEHAVIOR, False),
    ("local_promotion_hold", "local_promotion_hold", FactCategory.ELECTION_PREEMPTION_BEHAVIOR, False),
    ("local_max_flaps", "local_max_flaps", FactCategory.TRANSITION_FLAP_HISTORY, False),
    ("local_nonfunc_flap_cnt", "local_nonfunc_flap_cnt", FactCategory.TRANSITION_FLAP_HISTORY, False),
    ("local_preempt_flap_cnt", "local_preempt_flap_cnt", FactCategory.TRANSITION_FLAP_HISTORY, False),
    ("local_state_duration", "local_state_duration", FactCategory.TRANSITION_FLAP_HISTORY, False),
    ("local_last_error_reason", "local_last_error_reason", FactCategory.FAILURE_HEALTH_STATE, False),
    ("local_last_error_state", "local_last_error_state", FactCategory.FAILURE_HEALTH_STATE, False),
    ("local_build_rel", "local_build_rel", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY, False),
    ("local_app_version", "local_app_version", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY, False),
    ("local_app_compat", "local_app_compat", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY, False),
    ("local_av_version", "local_av_version", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY, False),
    ("local_av_compat", "local_av_compat", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY, False),
    ("local_threat_version", "local_threat_version", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY, False),
    ("local_threat_compat", "local_threat_compat", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY, False),
    ("local_url_version", "local_url_version", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY, False),
    ("local_url_compat", "local_url_compat", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY, False),
    ("peer_conn_status", "peer_conn_status", FactCategory.LINK_HEALTH, True),
    ("peer_conn_ha1_status", "peer_conn_ha1_status", FactCategory.LINK_HEALTH, True),
    ("peer_conn_ha1_backup_status", "peer_conn_ha1_backup_status", FactCategory.LINK_HEALTH, True),
    ("peer_conn_ha2_status", "peer_conn_ha2_status", FactCategory.LINK_HEALTH, True),
    ("peer_build_rel", "peer_build_rel", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY, True),
    ("peer_app_version", "peer_app_version", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY, True),
    ("peer_av_version", "peer_av_version", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY, True),
    ("peer_threat_version", "peer_threat_version", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY, True),
    ("peer_url_version", "peer_url_version", FactCategory.SOFTWARE_POLICY_CONTENT_PARITY, True),
)

#: Fact names whose raw text is a safe-to-convert bounded counter. Conversion
#: failure (a malformed field) degrades to UNKNOWN, never a crash and never a
#: silently-wrong value -- no D-F3/D-F2 threshold is applied to the resulting
#: number here, only the type conversion itself.
_NUMERIC_FACT_NAMES = frozenset({
    "local_priority", "local_preempt_hold", "local_promotion_hold",
    "local_max_flaps", "local_nonfunc_flap_cnt", "local_preempt_flap_cnt",
    "local_state_duration",
})

_LOCAL_SERIAL_FIELD = "local_serial_num"
_PEER_SERIAL_FIELD = "peer_serial_num"


def project_pan_preflight_facts(
    fields: Mapping[str, Any] | None,
    *,
    preflight_run_id: str,
    collected_at: str,
    physical_device_identity: str,
    operational_entity_id: str,
    transport: Transport = Transport.PANORAMA_API_PROXY,
    source_command: str = "show high-availability state",
    context: FactContext | None = None,
    outcome: Outcome = Outcome.SUCCESS,
) -> PreflightMemberEvidence:
    """Project one member's already-parsed PAN HA-state fields into a
    `PreflightMemberEvidence`.

    `fields` is the merged dict of `get_target_ha_runtime_state`'s five
    always-parsed leaves (`enabled`, `state`, `mode`, `peer_state`,
    `state_sync`) plus its `preflight_fields` (present only when
    `include_preflight_fields=True` was passed) — a typical caller builds it
    as ``{**ha_runtime_result, **ha_runtime_result.get("preflight_fields", {})}``.
    Pass `fields=None` to represent a collection failure explicitly: every
    fact becomes `FactState.COLLECTION_FAILED`, never silently omitted and
    never inferred as unhealthy.

    Every fact shares the one caller-supplied `preflight_run_id`/
    `collected_at`/`outcome`/`transport`/`source_command`/`context` — this
    function does not invent, derive, or vary any of them per-fact; S5/S6
    own creating and orchestrating those values for a real collection run.
    """
    ctx = context or FactContext.physical()

    def _provenance() -> Provenance:
        return Provenance(
            collected_at=collected_at,
            preflight_run_id=preflight_run_id,
            source_vendor="panorama",
            source_plane=SourceOrigin.DEVICE_RUNTIME,
            transport=transport,
            physical_device_identity=OpaqueToken(physical_device_identity),
            operational_entity_id=operational_entity_id,
            context=ctx,
            outcome=outcome,
            source_command=source_command,
        )

    def _fact(name: str, category: FactCategory, raw_value: Any, *, is_identity: bool = False) -> PreflightFact:
        if fields is None:
            state = FactState.COLLECTION_FAILED
        elif raw_value is None:
            state = FactState.UNKNOWN
        elif name in _NUMERIC_FACT_NAMES:
            try:
                raw_value = int(raw_value)
            except (TypeError, ValueError):
                state = FactState.UNKNOWN
                raw_value = None
            else:
                state = FactState.KNOWN
        else:
            state = FactState.KNOWN

        value: Any = None
        if state is FactState.KNOWN:
            value = OpaqueToken(raw_value) if is_identity else raw_value
        return PreflightFact(name=name, category=category, state=state, value=value, provenance=_provenance())

    own_facts: list[PreflightFact] = []
    peer_claim_facts: list[PreflightFact] = []

    for source_key, fact_name, category, is_peer_claim in (*_RUNTIME_STATE_FIELDS, *_PREFLIGHT_FIELDS):
        raw = None if fields is None else fields.get(source_key)
        fact = _fact(fact_name, category, raw)
        (peer_claim_facts if is_peer_claim else own_facts).append(fact)

    # local-info/serial-num: this member's own runtime self-report (category
    # A, corroborating only -- D-V3a unresolved, never authoritative).
    local_raw = None if fields is None else fields.get(_LOCAL_SERIAL_FIELD)
    own_facts.append(_fact("local_serial_claim", FactCategory.PHYSICAL_IDENTITY, local_raw, is_identity=True))

    # peer-info/serial-num: this member's CLAIM about its peer (category E,
    # peer_claim_facts) -- never own_facts, never used to form/confirm a pair.
    peer_raw = None if fields is None else fields.get(_PEER_SERIAL_FIELD)
    peer_claim_facts.append(
        _fact("peer_serial_claim", FactCategory.PEER_IDENTITY_RELATIONSHIP, peer_raw, is_identity=True)
    )

    return PreflightMemberEvidence(
        physical_device_identity=OpaqueToken(physical_device_identity),
        own_facts=tuple(own_facts),
        peer_claim_facts=tuple(peer_claim_facts),
    )
