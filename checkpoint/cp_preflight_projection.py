"""SecurityExpert — OP.0b S3, Check Point preflight fact projection.

Contract: `docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
(status: FROZEN WITH REAL-ENV VALIDATION GATES) — Implementation slices, S3.

Pure projection seam: turns the field dict a caller builds from the same
already-fetched ``cphaprob stat`` buffer
`configuration.checkpoint_config_collector._collect_host` (physical path) and
its per-VS probe already hold into
`utils.failover.preflight_model.PreflightFact` instances. A typical caller
builds `fields` as
``{"local_role": host_row["ha_role"], "cluster_mode": host_row["ha_cluster_mode"], **host_row.get("preflight_fields", {})}``
(or the equivalent `ctx_row` keys for a VS-context observation).

This module:

- performs **no** I/O, issues **no** command; it imports no SSH, network, or
  collector code — only `utils.failover.preflight_model` types;
- computes **no** readiness verdict and decides no PASS/FAIL/healthy/
  unhealthy from any parsed value. Every present field becomes a `KNOWN`
  fact carrying exactly the safe value that was read; an absent field
  becomes `UNKNOWN`. Interpreting a value is a future readiness slice's job
  (S7), never this one's;
- does not create, modify, or read CP cluster/VSX operational identity,
  `cluster_topology.group_id`, or any readiness-verdict path;
- projects a non-local member row's state as this member's *claim* about
  its peer (category E, `peer_claim_facts`) — never `own_facts`, never used
  to form or confirm an operational pair (contract domain invariant 4);
- projects no fact at all for a category the current `cphaprob stat` read
  cannot satisfy (state/session sync, pnote health, policy/software parity,
  control/sync link health, configured preemption, failover history) — per
  task S3 §13, these stay `NOT_COLLECTED` by never being asserted, not by a
  manufactured empty/UNKNOWN placeholder fact.
"""
from __future__ import annotations

import hashlib
from typing import Any, Mapping, Sequence

from utils.failover.preflight_model import (
    FactCategory,
    FactContext,
    FactState,
    OpaqueToken,
    Outcome,
    PreflightFact,
    PreflightMemberEvidence,
    Provenance,
    ShellProfile,
    SourceOrigin,
    Transport,
)

__all__ = [
    "project_cp_preflight_facts",
    "project_cp_software_version_fact",
    "project_cp_link_health_facts",
    "project_cp_pnote_facts",
    "project_cp_sync_facts",
    "project_cp_policy_facts",
    "project_cp_failover_history_facts",
    "project_cp_vsx_enumeration_facts",
]

#: The parser's own fail-closed sentinel (`CLUSTERXL_CLUSTER_MODES`), never a
#: positive KNOWN value -- an unrecognized/absent mode stays UNKNOWN, never a
#: guess (contract §12: "Any contradiction or unknown mode: UNKNOWN. Never infer.").
_UNKNOWN_MODE_SENTINEL = "unknown"


