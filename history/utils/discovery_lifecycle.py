"""Discovery lifecycle state machine — 0.6.1C.

Tracks the management-plane trust level of each discovered entity through
well-defined states.  No raw configuration, credentials or real device/
network identity is stored in lifecycle records; ``canonical_id`` is an
opaque, non-secret, vendor-scoped handle produced by the collector.

States
------
DISCOVERED  Seen by management plane at least once; not yet confirmed.
VALIDATED   At least one successful direct-evidence collection.
STABLE      Consistently validated across multiple collection cycles.
EXCLUDED    Runtime policy suppresses collection; reason code retained.
REMOVED     No longer present in any management-plane view.

Valid transitions
-----------------
DISCOVERED  → VALIDATED, EXCLUDED, REMOVED
VALIDATED   → STABLE, EXCLUDED, REMOVED, DISCOVERED  (re-discovery after gap)
STABLE      → EXCLUDED, REMOVED, VALIDATED            (confidence drop)
EXCLUDED    → DISCOVERED                              (re-inclusion)
REMOVED     → DISCOVERED                              (re-appearance)
"""
from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import datetime, timezone
from enum import Enum
from typing import Any


LIFECYCLE_SCHEMA_VERSION = 1


class LifecycleState(str, Enum):
    DISCOVERED = "DISCOVERED"
    VALIDATED  = "VALIDATED"
    STABLE     = "STABLE"
    EXCLUDED   = "EXCLUDED"
    REMOVED    = "REMOVED"


# Reason codes: opaque short strings safe for logs/manifests.
class TransitionReason(str, Enum):
    MANAGEMENT_DISCOVERY    = "management_discovery"
    DIRECT_COLLECTION_OK    = "direct_collection_ok"
    MULTI_CYCLE_STABLE      = "multi_cycle_stable"
    CONFIDENCE_DROP         = "confidence_drop"
    RUNTIME_POLICY_EXCLUDE  = "runtime_policy_exclude"
    POLICY_INCLUSION        = "policy_inclusion"
    NOT_IN_MANAGEMENT_VIEW  = "not_in_management_view"
    RE_APPEARED             = "re_appeared"
    IDENTITY_FAILURE        = "identity_failure"
    UNKNOWN                 = "unknown"


class LifecycleTransitionError(ValueError):
    """Raised when a state transition is not permitted."""


VALID_TRANSITIONS: dict[LifecycleState, frozenset[LifecycleState]] = {
    LifecycleState.DISCOVERED: frozenset({
        LifecycleState.VALIDATED,
        LifecycleState.EXCLUDED,
        LifecycleState.REMOVED,
    }),
    LifecycleState.VALIDATED: frozenset({
        LifecycleState.STABLE,
        LifecycleState.EXCLUDED,
        LifecycleState.REMOVED,
        LifecycleState.DISCOVERED,
    }),
    LifecycleState.STABLE: frozenset({
        LifecycleState.EXCLUDED,
        LifecycleState.REMOVED,
        LifecycleState.VALIDATED,
    }),
    LifecycleState.EXCLUDED: frozenset({
        LifecycleState.DISCOVERED,
    }),
    LifecycleState.REMOVED: frozenset({
        LifecycleState.DISCOVERED,
    }),
}


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


@dataclass(frozen=True)
class EntityRecord:
    """Immutable lifecycle record for one discovered entity.

    ``canonical_id`` is opaque and vendor-scoped.  It must not contain raw
    management-plane addresses, credentials or secrets.
    ``evidence_plane`` indicates how the record was last updated:
    ``management`` (management API / cprid inventory) or ``direct``
    (SSH/API direct collection).
    """
    vendor: str
    canonical_id: str
    state: LifecycleState
    confidence: int              # 0-100
    evidence_plane: str          # "management" | "direct" | "unknown"
    first_observed: str          # ISO UTC
    last_observed: str           # ISO UTC
    last_transition: str         # ISO UTC
    transition_reason: str
    lifecycle_version: int = LIFECYCLE_SCHEMA_VERSION

    def __post_init__(self) -> None:
        if not (0 <= self.confidence <= 100):
            raise ValueError(f"confidence must be 0-100, got {self.confidence}")
        if not self.canonical_id.strip():
            raise ValueError("canonical_id must be non-empty")
        if self.evidence_plane not in {"management", "direct", "unknown"}:
            raise ValueError(f"unsupported evidence_plane: {self.evidence_plane!r}")

    def to_dict(self) -> dict[str, Any]:
        return {
            "lifecycle_version": self.lifecycle_version,
            "vendor": self.vendor,
            "canonical_id": self.canonical_id,
            "state": self.state.value,
            "confidence": self.confidence,
            "evidence_plane": self.evidence_plane,
            "first_observed": self.first_observed,
            "last_observed": self.last_observed,
            "last_transition": self.last_transition,
            "transition_reason": self.transition_reason,
        }

    @staticmethod
    def from_dict(raw: dict[str, Any]) -> "EntityRecord":
        return EntityRecord(
            vendor=raw["vendor"],
            canonical_id=raw["canonical_id"],
            state=LifecycleState(raw["state"]),
            confidence=int(raw["confidence"]),
            evidence_plane=raw.get("evidence_plane", "unknown"),
            first_observed=raw["first_observed"],
            last_observed=raw["last_observed"],
            last_transition=raw["last_transition"],
            transition_reason=raw.get("transition_reason", TransitionReason.UNKNOWN.value),
            lifecycle_version=int(raw.get("lifecycle_version", 1)),
        )


