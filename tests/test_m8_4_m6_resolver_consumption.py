"""`M8.4` -- M6 resolver consumption (frozen M8 contract §7).

Proves `console/registry_targets.py`'s live, read-only identity-translation
extension: a `config_refresh_cp` `device_id` target stops refusing with
`IDENTITY_TRANSLATION_REQUIRED` only when every frozen `M8` currency
condition holds (an `M8.1` `ACTIVE` relationship, registry-revision
currency, identity-derivation-version currency, local trust currency, and
resolvable, matching producing evidence) -- performed identically at
admission (`console/app.py`) and immediately before execution
(`console/runner.py`), before any credential or device operation.

No real device, socket, or credential resolution anywhere in this suite.
`M7` (functional per-device `config_refresh_cp`) is not implemented or
exercised here: a fully-resolved target only stops being refused by this
module; nothing here substitutes the resolved `entity_id` into a collector
command, so `main.main()` (patched throughout) is never asked to actually
target a real device.
"""
from __future__ import annotations

import inspect
import json
from pathlib import Path
from unittest import mock

import paramiko
import pytest

from console import registry_targets
from console.registry_targets import (
    DEVICE_REGISTRY_UNAVAILABLE,
    IDENTITY_TRANSLATION_REQUIRED,
    RELATIONSHIP_STORE_UNAVAILABLE,
    UNKNOWN_DEVICE_ID,
    resolve_registry_targets,
)
from utils.config_evidence import ConfigEvidenceStore, build_evidence_reference
from utils.control_plane_store import ControlPlaneStore, store_path
from utils.cp_ssh_trust import host_key_fingerprint
from utils.device_identity_relationships import (
    IDENTITY_DERIVATION_CONTRACT_VERSION,
    get_active_relationship,
    record_first_contact_proof,
)
from utils.device_registry import DeviceRegistry

from tests.test_con2_console_job_engine import (  # noqa: F401
    console_env,
    uitest_runtime_paths,
    _headers,
    _post_job,
    _wait_terminal,
)
from tests.test_m6_registry_keyed_job_targets import _corrupt_registry, _enroll

pytestmark = pytest.mark.configuration

_SOURCE = "checkpoint-gaia"
_ARTIFACT_TYPE = "gaia_show_configuration_redacted"
_ENTITY_ID = "FW1"


# ---------------------------------------------------------------------------
# Fixtures / helpers
# ---------------------------------------------------------------------------

def _isolated_ssh_profile(tmp_path: Path, monkeypatch) -> Path:
    home = tmp_path / "home"
    (home / ".ssh").mkdir(parents=True, exist_ok=True)
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setenv("USERPROFILE", str(home))
    monkeypatch.delenv("HOMEDRIVE", raising=False)
    monkeypatch.delenv("HOMEPATH", raising=False)
    return home / ".ssh" / "known_hosts"


def _device_record(data_root: Path, device_id: str):
    return next(r for r in DeviceRegistry(data_root).list() if r.device_id == device_id)


def _bump_updated_at(data_root: Path, device_id: str) -> None:
    """Simulate the registry record being touched again (e.g. a future edit
    operation) after a relationship was already proven against the prior
    revision -- there is no product-level 'edit' verb yet, so this mirrors
    tests/test_m6_registry_keyed_job_targets.py::_corrupt_registry's own
    direct-file-manipulation convention rather than inventing one."""
    path = data_root / "state" / "device_registry.json"
    document = json.loads(path.read_text(encoding="utf-8"))
    for row in document["devices"]:
        if row["device_id"] == device_id:
            row["updated_at"] = "2099-01-01T00:00:00Z"
    path.write_text(json.dumps(document), encoding="utf-8")


def _write_evidence(tmp_path: Path, *, entity_id: str, fingerprint: str | None) -> str:
    store = ConfigEvidenceStore(root=tmp_path / "configs")
    extra = {"host_key_fingerprint": fingerprint} if fingerprint else {}
    snapshot = store.write_text_snapshot(
        source=_SOURCE, entity_id=entity_id, artifact_type=_ARTIFACT_TYPE,
        content="set hostname test\n", method="test-fixture",
        artifact_name="gaia-show-configuration.redacted.txt", extra_metadata=extra,
    )
    return build_evidence_reference(source=_SOURCE, entity_id=entity_id, snapshot_id=snapshot.directory.name)


