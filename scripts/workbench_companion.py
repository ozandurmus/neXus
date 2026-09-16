"""C1 source adapter and C2 local observer for the Workbench Companion.

No source discovery, raw-source adapter, process probe, notifier or client server
lives here. Stop the foreground loop to roll back; private state is retained.
"""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime, timezone
import errno
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import re
import signal
import stat
import sys
import threading
import time
import uuid

from scripts import local_relay as relay
from scripts import orchestrator_usage as usage


SCHEMA = 1
MAX_BYTES = 2 * 1024 * 1024
MAX_MOVEMENTS = 1000
MAX_DIRECTORY_ENTRIES = 4000
MAX_CONFIGURATION_BYTES = 64 * 1024
MOVEMENT = re.compile(r"NXS-LOCAL-[0-9]{4}\Z")
PHASES = {"running", "done", "failed", "cancelled"}
RATES = ("input_per_mtok", "cache_write_per_mtok", "cache_read_per_mtok", "output_per_mtok")
PROVENANCE = "orchestrator_usage_cache"
MAX_TRANSITIONS = 2000
RETENTION = 30 * 86400
SOURCES = ("record", "relay", "usage")
QUALITY = {"VALID", "MISSING", "UNREADABLE", "MALFORMED", "INCONSISTENT",
           "LIMIT_EXCEEDED", "INSUFFICIENT_EVIDENCE"}
COST_SOURCES = {"reported", "estimated", "estimated_from_requested_model", "unavailable"}
EVENTS = {"ATTENTION_REQUESTED", "RECORDED_FAILURE", "CLOSE_OBSERVED",
          "SOURCE_UNAVAILABLE", "SOURCE_RECOVERED", "RECONCILED"}
COUNTERS = ("input_tokens", "output_tokens", "cache_read_tokens",
            "cache_creation_tokens", "turns")
FIELDS = {
    "record": {"revision", "retry_count", "start_time", "phase",
               "external_participant", "verification", "provider", "effort"},
    "relay": {"relay_status", "next_actor", "sequence", "marker", "event_time", "outcome"},
    "usage": {*COUNTERS, "cache_ratio", "cost", "cost_source", "event_time",
              "requested_model", "observed_model", "provenance", "audit_exception",
              "price_table_fingerprint", "attempt_attribution"},
}


def _json(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise SourceError("MALFORMED")
        result[key] = value
    return result


def _number(value, integer=False):
    if value is None:
        return None
    if type(value) not in (int, float) or value < 0:
        raise ValueError("MALFORMED")
    try:
        finite = math.isfinite(value)
    except OverflowError:
        finite = False
    if not finite:
        raise ValueError("MALFORMED")
    if integer and type(value) is not int:
        raise ValueError("MALFORMED")
    return value


def _token(value):
    return (type(value) is str and 1 <= len(value) <= 128
            and all(c.isascii() and (c.isalnum() or c in "-_.:") for c in value))


class SourceError(ValueError):
    """Fixed error codes only; never carry source text or local paths."""


def _fail(code="MALFORMED"):
    raise SourceError(code)


def _timestamp(value):
    if value is not None:
        if not isinstance(value, str) or not re.fullmatch(
                r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,6})?(?:Z|[+-][0-9]{2}:[0-9]{2})", value):
            _fail()
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            _fail()
        if parsed.tzinfo is None:
            _fail()
    return value


def _boolean(value):
    if value is not None and type(value) is not bool:
        _fail()
    return value


def _object(value):
    if not isinstance(value, dict):
        _fail()
    return value


def _fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, allow_nan=False).encode()).hexdigest()


@dataclass(frozen=True)
class SourceConfig:
    state_root: Path
    relay_root: Path
    price_root: Path
    price_file: str
    providers: frozenset[str]
    models: frozenset[str]
    efforts: frozenset[str] = frozenset()
    audit_exceptions: frozenset[str] = frozenset()
    subscription_providers: frozenset[str] = frozenset()

    def __post_init__(self):
        for root in (self.state_root, self.relay_root, self.price_root):
            if not isinstance(root, Path) or not root.is_absolute() or ".." in root.parts:
                _fail("PATH_REJECTED")
        if not isinstance(self.price_file, str) or not re.fullmatch(r"[A-Za-z0-9_-]+\.json", self.price_file):
            _fail("PATH_REJECTED")
        for values in (self.providers, self.models, self.efforts, self.audit_exceptions,
                       self.subscription_providers):
            if not isinstance(values, frozenset) or len(values) > 100:
                _fail("CONFIG_INVALID")
            if any(not isinstance(v, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,100}", v)
                   for v in values):
                _fail("CONFIG_INVALID")
        if not self.subscription_providers <= self.providers:
            _fail("CONFIG_INVALID")


def _open_directory(path):
    """Walk every component with no-follow; fail closed on unsupported platforms."""
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
    fd = os.open("/", flags)
    try:
        for part in path.parts[1:]:
            child = os.open(part, flags, dir_fd=fd)
            os.close(fd)
            fd = child
        if os.fstat(fd).st_uid != os.getuid():
            _fail("PATH_REJECTED")
        return fd
    except BaseException:
        os.close(fd)
        raise


def _stat_key(info):
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)


