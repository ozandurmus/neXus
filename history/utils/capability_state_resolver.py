"""M10.2 -- capability-state resolver core (stages 0-3).

Frozen contract:
`docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` sections 4
(canonical vocabulary, result algebra) and 5 (resolution contract, stages
0-3 of the six-stage pipeline). This module is pure: no I/O, no device
contact, no reading of registry/evidence files -- every dimension (`D1`-`D6`,
`I10`, `I14`'s `K1`-`K6`, `I15`) is a typed input the caller supplies.

Scope, per this movement's SESSION_START: stage 0 (surface resolution /
union tag), stage 1 (contradiction/inconsistency gate: `CX1`, `RI-1`,
`RI-2`), stage 2 (the primary ladder, ranks 0-11) and stage 3 (capability
qualifiers). Stages 4-5 (evidence presentation, action affordance) are the
next slice and are not modeled here. `D2`/`D3`/`D5` producers do not exist
yet (`M10.3`/`M12`) -- this module only consumes already-resolved values of
those dimensions; it builds none of them. `RI-2` reuses
`utils.registry_evidence_reconciliation.detect_ri2` (`M10.1`) rather than
reimplementing it.

Nothing here is wired into `html_export`, the console, any payload or
navigation module. `utils/capability_registry.py` (the `0.6.1C` collection
planner -- a different concept, a collection *plan*, not a capability
*state*) is neither extended nor imported.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from utils.registry_evidence_reconciliation import (
    D4_VALUES,
    EVIDENCE_ONLY,
    RECONCILED,
    RECONCILIATION_UNKNOWN,
    REGISTRY_DISABLED,
    REGISTRY_ONLY,
    RegistrySide,
    detect_ri2,
)

__all__ = [
    # Stage 0 -- surface resolution / union tag
    "SurfaceEligibility",
    "SurfaceOmissionReason",
    "Diagnostic",
    "Resolved",
    "Omitted",
    "resolve_union_tag",
    # Vocabulary
    "CapabilityState",
    "CAPABILITY_STATE_VALUES",
    "PrimaryStatus",
    "QUALIFIER_STALE",
    "QUALIFIER_PARTIAL",
    "QUALIFIER_SOURCE_TRUST_LIMITED",
    "QUALIFIER_IDENTITY_TRANSLATION_REQUIRED",
    "QUALIFIER_NOT_SCHEDULED",
    "QUALIFIER_SCHEDULE_UNKNOWN",
    "QUALIFIER_MEMBER_SPECIFIC",
    "CAPABILITY_QUALIFIER_KINDS",
    "CapabilityQualifier",
    # Stage 1 -- contradiction / inconsistency gate
    "IdentityConflict",
    "Comparability",
    "ComparabilityFacts",
    "Ri1Comparability",
    "evaluate_ri1_comparability",
    "EntityApplicability",
    "VendorSupport",
    "Stage1Inputs",
    "Stage1Outcome",
    "evaluate_stage1",
    # Stage 2 -- the primary ladder
    "CapabilityPolicy",
    "ConfigurationEvidence",
    "LadderInputs",
    "resolve_primary_status",
    # Stage 3 -- capability qualifiers
    "FreshnessFacts",
    "QualifierInputs",
    "resolve_qualifiers",
    # Wire serialization (4.4 namespacing rule)
    "to_wire",
]


# --------------------------------------------------------------------------
# Stage 0 -- surface resolution, and the union tag (contract 4.1, 5.0)
# --------------------------------------------------------------------------

class SurfaceEligibility(str, Enum):
    """`D1` -- the only input stage 0 may read (contract 3.3 D1, 5.0)."""

    SURFACE_PRESENT = "SURFACE_PRESENT"
    SURFACE_ABSENT = "SURFACE_ABSENT"
    SURFACE_ELIGIBILITY_UNRESOLVABLE = "SURFACE_ELIGIBILITY_UNRESOLVABLE"


class SurfaceOmissionReason(str, Enum):
    """The `OMITTED` variant's reason -- exactly two values (contract 4.1).
    `NOT_SHIPPED` is a reason here, never a `CapabilityState` (`PO-NAV-7`)."""

    NOT_SHIPPED = "NOT_SHIPPED"
    SURFACE_ELIGIBILITY_UNRESOLVABLE = "SURFACE_ELIGIBILITY_UNRESOLVABLE"


@dataclass(frozen=True)
class Diagnostic:
    """An optional, render-independent diagnostic (contract 4.1.4). This
    module never computes one -- the diagnostic pass is explicitly OPTIONAL
    and out of this slice's scope; a caller may attach one verbatim."""

    detail: str


