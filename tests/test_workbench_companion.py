"""Synthetic C2/C3 checks: no live records, logs, installation or pinned clients."""

import copy
import builtins
from datetime import datetime, timedelta
import hashlib
import io
import json
import os
import socket
import stat
import subprocess
import sys
import threading
import types
from pathlib import Path

import pytest

from scripts import workbench_companion as companion
from scripts import workbench_companion_mcp as mcp
from scripts import workbench_companion_launchagent as launchagent


PLUGIN_ROOT = Path(__file__).parents[1] / "plugins" / "nexus-workbench-companion"


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


def test_current_coverage_discovers_records_without_historical_gaps(tmp_path):
    historical = tuple(f"SYNTHETIC-{index:04d}" for index in range(1, 229))
    inputs = {}
    for identity in historical:
        inputs[identity] = copy.deepcopy(projection()[IDENTITY])
        inputs[identity]["record"] = {"status": "MISSING"}
        inputs[identity]["usage"] = {"status": "MISSING"}
    inputs[historical[-1]]["record"] = projection()[IDENTITY]["record"]
    inputs[historical[-1]]["usage"] = copy.deepcopy(projection()[IDENTITY]["usage"])
    del inputs[historical[-1]]["usage"]["output_tokens"]

    with observer(tmp_path, ids=()) as worker:
        assert worker.poll(lambda: inputs, now=100) == "INCOMPLETE"
        assert worker.state["last_success"] is None
        assert len(worker.state["observations"]) == 228

    for identity in historical:
        inputs[identity]["record"] = {"status": "MISSING"}
    current = "NXS-LOCAL-9999"
    inputs[current] = copy.deepcopy(projection()[IDENTITY])
    with observer(tmp_path, ids=()) as worker:
        assert worker.poll(lambda: inputs, now=130) == "COMPLETE"
        assert worker.state["last_success"] == 130
        assert current in worker.state["observations"]
        assert worker.state["observations"][historical[0]]["record"]["status"] == "MISSING"
        assert worker.state["observations"][historical[0]]["relay"]["status"] == "VALID"
    reader = mcp.Reader(tmp_path / "private", allowlists=ALLOWLISTS)
    assert decoded(reader.call(mcp.TOOLS[0], {}, now=130))["coverage"] == "COMPLETE"
    visible = decoded(reader.call(mcp.TOOLS[1], {}, now=130))["items"]
    old = next(item for item in visible if item["movement_id"] == historical[0])
    assert old["record"]["status"] == "MISSING" and old["relay"]["status"] == "VALID"


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


def decoded(result):
    return json.loads(result["content"][0]["text"])


def rpc(server, method, params=None, identity=1):
    message = {"jsonrpc": "2.0", "id": identity, "method": method}
    if params is not None:
        message["params"] = params
    return server.handle(companion._json(message))


def initialized_server(reader):
    server = mcp.Server(reader)
    response = rpc(server, "initialize", {"protocolVersion": mcp.PROTOCOL,
                   "capabilities": {}, "clientInfo": {"name": "synthetic", "version": "1"}})
    assert response["result"]["capabilities"] == {"tools": {}}
    assert response["result"]["protocolVersion"] == mcp.PROTOCOL
    assert server.handle(b'{"jsonrpc":"2.0","method":"notifications/initialized"}') is None
    return server


def test_mcp_initialization_closed_registry_and_protocol_errors(tmp_path, monkeypatch):
    reader = mcp.Reader(tmp_path / "absent")
    monkeypatch.setattr(reader, "_read", lambda: pytest.fail("protocol must not read state"))
    assert rpc(mcp.Server(reader), "tools/list")["error"]["message"] == "NOT_INITIALIZED"
    server = initialized_server(reader)
    definitions = rpc(server, "tools/list")["result"]["tools"]
    assert [tool["name"] for tool in definitions] == list(mcp.TOOLS)
    assert all(tool["inputSchema"]["additionalProperties"] is False for tool in definitions)
    assert all(tool["annotations"]["readOnlyHint"] for tool in definitions)
    for method in ("resources/read", "resources/list", "prompts/list", "sampling/createMessage",
                   "elicitation/create", "refresh", "shutdown", "shell"):
        assert rpc(server, method)["error"]["message"] == "METHOD_NOT_FOUND"
    assert rpc(server, "ping")["result"] == {}
    assert rpc(server, "ping", {"extra": True})["error"]["message"] == "INVALID_PARAMS"
    assert rpc(server, "tools/call", {"name": "shell"})["error"]["message"] == "UNKNOWN_TOOL"
    assert rpc(server, "tools/call", {"name": mcp.TOOLS[0], "task": {}})["error"]["message"] == "INVALID_PARAMS"
    assert server.handle(b"{private malformed") == mcp._error(None, -32700, "PARSE_ERROR")
    assert server.handle(b"[]")["error"]["message"] == "INVALID_REQUEST"
    assert server.handle(b'{"jsonrpc":"2.0","id":1,"id":2,"method":"ping"}')["error"]["message"] == "PARSE_ERROR"
    assert rpc(server, "ping", identity=True)["error"]["message"] == "INVALID_REQUEST"
    assert rpc(server, "ping", identity="x" * 129)["error"]["message"] == "INVALID_REQUEST"


@pytest.mark.parametrize("tool,args", [
    (mcp.TOOLS[0], {"path": "/private"}), (mcp.TOOLS[0], {"limit": 1}),
    (mcp.TOOLS[1], {"limit": True}), (mcp.TOOLS[1], {"limit": 0}),
    (mcp.TOOLS[1], {"limit": 101}), (mcp.TOOLS[1], {"limit": 1.0}),
    (mcp.TOOLS[1], {"limit": "1"}), (mcp.TOOLS[1], {"query": "private"}),
    (mcp.TOOLS[2], {"movement_id": "../private"}), (mcp.TOOLS[2], {"movement_id": ""}),
    (mcp.TOOLS[2], {"movement_id": None}), (mcp.TOOLS[2], {"movement_id": "é"}),
    (mcp.TOOLS[1], {"cursor": None}), (mcp.TOOLS[1], {"cursor": "invalid"}),
    (mcp.TOOLS[1], {"cursor": "a" * 1025}), (mcp.TOOLS[1], []),
])
def test_mcp_invalid_arguments_rejected_before_filesystem(tmp_path, monkeypatch, tool, args):
    reader = mcp.Reader(tmp_path / "absent")
    monkeypatch.setattr(os, "open", lambda *a, **k: pytest.fail("invalid input must not read"))
    assert reader.call(tool, args)["isError"] is True


