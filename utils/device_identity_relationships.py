"""M8.1 — device-identity relationship storage and typed API.

The frozen contract is
`docs/history/phase/M8_FIRST_CONTACT_TRUST_AND_IDENTITY_EVIDENCE_PRODUCER_ARCHITECTURE.md`
§5 (schema), §6 (creation/consumption/invalidation) and §8 (privacy/
concurrency/acceptance criteria). This module is storage/API only: it has no
producer (`M8.2`/`M8.3`) and no consumer (`M8.4`) wired to it. It never
contacts a device, never reads a credential, and never writes to the
`PCP.1` Device Registry.

The relationship `(device_id, entity_id)` is a new, narrow, third object — a
record that a specific evidence-backed run bound them, never an identity
itself (§2). It **references** the producing run's already-governed CP
config evidence (`producing_run_ref`) and the registry's own `updated_at`
revision signal (`registry_record_revision`); it never copies an endpoint,
serial, host-key fingerprint or trust material (§5, §8) — the existing
generic forbidden-fragment column scan
(`tests/test_m4_control_plane_metadata_store.py::test_schema_owns_no_forbidden_concept`)
already proves this against the real schema.

`identity_mapping_proven = 1` means "proven for `mapping_scope`" only (§3
point 5) — every read here returns the two together, never a bare boolean.
`mapping_scope` is never general identity proof and is explicitly prohibited
as authority for `CLASS_2` operational state change, any authorization
decision, `operational_entity_id` derivation, Device Registry enrollment, or
any other security-identity purpose.

Only a producer proof may write here (§6): there is no API to insert an
"unproven" or `identity_mapping_proven = 0` row, because the contract
requires the full first-contact sequence (identity gate accepted AND a
usable serial read) before any row exists at all (§3 point 4, §8 AC 1/10) —
a caller that lacks that evidence has nothing to call this module with. A
durable row is never physically deleted (§6); `ACTIVE` transitions to
`INVALIDATED` or `SUPERSEDED` only, in the same transaction as whatever
triggered it.
"""
from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime, timezone

from utils.control_plane_store import ControlPlaneStore, ControlPlaneStoreError

#: Closed vocabularies (§5). Each is deliberately narrow; extending any of
#: them is its own migration/contract amendment, not a runtime parameter.
VENDOR_NAMESPACES: tuple[str, ...] = ("checkpoint",)
MAPPING_SCOPES: tuple[str, ...] = ("CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY",)
PROOF_TYPES: tuple[str, ...] = ("first_contact_identity_gate_and_serial",)
PROOF_SOURCES: tuple[str, ...] = ("direct_device_read",)
RELATIONSHIP_STATES: tuple[str, ...] = ("ACTIVE", "INVALIDATED", "SUPERSEDED")

#: §6's two durable, producer-written transition causes. Not a general
#: exception/error vocabulary -- `invalidation_reason` is "closed vocabulary
#: ... never a raw exception" and this module names exactly what the
#: contract names, nothing more.
INVALIDATION_REASONS: tuple[str, ...] = ("AMBIGUOUS_IDENTITY", "SUPERSEDED")

#: The two durable outcomes §6 names for a new proof against an existing
#: `ACTIVE` row, plus the ordinary case of no prior row.
OUTCOME_NEW = "NEW"
OUTCOME_SUPERSEDED = "SUPERSEDED"
OUTCOME_AMBIGUOUS_IDENTITY = "AMBIGUOUS_IDENTITY"

#: §5's `identity_derivation_contract_version` column value -- a source-code
#: constant naming the exact physical-only `_entity_id(target)` derivation
#: contract a row's `entity_id` was produced under (mirrors `capability_
#: projections.producer_version`'s role). The sole owner of this value: the
#: `M8.3` producer (`utils/first_contact_producer.py`) writes it, and the
#: `M8.4` resolver (`console/registry_targets.py`) reads it for currency --
#: both import it from here so neither can silently drift out of step with
#: the other. Bump only when `configuration.checkpoint_config_collector.
#: _entity_id`'s derivation contract itself changes, never for an unrelated
#: M8.3/M8.4 change.
IDENTITY_DERIVATION_CONTRACT_VERSION = "checkpoint_physical_entity_id.v1"