@dataclass(frozen=True)
class Resolved:
    """Stage 0 established `RESOLVED` (`D1 = SURFACE_PRESENT`). Carries no
    payload of its own -- the four capability outputs come from stage 1-3
    (this module) and stage 4-5 (a later slice), never from stage 0."""


@dataclass(frozen=True)
class Omitted:
    """The `OMITTED` variant: a structurally distinct type from `Resolved`,
    carrying `reason` + optional `diagnostic` and **no** capability output --
    not as an empty collection, not as a `None`-filled field (AC-1)."""

    reason: SurfaceOmissionReason
    diagnostic: Diagnostic | None = None


#: `CapabilityResolution = Resolved | Omitted` (contract 4.1); documented as
#: a plain Union rather than a wrapper type so `Omitted` truly carries no
#: capability-output fields (AC-1).
CapabilityResolution = "Resolved | Omitted"


def resolve_union_tag(
    d1: SurfaceEligibility | None,
    *,
    diagnostic: Diagnostic | None = None,
) -> "Resolved | Omitted":
    """Stage 0 (contract 5.0): resolve the union tag from `D1` alone, and
    nothing else -- no later stage/input may overturn this.

    - `SURFACE_PRESENT` -> `Resolved()` (case V-C).
    - `SURFACE_ABSENT` -> `Omitted(NOT_SHIPPED)` (cases V-A, V-B).
    - anything else -- `None`, a malformed value, or the explicit
      `SURFACE_ELIGIBILITY_UNRESOLVABLE` value -- -> fail closed,
      `Omitted(SURFACE_ELIGIBILITY_UNRESOLVABLE)` (case V-D). Never coerced
      to a default; a missing/malformed `D1` asserts nothing about what the
      build ships.
    """
    if d1 is SurfaceEligibility.SURFACE_PRESENT:
        return Resolved()
    if d1 is SurfaceEligibility.SURFACE_ABSENT:
        return Omitted(reason=SurfaceOmissionReason.NOT_SHIPPED, diagnostic=diagnostic)
    return Omitted(
        reason=SurfaceOmissionReason.SURFACE_ELIGIBILITY_UNRESOLVABLE,
        diagnostic=diagnostic,
    )


# --------------------------------------------------------------------------
# Vocabulary -- CapabilityState (4.2) and CapabilityQualifier (4.3)
# --------------------------------------------------------------------------

class CapabilityState(str, Enum):
    """The nine resolved-only primary values (contract 4.2). `NOT_SHIPPED`
    is deliberately absent -- it is a `SurfaceOmissionReason`, never
    reachable under `RESOLVED`. Lexically disjoint from X1 (job lifecycle)
    and X2 (`OP.2` action state): `COLLECTION_FAILED` is deliberately not
    `FAILED`; no value is named `BLOCKED`."""

    NOT_APPLICABLE = "NOT_APPLICABLE"
    DEVICE_DISABLED = "DEVICE_DISABLED"
    UNSUPPORTED = "UNSUPPORTED"
    NOT_ENROLLED = "NOT_ENROLLED"
    POLICY_DISABLED = "POLICY_DISABLED"
    COLLECTION_FAILED = "COLLECTION_FAILED"
    UNKNOWN = "UNKNOWN"
    NOT_CONFIGURED = "NOT_CONFIGURED"
    AVAILABLE = "AVAILABLE"


CAPABILITY_STATE_VALUES: frozenset[str] = frozenset(state.value for state in CapabilityState)


@dataclass(frozen=True)
class PrimaryStatus:
    """One `primary_status` output (stage 2). `reason` is the named missing
    fact / reason code the contract requires for every `UNKNOWN` (5.2) --
    never present for any other state."""

    state: CapabilityState
    reason: str | None = None

    def __post_init__(self) -> None:
        if self.state is CapabilityState.UNKNOWN and not self.reason:
            raise ValueError("UNKNOWN must carry its named missing fact / reason code")
        if self.state is not CapabilityState.UNKNOWN and self.reason is not None:
            raise ValueError(f"{self.state} does not carry a reason")


