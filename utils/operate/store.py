"""OP.2.B -- the durable action-record store: the HA-entity lock, the guarded
boundary transition, and the derived quarantine predicate (P8, P10).

No new lock mechanism, key domain or table is introduced (P8): the outer
lock is a uniqueness rule over records that must exist anyway, enforced here
at create time. The guarded transition is a from-state-checked, single
in-process-lock-protected read-modify-write -- sufficient because the
contract's own topology invariant is "exactly one class 2 coordinator
process per deployment" (§"Locking / concurrency model": "No distributed
consensus, leader election or quorum is introduced"). A multi-worker class 2
coordinator is explicitly out of scope.

Quarantine has exactly one owner here too: it is never written to a
separate field or table, only derived from "a record for this entity is
OUTCOME_UNKNOWN with acknowledged_at unset" (P10).
"""
from __future__ import annotations

import threading
from pathlib import Path
from typing import Callable, Iterable

from utils.evidence_backend import EvidenceBackendError, select_action_record_backend

from .record import ActionRecord, utc_now
from .states import ActionState, TERMINAL_STATES, is_legal_transition


class EntityActionInFlightError(RuntimeError):
    def __init__(self, operational_entity_id: str) -> None:
        super().__init__(f"entity_action_in_flight: {operational_entity_id!r}")
        self.operational_entity_id = operational_entity_id
        self.reason_code = "entity_action_in_flight"


class EntityQuarantinedError(RuntimeError):
    def __init__(self, operational_entity_id: str) -> None:
        super().__init__(f"entity_quarantined: {operational_entity_id!r}")
        self.operational_entity_id = operational_entity_id
        self.reason_code = "entity_quarantined"


class IllegalTransitionError(RuntimeError):
    """Raised when a caller asks for a transition absent from the frozen
    legal-transitions graph -- never reachable from a well-formed
    coordinator, but a hard stop against ever adding one ad hoc (AC-6)."""


_TERMINAL_VALUES = {s.value for s in TERMINAL_STATES}


def is_quarantining(record: dict) -> bool:
    """P10 -- the one and only quarantine predicate."""
    return record.get("state") == ActionState.OUTCOME_UNKNOWN.value and not record.get("acknowledged_at")


def is_blocking(record: dict) -> bool:
    """Non-terminal, or terminal-but-quarantining (P8 outer lock predicate)."""
    if record.get("state") not in _TERMINAL_VALUES:
        return True
    return is_quarantining(record)


class ActionRecordStore:
    def __init__(self, data_root: Path) -> None:
        self._backend = select_action_record_backend(root=Path(data_root) / "state" / "ha_action_records")
        self._entity_locks_guard = threading.Lock()
        self._entity_locks: dict[str, threading.Lock] = {}
        self._record_locks_guard = threading.Lock()
        self._record_locks: dict[str, threading.Lock] = {}

    def _entity_lock(self, entity_id: str) -> threading.Lock:
        with self._entity_locks_guard:
            return self._entity_locks.setdefault(entity_id, threading.Lock())

    def _record_lock(self, action_id: str) -> threading.Lock:
        with self._record_locks_guard:
            return self._record_locks.setdefault(action_id, threading.Lock())

    # ------------------------------------------------------------------
    # Outer lock (P8)
    # ------------------------------------------------------------------

    def blocking_record_for_entity(self, operational_entity_id: str) -> dict | None:
        for raw in self._backend.list_by_entity(operational_entity_id):
            if is_blocking(raw):
                return raw
        return None

    def create(self, record: ActionRecord) -> ActionRecord:
        """P8: refuses ``entity_action_in_flight`` / ``entity_quarantined``.

        A duplicate ``action_id`` (browser retry, double submit) returns the
        existing record and creates nothing new -- P7's idempotency subject.
        """
        existing = self.get(record.action_id)
        if existing is not None:
            return existing

        lock = self._entity_lock(record.operational_entity_id)
        with lock:
            blocking = self.blocking_record_for_entity(record.operational_entity_id)
            if blocking is not None:
                if is_quarantining(blocking):
                    raise EntityQuarantinedError(record.operational_entity_id)
                raise EntityActionInFlightError(record.operational_entity_id)
            try:
                self._backend.create(record.to_dict())
            except EvidenceBackendError:
                # Lost a create race against a concurrent identical request.
                existing = self.get(record.action_id)
                if existing is not None:
                    return existing
                raise
        created = self.get(record.action_id)
        assert created is not None
        return created

    # ------------------------------------------------------------------
    # Reads
    # ------------------------------------------------------------------

    def get(self, action_id: str) -> ActionRecord | None:
        raw = self._backend.get(action_id)
        return ActionRecord.from_dict(raw) if raw is not None else None

    def list_all(self) -> list[ActionRecord]:
        return [ActionRecord.from_dict(r) for r in self._backend.list_all()]

    # ------------------------------------------------------------------
    # The guarded transition (P6) -- exactly one winner
    # ------------------------------------------------------------------

    def guarded_transition(
        self,
        action_id: str,
        *,
        from_states: Iterable[ActionState],
        to_state: ActionState,
        mutate: Callable[[ActionRecord], dict] | None = None,
        reason_code: str | None = None,
    ) -> ActionRecord | None:
        """A from-state-guarded durable write. Returns the updated record if
        *this* call won the transition, or ``None`` if the record was not in
        one of ``from_states`` when the lock was acquired (already moved by
        another writer, a restart's reconciliation, or a race loser) -- the
        caller must re-read to see the current truth (P6/P7: the loser
        submits nothing).

        Raises ``IllegalTransitionError`` if the requested edge is not in the
        frozen ``LEGAL_TRANSITIONS`` graph for *any* of ``from_states`` --
        this can never depend on the record's actual current state, so it is
        checked unconditionally before the lock is even taken.
        """
        from_states = frozenset(from_states)
        for state in from_states:
            if not is_legal_transition(state, to_state):
                raise IllegalTransitionError(f"{state.value} -> {to_state.value} is not a legal transition")

        lock = self._record_lock(action_id)
        with lock:
            current = self.get(action_id)
            if current is None or current.state not in from_states:
                return None
            fields: dict = dict(mutate(current)) if mutate is not None else {}
            fields["state"] = to_state.value
            fields["transitions"] = list(current.transitions) + [
                {
                    "from": current.state.value,
                    "to": to_state.value,
                    "at": utc_now(),
                    "reason_code": reason_code,
                }
            ]
            if to_state in TERMINAL_STATES:
                fields.setdefault("finished_at", utc_now())
                fields.setdefault("terminal_reason", reason_code)
            self._backend.update(action_id, **fields)
        return self.get(action_id)

    # ------------------------------------------------------------------
    # Quarantine acknowledgement (P10) -- authorization is the coordinator's job
    # ------------------------------------------------------------------

    def acknowledge(self, action_id: str, *, actor_ref: str) -> ActionRecord:
        record = self.get(action_id)
        if record is None:
            raise ValueError(f"unknown action_id {action_id!r}")
        if record.state != ActionState.OUTCOME_UNKNOWN:
            raise ValueError("only an OUTCOME_UNKNOWN record may be acknowledged")
        if record.acknowledged_at:
            return record
        self._backend.update(action_id, acknowledged_at=utc_now(), acknowledged_by=actor_ref)
        updated = self.get(action_id)
        assert updated is not None
        return updated
