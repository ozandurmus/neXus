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
`device_id`/`state` fields, plus (`M8.4`, below) the existing `M8.1`
relationship table and `M8.2`/`M8.3` trust/evidence seams -- never
endpoint, hostname, display name, vendor hint, or spelling equality, and
collection output (`unified.json`) is never consulted here either, unlike
the `entity_ids` target_mode's `_known_entity_ids`.

`M8.4` (frozen `M8` contract §7): once a target is known and eligible, this
module now performs one more live, read-only check -- does a currently
*proven* `device_id` -> collector `entity_id` relationship exist for it
(the `M8.1` table, populated only by the `M8.3` producer)? Only when every
`M8` currency condition holds does a target stop refusing here; nothing in
this module ever consumes that proof to build a collector command or
substitute an `entity_id` into job targets -- that wiring (`M7`, "functional
per-device `config_refresh_cp`") is a separate, still-blocked movement.
`operator_assertion` (asserting that relationship from any of the signals
above) is an OPEN dissent, not accepted mapping authority, absent a later
explicit PO decision.

Both admission call sites share this one function so a target that was
eligible (or fully identity-resolved) at admission but becomes disabled/
retired/removed/stale before execution is refused the same way:
`console/app.py`'s `POST /api/jobs` (admission) and `console/runner.py`'s
`_execute` (immediately before argv construction / `main.main()`).

No vendor/collector module import (`checkpoint`/`panorama`/`configuration`):
every `M8.4` seam this module touches --
`utils.control_plane_store`, `utils.device_identity_relationships`,
`utils.cp_ssh_trust`, `utils.config_evidence` -- lives in `utils/` and is
already vendor-neutral (`tests/test_con1_operator_console_read_only.py`'s
AC-8 structural probe pins this).
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from utils.config_evidence import ConfigEvidenceStore, resolve_evidence_reference
from utils.control_plane_store import ControlPlaneStore, ControlPlaneStoreError
from utils.cp_ssh_trust import lookup_trusted_host_key
from utils.device_identity_relationships import (
    IDENTITY_DERIVATION_CONTRACT_VERSION,
    get_active_relationship,
)
from utils.device_registry import DeviceRecord, DeviceRegistry, DeviceRegistryError

#: The `target_mode` value `config_refresh_cp` carries under M6 -- distinct
#: from the collector-identity `"entity_ids"` mode `recovery_attest_cp`/
#: `cp_gaia_backup` use, and from the target-free `"none"` every other
#: `read`-class job type still carries.
DEVICE_ID_TARGET_MODE = "device_ids"

#: Reachable `PCP.1` lifecycle states only (`utils.device_registry.
#: LIFECYCLE_STATES`). `DISABLED`/`RETIRED`/`CONTACT_VERIFIED`/`OBSERVED`
#: are never eligible here, regardless of how a record reached them.
_ELIGIBLE_STATES = frozenset({"ENROLLED_UNVERIFIED"})

#: The one `M8.1` triple this module ever resolves against -- `config_refresh_cp`
#: is Check Point only, and `M8`'s relationship model is physical-only.
_VENDOR_NAMESPACE = "checkpoint"
_MAPPING_SCOPE = "CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY"

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
#: `M8.4`: an unreadable/corrupt `M8.1` relationship store (`control_plane.db`)
#: is likewise distinct from "no relationship exists" (contract §6/§7: "never
#: misreported as 'no relationship exists'"). Covers the structurally
#: near-unreachable unexpected-multiplicity fail-closed path too (§7: "more
#: than one qualifying row ... fail closed, never a heuristic pick") -- both
#: are store-level anomalies, not an ordinary unresolved mapping.
RELATIONSHIP_STORE_UNAVAILABLE = "relationship_store_unavailable"

_IDENTITY_TRANSLATION_REQUIRED_DETAIL = (
    "no currently valid device_id -> collector entity_id relationship exists for "
    "one or more targets (none was ever proven, or a registry/identity-derivation/"
    "trust/evidence currency condition no longer holds); functional per-device "
    "cp-config targeting is a separate, still-blocked movement regardless"
)


@dataclass(frozen=True)
class TargetRefusal:
    error: str  # "unresolvable" | "unsupported"
    reason: str  # one of the module-level constants above
    detail: str  # human-readable; never an endpoint, hostname, secret, raw
                 # exception, filesystem path, fingerprint, or internal
                 # parser detail


def _identity_translation_required() -> TargetRefusal:
    return TargetRefusal(
        "unsupported", IDENTITY_TRANSLATION_REQUIRED, _IDENTITY_TRANSLATION_REQUIRED_DETAIL
    )


def _identity_resolved(record: DeviceRecord, *, store: ControlPlaneStore) -> bool:
    """`M8.4` (contract §7): does a currently *proven and current* `device_id`
    -> collector `entity_id` relationship exist for `record`?

    Every condition below must hold, live, read-only, with no device contact
    and no credential resolution:

    1. exactly one `ACTIVE` row for `(device_id, "checkpoint",
       "CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY")` -- `get_active_relationship`
       already fails closed (raises) on unexpected multiplicity, and never
       returns an `INVALIDATED`/`SUPERSEDED` row as `ACTIVE`;
    2. `identity_mapping_proven == 1` (structurally always true for a row
       `get_active_relationship` returns at all -- `M8.1`'s write API has no
       code path that writes anything else -- checked anyway, defensively,
       consumed together with `mapping_scope` since both come off the same
       returned object rather than two separate lookups);
    3. the registry record's live `updated_at` still matches the
       relationship's captured `registry_record_revision`;
    4. the relationship's `identity_derivation_contract_version` still
       matches this build's current constant;
    5. the live, local trusted-key lookup for the registry's current
       endpoint/port still succeeds (`M8.2`'s seam -- no socket, no device
       contact);
    6. the relationship's `producing_run_ref` still resolves to a successful
       CP config evidence snapshot belonging to this same physical
       `entity_id`;
    7. that evidence's own captured host-key fingerprint is still among the
       endpoint's currently trusted fingerprints.

    Raises `utils.control_plane_store.ControlPlaneStoreError` (or a
    subclass) on a store-level fault -- the caller classifies that as
    `RELATIONSHIP_STORE_UNAVAILABLE`, never as "not resolved".
    """
    relationship = get_active_relationship(
        store,
        device_id=record.device_id,
        vendor_namespace=_VENDOR_NAMESPACE,
        mapping_scope=_MAPPING_SCOPE,
    )
    if relationship is None or relationship.identity_mapping_proven != 1:
        return False
    if relationship.registry_record_revision != record.updated_at:
        return False
    if relationship.identity_derivation_contract_version != IDENTITY_DERIVATION_CONTRACT_VERSION:
        return False

    trust = lookup_trusted_host_key(record.endpoint, record.port)
    if not trust.trusted:
        return False

    evidence = resolve_evidence_reference(ConfigEvidenceStore(), relationship.producing_run_ref)
    if evidence is None:
        return False
    if str(evidence.get("entity_id") or "") != relationship.entity_id:
        return False

    # Trust-currency cross-check (contract §7): the fingerprint the producing
    # run actually observed must still be among the endpoint's currently
    # trusted fingerprints. NOTE (M8.4 finding, not fixed here per this
    # movement's own scope boundary -- "do not modify the M8.3 producer
    # unless a material defect makes M8.4 impossible", and this evidence
    # write path is the shared, unmodified _collect_host, not the producer
    # itself): the CP config evidence snapshot metadata `_collect_host`
    # writes today carries no "host_key_fingerprint" field, so this
    # condition cannot currently be affirmatively satisfied by any real
    # M8.3-produced relationship -- it fails closed (returns False) exactly
    # as intended by the fail-closed law, rather than treating an absent
    # fingerprint as a vacuous match. See this build's SESSION CLOSE.
    evidence_fingerprint = evidence.get("host_key_fingerprint")
    if not evidence_fingerprint or evidence_fingerprint not in trust.fingerprints.values():
        return False

    return True


def _resolve_identity_translation(
    device_ids: "tuple[str, ...] | list[str]",
    records: dict,
    *,
    data_root: Path,
) -> "TargetRefusal | None":
    """`M8.4`: fail-closed, whole-request identity-translation resolution
    over already-known-and-eligible `device_ids`. Returns `None` only when
    *every* target currently resolves (§7); a single unresolved target
    refuses the entire request, same all-or-nothing philosophy `M6` already
    uses for unknown/ineligible targets.
    """
    try:
        with ControlPlaneStore(data_root) as store:
            for device_id in device_ids:
                if not _identity_resolved(records[device_id], store=store):
                    return _identity_translation_required()
    except ControlPlaneStoreError:
        return TargetRefusal(
            "unresolvable", RELATIONSHIP_STORE_UNAVAILABLE,
            "the device identity relationship store is unavailable, unreadable, or "
            "invalid; targeting cannot be admitted until it is restored",
        )
    return None


def resolve_registry_targets(
    device_ids: "tuple[str, ...] | list[str]", *, data_root: Path
) -> "TargetRefusal | None":
    """Fail-closed `device_id` resolution for `config_refresh_cp` (`M6`/`M8.4`).

    Returns ``None`` when ``device_ids`` is empty -- a target-free
    `config_refresh_cp` job stays M5's existing plane-wide behavior, byte-
    identical -- or when every submitted `device_id` is known, eligible, and
    (`M8.4`) currently identity-resolved. Every other request refuses:

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
    3. the `M8.1` relationship store itself cannot be read/parsed, or an
       unexpected multi-row state is found ->
       ``RELATIONSHIP_STORE_UNAVAILABLE``;
    4. otherwise, when at least one target has no currently proven and
       current `device_id` -> collector `entity_id` relationship (`M8.4`,
       contract §7) -> ``IDENTITY_TRANSLATION_REQUIRED``.

    Cases 1/2 preserve the caller's original ``device_ids`` order and exact
    opaque spelling in the reported list -- never sorted, never normalized
    (identity law). Re-reads the registry and the relationship store from
    disk on every call -- there is no cache to go stale between an admission
    check and a pre-execution re-check, for either.
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
    return _resolve_identity_translation(device_ids, records, data_root=data_root)
