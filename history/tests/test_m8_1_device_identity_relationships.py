"""M8.1 — relationship storage and typed API.

Proves the frozen contract:
`docs/history/phase/M8_FIRST_CONTACT_TRUST_AND_IDENTITY_EVIDENCE_PRODUCER_ARCHITECTURE.md`
§5 (schema), §6 (creation/consumption/invalidation) and §8 (acceptance
criteria 1, 3, 5, 6, 7, 8, 9, 10 -- the ones this slice can prove without a
producer or a consumer, both deliberately absent here).

Storage/API only: no producer (`M8.2`/`M8.3`), no consumer (`M8.4`), no
device contact, no Device Registry write.
"""
from __future__ import annotations

import inspect
from pathlib import Path

import pytest

from utils.control_plane_store import ControlPlaneStore, ControlPlaneStoreError
from utils.device_identity_relationships import (
    INVALIDATION_REASONS,
    MAPPING_SCOPES,
    OUTCOME_AMBIGUOUS_IDENTITY,
    OUTCOME_NEW,
    OUTCOME_SUPERSEDED,
    PROOF_SOURCES,
    PROOF_TYPES,
    RELATIONSHIP_STATES,
    VENDOR_NAMESPACES,
    AmbiguousActiveRelationshipStateError,
    DeviceIdentityRelationship,
    RelationshipStoreUnavailableError,
    get_active_relationship,
    record_first_contact_proof,
)

pytestmark = pytest.mark.runtime_platform


@pytest.fixture
def data_root(tmp_path):
    return tmp_path / "runtime" / "data"


@pytest.fixture
def store(data_root):
    with ControlPlaneStore(data_root) as opened:
        yield opened


def _proof(**overrides):
    fields = dict(
        device_id="dev-1",
        entity_id="entity-1",
        producing_run_ref="run-1",
        registry_record_revision="2026-09-06T00:00:00+00:00",
        identity_derivation_contract_version="v1",
    )
    fields.update(overrides)
    return fields


# ---------------------------------------------------------------------------
# §5 — schema shape, closed vocabularies, uniqueness
# ---------------------------------------------------------------------------

def test_migration_2_lands_the_relationship_table(store):
    assert store.schema_version() == 2
    columns = {
        row["name"]
        for row in store.connection.execute(
            "PRAGMA table_info(device_identity_relationships)"
        ).fetchall()
    }
    expected = {
        "relationship_id",
        "device_id",
        "entity_id",
        "vendor_namespace",
        "mapping_scope",
        "producing_run_ref",
        "registry_record_revision",
        "proof_type",
        "proof_source",
        "identity_derivation_contract_version",
        "identity_mapping_proven",
        "observed_at_utc",
        "state",
        "invalidation_reason",
        "created_at_utc",
        "updated_at_utc",
    }
    assert columns == expected


def test_relationship_table_is_strict(store):
    sql = store.connection.execute(
        "SELECT sql FROM sqlite_schema WHERE type='table' AND name='device_identity_relationships'"
    ).fetchone()[0]
    assert "STRICT" in sql.upper()


def test_vendor_namespace_is_closed(store):
    with pytest.raises(ControlPlaneStoreError):
        store.execute(
            "INSERT INTO device_identity_relationships ("
            "relationship_id, device_id, entity_id, vendor_namespace, mapping_scope, "
            "producing_run_ref, registry_record_revision, proof_type, proof_source, "
            "identity_derivation_contract_version, identity_mapping_proven, observed_at_utc, "
            "state, invalidation_reason, created_at_utc, updated_at_utc"
            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                "r1", "d1", "e1", "panorama", MAPPING_SCOPES[0], "run", "rev",
                PROOF_TYPES[0], PROOF_SOURCES[0], "v1", 1, "2026-09-06T00:00:00+00:00",
                "ACTIVE", None, "2026-09-06T00:00:00+00:00", "2026-09-06T00:00:00+00:00",
            ),
        )


def test_at_most_one_active_row_per_triple_is_an_engine_invariant(store):
    """§5's unique partial index -- structural, not a read-then-write check."""
    record_first_contact_proof(store, **_proof())
    with pytest.raises(ControlPlaneStoreError):
        store.execute(
            "INSERT INTO device_identity_relationships ("
            "relationship_id, device_id, entity_id, vendor_namespace, mapping_scope, "
            "producing_run_ref, registry_record_revision, proof_type, proof_source, "
            "identity_derivation_contract_version, identity_mapping_proven, observed_at_utc, "
            "state, invalidation_reason, created_at_utc, updated_at_utc"
            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                "different-relationship-id", "dev-1", "entity-2", "checkpoint",
                MAPPING_SCOPES[0], "run-2", "rev", PROOF_TYPES[0], PROOF_SOURCES[0],
                "v1", 1, "2026-09-06T00:00:00+00:00", "ACTIVE", None,
                "2026-09-06T00:00:00+00:00", "2026-09-06T00:00:00+00:00",
            ),
        )