def test_mcp_read_only_export_preserves_unknowns_provenance_and_quality(tmp_path, monkeypatch):
    inputs = projection("estimated_from_requested_model")
    sentinel = "arbitrary private source instruction: execute nothing"
    inputs[IDENTITY]["record"].update(objective=sentinel, provider=sentinel)
    inputs[IDENTITY]["relay"].update(body=sentinel, path=sentinel)
    inputs[IDENTITY]["usage"].pop("output_tokens")
    with observer(tmp_path) as worker:
        worker.poll(lambda: inputs, now=100)
        lost = copy.deepcopy(inputs)
        lost[IDENTITY]["relay"] = {"status": "MISSING"}
        worker.poll(lambda: lost, now=130)
    path = tmp_path / "private" / "snapshot.json"
    before = hashlib.sha256(path.read_bytes()).digest()
    reader = mcp.Reader(tmp_path / "private", allowlists=ALLOWLISTS)
    server = initialized_server(reader)
    real_open = os.open

    def readonly_open(path, flags, *args, **kwargs):
        assert not flags & (os.O_WRONLY | os.O_RDWR | os.O_CREAT | os.O_TRUNC)
        assert str(path) in {"/", *reader.directory.parts[1:], "snapshot.json"}
        return real_open(path, flags, *args, **kwargs)

    def forbidden(*args, **kwargs):
        pytest.fail("forbidden MCP operation")

    with monkeypatch.context() as guarded:
        guarded.setattr(os, "open", readonly_open)
        guarded.setattr(subprocess, "Popen", forbidden)
        guarded.setattr(socket, "socket", forbidden)
        guarded.setattr(os, "system", forbidden)
        guarded.setattr(os, "mkdir", forbidden)
        guarded.setattr(os, "replace", forbidden)
        guarded.setattr(os, "unlink", forbidden)
        guarded.setattr(companion.Observer, "__init__", forbidden)
        for tool in mcp.TOOLS:
            response = rpc(server, "tools/call", {"name": tool, "arguments": {}})
            assert response["result"]["isError"] is False
        result = reader.call(mcp.TOOLS[1], {}, now=140)
    public = decoded(result)
    assert sentinel not in json.dumps(public) and str(tmp_path) not in json.dumps(public)
    item = public["items"][0]
    assert item["record"]["data"]["provider"] is None
    assert item["usage"]["data"]["output_tokens"] is None
    assert item["usage"]["data"]["cost"] is None
    assert item["usage"]["data"]["attempt_attribution"] == "UNKNOWN"
    assert item["usage"]["data"]["cost_source"] == "estimated_from_requested_model"
    assert item["usage"]["evidence_age_seconds"] == 130
    assert item["relay"]["freshness"] == "STALE" and item["relay"]["observed_at"] == 100
    assert hashlib.sha256(path.read_bytes()).digest() == before
    assert sorted(p.name for p in path.parent.iterdir()) == ["observer.lock", "snapshot.json"]


@pytest.mark.parametrize("cost_source", sorted(companion.COST_SOURCES))
def test_mcp_preserves_each_cost_source_and_requested_observed_models(tmp_path, cost_source):
    with observer(tmp_path) as worker:
        worker.poll(lambda: projection(cost_source), now=100)
    reader = mcp.Reader(tmp_path / "private", allowlists=ALLOWLISTS)
    usage = decoded(reader.call(mcp.TOOLS[1], {}, now=100))["items"][0]["usage"]["data"]
    assert usage["cost_source"] == cost_source
    assert usage["cost"] == (None if cost_source == "unavailable" else 0.25)
    assert usage["requested_model"] == "requested" and usage["observed_model"] == "observed"


@pytest.mark.parametrize("now,freshness", [(100, "FRESH"), (190, "FRESH"), (191, "STALE"), (90, "UNKNOWN")])
def test_mcp_freshness_uses_completed_scan_not_client_read(tmp_path, now, freshness):
    with observer(tmp_path) as worker:
        worker.poll(projection, now=100)
    reader = mcp.Reader(tmp_path / "private", allowlists=ALLOWLISTS)
    assert decoded(reader.call(mcp.TOOLS[0], {}, now=now))["freshness"] == freshness


def test_mcp_recent_check_does_not_refresh_stale_usage(tmp_path):
    with observer(tmp_path) as worker:
        worker.poll(projection, now=100)
        worker.poll(projection, now=130)
    reader = mcp.Reader(tmp_path / "private", allowlists=ALLOWLISTS)
    page = decoded(reader.call(mcp.TOOLS[1], {}, now=140))
    assert page["freshness"] == "FRESH"
    assert page["items"][0]["relay"]["freshness"] == "FRESH"
    assert page["items"][0]["usage"]["freshness"] == "STALE"
    assert page["items"][0]["usage"]["evidence_age_seconds"] == 130


@pytest.mark.parametrize("payload,error", [(b"{private", "STATE_INVALID"),
    (b'{"schema_version":2}', "SCHEMA_UNSUPPORTED"),
    (b'{"schema_version":true}', "SCHEMA_UNSUPPORTED"),
    (b'{"schema_version":1}', "STATE_INVALID"),
    (b'{"schema_version":1,"schema_version":1}', "STATE_INVALID")])