def project_cp_preflight_facts(
    fields: Mapping[str, Any] | None,
    *,
    preflight_run_id: str,
    collected_at: str,
    physical_device_identity: str,
    operational_entity_id: str,
    transport: Transport = Transport.SSH_DIRECT,
    source_command: str = "cphaprob stat",
    shell_profile: ShellProfile | None = None,
    context: FactContext | None = None,
    outcome: Outcome = Outcome.SUCCESS,
) -> PreflightMemberEvidence:
    """Project one member's already-parsed ``cphaprob stat`` fields into a
    `PreflightMemberEvidence`.

    `fields` carries ``local_role`` (one of
    `checkpoint_config_collector.CLUSTERXL_RUNTIME_STATES` or `None`),
    ``cluster_mode`` (one of `checkpoint_config_collector.CLUSTERXL_CLUSTER_MODES`),
    ``peer_row_states`` (a sequence of state tokens, one per observed
    non-local member row, possibly empty) and ``local_attention`` (a bool or
    `None`) — the shape `_parse_clusterxl_stat_preflight_fields` returns,
    merged with the two always-parsed leaves as documented above.

    Pass `fields=None` to represent a collection failure explicitly: every
    fact becomes `FactState.COLLECTION_FAILED`, never silently omitted and
    never inferred as unhealthy.

    Every fact shares the one caller-supplied `preflight_run_id`/
    `collected_at`/`outcome`/`transport`/`source_command`/`shell_profile`/
    `context` — this function does not invent, derive, or vary any of them
    per-fact; S5/S6 own creating and orchestrating those values for a real
    preflight run. `context` distinguishes a physical-member observation
    from a VSID observation (contract §11) — the caller must pass the
    correct one; this function performs no VS/physical disambiguation
    itself and inherits nothing from any other call.
    """
    ctx = context or FactContext.physical()

    def _provenance() -> Provenance:
        return Provenance(
            collected_at=collected_at,
            preflight_run_id=preflight_run_id,
            source_vendor="checkpoint",
            source_plane=SourceOrigin.DEVICE_RUNTIME,
            transport=transport,
            physical_device_identity=OpaqueToken(physical_device_identity),
            operational_entity_id=operational_entity_id,
            context=ctx,
            outcome=outcome,
            source_command=source_command,
            shell_profile=shell_profile,
        )

    own_facts: list[PreflightFact] = []
    peer_claim_facts: list[PreflightFact] = []

    # -- local role (category D, RUNTIME_HA_STATE) --------------------------
    if fields is None:
        own_facts.append(
            PreflightFact(
                name="ha_local_role", category=FactCategory.RUNTIME_HA_STATE,
                state=FactState.COLLECTION_FAILED, value=None, provenance=_provenance(),
            )
        )
    else:
        local_role = fields.get("local_role")
        if local_role is None:
            own_facts.append(
                PreflightFact(
                    name="ha_local_role", category=FactCategory.RUNTIME_HA_STATE,
                    state=FactState.UNKNOWN, value=None, provenance=_provenance(),
                )
            )
        else:
            own_facts.append(
                PreflightFact(
                    name="ha_local_role", category=FactCategory.RUNTIME_HA_STATE,
                    state=FactState.KNOWN, value=str(local_role), provenance=_provenance(),
                )
            )

    # -- cluster mode (category D, RUNTIME_HA_STATE) -------------------------
    if fields is None:
        own_facts.append(
            PreflightFact(
                name="ha_cluster_mode", category=FactCategory.RUNTIME_HA_STATE,
                state=FactState.COLLECTION_FAILED, value=None, provenance=_provenance(),
            )
        )
    else:
        cluster_mode = fields.get("cluster_mode")
        if not cluster_mode or cluster_mode == _UNKNOWN_MODE_SENTINEL:
            own_facts.append(
                PreflightFact(
                    name="ha_cluster_mode", category=FactCategory.RUNTIME_HA_STATE,
                    state=FactState.UNKNOWN, value=None, provenance=_provenance(),
                )
            )
        else:
            own_facts.append(
                PreflightFact(
                    name="ha_cluster_mode", category=FactCategory.RUNTIME_HA_STATE,
                    state=FactState.KNOWN, value=str(cluster_mode), provenance=_provenance(),
                )
            )

    # -- local member failure/attention state (category J, corroborating D) --
    if fields is None:
        own_facts.append(
            PreflightFact(
                name="local_member_attention", category=FactCategory.FAILURE_HEALTH_STATE,
                state=FactState.COLLECTION_FAILED, value=None, provenance=_provenance(),
            )
        )
    else:
        local_attention = fields.get("local_attention")
        if local_attention is None:
            own_facts.append(
                PreflightFact(
                    name="local_member_attention", category=FactCategory.FAILURE_HEALTH_STATE,
                    state=FactState.UNKNOWN, value=None, provenance=_provenance(),
                )
            )
        else:
            own_facts.append(
                PreflightFact(
                    name="local_member_attention", category=FactCategory.FAILURE_HEALTH_STATE,
                    state=FactState.KNOWN, value=bool(local_attention), provenance=_provenance(),
                )
            )

    # -- peer/member row states: this member's CLAIM about its peer(s) -------
    # (category E, PEER_IDENTITY_RELATIONSHIP; contract domain invariant 4)
    if fields is None:
        peer_claim_facts.append(
            PreflightFact(
                name="peer_row_state_1", category=FactCategory.PEER_IDENTITY_RELATIONSHIP,
                state=FactState.COLLECTION_FAILED, value=None, provenance=_provenance(),
            )
        )
    else:
        peer_row_states: Sequence[Any] = fields.get("peer_row_states") or ()
        if not peer_row_states:
            # Only the local row was present (or none was) -- never
            # synthesize a peer observation from that absence (task S3 §17
            # test 10); represent it as one explicit UNKNOWN claim slot.
            peer_claim_facts.append(
                PreflightFact(
                    name="peer_row_state_1", category=FactCategory.PEER_IDENTITY_RELATIONSHIP,
                    state=FactState.UNKNOWN, value=None, provenance=_provenance(),
                )
            )
        else:
            for index, state in enumerate(peer_row_states, start=1):
                peer_claim_facts.append(
                    PreflightFact(
                        name=f"peer_row_state_{index}", category=FactCategory.PEER_IDENTITY_RELATIONSHIP,
                        state=FactState.KNOWN, value=str(state), provenance=_provenance(),
                    )
                )

    return PreflightMemberEvidence(
        physical_device_identity=OpaqueToken(physical_device_identity),
        own_facts=tuple(own_facts),
        peer_claim_facts=tuple(peer_claim_facts),
    )


