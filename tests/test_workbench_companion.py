"""Synthetic C2 checks: no live records, logs, service installation or clients."""

import copy
import json
import os
import socket
import stat
import subprocess
import threading

import pytest

from scripts import workbench_companion as companion


IDENTITY = "SYNTHETIC-0001"
ALLOWLISTS = {"provider": {"synthetic"}, "effort": {"low"},
              "requested_model": {"requested"}, "observed_model": {"observed"},
              "provenance": {"synthetic"}, "audit_exception": {"NONE"}}


def projection(cost_source="reported"):
    return {IDENTITY: {
        "record": {"status": "VALID", "phase": "running", "revision": 1,
                   "retry_count": 0, "start_time": 10, "external_participant": True,
                   "verification": False, "provider": "synthetic", "effort": "low"},
        "relay": {"status": "VALID", "relay_status": "OPEN", "next_actor": "engineer",
                  "sequence": 1, "marker": "SESSION_START", "event_time": 10, "outcome": None},
        "usage": {"status": "VALID", "input_tokens": 10, "output_tokens": 0,
                  "cache_read_tokens": 0, "cache_creation_tokens": 0, "turns": 1,
                  "cache_ratio": 0, "cost": 0.25, "cost_source": cost_source,
                  "event_time": 10, "requested_model": "requested", "observed_model": "observed",
                  "provenance": "synthetic", "audit_exception": "NONE",
                  "price_table_fingerprint": "0" * 64}}}


def observer(tmp_path, *, token="synthetic-set", ids=(IDENTITY,), name="private"):
    return companion.Observer(tmp_path / name, token, ids, source_roots=[tmp_path / "sources"],
                              allowlists=ALLOWLISTS)


def snapshot(tmp_path, name="private"):
    return json.loads((tmp_path / name / "snapshot.json").read_bytes())


def events(state):
    return [event["event"] for event in state["transitions"]]


def test_same_poll_preserves_evidence_age_totals_and_external_participant(tmp_path, monkeypatch):
    sources = tmp_path / "sources"
    sources.mkdir()
    source = sources / "synthetic.json"
    source.write_text(json.dumps(projection()))
    before = source.read_bytes()

    def forbidden(*args, **kwargs):
        raise AssertionError("forbidden observer operation")

    with observer(tmp_path) as worker:
        monkeypatch.setattr(subprocess, "Popen", forbidden)
        monkeypatch.setattr(socket, "socket", forbidden)
        monkeypatch.setattr(os, "system", forbidden)
        real_open = os.open

        def controlled_open(path, flags, *args, **kwargs):
            if flags & (os.O_WRONLY | os.O_RDWR | os.O_CREAT | os.O_TRUNC):
                assert kwargs.get("dir_fd") == worker.fd
            return real_open(path, flags, *args, **kwargs)

        monkeypatch.setattr(os, "open", controlled_open)
        scan = lambda: json.loads(source.read_bytes())
        assert worker.poll(scan, now=100) == "COMPLETE"
        assert worker.poll(scan, now=130) == "COMPLETE"
        state = snapshot(tmp_path)
        assert state["transitions"] == []
        observed = state["observations"][IDENTITY]
        assert observed["record"]["data"]["external_participant"] is True
        assert observed["usage"]["observed_at"] == 100
        assert observed["usage"]["checked_at"] == 130
        assert observed["usage"]["data"]["event_time"] == 10
        assert observed["usage"]["data"]["input_tokens"] == 10
        assert observed["usage"]["data"]["attempt_attribution"] == "UNKNOWN"
        assert "health" not in observed and "work_stage" not in observed
        assert source.read_bytes() == before
        assert stat.S_IMODE((tmp_path / "private").stat().st_mode) == 0o700
        assert stat.S_IMODE((tmp_path / "private" / "snapshot.json").stat().st_mode) == 0o600


