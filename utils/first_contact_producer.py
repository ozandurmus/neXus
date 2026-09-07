"""`M8.3` -- read-only first-contact identity-evidence producer.

Frozen contract:
`docs/history/phase/M8_FIRST_CONTACT_TRUST_AND_IDENTITY_EVIDENCE_PRODUCER_ARCHITECTURE.md`
Section 3 (evidence model), Section 4 (mandatory trust-before-credential
sequence), Sections 6/8 (positive write gate, acceptance criteria). This
module is the new, minimal, single-target driver Section 4 requires -- it is
deliberately not `run_checkpoint_config_collection`, which resolves
credentials once per run before any per-target check (Section 4's opening
paragraph).

Sequence, exactly as Section 4 orders it:

1. Device Registry record resolution for one `device_id`, fail-closed,
   read-only. Never mutates the registry or its frozen `relationships: []`
   field (`utils.device_registry`).
2. Physical candidate selection through the existing collector discovery
   seam (`configuration.checkpoint_config_collector._resolve_targets`),
   matched only on `PhysicalTarget.management_ip` -- a hypothesis, never
   proof (Section 3). Zero or multiple candidates refuse; only exactly one
   proceeds.
3. `utils.cp_ssh_trust.lookup_trusted_host_key` -- mandatory, blocking, and
   the last step before `resolve_credentials` may ever be called. No
   credential is resolved for an untrusted endpoint.
4. Credential resolution through the caller-supplied `resolve_credentials`
   callback: the existing DEV.2.1/DEV.2.2 runtime source, invoked here and
   nowhere earlier. This module introduces no other credential path, store,
   or registry secret interpretation.
5. The existing, unmodified `_collect_host` primitive (`_identity_gate`,
   `_collector_identity_gate`, `_parse_asset_semantic(..., "serial")`,
   `_entity_id`, the existing `RejectPolicy` SSH handshake all reused
   unmodified through it). Strict host-key mode is always forced `True`
   here -- first contact never uses the bulk collector's compatibility-mode
   default.
6. The positive write gate (Section 3 point 4, Section 8 AC1/AC10): the
   physical host identity gate `accepted is True`, a non-empty directly-read
   serial, and a concrete, resolvable `producing_run_ref` into this run's
   own already-governed CP config evidence -- all three, or no relationship
   row is ever written.

Never writes to the Device Registry. Never stores an endpoint, serial,
host-key fingerprint, credential, secret, or raw command output in the
`M8.1` relationship table -- only the already-approved opaque/closed-
vocabulary fields `record_first_contact_proof` accepts.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from configuration import checkpoint_config_collector as cp_collector
from utils.config_evidence import ConfigEvidenceStore, build_evidence_reference
from utils.control_plane_store import ControlPlaneStore, ControlPlaneStoreError
from utils.cp_ssh_trust import lookup_trusted_host_key
from utils.device_identity_relationships import (
    IDENTITY_DERIVATION_CONTRACT_VERSION as IDENTITY_DERIVATION_CONTRACT_VERSION,
    OUTCOME_AMBIGUOUS_IDENTITY as OUTCOME_AMBIGUOUS_IDENTITY,
    OUTCOME_NEW as OUTCOME_NEW,
    OUTCOME_SUPERSEDED as OUTCOME_SUPERSEDED,
    record_first_contact_proof,
)
from utils.device_registry import DeviceRegistry, DeviceRegistryError

# IDENTITY_DERIVATION_CONTRACT_VERSION now lives in
# utils/device_identity_relationships.py (M8.4): the M8.4 console resolver
# reads the same constant for currency, so both sides import one value
# rather than each holding a copy that could silently drift apart.

#: Reachable PCP.1 lifecycle states eligible for first contact. Mirrors
#: `console/registry_targets.py`'s own `_ELIGIBLE_STATES` (M6) in spirit;
#: kept as an independent constant here because `utils/` must not import
#: `console/`.
_ELIGIBLE_REGISTRY_STATES = frozenset({"ENROLLED_UNVERIFIED"})

# Sanitized, value-free producer refusal tokens (contract Section 3 point 4,
# Section 9 AC10). Never paired with a raw exception, endpoint, serial,
# host-key, or credential value.
UNKNOWN_DEVICE_ID = "unknown_device_id"
DEVICE_NOT_ELIGIBLE = "device_not_eligible"
DEVICE_REGISTRY_UNAVAILABLE = "device_registry_unavailable"
DISCOVERY_EVIDENCE_UNAVAILABLE = "discovery_evidence_unavailable"
NO_PHYSICAL_CANDIDATE = "no_physical_candidate"
AMBIGUOUS_PHYSICAL_CANDIDATE = "ambiguous_physical_candidate"
ENDPOINT_NOT_TRUSTED = "endpoint_not_trusted"
IDENTITY_GATE_REJECTED = "identity_gate_rejected"
IDENTITY_GATE_ACCEPTED_SERIAL_UNAVAILABLE = "identity_gate_accepted_serial_unavailable"
PRODUCING_EVIDENCE_UNRESOLVABLE = "producing_evidence_unresolvable"
RELATIONSHIP_STORE_UNAVAILABLE = "relationship_store_unavailable"
COLLECTOR_ERROR = "collector_error"


@dataclass(frozen=True)
class ProducerOutcome:
    """Sanitized, value-free result of one `run_first_contact_producer` call.

    `status` is one of this module's own refusal tokens above, or one of
    contract Section 6's three durable write outcomes (`utils.
    device_identity_relationships.OUTCOME_NEW` / `OUTCOME_SUPERSEDED` /
    `OUTCOME_AMBIGUOUS_IDENTITY`). `reason` is a value-free detail token
    (never an endpoint, serial, host-key fingerprint, credential, or raw
    exception) -- populated only for the trust-lookup refusal, where
    `utils.cp_ssh_trust.TrustedKeyLookupResult.reason` already carries one.
    `relationship_id` is set only for `NEW`/`SUPERSEDED` -- never for
    `AMBIGUOUS_IDENTITY`, which writes no `ACTIVE` row.
    """

    status: str
    device_id: str
    reason: str | None = None
    relationship_id: str | None = None


def _resolve_producing_run_ref(evidence_store: ConfigEvidenceStore, entity_id: str) -> str | None:
    """A concrete, resolvable opaque reference to this run's own governed CP
    config evidence (contract Section 5's `producing_run_ref`) -- never the
    raw configuration, never a new evidence type.

    `evidence_store.backend.list_snapshots` is the same public read seam
    `utils/config_history.py` already uses to walk this store; entries come
    back newest-first (its own contract), so the first `success` match for
    this entity/artifact type is the snapshot `_collect_host` (via
    `ConfigEvidenceStore.write_text_snapshot`) just wrote in this same call.
    """
    try:
        snapshots = evidence_store.backend.list_snapshots(
            source=cp_collector.SOURCE, entity_id=entity_id
        )
    except Exception:
        return None
    for snapshot_id, payload in snapshots:
        if not payload:
            continue
        if payload.get("status") != "success" or not payload.get("sha256"):
            continue
        if payload.get("artifact_type") != cp_collector.PHYSICAL_ARTIFACT_TYPE:
            continue
        return build_evidence_reference(
            source=cp_collector.SOURCE, entity_id=entity_id, snapshot_id=snapshot_id
        )
    return None


def run_first_contact_producer(
    *,
    device_id: str,
    data_root: Path,
    output_root: Path,
    resolve_credentials: Callable[[], "tuple[str, str]"],
    connect_timeout: int = 8,
    command_timeout: int = 20,
) -> ProducerOutcome:
    """Execute the full contract Section 4 sequence for one registry `device_id`.

    `resolve_credentials` is called at most once, and only after the
    target-specific trust lookup (Section 4 step 1) has already succeeded --
    never before (Section 8 AC2). It must return `(username, secret)` from
    the existing DEV.2.1/DEV.2.2 runtime source; this function introduces no
    other credential path, store, or registry secret interpretation
    (Section 4 step 2).
    """
    device_id = str(device_id or "").strip()
    if not device_id:
        raise ValueError("device_id must be a non-empty string")

    try:
        records = {record.device_id: record for record in DeviceRegistry(data_root).list()}
    except DeviceRegistryError:
        return ProducerOutcome(status=DEVICE_REGISTRY_UNAVAILABLE, device_id=device_id)

    record = records.get(device_id)
    if record is None:
        return ProducerOutcome(status=UNKNOWN_DEVICE_ID, device_id=device_id)
    if record.state not in _ELIGIBLE_REGISTRY_STATES:
        return ProducerOutcome(status=DEVICE_NOT_ELIGIBLE, device_id=device_id)

    # Step 2 -- candidate selection through the existing discovery seam.
    # Read-only: reuses the same OUTPUT_DIR-rebinding convention
    # `run_checkpoint_config_collection` itself uses to honor a non-default
    # RuntimeRoot, before any per-target check.
    cp_collector.OUTPUT_DIR = Path(output_root)
    try:
        targets, _skipped = cp_collector._resolve_targets()
    except RuntimeError:
        return ProducerOutcome(status=DISCOVERY_EVIDENCE_UNAVAILABLE, device_id=device_id)

    candidates = [target for target in targets if target.management_ip == record.endpoint]
    if not candidates:
        return ProducerOutcome(status=NO_PHYSICAL_CANDIDATE, device_id=device_id)
    if len(candidates) > 1:
        return ProducerOutcome(status=AMBIGUOUS_PHYSICAL_CANDIDATE, device_id=device_id)
    target = candidates[0]

    # Step 1 of the mandatory sequence (contract Section 4): trust before
    # credential, unconditionally. No credential is resolved, no socket is
    # opened, and no device is contacted above this line.
    trust = lookup_trusted_host_key(record.endpoint, record.port)
    if not trust.trusted:
        return ProducerOutcome(status=ENDPOINT_NOT_TRUSTED, device_id=device_id, reason=trust.reason)

    # Step 2 of the mandatory sequence: credential resolution, only now.
    username, secret = resolve_credentials()

    evidence_store = ConfigEvidenceStore()
    try:
        rows = cp_collector._collect_host(
            target,
            username=username,
            secret=secret,
            strict_host_key=True,
            connect_timeout=connect_timeout,
            command_timeout=command_timeout,
            store=evidence_store,
        )
    except Exception:
        return ProducerOutcome(status=COLLECTOR_ERROR, device_id=device_id)
    finally:
        username = secret = None

    host_row = rows[0] if rows else {}
    identity = host_row.get("identity_gate") or {}
    if identity.get("accepted") is not True:
        return ProducerOutcome(status=IDENTITY_GATE_REJECTED, device_id=device_id)

    serial = str(host_row.get("serial") or "").strip()
    if not serial:
        return ProducerOutcome(
            status=IDENTITY_GATE_ACCEPTED_SERIAL_UNAVAILABLE, device_id=device_id
        )
    serial = None  # discarded immediately once presence is proven (raw-evidence law)

    entity_id = cp_collector._entity_id(target)
    producing_run_ref = _resolve_producing_run_ref(evidence_store, entity_id)
    if producing_run_ref is None:
        return ProducerOutcome(status=PRODUCING_EVIDENCE_UNRESOLVABLE, device_id=device_id)

    try:
        with ControlPlaneStore(data_root) as store:
            proof = record_first_contact_proof(
                store,
                device_id=device_id,
                entity_id=entity_id,
                producing_run_ref=producing_run_ref,
                registry_record_revision=record.updated_at,
                identity_derivation_contract_version=IDENTITY_DERIVATION_CONTRACT_VERSION,
            )
    except ControlPlaneStoreError:
        return ProducerOutcome(status=RELATIONSHIP_STORE_UNAVAILABLE, device_id=device_id)

    return ProducerOutcome(
        status=proof.outcome,
        device_id=device_id,
        relationship_id=proof.relationship.relationship_id if proof.relationship else None,
    )
