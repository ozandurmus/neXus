"""event_signal_intake Slice 1 -- ``docs/design/EVENT_SIGNAL_INTAKE_ARCHITECTURE.md``.

AC-5: synthetic-payload proof that replay is rejected, malformed/
out-of-allowlist schema is rejected, dedup/cooldown behaves, and the signal
path cannot write evidence -- it can only ever reach
``console.jobs.ConsoleJobStore.submit()`` / ``ConsoleJobRunner.enqueue()``,
never a collector, credential, or device. No real device, socket, or
credential resolution anywhere in this suite.
"""
from __future__ import annotations

import ast
import hashlib
import hmac
import json
import time
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

pytestmark = pytest.mark.discovery

from console.jobs import ConsoleJobStore
from console.runner import ConsoleJobRunner
from signal_intake.app import create_signal_intake_app
from utils.device_registry import DeviceRegistry
from utils.event_signal_intake import SIGNAL_HMAC_SECRET_ENV

from tests.test_m8_4_m6_resolver_consumption import _fully_resolved

REPO_ROOT = Path(__file__).resolve().parents[1]
SECRET = "test-shared-secret"

VALID_BODY = {
    "signal_id": "sig-1",
    "event_type": "policy_install",
    "device_reference": "cp-gw-1.example.internal",
}


# ---------------------------------------------------------------------------
# Fixtures / helpers
# ---------------------------------------------------------------------------

@pytest.fixture()
def runtime_paths(tmp_path, monkeypatch):
    data_root = tmp_path / "data"
    (data_root / "state").mkdir(parents=True)
    monkeypatch.setenv(SIGNAL_HMAC_SECRET_ENV, SECRET)
    return SimpleNamespace(
        repository_root=REPO_ROOT,
        output_root=tmp_path / "output",
        data_root=data_root,
    )


class _RecordingRunner(ConsoleJobRunner):
    """AC-5, "the signal path cannot write evidence": this runner's worker
    thread never starts, so nothing in this test suite ever calls
    ``main.main()``, a collector, or a device. ``enqueue`` only records the
    job_id it was asked to run -- the *submission* is what this suite
    proves; ``console/runner.py``'s own already-tested suite
    (``tests/test_con2_console_job_engine.py``) proves what happens after a
    real worker thread picks a queued job up."""

    def __init__(self, *, job_store, runtime_paths, services, provenance="event"):
        super().__init__(
            job_store=job_store, runtime_paths=runtime_paths, services=services, provenance=provenance
        )
        self.enqueued_job_ids: list[str] = []

    def start(self) -> None:  # pragma: no cover - deliberately inert
        raise AssertionError("_RecordingRunner must never start a worker thread in this suite")

    def enqueue(self, job_id: str) -> None:
        self.enqueued_job_ids.append(job_id)


def _make_app(runtime_paths):
    from utils.collection_executor import CollectionCoordinator, RuntimeCollectionServices

    job_store = ConsoleJobStore(runtime_paths.data_root)
    runner = _RecordingRunner(
        job_store=job_store,
        runtime_paths=runtime_paths,
        services=RuntimeCollectionServices(coordinator=CollectionCoordinator()),
    )
    app = create_signal_intake_app(runtime_paths=runtime_paths, job_store=job_store, runner=runner)
    return app, job_store, runner


def _enroll(runtime_paths, *, endpoint="cp-gw-1.example.internal", vendor="checkpoint"):
    registry = DeviceRegistry(runtime_paths.data_root)
    return registry.enroll(endpoint=endpoint, vendor_hint=vendor)


def _sign(*, secret: str, timestamp: str, nonce: str, body: bytes) -> str:
    material = f"{timestamp}.{nonce}.".encode("utf-8") + body
    digest = hmac.new(secret.encode("utf-8"), material, hashlib.sha256).hexdigest()
    return f"sha256={digest}"


def _post(client, *, body: dict, timestamp=None, nonce="nonce-1", secret=SECRET, signature=None):
    raw = json.dumps(body).encode("utf-8")
    ts = str(timestamp if timestamp is not None else int(time.time()))
    sig = signature if signature is not None else _sign(secret=secret, timestamp=ts, nonce=nonce, body=raw)
    headers = {
        "X-Signal-Timestamp": ts,
        "X-Signal-Nonce": nonce,
        "X-Signal-Signature": sig,
        "Content-Type": "application/json",
    }
    return client.post("/events", content=raw, headers=headers)


