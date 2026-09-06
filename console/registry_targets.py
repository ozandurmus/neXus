"""M6 -- registry-keyed job target admission shell, `config_refresh_cp` only.

PO decision, 2026-09-06 (Option D, bounded `nexus-decision-council`
synthesis): this module is fail-closed *admission infrastructure*, not
functional per-device targeting. Console-submitted `config_refresh_cp`
target identifiers are opaque `PCP.1` Device Registry `device_id` values at
this boundary (`utils/device_registry.py`) -- distinct from the
collector-identity `entity_id` values `recovery_attest_cp`/`cp_gaia_backup`
already use (`console.registry.JOB_REGISTRY['config_refresh_cp'].target_mode
== DEVICE_ID_TARGET_MODE`, not `"entity_ids"`).

A submitted `device_id` is checked only against the registry's own
`device_id`/`state` fields. Endpoint, hostname, display name, vendor hint,
and spelling equality are never translation evidence (they answer "is this
device_id known and eligible", not "which collector entity_id does it
mean") -- and collection output (`unified.json`) is never consulted here
either, unlike the `entity_ids` target_mode's `_known_entity_ids`. No
legitimate device_id -> collector entity_id relationship producer exists
yet, so every otherwise-eligible target currently refuses with
`IDENTITY_TRANSLATION_REQUIRED`. `operator_assertion` (asserting that
relationship from any of the signals above) is an OPEN dissent, not
accepted mapping authority, absent a later explicit PO decision.

Both admission call sites share this one function so a target that was
eligible at admission but becomes disabled/retired/removed before execution
is refused the same way: `console/app.py`'s `POST /api/jobs` (admission) and
`console/runner.py`'s `_execute` (immediately before argv construction /
`main.main()`).
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from utils.device_registry import DeviceRegistry, DeviceRegistryError

#: The `target_mode` value `config_refresh_cp` carries under M6 -- distinct
#: from the collector-identity `"entity_ids"` mode `recovery_attest_cp`/
#: `cp_gaia_backup` use, and from the target-free `"none"` every other
#: `read`-class job type still carries.
DEVICE_ID_TARGET_MODE = "device_ids"

#: Reachable `PCP.1` lifecycle states only (`utils.device_registry.
#: LIFECYCLE_STATES`). `DISABLED`/`RETIRED`/`CONTACT_VERIFIED`/`OBSERVED`
#: are never eligible here, regardless of how a record reached them.
_ELIGIBLE_STATES = frozenset({"ENROLLED_UNVERIFIED"})

UNKNOWN_DEVICE_ID = "unknown_device_id"
DEVICE_NOT_ELIGIBLE = "device_not_eligible"
IDENTITY_TRANSLATION_REQUIRED = "IDENTITY_TRANSLATION_REQUIRED"
#: A corrupt/unreadable/otherwise-invalid registry (utils.device_registry.
#: DeviceRegistryError) is a distinct failure mode from "this device_id was
#: not found in an otherwise-healthy registry" -- conflating the two would
#: misreport a storage/parse fault as if every submitted device_id were
#: simply unknown. Never paired with the raw exception text, a filesystem
#: path, an endpoint, or any other internal detail (PO correction round 1).
DEVICE_REGISTRY_UNAVAILABLE = "device_registry_unavailable"


@dataclass(frozen=True)
class TargetRefusal:
    error: str  # "unresolvable" | "unsupported"
    reason: str  # one of the four module-level constants above
    detail: str  # human-readable; never an endpoint, hostname, secret, raw
                 # exception, filesystem path, or internal parser detail


def resolve_registry_targets(
    device_ids: "tuple[str, ...] | list[str]", *, data_root: Path
) -> "TargetRefusal | None":
    """Fail-closed `device_id` resolution for `config_refresh_cp` (M6).

    Returns ``None`` only when ``device_ids`` is empty -- a target-free
    `config_refresh_cp` job stays M5's existing plane-wide behavior, byte-
    identical, and never reaches either call site with a non-empty list
    otherwise. Every non-empty request currently refuses (Option D):

    0. the registry itself cannot be read/parsed at all
       (`utils.device_registry.DeviceRegistryError`) ->
       ``DEVICE_REGISTRY_UNAVAILABLE`` -- a storage/parse fault, never
       reported as if every submitted device_id were simply unknown, and
       never paired with the raw exception, a filesystem path, an endpoint,
       or any other internal detail;
    1. unknown `device_id` (not present in an otherwise-readable registry)
       -> ``UNKNOWN_DEVICE_ID``;
    2. a known `device_id` whose lifecycle state is not eligible
       (`DISABLED`/`RETIRED`/anything but `ENROLLED_UNVERIFIED`) ->
       ``DEVICE_NOT_ELIGIBLE``;
    3. otherwise (every target known and eligible) ->
       ``IDENTITY_TRANSLATION_REQUIRED``, because no proven
       `device_id` -> collector `entity_id` relationship exists to resolve
       it against.

    Cases 1/2 preserve the caller's original ``device_ids`` order and exact
    opaque spelling in the reported list -- never sorted, never normalized
    (identity law). Re-reads the registry from disk on every call -- there
    is no cache to go stale between an admission check and a pre-execution
    re-check.
    """
    if not device_ids:
        return None
    try:
        records = {record.device_id: record for record in DeviceRegistry(data_root).list()}
    except DeviceRegistryError:
        return TargetRefusal(
            "unresolvable", DEVICE_REGISTRY_UNAVAILABLE,
            "device registry is unavailable, unreadable, or invalid; targeting "
            "cannot be admitted until it is restored",
        )
    unknown = [t for t in device_ids if t not in records]
    if unknown:
        return TargetRefusal(
            "unresolvable", UNKNOWN_DEVICE_ID, f"unknown registry device_id(s): {unknown}"
        )
    ineligible = [t for t in device_ids if records[t].state not in _ELIGIBLE_STATES]
    if ineligible:
        return TargetRefusal(
            "unresolvable", DEVICE_NOT_ELIGIBLE,
            f"registry device_id(s) not eligible for targeting: {ineligible}",
        )
    return TargetRefusal(
        "unsupported", IDENTITY_TRANSLATION_REQUIRED,
        "no proven device_id-to-collector-entity_id relationship exists yet; "
        "M6 is fail-closed admission infrastructure, not functional per-device "
        "cp-config targeting",
    )