def test_mcp_bad_state_redaction_no_writes(tmp_path, payload, error):
    directory = tmp_path / "private"
    directory.mkdir(mode=0o700)
    path = directory / "snapshot.json"
    path.write_bytes(payload)
    path.chmod(0o600)
    result = mcp.Reader(directory).call(mcp.TOOLS[0], {})
    assert decoded(result) == {"error": error, "freshness": "UNKNOWN"}
    assert path.read_bytes() == payload


def test_mcp_snapshot_tampering_and_size_limits_are_fail_closed(tmp_path, monkeypatch):
    with observer(tmp_path) as worker:
        worker.poll(projection, now=100)
    reader = mcp.Reader(tmp_path / "private", allowlists=ALLOWLISTS)
    path = tmp_path / "private" / "snapshot.json"
    state = snapshot(tmp_path)
    state["observations"][IDENTITY]["record"]["data"]["extra"] = "private instruction sentinel"
    path.write_bytes(companion._json(state))
    assert decoded(reader.call(mcp.TOOLS[1], {})) == {"error": "STATE_INVALID", "freshness": "UNKNOWN"}
    monkeypatch.setattr(companion, "MAX_BYTES", 16)
    assert decoded(reader.call(mcp.TOOLS[0], {}))["error"] == "STATE_INVALID"


def test_mcp_missing_permissions_links_and_nonregular_state(tmp_path):
    directory = tmp_path / "private"
    reader = mcp.Reader(directory)
    assert decoded(reader.call(mcp.TOOLS[0], {}))["freshness"] == "UNAVAILABLE"
    assert not directory.exists()
    with observer(tmp_path) as worker:
        worker.poll(projection, now=100)
    path = directory / "snapshot.json"
    path.chmod(0o644)
    assert decoded(reader.call(mcp.TOOLS[0], {}))["error"] == "STATE_INVALID"
    path.chmod(0o600)
    link = tmp_path / "link"
    link.symlink_to(directory, target_is_directory=True)
    assert decoded(mcp.Reader(link).call(mcp.TOOLS[0], {}))["error"] == "STATE_INVALID"
    path.rename(directory / "saved.json")
    path.symlink_to(directory / "saved.json")
    assert decoded(reader.call(mcp.TOOLS[0], {}))["error"] == "STATE_INVALID"
    path.unlink()
    os.link(directory / "saved.json", path)
    assert decoded(reader.call(mcp.TOOLS[0], {}))["error"] == "STATE_INVALID"
    path.unlink()
    os.mkfifo(path, mode=0o600)
    assert decoded(reader.call(mcp.TOOLS[0], {}))["error"] == "STATE_INVALID"


def test_mcp_pagination_exact_filter_generation_and_cursor_binding(tmp_path, monkeypatch):
    other = "SYNTHETIC-0002"
    inputs = projection()
    inputs[other] = copy.deepcopy(inputs[IDENTITY])
    with observer(tmp_path, ids=(IDENTITY, other)) as worker:
        worker.poll(lambda: inputs, now=100)
        for identity in inputs:
            inputs[identity]["relay"].update(next_actor="po", sequence=2)
        worker.poll(lambda: inputs, now=130)
        reader = mcp.Reader(tmp_path / "private", allowlists=ALLOWLISTS)
        first = decoded(reader.call(mcp.TOOLS[1], {"limit": 1}, now=130))
        assert first["items"][0]["movement_id"] == IDENTITY
        cursor = first["next_cursor"]
        second = decoded(reader.call(mcp.TOOLS[1], {"limit": 1, "cursor": cursor}, now=130))
        assert second["items"][0]["movement_id"] == other and second["next_cursor"] is None
        history = decoded(reader.call(mcp.TOOLS[2], {"movement_id": IDENTITY, "limit": 1}, now=130))
        assert len(history["items"]) == 1 and history["items"][0]["movement_id"] == IDENTITY
        assert decoded(reader.call(mcp.TOOLS[2], {"movement_id": "SYNTHETIC-000"}, now=130))["items"] == []
        with monkeypatch.context() as guarded:
            guarded.setattr(reader, "_read", lambda: pytest.fail("invalid cursor must not read"))
            assert decoded(reader.call(mcp.TOOLS[2], {"cursor": cursor}))["error"] == "INVALID_CURSOR"
            assert decoded(reader.call(mcp.TOOLS[1], {"cursor": cursor[:-2] + "xx"}))["error"] == "INVALID_CURSOR"
        worker.poll(lambda: inputs, now=160)
        assert decoded(reader.call(mcp.TOOLS[1], {"cursor": cursor}))["error"] == "CURSOR_STALE"


def test_mcp_wire_output_paginates_and_bounds_unfittable_item(tmp_path, monkeypatch):
    ids = [f"SYNTHETIC-{i:04d}" for i in range(100)]
    inputs = {identity: copy.deepcopy(projection()[IDENTITY]) for identity in ids}
    with observer(tmp_path, ids=ids) as worker:
        worker.poll(lambda: inputs, now=100)
    reader = mcp.Reader(tmp_path / "private", allowlists=ALLOWLISTS)
    server = initialized_server(reader)
    collected = []
    args = {"limit": 100}
    while True:
        response = rpc(server, "tools/call", {"name": mcp.TOOLS[1], "arguments": args}, identity="\U0010ffff" * 128)
        assert len(companion._json(response)) + 1 <= mcp.MAX_OUTPUT
        page = decoded(response["result"])
        assert page["items"]
        collected.extend(i["movement_id"] for i in page["items"])
        if page["next_cursor"] is None:
            break
        args["cursor"] = page["next_cursor"]
    assert collected == ids
    monkeypatch.setattr(mcp, "MAX_OUTPUT", 1024)
    assert decoded(reader.call(mcp.TOOLS[1], {}))["error"] == "ITEM_TOO_LARGE"


