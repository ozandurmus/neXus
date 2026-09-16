"""C2 local observer. The scan callback supplies C1-validated projections only.

No source discovery, raw-source adapter, process probe, notifier or client server
lives here. Stop the foreground loop to roll back; private state is retained.
"""

import copy
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import stat
import threading
import time
import uuid


SCHEMA = 1
MAX_BYTES = 2 * 1024 * 1024
MAX_MOVEMENTS = 1000
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
            raise ValueError("STATE_INVALID")
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

    Configuration may seed movement identities and pins safe metadata vocabularies.
    C1-validated record entries define current coverage; relay-only entries remain
    visible as historical source quality and do not block a current successful scan.
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
                    "phase": {"pending", "running", "verifying", "failed", "done", "closed"},
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
                if any(not _token(identity) or type(value) is not dict
                       for identity, value in inputs.items()):
                    raise ValueError("MALFORMED")
                known = self.ids | set(state["observations"])
                inputs = {identity: value for identity, value in inputs.items()
                          if identity in known or any(source in value for source in SOURCES)}
            except Exception:
                inputs = {}
                errors.add("MALFORMED")
            current = {identity for identity, value in inputs.items()
                       if "record" in value and (type(value["record"]) is not dict
                                                  or value["record"].get("status") != "MISSING")}
            identities = sorted(self.ids | set(state["observations"]) | set(inputs))
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
                    if identity in current and status != "VALID":
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
