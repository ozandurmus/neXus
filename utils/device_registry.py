"""Device Registry — PCP.1 first persistent product object.

docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md section 21 is this
module's authoritative, frozen contract (AC-1a..AC-15); this docstring
names it, it does not restate it. Filesystem-only storage lives behind
``utils/evidence_backend.py::DeviceRegistryBackend`` (the eighth concern);
this module owns every business rule -- normalization, duplicate detection,
lifecycle transitions, fail-closed corrupt-data handling, and the single
narrow cross-process mutation lock -- so both a future Postgres backend and
today's filesystem one would behave identically.

No device contact. No vendor/collector import. No credential resolution:
``credential_ref`` is a bounded opaque reference, never resolved here.
"""
from __future__ import annotations

import ipaddress
import json
import os
import re
import socket
import uuid
from contextlib import contextmanager
from dataclasses import asdict, dataclass, field, fields
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator

from utils.evidence_backend import EvidenceBackendError, select_device_registry_backend
from utils.runtime_paths import discover_repository_root

SCHEMA_VERSION = 1

_MAX_FREE_TEXT_LENGTH = 255
_MAX_TAGS = 32
_CREDENTIAL_REF_RE = re.compile(r"^[A-Za-z0-9_.-]{1,64}$")

# Side states DISABLED/RETIRED are part of the frozen schema's lifecycle
# vocabulary; only ENROLLED_UNVERIFIED/DISABLED are reachable from any
# PCP.1 entry point (AC-3) -- RETIRED/CONTACT_VERIFIED/OBSERVED are accepted
# on load (a later movement may persist them) but no code path here ever
# produces them.
LIFECYCLE_STATES = frozenset({
    "ENROLLED_UNVERIFIED", "DISABLED", "RETIRED", "CONTACT_VERIFIED", "OBSERVED",
})
VENDOR_VALUES = frozenset({"checkpoint", "paloalto", "unknown"})
CLASSIFICATION_BASIS_VALUES = frozenset({
    "operator_hint", "management_discovery", "first_contact_evidence",
})
ENROLLMENT_SOURCE_VALUES = frozenset({"manual", "panorama", "cp_management"})


class DeviceRegistryError(RuntimeError):
    """Fail-closed error for an invalid request or a corrupt/unsupported
    persisted registry document. Raised from every entry point (enroll,
    list, disable) -- never silently treated as empty or partially loaded."""


class DeviceRegistryLockError(DeviceRegistryError):
    """Raised when the registry mutation lock cannot be acquired: immediate
    fail-closed refusal, before any load, validate, duplicate-check or
    write -- never a wait, retry, queue or unprotected fallback (AC-13)."""


@dataclass(frozen=True)
class DeviceRecord:
    """Closed field set (AC-2a): a fixed dataclass, not an open dict. No
    field named or shaped to carry a secret value exists anywhere here."""

    device_id: str
    endpoint: str  # already-normalized management address/FQDN; LOCAL-SENSITIVE
    port: int | None
    vendor: str
    classification_basis: str
    credential_ref: str | None  # named profile *reference* only -- never a secret (AC-2b)
    enrollment_source: str
    state: str
    site: str | None
    tags: dict[str, str] = field(default_factory=dict)
    environment: str | None = None
    relationships: list = field(default_factory=list)  # always [] in PCP.1 -- structural
    schema_version: int = SCHEMA_VERSION
    created_at: str = ""
    updated_at: str = ""

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


_RECORD_FIELD_NAMES = frozenset(f.name for f in fields(DeviceRecord))


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# ---------------------------------------------------------------------------
# Endpoint normalization (representation-only, no network resolution)
# ---------------------------------------------------------------------------

def _is_ip_literal(value: str) -> bool:
    try:
        ipaddress.ip_address(value)
        return True
    except ValueError:
        return False


def _parse_port_suffix(port_text: str) -> int:
    if not port_text.isdigit():
        raise DeviceRegistryError(f"malformed endpoint port: {port_text!r}")
    return _validate_port(int(port_text))


def _split_endpoint_and_port(text: str) -> tuple[str, int | None]:
    """Split an optional explicit port from the raw endpoint string.

    The port is compared literally as a separate field, never folded into
    the (normalized) endpoint string. Bracketed ``[literal]:port`` is the
    only accepted form for an IPv6 literal with a port, so a bare IPv6
    literal's own colons are never mistaken for a port separator.
    """
    if text.startswith("["):
        closing = text.find("]")
        if closing == -1:
            raise DeviceRegistryError("malformed bracketed IPv6 endpoint: missing closing ']'")
        host = text[1:closing]
        remainder = text[closing + 1:]
        if not remainder:
            return host, None
        if not remainder.startswith(":"):
            raise DeviceRegistryError("malformed endpoint: unexpected characters after ']'")
        return host, _parse_port_suffix(remainder[1:])
    if _is_ip_literal(text):
        return text, None  # a bare IP literal's colons (IPv6) are part of the address
    if text.count(":") > 1:
        raise DeviceRegistryError(
            "malformed endpoint: use [literal]:port to give an IPv6 literal an explicit port"
        )
    if ":" in text:
        host, _, port_text = text.partition(":")
        return host, _parse_port_suffix(port_text)
    return text, None