def test_mcp_stdio_oversize_drain_eof_and_real_process(tmp_path):
    reader = mcp.Reader(tmp_path / "absent")
    server = initialized_server(reader)
    incoming = io.BytesIO(b"x" * (mcp.MAX_REQUEST * 3) + b'\n{"jsonrpc":"2.0","id":1,"method":"ping"}\n')
    outgoing = io.BytesIO()
    server.serve(incoming, outgoing)
    responses = [json.loads(line) for line in outgoing.getvalue().splitlines()]
    assert responses[0]["error"]["message"] == "REQUEST_TOO_LARGE"
    assert responses[1]["result"] == {}
    assert server.handle(b"x" * (mcp.MAX_REQUEST + 1))["error"]["message"] == "REQUEST_TOO_LARGE"
    messages = [
        {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": mcp.PROTOCOL, "capabilities": {},
            "clientInfo": {"name": "synthetic", "version": "1"}}},
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
        {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": mcp.TOOLS[0]}}]
    process = subprocess.run(["python3", "-m", "scripts.workbench_companion_mcp",
                              "--snapshot-directory", str(tmp_path / "absent")],
                             input=b"\n".join(companion._json(m) for m in messages) + b"\n",
                             capture_output=True, timeout=10, check=True)
    responses = [json.loads(line) for line in process.stdout.splitlines()]
    assert [r["id"] for r in responses] == [1, 2, 3]
    assert decoded(responses[2]["result"])["error"] == "STATE_UNAVAILABLE"
    assert process.stderr == b"" and not (tmp_path / "absent").exists()


def test_mcp_concurrent_atomic_readers_and_source_hashes_unchanged(tmp_path):
    sources = tmp_path / "sources"
    sources.mkdir()
    source = sources / "synthetic.json"
    source.write_bytes(companion._json(projection()))
    before = hashlib.sha256(source.read_bytes()).digest()
    with observer(tmp_path) as worker:
        worker.poll(projection, now=100)
        stop = threading.Event()
        failures = []
        started = threading.Barrier(3)
        reads = []

        def read():
            reader = mcp.Reader(tmp_path / "private", allowlists=ALLOWLISTS)
            try:
                started.wait(timeout=10)
                while not stop.is_set():
                    result = reader.call(mcp.TOOLS[1], {}, now=1000)
                    assert not result["isError"]
                    assert decoded(result)["items"][0]["movement_id"] == IDENTITY
                    reads.append(True)
            except BaseException as error:
                failures.append(type(error).__name__)

        threads = [threading.Thread(target=read) for _ in range(2)]
        for thread in threads:
            thread.start()
        started.wait(timeout=10)
        try:
            for now in range(130, 430, 30):
                worker.poll(projection, now=now)
        finally:
            stop.set()
            for thread in threads:
                thread.join(timeout=10)
        assert reads and failures == [] and all(not t.is_alive() for t in threads)
    assert hashlib.sha256(source.read_bytes()).digest() == before


def test_private_codex_plugin_has_only_the_c3_read_only_mapping_and_finite_skill():
    manifest = json.loads((PLUGIN_ROOT / ".codex-plugin" / "plugin.json").read_text())
    mapping = json.loads((PLUGIN_ROOT / ".mcp.json").read_text())
    skill = (PLUGIN_ROOT / "skills" / "workbench-inspect" / "SKILL.md").read_text()

    assert manifest["name"] == "nexus-workbench-companion"
    assert manifest["skills"] == "./skills/" and manifest["mcpServers"] == "./.mcp.json"
    assert set(mapping) == {"mcpServers"} and set(mapping["mcpServers"]) == {"nexus-workbench-companion"}
    server = mapping["mcpServers"]["nexus-workbench-companion"]
    assert server == {
        "command": "${NEXUS_WORKBENCH_RELEASE_DIRECTORY}/bin/nexus-workbench-observer",
        "args": ["mcp", "--snapshot-directory",
        "${NEXUS_WORKBENCH_SNAPSHOT_DIRECTORY}"]}
    assert all(tool in skill for tool in mcp.TOOLS)
    assert "at most three tool calls" in skill and "Do not retry, paginate" in skill


def test_release_wrapper_and_launchagent_use_one_importable_entrypoint(tmp_path):
    root = Path(__file__).parents[1]
    wrapper = root / "bin" / "nexus-workbench-observer"
    result = subprocess.run([wrapper, "--help"], cwd=tmp_path, capture_output=True,
                            text=True, timeout=10)
    assert result.returncode == 0 and "--configuration" in result.stdout
    assert stat.S_IMODE(wrapper.stat().st_mode) == 0o755

    home = tmp_path / "home"
    release = home / "releases" / "v1"
    configuration = home / "config" / "companion.json"
    snapshot_directory = home / "state" / "companion"
    plan = launchagent.artifacts(home, release, configuration, snapshot_directory, uid=501)
    assert plan["plist"]["ProgramArguments"] == [
        str(release / "bin" / "nexus-workbench-observer"),
        "--configuration", str(configuration)]


def test_release_wrapper_dispatches_read_only_mcp_from_any_directory(tmp_path):
    wrapper = Path(__file__).parents[1] / "bin" / "nexus-workbench-observer"
    messages = [
        {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": mcp.PROTOCOL, "capabilities": {},
            "clientInfo": {"name": "synthetic", "version": "1"}}},
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
    ]
    result = subprocess.run(
        [wrapper, "mcp", "--snapshot-directory", str(tmp_path / "absent")],
        cwd=tmp_path, input=b"\n".join(companion._json(item) for item in messages) + b"\n",
        capture_output=True, timeout=10, check=True)
    responses = [json.loads(line) for line in result.stdout.splitlines()]
    assert [item["id"] for item in responses] == [1, 2]
    assert [tool["name"] for tool in responses[1]["result"]["tools"]] == list(mcp.TOOLS)
    assert result.stderr == b"" and not (tmp_path / "absent").exists()


