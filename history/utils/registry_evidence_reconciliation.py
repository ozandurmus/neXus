"""M10.1 -- D4 registry <-> evidence reconciliation projection.

Frozen contract:
`docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` section 3.3 D4
(values, meaning, evidence, ownership) plus section 5.1.1's `RI-2`/`S-RI-2`
bounded-inconsistency definition. This module is the projection AC-CS-3
names; it has no consumer wired to it (the capability-state resolver is
`M10.2`), no navigation, template, static or payload change, and it persists
nothing (`utils/device_registry.py`'s own persisted row is the only durable
state either input touches).

`RELAY_DECISION` `ozandurmus/nexus-agent-relay#11` (2026-09-08): this slice
ships with no canonical id spanning `utils/device_registry.py`'s `device_id`
and the merged evidence model's collector `entity_id`
(`utils/restore_readiness.py::resolve_entity_id`) -- they are different,
non-comparable identifier spaces, and no code path here ever compares them.
`utils/device_identity_relationships.py` (`M8.1`) is never read: its
`mapping_scope` is documented as prohibited for any purpose beyond
`CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY`, and reusing an `ACTIVE` row as D4
join evidence would be exactly that prohibited use. A future movement may
wire a real, authorized canonical id once one exists; until then, every call
here is made with `EvidenceSide.UNRESOLVABLE`, which this module treats the
same as a failed evidence read (`AC-3`) -- never as a confirmed absence,
which would fabricate `REGISTRY_ONLY`/`EVIDENCE_ONLY`.

The one exception is `REGISTRY_DISABLED`: it is a fact about the registry
row alone (`AC-5`), so it is reported even while the evidence side is
unresolvable -- the disabled operator decision does not depend on any join.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

RECONCILED = "RECONCILED"
EVIDENCE_ONLY = "EVIDENCE_ONLY"
REGISTRY_ONLY = "REGISTRY_ONLY"
REGISTRY_DISABLED = "REGISTRY_DISABLED"
RECONCILIATION_UNKNOWN = "RECONCILIATION_UNKNOWN"

#: The closed D4 vocabulary (AC-1). Nothing outside this set is ever returned.
D4_VALUES: frozenset[str] = frozenset({
    RECONCILED, EVIDENCE_ONLY, REGISTRY_ONLY, REGISTRY_DISABLED, RECONCILIATION_UNKNOWN,
})

RI_2 = "RI-2"


class RegistrySide(str, Enum):
    """The Device Registry's contribution to one canonical id's D4 answer,
    already classified at the source-read level -- never a live device call
    (`utils/device_registry.py` is filesystem-only)."""

    #: The registry document itself could not be read (missing, corrupt,
    #: unsupported schema_version) -- `utils.device_registry.DeviceRegistryError`.
    UNREADABLE = "unreadable"
    #: The registry was read successfully; no row exists for this canonical id.
    ABSENT = "absent"
    #: A row exists for this canonical id and its state is not `DISABLED`.
    ENROLLED = "enrolled"
    #: A row exists for this canonical id and its state is `DISABLED`.
    DISABLED = "disabled"


class EvidenceSide(str, Enum):
    """The merged evidence model's contribution to one canonical id's D4
    answer, already classified at the source-read level."""

    #: The merged evidence model itself could not be read (missing, corrupt,
    #: malformed) -- `utils.merge`'s own read failure.
    UNREADABLE = "unreadable"
    #: No canonical id spans the registry and the merged evidence model for
    #: this entity today (`RELAY_DECISION` #11); the question cannot be
    #: legitimately answered, never defaulted to a confirmed observation or
    #: a confirmed absence.
    UNRESOLVABLE = "unresolvable"
    #: The merged evidence model was read successfully; no observation
    #: exists for this canonical id.
    ABSENT = "absent"
    #: The merged evidence model was read successfully; an observation
    #: exists for this canonical id.
    OBSERVED = "observed"


@dataclass(frozen=True)
class ReconciliationResult:
    """One entity's D4 answer, plus the `RI-2` bounded-inconsistency flag
    (`AC-7`). `bounded_inconsistency` is `RI_2` or `None` -- never a raw
    boolean, so a future second bounded-inconsistency class has somewhere to
    go without changing this field's shape."""

    value: str
    bounded_inconsistency: str | None = None

    def __post_init__(self) -> None:
        if self.value not in D4_VALUES:
            raise ValueError(f"not a D4 value: {self.value!r}")
        if self.bounded_inconsistency not in (None, RI_2):
            raise ValueError(f"not a recognized bounded inconsistency: {self.bounded_inconsistency!r}")


def resolve_d4(*, registry_side: RegistrySide, evidence_side: EvidenceSide) -> ReconciliationResult:
    """Resolve one entity's D4 value from its already-classified registry
    and evidence sides (`AC-1`..`AC-5`).

    `REGISTRY_DISABLED` is checked before evidence readability: it derives
    only from the registry's own disabled row state (`AC-5`), never from a
    collection failure or an evidence-side read outcome.

    Everything else that cannot be positively established -- an unreadable
    registry, an unreadable evidence model, or (today) an unresolvable
    canonical id -- reports `RECONCILIATION_UNKNOWN` (`AC-3`); a failed or
    unresolvable read is never reported as `REGISTRY_ONLY` or `EVIDENCE_ONLY`.
    """
    if registry_side is RegistrySide.DISABLED:
        return ReconciliationResult(REGISTRY_DISABLED)

    if registry_side is RegistrySide.UNREADABLE or evidence_side in (
        EvidenceSide.UNREADABLE,
        EvidenceSide.UNRESOLVABLE,
    ):
        return ReconciliationResult(RECONCILIATION_UNKNOWN)

    if registry_side is RegistrySide.ENROLLED:
        if evidence_side is EvidenceSide.OBSERVED:
            return ReconciliationResult(RECONCILED)
        return ReconciliationResult(REGISTRY_ONLY)

    # registry_side is ABSENT.
    if evidence_side is EvidenceSide.OBSERVED:
        return ReconciliationResult(EVIDENCE_ONLY)
    return ReconciliationResult(RECONCILIATION_UNKNOWN)


def detect_ri2(*, d4_value: str, cross_check_registry_side: RegistrySide) -> bool:
    """`RI-2` (`AC-7`, section 5.1.1): true when a `d4_value` of
    `EVIDENCE_ONLY` disagrees with an independently obtained registry read
    (`cross_check_registry_side`) that finds a non-disabled row for the same
    canonical id. `resolve_d4` never produces this combination from one
    consistent pair of reads -- `EVIDENCE_ONLY` requires `RegistrySide.ABSENT`
    -- so a caller only observes it by re-checking the registry against a
    `d4_value` computed earlier or from a different source (a cached
    resolution racing a registry change, for example). This function makes
    that comparison directly comparable and testable; it decides nothing on
    its own (`S-RI-2`: no action is blocked in this slice)."""
    if d4_value not in D4_VALUES:
        raise ValueError(f"not a D4 value: {d4_value!r}")
    return d4_value == EVIDENCE_ONLY and cross_check_registry_side is RegistrySide.ENROLLED
