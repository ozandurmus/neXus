"""`M8.3` -- read-only first-contact identity-evidence producer.

Proves the frozen contract:
`docs/history/phase/M8_FIRST_CONTACT_TRUST_AND_IDENTITY_EVIDENCE_PRODUCER_ARCHITECTURE.md`
Section 3 (evidence model), Section 4 (mandatory trust-before-credential
sequence), Sections 6/8 (positive write gate, acceptance criteria).

No real device, socket, or network access anywhere in this suite --
`configuration.checkpoint_config_collector._collect_host` is monkeypatched
out in every test; a genuine identity-gate/SSH/RejectPolicy path is already
covered by the collector's own suites (`M8.3` reuses it unmodified, it does
not re-test it).
"""
from __future__ import annotations

import inspect
import json
from pathlib import Path

import pytest

from configuration import checkpoint_config_collector as cp_collector
from utils import first_contact_producer as producer
from utils.control_plane_store import ControlPlaneStore
from utils.cp_ssh_trust import TrustedKeyLookupResult
from utils.device_identity_relationships import (
    OUTCOME_AMBIGUOUS_IDENTITY,
    OUTCOME_NEW,
    OUTCOME_SUPERSEDED,
    get_active_relationship,
)
from utils.device_registry import DeviceRegistry

pytestmark = pytest.mark.configuration

_TEST_ENDPOINT = "192.0.2.10"
_TEST_SERIAL = "SN-SECRET-000111"
_TEST_USERNAME = "svc-cp-config"
_TEST_SECRET = "hunter2-do-not-log"  # pragma: allowlist secret -- synthetic, never a real credential


# ---------------------------------------------------------------------------
# Fixtures / helpers
# ---------------------------------------------------------------------------

@pytest.fixture
def data_root(tmp_path):
    return tmp_path / "runtime" / "data"


@pytest.fixture
def output_root(tmp_path):
    root = tmp_path / "runtime" / "output"
    root.mkdir(parents=True, exist_ok=True)
    return root


def _registry_path(data_root: Path) -> Path:
    return data_root / "state" / "device_registry.json"


def _write_cp_telemetry(output_root: Path, *, rows) -> None:
    (output_root / "cp_telemetry.json").write_text(
        json.dumps({"remote_command_status": list(rows)}), encoding="utf-8"
    )
    (output_root / "cp.json").write_text(json.dumps([]), encoding="utf-8")
    (output_root / "vsx.json").write_text(json.dumps([]), encoding="utf-8")


def _enroll(data_root: Path, *, endpoint: str = _TEST_ENDPOINT):
    return DeviceRegistry(data_root).enroll(endpoint=endpoint, vendor_hint="checkpoint")


def _trusted_lookup(monkeypatch, *, trusted: bool, reason: str | None = None):
    def _fake(endpoint, port):
        return TrustedKeyLookupResult(trusted=trusted, reason=reason)

    monkeypatch.setattr(producer, "lookup_trusted_host_key", _fake)


def _fake_evidence_store(monkeypatch, tmp_path):
    from utils.config_evidence import ConfigEvidenceStore

    monkeypatch.setattr(
        producer, "ConfigEvidenceStore", lambda: ConfigEvidenceStore(root=tmp_path / "configs")
    )


def _fake_collect_host(monkeypatch, *, accepted: bool, serial: str | None, write_snapshot: bool, calls: list):
    def _collect_host(target, *, username, secret, strict_host_key, connect_timeout, command_timeout, store):
        calls.append(
            {
                "device": target.device,
                "username": username,
                "secret": secret,
                "strict_host_key": strict_host_key,
            }
        )
        if write_snapshot:
            store.write_text_snapshot(
                source=cp_collector.SOURCE,
                entity_id=cp_collector._entity_id(target),
                artifact_type=cp_collector.PHYSICAL_ARTIFACT_TYPE,
                content="set hostname test\n",
                method="test-fixture",
                artifact_name="gaia-show-configuration.redacted.txt",
            )
        return [
            {
                "entity_id": cp_collector._entity_id(target),
                "entity_type": target.entity_type,
                "device": target.device,
                "identity_gate": {"accepted": accepted, "status": "TEST"},
                "serial": serial,
                "current_configuration": {"status": "success" if write_snapshot else "unavailable"},
            }
        ]

    monkeypatch.setattr(cp_collector, "_collect_host", _collect_host)