def test_one_configuration_drives_c1_to_c2_without_losing_unknowns(sources, tmp_path):
    source, record, cache, _ = sources
    record["provider"] = SENTINEL
    cache["model"] = SENTINEL
    write(source.state_root / (MID + ".json"), record)
    write(source.state_root / "usage" / (MID + ".json"), cache)
    configuration = tmp_path / "companion.json"
    payload = {
        "schema_version": 1,
        "snapshot_directory": str(tmp_path / "private"),
        "source_set_token": "synthetic-set",
        "movement_ids": [MID],
        "state_root": str(source.state_root),
        "relay_root": str(source.relay_root),
        "price_root": str(source.price_root),
        "price_file": source.price_file,
        "providers": sorted(source.providers),
        "models": sorted(source.models),
        "efforts": sorted(source.efforts),
        "audit_exceptions": sorted(source.audit_exceptions),
        "subscription_providers": sorted(source.subscription_providers),
    }
    write(configuration, payload)
    configuration.chmod(0o600)
    runtime = companion.load_configuration(configuration)
    projected = companion.observer_projection(companion.scan_sources(runtime.sources),
                                              runtime.movement_ids)
    assert projected[MID]["record"]["provider"] == "UNRECOGNIZED"
    assert projected[MID]["usage"]["observed_model"] == "UNRECOGNIZED"
    assert projected[MID]["usage"]["requested_model"] == "model-a"
    assert projected[MID]["usage"]["cost_source"] == "unavailable"
    allowlists = {
        "provider": runtime.sources.providers, "effort": runtime.sources.efforts,
        "requested_model": runtime.sources.models, "observed_model": runtime.sources.models,
        "provenance": {companion.PROVENANCE},
        "audit_exception": runtime.sources.audit_exceptions,
    }
    with companion.Observer(runtime.snapshot_directory, runtime.source_set_token,
                            runtime.movement_ids,
                            source_roots=(runtime.sources.state_root, runtime.sources.relay_root,
                                          runtime.sources.price_root),
                            allowlists=allowlists) as worker:
        coverage = worker.poll(lambda: projected,
                               now=datetime.fromisoformat(TIME.replace("Z", "+00:00")).timestamp())
        assert coverage == "COMPLETE", worker.state["errors"]
        stored = worker.state["observations"][MID]
        assert stored["record"]["data"]["classification"] == "UNRECOGNIZED"
        assert stored["usage"]["data"]["attempt_attribution"] == "UNKNOWN"


def test_configuration_is_private_bounded_and_exact(sources, tmp_path):
    source = sources[0]
    path = tmp_path / "configuration.json"
    payload = {
        "schema_version": 1, "snapshot_directory": str(tmp_path / "private"),
        "source_set_token": "synthetic", "movement_ids": [MID],
        "state_root": str(source.state_root), "relay_root": str(source.relay_root),
        "price_root": str(source.price_root), "price_file": source.price_file,
        "providers": sorted(source.providers), "models": sorted(source.models),
        "efforts": sorted(source.efforts),
        "audit_exceptions": sorted(source.audit_exceptions),
        "subscription_providers": sorted(source.subscription_providers),
    }
    write(path, payload)
    with pytest.raises(ValueError, match="^INVALID_CONFIGURATION$"):
        companion.load_configuration(path)
    path.chmod(0o600)
    payload["schema_version"] = True
    write(path, payload)
    with pytest.raises(ValueError, match="^INVALID_CONFIGURATION$"):
        companion.load_configuration(path)
    payload["schema_version"] = 1
    payload["extra"] = True
    write(path, payload)
    with pytest.raises(ValueError, match="^INVALID_CONFIGURATION$"):
        companion.load_configuration(path)

MID = "NXS-LOCAL-0001"
TIME = "2026-09-15T12:00:00Z"
SENTINEL = "UNTRUSTED_BODY_DO_NOT_EXPORT"


def write(path, value):
    path.write_text(json.dumps(value))


def start_report():
    return {
        "baseline": {"source": "synthetic"}, "objective": SENTINEL, "scope": {"in": ["synthetic"], "out": ["network"]},
        "movement_type": "IMPLEMENTATION", "requirements": ["synthetic"],
        "acceptance_criteria": ["synthetic"], "validation_plan": [{"name": "synthetic", "argv": ["true"]}],
        "invariants": ["read only"], "risks": [], "context_not_loaded": [],
        "recommended_reasoning": {"tier": "ordinary", "reason": "synthetic"},
        "git": {"lane": "synthetic", "base": "origin/main"}, "merge_gate": "PO",
        "deployment_direction": "local validation only", "output_contract": ["synthetic"],
    }


def relay_value():
    return {
        "schema_version": 1, "id": MID, "movement": MID, "refs": [SENTINEL],
        "status": "OPEN", "next_actor": "engineer", "created_at": TIME, "updated_at": TIME,
        "entries": [{"actor": "po", "marker": "SESSION_START", "seq": 1,
                     "timestamp": TIME, "report": start_report()}],
    }


@pytest.fixture
def sources(tmp_path):
    root = tmp_path.resolve()
    for name in ("state", "relay", "prices"):
        (root / name).mkdir()
    (root / "state" / "usage").mkdir()
    config = companion.SourceConfig(root / "state", root / "relay", root / "prices", "prices.json",
                                    frozenset({"provider-a"}), frozenset({"model-a", "model-b"}),
                                    frozenset({"ordinary"}), frozenset({"provider_default_used"}),
                                    frozenset({"provider-a"}))
    record = {"movement_id": MID, "phase": "running", "revision": 1, "retry_count": 0,
              "started_at": TIME, "provider": "provider-a", "model_requested": "model-a",
              "effort_requested": "ordinary", "verify": {"passed": True}, "external_participant": True,
              "objective": SENTINEL, "worktree_path": SENTINEL, "pid": 999999}
    cache = {field: index for index, field in enumerate(companion.usage.USAGE_FIELDS, 1)}
    cache.update(provider="provider-a", turns=1, model="model-a", result_usage=None,
                 result_cost_usd=None, last_event_at=TIME, seen_message_ids=[SENTINEL], byte_offset=50)
    prices = {"model-a": dict(zip(companion.RATES, (2, 3, 1, 4))), "_comment": SENTINEL}
    write(config.state_root / (MID + ".json"), record)
    write(config.state_root / "usage" / (MID + ".json"), cache)
    write(config.relay_root / (MID + "-synthetic.json"), relay_value())
    write(config.price_root / config.price_file, prices)
    return config, record, cache, prices