class DeviceIdentityRelationshipError(ControlPlaneStoreError):
    """Base fail-closed error for this module's typed API."""


class RelationshipStoreUnavailableError(DeviceIdentityRelationshipError):
    """The table is corrupt or unreadable.

    §6: "Corrupt store or unreadable table -> RELATIONSHIP_STORE_UNAVAILABLE,
    never misreported as 'no relationship exists.'" Distinct, on purpose,
    from a clean `None` read result.
    """


class AmbiguousActiveRelationshipStateError(DeviceIdentityRelationshipError):
    """More than one `ACTIVE` row for one triple was found on read.

    §7: "more than one qualifying row (should be structurally prevented by
    §5's index) -> fail closed, never a heuristic pick." The unique partial
    index makes this practically unreachable; this is the defensive
    fail-closed path if it is ever reached anyway.
    """


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _require_nonempty(name: str, value: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} must be a non-empty string")
    return value


def _require_closed(name: str, value: str, vocabulary: tuple[str, ...]) -> str:
    if value not in vocabulary:
        raise ValueError(f"{name}={value!r} is not in the closed vocabulary {vocabulary!r}")
    return value


@dataclass(frozen=True)
class DeviceIdentityRelationship:
    """One row of `device_identity_relationships`, exactly as stored (§5)."""

    relationship_id: str
    device_id: str
    entity_id: str
    vendor_namespace: str
    mapping_scope: str
    producing_run_ref: str
    registry_record_revision: str
    proof_type: str
    proof_source: str
    identity_derivation_contract_version: str
    identity_mapping_proven: int
    observed_at_utc: str
    state: str
    invalidation_reason: str | None
    created_at_utc: str
    updated_at_utc: str

    @classmethod
    def _from_row(cls, row) -> "DeviceIdentityRelationship":
        return cls(
            relationship_id=row["relationship_id"],
            device_id=row["device_id"],
            entity_id=row["entity_id"],
            vendor_namespace=row["vendor_namespace"],
            mapping_scope=row["mapping_scope"],
            producing_run_ref=row["producing_run_ref"],
            registry_record_revision=row["registry_record_revision"],
            proof_type=row["proof_type"],
            proof_source=row["proof_source"],
            identity_derivation_contract_version=row["identity_derivation_contract_version"],
            identity_mapping_proven=row["identity_mapping_proven"],
            observed_at_utc=row["observed_at_utc"],
            state=row["state"],
            invalidation_reason=row["invalidation_reason"],
            created_at_utc=row["created_at_utc"],
            updated_at_utc=row["updated_at_utc"],
        )


@dataclass(frozen=True)
class ProofOutcome:
    """The result of one `record_first_contact_proof` call (§6).

    `relationship` is the newly written row for `NEW`/`SUPERSEDED`, and is
    `None` for `AMBIGUOUS_IDENTITY` -- that outcome writes no `ACTIVE` row at
    all (both the prior claim and the new one land `INVALIDATED`).
    `superseded_relationship_id` names the prior row this proof replaced or
    invalidated, or `None` when there was no prior row (`NEW`).
    """

    outcome: str
    relationship: DeviceIdentityRelationship | None
    superseded_relationship_id: str | None


_SELECT_ACTIVE = (
    "SELECT * FROM device_identity_relationships "
    "WHERE device_id = ? AND vendor_namespace = ? AND mapping_scope = ? AND state = 'ACTIVE'"
)

_INSERT_ROW = (
    "INSERT INTO device_identity_relationships ("
    "relationship_id, device_id, entity_id, vendor_namespace, mapping_scope, "
    "producing_run_ref, registry_record_revision, proof_type, proof_source, "
    "identity_derivation_contract_version, identity_mapping_proven, observed_at_utc, "
    "state, invalidation_reason, created_at_utc, updated_at_utc"
    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
)