# --- S5 additions: one projection function per newly-approved command ------
#
# Same discipline as `project_cp_preflight_facts` above: no I/O, no readiness
# verdict, one caller-supplied `preflight_run_id`/`collected_at`/`outcome`/
# `transport`/`shell_profile`/`context` shared by every fact a call produces.
# Each function owns exactly one command's evidence and carries that
# command's own symbolic `source_command` id -- never a generic "cphaprob"
# label (contract "Provenance", §20).


def _provenance(
    *,
    preflight_run_id: str,
    collected_at: str,
    physical_device_identity: str,
    operational_entity_id: str,
    transport: Transport,
    source_command: str,
    shell_profile: ShellProfile | None,
    context: FactContext | None,
    outcome: Outcome,
) -> Provenance:
    return Provenance(
        collected_at=collected_at,
        preflight_run_id=preflight_run_id,
        source_vendor="checkpoint",
        source_plane=SourceOrigin.DEVICE_RUNTIME,
        transport=transport,
        physical_device_identity=OpaqueToken(physical_device_identity),
        operational_entity_id=operational_entity_id,
        context=context or FactContext.physical(),
        outcome=outcome,
        source_command=source_command,
        shell_profile=shell_profile,
    )


def project_cp_software_version_fact(
    sw_version: str | None,
    *,
    preflight_run_id: str,
    collected_at: str,
    physical_device_identity: str,
    operational_entity_id: str,
    transport: Transport = Transport.SSH_DIRECT,
    shell_profile: ShellProfile | None = None,
    context: FactContext | None = None,
    outcome: Outcome = Outcome.SUCCESS,
) -> PreflightFact:
    """CP-A2 (`show version all`) -- software version, category H. Feeds the
    software half of check 3 (policy/software parity); the policy half comes
    from `project_cp_policy_facts` (CP-A7) separately -- a CP-A7 failure
    never erases this fact (contract CP-A7 "Failure semantics")."""
    prov = _provenance(
        preflight_run_id=preflight_run_id, collected_at=collected_at,
        physical_device_identity=physical_device_identity, operational_entity_id=operational_entity_id,
        transport=transport, source_command="A2", shell_profile=shell_profile, context=context, outcome=outcome,
    )
    if outcome is not Outcome.SUCCESS:
        return PreflightFact(
            name="cp_software_version", category=FactCategory.SOFTWARE_POLICY_CONTENT_PARITY,
            state=FactState.COLLECTION_FAILED, value=None, provenance=prov,
        )
    if not sw_version:
        return PreflightFact(
            name="cp_software_version", category=FactCategory.SOFTWARE_POLICY_CONTENT_PARITY,
            state=FactState.UNKNOWN, value=None, provenance=prov,
        )
    return PreflightFact(
        name="cp_software_version", category=FactCategory.SOFTWARE_POLICY_CONTENT_PARITY,
        state=FactState.KNOWN, value=str(sw_version), provenance=prov,
    )


