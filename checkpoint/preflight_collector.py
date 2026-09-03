"""SecurityExpert -- OP.0b S5, Check Point dedicated preflight collector.

Contract chain: `docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
(FROZEN WITH REAL-ENV VALIDATION GATES) -> `docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md`
(APPROVED (2026-09-03), "Approval record") -- the sole implementation
authorities for this module.

Responsibility (task S5 §1):

    selected HA operational entity
        -> resolve bounded physical members (caller-supplied, <=2)
        -> create ONE preflight_run_id
        -> establish/reuse ONE controlled SSH session per physical member
        -> perform the approved read battery (`checkpoint.cp_preflight_battery`)
        -> parse safe derived evidence (`checkpoint.cp_preflight_extraction`)
        -> project S1 PreflightFact / Provenance (`checkpoint.cp_preflight_projection`)
        -> assemble member evidence / snapshot (`utils.failover.preflight_model`)
        -> return evidence

This module MUST NOT and does NOT: calculate failover readiness, authorize
an action, execute failover, modify configuration, or persist raw command
output. No `SAFE_TO_FAILOVER`/readiness verdict is emitted here -- `S7` owns
readiness integration. No new SSH transport, credential path, or command
retry is introduced; `MemberSession.run` issues exactly one invocation per
call and callers must not loop it. `B1` opens no second session -- it is
just another entry in the same member's fixed schedule, run over the same
`MemberSession` object as every other read.
"""
from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Callable, Sequence

from checkpoint.cp_preflight_battery import (
    COMMAND_TEXT,
    CPPreflightRead,
    resolve_a6_form,
    resolve_a8_form,
)
from checkpoint.cp_preflight_extraction import (
    parse_cp_failover_history,
    parse_cp_sync_status,
    parse_cphaprob_a_if,
    parse_cphaprob_ia_list,
    parse_fw_stat_policy,
    parse_vsx_stat_v,
)
from checkpoint.cp_preflight_projection import (
    project_cp_failover_history_facts,
    project_cp_link_health_facts,
    project_cp_pnote_facts,
    project_cp_policy_facts,
    project_cp_preflight_facts,
    project_cp_software_version_fact,
    project_cp_sync_facts,
    project_cp_vsx_enumeration_facts,
)
from configuration.checkpoint_config_collector import (
    _classify_platform,
    _parse_clusterxl_stat_preflight_fields,
    _parse_gaia_version,
)
from configuration.checkpoint_config_probe import (
    ProbeTarget,
    _connect,
    _identity_gate,
    _parse_hostname,
    _run_exec,
)
from utils.failover.preflight_model import (
    FactCategory,
    FactContext,
    FactState,
    OpaqueToken,
    Outcome,
    PreflightFact,
    PreflightMemberEvidence,
    PreflightSnapshot,
    Provenance,
    SourceOrigin,
    Transport,
)

__all__ = [
    "MAX_PHYSICAL_MEMBERS",
    "MemberSession",
    "CPPreflightCollectionError",
    "make_real_member_session",
    "collect_member",
    "run_cp_preflight",
]

#: Bounded, caller-selected physical members only -- one ClusterXL/VSX pair.
#: No fleet-wide, first-N, or implicit expansion (task S5 §5/§18/§27 test 33).
MAX_PHYSICAL_MEMBERS = 2


class CPPreflightCollectionError(RuntimeError):
    """Raised for a configuration error in how this collector was invoked
    (e.g. too many members) -- never for an ordinary device-read failure,
    which is represented as evidence (`FactState.COLLECTION_FAILED`), not an
    exception."""


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


# --- Session abstraction: one per physical member, reused for every read ---

@dataclass(frozen=True)
class MemberSession:
    """One controlled per-member command-execution abstraction. `run` issues
    exactly one command invocation for the given fixed, PO-approved read and
    returns the raw exec result dict (`success`/`stdout`/`stderr`/
    `error_class`/`timeout`) for the caller to parse and immediately
    discard. Command text is resolved internally from
    `cp_preflight_battery.COMMAND_TEXT` only -- callers cannot pass
    arbitrary command text through this abstraction (task S5 §8/§9).

    A single instance is reused for every read scheduled for that member,
    `B1` included -- this is the entire mechanism by which "no new SSH
    session for B1" (task S5 §4/§16) is enforced: there is structurally no
    second session to open, only further calls to the same `run`.
    """

    physical_device_identity: str
    _run_command: Callable[[str], dict]

    def run(self, read: CPPreflightRead) -> dict:
        return self._run_command(COMMAND_TEXT[read])