# ---------------------------------------------------------------------------
# Structural: the intake path never imports a vendor/collector module
# (mirrors tests/test_con1_operator_console_read_only.py's own AC-8 probe)
# ---------------------------------------------------------------------------

def test_signal_intake_imports_no_vendor_or_collector_module():
    for relative in ("signal_intake/app.py", "utils/event_signal_intake.py"):
        tree = ast.parse((REPO_ROOT / relative).read_text(encoding="utf-8"))
        imported_roots = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported_roots.update(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module:
                imported_roots.add(node.module.split(".")[0])
        forbidden = imported_roots & {"checkpoint", "panorama", "configuration"}
        assert not forbidden, f"{relative} imports vendor/collector module(s): {forbidden}"


# ---------------------------------------------------------------------------
# AC-5: schema allowlist
# ---------------------------------------------------------------------------

def test_unknown_field_is_rejected(runtime_paths):
    app, job_store, runner = _make_app(runtime_paths)
    client = TestClient(app)
    body = {**VALID_BODY, "extra_field": "nope"}
    resp = _post(client, body=body, nonce="n-schema-1")
    assert resp.status_code == 400
    assert "extra_field" in resp.json()["detail"]
    assert job_store.list_all() == []


def test_unknown_event_type_is_rejected(runtime_paths):
    app, job_store, runner = _make_app(runtime_paths)
    client = TestClient(app)
    body = {**VALID_BODY, "event_type": "reboot_now"}
    resp = _post(client, body=body, nonce="n-schema-2")
    assert resp.status_code == 400
    assert job_store.list_all() == []


def test_missing_required_field_is_rejected(runtime_paths):
    app, job_store, runner = _make_app(runtime_paths)
    client = TestClient(app)
    body = {k: v for k, v in VALID_BODY.items() if k != "device_reference"}
    resp = _post(client, body=body, nonce="n-schema-3")
    assert resp.status_code == 400
    assert job_store.list_all() == []


def test_non_string_body_is_rejected(runtime_paths):
    app, job_store, runner = _make_app(runtime_paths)
    client = TestClient(app)
    resp = _post(client, body=["not", "an", "object"], nonce="n-schema-4")
    assert resp.status_code == 400
    assert job_store.list_all() == []


# ---------------------------------------------------------------------------
# AC-5: authentication
# ---------------------------------------------------------------------------

def test_missing_signature_is_rejected(runtime_paths):
    app, job_store, runner = _make_app(runtime_paths)
    client = TestClient(app)
    raw = json.dumps(VALID_BODY).encode("utf-8")
    resp = client.post(
        "/events", content=raw,
        headers={"X-Signal-Timestamp": str(int(time.time())), "X-Signal-Nonce": "n-auth-1"},
    )
    assert resp.status_code == 401
    assert job_store.list_all() == []


def test_wrong_secret_is_rejected(runtime_paths):
    app, job_store, runner = _make_app(runtime_paths)
    client = TestClient(app)
    resp = _post(client, body=VALID_BODY, nonce="n-auth-2", secret="not-the-real-secret")
    assert resp.status_code == 401
    assert job_store.list_all() == []


def test_tampered_body_is_rejected(runtime_paths):
    app, job_store, runner = _make_app(runtime_paths)
    client = TestClient(app)
    ts = str(int(time.time()))
    nonce = "n-auth-3"
    good_sig = _sign(secret=SECRET, timestamp=ts, nonce=nonce, body=json.dumps(VALID_BODY).encode("utf-8"))
    tampered = {**VALID_BODY, "device_reference": "someone-elses-gateway"}
    resp = client.post(
        "/events", content=json.dumps(tampered).encode("utf-8"),
        headers={"X-Signal-Timestamp": ts, "X-Signal-Nonce": nonce, "X-Signal-Signature": good_sig},
    )
    assert resp.status_code == 401
    assert job_store.list_all() == []


def test_missing_secret_configuration_fails_closed(runtime_paths, monkeypatch):
    monkeypatch.delenv(SIGNAL_HMAC_SECRET_ENV, raising=False)
    app, job_store, runner = _make_app(runtime_paths)
    client = TestClient(app)
    resp = _post(client, body=VALID_BODY, nonce="n-auth-4")
    assert resp.status_code == 401
    assert job_store.list_all() == []


# ---------------------------------------------------------------------------
# AC-5: replay protection (nonce/timestamp-window)
# ---------------------------------------------------------------------------

def test_stale_timestamp_is_rejected(runtime_paths):
    app, job_store, runner = _make_app(runtime_paths)
    client = TestClient(app)
    stale_ts = int(time.time()) - 10_000
    resp = _post(client, body=VALID_BODY, timestamp=stale_ts, nonce="n-replay-1")
    assert resp.status_code == 401
    assert job_store.list_all() == []


def test_future_timestamp_is_rejected(runtime_paths):
    app, job_store, runner = _make_app(runtime_paths)
    client = TestClient(app)
    future_ts = int(time.time()) + 10_000
    resp = _post(client, body=VALID_BODY, timestamp=future_ts, nonce="n-replay-2")
    assert resp.status_code == 401
    assert job_store.list_all() == []


def test_replayed_nonce_is_rejected_on_second_use(runtime_paths):
    app, job_store, runner = _make_app(runtime_paths)
    client = TestClient(app)
    ts = int(time.time())
    body = {**VALID_BODY, "signal_id": "sig-replay", "device_reference": "never-enrolled.example.internal"}
    first = _post(client, body=body, timestamp=ts, nonce="n-replay-3")
    assert first.status_code == 200  # unknown_identity -- but authenticated and not a replay
    second = _post(client, body=body, timestamp=ts, nonce="n-replay-3")
    assert second.status_code == 401


# ---------------------------------------------------------------------------
# AC-3/AC-4: identity resolution + trigger
# ---------------------------------------------------------------------------

def test_unknown_device_reference_reports_unknown_identity_and_writes_no_job(runtime_paths):
    app, job_store, runner = _make_app(runtime_paths)
    client = TestClient(app)
    body = {**VALID_BODY, "device_reference": "never-enrolled.example.internal", "signal_id": "sig-unk"}
    resp = _post(client, body=body, nonce="n-identity-1")
    assert resp.status_code == 200
    assert resp.json()["status"] == "unknown_identity"
    assert job_store.list_all() == []
    assert runner.enqueued_job_ids == []


def test_non_checkpoint_vendor_reports_unsupported_target_seam(runtime_paths):
    _enroll(runtime_paths, endpoint="pan-fw-1.example.internal", vendor="paloalto")
    app, job_store, runner = _make_app(runtime_paths)
    client = TestClient(app)
    body = {**VALID_BODY, "device_reference": "pan-fw-1.example.internal", "signal_id": "sig-pan"}
    resp = _post(client, body=body, nonce="n-identity-2")
    assert resp.status_code == 200
    assert resp.json()["status"] == "unsupported_vendor_target_seam"
    assert job_store.list_all() == []
    assert runner.enqueued_job_ids == []


def test_known_but_not_identity_translated_device_is_refused_before_any_job(runtime_paths):
    _enroll(runtime_paths, endpoint="cp-gw-2.example.internal", vendor="checkpoint")
    app, job_store, runner = _make_app(runtime_paths)
    client = TestClient(app)
    body = {**VALID_BODY, "device_reference": "cp-gw-2.example.internal", "signal_id": "sig-cp-2"}
    resp = _post(client, body=body, nonce="n-identity-3")
    assert resp.status_code == 200
    assert resp.json()["status"] == "IDENTITY_TRANSLATION_REQUIRED"
    assert job_store.list_all() == []
    assert runner.enqueued_job_ids == []


def test_fully_resolved_device_triggers_exactly_one_queued_job(runtime_paths, monkeypatch):
    """AC-4: the only observable effect of a fully valid, identity-resolved
    signal is one durable, ``queued`` job record plus one runner enqueue
    call -- never a collector call, never evidence."""
    device_id = _fully_resolved(runtime_paths.data_root, monkeypatch, endpoint="cp-gw-3.example.internal")
    app, job_store, runner = _make_app(runtime_paths)
    client = TestClient(app)
    body = {**VALID_BODY, "device_reference": "cp-gw-3.example.internal", "signal_id": "sig-cp-3"}

    resp = _post(client, body=body, nonce="n-trigger-1")

    assert resp.status_code == 200
    payload = resp.json()
    assert payload["status"] == "triggered"
    assert payload["device_id"] == device_id
    job_id = payload["job_id"]

    records = job_store.list_all()
    assert len(records) == 1
    assert records[0].job_id == job_id
    assert records[0].job_type == "config_refresh_cp"
    assert records[0].command_class == "read"
    assert records[0].state == "queued"
    assert records[0].targets == [device_id]
    assert runner.enqueued_job_ids == [job_id]


def test_repeated_signal_id_is_idempotent_not_a_second_job(runtime_paths, monkeypatch):
    device_id = _fully_resolved(runtime_paths.data_root, monkeypatch, endpoint="cp-gw-4.example.internal")
    app, job_store, runner = _make_app(runtime_paths)
    client = TestClient(app)
    body = {**VALID_BODY, "device_reference": "cp-gw-4.example.internal", "signal_id": "sig-cp-4"}

    first = _post(client, body=body, nonce="n-idem-1")
    second = _post(client, body=body, timestamp=int(time.time()) + 1, nonce="n-idem-2")

    assert first.status_code == 200 and second.status_code == 200
    assert first.json()["job_id"] == second.json()["job_id"]
    assert len(job_store.list_all()) == 1
    # Idempotent resubmission does not re-enqueue a job already durable.
    assert runner.enqueued_job_ids == [first.json()["job_id"]]


# ---------------------------------------------------------------------------
# AC-2: dedup/cooldown -- distinct from replay protection (fresh signal_id,
# fresh nonce, still throttled per (device_id, event_type))
# ---------------------------------------------------------------------------

def test_second_distinct_signal_within_cooldown_is_ignored(runtime_paths, monkeypatch):
    device_id = _fully_resolved(runtime_paths.data_root, monkeypatch, endpoint="cp-gw-5.example.internal")
    app, job_store, runner = _make_app(runtime_paths)
    client = TestClient(app)
    ts = int(time.time())

    first_body = {**VALID_BODY, "device_reference": "cp-gw-5.example.internal", "signal_id": "sig-cool-1"}
    first = _post(client, body=first_body, timestamp=ts, nonce="n-cool-1")
    assert first.status_code == 200
    assert first.json()["status"] == "triggered"

    second_body = {**VALID_BODY, "device_reference": "cp-gw-5.example.internal", "signal_id": "sig-cool-2"}
    second = _post(client, body=second_body, timestamp=ts + 5, nonce="n-cool-2")
    assert second.status_code == 200
    assert second.json()["status"] == "ignored_cooldown"

    # Exactly one job was ever created -- the second, distinct signal_id
    # never reached job_store.submit() at all.
    assert len(job_store.list_all()) == 1
    assert runner.enqueued_job_ids == [first.json()["job_id"]]


def test_cooldown_is_scoped_per_event_type(runtime_paths, monkeypatch):
    """A distinct event_type for the same device is a distinct cooldown
    bucket -- not coalesced with an unrelated event type."""
    device_id = _fully_resolved(runtime_paths.data_root, monkeypatch, endpoint="cp-gw-6.example.internal")
    app, job_store, runner = _make_app(runtime_paths)
    client = TestClient(app)
    ts = int(time.time())

    first_body = {
        "signal_id": "sig-evt-1", "event_type": "policy_install",
        "device_reference": "cp-gw-6.example.internal",
    }
    first = _post(client, body=first_body, timestamp=ts, nonce="n-evt-1")
    assert first.json()["status"] == "triggered"

    second_body = {
        "signal_id": "sig-evt-2", "event_type": "config_change",
        "device_reference": "cp-gw-6.example.internal",
    }
    second = _post(client, body=second_body, timestamp=ts + 5, nonce="n-evt-2")
    assert second.json()["status"] == "triggered"

    assert len(job_store.list_all()) == 2
