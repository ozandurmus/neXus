"""CON.2 C2-4/C2-5/C2-9 — durable console job records.

One durable record per operator-triggered job, stored through the
``utils.evidence_backend`` abstraction (a sixth concern alongside DEV.3.3's
five, filesystem default / opt-in PostgreSQL). This module owns idempotency
policy (C2-9) and the crash-recovery sweep (C2-5); the backend itself is
dumb storage and has no opinion about either.

Forbidden in a job record, enforced by construction (``JobRecord`` has no
such field, so nothing can ever pass one through): credentials or tokens,
management addresses, hostnames beyond the ``entity_id`` values already
carried in ``targets``, raw device output, backup bytes, file paths outside
the runtime root, and stack traces. ``error_summary`` is redaction-registry
filtered and length-bounded before it is ever written.
"""
from __future__ import annotations

import uuid
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from utils.evidence_backend import EvidenceBackendError, select_console_job_backend
from utils.logger import redact_sensitive_text

TERMINAL_STATES = frozenset({"succeeded", "failed", "blocked", "skipped"})
_MAX_ERROR_SUMMARY_LENGTH = 500


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _bound_error_summary(text: str) -> str:
    return redact_sensitive_text(str(text))[:_MAX_ERROR_SUMMARY_LENGTH]


@dataclass
class JobRecord:
    job_id: str
    idempotency_key: str
    job_type: str
    command_class: str
    targets: list = field(default_factory=list)
    state: str = "queued"
    requested_at: str = field(default_factory=_utc_now)
    started_at: str | None = None
    finished_at: str | None = None
    run_id: str | None = None
    coordinator_decision: str | None = None
    outcome_counts: dict | None = None
    error_code: str | None = None
    error_summary: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


class ConsoleJobStore:
    def __init__(self, data_root: Path) -> None:
        self._backend = select_console_job_backend(root=Path(data_root) / "state" / "console_jobs")

    def submit(
        self, *, job_type: str, command_class: str, targets: list, idempotency_key: str
    ) -> tuple[JobRecord, bool]:
        """C2-9: a repeated ``idempotency_key`` returns the original record
        and creates no second job — the caller must only enqueue the record
        for execution when the returned ``is_new`` is ``True``. C2-5:
        durable in ``queued`` before this call returns, so the runner may
        not pick up a job that was never made durable."""
        existing = self._backend.get_by_idempotency_key(idempotency_key)
        if existing is not None:
            return JobRecord(**existing), False
        record = JobRecord(
            job_id=uuid.uuid4().hex,
            idempotency_key=idempotency_key,
            job_type=job_type,
            command_class=command_class,
            targets=list(targets),
        )
        try:
            self._backend.create(record.to_dict())
        except EvidenceBackendError:
            # Lost a create race against a concurrent identical request; its
            # record is durable now -- return that one rather than erroring.
            existing = self._backend.get_by_idempotency_key(idempotency_key)
            if existing is not None:
                return JobRecord(**existing), False
            raise
        return record, True

    def get(self, job_id: str) -> JobRecord | None:
        record = self._backend.get(job_id)
        return JobRecord(**record) if record is not None else None

    def list_all(self) -> list[JobRecord]:
        return [JobRecord(**r) for r in self._backend.list_all()]

    def mark_running(self, job_id: str) -> None:
        self._backend.update(job_id, state="running", started_at=_utc_now())

    def mark_terminal(
        self,
        job_id: str,
        *,
        state: str,
        run_id: str | None = None,
        coordinator_decision: str | None = None,
        outcome_counts: dict | None = None,
        error_code: str | None = None,
        error_summary: str | None = None,
    ) -> None:
        if state not in TERMINAL_STATES:
            raise ValueError(f"not a terminal state: {state!r}")
        fields: dict[str, Any] = {"state": state, "finished_at": _utc_now()}
        if run_id is not None:
            fields["run_id"] = run_id
        if coordinator_decision is not None:
            fields["coordinator_decision"] = coordinator_decision
        if outcome_counts is not None:
            fields["outcome_counts"] = outcome_counts
        if error_code is not None:
            fields["error_code"] = error_code
        if error_summary is not None:
            fields["error_summary"] = _bound_error_summary(error_summary)
        self._backend.update(job_id, **fields)

    def sweep_orphaned_running(self) -> list[str]:
        """C2-5: called once at console startup, before the runner starts.
        A record left ``running`` by a process that died is a crash, not a
        zombie -- it becomes ``failed`` / ``console_restarted``."""
        return self._backend.mark_orphaned_running_as_failed(error_code="console_restarted")