def _credentials_spy(calls: list):
    def _resolve():
        calls.append(True)
        return _TEST_USERNAME, _TEST_SECRET

    return _resolve


def _run(monkeypatch, *, data_root, output_root, device_id, resolve_credentials, tmp_path):
    _fake_evidence_store(monkeypatch, tmp_path)
    return producer.run_first_contact_producer(
        device_id=device_id,
        data_root=data_root,
        output_root=output_root,
        resolve_credentials=resolve_credentials,
    )


# ---------------------------------------------------------------------------
# Device Registry resolution (contract Section 2) -- fail-closed, read-only
# ---------------------------------------------------------------------------

class TestRegistryResolution:
    def test_unknown_device_id_refuses(self, monkeypatch, data_root, output_root, tmp_path):
        _enroll(data_root)  # some other device exists, but not the one requested
        calls = []
        _fake_collect_host(monkeypatch, accepted=True, serial=_TEST_SERIAL, write_snapshot=True, calls=calls)

        outcome = _run(
            monkeypatch, data_root=data_root, output_root=output_root,
            device_id="not-a-real-device-id", resolve_credentials=_credentials_spy([]), tmp_path=tmp_path,
        )

        assert outcome.status == producer.UNKNOWN_DEVICE_ID
        assert calls == []

    def test_disabled_device_is_not_eligible(self, monkeypatch, data_root, output_root, tmp_path):
        record = _enroll(data_root)
        DeviceRegistry(data_root).disable(record.device_id)
        calls = []
        _fake_collect_host(monkeypatch, accepted=True, serial=_TEST_SERIAL, write_snapshot=True, calls=calls)

        outcome = _run(
            monkeypatch, data_root=data_root, output_root=output_root,
            device_id=record.device_id, resolve_credentials=_credentials_spy([]), tmp_path=tmp_path,
        )

        assert outcome.status == producer.DEVICE_NOT_ELIGIBLE
        assert calls == []

    def test_unreadable_registry_refuses(self, monkeypatch, data_root, output_root, tmp_path):
        (data_root / "state").mkdir(parents=True, exist_ok=True)
        (data_root / "state" / "device_registry.json").write_text("not json", encoding="utf-8")

        outcome = _run(
            monkeypatch, data_root=data_root, output_root=output_root,
            device_id="whatever", resolve_credentials=_credentials_spy([]), tmp_path=tmp_path,
        )

        assert outcome.status == producer.DEVICE_REGISTRY_UNAVAILABLE


# ---------------------------------------------------------------------------
# Candidate selection (contract Section 3 point 1)
# ---------------------------------------------------------------------------