def observation(sources):
    result = companion.scan_sources(sources[0], observed_at=datetime.fromisoformat(TIME.replace("Z", "+00:00")))
    return result, result["movements"][0]


def update_cache(sources, cache):
    write(sources[0].state_root / "usage" / (MID + ".json"), cache)


def test_projection_reuses_pure_helpers_and_keeps_source_bytes(sources, monkeypatch):
    config, record, cache, prices = sources
    paths = [p for root in (config.state_root, config.relay_root, config.price_root) for p in root.rglob("*") if p.is_file()]
    hashes = {p: hashlib.sha256(p.read_bytes()).digest() for p in paths}
    expected = companion.usage._public_shape(cache, record["provider"], prices, record["model_requested"])

    def forbidden(*args, **kwargs):
        raise AssertionError("forbidden helper or side effect")

    for name in ("compute_usage", "update_usage", "usage_summary_only", "load_usage_cache", "load_price_table", "save_usage_cache", "_read_new_complete_lines"):
        monkeypatch.setattr(companion.usage, name, forbidden)
    monkeypatch.setattr(companion.relay, "_atomic_write", forbidden)
    for module, names in (("orchestrator", ("_cmd_status", "_status_row", "_save_state", "pid_alive")),
                          ("orchestrator_dashboard", ("build_movement_summary", "build_board", "gather_movements", "build_pending_action_card"))):
        monkeypatch.setitem(sys.modules, module, types.SimpleNamespace(**{name: forbidden for name in names}))
    for name in ("Popen", "run", "call", "check_output"):
        monkeypatch.setattr(subprocess, name, forbidden)
    monkeypatch.setattr(socket, "socket", forbidden)
    monkeypatch.setattr(socket, "create_connection", forbidden)
    for name in ("replace", "rename", "unlink", "remove", "mkdir", "rmdir", "chmod", "truncate", "system"):
        monkeypatch.setattr(os, name, forbidden)
    original_open = os.open

    def readonly_open(path, flags, *args, **kwargs):
        assert not flags & (os.O_WRONLY | os.O_RDWR | os.O_CREAT | os.O_TRUNC | os.O_APPEND)
        assert "engineer.log" not in str(path)
        return original_open(path, flags, *args, **kwargs)

    monkeypatch.setattr(os, "open", readonly_open)
    for module in (builtins, io):
        original = module.open

        def readonly_file(path, mode="r", *args, _original=original, **kwargs):
            assert not any(flag in mode for flag in "wax+")
            return _original(path, mode, *args, **kwargs)

        monkeypatch.setattr(module, "open", readonly_file)
    result, item = observation(sources)
    assert result["status"] == "VALID" and result["coverage"] == "COMPLETE"
    assert all(item["usage"][key] == value for key, value in expected.items())
    assert item["health"] is None and item["work_stage"] is None
    assert item["record"]["external_participant"] is True
    assert item["record"]["verification"] is True
    assert item["usage"]["attempt_attribution"] == "UNKNOWN"
    assert item["usage"]["accounting_class"] == "subscription"
    assert item["record"]["attempt_count"] == 1
    assert SENTINEL not in json.dumps(result)
    assert str(config.state_root) not in json.dumps(result)
    assert hashes == {p: hashlib.sha256(p.read_bytes()).digest() for p in paths}
    assert companion.scan_sources(config, observed_at=datetime.fromisoformat(TIME.replace("Z", "+00:00"))) == result  # no cumulative billing or refreshed event time


@pytest.mark.parametrize("reported,model,requested,source", [
    (0.5, "model-a", "model-a", "reported"),
    (None, "model-a", "model-a", "estimated"),
    (None, None, "model-a", "estimated_from_requested_model"),
    (None, "model-b", "model-a", "unavailable"),
])
def test_four_cost_sources(sources, reported, model, requested, source):
    config, record, cache, prices = sources
    cache.update(model=model, result_cost_usd=reported)
    record["model_requested"] = requested
    update_cache(sources, cache)
    write(config.state_root / (MID + ".json"), record)
    _, item = observation(sources)
    expected = companion.usage._public_shape(cache, record["provider"], prices, requested)
    assert item["usage"]["cost_source"] == source
    assert item["usage"]["cost_usd"] == expected["cost_usd"]
    assert item["usage"]["model"] == model
    assert item["record"]["model_requested"] == requested


@pytest.mark.parametrize("field", companion.usage.USAGE_FIELDS)
@pytest.mark.parametrize("result_usage", [False, True])
def test_partial_counters_never_invent_totals(sources, field, result_usage):
    cache = sources[2]
    if result_usage:
        cache["result_usage"] = {key: cache[key] for key in companion.usage.USAGE_FIELDS}
        del cache["result_usage"][field]
    else:
        cache[field] = None
    update_cache(sources, cache)
    _, item = observation(sources)
    usage = item["usage"]
    assert usage[field] is None
    assert usage["total_tokens"] is None and usage["cache_hit_ratio"] is None
    assert usage["cost_usd"] is None and usage["cost_source"] == "unavailable"
    assert usage["quality"] == "INSUFFICIENT_EVIDENCE"
    cache["result_cost_usd"] = 0
    update_cache(sources, cache)
    _, item = observation(sources)
    assert item["usage"]["cost_usd"] == 0 and item["usage"]["cost_source"] == "reported"


