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

import time
import uuid
from dataclasses import dataclass, field
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
    InteractiveSshSession,
    _classify_platform,
    _parse_clusterxl_cluster_mode,
    _parse_clusterxl_runtime_role,
    _parse_clusterxl_stat_preflight_fields,
    _parse_gaia_version,
)
from configuration.checkpoint_config_probe import (
    ProbeTarget,
    _connect,
    _identity_gate,
    _parse_hostname,
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


#: Deterministic pacing between two approved reads of the same member's
#: battery. Real-environment evidence: the battery executes correctly in one
#: persistent Expert shell, but issued back to back it destabilizes the SSH
#: session. Applied strictly BETWEEN reads (N reads -> N-1 waits), after
#: deterministic completion of the previous one. It is not retry, backoff,
#: reconnect, or an adaptive/configurable framework -- one constant, nothing
#: to tune, and never authority to re-issue a failed read.
INTER_COMMAND_DELAY_SECONDS = 0.3


# --- Execution-context capability gap (OP.0b S8-A real-environment) --------

#: Gaia Clish's own rejection vocabulary for a command it does not know.
#: Matched only to *classify* an already-failed read -- never to retry it,
#: never to select a different command, never to infer a healthy state.
_CLISH_REJECTION_MARKERS = (
    "invalid command",
    "unknown command",
    "not a valid command",
    "clinfr",
)

#: An approved read whose command text is a bare Expert-shell invocation --
#: i.e. anything the battery does not wrap in `clish -c '...'`. Derived from
#: the frozen `COMMAND_TEXT`, never from a second hand-maintained list.
def _is_expert_shell_form(command_text: str) -> bool:
    return not str(command_text or "").strip().startswith("clish -c")


def classify_execution_context_gap(command_text: str, result: dict) -> bool:
    """True when a read was rejected by the device's CLI before any binary
    ran, rather than answered badly by the device.

    A bare Expert read (`cphaprob ...`, `fw ...`, `vsx ...`) coming back with
    Clish's own rejection vocabulary means the command did not execute in an
    Expert execution context. Since the battery now runs inside one
    persistent Expert shell per member, this should not occur on a session
    that landed in Expert -- so it is a *diagnostic* for an unconfirmed
    Expert context, deliberately narrow and fail-closed.

    It is not a licence to route around the condition: no fallback, no
    retry, no privilege escalation, no second credential path, and no device
    change. Nor may it excuse an application execution-path defect -- if
    Expert reads are being rejected, the execution model is the first thing
    to suspect, not the environment.
    """
    if result.get("success"):
        return False
    if not _is_expert_shell_form(command_text):
        return False
    text = f"{result.get('stdout') or ''}\n{result.get('stderr') or ''}".lower()
    return any(marker in text for marker in _CLISH_REJECTION_MARKERS)


# --- Session abstraction: one per physical member, reused for every read ---

@dataclass
class MemberSession:
    """One controlled per-member execution context. `run` issues exactly one
    command invocation for the given fixed, PO-approved read and returns the
    raw exec result dict (`success`/`stdout`/`stderr`/`error_class`/
    `timeout`) for the caller to parse and immediately discard. Command text
    is resolved internally from `cp_preflight_battery.COMMAND_TEXT` only --
    callers cannot pass arbitrary command text through this abstraction
    (task S5 §8/§9).

    A single instance is reused for every read scheduled for that member,
    `B1` included -- this is the entire mechanism by which "no new SSH
    session for B1" (task S5 §4/§16) is enforced: there is structurally no
    second session to open, only further calls to the same `run`.

    It is also the member's **execution context**: the device-specific facts
    the battery dispatches on (`sw_version`, `platform_family`) and the
    resulting command plan (`a6_form`, `a8_form`) are resolved exactly once
    per member by `resolve_execution_context` and read from here afterwards
    -- never recomputed per command. The static parts of the Check Point
    execution model (Expert login shell; Gaia commands invoked explicitly as
    `clish -c '...'`; VSX context switched with `vsenv` inside this same
    session) are platform contract, not runtime discovery, and are therefore
    deliberately *not* probed here.
    """

    physical_device_identity: str
    _run_command: Callable[[str], dict]
    #: Injected pacing sleeper. `None` in unit doubles that drive the
    #: session directly, so no test ever waits for real time.
    _sleep: Callable[[float], None] | None = None
    #: The persistent shell backing this member, when there is a real one.
    #: Owned here so the shell's lifecycle is exactly the session's: opened
    #: once, closed once, never replaced mid-battery.
    _shell: object | None = None

    def close(self) -> None:
        """Close the member's persistent shell exactly once. Idempotent, and
        never closes the SSH transport -- that stays the caller's."""
        shell, self._shell = self._shell, None
        if shell is not None:
            try:
                shell.close()
            except Exception:
                pass
    #: Device-specific dispatch evidence + resulting plan -- resolved once.
    sw_version: str | None = field(default=None, init=False)
    platform_family: str | None = field(default=None, init=False)
    a6_form: CPPreflightRead | None = field(default=None, init=False)
    a8_form: CPPreflightRead | None = field(default=None, init=False)
    #: Bookkeeping that makes the once-only invariants observable/testable.
    execution_context_resolutions: int = field(default=0, init=False)
    command_invocations: int = field(default=0, init=False)

    def run(self, read: CPPreflightRead) -> dict:
        command_text = COMMAND_TEXT[read]
        # Pace BETWEEN reads: wait before every read except the first. Since
        # `_run_command` returns only on deterministic completion, waiting
        # here is exactly "previous read finished -> wait -> send next", and
        # it structurally cannot leave a delay after the final read.
        # Never retry authority: a failed read is recorded, never re-issued.
        if self.command_invocations and self._sleep is not None:
            self._sleep(INTER_COMMAND_DELAY_SECONDS)
        self.command_invocations += 1
        result = self._run_command(command_text)
        # Carry the command text alongside its own result so outcome
        # classification never has to re-resolve or guess it.
        if isinstance(result, dict):
            result = {**result, "command_text": command_text}
        return result

    def resolve_execution_context(
        self, *, sw_version: str | None, platform_family: str | None
    ) -> None:
        """Fix this member's command plan once, from already-collected `A2`
        evidence. Idempotent by construction: a second call is a no-op, so a
        future helper cannot silently reintroduce per-command dispatch."""
        if self.execution_context_resolutions:
            return
        self.sw_version = sw_version
        self.platform_family = platform_family
        self.a6_form = resolve_a6_form(sw_version)
        self.a8_form = resolve_a8_form(platform_family)
        self.execution_context_resolutions += 1


def make_real_member_session(
    ssh, *, physical_device_identity: str, command_timeout: int,
    sleeper: Callable[[float], None] | None = None,
) -> MemberSession:
    """Build a `MemberSession` on **one persistent Expert shell**.

    The whole battery for one physical member runs inside a single
    `InteractiveSshSession` -- the repository's existing, real-environment
    validated persistent-shell adapter (one `invoke_shell`, prompt framing,
    caller-owned allow-listed dispatch). No second Expert-shell
    implementation is introduced here (task S5 §25).

    **Why a persistent shell and not one exec channel per read.** OP.0b S8-A
    real-environment device-log evidence (`clish`/`xpand` audit trail, 8
    reads on one member): every *non-interactive exec channel* was dispatched
    through the Gaia CLI wrapper, so each read cost a full device-side CLI
    initialization (`clish -c ver`, exactly 8, one per channel) and the five
    bare Expert reads (`cphaprob stat`, `cphaprob -a if`,
    `cphaprob -ia list`, `cphaprob syncstat`, `fw stat`) never reached an
    Expert shell at all -- only the explicit `clish -c '...'` forms
    executed. One SSH transport was never the same thing as one Expert
    shell: eight independent exec channels meant eight execution contexts.

    An interactive shell is the operator's own login path, which the
    validated Check Point execution contract fixes as Expert. Opening it
    once per member gives one execution context for the entire battery,
    Gaia reads still explicit (`clish -c '...'`) and Expert reads direct, as
    the contract requires.

    Commands are **framed**, not timed: each read carries a per-session end
    marker echoing `$?`, so completion and exit status are read explicitly
    rather than inferred from a quiet period. Framing stays authoritative for
    completion -- the pacing below is *in addition to* it, never instead of
    it.

    Reads are **paced** at `INTER_COMMAND_DELAY_SECONDS`. Real-environment
    evidence: the battery executes correctly in one Expert shell, but issued
    back to back it destabilizes the session. The order is strictly
    send -> deterministic completion -> record -> wait -> next send, so the
    delay is spent between reads only: `N` reads cost `N-1` waits, with no
    delay before the first and none after the last. It is pacing, never
    retry, backoff, or reconnect authority -- a failed read is recorded and
    the battery moves on.
    """
    shell = InteractiveSshSession(ssh, command_timeout)
    return MemberSession(
        physical_device_identity=physical_device_identity,
        _run_command=lambda command_text: shell.run(command_text, command_timeout, frame=True),
        _shell=shell,
        # Resolved at call time, not bound as a default, so the pacing
        # sleeper stays injectable/observable.
        _sleep=sleeper if sleeper is not None else time.sleep,
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
        if result.get("success"):
            return Outcome.SUCCESS
        command_text = str(result.get("command_text") or "")
        if classify_execution_context_gap(command_text, result):
            return Outcome.CAPABILITY_GAP
        return Outcome.FAILED

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

    # One execution context per member: the battery's device-specific
    # dispatch evidence and the resulting command plan are fixed here, once,
    # from A2's already-collected output -- never recomputed per command.
    session.resolve_execution_context(sw_version=sw_version, platform_family=platform_family)

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
    a3_stdout = str(a3_result.get("stdout") or "")
    if a3_result.get("success"):
        # project_cp_preflight_facts's own contract is
        # _parse_clusterxl_stat_preflight_fields's shape "merged with the two
        # always-parsed leaves" (local_role, cluster_mode) -- that merge is
        # this call site's responsibility, not the S3 extraction function's
        # (whose own test_no_future_command_fields_fabricated deliberately
        # keeps its return set to exactly {peer_row_states, local_attention}).
        # Same canonical parsers the pre-existing VS-context path already
        # uses on this same buffer -- one parser, two consumers, no second
        # implementation.
        a3_fields = {
            **_parse_clusterxl_stat_preflight_fields(a3_stdout, observed_hostname),
            "local_role": _parse_clusterxl_runtime_role(a3_stdout, observed_hostname),
            "cluster_mode": _parse_clusterxl_cluster_mode(a3_stdout),
        }
    else:
        a3_fields = None
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
    a6_form = session.a6_form
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
    a8_form = session.a8_form
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
        session = None
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
            # One shell close, then one transport close, in that order.
            if session is not None:
                session.close()
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