def project_cp_link_health_facts(
    parsed: Mapping[str, Any] | None,
    *,
    preflight_run_id: str,
    collected_at: str,
    physical_device_identity: str,
    operational_entity_id: str,
    transport: Transport = Transport.SSH_DIRECT,
    shell_profile: ShellProfile | None = None,
    context: FactContext | None = None,
    outcome: Outcome = Outcome.SUCCESS,
) -> tuple[PreflightFact, ...]:
    """CP-A4 (`cphaprob -a if`) -- link health, category F. `parsed` is the
    dict `cp_preflight_extraction.parse_cphaprob_a_if` returns; pass
    `parsed=None` for an explicit `COLLECTION_FAILED` (command never ran or
    the read itself failed)."""
    prov = _provenance(
        preflight_run_id=preflight_run_id, collected_at=collected_at,
        physical_device_identity=physical_device_identity, operational_entity_id=operational_entity_id,
        transport=transport, source_command="A4", shell_profile=shell_profile, context=context, outcome=outcome,
    )
    if parsed is None or not parsed.get("observed"):
        state = FactState.COLLECTION_FAILED if outcome is not Outcome.SUCCESS else FactState.UNKNOWN
        return (
            PreflightFact(name="cp_link_any_down", category=FactCategory.LINK_HEALTH, state=state, value=None, provenance=prov),
        )
    any_down = parsed.get("any_down")
    facts = [
        PreflightFact(
            name="cp_link_any_down", category=FactCategory.LINK_HEALTH,
            state=FactState.KNOWN if any_down is not None else FactState.UNKNOWN,
            value=bool(any_down) if any_down is not None else None, provenance=prov,
        ),
    ]
    count = parsed.get("interface_count")
    if count is not None:
        facts.append(
            PreflightFact(
                name="cp_link_interface_count", category=FactCategory.LINK_HEALTH,
                state=FactState.KNOWN, value=int(count), provenance=prov,
            )
        )
    return tuple(facts)


def project_cp_pnote_facts(
    parsed: Mapping[str, Any] | None,
    *,
    preflight_run_id: str,
    collected_at: str,
    physical_device_identity: str,
    operational_entity_id: str,
    transport: Transport = Transport.SSH_DIRECT,
    shell_profile: ShellProfile | None = None,
    context: FactContext | None = None,
    outcome: Outcome = Outcome.SUCCESS,
) -> tuple[PreflightFact, ...]:
    """CP-A5 (`cphaprob -ia list`) -- critical-device (pnote) problem state,
    category J. Read failure -> `UNKNOWN` for check 8, never `KNOWN_BAD`
    from absence (gate CP-A5 "Failure semantics"; frozen contract D-V6)."""
    prov = _provenance(
        preflight_run_id=preflight_run_id, collected_at=collected_at,
        physical_device_identity=physical_device_identity, operational_entity_id=operational_entity_id,
        transport=transport, source_command="A5", shell_profile=shell_profile, context=context, outcome=outcome,
    )
    if parsed is None or not parsed.get("observed") or parsed.get("any_problem") is None:
        state = FactState.COLLECTION_FAILED if outcome is not Outcome.SUCCESS else FactState.UNKNOWN
        return (
            PreflightFact(name="cp_pnote_any_problem", category=FactCategory.FAILURE_HEALTH_STATE, state=state, value=None, provenance=prov),
        )
    facts = [
        PreflightFact(
            name="cp_pnote_any_problem", category=FactCategory.FAILURE_HEALTH_STATE,
            state=FactState.KNOWN, value=bool(parsed["any_problem"]), provenance=prov,
        ),
    ]
    if parsed.get("device_count") is not None:
        facts.append(
            PreflightFact(
                name="cp_pnote_device_count", category=FactCategory.FAILURE_HEALTH_STATE,
                state=FactState.KNOWN, value=int(parsed["device_count"]), provenance=prov,
            )
        )
    return tuple(facts)


def project_cp_sync_facts(
    parsed: Mapping[str, Any] | None,
    *,
    preflight_run_id: str,
    collected_at: str,
    physical_device_identity: str,
    operational_entity_id: str,
    dispatch_form: str | None,
    transport: Transport = Transport.SSH_DIRECT,
    shell_profile: ShellProfile | None = None,
    context: FactContext | None = None,
    outcome: Outcome = Outcome.SUCCESS,
) -> tuple[PreflightFact, ...]:
    """CP-A6 (`cphaprob syncstat` / `fw ctl pstat`, version-dispatched) --
    state/session sync, category G. `dispatch_form=None` means the caller
    could not prove capability/version evidence before execution and never
    ran either form -- the fact becomes `CAPABILITY_GAP`, per the gate's
    binding "no failure-driven fallback" rule (never attempted-then-switched)."""
    source_command = "A6" if dispatch_form is None else f"A6:{dispatch_form}"
    prov = _provenance(
        preflight_run_id=preflight_run_id, collected_at=collected_at,
        physical_device_identity=physical_device_identity, operational_entity_id=operational_entity_id,
        transport=transport, source_command=source_command, shell_profile=shell_profile, context=context,
        outcome=Outcome.CAPABILITY_GAP if dispatch_form is None else outcome,
    )
    if dispatch_form is None:
        return (
            PreflightFact(name="cp_sync_status", category=FactCategory.STATE_SESSION_SYNCHRONIZATION, state=FactState.UNSUPPORTED, value=None, provenance=prov),
        )
    if parsed is None or not parsed.get("observed") or parsed.get("status") is None:
        state = FactState.COLLECTION_FAILED if outcome is not Outcome.SUCCESS else FactState.UNKNOWN
        return (
            PreflightFact(name="cp_sync_status", category=FactCategory.STATE_SESSION_SYNCHRONIZATION, state=state, value=None, provenance=prov),
        )
    return (
        PreflightFact(
            name="cp_sync_status", category=FactCategory.STATE_SESSION_SYNCHRONIZATION,
            state=FactState.KNOWN, value=str(parsed["status"]), provenance=prov,
        ),
    )