_RETIRE_ROW = (
    "UPDATE device_identity_relationships "
    "SET state = ?, invalidation_reason = ?, updated_at_utc = ? "
    "WHERE relationship_id = ?"
)


def record_first_contact_proof(
    store: ControlPlaneStore,
    *,
    device_id: str,
    entity_id: str,
    producing_run_ref: str,
    registry_record_revision: str,
    identity_derivation_contract_version: str,
    vendor_namespace: str = "checkpoint",
    mapping_scope: str = "CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY",
    proof_type: str = "first_contact_identity_gate_and_serial",
    proof_source: str = "direct_device_read",
    observed_at_utc: str | None = None,
) -> ProofOutcome:
    """Write one proven `(device_id, entity_id)` relationship row (§6).

    The caller must already hold full first-contact proof (§3 point 4): the
    identity gate accepted **and** a usable serial was read. This function
    has no parameter for a weaker or unproven claim -- `identity_mapping_proven`
    is always written `1`; there is no code path here that writes a row
    otherwise.

    Transactional, single-writer, matching §6/§8:
    - no prior `ACTIVE` row for the triple -> `NEW`, the row is inserted `ACTIVE`.
    - a prior `ACTIVE` row with the **same** `entity_id` -> `SUPERSEDED`: the
      prior row moves to `SUPERSEDED`, the new row is inserted `ACTIVE`, same
      transaction.
    - a prior `ACTIVE` row with a **different** `entity_id` ->
      `AMBIGUOUS_IDENTITY`: the prior row and this new claim both move to
      `INVALIDATED`, same transaction, no recency/heuristic tie-break, no
      `ACTIVE` row results.

    Raises `ValueError` for a value outside a closed vocabulary or an empty
    opaque identifier, and `RelationshipStoreUnavailableError` if the table
    cannot be read or written for a reason other than the invariants above.
    """
    device_id = _require_nonempty("device_id", device_id)
    entity_id = _require_nonempty("entity_id", entity_id)
    producing_run_ref = _require_nonempty("producing_run_ref", producing_run_ref)
    registry_record_revision = _require_nonempty(
        "registry_record_revision", registry_record_revision
    )
    identity_derivation_contract_version = _require_nonempty(
        "identity_derivation_contract_version", identity_derivation_contract_version
    )
    vendor_namespace = _require_closed("vendor_namespace", vendor_namespace, VENDOR_NAMESPACES)
    mapping_scope = _require_closed("mapping_scope", mapping_scope, MAPPING_SCOPES)
    proof_type = _require_closed("proof_type", proof_type, PROOF_TYPES)
    proof_source = _require_closed("proof_source", proof_source, PROOF_SOURCES)

    now = _utc_now()
    observed_at_utc = observed_at_utc or now

    try:
        with store.transaction() as cursor:
            prior_rows = cursor.execute(
                _SELECT_ACTIVE, (device_id, vendor_namespace, mapping_scope)
            ).fetchall()
            _fail_closed_on_unexpected_multiplicity(prior_rows, device_id, vendor_namespace, mapping_scope)
            prior = prior_rows[0] if prior_rows else None

            if prior is not None and prior["entity_id"] != entity_id:
                # AMBIGUOUS_IDENTITY: both the old and the rejected new claim
                # are INVALIDATED, in this same transaction. No ACTIVE row
                # results.
                cursor.execute(
                    _RETIRE_ROW,
                    ("INVALIDATED", "AMBIGUOUS_IDENTITY", now, prior["relationship_id"]),
                )
                new_relationship_id = uuid.uuid4().hex
                cursor.execute(
                    _INSERT_ROW,
                    (
                        new_relationship_id,
                        device_id,
                        entity_id,
                        vendor_namespace,
                        mapping_scope,
                        producing_run_ref,
                        registry_record_revision,
                        proof_type,
                        proof_source,
                        identity_derivation_contract_version,
                        1,
                        observed_at_utc,
                        "INVALIDATED",
                        "AMBIGUOUS_IDENTITY",
                        now,
                        now,
                    ),
                )
                return ProofOutcome(
                    outcome=OUTCOME_AMBIGUOUS_IDENTITY,
                    relationship=None,
                    superseded_relationship_id=str(prior["relationship_id"]),
                )

            outcome = OUTCOME_NEW
            superseded_relationship_id: str | None = None
            if prior is not None:
                # SUPERSEDED: same entity_id, fresh proof (e.g. after a
                # currency loss). The prior row is superseded, the new row
                # inserted ACTIVE, same transaction.
                cursor.execute(
                    _RETIRE_ROW,
                    ("SUPERSEDED", "SUPERSEDED", now, prior["relationship_id"]),
                )
                outcome = OUTCOME_SUPERSEDED
                superseded_relationship_id = str(prior["relationship_id"])

            new_relationship_id = uuid.uuid4().hex
            cursor.execute(
                _INSERT_ROW,
                (
                    new_relationship_id,
                    device_id,
                    entity_id,
                    vendor_namespace,
                    mapping_scope,
                    producing_run_ref,
                    registry_record_revision,
                    proof_type,
                    proof_source,
                    identity_derivation_contract_version,
                    1,
                    observed_at_utc,
                    "ACTIVE",
                    None,
                    now,
                    now,
                ),
            )
            new_row = cursor.execute(
                "SELECT * FROM device_identity_relationships WHERE relationship_id = ?",
                (new_relationship_id,),
            ).fetchone()
            return ProofOutcome(
                outcome=outcome,
                relationship=DeviceIdentityRelationship._from_row(new_row),
                superseded_relationship_id=superseded_relationship_id,
            )
    except ControlPlaneStoreError:
        raise
    except Exception as exc:  # pragma: no cover - defensive, mirrors store._classify posture
        raise RelationshipStoreUnavailableError(
            f"device identity relationship store unavailable: {exc}"
        ) from exc