def test_zero_distinct_from_absence_and_result_precedence(sources):
    cache = sources[2]
    cache["result_usage"] = dict.fromkeys(companion.usage.USAGE_FIELDS, 0)
    update_cache(sources, cache)
    _, item = observation(sources)
    assert item["usage"]["total_tokens"] == 0
    assert item["usage"]["cost_usd"] == 0
    (sources[0].state_root / "usage" / (MID + ".json")).unlink()
    result, item = observation(sources)
    assert result["status"] == "DEGRADED"
    assert item["usage"] is None and item["sources"]["usage"] == "MISSING"


@pytest.mark.parametrize("field,bad", [("input_tokens", -1), ("turns", True), ("output_tokens", 1.5),
                                      ("result_cost_usd", float("inf")), ("last_event_at", "yesterday"),
                                      ("last_event_at", "2026-09-15T12:00:00"), ("result_usage", [])])
def test_invalid_usage_is_redacted(sources, field, bad):
    sources[2][field] = bad
    update_cache(sources, sources[2])
    result, item = observation(sources)
    assert item["usage"] is None and item["sources"]["usage"] == "MALFORMED"
    assert SENTINEL not in json.dumps(result)


@pytest.mark.parametrize("text", ['[]', '{broken', '{"input_tokens":1,"input_tokens":2}', '{"input_tokens":NaN}'])
def test_malformed_and_duplicate_json(sources, text):
    (sources[0].state_root / "usage" / (MID + ".json")).write_text(text)
    _, item = observation(sources)
    assert item["sources"]["usage"] == "MALFORMED"


def test_unrecognized_text_cannot_escape_or_trigger_model_fallback(sources):
    config, record, cache, _ = sources
    record.update(provider=SENTINEL, effort_requested=SENTINEL, audit_exception=SENTINEL)
    cache["model"] = SENTINEL
    cache["provider"] = SENTINEL
    write(config.state_root / (MID + ".json"), record)
    update_cache(sources, cache)
    result, item = observation(sources)
    assert SENTINEL not in json.dumps(result)
    assert item["record"]["provider"] is None
    assert item["usage"]["model_recognition"] == "UNRECOGNIZED"
    assert item["usage"]["cost_source"] == "unavailable"


@pytest.mark.parametrize("kind", ["file", "directory", "root", "ancestor", "fifo", "hardlink"])
def test_reject_symlinks_special_files_and_shared_inodes(sources, tmp_path, kind):
    config = sources[0]
    target = config.state_root / "usage" / (MID + ".json")
    if kind in ("file", "fifo", "hardlink"):
        outside = tmp_path / "outside.json"
        target.rename(outside)
        if kind == "file":
            target.symlink_to(outside)
        elif kind == "fifo":
            os.mkfifo(target)
        else:
            os.link(outside, target)
    else:
        directory = config.state_root / "usage" if kind == "directory" else config.state_root
        moved = directory.with_name("moved")
        directory.rename(moved)
        directory.symlink_to(moved, target_is_directory=True)
        if kind == "ancestor":
            config = companion.SourceConfig(config.state_root / "usage", config.relay_root, config.price_root,
                                            config.price_file, config.providers, config.models)
    result = companion.scan_sources(config, observed_at=datetime.fromisoformat(TIME.replace("Z", "+00:00")))
    assert result["status"] == "DEGRADED"
    statuses = list(result["sources"].values()) + [v for item in result["movements"] for v in item["sources"].values()]
    assert "PATH_REJECTED" in statuses


@pytest.mark.parametrize("relative", ["../outside.json", "/outside.json", "usage/../../outside.json", "usage/./x.json"])
def test_reject_traversal_before_read(sources, relative):
    with pytest.raises(companion.SourceError, match="^PATH_REJECTED$"):
        companion._Sources().read(sources[0].state_root, relative)


def test_config_rejects_nonlocal_or_unpinned_configuration(sources):
    config = sources[0]
    for root, filename in ((Path("relative"), "prices.json"), (config.state_root / "..", "prices.json"),
                           (config.state_root, "../prices.json")):
        with pytest.raises(companion.SourceError, match="^PATH_REJECTED$"):
            companion.SourceConfig(root, config.relay_root, config.price_root, filename, config.providers, config.models)
    with pytest.raises(companion.SourceError, match="^CONFIG_INVALID$"):
        companion.SourceConfig(config.state_root, config.relay_root, config.price_root, "prices.json",
                               frozenset({SENTINEL + " unsafe text"}), config.models)


def test_size_and_scan_limits_expose_incomplete_coverage(sources, monkeypatch):
    target = sources[0].state_root / "usage" / (MID + ".json")
    target.write_bytes(b" " * (companion.MAX_BYTES + 1))
    result, item = observation(sources)
    assert result["status"] == "LIMIT_EXCEEDED" and result["coverage"] == "INCOMPLETE"
    assert item["sources"]["usage"] == "LIMIT_EXCEEDED"
    monkeypatch.setattr(companion, "MAX_MOVEMENTS", 1)
    write(sources[0].state_root / "NXS-LOCAL-0002.json", {})
    result = companion.scan_sources(sources[0], observed_at=datetime.fromisoformat(TIME.replace("Z", "+00:00")))
    assert result["status"] == "LIMIT_EXCEEDED" and result["coverage"] == "INCOMPLETE"


def test_ambiguous_relay_and_identity_mismatch(sources):
    config = sources[0]
    write(config.relay_root / (MID + "-duplicate.json"), relay_value())
    _, item = observation(sources)
    assert item["relay"] is None and item["sources"]["relay"] == "AMBIGUOUS"
    sources[1]["movement_id"] = "NXS-LOCAL-0002"
    write(config.state_root / (MID + ".json"), sources[1])
    _, item = observation(sources)
    assert item["record"] is None and item["sources"]["record"] == "IDENTITY_MISMATCH"


def test_relay_only_movement_is_not_silently_omitted(sources):
    (sources[0].state_root / (MID + ".json")).unlink()
    result, item = observation(sources)
    assert result["coverage"] == "INCOMPLETE"
    assert item["record"] is None and item["relay"]["sequence"] == 1