def normalize_endpoint(raw_endpoint: Any) -> tuple[str, int | None]:
    """Applied identically to enrollment input and to every existing record
    before any duplicate comparison (section 21 "Endpoint normalization"):
    strip whitespace; lower-case + strip one trailing '.' for a hostname/
    FQDN (RFC 4343); compare an IP literal byte-for-byte after whitespace
    stripping only -- no octet reformatting, no leading-zero handling, no
    v4/v6 canonicalization. No DNS resolution, no reverse lookup, ever.
    """
    if not isinstance(raw_endpoint, str) or not raw_endpoint.strip():
        raise DeviceRegistryError("endpoint must be a non-empty string")
    host, port = _split_endpoint_and_port(raw_endpoint.strip())
    if not host:
        raise DeviceRegistryError("endpoint must include a non-empty host/address")
    if _is_ip_literal(host):
        return host, port
    normalized = host.lower()
    if normalized.endswith(".") and len(normalized) > 1:
        normalized = normalized[:-1]
    return normalized, port


# ---------------------------------------------------------------------------
# Field validation (format-only; never a secret-detection guarantee)
# ---------------------------------------------------------------------------

def _validate_port(port: Any) -> int | None:
    if port is None:
        return None
    if isinstance(port, bool) or not isinstance(port, int):
        raise DeviceRegistryError("port must be an integer")
    if not (1 <= port <= 65535):
        raise DeviceRegistryError("port must be between 1 and 65535")
    return port


def _validate_vendor(vendor_hint: Any) -> str:
    value = str(vendor_hint or "unknown").strip().lower()
    if value not in VENDOR_VALUES:
        raise DeviceRegistryError(f"unsupported vendor hint: {vendor_hint!r}")
    return value


def _validate_credential_ref(credential_ref: Any) -> str | None:
    """AC-2b: a bounded opaque profile identifier, format-validated only to
    reject obviously malformed input -- never a secret-detection guarantee.
    An operator who pastes a real secret here still has it persisted
    verbatim as this field's string value; the format check constrains
    shape, it does not and cannot prove the value is not itself a secret.
    """
    if credential_ref is None:
        return None
    value = str(credential_ref).strip()
    if not _CREDENTIAL_REF_RE.match(value):
        raise DeviceRegistryError(
            "credential profile reference must match ^[A-Za-z0-9_.-]{1,64}$"
        )
    return value