class _Sources:
    def __init__(self):
        self.checks = {}

    def read(self, root, relative):
        if (not isinstance(relative, str) or not relative or Path(relative).is_absolute()
                or any(part in ("", ".", "..") for part in relative.split("/"))):
            _fail("PATH_REJECTED")
        key = (root, relative)
        try:
            fd = _open_directory(root)
            try:
                parts = Path(relative).parts
                for part in parts[:-1]:
                    child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC,
                                    dir_fd=fd)
                    os.close(fd)
                    fd = child
                    if os.fstat(fd).st_uid != os.getuid():
                        _fail("PATH_REJECTED")
                source = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC,
                                 dir_fd=fd)
                try:
                    before = os.fstat(source)
                    if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.getuid()
                            or before.st_nlink != 1):
                        _fail("PATH_REJECTED")
                    if before.st_size > MAX_BYTES:
                        _fail("LIMIT_EXCEEDED")
                    chunks, size = [], 0
                    while size <= MAX_BYTES:
                        chunk = os.read(source, min(65536, MAX_BYTES + 1 - size))
                        if not chunk:
                            break
                        chunks.append(chunk)
                        size += len(chunk)
                    if size > MAX_BYTES:
                        _fail("LIMIT_EXCEEDED")
                    after = os.fstat(source)
                    if _stat_key(before) != _stat_key(after):
                        _fail("INCONSISTENT")
                    data = b"".join(chunks)
                    fingerprint = (_stat_key(after), hashlib.sha256(data).digest())
                finally:
                    os.close(source)
            finally:
                os.close(fd)
            self._track(key, fingerprint)
            value = json.loads(data, object_pairs_hook=_unique, parse_constant=lambda _: _fail())
            return _object(value)
        except FileNotFoundError:
            self._track(key, "MISSING")
            _fail("MISSING")
        except (UnicodeError, json.JSONDecodeError, RecursionError, OverflowError):
            _fail()
        except OSError as error:
            _fail("PATH_REJECTED" if error.errno in (errno.ELOOP, errno.ENOTDIR) else "UNREADABLE")

    def names(self, root, pattern):
        try:
            fd = _open_directory(root)
            try:
                before = _stat_key(os.fstat(fd))
                names = []
                with os.scandir(fd) as entries:
                    for count, entry in enumerate(entries, 1):
                        if count > MAX_DIRECTORY_ENTRIES:
                            _fail("LIMIT_EXCEEDED")
                        if pattern.fullmatch(entry.name):
                            names.append(entry.name)
                            if len(names) > MAX_MOVEMENTS:
                                _fail("LIMIT_EXCEEDED")
                if before != _stat_key(os.fstat(fd)):
                    _fail("INCONSISTENT")
                names.sort()
                self._track((root, pattern), (before, tuple(names)))
                return names
            finally:
                os.close(fd)
        except FileNotFoundError:
            self._track((root, pattern), "MISSING")
            _fail("MISSING")
        except OSError as error:
            _fail("PATH_REJECTED" if error.errno in (errno.ELOOP, errno.ENOTDIR) else "UNREADABLE")

    def _track(self, key, fingerprint):
        if key in self.checks and self.checks[key] != fingerprint:
            _fail("INCONSISTENT")
        self.checks[key] = fingerprint

    def recheck(self):
        for root, relative in list(self.checks):
            try:
                if isinstance(relative, str):
                    self.read(root, relative)
                else:
                    self.names(root, relative)
            except SourceError as error:
                if str(error) == "INCONSISTENT":
                    raise
                if str(error) not in ("MALFORMED", "MISSING"):
                    _fail("INCONSISTENT")


def _allowed(value, allowed):
    if value is None:
        return None, "MISSING"
    if isinstance(value, str) and value in allowed:
        return value, "RECOGNIZED"
    return None, "UNRECOGNIZED"


def _record(value, movement, config):
    if value.get("movement_id") != movement:
        _fail("IDENTITY_MISMATCH")
    if value.get("phase") not in PHASES:
        _fail()
    verify = value.get("verify")
    if verify is not None:
        verify = _boolean(_object(verify).get("passed"))
    result = {
        "movement_id": movement, "phase": value["phase"],
        "revision": _number(value.get("revision"), True),
        "retry_count": _number(value.get("retry_count"), True),
        "started_at": _timestamp(value.get("started_at")),
        "external_participant": _boolean(value.get("external_participant")),
        "verification": verify,
    }
    for field, allowed in (("provider", config.providers), ("model_requested", config.models),
                           ("effort_requested", config.efforts),
                           ("audit_exception", config.audit_exceptions)):
        result[field], result[field + "_recognition"] = _allowed(value.get(field), allowed)
    result["attempt_count"] = (max(1, (result["revision"] or 1) + result["retry_count"])
                               if result["revision"] is not None
                               and result["retry_count"] is not None else None)
    return result


def _relay(value, movement):
    if relay.validate_relay_object(value):
        _fail()
    if value["id"] != movement or value["movement"] != movement:
        _fail("IDENTITY_MISMATCH")
    if type(value["schema_version"]) is not int:
        _fail()
    for field in ("created_at", "updated_at"):
        _timestamp(value[field])
    for entry in value["entries"]:
        _timestamp(entry["timestamp"])
    last = value["entries"][-1]
    return {"status": value["status"], "next_actor": value["next_actor"],
            "sequence": last["seq"], "marker": last["marker"],
            "timestamp": last["timestamp"], "outcome": last.get("outcome")}