class TestCandidateSelection:
    def test_zero_candidates_refuses_before_any_contact(self, monkeypatch, data_root, output_root, tmp_path):
        record = _enroll(data_root)
        # A real discovery checkpoint exists, but no candidate's management_ip
        # matches the registry's endpoint -- distinct from no discovery
        # evidence existing at all (DISCOVERY_EVIDENCE_UNAVAILABLE).
        _write_cp_telemetry(
            output_root, rows=[{"device": "OTHER", "management_ip": "192.0.2.99", "object_type": "gateway"}]
        )
        calls = []
        _fake_collect_host(monkeypatch, accepted=True, serial=_TEST_SERIAL, write_snapshot=True, calls=calls)
        creds_calls = []

        outcome = _run(
            monkeypatch, data_root=data_root, output_root=output_root,
            device_id=record.device_id, resolve_credentials=_credentials_spy(creds_calls), tmp_path=tmp_path,
        )

        assert outcome.status == producer.NO_PHYSICAL_CANDIDATE
        assert calls == []
        assert creds_calls == []
        with ControlPlaneStore(data_root) as store:
            assert get_active_relationship(store, device_id=record.device_id) is None

    def test_no_discovery_evidence_at_all_refuses_before_any_contact(
        self, monkeypatch, data_root, output_root, tmp_path
    ):
        record = _enroll(data_root)
        # No prior CP/VSX inventory checkpoint has ever run in this RuntimeRoot.
        calls = []
        _fake_collect_host(monkeypatch, accepted=True, serial=_TEST_SERIAL, write_snapshot=True, calls=calls)

        outcome = _run(
            monkeypatch, data_root=data_root, output_root=output_root,
            device_id=record.device_id, resolve_credentials=_credentials_spy([]), tmp_path=tmp_path,
        )

        assert outcome.status == producer.DISCOVERY_EVIDENCE_UNAVAILABLE
        assert calls == []

    def test_multiple_candidates_refuses_before_any_contact(self, monkeypatch, data_root, output_root, tmp_path):
        record = _enroll(data_root)
        _write_cp_telemetry(
            output_root,
            rows=[
                {"device": "FW1", "management_ip": _TEST_ENDPOINT, "object_type": "gateway"},
                {"device": "FW1-DUP", "management_ip": _TEST_ENDPOINT, "object_type": "gateway"},
            ],
        )
        calls = []
        _fake_collect_host(monkeypatch, accepted=True, serial=_TEST_SERIAL, write_snapshot=True, calls=calls)
        creds_calls = []

        outcome = _run(
            monkeypatch, data_root=data_root, output_root=output_root,
            device_id=record.device_id, resolve_credentials=_credentials_spy(creds_calls), tmp_path=tmp_path,
        )

        assert outcome.status == producer.AMBIGUOUS_PHYSICAL_CANDIDATE
        assert calls == []
        assert creds_calls == []

    def test_exact_single_candidate_proceeds(self, monkeypatch, data_root, output_root, tmp_path):
        record = _enroll(data_root)
        _write_cp_telemetry(
            output_root, rows=[{"device": "FW1", "management_ip": _TEST_ENDPOINT, "object_type": "gateway"}]
        )
        _trusted_lookup(monkeypatch, trusted=True)
        calls = []
        _fake_collect_host(monkeypatch, accepted=True, serial=_TEST_SERIAL, write_snapshot=True, calls=calls)

        outcome = _run(
            monkeypatch, data_root=data_root, output_root=output_root,
            device_id=record.device_id, resolve_credentials=_credentials_spy([]), tmp_path=tmp_path,
        )

        assert outcome.status == OUTCOME_NEW
        assert len(calls) == 1


# ---------------------------------------------------------------------------
# Trust-before-credential (contract Section 4, Section 8 AC2)
# ---------------------------------------------------------------------------

class TestTrustBeforeCredential:
    def test_lookup_precedes_credential_resolution(self, monkeypatch, data_root, output_root, tmp_path):
        record = _enroll(data_root)
        _write_cp_telemetry(
            output_root, rows=[{"device": "FW1", "management_ip": _TEST_ENDPOINT, "object_type": "gateway"}]
        )
        order = []

        def _fake_trust(endpoint, port):
            order.append("trust")
            return TrustedKeyLookupResult(trusted=True, reason=None)

        monkeypatch.setattr(producer, "lookup_trusted_host_key", _fake_trust)

        def _resolve_credentials():
            order.append("credentials")
            return _TEST_USERNAME, _TEST_SECRET

        def _collect_host_ordered(target, **_kwargs):
            order.append("collect_host")
            return [
                {
                    "entity_id": cp_collector._entity_id(target),
                    "identity_gate": {"accepted": True, "status": "TEST"},
                    "serial": _TEST_SERIAL,
                    "current_configuration": {"status": "unavailable"},
                }
            ]

        monkeypatch.setattr(cp_collector, "_collect_host", _collect_host_ordered)

        _run(
            monkeypatch, data_root=data_root, output_root=output_root,
            device_id=record.device_id, resolve_credentials=_resolve_credentials, tmp_path=tmp_path,
        )

        assert order[0] == "trust"
        assert order.index("trust") < order.index("credentials")

    def test_untrusted_endpoint_reaches_neither_credentials_nor_collector_and_writes_no_row(
        self, monkeypatch, data_root, output_root, tmp_path
    ):
        record = _enroll(data_root)
        _write_cp_telemetry(
            output_root, rows=[{"device": "FW1", "management_ip": _TEST_ENDPOINT, "object_type": "gateway"}]
        )
        _trusted_lookup(monkeypatch, trusted=False, reason="endpoint_not_trusted")

        def _collect_host_must_not_be_called(*_a, **_k):
            raise AssertionError("_collect_host must not be called for an untrusted endpoint")

        monkeypatch.setattr(cp_collector, "_collect_host", _collect_host_must_not_be_called)

        creds_calls = []

        def _resolve_credentials_must_not_be_called():
            creds_calls.append(True)
            raise AssertionError("credentials must not be resolved for an untrusted endpoint")

        outcome = _run(
            monkeypatch, data_root=data_root, output_root=output_root,
            device_id=record.device_id, resolve_credentials=_resolve_credentials_must_not_be_called,
            tmp_path=tmp_path,
        )

        assert outcome.status == producer.ENDPOINT_NOT_TRUSTED
        assert outcome.reason == "endpoint_not_trusted"
        assert creds_calls == []
        with ControlPlaneStore(data_root) as store:
            assert get_active_relationship(store, device_id=record.device_id) is None


