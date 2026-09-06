"""GOV.SESSION.1 -- agent session-transfer packet CLI.

    py scripts/gov_session_transfer.py start   ...    # build a SESSION_START packet
    py scripts/gov_session_transfer.py close   ...    # build a SESSION_CLOSE packet
    py scripts/gov_session_transfer.py validate FILE|-  [--repo-root DIR]
    py scripts/gov_session_transfer.py render   FILE|-
    py scripts/gov_session_transfer.py extract  FILE|-  [--packet-id ID]

Full contract: docs/design/GOV_SESSION_TRANSFER_PROTOCOL.md and
docs/reference/gov_session_transfer_packet.schema.json. This module enforces
that schema's constraints natively -- dependency-free, stdlib only
(argparse/json/dataclasses/pathlib/sys) -- and is the authority tests check
the schema document against, not the other way around.

Offline and synchronous only: no network call, no daemon/scheduler, no LLM
call, no credential or device access, no automatic Git action. `--out` is
the only way to write a file, and it never writes anywhere the caller did
not name -- a generated packet is transient and untracked by default.

Exit codes (stable, do not renumber):
    0  success
    1  payload/content invalid (schema-shape, size, or field-value violation)
    2  envelope/boundary malformed (sentinel count, ordering, or pairing)
    3  CLI usage error (bad arguments, missing file)
    4  a requested --packet-id was not found in the input
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parent.parent

SENTINEL = "<<<NEXUS_SESSION_PACKET>>>"

SUPPORTED_PROTOCOL_VERSIONS = {1}
MESSAGE_TYPES = ("SESSION_START", "SESSION_CLOSE")
SAME_OR_NEW = ("SAME", "NEW")
OUTCOMES = ("AUTOMATED_VALIDATED", "REAL_ENV_VALIDATED", "DONE", "PARTIAL", "BLOCKED")
PRIVACY_STATES = ("PASS", "FAIL", "NOT_APPLICABLE", "UNKNOWN")

MAX_PAYLOAD_BYTES = 8192
MAX_ID_LEN = 80
MAX_SHORT_TEXT = 240
MAX_LONG_TEXT = 600
MAX_LIST_ITEMS = 24
MAX_LIST_ITEM_LEN = 300

EXIT_OK = 0
EXIT_INVALID = 1
EXIT_ENVELOPE = 2
EXIT_USAGE = 3
EXIT_NOT_FOUND = 4

_ID_START = set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")
_ID_REST = _ID_START | set("_.-")


class PacketError(ValueError):
    """A packet failed a structural (envelope) or content (payload) check."""

    def __init__(self, message: str, exit_code: int = EXIT_INVALID) -> None:
        super().__init__(message)
        self.exit_code = exit_code


def _is_short_id(value: Any) -> bool:
    if not isinstance(value, str) or not value or len(value) > MAX_ID_LEN:
        return False
    if value[0] not in _ID_START:
        return False
    return all(ch in _ID_REST for ch in value)


def _is_str_bounded(value: Any, max_len: int, *, allow_empty: bool = False) -> bool:
    if not isinstance(value, str):
        return False
    if not value and not allow_empty:
        return False
    return len(value) <= max_len


def _is_ref_list(value: Any) -> bool:
    if not isinstance(value, list) or len(value) > MAX_LIST_ITEMS:
        return False
    return all(_is_str_bounded(item, MAX_LIST_ITEM_LEN) for item in value)


def _is_protocol_version(value: Any) -> bool:
    # bool is a subclass of int in Python -- exclude it explicitly so
    # protocol_version: true/false never passes as an integer.
    return type(value) is int and value in SUPPORTED_PROTOCOL_VERSIONS


# --------------------------------------------------------------------------
# Strict JSON parsing: reject duplicate keys, non-finite constants, trailing
# content, and any non-object top-level value.
# --------------------------------------------------------------------------


def _no_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    seen: set[str] = set()
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in seen:
            raise PacketError(f"duplicate JSON key: {key!r}")
        seen.add(key)
        result[key] = value
    return result


def _reject_non_finite(token: str) -> Any:
    raise PacketError(f"non-finite JSON constant is not permitted: {token}")


def strict_json_object(raw: str) -> dict[str, Any]:
    """Parse `raw` as exactly one strict JSON object, or raise PacketError."""
    stripped = raw.strip()
    if not stripped:
        raise PacketError("empty payload")
    decoder = json.JSONDecoder(object_pairs_hook=_no_duplicate_keys, parse_constant=_reject_non_finite)
    try:
        obj, end = decoder.raw_decode(stripped)
    except PacketError:
        raise
    except ValueError as exc:
        raise PacketError(f"malformed JSON: {exc}") from exc
    remainder = stripped[end:]
    if remainder.strip():
        raise PacketError("trailing content after the JSON value")
    if not isinstance(obj, dict):
        raise PacketError("payload must be a JSON object")
    return obj


def check_size_and_sentinel(raw: str) -> None:
    size = len(raw.encode("utf-8"))
    if size > MAX_PAYLOAD_BYTES:
        raise PacketError(f"payload exceeds the {MAX_PAYLOAD_BYTES}-byte ceiling ({size} bytes)")
    if SENTINEL in raw:
        raise PacketError("the sentinel line must not appear inside the payload")


# --------------------------------------------------------------------------
# Field-level (schema-shape) validation.
# --------------------------------------------------------------------------

_START_REQUIRED = (
    "protocol_version", "packet_id", "message_type", "project", "movement",
    "session_mode", "objective", "authority_refs", "allow", "deny",
    "stop_on", "close_required",
)
_START_OPTIONAL = ("model_hint",)
_START_ALLOWED = set(_START_REQUIRED) | set(_START_OPTIONAL)

_CLOSE_REQUIRED = (
    "protocol_version", "packet_id", "message_type", "project", "movement",
    "outcome", "changed", "preserved", "validation", "privacy_state",
    "refs", "risks", "durable_state", "next_movement", "session_routing",
    "ui_effect",
)
_CLOSE_OPTIONAL = ("model_hint",)
_CLOSE_ALLOWED = set(_CLOSE_REQUIRED) | set(_CLOSE_OPTIONAL)


def validate_fields(obj: dict[str, Any]) -> list[str]:
    """Return a list of human-readable errors; empty means valid."""
    errors: list[str] = []

    message_type = obj.get("message_type")
    if message_type not in MESSAGE_TYPES:
        return [f"message_type must be one of {MESSAGE_TYPES}, got {message_type!r}"]

    if not _is_protocol_version(obj.get("protocol_version")):
        errors.append(f"protocol_version must be one of {sorted(SUPPORTED_PROTOCOL_VERSIONS)} (an integer)")
    if not _is_short_id(obj.get("packet_id")):
        errors.append("packet_id must be a non-empty identifier <= 80 chars, matching ^[A-Za-z0-9][A-Za-z0-9_.-]*$")
    if not _is_short_id(obj.get("project")):
        errors.append("project must be a non-empty identifier <= 80 chars")
    if not _is_short_id(obj.get("movement")):
        errors.append("movement must be a non-empty identifier <= 80 chars")

    required = _START_REQUIRED if message_type == "SESSION_START" else _CLOSE_REQUIRED
    allowed = _START_ALLOWED if message_type == "SESSION_START" else _CLOSE_ALLOWED

    for key in required:
        if key not in obj:
            errors.append(f"missing required field: {key}")

    for key in obj:
        if key not in allowed:
            errors.append(f"unexpected field not permitted by the schema: {key}")

    if message_type == "SESSION_START":
        errors.extend(_validate_start_fields(obj))
    else:
        errors.extend(_validate_close_fields(obj))

    return errors


def _validate_start_fields(obj: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if "session_mode" in obj and obj["session_mode"] not in SAME_OR_NEW:
        errors.append(f"session_mode must be one of {SAME_OR_NEW}")
    if "objective" in obj and not _is_str_bounded(obj["objective"], MAX_LONG_TEXT):
        errors.append(f"objective must be a non-empty string <= {MAX_LONG_TEXT} chars")
    for key in ("authority_refs", "allow", "deny", "stop_on"):
        if key in obj and not _is_ref_list(obj[key]):
            errors.append(f"{key} must be a list of <= {MAX_LIST_ITEMS} strings, each <= {MAX_LIST_ITEM_LEN} chars")
    if "close_required" in obj and not isinstance(obj["close_required"], bool):
        errors.append("close_required must be a boolean")
    if "model_hint" in obj and not _is_str_bounded(obj["model_hint"], MAX_SHORT_TEXT):
        errors.append(f"model_hint must be a string <= {MAX_SHORT_TEXT} chars")
    return errors


def _validate_close_fields(obj: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if "outcome" in obj and obj["outcome"] not in OUTCOMES:
        errors.append(f"outcome must be one of {OUTCOMES}")
    if "privacy_state" in obj and obj["privacy_state"] not in PRIVACY_STATES:
        errors.append(f"privacy_state must be one of {PRIVACY_STATES}")
    if "session_routing" in obj and obj["session_routing"] not in SAME_OR_NEW:
        errors.append(f"session_routing must be one of {SAME_OR_NEW}")
    if "validation" in obj and not _is_str_bounded(obj["validation"], MAX_LONG_TEXT):
        errors.append(f"validation must be a non-empty string <= {MAX_LONG_TEXT} chars")
    if "ui_effect" in obj and not _is_str_bounded(obj["ui_effect"], MAX_SHORT_TEXT):
        errors.append(f"ui_effect must be a non-empty string <= {MAX_SHORT_TEXT} chars")
    if "next_movement" in obj:
        nm = obj["next_movement"]
        if nm is not None and not _is_str_bounded(nm, MAX_ID_LEN):
            errors.append("next_movement must be a string <= 80 chars, or null")
    for key in ("changed", "preserved", "refs", "risks", "durable_state"):
        if key in obj and not _is_ref_list(obj[key]):
            errors.append(f"{key} must be a list of <= {MAX_LIST_ITEMS} strings, each <= {MAX_LIST_ITEM_LEN} chars")
    if "model_hint" in obj and not _is_str_bounded(obj["model_hint"], MAX_SHORT_TEXT):
        errors.append(f"model_hint must be a string <= {MAX_SHORT_TEXT} chars")
    return errors


def parse_and_validate(raw_payload: str) -> tuple[dict[str, Any] | None, list[str]]:
    """Parse+validate one payload. Returns (obj, []) or (None, errors)."""
    try:
        check_size_and_sentinel(raw_payload)
        obj = strict_json_object(raw_payload)
    except PacketError as exc:
        return None, [str(exc)]
    errors = validate_fields(obj)
    return (obj, []) if not errors else (None, errors)


def check_authority_refs(obj: dict[str, Any], repo_root: Path) -> list[str]:
    """Existence-only check (never content) for authority_refs / refs paths."""
    errors: list[str] = []
    for key in ("authority_refs", "refs"):
        for rel in obj.get(key, []) or []:
            if not (repo_root / rel).exists():
                errors.append(f"{key} path does not exist under {repo_root}: {rel}")
    return errors


# --------------------------------------------------------------------------
# Envelope: standalone split and transcript extraction.
# --------------------------------------------------------------------------


def split_standalone(text: str) -> str:
    """Return the payload text between exactly one sentinel pair, or raise."""
    lines = text.splitlines()
    sentinel_idxs = [i for i, line in enumerate(lines) if line == SENTINEL]
    if len(sentinel_idxs) != 2:
        raise PacketError(
            f"standalone input must contain exactly 2 sentinel lines, found {len(sentinel_idxs)}",
            EXIT_ENVELOPE,
        )
    start, end = sentinel_idxs
    before = "\n".join(lines[:start])
    after = "\n".join(lines[end + 1:])
    if before.strip() or after.strip():
        raise PacketError("unexpected content outside the sentinel pair", EXIT_ENVELOPE)
    return "\n".join(lines[start + 1:end])


@dataclass
class ExtractedPacket:
    index: int
    start_line: int
    end_line: int
    valid: bool
    errors: list[str] = field(default_factory=list)
    payload: dict[str, Any] | None = None

    @property
    def packet_id(self) -> str | None:
        return self.payload.get("packet_id") if self.payload else None

    def to_json(self) -> dict[str, Any]:
        return {
            "index": self.index,
            "start_line": self.start_line,
            "end_line": self.end_line,
            "valid": self.valid,
            "errors": self.errors,
            "packet_id": self.packet_id,
            "payload": self.payload,
        }


def extract_all(text: str) -> list[ExtractedPacket]:
    """Scan `text` for non-overlapping sentinel pairs, paired strictly in
    sequence (1st+2nd, 3rd+4th, ...). Each pair is validated independently.
    An odd total sentinel-line count fails the whole scan closed -- it
    cannot form complete pairs. Two adjacent sentinel lines with no payload
    line between them are an empty pair, which fails only that pair.
    """
    lines = text.splitlines()
    sentinel_idxs = [i for i, line in enumerate(lines) if line == SENTINEL]
    if len(sentinel_idxs) % 2 != 0:
        raise PacketError(
            f"odd sentinel-line count ({len(sentinel_idxs)}); cannot pair boundaries",
            EXIT_ENVELOPE,
        )
    results: list[ExtractedPacket] = []
    for pair_index, n in enumerate(range(0, len(sentinel_idxs), 2)):
        start, end = sentinel_idxs[n], sentinel_idxs[n + 1]
        if end == start + 1:
            results.append(ExtractedPacket(pair_index, start, end, False, ["empty pair: no payload between sentinel lines"]))
            continue
        raw_payload = "\n".join(lines[start + 1:end])
        obj, errors = parse_and_validate(raw_payload)
        results.append(ExtractedPacket(pair_index, start, end, obj is not None, errors, obj))
    return results


def render_packet(obj: dict[str, Any]) -> str:
    """Validate `obj`, then render it wrapped in the sentinel, deterministically."""
    errors = validate_fields(obj)
    if errors:
        raise PacketError("; ".join(errors))
    body = json.dumps(obj, sort_keys=True, indent=2, ensure_ascii=False)
    check_size_and_sentinel(body)
    return f"{SENTINEL}\n{body}\n{SENTINEL}\n"


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def _read_input(path: str) -> str:
    if path == "-":
        return sys.stdin.read()
    p = Path(path)
    if not p.is_file():
        raise PacketError(f"no such file: {path}", EXIT_USAGE)
    return p.read_text(encoding="utf-8")


def _write_output(text: str, out: str | None) -> None:
    if out:
        Path(out).write_text(text, encoding="utf-8")
        print(f"wrote {out} (not tracked automatically)", file=sys.stderr)
    else:
        sys.stdout.write(text)


def _cmd_start(args: argparse.Namespace) -> int:
    obj = {
        "protocol_version": 1,
        "packet_id": args.packet_id,
        "message_type": "SESSION_START",
        "project": args.project,
        "movement": args.movement,
        "session_mode": args.session_mode,
        "objective": args.objective,
        "authority_refs": args.authority_ref,
        "allow": args.allow,
        "deny": args.deny,
        "stop_on": args.stop_on,
        "close_required": args.close_required,
    }
    if args.model_hint:
        obj["model_hint"] = args.model_hint
    return _emit(obj, args.out)


def _cmd_close(args: argparse.Namespace) -> int:
    obj = {
        "protocol_version": 1,
        "packet_id": args.packet_id,
        "message_type": "SESSION_CLOSE",
        "project": args.project,
        "movement": args.movement,
        "outcome": args.outcome,
        "changed": args.changed,
        "preserved": args.preserved,
        "validation": args.validation,
        "privacy_state": args.privacy_state,
        "refs": args.ref,
        "risks": args.risk,
        "durable_state": args.durable_state,
        "next_movement": args.next_movement,
        "session_routing": args.session_routing,
        "ui_effect": args.ui_effect,
    }
    if args.model_hint:
        obj["model_hint"] = args.model_hint
    return _emit(obj, args.out)


def _emit(obj: dict[str, Any], out: str | None) -> int:
    try:
        text = render_packet(obj)
    except PacketError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return exc.exit_code
    _write_output(text, out)
    return EXIT_OK


def _cmd_validate(args: argparse.Namespace) -> int:
    try:
        text = _read_input(args.file)
    except PacketError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return exc.exit_code
    try:
        raw_payload = split_standalone(text)
    except PacketError as exc:
        print(json.dumps({"valid": False, "errors": [str(exc)]}))
        return exc.exit_code
    obj, errors = parse_and_validate(raw_payload)
    if obj and args.repo_root:
        errors = check_authority_refs(obj, Path(args.repo_root))
        obj = obj if not errors else None
    print(json.dumps({"valid": obj is not None, "errors": errors}, sort_keys=True))
    return EXIT_OK if obj is not None else EXIT_INVALID


def _cmd_render(args: argparse.Namespace) -> int:
    try:
        text = _read_input(args.file)
        obj = strict_json_object(text)
        rendered = render_packet(obj)
    except PacketError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return exc.exit_code
    _write_output(rendered, args.out)
    return EXIT_OK


def _cmd_extract(args: argparse.Namespace) -> int:
    try:
        text = _read_input(args.file)
        results = extract_all(text)
    except PacketError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return exc.exit_code

    if args.packet_id is not None:
        match = next((r for r in results if r.packet_id == args.packet_id), None)
        if match is None:
            print(f"error: no packet with packet_id={args.packet_id!r} found", file=sys.stderr)
            return EXIT_NOT_FOUND
        print(json.dumps(match.to_json(), sort_keys=True))
        return EXIT_OK if match.valid else EXIT_INVALID

    print(json.dumps([r.to_json() for r in results], sort_keys=True))
    if not results:
        return EXIT_OK
    return EXIT_OK if any(r.valid for r in results) else EXIT_INVALID


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="gov_session_transfer", description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    p_start = sub.add_parser("start", help="build a SESSION_START packet")
    p_start.add_argument("--packet-id", required=True)
    p_start.add_argument("--project", default="neXus")
    p_start.add_argument("--movement", required=True)
    p_start.add_argument("--session-mode", choices=SAME_OR_NEW, default="NEW")
    p_start.add_argument("--objective", required=True)
    p_start.add_argument("--authority-ref", action="append", default=[])
    p_start.add_argument("--allow", action="append", default=[])
    p_start.add_argument("--deny", action="append", default=[])
    p_start.add_argument("--stop-on", action="append", default=[])
    p_start.add_argument("--close-required", dest="close_required", action="store_true", default=True)
    p_start.add_argument("--no-close-required", dest="close_required", action="store_false")
    p_start.add_argument("--model-hint", default=None)
    p_start.add_argument("--out", default=None)
    p_start.set_defaults(func=_cmd_start)

    p_close = sub.add_parser("close", help="build a SESSION_CLOSE packet")
    p_close.add_argument("--packet-id", required=True)
    p_close.add_argument("--project", default="neXus")
    p_close.add_argument("--movement", required=True)
    p_close.add_argument("--outcome", required=True, choices=OUTCOMES)
    p_close.add_argument("--changed", action="append", default=[])
    p_close.add_argument("--preserved", action="append", default=[])
    p_close.add_argument("--validation", required=True)
    p_close.add_argument("--privacy-state", required=True, choices=PRIVACY_STATES)
    p_close.add_argument("--ref", action="append", default=[])
    p_close.add_argument("--risk", action="append", default=[])
    p_close.add_argument("--durable-state", action="append", default=[])
    p_close.add_argument("--next-movement", default=None)
    p_close.add_argument("--session-routing", required=True, choices=SAME_OR_NEW)
    p_close.add_argument("--ui-effect", required=True)
    p_close.add_argument("--model-hint", default=None)
    p_close.add_argument("--out", default=None)
    p_close.set_defaults(func=_cmd_close)

    p_validate = sub.add_parser("validate", help="validate one standalone packet")
    p_validate.add_argument("file", help="path, or - for stdin")
    p_validate.add_argument("--repo-root", default=None, help="check authority_refs/refs exist under this directory")
    p_validate.set_defaults(func=_cmd_validate)

    p_render = sub.add_parser("render", help="wrap a bare JSON object in the sentinel envelope")
    p_render.add_argument("file", help="path, or - for stdin")
    p_render.add_argument("--out", default=None)
    p_render.set_defaults(func=_cmd_render)

    p_extract = sub.add_parser("extract", help="extract packet(s) from a larger transcript")
    p_extract.add_argument("file", help="path, or - for stdin")
    p_extract.add_argument("--packet-id", default=None)
    p_extract.set_defaults(func=_cmd_extract)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