def _prices(value, config):
    result = {}
    for model, entry in value.items():
        if model == "_comment":
            continue
        _object(entry)
        for rate in RATES:
            if rate not in entry or entry[rate] is None:
                _fail("INSUFFICIENT_EVIDENCE")
            _number(entry[rate])
        if model in config.models:
            result[model] = {rate: entry[rate] for rate in RATES}
    return result


def _usage(value, record, prices, config, observed_at):
    for field in (*usage.USAGE_FIELDS, "turns", "byte_offset"):
        _number(value.get(field), True)
    _number(value.get("result_cost_usd"))
    _timestamp(value.get("last_event_at"))
    tokens = value.get("result_usage")
    if tokens is not None:
        _object(tokens)
        for field in usage.USAGE_FIELDS:
            _number(tokens.get(field), True)
    else:
        tokens = value
    model, recognition = _allowed(value.get("model"), config.models)
    cache_provider, provider_recognition = _allowed(value.get("provider"), config.providers)
    if cache_provider is not None and record["provider"] is not None and cache_provider != record["provider"]:
        _fail("IDENTITY_MISMATCH")
    safe = {field: tokens.get(field) for field in usage.USAGE_FIELDS}
    safe.update(turns=value.get("turns"), model=model, last_event_at=value.get("last_event_at"),
                result_cost_usd=value.get("result_cost_usd"))
    complete = all(safe[field] is not None for field in usage.USAGE_FIELDS)
    requested = record["model_requested"] if recognition != "UNRECOGNIZED" else None
    projection = usage._public_shape(safe, record["provider"], prices if complete else {}, requested)
    if not complete:
        projection.update(total_tokens=None, cache_hit_ratio=None,
                          cost_usd=safe["result_cost_usd"],
                          cost_source="reported" if safe["result_cost_usd"] is not None else "unavailable")
    for field in ("total_tokens", "cache_hit_ratio", "cost_usd"):
        _number(projection[field])
    projection.update(model_recognition=recognition,
                      cache_provider_recognition=provider_recognition,
                      attempt_attribution="UNKNOWN",
                      quality="VALID" if complete else "INSUFFICIENT_EVIDENCE")
    event_time = safe["last_event_at"]
    age = ((observed_at - datetime.fromisoformat(event_time.replace("Z", "+00:00"))).total_seconds()
           if event_time is not None else None)
    projection["age_seconds"] = age if age is not None and age >= 0 else None
    projection["freshness"] = "UNKNOWN" if age is None or age < 0 else "STALE" if age > 90 else "FRESH"
    provider = record["provider"]
    projection["accounting_class"] = (None if provider is None else
                                      "subscription" if provider in config.subscription_providers
                                      else "metered")
    return projection


def scan_sources(config: SourceConfig, *, observed_at: datetime | None = None) -> dict:
    """Return only safe in-memory observations; changed input yields no mixed data."""
    if not all(hasattr(os, name) for name in ("O_NOFOLLOW", "O_DIRECTORY", "getuid")):
        return {"status": "UNSUPPORTED", "coverage": "INCOMPLETE", "movements": []}
    observed_at = observed_at or datetime.now(timezone.utc)
    if not isinstance(observed_at, datetime) or observed_at.tzinfo is None:
        _fail("CONFIG_INVALID")
    sources = _Sources()
    statuses = {}

    def get(label, read, project):
        try:
            value = project(read())
            statuses[label] = "VALID"
            return value
        except (SourceError, TypeError, ValueError, OverflowError, RecursionError) as error:
            statuses[label] = str(error) if isinstance(error, SourceError) else "MALFORMED"
            return None

    prices = get("prices", lambda: sources.read(config.price_root, config.price_file),
                 lambda v: _prices(v, config))
    records = get("records", lambda: sources.names(
        config.state_root, re.compile(r"NXS-LOCAL-[0-9]{4}\.json")), lambda v: v)
    relays = get("relays", lambda: sources.names(
        config.relay_root, re.compile(r"NXS-LOCAL-[0-9]{4}-[a-z0-9-]+\.json")), lambda v: v)
    matches = {}
    for name in relays or []:
        matches.setdefault(name[:14], []).append(name)
    movements = sorted({name[:-5] for name in records or []} | set(matches))
    if len(movements) > MAX_MOVEMENTS:
        return {"status": "LIMIT_EXCEEDED", "coverage": "INCOMPLETE", "movements": []}
    observations = []
    for movement in movements:
        name = movement + ".json"
        record = get(movement + ":record", lambda: sources.read(config.state_root, name),
                     lambda v: _record(v, movement, config))
        observation = {"movement_id": movement, "record": record,
                       "health": None, "work_stage": None}
        candidates = matches.get(movement, [])
        if len(candidates) > 1:
            statuses[movement + ":relay"] = "AMBIGUOUS"
            observation["relay"] = None
        elif candidates:
            observation["relay"] = get(
                movement + ":relay", lambda: sources.read(config.relay_root, candidates[0]),
                lambda v: _relay(v, movement))
        else:
            statuses[movement + ":relay"] = "MISSING" if relays is not None else statuses["relays"]
            observation["relay"] = None
        observation["usage"] = get(
            movement + ":usage", lambda: sources.read(config.state_root, "usage/" + name),
            lambda v: _usage(v, record, prices or {}, config, observed_at)) if record is not None else None
        observation["sources"] = {
            kind: statuses.get(movement + ":" + kind, "NOT_EVALUABLE")
            for kind in ("record", "relay", "usage")}
        fingerprint_fields = dict(observation)
        if observation["usage"] is not None:
            fingerprint_fields["usage"] = {
                key: value for key, value in observation["usage"].items()
                if key not in ("age_seconds", "freshness")}
        observation["fingerprint"] = _fingerprint(fingerprint_fields)
        observations.append(observation)
    try:
        sources.recheck()
    except SourceError:
        return {"status": "INCONSISTENT", "coverage": "INCOMPLETE", "movements": []}
    errors = set(statuses.values()) - {"VALID"}
    status = ("INCONSISTENT" if "INCONSISTENT" in errors else
              "LIMIT_EXCEEDED" if "LIMIT_EXCEEDED" in errors else
              "DEGRADED" if errors else "VALID")
    return {"status": status, "coverage": "INCOMPLETE" if errors else "COMPLETE",
            "sources": {key: statuses[key] for key in ("records", "relays", "prices")},
            "price_table_fingerprint": _fingerprint(prices) if prices is not None else None,
            "movements": [] if status == "INCONSISTENT" else observations}