# ---------------------------------------------------------------------------
# The positive write gate (contract Section 3 point 4, Section 8 AC1/AC10)
# ---------------------------------------------------------------------------

class TestPositiveWriteGate:
    def _setup(self, monkeypatch, data_root, output_root):
        record = _enroll(data_root)
        _write_cp_telemetry(
            output_root, rows=[{"device": "FW1", "management_ip": _TEST_ENDPOINT, "object_type": "gateway"}]
        )
        _trusted_lookup(monkeypatch, trusted=True)
        return record

    def test_identity_gate_rejected_writes_no_row(self, monkeypatch, data_root, output_root, tmp_path):
        record = self._setup(monkeypatch, data_root, output_root)
        calls = []
        _fake_collect_host(monkeypatch, accepted=False, serial=_TEST_SERIAL, write_snapshot=False, calls=calls)

        outcome = _run(
            monkeypatch, data_root=data_root, output_root=output_root,
            device_id=record.device_id, resolve_credentials=_credentials_spy([]), tmp_path=tmp_path,
        )

        assert outcome.status == producer.IDENTITY_GATE_REJECTED
        with ControlPlaneStore(data_root) as store:
            assert get_active_relationship(store, device_id=record.device_id) is None

    def test_accepted_identity_missing_serial_writes_no_row_and_is_sanitized(
        self, monkeypatch, data_root, output_root, tmp_path
    ):
        record = self._setup(monkeypatch, data_root, output_root)
        calls = []
        _fake_collect_host(monkeypatch, accepted=True, serial=None, write_snapshot=False, calls=calls)

        outcome = _run(
            monkeypatch, data_root=data_root, output_root=output_root,
            device_id=record.device_id, resolve_credentials=_credentials_spy([]), tmp_path=tmp_path,
        )

        assert outcome.status == "identity_gate_accepted_serial_unavailable"
        assert outcome.status == producer.IDENTITY_GATE_ACCEPTED_SERIAL_UNAVAILABLE
        with ControlPlaneStore(data_root) as store:
            assert get_active_relationship(store, device_id=record.device_id) is None

    def test_missing_producing_evidence_writes_no_row(self, monkeypatch, data_root, output_root, tmp_path):
        record = self._setup(monkeypatch, data_root, output_root)
        calls = []
        # Identity accepted and a serial was read, but no CP config evidence
        # snapshot was ever written for this run (e.g. the collector's
        # "show configuration" step failed after an hostname+version-only
        # identity accept) -- the positive gate's third condition fails.
        _fake_collect_host(monkeypatch, accepted=True, serial=_TEST_SERIAL, write_snapshot=False, calls=calls)

        outcome = _run(
            monkeypatch, data_root=data_root, output_root=output_root,
            device_id=record.device_id, resolve_credentials=_credentials_spy([]), tmp_path=tmp_path,
        )

        assert outcome.status == producer.PRODUCING_EVIDENCE_UNRESOLVABLE
        with ControlPlaneStore(data_root) as store:
            assert get_active_relationship(store, device_id=record.device_id) is None

    def test_complete_positive_path_writes_one_correctly_scoped_relationship(
        self, monkeypatch, data_root, output_root, tmp_path
    ):
        record = self._setup(monkeypatch, data_root, output_root)
        calls = []
        _fake_collect_host(monkeypatch, accepted=True, serial=_TEST_SERIAL, write_snapshot=True, calls=calls)

        outcome = _run(
            monkeypatch, data_root=data_root, output_root=output_root,
            device_id=record.device_id, resolve_credentials=_credentials_spy([]), tmp_path=tmp_path,
        )

        assert outcome.status == OUTCOME_NEW
        assert outcome.relationship_id
        with ControlPlaneStore(data_root) as store:
            relationship = get_active_relationship(store, device_id=record.device_id)
        assert relationship is not None
        assert relationship.entity_id == "FW1"
        assert relationship.vendor_namespace == "checkpoint"
        assert relationship.mapping_scope == "CLASS_0_CP_CONFIG_TARGET_SELECTION_ONLY"
        assert relationship.proof_type == "first_contact_identity_gate_and_serial"
        assert relationship.proof_source == "direct_device_read"
        assert relationship.registry_record_revision == record.updated_at
        assert relationship.identity_derivation_contract_version == producer.IDENTITY_DERIVATION_CONTRACT_VERSION
        assert relationship.identity_mapping_proven == 1
        assert relationship.state == "ACTIVE"


