"""Local runtime inventory exclusion policy.

Environment-specific inventory identities belong to RuntimeRoot, never to the
source repository.  This module is deliberately vendor-neutral; collectors
consume only the identities for their own vendor.

Write path (``add_exclusion`` / ``restore_exclusion``): backend logic only,
built ahead of any UI. `inventory_exclusions_management_ui` (the UI phase)
is DEPLOY.1A-gated -- write access here controls which devices get polled at
all, so it needs an authenticated, authorized, audited actor before any
control reaches an untrusted caller. Nothing in this repository currently
calls these two functions; they exist so that future gated UI has a tested
API to call, not to expose a write path today. Do not wire either into
main.py, html_export.py or any HTTP-reachable surface before the DEPLOY.1A
OIDC/RBAC boundary exists.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import json
from pathlib import Path
from typing import Any, Iterable


POLICY_RELATIVE_PATH = Path("state") / "inventory_exclusions.json"
SUPPORTED_SCHEMA_VERSION = 1

AUDIT_RELATIVE_PATH = Path("state") / "inventory_exclusions_audit.json"
AUDIT_SCHEMA_VERSION = 1
MAX_AUDIT_ENTRIES = 500  # ledger cap; oldest trimmed on append, newest kept


class InventoryExclusionPolicyError(RuntimeError):
    """Raised when a local exclusion policy exists but is not safe to use."""


@dataclass(frozen=True)
class InventoryExclusion:
    vendor: str
    identity: str
    reason: str


@dataclass(frozen=True)
class InventoryExclusionPolicy:
    source: str
    entries: tuple[InventoryExclusion, ...]

    def identities_for(self, vendor: str) -> tuple[str, ...]:
        wanted = str(vendor or "").strip().lower()
        return tuple(entry.identity for entry in self.entries if entry.vendor == wanted)

    def count_for(self, vendor: str) -> int:
        return len(self.identities_for(vendor))


@dataclass(frozen=True)
class InventoryExclusionAuditEntry:
    """One append-only audit record for a write-path change.

    ``actor`` is ``None`` today (no authenticated caller exists yet); the
    field exists so a DEPLOY.1A caller can pass an OIDC principal without a
    schema change later.
    """
    timestamp: str
    action: str  # "added" | "restored"
    vendor: str
    identity: str
    reason: str
    actor: str | None


def policy_path(data_root: Path) -> Path:
    return Path(data_root) / POLICY_RELATIVE_PATH


def audit_path(data_root: Path) -> Path:
    return Path(data_root) / AUDIT_RELATIVE_PATH


def _validate_identity(value: object) -> str:
    if not isinstance(value, str) or not value.strip():
        raise InventoryExclusionPolicyError("inventory exclusion identity must be a non-empty string")
    identity = value.strip()
    if len(identity) > 255 or any(ch in identity for ch in ("\x00", "\r", "\n")):
        raise InventoryExclusionPolicyError("inventory exclusion identity contains unsupported characters")
    return identity


def load_inventory_exclusions(data_root: Path) -> InventoryExclusionPolicy:
    """Load the local-only policy without logging or returning matched values."""
    path = policy_path(data_root)
    if not path.exists():
        return InventoryExclusionPolicy(source="missing", entries=())
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise InventoryExclusionPolicyError("inventory exclusion policy cannot be read safely") from exc

    if not isinstance(raw, dict) or raw.get("version") != SUPPORTED_SCHEMA_VERSION:
        raise InventoryExclusionPolicyError("inventory exclusion policy has an unsupported schema version")
    rows = raw.get("exclusions")
    if not isinstance(rows, list):
        raise InventoryExclusionPolicyError("inventory exclusion policy exclusions must be a list")

    entries: list[InventoryExclusion] = []
    seen: set[tuple[str, str]] = set()
    for row in rows:
        if not isinstance(row, dict):
            raise InventoryExclusionPolicyError("inventory exclusion entry must be an object")
        if row.get("enabled", True) is False:
            continue
        vendor = str(row.get("vendor") or "").strip().lower()
        if not vendor:
            raise InventoryExclusionPolicyError("inventory exclusion vendor is required")
        identity = _validate_identity(row.get("identity"))
        reason = str(row.get("reason") or "manual").strip() or "manual"
        key = (vendor, identity)
        if key in seen:
            continue
        seen.add(key)
        entries.append(InventoryExclusion(vendor=vendor, identity=identity, reason=reason))

    return InventoryExclusionPolicy(source="runtime-policy", entries=tuple(entries))


def _load_raw_document(data_root: Path) -> dict[str, Any]:
    """Load the policy file's raw dict, including disabled rows that
    ``load_inventory_exclusions`` intentionally drops. Write-path only: the
    write functions need to see a disabled row to re-enable it in place
    rather than appending a duplicate.
    """
    path = policy_path(data_root)
    if not path.exists():
        return {"version": SUPPORTED_SCHEMA_VERSION, "exclusions": []}
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise InventoryExclusionPolicyError("inventory exclusion policy cannot be read safely") from exc
    if not isinstance(raw, dict) or raw.get("version") != SUPPORTED_SCHEMA_VERSION:
        raise InventoryExclusionPolicyError("inventory exclusion policy has an unsupported schema version")
    if not isinstance(raw.get("exclusions"), list):
        raise InventoryExclusionPolicyError("inventory exclusion policy exclusions must be a list")
    return raw


def _atomic_write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    tmp.replace(path)


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _validate_reason(value: object) -> str:
    if not isinstance(value, str) or not value.strip():
        raise InventoryExclusionPolicyError(
            "a non-empty reason is required for this action -- unlike the read path's "
            "'manual' fallback for pre-existing entries, a new write-path change must "
            "always state why"
        )
    reason = value.strip()
    if len(reason) > 500 or any(ch in reason for ch in ("\x00", "\r", "\n")):
        raise InventoryExclusionPolicyError("reason contains unsupported characters or is too long")
    return reason


def load_inventory_exclusions_audit(
    data_root: Path, *, limit: int | None = None
) -> list[InventoryExclusionAuditEntry]:
    """Every stored audit record, oldest first. Missing/unreadable/malformed
    -> [] (fail-safe read; audit *display* must never break a render, even
    though the *write* path below is fail-closed on its own audit append).
    """
    path = audit_path(data_root)
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return []
    rows = raw.get("entries") if isinstance(raw, dict) else None
    if not isinstance(rows, list):
        return []
    entries: list[InventoryExclusionAuditEntry] = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        try:
            entries.append(InventoryExclusionAuditEntry(
                timestamp=str(row["timestamp"]),
                action=str(row["action"]),
                vendor=str(row["vendor"]),
                identity=str(row["identity"]),
                reason=str(row["reason"]),
                actor=row.get("actor"),
            ))
        except KeyError:
            continue
    return entries[-limit:] if limit else entries


def _append_audit_entry(data_root: Path, entry: InventoryExclusionAuditEntry) -> None:
    """Fail-CLOSED: unlike compliance_history's best-effort trend ledger, a
    security-relevant audit record must not be silently dropped. An OSError
    here propagates -- the caller (add_exclusion/restore_exclusion) applies
    the audit write before the policy write specifically so a failed audit
    write leaves the policy file untouched rather than making an unaudited
    change.
    """
    existing = load_inventory_exclusions_audit(data_root)
    rows = [
        {
            "timestamp": e.timestamp, "action": e.action, "vendor": e.vendor,
            "identity": e.identity, "reason": e.reason, "actor": e.actor,
        }
        for e in existing
    ]
    rows.append({
        "timestamp": entry.timestamp, "action": entry.action, "vendor": entry.vendor,
        "identity": entry.identity, "reason": entry.reason, "actor": entry.actor,
    })
    rows = rows[-MAX_AUDIT_ENTRIES:]
    _atomic_write_json(audit_path(data_root), {
        "schema_version": AUDIT_SCHEMA_VERSION,
        "updated_at": _utc_now_iso(),
        "entries": rows,
    })


def add_exclusion(
    data_root: Path, *, vendor: str, identity: str, reason: str, actor: str | None = None
) -> InventoryExclusionPolicy:
    """Add (or re-enable, if already present but disabled) one exclusion.

    Idempotent on ``(vendor, identity)``: a second call with the same pair
    updates the reason on the existing row rather than duplicating it.
    Fail-closed -- validation, then the audit record, then the policy file;
    any failure leaves the policy file exactly as it was.
    """
    vendor_key = str(vendor or "").strip().lower()
    if not vendor_key:
        raise InventoryExclusionPolicyError("vendor is required")
    identity_value = _validate_identity(identity)
    reason_value = _validate_reason(reason)

    document = _load_raw_document(data_root)
    rows = list(document["exclusions"])
    matched = False
    for row in rows:
        if not isinstance(row, dict):
            continue
        if str(row.get("vendor") or "").strip().lower() == vendor_key and \
                str(row.get("identity") or "").strip() == identity_value:
            row["enabled"] = True
            row["reason"] = reason_value
            matched = True
            break
    if not matched:
        rows.append({
            "vendor": vendor_key, "identity": identity_value,
            "reason": reason_value, "enabled": True,
        })

    timestamp = _utc_now_iso()
    _append_audit_entry(data_root, InventoryExclusionAuditEntry(
        timestamp=timestamp, action="added", vendor=vendor_key,
        identity=identity_value, reason=reason_value, actor=actor,
    ))
    _atomic_write_json(policy_path(data_root), {
        "version": SUPPORTED_SCHEMA_VERSION, "exclusions": rows,
    })
    return load_inventory_exclusions(data_root)


def restore_exclusion(
    data_root: Path, *, vendor: str, identity: str, reason: str, actor: str | None = None
) -> InventoryExclusionPolicy:
    """Restore (soft-disable, never delete) one exclusion so it stops being
    applied while the row -- and its history -- is preserved for audit.

    Raises if no matching enabled row exists (nothing to restore). Same
    fail-closed audit-then-policy ordering as ``add_exclusion``.
    """
    vendor_key = str(vendor or "").strip().lower()
    identity_value = _validate_identity(identity)
    reason_value = _validate_reason(reason)

    document = _load_raw_document(data_root)
    rows = list(document["exclusions"])
    matched = False
    for row in rows:
        if not isinstance(row, dict):
            continue
        if str(row.get("vendor") or "").strip().lower() == vendor_key and \
                str(row.get("identity") or "").strip() == identity_value and \
                row.get("enabled", True) is not False:
            row["enabled"] = False
            row["reason"] = reason_value
            matched = True
            break
    if not matched:
        raise InventoryExclusionPolicyError(
            f"no active exclusion found for vendor={vendor_key!r} identity={identity_value!r}"
        )

    timestamp = _utc_now_iso()
    _append_audit_entry(data_root, InventoryExclusionAuditEntry(
        timestamp=timestamp, action="restored", vendor=vendor_key,
        identity=identity_value, reason=reason_value, actor=actor,
    ))
    _atomic_write_json(policy_path(data_root), {
        "version": SUPPORTED_SCHEMA_VERSION, "exclusions": rows,
    })
    return load_inventory_exclusions(data_root)


def checkpoint_transport_value(identities: Iterable[str]) -> str:
    """Encode exact CP object names for the existing comma-delimited shell hook.

    Commas are rejected rather than guessed/escaped because the remote shell
    collector intentionally performs exact-name matching with a simple format.
    """
    values = []
    for value in identities:
        identity = _validate_identity(value)
        if "," in identity:
            raise InventoryExclusionPolicyError(
                "Check Point exclusion identity cannot contain a comma with the current exact-match transport"
            )
        values.append(identity)
    return ",".join(values)