def _validate_free_text(field_name: str, value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    if len(text) > _MAX_FREE_TEXT_LENGTH or any(ch in text for ch in ("\x00", "\r", "\n")):
        raise DeviceRegistryError(f"{field_name} contains unsupported characters or is too long")
    return text


def _validate_tags(tags: Any) -> dict[str, str]:
    if not tags:
        return {}
    if not isinstance(tags, dict):
        raise DeviceRegistryError("tags must be a mapping of key to value")
    if len(tags) > _MAX_TAGS:
        raise DeviceRegistryError(f"at most {_MAX_TAGS} tags are supported")
    validated: dict[str, str] = {}
    for key, value in tags.items():
        safe_key = _validate_free_text("tag key", key)
        if not safe_key:
            raise DeviceRegistryError("tag key must be a non-empty string")
        validated[safe_key] = _validate_free_text("tag value", value) or ""
    return validated


def _validate_persisted_record(row: Any) -> None:
    """Mirrors utils/inventory_exclusions.py's fail-closed posture: a
    malformed individual record inside an otherwise valid document raises
    the same typed error rather than being skipped (AC-11) -- a corrupt
    registry fails closed as a whole, not row-by-row.
    """
    if not isinstance(row, dict):
        raise DeviceRegistryError("device registry contains a non-object record")
    if set(row.keys()) != _RECORD_FIELD_NAMES:
        raise DeviceRegistryError("device registry record has an unrecognized or missing field")
    if not isinstance(row.get("device_id"), str) or not row["device_id"]:
        raise DeviceRegistryError("device registry record device_id must be a non-empty string")
    if not isinstance(row.get("endpoint"), str) or not row["endpoint"]:
        raise DeviceRegistryError("device registry record endpoint must be a non-empty string")
    port = row.get("port")
    if port is not None and (isinstance(port, bool) or not isinstance(port, int)):
        raise DeviceRegistryError("device registry record port must be an integer or null")
    if row.get("state") not in LIFECYCLE_STATES:
        raise DeviceRegistryError(f"device registry record has an unknown lifecycle state: {row.get('state')!r}")
    if row.get("vendor") not in VENDOR_VALUES:
        raise DeviceRegistryError(f"device registry record has an unknown vendor: {row.get('vendor')!r}")
    if row.get("classification_basis") not in CLASSIFICATION_BASIS_VALUES:
        raise DeviceRegistryError("device registry record has an unknown classification_basis")
    if row.get("enrollment_source") not in ENROLLMENT_SOURCE_VALUES:
        raise DeviceRegistryError("device registry record has an unknown enrollment_source")
    credential_ref = row.get("credential_ref")
    if credential_ref is not None and not _CREDENTIAL_REF_RE.match(str(credential_ref)):
        raise DeviceRegistryError("device registry record credential_ref fails its format check")
    if not isinstance(row.get("tags"), dict):
        raise DeviceRegistryError("device registry record tags must be an object")
    if row.get("site") is not None and not isinstance(row.get("site"), str):
        raise DeviceRegistryError("device registry record site must be a string or null")
    if row.get("environment") is not None and not isinstance(row.get("environment"), str):
        raise DeviceRegistryError("device registry record environment must be a string or null")
    if not isinstance(row.get("relationships"), list) or row["relationships"]:
        raise DeviceRegistryError("device registry record relationships must be an empty list in PCP.1")
    if row.get("schema_version") != SCHEMA_VERSION:
        raise DeviceRegistryError("device registry record has an unsupported schema_version")


# ---------------------------------------------------------------------------
# Registry mutation lock -- a single, narrow cross-process file lock
# ---------------------------------------------------------------------------
#
# Not a general concurrency framework: scoped to this module's own mutation
# path alone. `--registry-enroll`/`--registry-disable` acquire it before the
# load step and hold it across load -> validate -> duplicate-check-or-
# transition -> atomic-replace. `--registry-list` never takes it (read-only,
# already race-safe via atomic replace). Contention fails closed immediately
# -- no wait, retry, or queue. Crash/stale-lock recovery is explicit and
# manual (never automatic) -- see the module-level note at the bottom.

LOCK_FILENAME = "device_registry.lock"
REGISTRY_FILENAME = "device_registry.json"


def _acquire_lock(lock_path: Path) -> str:
    owner_token = uuid.uuid4().hex
    payload = {
        "pid": os.getpid(),
        "hostname": socket.gethostname(),
        "acquired_at_utc": _utc_now_iso(),
        "owner_token": owner_token,
    }
    data = json.dumps(payload, indent=2, ensure_ascii=False).encode("utf-8")
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        fd = os.open(str(lock_path), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError as exc:
        raise DeviceRegistryLockError(
            "registry mutation lock is held by another process -- refusing to wait, retry or "
            "queue. If the recorded holder is confirmed dead by independent means, a human must "
            "manually delete the lock file (never automatic -- see 'Crash / stale-lock recovery', "
            "PRODUCT_CONTROL_PLANE_ARCHITECTURE.md section 21)."
        ) from exc
    try:
        os.write(fd, data)
    finally:
        os.close(fd)
    return owner_token


def _release_lock(lock_path: Path, owner_token: str) -> None:
    """Instance-safe release (AC-15): deletes the lock file only when its
    current owner_token still equals the one this process wrote at
    acquisition. A missing file or a mismatched token means a different
    writer's instance is now at that path (a human deleted what they
    believed a stale lock and a new mutation created a fresh instance) --
    this process must never delete another writer's active lock.
    """
    try:
        raw = lock_path.read_text(encoding="utf-8")
        current = json.loads(raw)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return
    if not isinstance(current, dict) or current.get("owner_token") != owner_token:
        return
    try:
        lock_path.unlink()
    except OSError:
        pass


@contextmanager
def _held_lock(lock_path: Path) -> Iterator[None]:
    owner_token = _acquire_lock(lock_path)
    try:
        yield
    finally:
        _release_lock(lock_path, owner_token)


# ---------------------------------------------------------------------------
# DeviceRegistry service
# ---------------------------------------------------------------------------

def _assert_outside_repository(data_root: Path) -> None:
    """AC-4: writing to a path equal to or nested with the repository root
    is refused. The CLI's RuntimePaths bootstrap already enforces this
    separation for every caller; this is a direct, unit-testable defense
    scoped to this module alone.
    """
    repo_root = discover_repository_root().resolve()
    resolved = Path(data_root).expanduser().resolve()
    if resolved == repo_root:
        raise DeviceRegistryError("device registry data_root must not be the repository root")
    try:
        resolved.relative_to(repo_root)
    except ValueError:
        return
    raise DeviceRegistryError("device registry data_root must not be inside the repository root")


class DeviceRegistry:
    """PCP.1 Device Registry: enroll / list / disable over a single
    RuntimeRoot-resident JSON document, guarded by the registry mutation
    lock for the two mutating verbs.
    """

    def __init__(self, data_root: Path) -> None:
        _assert_outside_repository(data_root)
        self._data_root = Path(data_root)
        self._backend = select_device_registry_backend(path=self._registry_path())

    def _registry_path(self) -> Path:
        return self._data_root / "state" / REGISTRY_FILENAME

    def _lock_path(self) -> Path:
        return self._data_root / "state" / LOCK_FILENAME

    def _load_document(self) -> dict[str, Any]:
        try:
            raw = self._backend.load_raw()
        except EvidenceBackendError as exc:
            raise DeviceRegistryError(f"device registry cannot be read safely: {exc}") from exc
        if raw is None:
            return {"schema_version": SCHEMA_VERSION, "devices": []}
        if not isinstance(raw, dict) or raw.get("schema_version") != SCHEMA_VERSION:
            raise DeviceRegistryError("device registry has an unsupported or missing schema_version")
        devices = raw.get("devices")
        if not isinstance(devices, list):
            raise DeviceRegistryError("device registry 'devices' must be a list")
        for row in devices:
            _validate_persisted_record(row)
        return {"schema_version": SCHEMA_VERSION, "devices": devices}

    def _save_document(self, document: dict[str, Any]) -> None:
        try:
            self._backend.save_raw(document)
        except EvidenceBackendError as exc:
            raise DeviceRegistryError(f"device registry could not be written safely: {exc}") from exc

    def enroll(
        self,
        *,
        endpoint: str,
        vendor_hint: str = "unknown",
        credential_ref: str | None = None,
        tags: dict[str, str] | None = None,
    ) -> DeviceRecord:
        """Fails closed on a normalized-duplicate endpoint before a
        device_id is ever generated (AC-1a/AC-1b). Acquires the registry
        mutation lock before the load step and holds it across the whole
        load -> validate -> duplicate-check -> atomic-replace sequence
        (AC-13).
        """
        with _held_lock(self._lock_path()):
            normalized_endpoint, port = normalize_endpoint(endpoint)
            vendor_value = _validate_vendor(vendor_hint)
            credential_value = _validate_credential_ref(credential_ref)
            tags_value = _validate_tags(tags)

            document = self._load_document()
            for row in document["devices"]:
                if row["endpoint"] == normalized_endpoint and row.get("port") == port:
                    raise DeviceRegistryError(
                        "duplicate endpoint: an existing device already uses this normalized "
                        f"endpoint (device_id={row['device_id']}, state={row['state']})"
                    )

            now = _utc_now_iso()
            record = DeviceRecord(
                device_id=uuid.uuid4().hex,
                endpoint=normalized_endpoint,
                port=port,
                vendor=vendor_value,
                classification_basis="operator_hint",
                credential_ref=credential_value,
                enrollment_source="manual",
                state="ENROLLED_UNVERIFIED",
                site=None,
                tags=tags_value,
                environment=None,
                relationships=[],
                schema_version=SCHEMA_VERSION,
                created_at=now,
                updated_at=now,
            )
            document["devices"].append(record.to_dict())
            self._save_document(document)
        return record

    def list(self) -> list[DeviceRecord]:
        """Read-only; no lock is taken -- the existing atomic-replace
        guarantee already makes this safe."""
        document = self._load_document()
        return [DeviceRecord(**row) for row in document["devices"]]

    def disable(self, device_id: str) -> tuple[DeviceRecord, bool]:
        """Returns ``(record, already_disabled)``. Idempotent no-op on an
        already-``DISABLED`` id (no write, no duplicate audit entry); a
        distinct ``DeviceRegistryError`` on an unknown id -- never a silent
        no-op (AC-12).
        """
        if not isinstance(device_id, str) or not device_id.strip():
            raise DeviceRegistryError("device_id must be a non-empty string")
        target = device_id.strip()
        with _held_lock(self._lock_path()):
            document = self._load_document()
            for row in document["devices"]:
                if row["device_id"] != target:
                    continue
                if row["state"] == "DISABLED":
                    return DeviceRecord(**row), True
                if row["state"] != "ENROLLED_UNVERIFIED":
                    # Structurally unreachable in PCP.1 (AC-3) -- no code
                    # path produces any other state -- but fail closed
                    # rather than silently transitioning it anyway.
                    raise DeviceRegistryError(
                        f"device_id {target} is in state {row['state']!r}, not disableable in PCP.1"
                    )
                row["state"] = "DISABLED"
                row["updated_at"] = _utc_now_iso()
                self._save_document(document)
                return DeviceRecord(**row), False
            raise DeviceRegistryError(f"no such device: {target!r}")