def make_real_member_session(ssh, *, physical_device_identity: str, command_timeout: int) -> MemberSession:
    """Build a `MemberSession` backed by the existing, already-connected,
    already-identity-relevant SSH client -- `configuration.checkpoint_config_probe._run_exec`
    is the same primitive the repository's existing CP config probe/collector
    already use for exactly this shape of read; this module introduces no
    second transport path (task S5 §25)."""
    return MemberSession(
        physical_device_identity=physical_device_identity,
        _run_command=lambda command_text: _run_exec(ssh, command_text, command_timeout),
    )


def _identity_gate_fact(
    *, accepted: bool, preflight_run_id: str, collected_at: str,
    physical_device_identity: str, operational_entity_id: str,
    transport: Transport, context: FactContext,
) -> PreflightFact:
    provenance = Provenance(
        collected_at=collected_at,
        preflight_run_id=preflight_run_id,
        source_vendor="checkpoint",
        source_plane=SourceOrigin.DEVICE_RUNTIME,
        transport=transport,
        physical_device_identity=OpaqueToken(physical_device_identity),
        operational_entity_id=operational_entity_id,
        context=context,
        outcome=Outcome.SUCCESS if accepted else Outcome.IDENTITY_MISMATCH,
        source_command="A1+A2",
    )
    return PreflightFact(
        name="cp_identity_gate_accepted", category=FactCategory.PHYSICAL_IDENTITY,
        state=FactState.KNOWN, value=bool(accepted), provenance=provenance,
    )