QUALIFIER_STALE = "STALE"
QUALIFIER_PARTIAL = "PARTIAL"
QUALIFIER_SOURCE_TRUST_LIMITED = "SOURCE_TRUST_LIMITED"
QUALIFIER_IDENTITY_TRANSLATION_REQUIRED = "IDENTITY_TRANSLATION_REQUIRED"
QUALIFIER_NOT_SCHEDULED = "NOT_SCHEDULED"
QUALIFIER_SCHEDULE_UNKNOWN = "SCHEDULE_UNKNOWN"
QUALIFIER_MEMBER_SPECIFIC = "MEMBER_SPECIFIC"

#: The closed seven-value `CapabilityQualifier` vocabulary (contract 4.3).
CAPABILITY_QUALIFIER_KINDS: frozenset[str] = frozenset({
    QUALIFIER_STALE,
    QUALIFIER_PARTIAL,
    QUALIFIER_SOURCE_TRUST_LIMITED,
    QUALIFIER_IDENTITY_TRANSLATION_REQUIRED,
    QUALIFIER_NOT_SCHEDULED,
    QUALIFIER_SCHEDULE_UNKNOWN,
    QUALIFIER_MEMBER_SPECIFIC,
})

_QUALIFIER_KINDS_WITH_REASON_CODE = frozenset({
    QUALIFIER_SOURCE_TRUST_LIMITED,
    QUALIFIER_IDENTITY_TRANSLATION_REQUIRED,
})


@dataclass(frozen=True)
class CapabilityQualifier:
    """One capability/evidence fact only (contract 4.3) -- never an action
    outcome (those are action-keyed, stage 5, out of this slice's scope)."""

    kind: str
    reason_code: str | None = None
    as_of: object | None = None

    def __post_init__(self) -> None:
        if self.kind not in CAPABILITY_QUALIFIER_KINDS:
            raise ValueError(f"not a CapabilityQualifier kind: {self.kind!r}")
        if self.kind == QUALIFIER_STALE:
            if self.as_of is None:
                raise ValueError("STALE requires a real freshness anchor (as_of)")
        elif self.as_of is not None:
            raise ValueError(f"{self.kind} never carries an as_of anchor")
        if self.kind in _QUALIFIER_KINDS_WITH_REASON_CODE:
            if not self.reason_code:
                raise ValueError(f"{self.kind} requires its source reason code")
        elif self.reason_code is not None:
            raise ValueError(f"{self.kind} never carries a reason_code")

    def to_wire(self) -> dict:
        payload: dict = {"kind": self.kind}
        if self.reason_code is not None:
            payload["reason_code"] = self.reason_code
        if self.as_of is not None:
            payload["as_of"] = self.as_of
        return payload


# --------------------------------------------------------------------------
# Stage 1 -- contradictions and bounded inconsistencies (contract 5.1)
# --------------------------------------------------------------------------

class EntityApplicability(str, Enum):
    """`D2` (contract 3.3 D2)."""

    APPLICABLE = "APPLICABLE"
    NOT_APPLICABLE = "NOT_APPLICABLE"
    APPLICABILITY_UNKNOWN = "APPLICABILITY_UNKNOWN"


class VendorSupport(str, Enum):
    """`D3` (contract 3.3 D3). No producer exists yet (`M10.3`); this
    module only consumes an already-resolved value."""

    SUPPORTED = "SUPPORTED"
    UNSUPPORTED = "UNSUPPORTED"
    SUPPORT_UNKNOWN = "SUPPORT_UNKNOWN"


@dataclass(frozen=True)
class IdentityConflict:
    """`I10`: two incompatible entity-type/vendor resolutions for one
    canonical id -- the only input that may produce `CX1` (contract 4.1.5,
    5.1). `canonical_id` represents the disputed subject (`CX1` subject
    scoping, 5.1.2) even though action gating on it is stage 5, out of
    scope here."""

    canonical_id: str
    resolution_a: str
    resolution_b: str


class Comparability(str, Enum):
    """One `K1`-`K6` condition's value (contract 5.1.1, from `I14`)."""

    TRUE = "TRUE"
    FALSE = "FALSE"
    UNESTABLISHED = "UNESTABLISHED"


