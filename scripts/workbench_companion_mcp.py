"""C3 read-only snapshot server: python3 -m scripts.workbench_companion_mcp.

Pinned legacy MCP handshake (2025-11-25 and earlier); newer protocol eras and
actual client compatibility are unverified. No observer is started or locked.
Remove the client registration to roll back; the observer and state are untouched.
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import stat
import sys
import time

from scripts import workbench_companion as snapshot


MAX_REQUEST = 16 * 1024
MAX_OUTPUT = 64 * 1024
PROTOCOL = "2025-11-25"
VERSIONS = {PROTOCOL, "2025-06-18", "2025-03-26", "2024-11-05"}
TOOLS = ("companion_status", "companion_movements", "companion_observations")
METADATA = {"provider", "effort", "requested_model", "observed_model",
            "provenance", "audit_exception"}


def _result(value, error=False):
    return {"content": [{"type": "text", "text": snapshot._json(value).decode()}],
            "isError": error}


def _error(identity, code, message):
    return {"jsonrpc": "2.0", "id": identity,
            "error": {"code": code, "message": message}}


def _age(value, now):
    return None if value is None or value > now else now - value


class Reader:
    # Reuse only the C2 shape/projection validator, not its constructor or I/O.
    _empty = staticmethod(snapshot.Observer._empty)
    _project = snapshot.Observer._project
    _validate_state = snapshot.Observer._validate_state

    def __init__(self, directory, *, allowlists=None):
        self.directory = Path(os.path.abspath(directory))
        self.allowlists = {key: frozenset(values) for key, values in (allowlists or {}).items()}
        if (set(self.allowlists) - METADATA
                or any(not snapshot._token(v) for values in self.allowlists.values() for v in values)):
            raise ValueError("INVALID_CONFIGURATION")
        self.cursor_key = os.urandom(32)

    def _read(self):
        """Reopen only the configured directory and fixed file, without writes."""
        fd = os.open("/", os.O_RDONLY | os.O_DIRECTORY)
        try:
            for part in self.directory.parts[1:]:
                next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                os.close(fd)
                fd = next_fd
            info = os.fstat(fd)
            if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
                raise ValueError("STATE_INVALID")
            file_fd = os.open("snapshot.json", os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
            with os.fdopen(file_fd, "rb") as stream:
                info = os.fstat(stream.fileno())
                if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                        or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink > 1):
                    raise ValueError("STATE_INVALID")
                raw = stream.read(snapshot.MAX_BYTES + 1)
                after = os.fstat(stream.fileno())
                # Atomic replacement may unlink this opened generation (nlink=0,
                # changed ctime); its complete descriptor contents remain valid.
                if after.st_nlink > 1 or any(getattr(after, key) != getattr(info, key) for key in (
                        "st_dev", "st_ino", "st_mode", "st_uid", "st_size", "st_mtime_ns")):
                    raise ValueError("STATE_INVALID")
        finally:
            os.close(fd)
        if len(raw) > snapshot.MAX_BYTES:
            raise ValueError("STATE_INVALID")
        state = json.loads(raw, object_pairs_hook=snapshot._unique)
        if type(state) is not dict:
            raise ValueError("STATE_INVALID")
        if type(state.get("schema_version")) is not int or state["schema_version"] != snapshot.SCHEMA:
            raise ValueError("SCHEMA_UNSUPPORTED")
        self._validate_state(state)
        return state, hashlib.sha256(raw).hexdigest()

    def _cursor(self, generation, tool, movement, offset):
        body = snapshot._json([generation, tool, movement, offset])
        return base64.urlsafe_b64encode(hmac.digest(self.cursor_key, body, "sha256") + body).decode()

    def _arguments(self, tool, arguments):
        keys = set() if tool == TOOLS[0] else {"cursor", "limit"}
        if tool == TOOLS[2]:
            keys.add("movement_id")
        if type(arguments) is not dict or set(arguments) - keys:
            raise ValueError("INVALID_ARGUMENTS")
        limit = arguments.get("limit", 100)
        movement = arguments.get("movement_id")
        if type(limit) is not int or not 1 <= limit <= 100:
            raise ValueError("INVALID_ARGUMENTS")
        if "movement_id" in arguments and not snapshot._token(movement):
            raise ValueError("INVALID_ARGUMENTS")
        cursor = arguments.get("cursor")
        page = None
        if "cursor" in arguments:
            if type(cursor) is not str or not 1 <= len(cursor) <= 1024:
                raise ValueError("INVALID_CURSOR")
            try:
                raw = base64.b64decode(cursor, altchars=b"-_", validate=True)
                signature, body = raw[:32], raw[32:]
                if not hmac.compare_digest(signature, hmac.digest(self.cursor_key, body, "sha256")):
                    raise ValueError
                page = json.loads(body)
                if (type(page) is not list or len(page) != 4
                        or type(page[0]) is not str or len(page[0]) != 64
                        or page[1:3] != [tool, movement]
                        or type(page[3]) is not int or not 0 <= page[3] <= snapshot.MAX_TRANSITIONS):
                    raise ValueError
            except (ValueError, TypeError):
                raise ValueError("INVALID_CURSOR") from None
        return limit, movement, page

    def call(self, tool, arguments, *, now=None):
        if type(tool) is not str or tool not in TOOLS:
            return _result({"error": "UNKNOWN_TOOL"}, True)
        try:
            limit, movement, page = self._arguments(tool, arguments)
        except ValueError as error:
            return _result({"error": str(error)}, True)
        try:
            state, generation = self._read()
        except FileNotFoundError:
            return _result({"error": "STATE_UNAVAILABLE", "freshness": "UNAVAILABLE"}, True)
        except PermissionError:
            return _result({"error": "STATE_UNAVAILABLE", "freshness": "UNAVAILABLE"}, True)
        except (OSError, ValueError, TypeError, KeyError, AttributeError, RecursionError) as error:
            code = "SCHEMA_UNSUPPORTED" if str(error) == "SCHEMA_UNSUPPORTED" else "STATE_INVALID"
            return _result({"error": code, "freshness": "UNKNOWN"}, True)
        if page is not None and page[0] != generation:
            return _result({"error": "CURSOR_STALE"}, True)
        now = time.time() if now is None else now
        age = _age(state["last_success"], now)
        freshness = "UNKNOWN" if age is None or _age(state["last_attempt"], now) is None else (
            "STALE" if age > 90 else "FRESH")
        status = {"schema_version": state["schema_version"], "freshness": freshness,
                  "last_check": state["last_attempt"], "last_success": state["last_success"],
                  "age_seconds": age, "coverage": state["coverage"], "errors": state["errors"],
                  "history_floor": state["history_floor"], "dropped_count": state["dropped_count"]}
        if tool == TOOLS[0]:
            return _result(status)
        if tool == TOOLS[1]:
            items = []
            for identity in sorted(state["observations"]):
                observation = state["observations"][identity]
                item = {"movement_id": identity, "observation_time": observation["observation_time"]}
                for source in snapshot.SOURCES:
                    block = observation[source]
                    data = block["data"]
                    evidence_time = (data or {}).get("event_time", block["observed_at"])
                    check_age = _age(block["checked_at"], now)
                    evidence_age = _age(evidence_time, now)
                    source_fresh = (block["status"] == "VALID" and check_age is not None and check_age <= 90
                                    and (source != "usage" or (evidence_age is not None and evidence_age <= 90)))
                    item[source] = {"status": block["status"], "checked_at": block["checked_at"],
                                    "observed_at": block["observed_at"], "data": data,
                                    "check_age_seconds": check_age,
                                    "observation_age_seconds": _age(block["observed_at"], now),
                                    "evidence_age_seconds": evidence_age,
                                    "freshness": "UNKNOWN" if check_age is None or evidence_age is None else (
                                        "FRESH" if source_fresh else "STALE")}
                items.append(item)
        else:
            items = [event for event in state["transitions"]
                     if movement is None or event["movement_id"] == movement]
        offset = page[3] if page else 0
        if offset > len(items):
            return _result({"error": "INVALID_CURSOR"}, True)
        result = {**status, "items": [], "next_cursor": None, "gap": False}
        for item in items[offset:offset + limit]:
            candidate = {**result, "items": [*result["items"], item]}
            end = offset + len(candidate["items"])
            candidate["next_cursor"] = self._cursor(generation, tool, movement, end) if end < len(items) else None
            candidate["gap"] = any(i.get("gap", False) for i in candidate["items"])
            # Include the text encoding and a worst-case bounded request ID.
            wire = {"jsonrpc": "2.0", "id": "\U0010ffff" * 128, "result": _result(candidate)}
            if len(snapshot._json(wire)) + 1 > MAX_OUTPUT:
                if not result["items"]:
                    return _result({"error": "ITEM_TOO_LARGE"}, True)
                break
            result = candidate
        return _result(result)


def tool_definitions():
    definitions = []
    for name in TOOLS:
        properties = {} if name == TOOLS[0] else {
            "cursor": {"type": "string", "minLength": 1, "maxLength": 1024},
            "limit": {"type": "integer", "minimum": 1, "maximum": 100}}
        if name == TOOLS[2]:
            properties["movement_id"] = {"type": "string", "minLength": 1, "maxLength": 128,
                                         "pattern": "^[A-Za-z0-9_.:-]+$"}
        definitions.append({"name": name, "description": "Read recorded companion facts; never grants authority.",
                            "inputSchema": {"type": "object", "properties": properties,
                                            "additionalProperties": False},
                            "annotations": {"readOnlyHint": True, "destructiveHint": False,
                                            "idempotentHint": True, "openWorldHint": False}})
    return definitions


class Server:
    def __init__(self, reader):
        self.reader = reader
        self.initialized = False
        self.ready = False

    def handle(self, raw):
        if len(raw) > MAX_REQUEST:
            return _error(None, -32600, "REQUEST_TOO_LARGE")
        try:
            request = json.loads(raw, object_pairs_hook=snapshot._unique)
        except (ValueError, UnicodeError, RecursionError):
            return _error(None, -32700, "PARSE_ERROR")
        if type(request) is not dict:
            return _error(None, -32600, "INVALID_REQUEST")
        identity = request.get("id")
        valid_id = ((type(identity) is str and len(identity) <= 128)
                    or (type(identity) is int and abs(identity) <= 2**53 - 1))
        if (request.get("jsonrpc") != "2.0" or type(request.get("method")) is not str
                or set(request) - {"jsonrpc", "id", "method", "params"}
                or ("id" in request and not valid_id)):
            return _error(identity if valid_id else None, -32600, "INVALID_REQUEST")
        method, params = request["method"], request.get("params", {})
        if "id" not in request:
            if method == "notifications/initialized" and self.initialized and params == {}:
                self.ready = True
            return None
        if type(params) is not dict:
            return _error(identity, -32602, "INVALID_PARAMS")
        if method == "initialize":
            if (self.initialized or set(params) - {"protocolVersion", "capabilities", "clientInfo", "_meta"}
                    or type(params.get("protocolVersion")) is not str
                    or type(params.get("capabilities")) is not dict
                    or type(params.get("clientInfo")) is not dict
                    or any(type(params["clientInfo"].get(k)) is not str for k in ("name", "version"))):
                return _error(identity, -32602, "INVALID_PARAMS")
            self.initialized = True
            version = params["protocolVersion"]
            result = {"protocolVersion": version if version in VERSIONS else PROTOCOL,
                      "capabilities": {"tools": {}},
                      "serverInfo": {"name": "nexus-workbench-companion", "version": "1.0.0"}}
        elif method == "ping":
            if params:
                return _error(identity, -32602, "INVALID_PARAMS")
            result = {}
        elif method not in {"tools/list", "tools/call"}:
            return _error(identity, -32601, "METHOD_NOT_FOUND")
        elif not self.ready:
            return _error(identity, -32600, "NOT_INITIALIZED")
        elif method == "tools/list":
            if params:
                return _error(identity, -32602, "INVALID_PARAMS")
            result = {"tools": tool_definitions()}
        else:
            if (set(params) - {"name", "arguments"} or type(params.get("name")) is not str
                    or type(params.get("arguments", {})) is not dict):
                return _error(identity, -32602, "INVALID_PARAMS")
            if params["name"] not in TOOLS:
                return _error(identity, -32602, "UNKNOWN_TOOL")
            result = self.reader.call(params["name"], params.get("arguments", {}))
        response = {"jsonrpc": "2.0", "id": identity, "result": result}
        if len(snapshot._json(response)) + 1 > MAX_OUTPUT:
            return _error(identity, -32603, "OUTPUT_TOO_LARGE")
        return response

    def serve(self, incoming, outgoing):
        while True:
            raw = incoming.readline(MAX_REQUEST + 1)
            if not raw:
                return
            if len(raw) > MAX_REQUEST:
                while not raw.endswith(b"\n"):
                    raw = incoming.readline(MAX_REQUEST + 1)
                    if not raw:
                        break
                response = _error(None, -32600, "REQUEST_TOO_LARGE")
            else:
                response = self.handle(raw)
            if response is not None:
                outgoing.write(snapshot._json(response) + b"\n")
                outgoing.flush()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot-directory", required=True)
    parser.add_argument("--allow", action="append", default=[], metavar="FIELD=TOKEN")
    args = parser.parse_args()
    allowlists = {}
    try:
        for entry in args.allow:
            field, value = entry.split("=", 1)
            allowlists.setdefault(field, set()).add(value)
        reader = Reader(args.snapshot_directory, allowlists=allowlists)
    except (ValueError, TypeError):
        parser.exit(2, "INVALID_CONFIGURATION\n")
    try:
        Server(reader).serve(sys.stdin.buffer, sys.stdout.buffer)
    except BrokenPipeError:
        pass


if __name__ == "__main__":
    main()
