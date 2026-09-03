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

__all__ = ["project_cp_preflight_facts"]

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
