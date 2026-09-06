"""GOV.SESSION.1 -- minimal agent session-boundary packet helper.

    py scripts/gov_session_transfer.py start   --movement ID --ref PATH [--ref PATH ...]
    py scripts/gov_session_transfer.py close   --movement ID --outcome OUTCOME --ref PATH [--ref PATH ...]
    py scripts/gov_session_transfer.py extract  FILE|-
    py scripts/gov_session_transfer.py validate FILE|-

Full contract: docs/design/GOV_SESSION_TRANSFER_PROTOCOL.md. A packet is a
pointer to authoritative repository files -- never a copy of their content,
never itself an authority (AGENTS.md "Authority hierarchy" is unaffected).

Stdlib only. Offline, synchronous, no network, no daemon, no credential or
device access. `--out` is the only way to write a file, and only to the
path given.

Exit codes: 0 success, 1 invalid packet (envelope or content), 2 usage
error (bad arguments, missing file).
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

SENTINEL = "<<<NEXUS_SESSION_PACKET>>>"
PROTOCOL_VERSION = 1
MESSAGE_TYPES = ("SESSION_START", "SESSION_CLOSE")
OUTCOMES = ("DONE", "AUTOMATED_VALIDATED", "PARTIAL", "BLOCKED")
EXIT_OK, EXIT_INVALID, EXIT_USAGE = 0, 1, 2


class PacketError(ValueError):
    """A packet failed an envelope or content check."""


def build_packet(message_type: str, movement: str, refs: list[str], outcome: str | None = None) -> dict[str, Any]:
    obj = {"protocol_version": PROTOCOL_VERSION, "message_type": message_type, "movement": movement, "refs": refs}
    if outcome is not None:
        obj["outcome"] = outcome
    return obj


def validate_fields(obj: Any) -> list[str]:
    """Return human-readable errors; empty means valid."""
    if not isinstance(obj, dict):
        return ["packet must be a JSON object"]
    errors: list[str] = []
    version = obj.get("protocol_version")
    if type(version) is not int or version != PROTOCOL_VERSION:  # bool is an int subclass; exclude it
        errors.append(f"protocol_version must be {PROTOCOL_VERSION}")
    message_type = obj.get("message_type")
    if message_type not in MESSAGE_TYPES:
        errors.append(f"message_type must be one of {MESSAGE_TYPES}")
    if not isinstance(obj.get("movement"), str) or not obj.get("movement"):
        errors.append("movement is required (non-empty string)")
    refs = obj.get("refs")
    if not isinstance(refs, list) or not all(isinstance(r, str) and r for r in refs):
        errors.append("refs is required (list of non-empty strings)")
    if message_type == "SESSION_CLOSE" and obj.get("outcome") not in OUTCOMES:
        errors.append(f"outcome must be one of {OUTCOMES}")
    return errors


def extract_one(text: str) -> str:
    """Return the payload text between exactly one sentinel pair found
    anywhere in `text`, ignoring surrounding content. Rejects a missing,
    unmatched, or repeated (more than one pair's worth of) sentinel."""
    lines = text.splitlines()
    idxs = [i for i, line in enumerate(lines) if line == SENTINEL]
    if len(idxs) < 2:
        raise PacketError(f"expected a sentinel pair, found {len(idxs)} sentinel line(s)")
    if len(idxs) > 2:
        raise PacketError(f"expected exactly one packet body, found {len(idxs)} sentinel lines")
    start, end = idxs
    return "\n".join(lines[start + 1:end])


def parse_and_validate(raw_payload: str) -> tuple[dict[str, Any] | None, list[str]]:
    try:
        obj = json.loads(raw_payload)
    except json.JSONDecodeError as exc:
        return None, [f"invalid JSON: {exc}"]
    errors = validate_fields(obj)
    return (obj, []) if not errors else (None, errors)


def render(obj: dict[str, Any]) -> str:
    errors = validate_fields(obj)
    if errors:
        raise PacketError("; ".join(errors))
    body = json.dumps(obj, sort_keys=True, indent=2)
    return f"{SENTINEL}\n{body}\n{SENTINEL}\n"


def _read_input(path: str) -> str:
    if path == "-":
        return sys.stdin.read()
    p = Path(path)
    if not p.is_file():
        raise PacketError(f"no such file: {path}")
    return p.read_text(encoding="utf-8")


def _write_output(text: str, out: str | None) -> None:
    if out:
        Path(out).write_text(text, encoding="utf-8")
        print(f"wrote {out} (not tracked automatically)", file=sys.stderr)
    else:
        sys.stdout.write(text)


def _cmd_start(args: argparse.Namespace) -> int:
    return _emit(build_packet("SESSION_START", args.movement, args.ref), args.out)


def _cmd_close(args: argparse.Namespace) -> int:
    return _emit(build_packet("SESSION_CLOSE", args.movement, args.ref, args.outcome), args.out)


def _emit(obj: dict[str, Any], out: str | None) -> int:
    try:
        text = render(obj)
    except PacketError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_INVALID
    _write_output(text, out)
    return EXIT_OK


def _cmd_extract(args: argparse.Namespace) -> int:
    try:
        raw_payload = extract_one(_read_input(args.file))
    except PacketError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_USAGE if str(exc).startswith("no such file") else EXIT_INVALID
    obj, errors = parse_and_validate(raw_payload)
    if obj is None:
        print(f"error: {'; '.join(errors)}", file=sys.stderr)
        return EXIT_INVALID
    print(json.dumps(obj, sort_keys=True))
    return EXIT_OK


def _cmd_validate(args: argparse.Namespace) -> int:
    try:
        raw_payload = extract_one(_read_input(args.file))
    except PacketError as exc:
        if str(exc).startswith("no such file"):
            print(f"error: {exc}", file=sys.stderr)
            return EXIT_USAGE
        print(json.dumps({"valid": False, "errors": [str(exc)]}))
        return EXIT_INVALID
    obj, errors = parse_and_validate(raw_payload)
    print(json.dumps({"valid": obj is not None, "errors": errors}, sort_keys=True))
    return EXIT_OK if obj is not None else EXIT_INVALID


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="gov_session_transfer", description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    p_start = sub.add_parser("start", help="emit a SESSION_START packet")
    p_start.add_argument("--movement", required=True)
    p_start.add_argument("--ref", action="append", required=True, dest="ref")
    p_start.add_argument("--out", default=None)
    p_start.set_defaults(func=_cmd_start)

    p_close = sub.add_parser("close", help="emit a SESSION_CLOSE packet")
    p_close.add_argument("--movement", required=True)
    p_close.add_argument("--outcome", required=True, choices=OUTCOMES)
    p_close.add_argument("--ref", action="append", required=True, dest="ref")
    p_close.add_argument("--out", default=None)
    p_close.set_defaults(func=_cmd_close)

    p_extract = sub.add_parser("extract", help="extract one packet from surrounding text")
    p_extract.add_argument("file", help="path, or - for stdin")
    p_extract.set_defaults(func=_cmd_extract)

    p_validate = sub.add_parser("validate", help="validate one packet")
    p_validate.add_argument("file", help="path, or - for stdin")
    p_validate.set_defaults(func=_cmd_validate)

    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
