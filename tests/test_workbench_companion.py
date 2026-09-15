"""Synthetic acceptance for the frozen companion's C1 read boundary."""
import builtins
from datetime import datetime, timedelta
import hashlib
import io
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import types

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import workbench_companion as companion

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
