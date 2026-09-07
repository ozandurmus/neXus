"""M9 -- immutable enrollment audit trail (condition 14, `AC-EN-8`).

Frozen contract: `docs/design/LOCAL_CONTROL_PLANE_RUNTIME_AND_ENROLLMENT.md`
§9.1 condition 14 / §15.4 `AC-EN-8` -- "an immutable audit record written
before the registry mutation." This module is that record.

Two immutable rows per confirmed enrollment attempt, both written via
``create`` only -- never ``update`` -- so "immutable" is structural, not a
convention: a *confirmation* row, written and durable before
``utils.device_registry.DeviceRegistry.enroll`` is ever called, and a
*outcome* row, written after ``enroll`` resolves, naming what actually
happened. Neither row is ever rewritten; a caller that wants to know the
final state of one enrollment attempt reads both rows by their linking
``confirmation_audit_id``.

Deliberately not built on `utils/control_plane_store.py`: that store's own
contract (§6.4/`AC-ST-3`) forbids exactly the data an enrollment audit trail
must carry -- "Device Registry rows, copied endpoints." This module lives
beside the Device Registry's own filesystem-JSON world instead, the same
place `credential_ref`/`endpoint` are already legitimately persisted.

Endpoint, port, vendor hint and the opaque credential-profile/trust-profile
references are the same category of data `utils/device_registry.py` already
persists -- never a secret, never raw device output, never a serial or
host-key fingerprint.
"""
from __future__ import annotations

import uuid
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from utils.evidence_backend import EvidenceBackendError, select_enrollment_audit_backend

_MAX_FREE_TEXT_LENGTH = 255

#: Closed vocabularies. Extending either is a contract amendment, not a
#: runtime parameter.
AUDIT_KINDS: tuple[str, ...] = ("confirmation", "outcome")
OUTCOME_VALUES: tuple[str, ...] = (
    "enrolled", "refused_duplicate", "refused_lock", "refused_other",
)


class EnrollmentAuditError(RuntimeError):
    """Fail-closed error for an invalid request or an unreadable audit store."""


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _require_nonempty(name: str, value: Any) -> str:
    if not isinstance(value, str) or not value.strip():
        raise EnrollmentAuditError(f"{name} must be a non-empty string")
    return value.strip()


def _require_closed(name: str, value: Any, vocabulary: tuple[str, ...]) -> str:
    if value not in vocabulary:
        raise EnrollmentAuditError(f"{name}={value!r} is not in the closed vocabulary {vocabulary!r}")
    return value


def _bound_free_text(name: str, value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    if len(text) > _MAX_FREE_TEXT_LENGTH or any(ch in text for ch in ("\x00", "\r", "\n")):
        raise EnrollmentAuditError(f"{name} contains unsupported characters or is too long")
    return text


@dataclass(frozen=True)
class EnrollmentAuditRecord:
    """One row exactly as stored. ``kind`` distinguishes the two immutable
    halves of one enrollment attempt (a *confirmation* row has ``outcome``
    and ``device_id`` unset; an *outcome* row has them set and links back
    via ``confirmation_audit_id``)."""

    audit_id: str
    kind: str
    probe_job_id: str
    confirmation_audit_id: str | None
    endpoint: str | None
    port: int | None
    vendor_hint: str | None
    credential_ref: str | None
    trust_ref: str | None
    tags: dict[str, str] = field(default_factory=dict)
    outcome: str | None = None
    device_id: str | None = None
    reason_code: str | None = None
    recorded_at_utc: str = ""

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


class EnrollmentAuditStore:
    """Filesystem-JSON-backed, one immutable file per row, under
    ``<data_root>/state/enrollment_audit/``."""

    def __init__(self, data_root: Path) -> None:
        self._data_root = Path(data_root)
        self._backend = select_enrollment_audit_backend(root=self._data_root / "state" / "enrollment_audit")

    def record_confirmation(
        self,
        *,
        probe_job_id: str,
        endpoint: str,
        port: int | None,
        vendor_hint: str,
        credential_ref: str | None,
        trust_ref: str | None,
        tags: dict[str, str] | None = None,
    ) -> EnrollmentAuditRecord:
        """Written and durable BEFORE the caller may invoke
        ``DeviceRegistry.enroll`` (condition 14) -- the caller is responsible
        for that ordering; this call itself just makes the row durable and
        returns."""
        record = EnrollmentAuditRecord(
            audit_id=uuid.uuid4().hex,
            kind="confirmation",
            probe_job_id=_require_nonempty("probe_job_id", probe_job_id),
            confirmation_audit_id=None,
            endpoint=_require_nonempty("endpoint", endpoint),
            port=port,
            vendor_hint=_bound_free_text("vendor_hint", vendor_hint),
            credential_ref=_bound_free_text("credential_ref", credential_ref),
            trust_ref=_bound_free_text("trust_ref", trust_ref),
            tags=dict(tags or {}),
            recorded_at_utc=_utc_now_iso(),
        )
        self._create(record)
        return record

    def record_outcome(
        self,
        *,
        confirmation_audit_id: str,
        probe_job_id: str,
        outcome: str,
        device_id: str | None = None,
        reason_code: str | None = None,
    ) -> EnrollmentAuditRecord:
        """A second, independent immutable row -- never an update of the
        confirmation row it links to via ``confirmation_audit_id``."""
        record = EnrollmentAuditRecord(
            audit_id=uuid.uuid4().hex,
            kind="outcome",
            probe_job_id=_require_nonempty("probe_job_id", probe_job_id),
            confirmation_audit_id=_require_nonempty("confirmation_audit_id", confirmation_audit_id),
            endpoint=None,
            port=None,
            vendor_hint=None,
            credential_ref=None,
            trust_ref=None,
            outcome=_require_closed("outcome", outcome, OUTCOME_VALUES),
            device_id=device_id,
            reason_code=_bound_free_text("reason_code", reason_code),
            recorded_at_utc=_utc_now_iso(),
        )
        self._create(record)
        return record

    def _create(self, record: EnrollmentAuditRecord) -> None:
        try:
            self._backend.create(record.to_dict())
        except EvidenceBackendError as exc:
            raise EnrollmentAuditError(f"enrollment audit record could not be written safely: {exc}") from exc

    def get(self, audit_id: str) -> EnrollmentAuditRecord | None:
        row = self._backend.get(audit_id)
        return EnrollmentAuditRecord(**row) if row is not None else None

    def list_all(self) -> list[EnrollmentAuditRecord]:
        return [EnrollmentAuditRecord(**row) for row in self._backend.list_all()]