@pytest.mark.parametrize("replacement", ["content", "symlink", "root", "new_relay"])
def test_source_replacement_during_scan_yields_no_mixed_result(sources, monkeypatch, replacement):
    config = sources[0]
    original = companion._Sources.recheck

    def race(reader):
        target = config.state_root / (MID + ".json")
        if replacement == "content":
            sources[1]["revision"] = 2
            write(target, sources[1])
        elif replacement == "symlink":
            moved = config.state_root / "moved.json"
            target.rename(moved)
            target.symlink_to(moved)
        elif replacement == "root":
            moved = config.state_root.with_name("old_state")
            config.state_root.rename(moved)
            config.state_root.mkdir()
        else:
            write(config.relay_root / (MID + "-second.json"), relay_value())
        return original(reader)

    monkeypatch.setattr(companion._Sources, "recheck", race)
    result = companion.scan_sources(config, observed_at=datetime.fromisoformat(TIME.replace("Z", "+00:00")))
    assert result == {"status": "INCONSISTENT", "coverage": "INCOMPLETE", "movements": []}


def test_partial_price_table_does_not_invent_free_rates(sources):
    del sources[3]["model-a"]["output_per_mtok"]
    write(sources[0].price_root / sources[0].price_file, sources[3])
    result, item = observation(sources)
    assert result["sources"]["prices"] == "INSUFFICIENT_EVIDENCE"
    assert item["usage"]["cost_usd"] is None and item["usage"]["cost_source"] == "unavailable"


def test_closed_relay_is_recorded_without_merge_claim(sources):
    value = relay_value()
    # Reuse the real schema with entirely synthetic values.
    schema = companion.relay.gst.SESSION_CLOSE_REPORT_SCHEMA[1]
    report = {key: [] for key in ("completed", "changed", "preserved", "unresolved_risks", "state_updates")}
    for key in ("completed", "changed", "preserved"):
        report[key] = ["synthetic"]
    report.update(validation={key: "synthetic" for key in schema["validation"][1]},
                  next={"movement": "C2", "movement_type": "IMPLEMENTATION", "status": "planned", "objective": SENTINEL},
                  recommended_reasoning={"tier": "ordinary", "reason": "synthetic"}, continuation="NEW_SESSION",
                  integration={"branch": SENTINEL, "head_sha": SENTINEL, "pr": None, "pr_url": None,
                               "ci": "synthetic", "merge_state": "NOT_OPENED", "merge_commit": None, "merge_decision": "PO"},
                  effects={"main_py": "unchanged", "ui": "unchanged"})
    value["entries"].append({"actor": "engineer", "marker": "SESSION_CLOSE", "seq": 2,
                             "timestamp": TIME, "outcome": "DONE", "report": report})
    value.update(status="CLOSED", next_actor=None)
    write(sources[0].relay_root / (MID + "-synthetic.json"), value)
    result, item = observation(sources)
    assert item["relay"] == {"status": "CLOSED", "next_actor": None, "sequence": 2,
                              "marker": "SESSION_CLOSE", "timestamp": TIME, "outcome": "DONE"}
    assert "merge" not in json.dumps(result) and SENTINEL not in json.dumps(result)


@pytest.mark.parametrize("seconds,freshness,age", [(0, "FRESH", 0), (91, "STALE", 91), (-1, "UNKNOWN", None)])
def test_usage_age_comes_from_source_event_not_poll(sources, seconds, freshness, age):
    checked = datetime.fromisoformat(TIME.replace("Z", "+00:00"))
    baseline = companion.scan_sources(sources[0], observed_at=checked)["movements"][0]
    item = companion.scan_sources(sources[0], observed_at=checked + timedelta(seconds=seconds))["movements"][0]
    assert item["usage"]["freshness"] == freshness and item["usage"]["age_seconds"] == age
    assert item["usage"]["last_event_at"] == TIME
    assert item["fingerprint"] == baseline["fingerprint"]


@pytest.mark.parametrize("field,value", [("phase", "unapproved"), ("revision", True),
                                        ("retry_count", -1), ("started_at", "2026-09-15\n12:00:00Z"),
                                        ("external_participant", "yes"), ("verify", {"passed": 1})])
def test_record_fields_validate_before_projection(sources, field, value):
    sources[1][field] = value
    write(sources[0].state_root / (MID + ".json"), sources[1])
    _, item = observation(sources)
    assert item["record"] is None and item["sources"]["record"] == "MALFORMED"


def test_root_ownership_and_missing_roots_fail_closed(sources, monkeypatch):
    with monkeypatch.context() as patch:
        patch.setattr(os, "getuid", lambda: -1)
        result = companion.scan_sources(sources[0])
        assert result["sources"]["records"] == "PATH_REJECTED" and result["coverage"] == "INCOMPLETE"
    sources[0].relay_root.rename(sources[0].relay_root.with_name("unavailable"))
    result, item = observation(sources)
    assert result["sources"]["relays"] == "MISSING" and item["relay"] is None


def test_directory_and_union_movement_caps(sources, monkeypatch):
    with monkeypatch.context() as patch:
        patch.setattr(companion, "MAX_DIRECTORY_ENTRIES", 1)
        result = companion.scan_sources(sources[0])
        assert result["status"] == "LIMIT_EXCEEDED"
    patch_value = relay_value()
    patch_value.update(id="NXS-LOCAL-0002", movement="NXS-LOCAL-0002")
    (sources[0].relay_root / (MID + "-synthetic.json")).unlink()
    write(sources[0].relay_root / "NXS-LOCAL-0002-synthetic.json", patch_value)
    monkeypatch.setattr(companion, "MAX_MOVEMENTS", 1)
    result = companion.scan_sources(sources[0])
    assert result == {"status": "LIMIT_EXCEEDED", "coverage": "INCOMPLETE", "movements": []}