def project_cp_policy_facts(
    parsed: Mapping[str, Any] | None,
    *,
    preflight_run_id: str,
    collected_at: str,
    physical_device_identity: str,
    operational_entity_id: str,
    transport: Transport = Transport.SSH_DIRECT,
    shell_profile: ShellProfile | None = None,
    context: FactContext | None = None,
    outcome: Outcome = Outcome.SUCCESS,
) -> tuple[PreflightFact, ...]:
    """CP-A7 (`fw stat`, physical only) -- installed policy identity,
    category H. The policy name is tokenized (sha256, truncated) here and
    never retained raw (gate CP-A7 "Safe retained fields": "opaque token
    used only for equality comparison")."""
    prov = _provenance(
        preflight_run_id=preflight_run_id, collected_at=collected_at,
        physical_device_identity=physical_device_identity, operational_entity_id=operational_entity_id,
        transport=transport, source_command="A7", shell_profile=shell_profile, context=context, outcome=outcome,
    )
    if parsed is None or not parsed.get("observed") or not parsed.get("policy_name"):
        state = FactState.COLLECTION_FAILED if outcome is not Outcome.SUCCESS else FactState.UNKNOWN
        return (
            PreflightFact(name="cp_installed_policy_token", category=FactCategory.SOFTWARE_POLICY_CONTENT_PARITY, state=state, value=None, provenance=prov),
        )
    digest = hashlib.sha256(str(parsed["policy_name"]).encode("utf-8")).hexdigest()[:16]
    return (
        PreflightFact(
            name="cp_installed_policy_token", category=FactCategory.SOFTWARE_POLICY_CONTENT_PARITY,
            state=FactState.KNOWN, value=OpaqueToken(digest), provenance=prov,
        ),
    )


#: Bounded, known-safe failover-count clamp -- mirrors
#: `cp_preflight_extraction._MAX_SAFE_COUNT`; defends this module too if a
#: caller ever passes an unclamped value from a different source.
_MAX_SAFE_FAILOVER_COUNT = 10_000