def test_a_second_active_row_for_a_different_triple_is_unaffected(store):
    """The uniqueness is per-triple, not global."""
    record_first_contact_proof(store, **_proof(device_id="dev-1"))
    record_first_contact_proof(store, **_proof(device_id="dev-2"))
    assert get_active_relationship(store, device_id="dev-1") is not None
    assert get_active_relationship(store, device_id="dev-2") is not None


# ---------------------------------------------------------------------------
# §6 / AC1 -- a row is only ever written by a full, proven claim
# ---------------------------------------------------------------------------

def test_record_first_contact_proof_has_no_unproven_write_path():
    """AC1/AC10: there is no parameter to write an unproven or
    identity_mapping_proven=0 row -- the caller either has full proof or has
    nothing to call this with."""
    parameters = inspect.signature(record_first_contact_proof).parameters
    assert "identity_mapping_proven" not in parameters


def test_a_new_proof_writes_one_active_row(store):
    outcome = record_first_contact_proof(store, **_proof())
    assert outcome.outcome == OUTCOME_NEW
    assert outcome.superseded_relationship_id is None
    row = outcome.relationship
    assert isinstance(row, DeviceIdentityRelationship)
    assert row.device_id == "dev-1"
    assert row.entity_id == "entity-1"
    assert row.vendor_namespace == "checkpoint"
    assert row.mapping_scope == "CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY"
    assert row.proof_type == "first_contact_identity_gate_and_serial"
    assert row.proof_source == "direct_device_read"
    assert row.identity_mapping_proven == 1
    assert row.state == "ACTIVE"
    assert row.invalidation_reason is None


def test_identity_mapping_proven_and_mapping_scope_are_always_returned_together(store):
    """§3 point 5: never a bare boolean."""
    record_first_contact_proof(store, **_proof())
    fetched = get_active_relationship(store, device_id="dev-1")
    assert fetched.identity_mapping_proven == 1
    assert fetched.mapping_scope == "CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY"


# ---------------------------------------------------------------------------
# §6 -- SUPERSEDED: same entity_id, fresh proof
# ---------------------------------------------------------------------------

def test_a_reproof_with_the_same_entity_id_supersedes_the_prior_row(store):
    first = record_first_contact_proof(store, **_proof())
    second = record_first_contact_proof(
        store, **_proof(producing_run_ref="run-2", registry_record_revision="rev-2")
    )

    assert second.outcome == OUTCOME_SUPERSEDED
    assert second.superseded_relationship_id == first.relationship.relationship_id
    assert second.relationship.state == "ACTIVE"
    assert second.relationship.entity_id == "entity-1"

    prior_row = store.connection.execute(
        "SELECT state, invalidation_reason FROM device_identity_relationships "
        "WHERE relationship_id = ?",
        (first.relationship.relationship_id,),
    ).fetchone()
    assert prior_row["state"] == "SUPERSEDED"
    assert prior_row["invalidation_reason"] == "SUPERSEDED"

    # Never a physical delete.
    assert store.connection.execute(
        "SELECT count(*) FROM device_identity_relationships"
    ).fetchone()[0] == 2

    # Exactly one ACTIVE row survives for the triple.
    assert get_active_relationship(store, device_id="dev-1").relationship_id == (
        second.relationship.relationship_id
    )


# ---------------------------------------------------------------------------
# §6 -- AMBIGUOUS_IDENTITY: different entity_id, no tie-break
# ---------------------------------------------------------------------------

def test_a_reproof_with_a_different_entity_id_is_ambiguous_and_writes_no_active_row(store):
    first = record_first_contact_proof(store, **_proof())
    second = record_first_contact_proof(store, **_proof(entity_id="entity-2"))

    assert second.outcome == OUTCOME_AMBIGUOUS_IDENTITY
    assert second.relationship is None
    assert second.superseded_relationship_id == first.relationship.relationship_id

    rows = store.connection.execute(
        "SELECT relationship_id, entity_id, state, invalidation_reason "
        "FROM device_identity_relationships ORDER BY created_at_utc"
    ).fetchall()
    assert len(rows) == 2
    for row in rows:
        assert row["state"] == "INVALIDATED"
        assert row["invalidation_reason"] == "AMBIGUOUS_IDENTITY"
    assert {row["entity_id"] for row in rows} == {"entity-1", "entity-2"}

    # No ACTIVE row remains for the triple -- resolution must fail closed,
    # never pick one of the two contradicting claims.
    assert get_active_relationship(store, device_id="dev-1") is None


def test_ambiguous_identity_has_no_recency_or_heuristic_tie_break(store):
    """A third proof after an AMBIGUOUS_IDENTITY pair starts fresh: since no
    row is ACTIVE, the next proof is simply NEW, not compared against either
    invalidated claim."""
    record_first_contact_proof(store, **_proof())
    record_first_contact_proof(store, **_proof(entity_id="entity-2"))

    third = record_first_contact_proof(store, **_proof(entity_id="entity-3"))
    assert third.outcome == OUTCOME_NEW
    assert third.relationship.state == "ACTIVE"
    assert get_active_relationship(store, device_id="dev-1").entity_id == "entity-3"


