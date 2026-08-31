"""RB.3b decision B4 — durable per-endpoint ``operational-write`` ledger.

``BACKUP_RECOVERY_CONTRACTS.md`` §7.3 point 6 makes "at most one
``add backup local`` per endpoint per 24 h" a *hard* enforcement rather than a
convention. ``utils.collection_executor.CollectionCoordinator`` is process-local
and in-memory: it stops two *concurrent* backups but not a second one ten
minutes later, and a restart discards what it knew. ``add backup local``
consumes ``/var/log`` disk on a production firewall, so the ceiling needs
durable state — this module, on the DEV.3.3 evidence backend (filesystem
default, opt-in Postgres, shared across containers when configured).

Fail-closed read contract (design doc §5):

| Ledger state                         | Decision                                  |
|--------------------------------------|-------------------------------------------|
| absent (no file / empty table)       | proceed — first backup                     |
| readable, entry inside the 24 h window| skip, zero device contact                 |
| readable, newest entry older         | proceed                                    |
| **unreadable** (corrupt / I/O /       | **BLOCK** — ``OperationalLedgerUnreadable  |
| Postgres unreachable / query error)  | Error``; no command sent                   |

"I couldn't tell whether I already backed this up today" resolves to *do not
back it up again* — a false refusal (missed backup, recoverable next run) is
chosen deliberately over a false proceed (a second disk-consuming write inside
the window). ``command_class`` is the artifact class (``cp_gaia_backup``), not
the literal device command, so the RB.3c classes reuse this without a schema
change.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from utils.evidence_backend import (
    EvidenceBackendError,
    OperationalWriteLedgerBackend,
    select_operational_write_ledger_backend,
)

LEDGER_RELATIVE_PATH = "state/recovery_operational_ledger.json"
DEFAULT_WINDOW = timedelta(hours=24)

_VALID_OUTCOMES = ("completed", "failed", "cleanup_failed")


class OperationalLedgerUnreadableError(EvidenceBackendError):
    """The ledger exists but cannot be read/parsed, or the Postgres backend is
    unreachable / errored. Fail-closed: the caller MUST NOT run the
    ``operational-write``."""


@dataclass(frozen=True)
class LedgerEntry:
    entity_id: str
    command_class: str          # "cp_gaia_backup" today — the artifact class, not the command string
    executed_at: datetime       # tz-aware UTC
    outcome: str                # "completed" | "failed" | "cleanup_failed"
    run_id: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "entity_id": self.entity_id,
            "command_class": self.command_class,
            "executed_at": self.executed_at.astimezone(timezone.utc).isoformat(),
            "outcome": self.outcome,
            "run_id": self.run_id,
        }

    @classmethod
    def from_dict(cls, raw: dict[str, Any]) -> "LedgerEntry":
        executed_at = _parse_utc(raw.get("executed_at"))
        if executed_at is None:
            raise OperationalLedgerUnreadableError(
                f"operational-write ledger entry has an unparseable executed_at: {raw.get('executed_at')!r}"
            )
        return cls(
            entity_id=str(raw.get("entity_id") or ""),
            command_class=str(raw.get("command_class") or ""),
            executed_at=executed_at,
            outcome=str(raw.get("outcome") or ""),
            run_id=(str(raw["run_id"]) if raw.get("run_id") not in (None, "") else None),
        )


def _parse_utc(value: Any) -> datetime | None:
    if isinstance(value, datetime):
        return value if value.tzinfo is not None else value.replace(tzinfo=timezone.utc)
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo is not None else parsed.replace(tzinfo=timezone.utc)


def ledger_path(data_root: Any) -> Path:
    return Path(data_root) / LEDGER_RELATIVE_PATH


class RecoveryOperationalLedger:
    """Reads and appends the durable ``operational-write`` ledger. Both the read
    and the write must happen *inside* the admission-held section (design doc
    §7) so two containers past the window racing the same endpoint serialise
    correctly."""

    def __init__(self, backend: OperationalWriteLedgerBackend) -> None:
        self._backend = backend

    @classmethod
    def from_data_root(cls, data_root: Any) -> "RecoveryOperationalLedger":
        return cls(
            select_operational_write_ledger_backend(state_file=ledger_path(data_root))
        )

    def last_execution(self, *, entity_id: str, command_class: str) -> LedgerEntry | None:
        """Newest entry for the pair, or ``None`` when there has genuinely never
        been one. Raises ``OperationalLedgerUnreadableError`` if the store
        cannot be read — never conflates 'unreadable' with 'none'."""
        try:
            rows = self._backend.entries_for(entity_id=entity_id, command_class=command_class)
        except OperationalLedgerUnreadableError:
            raise
        except EvidenceBackendError as exc:
            raise OperationalLedgerUnreadableError(str(exc)) from exc
        if not rows:
            return None
        entries = [LedgerEntry.from_dict(row) for row in rows]
        entries.sort(key=lambda e: e.executed_at, reverse=True)
        return entries[0]

    def within_window(
        self,
        *,
        entity_id: str,
        command_class: str,
        now: datetime,
        window: timedelta = DEFAULT_WINDOW,
    ) -> bool:
        last = self.last_execution(entity_id=entity_id, command_class=command_class)
        if last is None:
            return False
        return (now.astimezone(timezone.utc) - last.executed_at) < window

    def record_execution(
        self,
        *,
        entity_id: str,
        command_class: str,
        executed_at: datetime,
        outcome: str,
        run_id: str | None,
    ) -> None:
        """Append one entry. Called once per endpoint, AFTER ``add backup
        local`` was actually sent to the device — ``completed`` / ``failed`` /
        ``cleanup_failed`` all recorded (design doc §6). NOT called when the run
        aborts at or before the free-space precondition."""
        if outcome not in _VALID_OUTCOMES:
            raise ValueError(
                f"operational-write ledger outcome must be one of {_VALID_OUTCOMES}, got {outcome!r}"
            )
        entry = LedgerEntry(
            entity_id=entity_id,
            command_class=command_class,
            executed_at=executed_at,
            outcome=outcome,
            run_id=run_id,
        )
        self._backend.append(entry.to_dict())