def project_cp_failover_history_facts(
    parsed: Mapping[str, Any] | None,
    *,
    preflight_run_id: str,
    collected_at: str,
    physical_device_identity: str,
    operational_entity_id: str,
    dispatch_form: str | None,
    transport: Transport = Transport.SSH_DIRECT,
    shell_profile: ShellProfile | None = None,
    context: FactContext | None = None,
    outcome: Outcome = Outcome.SUCCESS,
) -> tuple[PreflightFact, ...]:
    """CP-A8 (`show cluster failover` / `cphaprob show_failover`,
    platform-dispatched) -- flap/failover history, category K. Never
    produces a PASS/healthy verdict from the count (`D-F3` open);
    `dispatch_form=None` means platform evidence was insufficient to choose
    a form and neither was executed -- `CAPABILITY_GAP`, not a fallback
    attempt (gate CP-A8 "Also binding")."""
    source_command = "A8" if dispatch_form is None else f"A8:{dispatch_form}"
    prov = _provenance(
        preflight_run_id=preflight_run_id, collected_at=collected_at,
        physical_device_identity=physical_device_identity, operational_entity_id=operational_entity_id,
        transport=transport, source_command=source_command, shell_profile=shell_profile, context=context,
        outcome=Outcome.CAPABILITY_GAP if dispatch_form is None else outcome,
    )
    if dispatch_form is None:
        return (
            PreflightFact(name="cp_failover_count", category=FactCategory.TRANSITION_FLAP_HISTORY, state=FactState.UNSUPPORTED, value=None, provenance=prov),
        )
    if parsed is None or not parsed.get("observed"):
        state = FactState.COLLECTION_FAILED if outcome is not Outcome.SUCCESS else FactState.UNKNOWN
        return (
            PreflightFact(name="cp_failover_count", category=FactCategory.TRANSITION_FLAP_HISTORY, state=state, value=None, provenance=prov),
        )
    facts: list[PreflightFact] = []
    count = parsed.get("count")
    if count is not None and 0 <= int(count) <= _MAX_SAFE_FAILOVER_COUNT:
        facts.append(
            PreflightFact(name="cp_failover_count", category=FactCategory.TRANSITION_FLAP_HISTORY, state=FactState.KNOWN, value=int(count), provenance=prov)
        )
    else:
        facts.append(
            PreflightFact(name="cp_failover_count", category=FactCategory.TRANSITION_FLAP_HISTORY, state=FactState.UNKNOWN, value=None, provenance=prov)
        )
    reason_class = parsed.get("last_reason_class")
    facts.append(
        PreflightFact(
            name="cp_failover_last_reason", category=FactCategory.TRANSITION_FLAP_HISTORY,
            state=FactState.KNOWN if reason_class else FactState.UNKNOWN,
            value=str(reason_class) if reason_class else None, provenance=prov,
        )
    )
    last_event_time = parsed.get("last_event_time")
    facts.append(
        PreflightFact(
            name="cp_failover_last_event_time", category=FactCategory.TRANSITION_FLAP_HISTORY,
            state=FactState.KNOWN if last_event_time else FactState.UNKNOWN,
            value=str(last_event_time) if last_event_time else None, provenance=prov,
        )
    )
    return tuple(facts)


#: Bounded VS enumeration retained per member -- a defensive cap against a
#: malformed/looping response shape, not a real-world VS count expectation.
_MAX_RETAINED_VS_ROWS = 64


def project_cp_vsx_enumeration_facts(
    parsed: Mapping[str, Any] | None,
    *,
    preflight_run_id: str,
    collected_at: str,
    physical_device_identity: str,
    operational_entity_id: str,
    transport: Transport = Transport.SSH_DIRECT,
    shell_profile: ShellProfile | None = None,
    context: FactContext | None = None,
    outcome: Outcome = Outcome.SUCCESS,
) -> tuple[PreflightFact, ...]:
    """CP-B1 (`vsx stat -v`, VSX battery only) -- VS enumeration, category B
    (`OPERATIONAL_HA_ENTITY_IDENTITY` in the S1 taxonomy). One fact per
    observed VSID + a count fact; VS *names* are never retained (gate CP-B1
    "Safe retained fields"). A subordinate VS is never turned into a CLASS 2
    execution target by this or any later step (contract domain invariant 9
    / task §10/§16) -- this function only records enumeration/status."""
    prov = _provenance(
        preflight_run_id=preflight_run_id, collected_at=collected_at,
        physical_device_identity=physical_device_identity, operational_entity_id=operational_entity_id,
        transport=transport, source_command="B1", shell_profile=shell_profile, context=context, outcome=outcome,
    )
    if parsed is None or not parsed.get("observed"):
        state = FactState.COLLECTION_FAILED if outcome is not Outcome.SUCCESS else FactState.UNKNOWN
        return (
            PreflightFact(name="cp_vsx_vs_count", category=FactCategory.OPERATIONAL_HA_ENTITY_IDENTITY, state=state, value=None, provenance=prov),
        )
    rows = list(parsed.get("vs_rows") or [])[:_MAX_RETAINED_VS_ROWS]
    facts = [
        PreflightFact(
            name="cp_vsx_vs_count", category=FactCategory.OPERATIONAL_HA_ENTITY_IDENTITY,
            state=FactState.KNOWN, value=len(rows), provenance=prov,
        ),
    ]
    for row in rows:
        vsid = str(row.get("vsid") or "")
        if not vsid:
            continue
        status = row.get("status")
        facts.append(
            PreflightFact(
                name=f"cp_vsx_vs_{vsid}_status", category=FactCategory.OPERATIONAL_HA_ENTITY_IDENTITY,
                state=FactState.KNOWN if status else FactState.UNKNOWN,
                value=str(status) if status else None, provenance=prov,
            )
        )
    return tuple(facts)