def _trust(tmp_path: Path, monkeypatch, *, endpoint: str) -> str:
    known_hosts = _isolated_ssh_profile(tmp_path, monkeypatch)
    key = paramiko.ECDSAKey.generate()
    known_hosts.write_text(f"{endpoint} {key.get_name()} {key.get_base64()}\n", encoding="utf-8")
    return host_key_fingerprint(key)


def _proof(
    data_root: Path,
    *,
    device_id: str,
    entity_id: str,
    producing_run_ref: str,
    registry_record_revision: str,
    identity_derivation_contract_version: str = IDENTITY_DERIVATION_CONTRACT_VERSION,
):
    with ControlPlaneStore(data_root) as store:
        return record_first_contact_proof(
            store,
            device_id=device_id,
            entity_id=entity_id,
            producing_run_ref=producing_run_ref,
            registry_record_revision=registry_record_revision,
            identity_derivation_contract_version=identity_derivation_contract_version,
        )


def _fully_resolved(
    tmp_path: Path, monkeypatch, *, endpoint: str = "192.0.2.70", entity_id: str = _ENTITY_ID,
    identity_derivation_contract_version: str = IDENTITY_DERIVATION_CONTRACT_VERSION,
    include_fingerprint: bool = True,
) -> str:
    """The complete, currently-resolvable M8 state for one device: registry
    record + trust + evidence + M8.1 relationship, exactly as M8.2/M8.3
    would have produced them (evidence carrying a `host_key_fingerprint`
    field is this test file's own fixture assumption -- see the module-level
    `_identity_resolved` docstring/comment for why real M8.3-produced
    evidence does not carry one today)."""
    device_id = _enroll(tmp_path, endpoint=endpoint)
    record = _device_record(tmp_path, device_id)
    fingerprint = _trust(tmp_path, monkeypatch, endpoint=endpoint)
    monkeypatch.setattr(
        registry_targets, "ConfigEvidenceStore", lambda: ConfigEvidenceStore(root=tmp_path / "configs")
    )
    ref = _write_evidence(tmp_path, entity_id=entity_id, fingerprint=fingerprint if include_fingerprint else None)
    outcome = _proof(
        tmp_path, device_id=device_id, entity_id=entity_id, producing_run_ref=ref,
        registry_record_revision=record.updated_at,
        identity_derivation_contract_version=identity_derivation_contract_version,
    )
    assert outcome.outcome == "NEW"
    return device_id


# ---------------------------------------------------------------------------
# Positive path
# ---------------------------------------------------------------------------

class TestValidResolution:
    def test_fully_resolved_target_is_not_refused(self, tmp_path, monkeypatch):
        device_id = _fully_resolved(tmp_path, monkeypatch)
        assert resolve_registry_targets((device_id,), data_root=tmp_path) is None

    def test_admission_and_pre_execution_agree_when_resolved(self, console_env, monkeypatch):
        device_id = _fully_resolved(console_env.runtime_paths.data_root, monkeypatch)
        with mock.patch("main.main", return_value=None) as fake_main:
            response = _post_job(console_env, "config_refresh_cp", targets=[device_id])
            assert response.status_code == 200
            job_id = response.json()["job_id"]
            final = _wait_terminal(console_env, job_id)
        fake_main.assert_called_once()
        assert final.state == "succeeded"


# ---------------------------------------------------------------------------
# Every independent currency failure -> IDENTITY_TRANSLATION_REQUIRED
# ---------------------------------------------------------------------------

