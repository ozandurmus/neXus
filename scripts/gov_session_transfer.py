"""GOV.SESSION.1 -- agent session-boundary packet helper (protocol v2).

    py scripts/gov_session_transfer.py render   FILE|- [--out FILE]
    py scripts/gov_session_transfer.py extract  FILE|-
    py scripts/gov_session_transfer.py validate FILE|-

Full contract: docs/design/GOV_SESSION_TRANSFER_PROTOCOL.md. A packet is a
single, self-delimiting JSON object carrying the complete SESSION START /
SESSION CLOSE report (`AI_START_HERE.md`'s own schema, made structured and
machine-checkable) plus a pointer (`refs`) to the authoritative repository
files -- the packet itself is never a copy of their content and never an
authority in its own right (`AGENTS.md` "Authority hierarchy" is unaffected;
validating a packet's *structure* never proves the *truth* of its claims).

`render` reads one bare JSON object (no sentinel) from a file or stdin,
validates it completely, and only on success emits the sentinel-wrapped
packet -- nothing is written or printed on a validation failure. There is no
flag-driven path that assembles a packet piece by piece: protocol v1's
pointer-only `start`/`close` subcommands do not exist here, so there is no
way to produce a non-canonical, report-less packet with this tool.

Symmetric, direction-neutral transport (GOV.SESSION.1A correction round 1):
the exact same envelope and schema apply to a PO-to-engineer `SESSION_START`
and an engineer-to-PO `SESSION_CLOSE` alike. Valid input to `extract`/
`validate` is optional leading whitespace, one opening sentinel, the JSON
payload, one closing sentinel, and optional trailing whitespace -- nothing
else. A narrative before or after the packet is rejected, not skipped over,
so a split narrative-plus-packet handoff can never pass as valid transport.

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
PROTOCOL_VERSION = 2
MESSAGE_TYPES = ("SESSION_START", "SESSION_CLOSE")
OUTCOMES = ("DONE", "AUTOMATED_VALIDATED", "PARTIAL", "BLOCKED")
EXIT_OK, EXIT_INVALID, EXIT_USAGE = 0, 1, 2

#: `AGENTS.md` "Mandatory session start / close" movement-type list.
#: Duplicated here, not imported, so this helper stays a single,
#: dependency-free, tool-agnostic file portable outside this repository --
#: the same "an independent constant at a package boundary beats a cross-
#: boundary import" precedent this repository already uses elsewhere
#: (e.g. `console/registry_targets.py`'s own `_ELIGIBLE_STATES`).
MOVEMENT_TYPES = frozenset({
    "READ_ONLY_AUDIT", "ARCHITECTURE", "IMPLEMENTATION", "VALIDATION",
    "ROOT_CAUSE", "UI", "DOCS", "RELEASE_HANDOVER",
})

#: `utils/project_plan.py::STATUS_VALUES`, duplicated for the same reason.
STATUS_VALUES = frozenset({
    "done", "in_progress", "planned", "blocked", "deferred", "complete",
    "complete_with_followup", "automated_validated", "real_env_validated",
})

#: `AI_START_HERE.md` "SESSION START" deployment-direction vocabulary.
DEPLOYMENT_DIRECTIONS = frozenset({"local validation only", "staging-like", "production-gated"})

MERGE_STATES = frozenset({"NOT_OPENED", "OPEN", "MERGED", "CLOSED"})
CONTINUATIONS = frozenset({"SAME_SESSION", "NEW_SESSION"})


class PacketError(ValueError):
    """A packet failed an envelope or content check."""


# ---------------------------------------------------------------------------
# Schema
#
# A field spec is one of:
#   ("str",)                        non-empty string
#   ("enum", frozenset(...))        value must be a member
#   ("list_str", allow_empty: bool) list of non-empty strings
#   ("freeform_obj",)               a non-empty JSON object, shape not checked
#                                    (the one open-ended field: `baseline`,
#                                    which legitimately varies build to build)
#   ("int_or_null",)                a plain int (never bool), or null
#   ("str_or_null",)                a non-empty string, or null
#   ("obj", {field: spec, ...})     an object with exactly those fields,
#                                    nothing missing, nothing extra
#   ("validation_plan",)            GOV.ORCH.1 §3 additive union: a
#                                    non-empty list whose items are each
#                                    either a non-empty string (prose,
#                                    unchanged) or an object
#                                    {"name": str (optional), "argv": list
#                                    of non-empty str} -- a machine-
#                                    executable step the orchestrator can
#                                    run directly with shell=False.
# ---------------------------------------------------------------------------

_REASONING_SCHEMA = ("obj", {"tier": ("str",), "reason": ("str",)})

SESSION_START_REPORT_SCHEMA = ("obj", {
    "baseline": ("freeform_obj",),
    "objective": ("str",),
    "scope": ("obj", {"in": ("list_str", False), "out": ("list_str", False)}),
    "movement_type": ("enum", MOVEMENT_TYPES),
    "requirements": ("list_str", False),
    "acceptance_criteria": ("list_str", False),
    "validation_plan": ("validation_plan",),
    "invariants": ("list_str", False),
    "risks": ("list_str", True),
    "context_not_loaded": ("list_str", True),
    "recommended_reasoning": _REASONING_SCHEMA,
    "git": ("obj", {"lane": ("str",), "base": ("str",)}),
    "merge_gate": ("str",),
    "deployment_direction": ("enum", DEPLOYMENT_DIRECTIONS),
    "output_contract": ("list_str", False),
})

SESSION_CLOSE_REPORT_SCHEMA = ("obj", {
    "completed": ("list_str", False),
    "changed": ("list_str", False),
    "preserved": ("list_str", False),
    "validation": ("obj", {
        "targeted": ("str",),
        "affected": ("str",),
        "full_regression": ("str",),
        "privacy": ("str",),
        "state_consistency": ("str",),
        "diff_check": ("str",),
        "real_environment": ("str",),
    }),
    "unresolved_risks": ("list_str", True),
    "state_updates": ("list_str", True),
    "next": ("obj", {
        "movement": ("str",),
        "movement_type": ("enum", MOVEMENT_TYPES),
        "status": ("enum", STATUS_VALUES),
        "objective": ("str",),
    }),
    "recommended_reasoning": _REASONING_SCHEMA,
    "continuation": ("enum", CONTINUATIONS),
    "integration": ("obj", {
        "branch": ("str",),
        "head_sha": ("str",),
        "pr": ("int_or_null",),
        "pr_url": ("str_or_null",),
        "ci": ("str",),
        "merge_state": ("enum", MERGE_STATES),
        "merge_commit": ("str_or_null",),
        "merge_decision": ("str",),
    }),
    "effects": ("obj", {"main_py": ("str",), "ui": ("str",)}),
})

_REPORT_SCHEMA_BY_TYPE = {
    "SESSION_START": SESSION_START_REPORT_SCHEMA,
    "SESSION_CLOSE": SESSION_CLOSE_REPORT_SCHEMA,
}

_TOP_LEVEL_COMMON = {"protocol_version", "message_type", "movement", "refs", "report"}
_TOP_LEVEL_BY_TYPE = {
    "SESSION_START": _TOP_LEVEL_COMMON,
    "SESSION_CLOSE": _TOP_LEVEL_COMMON | {"outcome"},
}


def _validate_node(value: Any, spec: tuple, path: str, errors: list[str]) -> None:
    kind = spec[0]
    if kind == "str":
        if not (isinstance(value, str) and value):
            errors.append(f"{path}: must be a non-empty string")
    elif kind == "enum":
        allowed = spec[1]
        if value not in allowed:
            errors.append(f"{path}: must be one of {sorted(allowed)}")
    elif kind == "list_str":
        allow_empty = spec[1]
        if not isinstance(value, list) or not all(isinstance(x, str) and x for x in value):
            errors.append(f"{path}: must be a list of non-empty strings")
        elif not allow_empty and not value:
            errors.append(f"{path}: must be a non-empty list")
    elif kind == "validation_plan":
        if not isinstance(value, list) or not value:
            errors.append(f"{path}: must be a non-empty list")
        else:
            for i, item in enumerate(value):
                item_path = f"{path}[{i}]"
                if isinstance(item, str):
                    if not item:
                        errors.append(f"{item_path}: string item must be a non-empty string")
                elif isinstance(item, dict):
                    allowed = {"name", "argv"}
                    for field in sorted(set(item) - allowed):
                        errors.append(f"{item_path}.{field}: unknown field")
                    argv = item.get("argv")
                    if not isinstance(argv, list) or not argv or not all(
                        isinstance(a, str) and a for a in argv
                    ):
                        errors.append(f"{item_path}.argv: must be a non-empty list of non-empty strings")
                    if "name" in item and not (isinstance(item["name"], str) and item["name"]):
                        errors.append(f"{item_path}.name: must be a non-empty string")
                else:
                    errors.append(f"{item_path}: must be a string or an object")
    elif kind == "freeform_obj":
        if not isinstance(value, dict) or not value:
            errors.append(f"{path}: must be a non-empty object")
    elif kind == "int_or_null":
        if value is not None and (type(value) is not int):
            errors.append(f"{path}: must be an integer or null")
    elif kind == "str_or_null":
        if value is not None and not (isinstance(value, str) and value):
            errors.append(f"{path}: must be a non-empty string or null")
    elif kind == "obj":
        subschema = spec[1]
        if not isinstance(value, dict):
            errors.append(f"{path}: must be an object")
            return
        missing = set(subschema) - set(value)
        extra = set(value) - set(subschema)
        for field in sorted(missing):
            errors.append(f"{path}.{field}: missing required field")
        for field in sorted(extra):
            errors.append(f"{path}.{field}: unknown field")
        for field, field_spec in subschema.items():
            if field in value:
                _validate_node(value[field], field_spec, f"{path}.{field}", errors)
    else:  # pragma: no cover -- programmer error, not a data error
        raise AssertionError(f"unknown schema kind: {kind}")


def validate_fields(obj: Any) -> list[str]:
    """Return human-readable errors; empty means valid.

    Validates structure only (envelope, required/forbidden/unknown fields,
    types, closed vocabularies) -- it proves the packet is well-formed, not
    that its claims are true or its instructions authorized (module note
    above)."""
    if not isinstance(obj, dict):
        return ["packet must be a JSON object"]

    errors: list[str] = []
    version = obj.get("protocol_version")
    if type(version) is not int or version != PROTOCOL_VERSION:  # `type(...) is not int` excludes bool (a bool's type is bool, not int)
        errors.append(f"protocol_version must be {PROTOCOL_VERSION}")

    message_type = obj.get("message_type")
    if message_type not in MESSAGE_TYPES:
        errors.append(f"message_type must be one of {MESSAGE_TYPES}")

    if not isinstance(obj.get("movement"), str) or not obj.get("movement"):
        errors.append("movement is required (non-empty string)")

    _validate_node(obj.get("refs"), ("list_str", True), "refs", errors)

    if message_type in _REPORT_SCHEMA_BY_TYPE:
        allowed_keys = _TOP_LEVEL_BY_TYPE[message_type]
        for field in sorted(set(obj) - allowed_keys):
            errors.append(f"{field}: unknown top-level field")
        if "report" not in obj:
            errors.append("report: missing required field")
        else:
            _validate_node(obj["report"], _REPORT_SCHEMA_BY_TYPE[message_type], "report", errors)
        if message_type == "SESSION_CLOSE" and obj.get("outcome") not in OUTCOMES:
            errors.append(f"outcome must be one of {OUTCOMES}")
    else:
        # message_type itself is invalid -- still flag an unrecognized
        # top-level field set as best-effort, but do not attempt to pick a
        # report schema for a message type that does not exist.
        for field in sorted(set(obj) - (_TOP_LEVEL_COMMON | {"outcome"})):
            errors.append(f"{field}: unknown top-level field")

    return errors


def extract_one(text: str) -> str:
    """Return the payload text between exactly one sentinel pair.

    Correction round 1 (GOV.SESSION.1A): `text` must consist of optional
    leading whitespace, one opening sentinel line, the JSON payload, one
    closing sentinel line, and optional trailing whitespace -- and nothing
    else. This is a symmetric, direction-neutral envelope: the same rule
    applies whether the packet is a PO-to-engineer `SESSION_START` or an
    engineer-to-PO `SESSION_CLOSE`. A narrative before or after the packet
    (a heading, an explanation, chat history, another payload) is no
    longer silently skipped over -- it is rejected, the same as a missing
    or malformed envelope, so a split narrative-plus-packet handoff can
    never pass as valid transport. Rejects a missing, unmatched, or
    repeated (more than one pair's worth of) sentinel, and any
    non-whitespace content outside the sentinel pair."""
    lines = text.splitlines()
    idxs = [i for i, line in enumerate(lines) if line == SENTINEL]
    if len(idxs) < 2:
        raise PacketError(f"expected a sentinel pair, found {len(idxs)} sentinel line(s)")
    if len(idxs) > 2:
        raise PacketError(f"expected exactly one packet body, found {len(idxs)} sentinel lines")
    start, end = idxs
    if any(line.strip() for line in lines[:start]):
        raise PacketError("non-whitespace content found before the opening sentinel")
    if any(line.strip() for line in lines[end + 1:]):
        raise PacketError("non-whitespace content found after the closing sentinel")
    return "\n".join(lines[start + 1:end])


def parse_and_validate(raw_payload: str) -> tuple[dict[str, Any] | None, list[str]]:
    try:
        obj = json.loads(raw_payload)
    except json.JSONDecodeError as exc:
        return None, [f"invalid JSON: {exc}"]
    errors = validate_fields(obj)
    return (obj, []) if not errors else (None, errors)


def render(obj: dict[str, Any]) -> str:
    """Validate `obj` completely and, only on success, return the
    sentinel-wrapped canonical text. Raises `PacketError` (nothing is
    returned or printed) on any validation failure."""
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


def _cmd_render(args: argparse.Namespace) -> int:
    try:
        raw = _read_input(args.file)
    except PacketError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_USAGE
    try:
        obj = json.loads(raw)
    except json.JSONDecodeError as exc:
        print(f"error: invalid JSON: {exc}", file=sys.stderr)
        return EXIT_INVALID
    try:
        text = render(obj)
    except PacketError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_INVALID
    _write_output(text, args.out)
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

    p_render = sub.add_parser("render", help="validate and wrap one bare packet JSON object")
    p_render.add_argument("file", help="path to a bare JSON object, or - for stdin")
    p_render.add_argument("--out", default=None)
    p_render.set_defaults(func=_cmd_render)

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
