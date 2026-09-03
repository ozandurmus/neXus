"""SecurityExpert — OP.0b preflight fact + provenance model (S1).

Contract: `docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
(status: FROZEN WITH REAL-ENV VALIDATION GATES) — Implementation slices, S1.

Pure domain model for one normalized preflight fact and its provenance
envelope, plus deterministic same-run coherence validation. This module:

- performs **no** device I/O, issues **no** command, contacts **no** network;
- computes **no** readiness verdict — that is `utils.failover.assessment`
  today and a future readiness-v2 slice (S7) tomorrow. This module is
  evidence, not authorization (contract domain invariant "readiness !=
  authorization");
- authorizes **no** CLASS 2 action (`utils.action_taxonomy.CLASS_2_*` stays
  empty regardless of anything modeled here);
- picks **no** numeric threshold. `D-F1` (category-C max age), `D-F2`
  (member-skew tolerance) and `D-F3` (flap/failover frequency) are open,
  unresolved product-owner decisions; this module only carries the raw data
  (timestamps, counters) a later, explicitly-thresholded check can use.

Encodes, without reinterpreting, the frozen contract's domain invariants:
evidence identity != operational identity; configuration intent != runtime
truth; pair existence != pair health; a member's report about its peer is
that member's claim, never an independent observation of the peer; absence
of observation != observation of absence; readiness != authorization; an
unrecognized vendor value is never treated as PASS.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Mapping

__all__ = [
    "FactCategory",
    "RUNTIME_COHERENCE_CATEGORIES",
    "SourceOrigin",
    "Transport",
    "ShellProfile",
    "Outcome",
    "FactState",
    "ContextKind",
    "FactContext",
    "OpaqueToken",
    "FactValue",
    "Provenance",
    "PreflightFact",
    "PreflightMemberEvidence",
    "PreflightSnapshot",
    "CoherenceResult",
    "evaluate_coherence",
]

#: A classification/label string above this length is almost certainly raw
#: buffer/wire content, not a short safe token ("up", "Match", "Complete") —
#: reject it at construction time rather than let it slip in unnoticed
#: (contract privacy invariants; task S1 §9/§19).
_MAX_LABEL_LENGTH = 64


# --- Evidence taxonomy (contract "Evidence taxonomy", categories A-M) -------

class FactCategory(str, Enum):
    """One primary evidence category per fact, per the frozen contract's
    taxonomy table. A secondary category is allowed only where vendor
    semantics prove it — not modeled here; S1 carries one category per fact
    and leaves secondary-category judgment to the caller/future slices."""

    PHYSICAL_IDENTITY = "physical_identity"
    OPERATIONAL_HA_ENTITY_IDENTITY = "operational_ha_entity_identity"
    CONFIGURATION_INTENT = "configuration_intent"
    RUNTIME_HA_STATE = "runtime_ha_state"
    PEER_IDENTITY_RELATIONSHIP = "peer_identity_relationship"
    LINK_HEALTH = "link_health"
    STATE_SESSION_SYNCHRONIZATION = "state_session_synchronization"
    SOFTWARE_POLICY_CONTENT_PARITY = "software_policy_content_parity"
    ELECTION_PREEMPTION_BEHAVIOR = "election_preemption_behavior"
    FAILURE_HEALTH_STATE = "failure_health_state"
    TRANSITION_FLAP_HISTORY = "transition_flap_history"
    PROVENANCE_FRESHNESS = "provenance_freshness"
    PRESENTATION_ONLY = "presentation_only"


#: Categories D, E, F, G, J, K: a preflight fact set is coherent only if
#: every fact in these categories, for every member, shares one
#: `preflight_run_id` (contract "Provenance contract"; task S1 §13).
#: Category H (software/content parity) and category I (election/preemption)
#: are deliberately **not** in this set — the contract's own "Freshness
#: contract" table gives each its own rule (H: "in-run... where required";
#: I: vendor-dependent, CP may be a bounded management-plane read) rather
#: than the hard same-run rule this set enforces. Category C (configuration
#: intent) is handled separately again — see `evaluate_coherence`.
RUNTIME_COHERENCE_CATEGORIES: frozenset[FactCategory] = frozenset({
    FactCategory.RUNTIME_HA_STATE,
    FactCategory.PEER_IDENTITY_RELATIONSHIP,
    FactCategory.LINK_HEALTH,
    FactCategory.STATE_SESSION_SYNCHRONIZATION,
    FactCategory.FAILURE_HEALTH_STATE,
    FactCategory.TRANSITION_FLAP_HISTORY,
})


# --- Provenance vocabulary ---------------------------------------------------

class SourceOrigin(str, Enum):
    """Which plane a fact's value actually came from. Never collapsed into a
    generic "source" — the contract's Palo Alto config/runtime consistency
    axis and the discovery-cache-must-never-short-circuit-runtime rule both
    depend on this distinction staying explicit."""

    MANAGEMENT_DISCOVERY = "management_discovery"
    MANAGEMENT_INTENT = "management_intent"
    DEVICE_CONFIG = "device_config"
    DEVICE_RUNTIME = "device_runtime"


class Transport(str, Enum):
    """Provenance only, never semantic authority (task S1 §6) — the fact's
    meaning does not change depending on which transport carried it."""

    SSH_DIRECT = "ssh_direct"
    CPRID_MDS = "cprid_mds"
    PANORAMA_API_PROXY = "panorama_api_proxy"
    DIRECT_API = "direct_api"


class ShellProfile(str, Enum):
    """Check Point only. `None` on a `Provenance` for any other vendor."""

    INTERACTIVE_DIRECT_CLISH = "interactive_direct_clish"
    INTERACTIVE_EXPERT_EXPLICIT_CLISH = "interactive_expert_explicit_clish"
    EXEC_EXPERT = "exec_expert"


class Outcome(str, Enum):
    """What happened when this fact's read was attempted — the provenance
    envelope's `outcome` field. This is a fact about the *read*, not a
    verdict about the *unit*; it must never be translated into a safety
    verdict here (task S1 §8) — that translation belongs to a future
    readiness slice."""

    SUCCESS = "success"
    FAILED = "failed"
    UNSUPPORTED = "unsupported"
    CAPABILITY_GAP = "capability_gap"
    IDENTITY_MISMATCH = "identity_mismatch"


class FactState(str, Enum):
    """Whether a fact carries a usable value, and if not, why — the
    evidence-level state a `PreflightFact.value` is gated behind. Deliberately
    narrower than a future readiness-verdict enum (task S1 §16): no
    `INSUFFICIENT_EVIDENCE`, `KNOWN_BAD` or `RELATIONSHIP_INCONSISTENT` here
    — those are readiness-verdict vocabulary, not evidence vocabulary, and
    belong to a later slice that interprets these facts."""

    KNOWN = "known"
    UNKNOWN = "unknown"
    NOT_APPLICABLE = "not_applicable"
    UNSUPPORTED = "unsupported"
    COLLECTION_FAILED = "collection_failed"


#: States that never carry a value. `FactState.KNOWN` is the only state a
#: fact may carry a `value` under — see `PreflightFact.__post_init__`.
_VALUELESS_STATES = frozenset(FactState) - {FactState.KNOWN}


class ContextKind(str, Enum):
    """Check Point: PHYSICAL or a numbered VSID. Palo Alto: PHYSICAL, or
    VSYS where a presentation/subordinate context is required. VSYS is never
    an operational failover unit and a non-VSLS VS is never a CLASS 2
    execution target (contract domain invariants 8/9) — S1 only models the
    context a fact was read in; it assigns no execution meaning to it."""

    PHYSICAL = "physical"
    VSID = "vsid"
    VSYS = "vsys"


@dataclass(frozen=True)
class FactContext:
    """The context a fact was read in. `identifier` is required for `VSID`
    and `VSYS`, absent for `PHYSICAL`."""

    kind: ContextKind
    identifier: str | None = None

    def __post_init__(self) -> None:
        if self.kind is ContextKind.PHYSICAL and self.identifier is not None:
            raise ValueError("FactContext.PHYSICAL carries no identifier")
        if self.kind is not ContextKind.PHYSICAL and not self.identifier:
            raise ValueError(f"FactContext.{self.kind.value} requires an identifier")

    @staticmethod
    def physical() -> "FactContext":
        return FactContext(ContextKind.PHYSICAL)

    @staticmethod
    def vsid(vs_id: str) -> "FactContext":
        return FactContext(ContextKind.VSID, str(vs_id))

    @staticmethod
    def vsys(name: str) -> "FactContext":
        return FactContext(ContextKind.VSYS, str(name))

    def to_dict(self) -> dict[str, str]:
        record = {"kind": self.kind.value}
        if self.identifier is not None:
            record["identifier"] = self.identifier
        return record


# --- Safe fact value model (task S1 §9) -------------------------------------

class OpaqueToken(str):
    """A pre-opaque identifier — already tokenized/redacted upstream by the
    repository's existing identity/privacy machinery (e.g. the HMAC
    `Tokenizer` in `utils/support_bundle.py`). S1 does not tokenize anything
    itself and invents no normalization; it only marks, at the type level,
    that a value has already been made opaque and must never be a raw
    serial, IP, hostname, or credential. A distinct subtype of `str` so an
    opaque identifier and an ordinary short classification string
    (`FactValue`'s plain-`str` case) cannot be silently interchanged."""

    __slots__ = ()


#: A `PreflightFact.value` is one of: a short safe classification (`str`,
#: length-guarded — never a raw buffer/serial/config), a boolean, a bounded
#: integer/counter, or an already-opaque identifier. No raw XML/CLI buffer,
#: no `dict[str, Any]` payload, is a `FactValue`.
FactValue = str | bool | int | OpaqueToken


def _reject_unsafe_label(value: object, *, field_name: str, max_length: int = _MAX_LABEL_LENGTH) -> None:
    if isinstance(value, str) and not isinstance(value, OpaqueToken) and len(value) > max_length:
        raise ValueError(
            f"{field_name} is {len(value)} chars, over the {max_length}-char safe-label cap — "
            "looks like raw buffer/config content, not a short classification. "
            "Tokenize identity values as OpaqueToken; do not persist raw wire output."
        )


# --- Provenance envelope (contract "Provenance contract"; task S1 §4) ------

@dataclass(frozen=True)
class Provenance:
    """Every preflight fact carries one of these. Fields follow the frozen
    contract's provenance envelope names where practical."""

    collected_at: str
    preflight_run_id: str
    source_vendor: str  # "checkpoint" | "panorama" — existing repo vocabulary (utils.failover.assessment), not re-enumerated here
    source_plane: SourceOrigin
    transport: Transport
    physical_device_identity: OpaqueToken
    operational_entity_id: str
    context: FactContext
    outcome: Outcome
    source_command: str | None = None
    shell_profile: ShellProfile | None = None
    #: `None` = not evaluable (e.g. one side's timestamp missing) — never a
    #: silent zero (task S1 §14). Populated by `evaluate_coherence`, not by
    #: the collector that produces one member's own facts.
    member_skew_ms: int | None = None
    #: Category-C (configuration intent) facts may predate this preflight;
    #: these two carry that fact's own collection provenance distinctly from
    #: `preflight_run_id`/`collected_at` above, which always describe *this*
    #: preflight. Both `None` for a fact collected fresh, in-run.
    collection_run_id: str | None = None
    original_collected_at: str | None = None

    def __post_init__(self) -> None:
        if not self.collected_at:
            raise ValueError("Provenance.collected_at is required")
        if not self.preflight_run_id:
            raise ValueError("Provenance.preflight_run_id is required")
        if not self.source_vendor:
            raise ValueError("Provenance.source_vendor is required")
        if self.member_skew_ms is not None and self.member_skew_ms < 0:
            raise ValueError("Provenance.member_skew_ms must not be negative")
        _reject_unsafe_label(self.source_command, field_name="Provenance.source_command")

    def to_dict(self) -> dict[str, object]:
        return {
            "collected_at": self.collected_at,
            "preflight_run_id": self.preflight_run_id,
            "source_vendor": self.source_vendor,
            "source_plane": self.source_plane.value,
            "transport": self.transport.value,
            "physical_device_identity": str(self.physical_device_identity),
            "operational_entity_id": self.operational_entity_id,
            "context": self.context.to_dict(),
            "outcome": self.outcome.value,
            "source_command": self.source_command,
            "shell_profile": self.shell_profile.value if self.shell_profile else None,
            "member_skew_ms": self.member_skew_ms,
            "collection_run_id": self.collection_run_id,
            "original_collected_at": self.original_collected_at,
        }


# --- One normalized fact -----------------------------------------------------

@dataclass(frozen=True)
class PreflightFact:
    """One normalized, safe-value preflight fact. `value` is populated only
    when `state is FactState.KNOWN`; every other state carries `value=None`
    explicitly — `UNKNOWN` is never encoded as `False`/`0`/`""`/a missing key
    (task S1 §16, AC-9)."""

    name: str
    category: FactCategory
    state: FactState
    provenance: Provenance
    value: FactValue | None = None
    #: Small, controlled, vendor-specific supplementary values only — never
    #: an escape hatch for arbitrary raw data. Kept narrowly typed to the
    #: same `FactValue` safety rules as `value` itself (task S1 §23: "avoid
    #: a generic payload: dict[str, Any] as the primary design").
    vendor_metadata: Mapping[str, FactValue] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.name:
            raise ValueError("PreflightFact.name is required")
        if self.state is FactState.KNOWN and self.value is None:
            raise ValueError(f"fact {self.name!r} is KNOWN but carries no value")
        if self.state in _VALUELESS_STATES and self.value is not None:
            raise ValueError(f"fact {self.name!r} is {self.state.value} and must not carry a value")
        _reject_unsafe_label(self.value, field_name=f"PreflightFact({self.name!r}).value")
        for key, meta_value in self.vendor_metadata.items():
            _reject_unsafe_label(meta_value, field_name=f"PreflightFact({self.name!r}).vendor_metadata[{key!r}]")

    def to_dict(self) -> dict[str, object]:
        value = self.value
        if isinstance(value, OpaqueToken):
            value = str(value)
        return {
            "name": self.name,
            "category": self.category.value,
            "state": self.state.value,
            "value": value,
            "provenance": self.provenance.to_dict(),
            "vendor_metadata": {
                key: (str(v) if isinstance(v, OpaqueToken) else v)
                for key, v in self.vendor_metadata.items()
            },
        }


# --- Per-member evidence: own observation vs. claim about the peer ---------

@dataclass(frozen=True)
class PreflightMemberEvidence:
    """One physical member's contribution to a preflight snapshot.

    `own_facts` is what this member reports about itself. `peer_claim_facts`
    is what this member reports *about its peer* — still this member's
    claim, sourced and provenanced as this member's read, never elevated
    into an independent observation of the peer (contract domain invariant
    4: "a member's report about its peer is that member's claim... not an
    independent observation"). Keeping both collections on the *same*
    `PreflightMemberEvidence` — rather than synthesizing a second member
    from `peer_claim_facts` — is precisely what prevents the PAN
    phantom-member defect this slice exists to make impossible to repeat."""

    physical_device_identity: OpaqueToken
    own_facts: tuple[PreflightFact, ...] = ()
    peer_claim_facts: tuple[PreflightFact, ...] = ()

    def to_dict(self) -> dict[str, object]:
        return {
            "physical_device_identity": str(self.physical_device_identity),
            "own_facts": [f.to_dict() for f in self.own_facts],
            "peer_claim_facts": [f.to_dict() for f in self.peer_claim_facts],
        }


# --- Coherence -----------------------------------------------------------

@dataclass(frozen=True)
class CoherenceResult:
    """Structured result of `evaluate_coherence` — never discards the facts
    it was computed from; an incoherent snapshot's facts remain available to
    the caller, only the coherence verdict says whether they may be read as
    one snapshot (task S1 §13)."""

    coherent: bool
    reasons: tuple[str, ...] = ()
    #: `None` when not evaluable (timestamps missing on one or more runtime
    #: facts) — never a silent zero.
    member_skew_ms: int | None = None
    #: True when at least one configuration-intent (category C) fact does
    #: not carry this snapshot's own `preflight_run_id` — i.e. it predates
    #: this preflight. This is a plain fact, not a threshold judgment: D-F1
    #: (the numeric max age that would make "stale" a safety verdict) is an
    #: open product-owner decision this field deliberately does not resolve.
    stale_intent_present: bool = False

    def to_dict(self) -> dict[str, object]:
        return {
            "coherent": self.coherent,
            "reasons": list(self.reasons),
            "member_skew_ms": self.member_skew_ms,
            "stale_intent_present": self.stale_intent_present,
        }


@dataclass(frozen=True)
class PreflightSnapshot:
    """Facts for one operational HA unit, grouped for one preflight
    invocation. Evidence, not authorization: this type carries no verdict
    field and computes none — see `evaluate_coherence` for the one
    deterministic judgment this slice makes, which is about evidence
    coherence, not readiness."""

    operational_unit_id: str
    vendor: str
    unit_type: str
    preflight_run_id: str
    members: tuple[PreflightMemberEvidence, ...] = ()
    #: Category-C facts scoped to the whole unit rather than one member
    #: (e.g. a management-plane cluster-object setting). Per-member
    #: configuration facts belong in that member's `own_facts` instead.
    configuration_facts: tuple[PreflightFact, ...] = ()

    def __post_init__(self) -> None:
        if not self.operational_unit_id:
            raise ValueError("PreflightSnapshot.operational_unit_id is required")
        if not self.preflight_run_id:
            raise ValueError("PreflightSnapshot.preflight_run_id is required")

    def to_dict(self) -> dict[str, object]:
        return {
            "operational_unit_id": self.operational_unit_id,
            "vendor": self.vendor,
            "unit_type": self.unit_type,
            "preflight_run_id": self.preflight_run_id,
            "members": [m.to_dict() for m in self.members],
            "configuration_facts": [f.to_dict() for f in self.configuration_facts],
        }


def _all_facts(member: PreflightMemberEvidence) -> tuple[PreflightFact, ...]:
    return member.own_facts + member.peer_claim_facts


def _parse_utc(timestamp: str) -> float | None:
    """Best-effort epoch-seconds parse of an ISO-8601 UTC timestamp
    (`collected_at`'s documented shape). Returns `None` — never a guessed
    value — when the timestamp is missing or unparseable, so skew stays
    "not evaluable" rather than a fake number (task S1 §14)."""
    if not timestamp:
        return None
    from datetime import datetime

    text = timestamp[:-1] + "+00:00" if timestamp.endswith("Z") else timestamp
    try:
        return datetime.fromisoformat(text).timestamp()
    except ValueError:
        return None


def evaluate_coherence(snapshot: PreflightSnapshot) -> CoherenceResult:
    """Deterministic same-run coherence check (task S1 §13).

    Every fact in `RUNTIME_COHERENCE_CATEGORIES`, for every member (own or
    peer-claim), must carry `provenance.preflight_run_id ==
    snapshot.preflight_run_id`. A mismatch — including a runtime fact whose
    provenance was never stamped with this run at all — makes the snapshot
    incoherent, with a reason naming the offending fact. Non-runtime
    categories (identity, configuration intent, presentation, provenance
    itself) are not part of this check; they have their own freshness rules
    (contract "Freshness contract") that this function does not evaluate.
    """
    reasons: list[str] = []

    for member in snapshot.members:
        for fact in _all_facts(member):
            if fact.category not in RUNTIME_COHERENCE_CATEGORIES:
                continue
            if fact.provenance.preflight_run_id != snapshot.preflight_run_id:
                reasons.append(
                    f"member {member.physical_device_identity!s}: runtime fact "
                    f"{fact.name!r} (category {fact.category.value}) carries "
                    f"preflight_run_id={fact.provenance.preflight_run_id!r}, "
                    f"expected {snapshot.preflight_run_id!r}"
                )

    stale_intent_present = any(
        fact.provenance.preflight_run_id != snapshot.preflight_run_id
        or fact.provenance.collection_run_id is not None
        for fact in snapshot.configuration_facts
    )

    timestamps: list[float] = []
    timestamps_incomplete = False
    for member in snapshot.members:
        for fact in _all_facts(member):
            if fact.category not in RUNTIME_COHERENCE_CATEGORIES:
                continue
            parsed = _parse_utc(fact.provenance.collected_at)
            if parsed is None:
                timestamps_incomplete = True
            else:
                timestamps.append(parsed)

    if timestamps_incomplete or len(timestamps) < 2:
        member_skew_ms: int | None = None
    else:
        member_skew_ms = round((max(timestamps) - min(timestamps)) * 1000)

    return CoherenceResult(
        coherent=not reasons,
        reasons=tuple(reasons),
        member_skew_ms=member_skew_ms,
        stale_intent_present=stale_intent_present,
    )