def collect_member(
    session: MemberSession,
    *,
    expected_device_name: str,
    management_ip: str,
    is_vsx: bool,
    preflight_run_id: str,
    operational_entity_id: str,
    transport: Transport = Transport.SSH_DIRECT,
    context: FactContext | None = None,
) -> PreflightMemberEvidence:
    """Run the fixed, bounded read battery for one physical member over one
    already-open `MemberSession` and project it into one
    `PreflightMemberEvidence`. No retry: each read in the schedule is issued
    exactly once (task S5 §3/§27 test 9). No readiness verdict is computed
    anywhere in this function.
    """
    ctx = context or FactContext.physical()
    physical_device_identity = session.physical_device_identity
    own_facts: list[PreflightFact] = []
    peer_claim_facts: tuple[PreflightFact, ...] = ()

    def _outcome(result: dict) -> Outcome:
        return Outcome.SUCCESS if result.get("success") else Outcome.FAILED

    # A1: identity/hostname
    hostname_at = _utc_now()
    hostname_result = session.run(CPPreflightRead.A1_HOSTNAME)
    observed_hostname = _parse_hostname(str(hostname_result.get("stdout") or "")) if hostname_result.get("success") else None
    hostname_success = bool(hostname_result.get("success"))

    # A2: version
    version_at = _utc_now()
    version_result = session.run(CPPreflightRead.A2_VERSION)
    version_stdout = str(version_result.get("stdout") or "") if version_result.get("success") else ""
    version_success = bool(version_result.get("success"))
    sw_version = _parse_gaia_version(version_stdout) if version_success else None
    platform_family = _classify_platform(version_stdout=version_stdout, asset_stdout="", model=None)["family"]

    own_facts.append(
        project_cp_software_version_fact(
            sw_version, preflight_run_id=preflight_run_id, collected_at=version_at,
            physical_device_identity=physical_device_identity, operational_entity_id=operational_entity_id,
            transport=transport, context=ctx, outcome=_outcome(version_result),
        )
    )

    identity_target = ProbeTarget(
        role="clusterxl_member", device=expected_device_name, management_ip=management_ip, object_type="cluster_member",
    )
    identity = _identity_gate(
        target=identity_target, observed_hostname=observed_hostname,
        hostname_success=hostname_success, version_success=version_success, authenticated=True,
    )
    own_facts.append(
        _identity_gate_fact(
            accepted=bool(identity.get("accepted")), preflight_run_id=preflight_run_id, collected_at=hostname_at,
            physical_device_identity=physical_device_identity, operational_entity_id=operational_entity_id,
            transport=transport, context=ctx,
        )
    )
    if not identity.get("accepted"):
        # Identity gate failure stops attribution for this member (task S5
        # §6/§27 test 27) -- no further read is issued or attributed.
        return PreflightMemberEvidence(
            physical_device_identity=OpaqueToken(physical_device_identity),
            own_facts=tuple(own_facts), peer_claim_facts=(),
        )

    # A3: cphaprob stat -- local role / cluster mode / attention / peer claims
    a3_at = _utc_now()
    a3_result = session.run(CPPreflightRead.A3_CPHAPROB_STAT)
    a3_fields = _parse_clusterxl_stat_preflight_fields(str(a3_result.get("stdout") or ""), observed_hostname) if a3_result.get("success") else None
    a3_member = project_cp_preflight_facts(
        a3_fields, preflight_run_id=preflight_run_id, collected_at=a3_at,
        physical_device_identity=physical_device_identity, operational_entity_id=operational_entity_id,
        transport=transport, context=ctx, outcome=_outcome(a3_result),
    )
    own_facts.extend(a3_member.own_facts)
    peer_claim_facts = a3_member.peer_claim_facts

    # A4: link health
    a4_at = _utc_now()
    a4_result = session.run(CPPreflightRead.A4_LINK_IF)
    a4_parsed = parse_cphaprob_a_if(str(a4_result.get("stdout") or "")) if a4_result.get("success") else None
    own_facts.extend(
        project_cp_link_health_facts(
            a4_parsed, preflight_run_id=preflight_run_id, collected_at=a4_at,
            physical_device_identity=physical_device_identity, operational_entity_id=operational_entity_id,
            transport=transport, context=ctx, outcome=_outcome(a4_result),
        )
    )

    # A5: pnote/critical device health
    a5_at = _utc_now()
    a5_result = session.run(CPPreflightRead.A5_PNOTE_LIST)
    a5_parsed = parse_cphaprob_ia_list(str(a5_result.get("stdout") or "")) if a5_result.get("success") else None
    own_facts.extend(
        project_cp_pnote_facts(
            a5_parsed, preflight_run_id=preflight_run_id, collected_at=a5_at,
            physical_device_identity=physical_device_identity, operational_entity_id=operational_entity_id,
            transport=transport, context=ctx, outcome=_outcome(a5_result),
        )
    )

    # A6: state/sync -- evidence-based dispatch from A2's already-collected
    # version, decided before execution; never a failure-driven fallback.
    a6_form = resolve_a6_form(sw_version)
    if a6_form is None:
        own_facts.extend(
            project_cp_sync_facts(
                None, preflight_run_id=preflight_run_id, collected_at=_utc_now(),
                physical_device_identity=physical_device_identity, operational_entity_id=operational_entity_id,
                transport=transport, context=ctx, dispatch_form=None,
            )
        )
    else:
        a6_at = _utc_now()
        a6_result = session.run(a6_form)
        a6_parsed = parse_cp_sync_status(str(a6_result.get("stdout") or "")) if a6_result.get("success") else None
        own_facts.extend(
            project_cp_sync_facts(
                a6_parsed, preflight_run_id=preflight_run_id, collected_at=a6_at,
                physical_device_identity=physical_device_identity, operational_entity_id=operational_entity_id,
                transport=transport, context=ctx, dispatch_form=a6_form.value, outcome=_outcome(a6_result),
            )
        )

    # A7: installed policy
    a7_at = _utc_now()
    a7_result = session.run(CPPreflightRead.A7_FW_STAT)
    a7_parsed = parse_fw_stat_policy(str(a7_result.get("stdout") or "")) if a7_result.get("success") else None
    own_facts.extend(
        project_cp_policy_facts(
            a7_parsed, preflight_run_id=preflight_run_id, collected_at=a7_at,
            physical_device_identity=physical_device_identity, operational_entity_id=operational_entity_id,
            transport=transport, context=ctx, outcome=_outcome(a7_result),
        )
    )

    # A8: failover/flap history -- evidence-based dispatch from the already-
    # collected platform classification; never a failure-driven fallback.
    a8_form = resolve_a8_form(platform_family)
    if a8_form is None:
        own_facts.extend(
            project_cp_failover_history_facts(
                None, preflight_run_id=preflight_run_id, collected_at=_utc_now(),
                physical_device_identity=physical_device_identity, operational_entity_id=operational_entity_id,
                transport=transport, context=ctx, dispatch_form=None,
            )
        )
    else:
        a8_at = _utc_now()
        a8_result = session.run(a8_form)
        a8_parsed = parse_cp_failover_history(str(a8_result.get("stdout") or "")) if a8_result.get("success") else None
        own_facts.extend(
            project_cp_failover_history_facts(
                a8_parsed, preflight_run_id=preflight_run_id, collected_at=a8_at,
                physical_device_identity=physical_device_identity, operational_entity_id=operational_entity_id,
                transport=transport, context=ctx, dispatch_form=a8_form.value, outcome=_outcome(a8_result),
            )
        )

    # B1: VSX enumeration -- VSX battery only, same session, no new command
    # beyond this member's own already-open battery.
    if is_vsx:
        b1_at = _utc_now()
        b1_result = session.run(CPPreflightRead.B1_VSX_STAT)
        b1_parsed = parse_vsx_stat_v(str(b1_result.get("stdout") or "")) if b1_result.get("success") else None
        own_facts.extend(
            project_cp_vsx_enumeration_facts(
                b1_parsed, preflight_run_id=preflight_run_id, collected_at=b1_at,
                physical_device_identity=physical_device_identity, operational_entity_id=operational_entity_id,
                transport=transport, context=ctx, outcome=_outcome(b1_result),
            )
        )

    return PreflightMemberEvidence(
        physical_device_identity=OpaqueToken(physical_device_identity),
        own_facts=tuple(own_facts), peer_claim_facts=peer_claim_facts,
    )