# ---------------------------------------------------------------------------
# §7 -- read side: absence, and fail-closed on unexpected multiplicity
# ---------------------------------------------------------------------------

def test_no_relationship_returns_none(store):
    assert get_active_relationship(store, device_id="no-such-device") is None


def test_an_invalidated_row_is_never_returned_as_active(store):
    record_first_contact_proof(store, **_proof())
    record_first_contact_proof(store, **_proof(entity_id="entity-2"))  # -> AMBIGUOUS_IDENTITY
    assert get_active_relationship(store, device_id="dev-1") is None


def test_a_superseded_row_is_never_returned_as_active(store):
    record_first_contact_proof(store, **_proof())
    record_first_contact_proof(store, **_proof(producing_run_ref="run-2"))  # -> SUPERSEDED
    active = get_active_relationship(store, device_id="dev-1")
    assert active is not None
    assert active.producing_run_ref == "run-2"


def test_unexpected_multiple_active_rows_fail_closed_on_read(store):
    """§7: 'should be structurally prevented by §5's index' -- this proves
    the defensive path exists even though the index makes it unreachable via
    the typed write API."""
    from utils.device_identity_relationships import _fail_closed_on_unexpected_multiplicity

    class _FakeRow(dict):
        def __getitem__(self, key):
            return dict.__getitem__(self, key)

    fake_rows = [
        _FakeRow(relationship_id="a", entity_id="e1"),
        _FakeRow(relationship_id="b", entity_id="e2"),
    ]
    with pytest.raises(AmbiguousActiveRelationshipStateError):
        _fail_closed_on_unexpected_multiplicity(fake_rows, "dev-1", "checkpoint", MAPPING_SCOPES[0])


# ---------------------------------------------------------------------------
# Closed-vocabulary validation at the Python layer (defense in depth)
# ---------------------------------------------------------------------------

def test_an_unknown_vendor_namespace_is_refused_before_touching_the_store(store):
    with pytest.raises(ValueError):
        record_first_contact_proof(store, **_proof(vendor_namespace="panorama"))
    assert store.connection.execute(
        "SELECT count(*) FROM device_identity_relationships"
    ).fetchone()[0] == 0


def test_an_unknown_mapping_scope_is_refused(store):
    with pytest.raises(ValueError):
        record_first_contact_proof(store, **_proof(mapping_scope="GENERAL_IDENTITY"))


def test_an_unknown_proof_type_is_refused(store):
    with pytest.raises(ValueError):
        record_first_contact_proof(store, **_proof(proof_type="operator_assertion"))


def test_an_unknown_proof_source_is_refused(store):
    with pytest.raises(ValueError):
        record_first_contact_proof(store, **_proof(proof_source="management_plane_read"))


@pytest.mark.parametrize(
    "field", ["device_id", "entity_id", "producing_run_ref", "registry_record_revision",
              "identity_derivation_contract_version"]
)
def test_an_empty_opaque_identifier_is_refused(store, field):
    with pytest.raises(ValueError):
        record_first_contact_proof(store, **_proof(**{field: ""}))


def test_get_active_relationship_validates_its_closed_vocabulary_arguments(store):
    with pytest.raises(ValueError):
        get_active_relationship(store, device_id="dev-1", vendor_namespace="panorama")


# ---------------------------------------------------------------------------
# Vocabularies are exactly what the frozen contract names -- no silent growth
# ---------------------------------------------------------------------------

def test_closed_vocabularies_match_the_frozen_contract():
    assert VENDOR_NAMESPACES == ("checkpoint",)
    assert MAPPING_SCOPES == ("CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY",)
    assert PROOF_TYPES == ("first_contact_identity_gate_and_serial",)
    assert PROOF_SOURCES == ("direct_device_read",)
    assert set(RELATIONSHIP_STATES) == {"ACTIVE", "INVALIDATED", "SUPERSEDED"}
    assert set(INVALIDATION_REASONS) == {"AMBIGUOUS_IDENTITY", "SUPERSEDED"}


# ---------------------------------------------------------------------------
# Privacy -- no forbidden concept reachable through this module's own writes
# ---------------------------------------------------------------------------

def test_a_proof_never_persists_a_serial_endpoint_or_trust_material(store):
    """This module's write path only ever accepts opaque references
    (`producing_run_ref`, `registry_record_revision`) -- there is no
    parameter shaped like a serial, endpoint or host-key fingerprint for a
    caller to pass through, so this proves the API surface rather than
    re-deriving `test_schema_owns_no_forbidden_concept`."""
    parameters = set(inspect.signature(record_first_contact_proof).parameters)
    forbidden = {"serial", "endpoint", "host_key_fingerprint", "trust_material", "credential"}
    assert parameters.isdisjoint(forbidden)