@pytest.mark.parametrize("cost_source", sorted(companion.COST_SOURCES))
def test_cost_provenance_and_partial_counters(tmp_path, cost_source):
    inputs = projection(cost_source)
    with observer(tmp_path) as worker:
        worker.poll(lambda: inputs, now=100)
        usage = worker.state["observations"][IDENTITY]["usage"]["data"]
        assert usage["cost_source"] == cost_source
        assert usage["cost"] == (None if cost_source == "unavailable" else 0.25)
        assert usage["requested_model"] == "requested" and usage["observed_model"] == "observed"
        del inputs[IDENTITY]["usage"]["output_tokens"]
        assert worker.poll(lambda: inputs, now=130) == "INCOMPLETE"
        usage = worker.state["observations"][IDENTITY]["usage"]["data"]
        assert usage["output_tokens"] is None and usage["input_tokens"] == 10
        assert usage["cache_ratio"] is None
        assert usage["cost"] == (0.25 if cost_source == "reported" else None)
        assert usage["cost_source"] == cost_source


@pytest.mark.parametrize("quality", ["MISSING", "UNREADABLE", "MALFORMED", "INCONSISTENT", "LIMIT_EXCEEDED"])
def test_source_loss_recovery_keeps_last_good_age_and_gap(tmp_path, quality):
    inputs = projection()
    with observer(tmp_path) as worker:
        worker.poll(lambda: inputs, now=100)
        lost = copy.deepcopy(inputs)
        lost[IDENTITY]["usage"] = {"status": quality, "cost": 999}
        worker.poll(lambda: lost, now=130)
        state = worker.state
        usage = state["observations"][IDENTITY]["usage"]
        assert usage["status"] == quality and usage["observed_at"] == 100
        assert usage["data"]["cost"] == 0.25 and state["last_success"] == 100
        assert events(state) == ["SOURCE_UNAVAILABLE"]
        worker.poll(lambda: lost, now=160)
        assert events(worker.state) == ["SOURCE_UNAVAILABLE"]
        worker.poll(lambda: inputs, now=190)
        assert events(worker.state) == ["SOURCE_UNAVAILABLE", "SOURCE_RECOVERED"]
        assert all(e["gap"] for e in worker.state["transitions"])


def test_restart_generation_regression_and_close_are_observed_facts(tmp_path):
    inputs = projection()
    inputs[IDENTITY]["relay"]["sequence"] = 5
    with observer(tmp_path) as worker:
        worker.poll(lambda: inputs, now=100)
        generation = worker.state["observer_generation"]
    inputs[IDENTITY]["record"].update(retry_count=1, start_time=150, phase="failed")
    inputs[IDENTITY]["relay"].update(sequence=2, next_actor="po", marker="SESSION_CLOSE", outcome="DONE")
    with observer(tmp_path) as worker:
        assert worker.error is None
        assert worker.state["observer_generation"] != generation
        worker.poll(lambda: inputs, now=200)
        assert set(events(worker.state)) == {"RECONCILED", "RECORDED_FAILURE", "ATTENTION_REQUESTED", "CLOSE_OBSERVED"}
        assert all(e["gap"] for e in worker.state["transitions"])
        assert worker.state["observations"][IDENTITY]["usage"]["data"]["attempt_attribution"] == "UNKNOWN"
        assert "merged" not in json.dumps(worker.state).lower()
        assert worker.state["notification"]["delivery_attempt"] == "UNSUPPORTED"
        worker.poll(lambda: inputs, now=230)
        assert len(worker.state["transitions"]) == 4


def test_source_set_change_marks_reconciliation_without_replay(tmp_path):
    with observer(tmp_path) as worker:
        worker.poll(projection, now=100)
    with observer(tmp_path, token="another-set") as worker:
        worker.poll(projection, now=130)
        assert events(worker.state) == ["RECONCILED"]
        assert worker.state["transitions"][0]["gap"] is True


@pytest.mark.parametrize("payload,error", [(b"{broken", "STATE_INVALID"),
                                           (b'{"schema_version":2}', "SCHEMA_UNSUPPORTED"),
                                           (b'{"schema_version":true}', "SCHEMA_UNSUPPORTED"),
                                           (b'{"schema_version":1,"schema_version":1}', "STATE_INVALID"),
                                           (b'{"schema_version":1}', "STATE_INVALID")])
def test_bad_state_is_private_and_never_rewritten(tmp_path, payload, error):
    directory = tmp_path / "private"
    directory.mkdir(mode=0o700)
    path = directory / "snapshot.json"
    path.write_bytes(payload)
    path.chmod(0o600)
    with observer(tmp_path) as worker:
        assert worker.error == error
        assert worker.poll(lambda: pytest.fail("must not scan"), now=100) == error
        assert path.read_bytes() == payload