@dataclass(frozen=True)
class CPPhysicalMemberTarget:
    """One caller-selected physical member of the operational HA entity.
    `physical_device_identity` is the already-tokenized/opaque identity this
    member's evidence is attributed to -- distinct from `expected_device_name`
    (the management-plane object name used only for the identity gate)."""

    physical_device_identity: str
    expected_device_name: str
    management_ip: str


def run_cp_preflight(
    *,
    operational_entity_id: str,
    unit_type: str,
    members: Sequence[CPPhysicalMemberTarget],
    username: str,
    secret: str,
    strict_host_key: bool = False,
    connect_timeout: int = 8,
    command_timeout: int = 20,
) -> PreflightSnapshot:
    """Top-level, real-device-contact entry point (task S5 §1). Input is one
    explicitly selected HA operational entity and its bounded physical
    members -- never a fleet/inventory scan (task S5 §5/§18).

    Coherence evaluation (`utils.failover.preflight_model.evaluate_coherence`)
    is deliberately not invoked here: that verdict belongs to the caller, on
    the returned `PreflightSnapshot`, once collection is complete -- the S1
    dataclass this module returns carries no coherence field of its own and
    this module adds none, per the file-boundary constraint of this build.

    Trust policy (PO override, 2026-09-03 -- see `OP_0B_1_COMMAND_GATE_PACKAGE.md`
    "PO override — development trust mode"): ``strict_host_key`` defaults to
    ``False``, matching the same compatibility-mode default every other CP
    SSH caller in this repository already has. This is not a new trust
    mechanism -- it is the identical `utils.cp_ssh_trust.apply_strict_host_key_policy`
    helper every other caller uses, with the same argument every other
    caller already defaults to. Strict mode remains fully implemented and
    selectable by passing ``strict_host_key=True`` explicitly; mandatory
    strict enforcement for a production/container runtime is tracked as
    backlog `cp_production_ssh_host_key_trust_hardening`, not implemented
    here. A host key observed in compatibility mode is never persisted,
    never promoted to trust, and never enters the returned evidence (the
    fingerprint `_connect` returns is discarded below) -- it carries no
    identity or readiness authority.
    """
    if not members:
        raise CPPreflightCollectionError("run_cp_preflight requires at least one selected physical member")
    if len(members) > MAX_PHYSICAL_MEMBERS:
        raise CPPreflightCollectionError(
            f"run_cp_preflight received {len(members)} members; bounded maximum is {MAX_PHYSICAL_MEMBERS} "
            "(one explicitly selected HA entity, no fleet expansion)"
        )
    is_vsx = unit_type == "vsx"
    preflight_run_id = str(uuid.uuid4())

    member_evidence: list[PreflightMemberEvidence] = []
    for member in members:
        probe_target = ProbeTarget(
            role="clusterxl_member" if not is_vsx else "vsx_host",
            device=member.expected_device_name,
            management_ip=member.management_ip,
            object_type="cluster_member" if not is_vsx else "vsx_host",
        )
        ssh, _fingerprint = _connect(
            probe_target, username, secret, strict=strict_host_key, connect_timeout=connect_timeout,
        )
        try:
            session = make_real_member_session(
                ssh, physical_device_identity=member.physical_device_identity, command_timeout=command_timeout,
            )
            member_evidence.append(
                collect_member(
                    session,
                    expected_device_name=member.expected_device_name,
                    management_ip=member.management_ip,
                    is_vsx=is_vsx,
                    preflight_run_id=preflight_run_id,
                    operational_entity_id=operational_entity_id,
                )
            )
        finally:
            try:
                ssh.close()
            except Exception:
                pass

    return PreflightSnapshot(
        operational_unit_id=operational_entity_id,
        vendor="checkpoint",
        unit_type=unit_type,
        preflight_run_id=preflight_run_id,
        members=tuple(member_evidence),
        configuration_facts=(),
    )