@dataclass(frozen=True)
class ComparabilityFacts:
    """`I14`'s `K1`-`K6`, total (contract 5.1.1)."""

    k1_same_subject: Comparability
    k2_same_capability: Comparability
    k3_same_platform: Comparability
    k4_generation_not_superseded: Comparability
    k5_same_support_rule_version: Comparability
    k6_producer_version_comparable: Comparability


class Ri1Comparability(str, Enum):
    """The six precedence rows of the `RI-1` comparability table (contract
    5.1.1), evaluated in order; the first match wins."""

    IRRELEVANT_EVIDENCE = "irrelevant_evidence"
    IDENTITY_UNKNOWN = "support_comparability_unestablished"
    KNOWN_INCOMPATIBLE_PRODUCER = "known_incompatible_producer"
    HISTORICAL_MISMATCH = "historical_mismatch"
    COMPARABILITY_UNKNOWN = "support_comparability_unestablished"
    HOLDS = "support_inconsistency"


def evaluate_ri1_comparability(facts: ComparabilityFacts) -> Ri1Comparability:
    """The `K1`-`K6` precedence table, rows 1-6 evaluated in order, first
    match wins (contract 5.1.1). Total: exactly one row always matches. A
    `FALSE` on a scope condition (rows 1, 3) always beats an `UNESTABLISHED`
    elsewhere (rows 2, 5); a known mismatch (rows 3, 4) always beats an
    unknown one (row 5)."""
    if facts.k1_same_subject is Comparability.FALSE or facts.k2_same_capability is Comparability.FALSE:
        return Ri1Comparability.IRRELEVANT_EVIDENCE
    if (
        facts.k1_same_subject is Comparability.UNESTABLISHED
        or facts.k2_same_capability is Comparability.UNESTABLISHED
    ):
        return Ri1Comparability.IDENTITY_UNKNOWN
    if facts.k6_producer_version_comparable is Comparability.FALSE:
        return Ri1Comparability.KNOWN_INCOMPATIBLE_PRODUCER
    if (
        facts.k3_same_platform is Comparability.FALSE
        or facts.k4_generation_not_superseded is Comparability.FALSE
        or facts.k5_same_support_rule_version is Comparability.FALSE
    ):
        return Ri1Comparability.HISTORICAL_MISMATCH
    if any(
        value is Comparability.UNESTABLISHED
        for value in (
            facts.k3_same_platform,
            facts.k4_generation_not_superseded,
            facts.k5_same_support_rule_version,
            facts.k6_producer_version_comparable,
        )
    ):
        return Ri1Comparability.COMPARABILITY_UNKNOWN
    return Ri1Comparability.HOLDS


@dataclass(frozen=True)
class Stage1Inputs:
    """Everything stage 1 may read. `identity_conflict` is the sole `CX1`
    input; `ri1_support_status`/`ri1_comparability` are consulted only when
    `D3 = UNSUPPORTED`; `ri2_*` feed straight into the reused `detect_ri2`.
    Absent fields mean "this class does not apply here" -- never "unknown
    whether it applies"."""

    identity_conflict: IdentityConflict | None = None
    ri1_support_status: VendorSupport | None = None
    ri1_comparability: ComparabilityFacts | None = None
    ri2_d4_value: str | None = None
    ri2_cross_check_registry_side: RegistrySide | None = None


@dataclass(frozen=True)
class Stage1Outcome:
    """Stage 1's result: whether each closed class holds, plus what each
    names (contract 5.1). None of these are ever inferred from one another
    -- `CX1` only from `identity_conflict`, `RI-1` only from `D3 = UNSUPPORTED`
    plus its own comparability table, `RI-2` only from the reused
    `detect_ri2`."""

    cx1_holds: bool = False
    cx1_disputed_subject: str | None = None
    ri1_holds: bool = False
    ri1_comparability_unestablished: bool = False
    ri1_comparability_reason: str | None = None
    ri2_holds: bool = False

    @property
    def any_holds(self) -> bool:
        return self.cx1_holds or self.ri1_holds or self.ri2_holds