class TestCurrencyFailuresFoldToIdentityTranslationRequired:
    def test_no_relationship_at_all(self, tmp_path):
        device_id = _enroll(tmp_path, endpoint="192.0.2.71")
        refusal = resolve_registry_targets((device_id,), data_root=tmp_path)
        assert refusal is not None
        assert refusal.reason == IDENTITY_TRANSLATION_REQUIRED

    def test_stale_registry_revision(self, tmp_path, monkeypatch):
        device_id = _fully_resolved(tmp_path, monkeypatch, endpoint="192.0.2.72")
        _bump_updated_at(tmp_path, device_id)
        refusal = resolve_registry_targets((device_id,), data_root=tmp_path)
        assert refusal is not None
        assert refusal.reason == IDENTITY_TRANSLATION_REQUIRED

    def test_stale_identity_derivation_contract_version(self, tmp_path, monkeypatch):
        device_id = _fully_resolved(
            tmp_path, monkeypatch, endpoint="192.0.2.73",
            identity_derivation_contract_version="an_older_contract_version.v0",
        )
        refusal = resolve_registry_targets((device_id,), data_root=tmp_path)
        assert refusal is not None
        assert refusal.reason == IDENTITY_TRANSLATION_REQUIRED

    def test_endpoint_not_currently_trusted(self, tmp_path, monkeypatch):
        # Same as _fully_resolved but never writes a known_hosts entry.
        endpoint = "192.0.2.74"
        device_id = _enroll(tmp_path, endpoint=endpoint)
        record = _device_record(tmp_path, device_id)
        _isolated_ssh_profile(tmp_path, monkeypatch)  # empty known_hosts
        monkeypatch.setattr(
            registry_targets, "ConfigEvidenceStore", lambda: ConfigEvidenceStore(root=tmp_path / "configs")
        )
        ref = _write_evidence(tmp_path, entity_id=_ENTITY_ID, fingerprint="SHA256:unused")
        _proof(tmp_path, device_id=device_id, entity_id=_ENTITY_ID, producing_run_ref=ref,
               registry_record_revision=record.updated_at)

        refusal = resolve_registry_targets((device_id,), data_root=tmp_path)
        assert refusal is not None
        assert refusal.reason == IDENTITY_TRANSLATION_REQUIRED

    def test_producing_evidence_unresolvable(self, tmp_path, monkeypatch):
        endpoint = "192.0.2.75"
        device_id = _enroll(tmp_path, endpoint=endpoint)
        record = _device_record(tmp_path, device_id)
        _trust(tmp_path, monkeypatch, endpoint=endpoint)
        monkeypatch.setattr(
            registry_targets, "ConfigEvidenceStore", lambda: ConfigEvidenceStore(root=tmp_path / "configs")
        )
        # A well-formed but never-written reference.
        fake_ref = build_evidence_reference(source=_SOURCE, entity_id=_ENTITY_ID, snapshot_id="does-not-exist")
        _proof(tmp_path, device_id=device_id, entity_id=_ENTITY_ID, producing_run_ref=fake_ref,
               registry_record_revision=record.updated_at)

        refusal = resolve_registry_targets((device_id,), data_root=tmp_path)
        assert refusal is not None
        assert refusal.reason == IDENTITY_TRANSLATION_REQUIRED

    def test_producing_evidence_belongs_to_a_different_entity_is_unusable(self, tmp_path, monkeypatch):
        """Corrupted/inconsistent state: the relationship's own `entity_id`
        column disagrees with the entity the referenced evidence was
        actually written for. Must never be treated as confirmed."""
        endpoint = "192.0.2.76"
        device_id = _enroll(tmp_path, endpoint=endpoint)
        record = _device_record(tmp_path, device_id)
        fingerprint = _trust(tmp_path, monkeypatch, endpoint=endpoint)
        monkeypatch.setattr(
            registry_targets, "ConfigEvidenceStore", lambda: ConfigEvidenceStore(root=tmp_path / "configs")
        )
        ref = _write_evidence(tmp_path, entity_id="SOME-OTHER-DEVICE", fingerprint=fingerprint)
        _proof(tmp_path, device_id=device_id, entity_id=_ENTITY_ID, producing_run_ref=ref,
               registry_record_revision=record.updated_at)

        refusal = resolve_registry_targets((device_id,), data_root=tmp_path)
        assert refusal is not None
        assert refusal.reason == IDENTITY_TRANSLATION_REQUIRED

    def test_fingerprint_no_longer_among_currently_trusted(self, tmp_path, monkeypatch):
        endpoint = "192.0.2.77"
        device_id = _enroll(tmp_path, endpoint=endpoint)
        record = _device_record(tmp_path, device_id)
        _trust(tmp_path, monkeypatch, endpoint=endpoint)  # a real, different, current fingerprint
        monkeypatch.setattr(
            registry_targets, "ConfigEvidenceStore", lambda: ConfigEvidenceStore(root=tmp_path / "configs")
        )
        ref = _write_evidence(tmp_path, entity_id=_ENTITY_ID, fingerprint="SHA256:stale-rotated-key-fingerprint")
        _proof(tmp_path, device_id=device_id, entity_id=_ENTITY_ID, producing_run_ref=ref,
               registry_record_revision=record.updated_at)

        refusal = resolve_registry_targets((device_id,), data_root=tmp_path)
        assert refusal is not None
        assert refusal.reason == IDENTITY_TRANSLATION_REQUIRED

    def test_todays_real_m8_3_evidence_shape_has_no_fingerprint_and_fails_closed(self, tmp_path, monkeypatch):
        """Documents a real gap (reported in this build's SESSION CLOSE, not
        silently patched): `_collect_host`'s persisted CP config evidence
        metadata carries no `host_key_fingerprint` field today, so this
        currency condition can never be affirmatively proven against real
        M8.3-produced evidence -- it must fail closed, not pass vacuously."""
        device_id = _fully_resolved(tmp_path, monkeypatch, endpoint="192.0.2.78", include_fingerprint=False)
        refusal = resolve_registry_targets((device_id,), data_root=tmp_path)
        assert refusal is not None
        assert refusal.reason == IDENTITY_TRANSLATION_REQUIRED


