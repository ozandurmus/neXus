"""C1: bounded, read-only local source projection; no observer or client runtime."""
from __future__ import annotations

import errno
import hashlib
import json
import math
import os
import re
import stat
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import local_relay as relay
import orchestrator_usage as usage

MAX_BYTES = 2 * 1024 * 1024
MAX_MOVEMENTS = 1000
MAX_DIRECTORY_ENTRIES = 4000
MOVEMENT = re.compile(r"NXS-LOCAL-[0-9]{4}\Z")
PHASES = {"running", "done", "failed", "cancelled"}
RATES = ("input_per_mtok", "cache_write_per_mtok", "cache_read_per_mtok", "output_per_mtok")


class SourceError(ValueError):
    """Fixed error codes only; never carry source text or local paths."""


def _fail(code="MALFORMED"):
    raise SourceError(code)


def _number(value, integer=False):
    if value is not None and (
        type(value) not in ((int,) if integer else (int, float))
        or value < 0 or not math.isfinite(value)
    ):
        _fail()
    return value


def _timestamp(value):
    if value is not None:
        if not isinstance(value, str) or not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,6})?(?:Z|[+-][0-9]{2}:[0-9]{2})", value):
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


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            _fail()
        result[key] = value
    return result


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
        for values in (self.providers, self.models, self.efforts, self.audit_exceptions, self.subscription_providers):
            if not isinstance(values, frozenset) or len(values) > 100:
                _fail("CONFIG_INVALID")
            if any(not isinstance(v, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,100}", v) for v in values):
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
        if not isinstance(relative, str) or not relative or Path(relative).is_absolute() or any(part in ("", ".", "..") for part in relative.split("/")):
            _fail("PATH_REJECTED")
        key = (root, relative)
        try:
            fd = _open_directory(root)
            try:
                parts = Path(relative).parts
                for part in parts[:-1]:
                    child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=fd)
                    os.close(fd)
                    fd = child
                    if os.fstat(fd).st_uid != os.getuid():
                        _fail("PATH_REJECTED")
                source = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC, dir_fd=fd)
                try:
                    before = os.fstat(source)
                    if not stat.S_ISREG(before.st_mode) or before.st_uid != os.getuid() or before.st_nlink != 1:
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
                # Invalid sources stay invalid; changed bytes were checked before parsing.
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
                           ("effort_requested", config.efforts), ("audit_exception", config.audit_exceptions)):
        result[field], result[field + "_recognition"] = _allowed(value.get(field), allowed)
    result["attempt_count"] = (max(1, (result["revision"] or 1) + result["retry_count"])
                               if result["revision"] is not None and result["retry_count"] is not None else None)
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
    # Never use requested-model fallback to disguise an unrecognized observed model.
    requested = record["model_requested"] if recognition != "UNRECOGNIZED" else None
    projection = usage._public_shape(safe, record["provider"], prices if complete else {}, requested)
    if not complete:
        projection.update(total_tokens=None, cache_hit_ratio=None,
                          cost_usd=safe["result_cost_usd"],
                          cost_source="reported" if safe["result_cost_usd"] is not None else "unavailable")
    for field in ("total_tokens", "cache_hit_ratio", "cost_usd"):
        _number(projection[field])
    projection.update(model_recognition=recognition, cache_provider_recognition=provider_recognition,
                      attempt_attribution="UNKNOWN", quality="VALID" if complete else "INSUFFICIENT_EVIDENCE")
    event_time = safe["last_event_at"]
    age = ((observed_at - datetime.fromisoformat(event_time.replace("Z", "+00:00"))).total_seconds()
           if event_time is not None else None)
    projection["age_seconds"] = age if age is not None and age >= 0 else None
    projection["freshness"] = "UNKNOWN" if age is None or age < 0 else "STALE" if age > 90 else "FRESH"
    provider = record["provider"]
    projection["accounting_class"] = (None if provider is None else
                                      "subscription" if provider in config.subscription_providers else "metered")
    return projection


def scan_sources(config: SourceConfig, *, observed_at: datetime | None = None) -> dict:
    """Return only safe in-memory observations; a changed scan returns no mixed data."""
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

    prices = get("prices", lambda: sources.read(config.price_root, config.price_file), lambda v: _prices(v, config))
    records = get("records", lambda: sources.names(config.state_root, re.compile(r"NXS-LOCAL-[0-9]{4}\.json")), lambda v: v)
    relays = get("relays", lambda: sources.names(config.relay_root, re.compile(r"NXS-LOCAL-[0-9]{4}-[a-z0-9-]+\.json")), lambda v: v)
    matches = {}
    for name in relays or []:
        matches.setdefault(name[:14], []).append(name)
    movements = sorted({name[:-5] for name in records or []} | set(matches))
    if len(movements) > MAX_MOVEMENTS:
        return {"status": "LIMIT_EXCEEDED", "coverage": "INCOMPLETE", "movements": []}
    observations = []
    for movement in movements:
        name = movement + ".json"
        record = get(movement + ":record", lambda: sources.read(config.state_root, name), lambda v: _record(v, movement, config))
        observation = {"movement_id": movement, "record": record, "health": None, "work_stage": None}
        candidates = matches.get(movement, [])
        if len(candidates) > 1:
            statuses[movement + ":relay"] = "AMBIGUOUS"
            observation["relay"] = None
        elif candidates:
            observation["relay"] = get(movement + ":relay", lambda: sources.read(config.relay_root, candidates[0]), lambda v: _relay(v, movement))
        else:
            statuses[movement + ":relay"] = "MISSING" if relays is not None else statuses["relays"]
            observation["relay"] = None
        observation["usage"] = get(movement + ":usage", lambda: sources.read(config.state_root, "usage/" + name),
                                   lambda v: _usage(v, record, prices or {}, config, observed_at)) if record is not None else None
        observation["sources"] = {kind: statuses.get(movement + ":" + kind, "NOT_EVALUABLE") for kind in ("record", "relay", "usage")}
        fingerprint_fields = dict(observation)
        if observation["usage"] is not None:
            fingerprint_fields["usage"] = {key: value for key, value in observation["usage"].items()
                                            if key not in ("age_seconds", "freshness")}
        observation["fingerprint"] = _fingerprint(fingerprint_fields)
        observations.append(observation)
    try:
        sources.recheck()
    except SourceError:
        return {"status": "INCONSISTENT", "coverage": "INCOMPLETE", "movements": []}
    errors = set(statuses.values()) - {"VALID"}
    status = ("INCONSISTENT" if "INCONSISTENT" in errors else
              "LIMIT_EXCEEDED" if "LIMIT_EXCEEDED" in errors else "DEGRADED" if errors else "VALID")
    return {"status": status, "coverage": "INCOMPLETE" if errors else "COMPLETE",
            "sources": {key: statuses[key] for key in ("records", "relays", "prices")},
            "price_table_fingerprint": _fingerprint(prices) if prices is not None else None,
            "movements": [] if status == "INCONSISTENT" else observations}