def evaluate_stage1(inputs: Stage1Inputs) -> Stage1Outcome:
    """Stage 1 (contract 5.1): the contradiction/inconsistency gate.
    Reached only under `RESOLVED` -- callers invoke this only after
    `resolve_union_tag` returns `Resolved()`."""
    cx1_holds = inputs.identity_conflict is not None
    cx1_subject = inputs.identity_conflict.canonical_id if inputs.identity_conflict else None

    ri1_holds = False
    ri1_unestablished = False
    ri1_reason: str | None = None
    if inputs.ri1_support_status is VendorSupport.UNSUPPORTED and inputs.ri1_comparability is not None:
        row = evaluate_ri1_comparability(inputs.ri1_comparability)
        if row is Ri1Comparability.HOLDS:
            ri1_holds = True
        elif row in (Ri1Comparability.IDENTITY_UNKNOWN, Ri1Comparability.COMPARABILITY_UNKNOWN):
            ri1_unestablished = True
            ri1_reason = row.value

    ri2_holds = False
    if inputs.ri2_d4_value is not None and inputs.ri2_cross_check_registry_side is not None:
        ri2_holds = detect_ri2(
            d4_value=inputs.ri2_d4_value,
            cross_check_registry_side=inputs.ri2_cross_check_registry_side,
        )

    return Stage1Outcome(
        cx1_holds=cx1_holds,
        cx1_disputed_subject=cx1_subject,
        ri1_holds=ri1_holds,
        ri1_comparability_unestablished=ri1_unestablished,
        ri1_comparability_reason=ri1_reason,
        ri2_holds=ri2_holds,
    )


# --------------------------------------------------------------------------
# Stage 2 -- the primary ladder (contract 5.2)
# --------------------------------------------------------------------------

class CapabilityPolicy(str, Enum):
    """`D5` (contract 3.3 D5). No per-(entity, capability) producer exists
    yet (`M12`); this module only consumes an already-resolved value."""

    POLICY_ACTIVE = "POLICY_ACTIVE"
    POLICY_DISABLED = "POLICY_DISABLED"
    NO_APPLICABLE_SCHEDULE = "NO_APPLICABLE_SCHEDULE"
    POLICY_UNKNOWN = "POLICY_UNKNOWN"


class ConfigurationEvidence(str, Enum):
    """`I11`: configuration presence, or *positively* established absence
    (contract 4.1.3 I11). Rank 9 fires only on `POSITIVELY_ABSENT` --
    never on `NOT_ESTABLISHED`, which would fabricate certainty."""

    NOT_ESTABLISHED = "NOT_ESTABLISHED"
    PRESENT = "PRESENT"
    POSITIVELY_ABSENT = "POSITIVELY_ABSENT"


_DATA_STATES = frozenset({"live", "last_known_good", "partial", "no_data"})
_COLLECTION_OUTCOMES = frozenset({"success", "failed", "unsupported", "capability_gap", "identity_mismatch"})
_AVAILABLE_DATA_STATES = frozenset({"live", "last_known_good", "partial"})


@dataclass(frozen=True)
class LadderInputs:
    """Everything stage 2 may read: `D2`-`D6c`, stage 1's outcome, `I11`.
    Fields carry raw values rather than being pre-validated so that a
    missing (`None`) or malformed (not a domain member) value naturally
    fails to match any ladder condition and falls through to rank 11,
    without any special-cased coercion (contract 4.1.5: absent/malformed
    input is never coerced to a default)."""

    stage1: Stage1Outcome
    d2_entity_applicability: object
    d3_vendor_support: object
    d4_reconciliation: object
    d5_capability_policy: object
    d6b_data_state: object
    d6c_collection_outcome: object
    insufficient_evidence: bool = False
    configuration_evidence: object = ConfigurationEvidence.NOT_ESTABLISHED


def _all_well_formed(inputs: LadderInputs) -> bool:
    return (
        inputs.d2_entity_applicability in EntityApplicability
        and inputs.d3_vendor_support in VendorSupport
        and inputs.d4_reconciliation in D4_VALUES
        and inputs.d5_capability_policy in CapabilityPolicy
        and inputs.d6b_data_state in _DATA_STATES
        and inputs.d6c_collection_outcome in _COLLECTION_OUTCOMES
    )