def _directory(path):
    """Walk opened directories without following replacement symlinks."""
    fd = os.open("/", os.O_RDONLY | os.O_DIRECTORY)
    try:
        for index, part in enumerate(path.parts[1:]):
            if index == len(path.parts) - 2:
                try:
                    os.mkdir(part, mode=0o700, dir_fd=fd)
                except FileExistsError:
                    pass
            next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd)
            fd = next_fd
        return fd
    except BaseException:
        os.close(fd)
        raise


class Observer:
    """Single writer, one bounded scan per tick, explicit private local state.

    Configuration pins exact movement identities and safe metadata vocabularies.
    Timestamps in the C1 projection are finite nonnegative Unix seconds. The
    callback returns {movement_id: {record/relay/usage: {status, allowed fields}}}.
    A missing source is unavailable, never a workflow failure. The callback must
    enforce the contract's bounded descriptor reads and generation consistency.
    """

    def __init__(self, directory, source_set_token, movement_ids, *, source_roots,
                 allowlists=None):
        if not _token(source_set_token):
            raise ValueError("INVALID_CONFIGURATION")
        self.ids = frozenset(movement_ids)
        if len(self.ids) > MAX_MOVEMENTS or any(not _token(value) for value in self.ids):
            raise ValueError("INVALID_CONFIGURATION")
        self.allowlists = {key: frozenset(values) for key, values in (allowlists or {}).items()}
        if any(not _token(value) for values in self.allowlists.values() for value in values):
            raise ValueError("INVALID_CONFIGURATION")
        directory = Path(os.path.abspath(directory))
        excluded = [Path(__file__).resolve().parent.parent, *map(Path, source_roots)]
        if directory.resolve() != directory or any(directory.is_relative_to(p.resolve()) for p in excluded):
            raise ValueError("INVALID_STATE_LOCATION")
        self.fd = _directory(directory)
        self.lock_fd = None
        self.busy = threading.Lock()
        self.error = None
        self.restarted = False
        self.source_changed = False
        self.state = self._empty(source_set_token)
        try:
            info = os.fstat(self.fd)
            if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
                raise ValueError("STATE_PERMISSIONS")
            self.lock_fd = self._open("observer.lock", os.O_RDWR | os.O_CREAT)
            fcntl.flock(self.lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            # Check directory durability before admitting any snapshot writes.
            os.fsync(self.fd)
            self._load(source_set_token)
        except BaseException:
            self.close()
            raise

    @staticmethod
    def _empty(token):
        return {"schema_version": SCHEMA, "source_set_token": token,
                "observer_generation": uuid.uuid4().hex, "last_attempt": None,
                "last_success": None, "coverage": "INCOMPLETE", "errors": [],
                "observations": {}, "transitions": [], "sequence": 0,
                "history_floor": 1, "dropped_count": 0,
                "notification": {"last_considered_sequence": 0,
                                 "coalescing_time": None, "delivery_attempt": "UNSUPPORTED"}}

    def _open(self, name, flags):
        fd = os.open(name, flags | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600, dir_fd=self.fd)
        info = os.fstat(fd)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1):
            os.close(fd)
            raise ValueError("STATE_PERMISSIONS")
        return fd

    def _project(self, source, value):
        if type(value) is not dict or value.get("status") not in QUALITY:
            raise ValueError("MALFORMED")
        status = value["status"]
        if status not in {"VALID", "INSUFFICIENT_EVIDENCE"}:
            return {"status": status}
        result = {"status": status}
        unknown = False
        for field in FIELDS[source] - {"attempt_attribution"}:
            item = value.get(field)
            if field in {*COUNTERS, "revision", "retry_count", "sequence"}:
                item = _number(item, integer=True)
            elif field in {"cost", "cache_ratio", "start_time", "event_time"}:
                item = _number(item)
                if field == "cache_ratio" and item is not None and item > 1:
                    raise ValueError("MALFORMED")
            elif field in {"external_participant", "verification"}:
                if item is not None and type(item) is not bool:
                    raise ValueError("MALFORMED")
            elif field == "price_table_fingerprint":
                if item is not None and (type(item) is not str or len(item) != 64
                                         or any(c not in "0123456789abcdef" for c in item)):
                    raise ValueError("MALFORMED")
            elif field == "cost_source":
                if item not in COST_SOURCES:
                    raise ValueError("MALFORMED")
            else:
                choices = {
                    "phase": {"pending", "running", "verifying", "failed", "done", "closed", "cancelled"},
                    "relay_status": {"OPEN", "CLOSED"},
                    "next_actor": {"po", "engineer"},
                    "marker": {"SESSION_START", "SESSION_CLOSE", "RELAY_ACK", "RELAY_NOTE",
                               "RELAY_QUESTION", "RELAY_DECISION", "RELAY_CORRECTION"},
                    "outcome": {"DONE", "AUTOMATED_VALIDATED", "PARTIAL", "BLOCKED"},
                }.get(field, self.allowlists.get(field, ()))
                if item is not None and (type(item) is not str or item not in choices):
                    item, unknown = None, True
            result[field] = item
        if source == "usage":
            result["attempt_attribution"] = "UNKNOWN"
            if any(result[field] is None for field in COUNTERS):
                result["status"] = "INSUFFICIENT_EVIDENCE"
                result["cache_ratio"] = None
                if result["cost_source"] != "reported":
                    result["cost"] = None
            if result["cost_source"] == "unavailable":
                result["cost"] = None
        result["classification"] = "UNRECOGNIZED" if unknown else "RECOGNIZED"
        return result

    def _load(self, token):
        try:
            fd = self._open("snapshot.json", os.O_RDONLY)
        except FileNotFoundError:
            return
        except (OSError, ValueError):
            self.error = "STATE_INVALID"
            return
        try:
            with os.fdopen(fd, "rb") as stream:
                raw = stream.read(MAX_BYTES + 1)
            if len(raw) > MAX_BYTES:
                raise ValueError("STATE_INVALID")
            loaded = json.loads(raw, object_pairs_hook=_unique)
            if type(loaded) is not dict:
                raise ValueError("STATE_INVALID")
            if type(loaded.get("schema_version")) is not int or loaded["schema_version"] != SCHEMA:
                self.error = "SCHEMA_UNSUPPORTED"
                return
            self._validate_state(loaded)
            self.state = loaded
            self.restarted = True
            self.state["observer_generation"] = uuid.uuid4().hex
            if loaded["source_set_token"] != token:
                self.state["source_set_token"] = token
                self.source_changed = True
        except (ValueError, TypeError, KeyError, OSError, RecursionError):
            self.error = "STATE_INVALID"

    def _validate_state(self, state):
        if set(state) != set(self._empty("validation")) or not _token(state["source_set_token"]):
            raise ValueError("STATE_INVALID")
        if not _token(state["observer_generation"]) or state["coverage"] not in {"COMPLETE", "INCOMPLETE"}:
            raise ValueError("STATE_INVALID")
        for field in ("last_attempt", "last_success"):
            _number(state[field])
        for field in ("sequence", "history_floor", "dropped_count"):
            if _number(state[field], True) is None:
                raise ValueError("STATE_INVALID")
        if (type(state["errors"]) is not list or len(state["errors"]) > len(QUALITY) + 1
                or any(type(e) is not str or e not in QUALITY | {"CLOCK_ROLLBACK"} for e in state["errors"])):
            raise ValueError("STATE_INVALID")
        notification = state["notification"]
        if notification != self._empty("validation")["notification"]:
            raise ValueError("STATE_INVALID")
        observations = state["observations"]
        if type(observations) is not dict or len(observations) > MAX_MOVEMENTS:
            raise ValueError("STATE_INVALID")
        for identity, observation in observations.items():
            if not _token(identity) or set(observation) != {"movement_id", "observation_time", *SOURCES}:
                raise ValueError("STATE_INVALID")
            if observation["movement_id"] != identity or _number(observation["observation_time"]) is None:
                raise ValueError("STATE_INVALID")
            for source in SOURCES:
                block = observation[source]
                if set(block) != {"status", "checked_at", "observed_at", "fingerprint", "data"}:
                    raise ValueError("STATE_INVALID")
                if block["status"] not in QUALITY or _number(block["checked_at"]) is None:
                    raise ValueError("STATE_INVALID")
                _number(block["observed_at"])
                data = block["data"]
                if data is None:
                    if (block["fingerprint"] is not None or block["observed_at"] is not None
                            or block["status"] in {"VALID", "INSUFFICIENT_EVIDENCE"}):
                        raise ValueError("STATE_INVALID")
                else:
                    projected = self._project(source, data)
                    # Classification is derived; unknown metadata must stay unknown after restart.
                    if data.get("classification") == "UNRECOGNIZED":
                        projected["classification"] = "UNRECOGNIZED"
                    if data != projected or block["fingerprint"] != hashlib.sha256(_json(data)).hexdigest():
                        raise ValueError("STATE_INVALID")
                    if block["observed_at"] is None:
                        raise ValueError("STATE_INVALID")
        transitions = state["transitions"]
        if type(transitions) is not list or len(transitions) > MAX_TRANSITIONS:
            raise ValueError("STATE_INVALID")
        previous = 0
        for event in transitions:
            if set(event) != {"sequence", "movement_id", "record_generation", "relay_sequence", "event", "observed_at", "gap"}:
                raise ValueError("STATE_INVALID")
            sequence = _number(event["sequence"], True)
            if sequence is None or not previous < sequence <= state["sequence"]:
                raise ValueError("STATE_INVALID")
            previous = sequence
            if not _token(event["movement_id"]) or event["event"] not in EVENTS or type(event["gap"]) is not bool:
                raise ValueError("STATE_INVALID")
            if type(event["record_generation"]) is not list or len(event["record_generation"]) != 3:
                raise ValueError("STATE_INVALID")
            for index, value in enumerate(event["record_generation"]):
                _number(value, index < 2)
            _number(event["relay_sequence"], True)
            if _number(event["observed_at"]) is None:
                raise ValueError("STATE_INVALID")
        floor = transitions[0]["sequence"] if transitions else state["sequence"] + 1
        if state["history_floor"] != floor:
            raise ValueError("STATE_INVALID")

    def _write(self, state):
        self._validate_state(state)
        payload = _json(state)
        if len(payload) > MAX_BYTES:
            raise ValueError("LIMIT_EXCEEDED")
        name = ".snapshot-" + uuid.uuid4().hex
        try:
            fd = self._open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL)
            with os.fdopen(fd, "wb") as stream:
                stream.write(payload)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(name, "snapshot.json", src_dir_fd=self.fd, dst_dir_fd=self.fd)
            os.fsync(self.fd)
        finally:
            try:
                os.unlink(name, dir_fd=self.fd)
            except FileNotFoundError:
                pass

    @staticmethod
    def _generation(observation):
        record = observation["record"]["data"] or {}
        return [record.get(field) for field in ("revision", "retry_count", "start_time")]

    def _events(self, state, identity, old, new, now, gap):
        if old is None:
            return  # First observations are a baseline, never historical alerts.
        record = new["record"]["data"] or {}
        relay = new["relay"]["data"] or {}
        old_relay = old["relay"]["data"] or {}
        regression = (relay.get("sequence") is not None and old_relay.get("sequence") is not None
                      and relay["sequence"] < old_relay["sequence"])
        generation_changed = self._generation(old) != self._generation(new)
        gap = gap or regression
        events = set()
        for source in SOURCES:
            was = old[source]["status"] in {"VALID", "INSUFFICIENT_EVIDENCE"}
            valid = new[source]["status"] in {"VALID", "INSUFFICIENT_EVIDENCE"}
            if was != valid:
                events.add("SOURCE_RECOVERED" if valid else "SOURCE_UNAVAILABLE")
                gap = True
        if regression or generation_changed or self.source_changed:
            events.add("RECONCILED")
        if new["record"]["status"] == "VALID" and old["record"]["fingerprint"] != new["record"]["fingerprint"]:
            if record.get("phase") == "failed":
                events.add("RECORDED_FAILURE")
        if new["relay"]["status"] == "VALID" and old["relay"]["fingerprint"] != new["relay"]["fingerprint"]:
            if relay.get("next_actor") == "po":
                events.add("ATTENTION_REQUESTED")
            if relay.get("marker") == "SESSION_CLOSE":
                events.add("CLOSE_OBSERVED")
        for event in sorted(events):
            state["sequence"] += 1
            state["transitions"].append({"sequence": state["sequence"], "movement_id": identity,
                                         "record_generation": self._generation(new),
                                         "relay_sequence": relay.get("sequence"), "event": event,
                                         "observed_at": now, "gap": gap})

    @staticmethod
    def _prune(state, now):
        original = len(state["transitions"])
        state["transitions"] = [e for e in state["transitions"] if e["observed_at"] >= now - RETENTION][-MAX_TRANSITIONS:]
        state["dropped_count"] += original - len(state["transitions"])
        size = len(_json(state))
        while state["transitions"] and size > MAX_BYTES - 4096:
            removed = state["transitions"].pop(0)
            size -= len(_json(removed)) + bool(state["transitions"])
            state["dropped_count"] += 1
        state["history_floor"] = state["transitions"][0]["sequence"] if state["transitions"] else state["sequence"] + 1

    def poll(self, scan, *, now=None):
        """Complete one scan atomically; failed writes do not advance memory."""
        if self.error:
            return self.error
        if not self.busy.acquire(blocking=False):
            return "SCAN_BUSY"
        try:
            now = _number(time.time() if now is None else now)
            if now is None:
                raise ValueError("MALFORMED")
            old_state = self.state
            state = copy.deepcopy(old_state)
            previous = old_state["last_attempt"]
            rollback = previous is not None and now < previous
            gap = self.restarted or (previous is not None and now - previous > 90) or rollback
            errors = {"CLOCK_ROLLBACK"} if rollback else set()
            try:
                inputs = scan()
                if type(inputs) is not dict:
                    raise ValueError("MALFORMED")
                if len(inputs) > MAX_MOVEMENTS:
                    errors.add("LIMIT_EXCEEDED")
                    inputs = {}
                if any(identity not in self.ids for identity in inputs):
                    raise ValueError("MALFORMED")
            except Exception:
                inputs = {}
                errors.add("MALFORMED")
            identities = sorted(self.ids | set(state["observations"]))
            for identity in identities:
                old = old_state["observations"].get(identity)
                if old is None and len(state["observations"]) >= MAX_MOVEMENTS:
                    errors.add("LIMIT_EXCEEDED")
                    continue
                incoming = inputs.get(identity, {})
                observation = {"movement_id": identity, "observation_time": now}
                for source in SOURCES:
                    try:
                        data = self._project(source, incoming.get(source, {"status": "MISSING"}))
                        if any(data.get(field) is not None and data[field] > now
                               for field in ("event_time", "start_time")):
                            data = {"status": "INCONSISTENT"}
                    except (ValueError, TypeError, AttributeError):
                        data = {"status": "MALFORMED"}
                    status = data["status"]
                    valid = status in {"VALID", "INSUFFICIENT_EVIDENCE"}
                    prior = old[source] if old else {}
                    fingerprint = hashlib.sha256(_json(data)).hexdigest() if valid else prior.get("fingerprint")
                    if status != "VALID":
                        errors.add(status)
                    observation[source] = {"status": status, "checked_at": now,
                                           "observed_at": now if valid and fingerprint != prior.get("fingerprint") else prior.get("observed_at"),
                                           "fingerprint": fingerprint,
                                           "data": data if valid else prior.get("data")}
                state["observations"][identity] = observation
                self._events(state, identity, old, observation, now, gap)
            state["last_attempt"] = now
            if rollback:
                state["last_success"] = None
            self._prune(state, now)
            if len(_json(state)) > MAX_BYTES - 4096:
                # No active observation eviction: keep last-good data, report the cap.
                state = copy.deepcopy(old_state)
                state["last_attempt"] = now
                if rollback:
                    state["last_success"] = None
                self._prune(state, now)
                errors.add("LIMIT_EXCEEDED")
            state["errors"] = sorted(errors)
            state["coverage"] = "INCOMPLETE" if errors else "COMPLETE"
            if not errors:
                state["last_success"] = now
            self._write(state)
            self.state = state
            self.restarted = False
            self.source_changed = False
            return state["coverage"]
        finally:
            self.busy.release()

    def run(self, scan, stop):
        """Foreground loop. Monotonic scheduling skips elapsed/overlapping ticks."""
        while not stop.is_set() and not self.error:
            started = time.monotonic()
            self.poll(scan)
            elapsed = time.monotonic() - started
            stop.wait(30 - elapsed % 30)

    def close(self):
        if self.lock_fd is not None:
            os.close(self.lock_fd)
            self.lock_fd = None
        if getattr(self, "fd", None) is not None:
            os.close(self.fd)
            self.fd = None

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