def test_partial_write_does_not_replace_or_advance_memory(tmp_path, monkeypatch):
    with observer(tmp_path) as worker:
        worker.poll(projection, now=100)
        before = (tmp_path / "private" / "snapshot.json").read_bytes()
        memory = copy.deepcopy(worker.state)
        original = os.fsync

        def fail_file(fd):
            if fd != worker.fd:
                raise OSError("synthetic failure")
            original(fd)

        monkeypatch.setattr(os, "fsync", fail_file)
        with pytest.raises(OSError):
            worker.poll(projection, now=130)
        assert (tmp_path / "private" / "snapshot.json").read_bytes() == before
        assert worker.state == memory
        assert not list((tmp_path / "private").glob(".snapshot-*"))


def test_readers_only_see_complete_snapshots_during_replacement(tmp_path):
    with observer(tmp_path) as worker:
        worker.poll(projection, now=100)
        stop = threading.Event()
        failures = []

        def reader():
            while not stop.is_set():
                try:
                    state = snapshot(tmp_path)
                    assert state["schema_version"] == 1
                    assert IDENTITY in state["observations"]
                except Exception as error:
                    failures.append(type(error).__name__)
                    stop.set()

        thread = threading.Thread(target=reader)
        thread.start()
        try:
            for now in range(130, 430, 30):
                worker.poll(projection, now=now)
        finally:
            stop.set()
            thread.join()
        assert failures == []


def test_clock_rollback_then_reconciliation_and_removed_sources(tmp_path):
    with observer(tmp_path) as worker:
        worker.poll(projection, now=100)
        assert worker.poll(projection, now=90) == "INCOMPLETE"
        assert worker.state["last_success"] is None
        assert worker.state["errors"] == ["CLOCK_ROLLBACK"]
        assert worker.poll(projection, now=120) == "COMPLETE"
        worker.poll(lambda: {}, now=150)
        assert IDENTITY in worker.state["observations"]
        assert all(worker.state["observations"][IDENTITY][s]["status"] == "MISSING" for s in companion.SOURCES)
        assert "RECORDED_FAILURE" not in events(worker.state)


def test_limits_retention_and_no_active_eviction(tmp_path, monkeypatch):
    with observer(tmp_path, ids=(IDENTITY, "SYNTHETIC-0002")) as worker:
        inputs = projection()
        inputs["SYNTHETIC-0002"] = copy.deepcopy(inputs[IDENTITY])
        monkeypatch.setattr(companion, "MAX_MOVEMENTS", 1)
        worker.poll(lambda: inputs, now=100)
        assert "LIMIT_EXCEEDED" in worker.state["errors"]
        assert len(worker.state["observations"]) == 1
    monkeypatch.setattr(companion, "MAX_MOVEMENTS", 1000)
    with observer(tmp_path, name="retention") as worker:
        worker.poll(projection, now=100)
        inputs = projection()
        inputs[IDENTITY]["relay"].update(next_actor="po", marker="RELAY_QUESTION", sequence=2)
        worker.poll(lambda: inputs, now=130)
        inputs[IDENTITY]["relay"]["sequence"] = 3
        monkeypatch.setattr(companion, "MAX_TRANSITIONS", 1)
        worker.poll(lambda: inputs, now=160)
        assert len(worker.state["transitions"]) == 1 and worker.state["dropped_count"] == 1
        assert worker.state["history_floor"] == 2
        worker.poll(lambda: inputs, now=160 + companion.RETENTION + 1)
        assert worker.state["transitions"] == [] and worker.state["dropped_count"] == 2
        assert IDENTITY in worker.state["observations"]


def test_size_cap_preserves_prior_observations(tmp_path, monkeypatch):
    with observer(tmp_path) as worker:
        worker.poll(projection, now=100)
        old = copy.deepcopy(worker.state["observations"])
        # Reserve exists for bounded degradation even when admission cannot fit.
        monkeypatch.setattr(companion, "MAX_BYTES", len(companion._json(worker.state)) - 1 + 4096)
        inputs = projection()
        inputs[IDENTITY]["record"]["phase"] = "verifying"
        worker.poll(lambda: inputs, now=130)
        assert "LIMIT_EXCEEDED" in worker.state["errors"]
        assert worker.state["observations"] == old
        assert worker.state["last_success"] == 100