# ---------------------------------------------------------------------------
# Registry non-mutation (contract Section 2, Section 11)
# ---------------------------------------------------------------------------

class TestRegistryUntouched:
    def test_no_registry_mutation_occurs(self, monkeypatch, data_root, output_root, tmp_path):
        record = _enroll(data_root)
        _write_cp_telemetry(
            output_root, rows=[{"device": "FW1", "management_ip": _TEST_ENDPOINT, "object_type": "gateway"}]
        )
        _trusted_lookup(monkeypatch, trusted=True)
        calls = []
        _fake_collect_host(monkeypatch, accepted=True, serial=_TEST_SERIAL, write_snapshot=True, calls=calls)

        before = _registry_path(data_root).read_bytes()
        _run(
            monkeypatch, data_root=data_root, output_root=output_root,
            device_id=record.device_id, resolve_credentials=_credentials_spy([]), tmp_path=tmp_path,
        )
        after = _registry_path(data_root).read_bytes()

        assert before == after
        assert not (data_root / "state" / "device_registry.lock").exists()


# ---------------------------------------------------------------------------
# Privacy: no raw serial/endpoint/host-key/credential/exception leakage
# ---------------------------------------------------------------------------

class TestSanitization:
    def test_positive_outcome_carries_no_raw_identity_or_credential_values(
        self, monkeypatch, data_root, output_root, tmp_path
    ):
        record = _enroll(data_root)
        _write_cp_telemetry(
            output_root, rows=[{"device": "FW1", "management_ip": _TEST_ENDPOINT, "object_type": "gateway"}]
        )
        _trusted_lookup(monkeypatch, trusted=True)
        calls = []
        _fake_collect_host(monkeypatch, accepted=True, serial=_TEST_SERIAL, write_snapshot=True, calls=calls)

        outcome = _run(
            monkeypatch, data_root=data_root, output_root=output_root,
            device_id=record.device_id, resolve_credentials=_credentials_spy([]), tmp_path=tmp_path,
        )

        outcome_text = repr(outcome)
        for sensitive in (_TEST_SERIAL, _TEST_ENDPOINT, _TEST_USERNAME, _TEST_SECRET):
            assert sensitive not in outcome_text

        with ControlPlaneStore(data_root) as store:
            relationship = get_active_relationship(store, device_id=record.device_id)
        relationship_text = repr(relationship)
        for sensitive in (_TEST_SERIAL, _TEST_ENDPOINT, _TEST_USERNAME, _TEST_SECRET):
            assert sensitive not in relationship_text

    def test_collector_exception_is_sanitized_never_raw(self, monkeypatch, data_root, output_root, tmp_path):
        record = _enroll(data_root)
        _write_cp_telemetry(
            output_root, rows=[{"device": "FW1", "management_ip": _TEST_ENDPOINT, "object_type": "gateway"}]
        )
        _trusted_lookup(monkeypatch, trusted=True)

        def _collect_host_raises(*_a, **_k):
            raise RuntimeError(f"boom while talking to {_TEST_ENDPOINT} as {_TEST_USERNAME}")

        monkeypatch.setattr(cp_collector, "_collect_host", _collect_host_raises)

        outcome = _run(
            monkeypatch, data_root=data_root, output_root=output_root,
            device_id=record.device_id, resolve_credentials=_credentials_spy([]), tmp_path=tmp_path,
        )

        assert outcome.status == producer.COLLECTOR_ERROR
        outcome_text = repr(outcome)
        assert _TEST_ENDPOINT not in outcome_text
        assert _TEST_USERNAME not in outcome_text
        with ControlPlaneStore(data_root) as store:
            assert get_active_relationship(store, device_id=record.device_id) is None