@dataclass(frozen=True)
class RuntimeConfig:
    snapshot_directory: Path
    source_set_token: str
    movement_ids: tuple[str, ...]
    sources: SourceConfig


CONFIGURATION_FIELDS = {
    "schema_version", "snapshot_directory", "source_set_token", "movement_ids",
    "state_root", "relay_root", "price_root", "price_file", "providers", "models",
    "efforts", "audit_exceptions", "subscription_providers",
}
QUALITY_MAP = {
    "VALID": "VALID",
    "MISSING": "MISSING",
    "UNREADABLE": "UNREADABLE",
    "PATH_REJECTED": "UNREADABLE",
    "MALFORMED": "MALFORMED",
    "IDENTITY_MISMATCH": "INCONSISTENT",
    "AMBIGUOUS": "INCONSISTENT",
    "INCONSISTENT": "INCONSISTENT",
    "LIMIT_EXCEEDED": "LIMIT_EXCEEDED",
    "INSUFFICIENT_EVIDENCE": "INSUFFICIENT_EVIDENCE",
    "NOT_EVALUABLE": "INSUFFICIENT_EVIDENCE",
    "UNSUPPORTED": "INSUFFICIENT_EVIDENCE",
    "DEGRADED": "INSUFFICIENT_EVIDENCE",
}


