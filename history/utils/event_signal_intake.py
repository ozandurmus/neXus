"""Event Signal Intake -- ``event_signal_intake`` Slice 1.

Authoritative design: ``docs/design/EVENT_SIGNAL_INTAKE_ARCHITECTURE.md``
(FROZEN -- Slice 1 only). This module is the toolbox of pure, independently
testable pieces the intake boundary needs: HMAC signature verification,
replay protection, schema allowlisting, canonical identity resolution, and
dedup/cooldown. It contacts no device, resolves no credential, and never
calls a collector -- ``signal_intake/app.py`` composes these into the actual
route, then hands off to the *existing*, unmodified ``CON.2`` job engine
(``console.jobs.ConsoleJobStore`` / ``console.runner.ConsoleJobRunner``) to
enqueue a bounded, coordinator-managed collection. Nothing in this module,
or in the route that uses it, ever writes evidence.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from utils.device_registry import (
    VENDOR_VALUES,
    DeviceRecord,
    DeviceRegistry,
    DeviceRegistryError,
    normalize_endpoint,
)
from utils.evidence_backend import _write_json_atomic
from utils.logger import register_sensitive_value

SIGNAL_HMAC_SECRET_ENV = "SECURITYEXPERT_EVENT_SIGNAL_HMAC_SECRET"

#: Slice 1's closed event-type vocabulary. Both map to the same
#: config_refresh_cp trigger today (see the design doc's "Trigger" section);
#: a value outside this set is a 400, never silently ignored.
ALLOWED_EVENT_TYPES: frozenset[str] = frozenset({"policy_install", "config_change"})

#: Strict schema allowlist (AC-2) -- an unknown field is a 400.
ALLOWED_SIGNAL_FIELDS: frozenset[str] = frozenset(
    {"signal_id", "event_type", "device_reference", "vendor_hint", "observed_at"}
)

_MAX_SIGNAL_ID_LENGTH = 128
_MAX_NONCE_LENGTH = 128

#: AC-2 "nonce/timestamp-window or equivalent". 300s is a common webhook
#: clock-skew/replay tolerance (design doc "Replay protection").
REPLAY_WINDOW_SECONDS = 300

#: Dedup/cooldown floor, deliberately the same 10-minute floor
#: utils.collection_executor._MIN_INTERVAL_MINUTES already enforces for
#: scheduled polling -- one polling-frequency policy, not two.
COOLDOWN_SECONDS = 600

STATE_RELATIVE_PATH = Path("state") / "event_signal_intake_state.json"
STATE_SCHEMA_VERSION = 1

_state_lock = threading.Lock()


class SignalIntakeError(RuntimeError):
    """Fail-closed error: a corrupt/unreadable intake state store, or a
    registry-corruption condition (more than one registry record matching
    the same normalized endpoint -- structurally prevented by
    ``DeviceRegistry.enroll()``'s own duplicate refusal, but never silently
    picked from if it is ever observed anyway)."""


class SignalAuthenticationError(RuntimeError):
    """Missing/malformed/incorrect HMAC signature -- refuse with 401, before
    the request body is parsed as JSON at all."""


class SignalReplayError(RuntimeError):
    """Stale timestamp (outside ``REPLAY_WINDOW_SECONDS``) or a nonce already
    seen within the window -- refuse with 401."""


class SignalSchemaError(RuntimeError):
    """Unknown field, missing required field, wrong type, or an
    ``event_type``/``vendor_hint`` outside its closed allowlist -- refuse
    with 400."""


@dataclass(frozen=True)
class SignalOutcome:
    """The explicit outcome vocabulary
    (``project/feature_registry.json``'s ``event_signal_intake`` criterion
    ``signal_outcome``): never a bare boolean. ``status`` is one of
    ``triggered``, ``ignored_cooldown``, ``unknown_identity``,
    ``unsupported_vendor_target_seam``, or a
    ``console.registry_targets.TargetRefusal.reason`` value."""

    status: str
    detail: str
    job_id: str | None = None
    device_id: str | None = None


# ---------------------------------------------------------------------------
# Authentication
# ---------------------------------------------------------------------------

def load_signal_secret() -> bytes:
    """Required, no fallback generation (design doc "Authentication"): unlike
    the console's per-launch token, this secret must already be known to the
    external caller, so silently generating one here would only desynchronize
    from it. Fails closed with a named reason instead of a KeyError."""
    raw = os.environ.get(SIGNAL_HMAC_SECRET_ENV, "")
    if not raw:
        raise SignalAuthenticationError(
            f"{SIGNAL_HMAC_SECRET_ENV} is not configured -- event signal intake "
            "cannot authenticate any request until it is set"
        )
    secret = raw.encode("utf-8")
    register_sensitive_value(raw, "[EVENT_SIGNAL_HMAC_SECRET:REDACTED]")
    return secret


def verify_signature(
    *, secret: bytes, timestamp: str, nonce: str, raw_body: bytes, signature_header: str | None
) -> None:
    """Binds ``timestamp``/``nonce`` into the signed material, not just
    alongside it (design doc "Authentication") -- a signature that only
    covered ``raw_body`` would let a captured, previously-valid request be
    replayed under a fresh, unvalidated timestamp/nonce pair."""
    if not signature_header or not signature_header.startswith("sha256="):
        raise SignalAuthenticationError("missing or malformed X-Signal-Signature header")
    candidate_hex = signature_header[len("sha256="):]
    signed_material = f"{timestamp}.{nonce}.".encode("utf-8") + raw_body
    expected_hex = hmac.new(secret, signed_material, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(candidate_hex, expected_hex):
        raise SignalAuthenticationError("signature does not match")


# ---------------------------------------------------------------------------
# Replay protection + dedup/cooldown state (one small durable store)
# ---------------------------------------------------------------------------

def _state_path(data_root: Path) -> Path:
    return Path(data_root) / STATE_RELATIVE_PATH


def _load_state(data_root: Path) -> dict[str, Any]:
    path = _state_path(data_root)
    if not path.exists():
        return {"schema_version": STATE_SCHEMA_VERSION, "nonces": {}, "cooldowns": {}}
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise SignalIntakeError("event signal intake state cannot be read safely") from exc
    if not isinstance(raw, dict) or raw.get("schema_version") != STATE_SCHEMA_VERSION:
        raise SignalIntakeError("event signal intake state has an unsupported schema_version")
    nonces = raw.get("nonces")
    cooldowns = raw.get("cooldowns")
    if not isinstance(nonces, dict) or not isinstance(cooldowns, dict):
        raise SignalIntakeError("event signal intake state is malformed")
    return {"schema_version": STATE_SCHEMA_VERSION, "nonces": nonces, "cooldowns": cooldowns}


def _save_state(data_root: Path, state: dict[str, Any]) -> None:
    path = _state_path(data_root)
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        _write_json_atomic(path, state)
    except OSError as exc:
        raise SignalIntakeError("event signal intake state could not be written safely") from exc


def _prune_nonces(nonces: dict[str, Any], *, now_epoch: float) -> dict[str, Any]:
    return {n: exp for n, exp in nonces.items() if isinstance(exp, (int, float)) and exp > now_epoch}


def check_and_record_replay(
    *, data_root: Path, timestamp: str, nonce: str, now: datetime | None = None
) -> None:
    """Raises :class:`SignalReplayError` on a stale timestamp or a nonce
    already recorded within the window; otherwise records the nonce.
    Fail-closed on a malformed timestamp or an over-long nonce -- never
    silently truncated or coerced."""
    if not nonce or len(nonce) > _MAX_NONCE_LENGTH:
        raise SignalReplayError("nonce must be a non-empty string of at most 128 characters")
    try:
        timestamp_epoch = int(timestamp)
    except (TypeError, ValueError) as exc:
        raise SignalReplayError("X-Signal-Timestamp must be an integer unix timestamp") from exc

    now_dt = now if now is not None else datetime.now(timezone.utc)
    now_epoch = now_dt.timestamp()
    if abs(now_epoch - timestamp_epoch) > REPLAY_WINDOW_SECONDS:
        raise SignalReplayError(
            f"timestamp outside the {REPLAY_WINDOW_SECONDS}s replay window"
        )

    with _state_lock:
        state = _load_state(data_root)
        nonces = _prune_nonces(state["nonces"], now_epoch=now_epoch)
        if nonce in nonces:
            raise SignalReplayError("nonce already used within the replay window")
        nonces[nonce] = now_epoch + REPLAY_WINDOW_SECONDS
        state["nonces"] = nonces
        _save_state(data_root, state)


def check_and_record_cooldown(
    *,
    data_root: Path,
    device_id: str,
    event_type: str,
    now: datetime | None = None,
    cooldown_seconds: int = COOLDOWN_SECONDS,
) -> bool:
    """Returns ``True`` (and records this trigger) when ``(device_id,
    event_type)`` is not currently cooling down; ``False`` when it is (the
    caller reports ``ignored_cooldown`` and enqueues nothing). Distinct from
    replay protection -- this defends against legitimate, distinct,
    validly-signed signals arriving faster than the evidence-collection
    plane should be re-triggered, not against a captured request being
    resent."""
    now_dt = now if now is not None else datetime.now(timezone.utc)
    now_epoch = now_dt.timestamp()
    key = f"{device_id}|{event_type}"

    with _state_lock:
        state = _load_state(data_root)
        cooldowns = state["cooldowns"]
        next_eligible = cooldowns.get(key)
        if isinstance(next_eligible, (int, float)) and now_epoch < next_eligible:
            return False
        cooldowns[key] = now_epoch + cooldown_seconds
        state["cooldowns"] = cooldowns
        _save_state(data_root, state)
        return True


# ---------------------------------------------------------------------------
# Schema allowlist
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class ValidatedSignal:
    signal_id: str
    event_type: str
    device_reference: str
    vendor_hint: str | None
    observed_at: str | None


def validate_schema(body: Any) -> ValidatedSignal:
    if not isinstance(body, dict):
        raise SignalSchemaError("request body must be a JSON object")

    unknown = set(body) - ALLOWED_SIGNAL_FIELDS
    if unknown:
        raise SignalSchemaError(f"unknown field(s): {sorted(unknown)}")

    signal_id = body.get("signal_id")
    if not isinstance(signal_id, str) or not signal_id.strip() or len(signal_id) > _MAX_SIGNAL_ID_LENGTH:
        raise SignalSchemaError("signal_id must be a non-empty string of at most 128 characters")

    event_type = body.get("event_type")
    if event_type not in ALLOWED_EVENT_TYPES:
        raise SignalSchemaError(
            f"event_type must be one of {sorted(ALLOWED_EVENT_TYPES)}, got {event_type!r}"
        )

    device_reference = body.get("device_reference")
    if not isinstance(device_reference, str) or not device_reference.strip():
        raise SignalSchemaError("device_reference must be a non-empty string")

    vendor_hint = body.get("vendor_hint")
    if vendor_hint is not None:
        if not isinstance(vendor_hint, str) or vendor_hint not in VENDOR_VALUES:
            raise SignalSchemaError(f"vendor_hint must be one of {sorted(VENDOR_VALUES)} or omitted")

    observed_at = body.get("observed_at")
    if observed_at is not None and not isinstance(observed_at, str):
        raise SignalSchemaError("observed_at, if present, must be a string")

    return ValidatedSignal(
        signal_id=signal_id.strip(),
        event_type=event_type,
        device_reference=device_reference.strip(),
        vendor_hint=vendor_hint,
        observed_at=observed_at,
    )


# ---------------------------------------------------------------------------
# Canonical identity resolution (AC-3) -- reuses DeviceRegistry, invents nothing
# ---------------------------------------------------------------------------

def resolve_device_record(device_reference: str, *, data_root: Path) -> DeviceRecord | None:
    """Normalizes ``device_reference`` the same way ``DeviceRegistry.enroll()``
    already normalizes and compares every endpoint (Identity law: no new
    equivalence rule), then matches it against the registry's own records.
    ``None`` means genuinely unknown -- not a refusal, a fact. More than one
    match is registry corruption (enroll() itself refuses a duplicate
    endpoint), never a guessed pick. Returns the full record (not just
    ``device_id``) so a caller can also inspect ``vendor`` -- Slice 1's
    Check-Point-only target-seam check needs it (design doc "Gate
    verification", finding 4).
    """
    try:
        endpoint, port = normalize_endpoint(device_reference)
    except DeviceRegistryError:
        return None
    try:
        records = DeviceRegistry(data_root).list()
    except DeviceRegistryError as exc:
        raise SignalIntakeError("device registry is unavailable, unreadable, or invalid") from exc

    matches = [r for r in records if r.endpoint == endpoint and r.port == port]
    if not matches:
        return None
    if len(matches) > 1:
        raise SignalIntakeError(
            "device registry contains more than one record for the same normalized "
            "endpoint -- refusing to guess"
        )
    return matches[0]