# ---------------------------------------------------------------------------
# Single-target contact, existing collector reuse (contract Section 4, Section 6)
# ---------------------------------------------------------------------------

class TestSingleTargetReuse:
    def test_contacts_at_most_one_target(self, monkeypatch, data_root, output_root, tmp_path):
        record = _enroll(data_root)
        _write_cp_telemetry(
            output_root, rows=[{"device": "FW1", "management_ip": _TEST_ENDPOINT, "object_type": "gateway"}]
        )
        _trusted_lookup(monkeypatch, trusted=True)
        calls = []
        _fake_collect_host(monkeypatch, accepted=True, serial=_TEST_SERIAL, write_snapshot=True, calls=calls)

        _run(
            monkeypatch, data_root=data_root, output_root=output_root,
            device_id=record.device_id, resolve_credentials=_credentials_spy([]), tmp_path=tmp_path,
        )

        assert len(calls) == 1
        assert calls[0]["device"] == "FW1"
        assert calls[0]["strict_host_key"] is True

    def test_no_copied_collector_no_raw_ssh_use(self):
        source = inspect.getsource(producer)
        assert "_collect_host(" in source
        assert "paramiko" not in source
        assert "ssh.connect(" not in source
        assert ".connect(" not in source


# ---------------------------------------------------------------------------
# M8.1 NEW / SUPERSEDED / AMBIGUOUS_IDENTITY pass-through (contract Section 6)
# ---------------------------------------------------------------------------

class TestM81OutcomesPreserved:
    def test_new_then_superseded_then_ambiguous(self, monkeypatch, data_root, output_root, tmp_path):
        record = _enroll(data_root)
        _trusted_lookup(monkeypatch, trusted=True)

        # 1) NEW -- first proof for this triple.
        _write_cp_telemetry(
            output_root, rows=[{"device": "FW1", "management_ip": _TEST_ENDPOINT, "object_type": "gateway"}]
        )
        calls = []
        _fake_collect_host(monkeypatch, accepted=True, serial=_TEST_SERIAL, write_snapshot=True, calls=calls)
        outcome_1 = _run(
            monkeypatch, data_root=data_root, output_root=output_root,
            device_id=record.device_id, resolve_credentials=_credentials_spy([]), tmp_path=tmp_path,
        )
        assert outcome_1.status == OUTCOME_NEW

        # 2) SUPERSEDED -- a fresh proof for the same (device_id, entity_id).
        outcome_2 = _run(
            monkeypatch, data_root=data_root, output_root=output_root,
            device_id=record.device_id, resolve_credentials=_credentials_spy([]), tmp_path=tmp_path,
        )
        assert outcome_2.status == OUTCOME_SUPERSEDED

        # 3) AMBIGUOUS_IDENTITY -- a new proof for the same device_id yields a
        # different entity_id (the discovered physical device name changed).
        _write_cp_telemetry(
            output_root, rows=[{"device": "FW2", "management_ip": _TEST_ENDPOINT, "object_type": "gateway"}]
        )
        outcome_3 = _run(
            monkeypatch, data_root=data_root, output_root=output_root,
            device_id=record.device_id, resolve_credentials=_credentials_spy([]), tmp_path=tmp_path,
        )
        assert outcome_3.status == OUTCOME_AMBIGUOUS_IDENTITY
        with ControlPlaneStore(data_root) as store:
            assert get_active_relationship(store, device_id=record.device_id) is None