def _configured_values(value, *, maximum=100):
    if (type(value) is not list or len(value) > maximum or len(set(value)) != len(value)
            or any(type(item) is not str for item in value)):
        raise ValueError("INVALID_CONFIGURATION")
    return frozenset(value)


def load_configuration(value):
    """Load one private, bounded configuration without following links."""
    try:
        path = Path(value)
        if not path.is_absolute() or ".." in path.parts or path == Path("/"):
            raise ValueError
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC)
        try:
            info = os.fstat(fd)
            if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                    or info.st_nlink != 1 or stat.S_IMODE(info.st_mode) & 0o077
                    or info.st_size > MAX_CONFIGURATION_BYTES):
                raise ValueError
            raw = os.read(fd, MAX_CONFIGURATION_BYTES + 1)
            if len(raw) > MAX_CONFIGURATION_BYTES or os.read(fd, 1):
                raise ValueError
        finally:
            os.close(fd)
        data = json.loads(raw, object_pairs_hook=_unique, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
        if (type(data) is not dict or set(data) != CONFIGURATION_FIELDS
                or type(data["schema_version"]) is not int or data["schema_version"] != 1):
            raise ValueError
        movement_ids = data["movement_ids"]
        if (type(movement_ids) is not list or not movement_ids or len(movement_ids) > MAX_MOVEMENTS
                or len(set(movement_ids)) != len(movement_ids)
                or any(type(item) is not str or not MOVEMENT.fullmatch(item) for item in movement_ids)):
            raise ValueError
        if not _token(data["source_set_token"]):
            raise ValueError
        sources = SourceConfig(
            Path(data["state_root"]), Path(data["relay_root"]), Path(data["price_root"]),
            data["price_file"], _configured_values(data["providers"]),
            _configured_values(data["models"]), _configured_values(data["efforts"]),
            _configured_values(data["audit_exceptions"]),
            _configured_values(data["subscription_providers"]),
        )
        snapshot_directory = Path(data["snapshot_directory"])
        if not snapshot_directory.is_absolute() or ".." in snapshot_directory.parts:
            raise ValueError
        return RuntimeConfig(snapshot_directory, data["source_set_token"],
                             tuple(movement_ids), sources)
    except (KeyError, TypeError, ValueError, OSError, UnicodeError, json.JSONDecodeError,
            RecursionError, OverflowError) as error:
        raise ValueError("INVALID_CONFIGURATION") from None


def _quality(value):
    return QUALITY_MAP.get(value, "MALFORMED")


def _epoch(value):
    return None if value is None else datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()


def _recognized(value, recognition):
    return "UNRECOGNIZED" if recognition == "UNRECOGNIZED" else value


def observer_projection(result, movement_ids):
    """Translate the C1 safe projection into the sole C2 callback schema."""
    if type(result) is not dict or type(result.get("movements")) is not list:
        raise ValueError("MALFORMED")
    expected = frozenset(movement_ids)
    found = {item.get("movement_id") for item in result["movements"] if type(item) is dict}
    if len(found) != len(result["movements"]) or not found <= expected:
        raise ValueError("MALFORMED")
    if not result["movements"] and result.get("status") in {
            "INCONSISTENT", "LIMIT_EXCEEDED", "UNSUPPORTED"}:
        status = _quality(result["status"])
        return {identity: {source: {"status": status} for source in SOURCES}
                for identity in movement_ids}
    projected = {}
    by_id = {item["movement_id"]: item for item in result["movements"]}
    for identity in movement_ids:
        item = by_id.get(identity)
        if item is None:
            projected[identity] = {source: {"status": "MISSING"} for source in SOURCES}
            continue
        record = item.get("record")
        relay_value = item.get("relay")
        usage_value = item.get("usage")
        statuses = item.get("sources", {})
        blocks = {
            "record": {"status": _quality(statuses.get("record"))},
            "relay": {"status": _quality(statuses.get("relay"))},
            "usage": {"status": _quality(statuses.get("usage"))},
        }
        if record is not None and blocks["record"]["status"] == "VALID":
            blocks["record"].update(
                phase=record.get("phase"), revision=record.get("revision"),
                retry_count=record.get("retry_count"), start_time=_epoch(record.get("started_at")),
                external_participant=record.get("external_participant"),
                verification=record.get("verification"),
                provider=_recognized(record.get("provider"), record.get("provider_recognition")),
                effort=_recognized(record.get("effort_requested"),
                                   record.get("effort_requested_recognition")),
            )
        if relay_value is not None and blocks["relay"]["status"] == "VALID":
            blocks["relay"].update(
                relay_status=relay_value.get("status"), next_actor=relay_value.get("next_actor"),
                sequence=relay_value.get("sequence"), marker=relay_value.get("marker"),
                event_time=_epoch(relay_value.get("timestamp")), outcome=relay_value.get("outcome"),
            )
        if usage_value is not None and blocks["usage"]["status"] == "VALID":
            blocks["usage"]["status"] = usage_value.get("quality", "VALID")
            blocks["usage"].update(
                input_tokens=usage_value.get("input_tokens"),
                output_tokens=usage_value.get("output_tokens"),
                cache_read_tokens=usage_value.get("cache_read_input_tokens"),
                cache_creation_tokens=usage_value.get("cache_creation_input_tokens"),
                turns=usage_value.get("turns"), cache_ratio=usage_value.get("cache_hit_ratio"),
                cost=usage_value.get("cost_usd"), cost_source=usage_value.get("cost_source"),
                event_time=_epoch(usage_value.get("last_event_at")),
                requested_model=_recognized(record.get("model_requested"),
                                            record.get("model_requested_recognition")),
                observed_model=_recognized(usage_value.get("model"),
                                           usage_value.get("model_recognition")),
                provenance=PROVENANCE,
                audit_exception=_recognized(record.get("audit_exception"),
                                            record.get("audit_exception_recognition")),
                price_table_fingerprint=result.get("price_table_fingerprint"),
            )
        projected[identity] = blocks
    return projected


def _run(runtime, stop):
    allowlists = {
        "provider": runtime.sources.providers,
        "effort": runtime.sources.efforts,
        "requested_model": runtime.sources.models,
        "observed_model": runtime.sources.models,
        "provenance": {PROVENANCE},
        "audit_exception": runtime.sources.audit_exceptions,
    }
    with Observer(runtime.snapshot_directory, runtime.source_set_token,
                  runtime.movement_ids,
                  source_roots=(runtime.sources.state_root, runtime.sources.relay_root,
                                runtime.sources.price_root),
                  allowlists=allowlists) as observer:
        observer.run(lambda: observer_projection(scan_sources(runtime.sources),
                                                 runtime.movement_ids), stop)
        return observer.error


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--configuration", required=True)
    args = parser.parse_args(argv)
    try:
        runtime = load_configuration(args.configuration)
    except ValueError:
        parser.exit(2, "INVALID_CONFIGURATION\n")
    stop = threading.Event()
    for name in (signal.SIGINT, signal.SIGTERM):
        signal.signal(name, lambda *_: stop.set())
    try:
        error = _run(runtime, stop)
    except (OSError, ValueError):
        print("OBSERVER_START_FAILED", file=sys.stderr)
        return 1
    if error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