# ---------------------------------------------------------------------------
# Relationship-store failure
# ---------------------------------------------------------------------------

class TestRelationshipStoreUnavailable:
    def test_corrupt_control_plane_store(self, tmp_path):
        device_id = _enroll(tmp_path, endpoint="192.0.2.79")
        path = store_path(tmp_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"not a database")

        refusal = resolve_registry_targets((device_id,), data_root=tmp_path)
        assert refusal is not None
        assert refusal.reason == RELATIONSHIP_STORE_UNAVAILABLE
        assert refusal.reason != IDENTITY_TRANSLATION_REQUIRED

    def test_relationship_store_unavailable_detail_is_sanitized(self, tmp_path):
        device_id = _enroll(tmp_path, endpoint="192.0.2.81")
        path = store_path(tmp_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"not a database")

        refusal = resolve_registry_targets((device_id,), data_root=tmp_path)
        assert str(tmp_path) not in refusal.detail
        assert "traceback" not in refusal.detail.lower()
        assert device_id not in refusal.detail


# ---------------------------------------------------------------------------
# Trust lookup is local-only and precedes any credential/device operation
# ---------------------------------------------------------------------------

class TestTrustIsLocalOnly:
    def test_module_contains_no_credential_or_device_contact_code(self):
        source = inspect.getsource(registry_targets)
        assert "import paramiko" not in source  # trust is via utils.cp_ssh_trust only
        assert "ssh.connect(" not in source
        assert "_collect_host(" not in source
        assert "_connect(" not in source
        assert "import socket" not in source
        assert "make_runtime_config" not in source
        assert "_build_runtime_config" not in source

    def test_no_socket_opened_while_resolving(self, tmp_path, monkeypatch):
        import socket

        def _fail_if_called(*_a, **_k):
            raise AssertionError("resolve_registry_targets must never open a socket")

        monkeypatch.setattr(socket, "socket", _fail_if_called)
        monkeypatch.setattr(socket, "create_connection", _fail_if_called)

        device_id = _fully_resolved(tmp_path, monkeypatch, endpoint="192.0.2.82")
        assert resolve_registry_targets((device_id,), data_root=tmp_path) is None


# ---------------------------------------------------------------------------
# Stale-after-admission blocks before execution
# ---------------------------------------------------------------------------

class TestStaleAfterAdmission:
    def test_relationship_going_stale_after_admission_blocks_before_main(self, console_env, monkeypatch):
        data_root = console_env.runtime_paths.data_root
        device_id = _fully_resolved(data_root, monkeypatch, endpoint="192.0.2.83")
        assert resolve_registry_targets((device_id,), data_root=data_root) is None  # would have admitted

        record, is_new = console_env.job_store.submit(
            job_type="config_refresh_cp", command_class="read",
            targets=[device_id], idempotency_key="m8-4-stale-1",
        )
        assert is_new

        _bump_updated_at(data_root, device_id)  # goes stale between admission and execution

        with mock.patch("main.main") as fake_main:
            console_env.runner.enqueue(record.job_id)
            final = _wait_terminal(console_env, record.job_id)
        fake_main.assert_not_called()
        assert final.state == "blocked"
        assert final.error_code == IDENTITY_TRANSLATION_REQUIRED


# ---------------------------------------------------------------------------
# No Console relationship write; no leaked sensitive values
# ---------------------------------------------------------------------------