def resolve_primary_status(inputs: LadderInputs) -> PrimaryStatus:
    """Stage 2 (contract 5.2): the primary ladder, ranks 0-11, first match
    wins. Reached only under `RESOLVED`. `AVAILABLE` is reachable only via
    rank 10's full positive conjunction; rank 11 is the fail-closed
    catch-all for any input absent, malformed, or matching no rank."""
    stage1 = inputs.stage1

    # Rank 0: CX1, RI-1 or RI-2 holds.
    if stage1.cx1_holds:
        return PrimaryStatus(CapabilityState.UNKNOWN, "identity_contradiction")
    if stage1.ri1_holds:
        return PrimaryStatus(CapabilityState.UNKNOWN, "support_inconsistency")
    if stage1.ri2_holds:
        return PrimaryStatus(CapabilityState.UNKNOWN, "reconciliation_inconsistency")

    # Rank 1: RI-1 comparability could not be established.
    if stage1.ri1_comparability_unestablished:
        return PrimaryStatus(CapabilityState.UNKNOWN, "support_comparability_unestablished")

    # Rank 2: D2 = NOT_APPLICABLE.
    if inputs.d2_entity_applicability is EntityApplicability.NOT_APPLICABLE:
        return PrimaryStatus(CapabilityState.NOT_APPLICABLE)

    # Rank 3: D4 = REGISTRY_DISABLED.
    if inputs.d4_reconciliation == REGISTRY_DISABLED:
        return PrimaryStatus(CapabilityState.DEVICE_DISABLED)

    # Rank 4: D3 = UNSUPPORTED (RI-1 already ruled out above by rank 0).
    if inputs.d3_vendor_support is VendorSupport.UNSUPPORTED:
        return PrimaryStatus(CapabilityState.UNSUPPORTED)

    # Rank 5: D4 = EVIDENCE_ONLY.
    if inputs.d4_reconciliation == EVIDENCE_ONLY:
        return PrimaryStatus(CapabilityState.NOT_ENROLLED)

    # Rank 6: D5 = POLICY_DISABLED.
    if inputs.d5_capability_policy is CapabilityPolicy.POLICY_DISABLED:
        return PrimaryStatus(CapabilityState.POLICY_DISABLED)

    # Rank 7: D6c = failed on the latest attempt.
    if inputs.d6c_collection_outcome == "failed":
        return PrimaryStatus(CapabilityState.COLLECTION_FAILED)

    # Rank 8: any validly-unknown dimension, or insufficient evidence.
    if inputs.d3_vendor_support is VendorSupport.SUPPORT_UNKNOWN:
        return PrimaryStatus(CapabilityState.UNKNOWN, "support_unknown")
    if inputs.d2_entity_applicability is EntityApplicability.APPLICABILITY_UNKNOWN:
        return PrimaryStatus(CapabilityState.UNKNOWN, "applicability_unknown")
    if inputs.d4_reconciliation == REGISTRY_ONLY:
        return PrimaryStatus(CapabilityState.UNKNOWN, "registry_only")
    if inputs.d4_reconciliation == RECONCILIATION_UNKNOWN:
        return PrimaryStatus(CapabilityState.UNKNOWN, "reconciliation_unknown")
    if inputs.d6b_data_state == "no_data":
        return PrimaryStatus(CapabilityState.UNKNOWN, "no_data")
    if inputs.insufficient_evidence:
        return PrimaryStatus(CapabilityState.UNKNOWN, "insufficient_evidence")

    # Rank 9: D6 positively evidences absence of configuration.
    if inputs.configuration_evidence is ConfigurationEvidence.POSITIVELY_ABSENT:
        return PrimaryStatus(CapabilityState.NOT_CONFIGURED)

    # Rank 10: the full positive conjunction.
    if (
        inputs.d2_entity_applicability is EntityApplicability.APPLICABLE
        and inputs.d3_vendor_support is VendorSupport.SUPPORTED
        and inputs.d4_reconciliation == RECONCILED
        and inputs.d6b_data_state in _AVAILABLE_DATA_STATES
        and inputs.d6c_collection_outcome == "success"
        and not stage1.any_holds
        and _all_well_formed(inputs)
    ):
        return PrimaryStatus(CapabilityState.AVAILABLE)

    # Rank 11: fail closed -- absent, malformed, or matching no rank above.
    return PrimaryStatus(CapabilityState.UNKNOWN, "unclassified_input")