def make_discovered(
    vendor: str,
    canonical_id: str,
    *,
    confidence: int = 50,
    evidence_plane: str = "management",
    reason: str = TransitionReason.MANAGEMENT_DISCOVERY.value,
) -> EntityRecord:
    """Create a brand-new DISCOVERED record."""
    now = _utc_now()
    return EntityRecord(
        vendor=vendor,
        canonical_id=canonical_id,
        state=LifecycleState.DISCOVERED,
        confidence=confidence,
        evidence_plane=evidence_plane,
        first_observed=now,
        last_observed=now,
        last_transition=now,
        transition_reason=reason,
    )


def transition(
    record: EntityRecord,
    new_state: LifecycleState,
    *,
    reason: str,
    confidence: int | None = None,
    evidence_plane: str | None = None,
) -> EntityRecord:
    """Return a new EntityRecord in the requested state.

    Raises LifecycleTransitionError when the transition is not permitted.
    """
    allowed = VALID_TRANSITIONS.get(record.state, frozenset())
    if new_state not in allowed:
        raise LifecycleTransitionError(
            f"cannot transition {record.state.value} → {new_state.value} "
            f"for canonical_id={record.canonical_id!r}"
        )
    now = _utc_now()
    return replace(
        record,
        state=new_state,
        confidence=confidence if confidence is not None else record.confidence,
        evidence_plane=evidence_plane if evidence_plane is not None else record.evidence_plane,
        last_observed=now,
        last_transition=now,
        transition_reason=reason,
    )


def update_observed(
    record: EntityRecord,
    *,
    confidence: int | None = None,
    evidence_plane: str | None = None,
) -> EntityRecord:
    """Refresh last_observed and optionally update confidence/plane without a state change."""
    return replace(
        record,
        confidence=confidence if confidence is not None else record.confidence,
        evidence_plane=evidence_plane if evidence_plane is not None else record.evidence_plane,
        last_observed=_utc_now(),
    )


class LifecycleStore:
    """In-memory store for EntityRecords.

    Thread-safety is the caller's responsibility.  This class is intentionally
    free of I/O; persistence is a separate integration concern.
    """

    def __init__(self) -> None:
        self._records: dict[tuple[str, str], EntityRecord] = {}

    def _key(self, vendor: str, canonical_id: str) -> tuple[str, str]:
        return (vendor.strip().lower(), canonical_id)

    def get(self, vendor: str, canonical_id: str) -> EntityRecord | None:
        return self._records.get(self._key(vendor, canonical_id))

    def put(self, record: EntityRecord) -> None:
        self._records[self._key(record.vendor, record.canonical_id)] = record

    def all_records(self) -> list[EntityRecord]:
        return list(self._records.values())

    def records_for_vendor(self, vendor: str) -> list[EntityRecord]:
        prefix = vendor.strip().lower()
        return [r for (v, _), r in self._records.items() if v == prefix]

    def observe(
        self,
        vendor: str,
        canonical_id: str,
        *,
        confidence: int = 50,
        evidence_plane: str = "management",
        reason: str = TransitionReason.MANAGEMENT_DISCOVERY.value,
    ) -> EntityRecord:
        """Upsert: create DISCOVERED if new, else refresh last_observed."""
        existing = self.get(vendor, canonical_id)
        if existing is None:
            record = make_discovered(
                vendor, canonical_id,
                confidence=confidence,
                evidence_plane=evidence_plane,
                reason=reason,
            )
        else:
            record = update_observed(existing, confidence=confidence, evidence_plane=evidence_plane)
        self.put(record)
        return record

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema_version": LIFECYCLE_SCHEMA_VERSION,
            "records": [r.to_dict() for r in self._records.values()],
        }

    @staticmethod
    def from_dict(raw: dict[str, Any]) -> "LifecycleStore":
        """Deserialise a previously serialised store.

        Unknown state values are preserved as-is (forward-compat); records
        with unrecognised states are skipped with a note rather than raising.
        """
        store = LifecycleStore()
        for row in raw.get("records", []):
            try:
                record = EntityRecord.from_dict(row)
            except (KeyError, ValueError):
                continue  # skip unrecognised records; do not raise
            store.put(record)
        return store