@pytest.mark.parametrize("value", [-1, True, float("inf"), float("nan"), "10", 10**1000])
def test_malformed_counters_and_future_evidence_are_not_retained(tmp_path, value):
    with observer(tmp_path) as worker:
        worker.poll(projection, now=100)
        inputs = projection()
        inputs[IDENTITY]["usage"]["input_tokens"] = value
        worker.poll(lambda: inputs, now=130)
        assert worker.state["observations"][IDENTITY]["usage"]["status"] == "MALFORMED"
        inputs = projection()
        inputs[IDENTITY]["usage"]["event_time"] = 1000
        worker.poll(lambda: inputs, now=160)
        assert worker.state["observations"][IDENTITY]["usage"]["status"] == "INCONSISTENT"


def test_arbitrary_text_is_redacted_and_snapshot_validation_rejects_tampering(tmp_path):
    inputs = projection()
    sentinel = "arbitrary source text / must never persist"
    inputs[IDENTITY]["record"].update(objective=sentinel, provider=sentinel)
    inputs[IDENTITY]["relay"].update(body=sentinel, path=sentinel)
    with observer(tmp_path) as worker:
        worker.poll(lambda: inputs, now=100)
        assert sentinel not in json.dumps(worker.state)
        assert worker.state["observations"][IDENTITY]["record"]["data"]["provider"] is None
        assert worker.state["observations"][IDENTITY]["record"]["data"]["classification"] == "UNRECOGNIZED"
    with observer(tmp_path) as worker:
        assert worker.error is None
    state = snapshot(tmp_path)
    state["observations"][IDENTITY]["record"]["data"]["extra"] = sentinel
    (tmp_path / "private" / "snapshot.json").write_text(json.dumps(state))
    with observer(tmp_path) as worker:
        assert worker.error == "STATE_INVALID"


def test_symlinks_hardlinks_permissions_and_duplicate_writer_rejected(tmp_path):
    with observer(tmp_path):
        with pytest.raises(BlockingIOError):
            observer(tmp_path)
    target = tmp_path / "target"
    target.mkdir(mode=0o700)
    (tmp_path / "linked").symlink_to(target, target_is_directory=True)
    with pytest.raises(ValueError):
        observer(tmp_path, name="linked")
    (tmp_path / "private" / "snapshot.json").symlink_to(tmp_path / "absent")
    with observer(tmp_path) as worker:
        assert worker.error == "STATE_INVALID"
    (tmp_path / "private" / "snapshot.json").unlink()
    target_file = tmp_path / "file"
    target_file.write_text("{}")
    target_file.chmod(0o600)
    os.link(target_file, tmp_path / "private" / "snapshot.json")
    with observer(tmp_path) as worker:
        assert worker.error == "STATE_INVALID"
    (tmp_path / "private").chmod(0o755)
    with pytest.raises(ValueError, match="STATE_PERMISSIONS"):
        observer(tmp_path)
    with pytest.raises(ValueError, match="INVALID_STATE_LOCATION"):
        companion.Observer(tmp_path / "sources" / "private", "synthetic", [IDENTITY],
                           source_roots=[tmp_path / "sources"])


def test_overlapping_scans_and_foreground_schedule_skip_missed_ticks(tmp_path, monkeypatch):
    with observer(tmp_path) as worker:
        worker.busy.acquire()
        assert worker.poll(lambda: pytest.fail("overlap"), now=100) == "SCAN_BUSY"
        worker.busy.release()
        clock = iter([0, 65])
        monkeypatch.setattr(companion.time, "monotonic", lambda: next(clock))

        class Stop:
            stopped = False
            waits = []

            def is_set(self):
                return self.stopped

            def wait(self, seconds):
                self.waits.append(seconds)
                self.stopped = True

        stop = Stop()
        worker.run(projection, stop)
        assert stop.waits == [25]


def test_scan_error_redaction_and_unknown_identity(tmp_path):
    with observer(tmp_path) as worker:
        worker.poll(projection, now=100)

        def failed():
            raise RuntimeError("arbitrary private source content")

        worker.poll(failed, now=130)
        assert "MALFORMED" in worker.state["errors"]
        assert "arbitrary" not in json.dumps(worker.state)
        worker.poll(lambda: {"unapproved": {}}, now=160)
        assert "unapproved" not in json.dumps(worker.state)