def get_active_relationship(
    store: ControlPlaneStore,
    *,
    device_id: str,
    vendor_namespace: str = "checkpoint",
    mapping_scope: str = "CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY",
) -> DeviceIdentityRelationship | None:
    """The one `ACTIVE` row for a `(device_id, vendor_namespace, mapping_scope)`
    triple, or `None` if there is none (§7).

    Never returns an `INVALIDATED`/`SUPERSEDED` row. `identity_mapping_proven`
    and `mapping_scope` are always returned together, on the same object --
    there is no accessor that exposes one without the other (§3 point 5).
    """
    device_id = _require_nonempty("device_id", device_id)
    vendor_namespace = _require_closed("vendor_namespace", vendor_namespace, VENDOR_NAMESPACES)
    mapping_scope = _require_closed("mapping_scope", mapping_scope, MAPPING_SCOPES)

    try:
        rows = store.connection.execute(
            _SELECT_ACTIVE, (device_id, vendor_namespace, mapping_scope)
        ).fetchall()
    except ControlPlaneStoreError:
        raise
    except Exception as exc:  # pragma: no cover - defensive, mirrors store._classify posture
        raise RelationshipStoreUnavailableError(
            f"device identity relationship store unavailable: {exc}"
        ) from exc

    _fail_closed_on_unexpected_multiplicity(rows, device_id, vendor_namespace, mapping_scope)
    if not rows:
        return None
    return DeviceIdentityRelationship._from_row(rows[0])


def _fail_closed_on_unexpected_multiplicity(rows, device_id: str, vendor_namespace: str, mapping_scope: str) -> None:
    if len(rows) > 1:
        raise AmbiguousActiveRelationshipStateError(
            "more than one ACTIVE device identity relationship exists for "
            f"(device_id={device_id!r}, vendor_namespace={vendor_namespace!r}, "
            f"mapping_scope={mapping_scope!r}); this should be structurally prevented "
            "by the unique partial index -- refusing rather than picking one"
        )