# --------------------------------------------------------------------------
# Stage 3 -- capability qualifiers (contract 4.3, 5.2.1)
# --------------------------------------------------------------------------

@dataclass(frozen=True)
class FreshnessFacts:
    """`D6a` (contract 3.3 D6, facet a). `STALE` requires both `fresh ==
    False` and a real anchor -- never emitted from a missing anchor."""

    fresh: bool
    as_of: object | None = None
    stale_reason: str | None = None


@dataclass(frozen=True)
class QualifierInputs:
    """Everything stage 3 may read, one field per facet (contract 4.3).
    Each qualifier is derived from exactly one facet, independent of every
    other -- the four inference bans (4.1.5) forbid deriving one facet's
    qualifier from another's input."""

    freshness: FreshnessFacts | None = None
    data_state: str | None = None
    source_trust_limited_reason: str | None = None
    identity_translation_required_reason: str | None = None
    capability_policy: object | None = None
    member_specific: bool = False


def resolve_qualifiers(inputs: QualifierInputs) -> frozenset[CapabilityQualifier]:
    """Stage 3 (contract 5.2.1): independent of stage 2's rank, no action
    outcomes. Each qualifier is evaluated from its own facet only:

    - `STALE(as_of)`: `D6a` alone, `fresh is False` with a real anchor.
    - `PARTIAL`: `D6b == partial` alone.
    - `SOURCE_TRUST_LIMITED(reason_code)`: `D6d` alone.
    - `IDENTITY_TRANSLATION_REQUIRED(reason_code)`: `D6e` alone -- never
      promoted to `CX1` here; that promotion, if it ever happens, is
      `CX1`'s own independent condition (stage 1), never derived from this
      qualifier.
    - `NOT_SCHEDULED`: `D5 == NO_APPLICABLE_SCHEDULE` alone.
    - `SCHEDULE_UNKNOWN`: `D5 == POLICY_UNKNOWN` alone.
    - `MEMBER_SPECIFIC`: `I15` alone.
    """
    qualifiers: set[CapabilityQualifier] = set()

    freshness = inputs.freshness
    if freshness is not None and freshness.fresh is False and freshness.as_of is not None:
        qualifiers.add(CapabilityQualifier(kind=QUALIFIER_STALE, as_of=freshness.as_of))

    if inputs.data_state == "partial":
        qualifiers.add(CapabilityQualifier(kind=QUALIFIER_PARTIAL))

    if inputs.source_trust_limited_reason is not None:
        qualifiers.add(
            CapabilityQualifier(
                kind=QUALIFIER_SOURCE_TRUST_LIMITED,
                reason_code=inputs.source_trust_limited_reason,
            )
        )

    if inputs.identity_translation_required_reason is not None:
        qualifiers.add(
            CapabilityQualifier(
                kind=QUALIFIER_IDENTITY_TRANSLATION_REQUIRED,
                reason_code=inputs.identity_translation_required_reason,
            )
        )

    if inputs.capability_policy is CapabilityPolicy.NO_APPLICABLE_SCHEDULE:
        qualifiers.add(CapabilityQualifier(kind=QUALIFIER_NOT_SCHEDULED))
    elif inputs.capability_policy is CapabilityPolicy.POLICY_UNKNOWN:
        qualifiers.add(CapabilityQualifier(kind=QUALIFIER_SCHEDULE_UNKNOWN))

    if inputs.member_specific:
        qualifiers.add(CapabilityQualifier(kind=QUALIFIER_MEMBER_SPECIFIC))

    return frozenset(qualifiers)


# --------------------------------------------------------------------------
# Wire serialization -- the 4.4 namespacing rule
# --------------------------------------------------------------------------

def to_wire(status: PrimaryStatus, qualifiers: frozenset[CapabilityQualifier] = frozenset()) -> dict:
    """Serialize under exactly the two keys the contract names
    (`capability_state` / `capability_qualifiers`, 4.4) -- a bare state
    string never crosses a subsystem boundary on its own."""
    payload: dict = {
        "capability_state": status.state.value,
        "capability_qualifiers": [q.to_wire() for q in sorted(qualifiers, key=lambda q: q.kind)],
    }
    if status.reason is not None:
        payload["reason"] = status.reason
    return payload