class TestNoConsoleWriteNoLeak:
    def test_no_relationship_row_written_by_the_resolver(self, tmp_path, monkeypatch):
        device_id = _fully_resolved(tmp_path, monkeypatch, endpoint="192.0.2.84")
        with ControlPlaneStore(tmp_path) as store:
            before = get_active_relationship(store, device_id=device_id)
        resolve_registry_targets((device_id,), data_root=tmp_path)
        resolve_registry_targets((device_id,), data_root=tmp_path)
        with ControlPlaneStore(tmp_path) as store:
            after = get_active_relationship(store, device_id=device_id)
        assert before is not None and after is not None
        assert before.relationship_id == after.relationship_id  # unchanged, no new row

    def test_no_device_registry_relationship_written(self, tmp_path, monkeypatch):
        device_id = _fully_resolved(tmp_path, monkeypatch, endpoint="192.0.2.85")
        resolve_registry_targets((device_id,), data_root=tmp_path)
        record = _device_record(tmp_path, device_id)
        assert record.relationships == []

    def test_sensitive_values_never_appear_in_a_refusal(self, tmp_path, monkeypatch):
        endpoint = "192.0.2.86"
        device_id = _enroll(tmp_path, endpoint=endpoint)
        record = _device_record(tmp_path, device_id)
        fingerprint = _trust(tmp_path, monkeypatch, endpoint=endpoint)
        monkeypatch.setattr(
            registry_targets, "ConfigEvidenceStore", lambda: ConfigEvidenceStore(root=tmp_path / "configs")
        )
        ref = _write_evidence(tmp_path, entity_id=_ENTITY_ID, fingerprint="SHA256:stale-fingerprint")
        _proof(tmp_path, device_id=device_id, entity_id=_ENTITY_ID, producing_run_ref=ref,
               registry_record_revision=record.updated_at)

        refusal = resolve_registry_targets((device_id,), data_root=tmp_path)
        assert refusal is not None
        for sensitive in (endpoint, fingerprint, _ENTITY_ID, "SHA256:stale-fingerprint", ref):
            assert sensitive not in refusal.detail
            assert sensitive not in refusal.reason

    def test_sensitive_values_never_leak_into_job_record(self, console_env, monkeypatch):
        endpoint = "192.0.2.87"
        device_id = _fully_resolved(console_env.runtime_paths.data_root, monkeypatch, endpoint=endpoint)
        _bump_updated_at(console_env.runtime_paths.data_root, device_id)  # force a refusal to inspect

        with mock.patch("main.main") as fake_main:
            response = _post_job(console_env, "config_refresh_cp", targets=[device_id])
        assert response.status_code == 400
        fake_main.assert_not_called()
        body = response.text
        assert endpoint not in body
        assert _ENTITY_ID not in body


# ---------------------------------------------------------------------------
# Ordering / mixed targets never partially execute
# ---------------------------------------------------------------------------

def test_one_unresolved_target_refuses_the_whole_mixed_request(tmp_path, monkeypatch):
    resolved = _fully_resolved(tmp_path, monkeypatch, endpoint="192.0.2.88")
    unresolved = _enroll(tmp_path, endpoint="192.0.2.89")
    refusal = resolve_registry_targets((resolved, unresolved), data_root=tmp_path)
    assert refusal is not None
    assert refusal.reason == IDENTITY_TRANSLATION_REQUIRED


def test_unknown_device_id_check_still_precedes_identity_resolution(tmp_path, monkeypatch):
    resolved = _fully_resolved(tmp_path, monkeypatch, endpoint="192.0.2.91")
    refusal = resolve_registry_targets((resolved, "ghost-device-id"), data_root=tmp_path)
    assert refusal is not None
    assert refusal.reason == UNKNOWN_DEVICE_ID


# ---------------------------------------------------------------------------
# M6's existing outcomes remain unchanged
# ---------------------------------------------------------------------------

def test_m6_unknown_device_id_unchanged(tmp_path):
    refusal = resolve_registry_targets(("does-not-exist",), data_root=tmp_path)
    assert refusal is not None
    assert refusal.reason == UNKNOWN_DEVICE_ID


def test_m6_device_registry_unavailable_unchanged(tmp_path):
    _corrupt_registry(tmp_path)
    refusal = resolve_registry_targets(("any-device-id",), data_root=tmp_path)
    assert refusal is not None
    assert refusal.reason == DEVICE_REGISTRY_UNAVAILABLE
