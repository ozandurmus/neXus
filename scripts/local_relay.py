"""GOV.PO.1 -- local, file-based relay transport (stdlib only).

    py scripts/local_relay.py create   --role {po|engineer} --start FILE|- [--dir relay] [--slug SLUG]
    py scripts/local_relay.py append   --file FILE --role {po|engineer} --marker MARKER [...]
    py scripts/local_relay.py status   --file FILE
    py scripts/local_relay.py validate --file FILE

Full contract: docs/design/LOCAL_RELAY_PROTOCOL.md (DRAFT). This is an
ADDITIONAL transport for same-machine, same-checkout Product-Owner/engineer
coordination -- it does not amend or replace
docs/design/NEXUS_AGENT_RELAY_PROTOCOL.md or the GitHub-based
`RELAY_READY owner/repository#issue` workflow, which stay exactly as they
are for cross-machine or human-external-visibility work.

One tracked JSON file per movement under `relay/`
(`relay/NXS-LOCAL-<4-digit-id>-<slug>.json`), an append-only `entries`
array reusing `NEXUS_AGENT_RELAY_PROTOCOL.md`'s five markers
(`RELAY_ACK`/`RELAY_NOTE`/`RELAY_QUESTION`/`RELAY_DECISION`/
`RELAY_CORRECTION`) plus `GOV.SESSION.1`'s two packet types
(`SESSION_START`/`SESSION_CLOSE`), and a top-level `next_actor` field
(`"po"`/`"engineer"`/`null`) enforcing single-writer-at-a-time turn
ownership: `append --role <r>` is rejected unless `<r>` equals the file's
current `next_actor`.

This module imports `scripts/gov_session_transfer.py` (same directory) to
validate `SESSION_START`/`SESSION_CLOSE` `report` objects against its
existing, unchanged schema (`_REPORT_SCHEMA_BY_TYPE`, `_validate_node`,
`OUTCOMES`) -- reused directly, never re-derived, so the two transports can
never drift into two different ideas of what a valid report looks like.

Offline, synchronous. No network, daemon, credential, or device access.
Exit codes: 0 success, 1 invalid/rejected (schema violation, wrong-turn
append, concurrent-write conflict, append to a CLOSED movement), 2 usage
error (bad arguments, missing file) -- the same convention
`gov_session_transfer.py` uses.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gov_session_transfer as gst  # noqa: E402

EXIT_OK, EXIT_INVALID, EXIT_USAGE = 0, 1, 2

SCHEMA_VERSION = 1
ID_PREFIX = "NXS-LOCAL-"
ID_RE = re.compile(r"^NXS-LOCAL-(\d{4})$")
FILENAME_RE = re.compile(r"^(NXS-LOCAL-\d{4})-[a-z0-9-]+\.json$")

ROLES = ("po", "engineer")
STATUSES = ("OPEN", "IN_PROGRESS", "AWAITING_PO", "AWAITING_ENGINEER", "CLOSED")

#: The two GOV.SESSION.1 packet types plus NEXUS_AGENT_RELAY_PROTOCOL.md's
#: five intermediate markers -- seven total, closed, reused verbatim.
RELAY_MARKERS = ("RELAY_ACK", "RELAY_NOTE", "RELAY_QUESTION", "RELAY_DECISION", "RELAY_CORRECTION")
MARKERS = ("SESSION_START", "SESSION_CLOSE") + RELAY_MARKERS
#: Every marker except SESSION_START may be appended -- SESSION_START only
#: ever exists as entries[0], written by `create`.
APPENDABLE_MARKERS = ("SESSION_CLOSE",) + RELAY_MARKERS
#: Markers whose only required fields are `subject`/`text` (RELAY_DECISION
#: additionally requires authorized_by/scope/supersedes; SESSION_CLOSE
#: requires report/outcome instead).
NARRATIVE_MARKERS = ("RELAY_ACK", "RELAY_NOTE", "RELAY_QUESTION", "RELAY_CORRECTION")

_COMMON_ENTRY_FIELDS = {
    "marker": ("enum", frozenset(MARKERS)),
    "actor": ("enum", frozenset(ROLES)),
    "timestamp": ("str",),
}


def _entry_schema(extra: dict) -> tuple:
    return ("obj", {**_COMMON_ENTRY_FIELDS, **extra})


#: One closed-shape schema per marker (gov_session_transfer.py's own generic
#: tuple-based schema kinds, reused directly -- see module docstring).
ENTRY_SCHEMA_BY_MARKER = {
    "SESSION_START": _entry_schema({"report": gst.SESSION_START_REPORT_SCHEMA}),
    "SESSION_CLOSE": _entry_schema({"report": gst.SESSION_CLOSE_REPORT_SCHEMA, "outcome": ("enum", gst.OUTCOMES)}),
    "RELAY_DECISION": _entry_schema({
        "subject": ("str",), "text": ("str",),
        "authorized_by": ("str",), "scope": ("str",), "supersedes": ("str",),
    }),
}
for _marker in ("RELAY_ACK", "RELAY_NOTE", "RELAY_QUESTION", "RELAY_CORRECTION"):
    ENTRY_SCHEMA_BY_MARKER[_marker] = _entry_schema({"subject": ("str",), "text": ("str",)})

TOP_LEVEL_FIELDS = {
    "schema_version", "id", "movement", "refs", "status", "next_actor",
    "created_at", "updated_at", "entries",
}


class LocalRelayError(ValueError):
    """A CLI-level request could not be satisfied (bad input, wrong turn,
    a closed movement, or a concurrent-write conflict)."""


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _slugify(text: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", text.strip().lower()).strip("-")
    return (slug or "movement")[:40]


# ---------------------------------------------------------------------------
# Structural validation (pure -- no filesystem access)
# ---------------------------------------------------------------------------

def _validate_entry(entry: Any, index: int, errors: list[str]) -> None:
    path = f"entries[{index}]"
    if not isinstance(entry, dict):
        errors.append(f"{path}: must be an object")
        return
    marker = entry.get("marker")
    if marker not in ENTRY_SCHEMA_BY_MARKER:
        errors.append(f"{path}.marker: must be one of {sorted(ENTRY_SCHEMA_BY_MARKER)}")
        return
    working = dict(entry)
    seq = working.pop("seq", None)
    good_to_go = working.pop("good_to_go", None)
    if seq != index + 1 or isinstance(seq, bool) or not isinstance(seq, int):
        errors.append(f"{path}.seq: must equal {index + 1}")
    if good_to_go is not None and not isinstance(good_to_go, bool):
        errors.append(f"{path}.good_to_go: must be a boolean")
    gst._validate_node(working, ENTRY_SCHEMA_BY_MARKER[marker], path, errors)


def _validate_shape_and_order(entries: list, errors: list[str]) -> None:
    if not entries:
        errors.append("entries: must be a non-empty list")
        return
    if not (isinstance(entries[0], dict) and entries[0].get("marker") == "SESSION_START"):
        errors.append("entries[0].marker: must be SESSION_START")
    starts = sum(1 for e in entries if isinstance(e, dict) and e.get("marker") == "SESSION_START")
    if starts != 1:
        errors.append(f"entries: exactly one SESSION_START required, found {starts}")
    for i, entry in enumerate(entries):
        if isinstance(entry, dict) and entry.get("marker") == "SESSION_START" and i != 0:
            errors.append(f"entries[{i}]: SESSION_START may only appear at position 0")
        if isinstance(entry, dict) and entry.get("marker") == "SESSION_CLOSE" and i != len(entries) - 1:
            errors.append(f"entries[{i}]: SESSION_CLOSE may only be the last entry")
    closes = sum(1 for e in entries if isinstance(e, dict) and e.get("marker") == "SESSION_CLOSE")
    if closes > 1:
        errors.append(f"entries: at most one SESSION_CLOSE allowed, found {closes}")


def _validate_turn_state(obj: dict, errors: list[str]) -> None:
    entries = obj.get("entries")
    if not isinstance(entries, list) or not entries or not isinstance(entries[-1], dict):
        return  # already reported by _validate_shape_and_order
    last = entries[-1]
    next_actor = obj.get("next_actor")
    status = obj.get("status")
    closed = last.get("marker") == "SESSION_CLOSE" or next_actor is None
    if closed:
        if next_actor is not None:
            errors.append("next_actor: must be null once the movement is closed")
        if status != "CLOSED":
            errors.append(f"status: must be CLOSED when next_actor is null, got {status!r}")
        return
    if next_actor not in ROLES:
        errors.append(f"next_actor: must be 'po', 'engineer', or null when CLOSED, got {next_actor!r}")
        return
    if len(entries) == 1:
        if status != "OPEN":
            errors.append(f"status: must be OPEN with only the SESSION_START entry present, got {status!r}")
        return
    expected = "AWAITING_PO" if next_actor == "po" else "AWAITING_ENGINEER"
    if status not in (expected, "IN_PROGRESS"):
        errors.append(f"status: must be {expected} or IN_PROGRESS when next_actor is {next_actor!r}, got {status!r}")


def validate_relay_object(obj: Any) -> list[str]:
    """Structural + internal-consistency validation of one relay file's
    already-parsed JSON object. Pure -- takes no filesystem path, proves
    shape and internal consistency, never that a filename matches `id`
    (the CLI layer checks that separately, where it has a real path)."""
    if not isinstance(obj, dict):
        return ["must be a JSON object"]

    errors: list[str] = []
    for field in sorted(set(obj) - TOP_LEVEL_FIELDS):
        errors.append(f"{field}: unknown top-level field")
    missing = TOP_LEVEL_FIELDS - set(obj)
    for field in sorted(missing):
        errors.append(f"{field}: missing required field")

    # Only type-check a field that is actually present -- an absent field
    # was already reported above; re-checking its (nonexistent) type would
    # just double-report the same defect under two messages.
    if "schema_version" in obj and obj["schema_version"] != SCHEMA_VERSION:
        errors.append(f"schema_version: must be {SCHEMA_VERSION}")
    if "id" in obj and not (isinstance(obj["id"], str) and ID_RE.match(obj["id"])):
        errors.append(f"id: must match {ID_RE.pattern}")
    if "movement" in obj and not (isinstance(obj["movement"], str) and obj["movement"]):
        errors.append("movement: must be a non-empty string")
    if "refs" in obj:
        gst._validate_node(obj["refs"], ("list_str", True), "refs", errors)
    if "status" in obj and obj["status"] not in STATUSES:
        errors.append(f"status: must be one of {STATUSES}")
    for field in ("created_at", "updated_at"):
        if field in obj and not (isinstance(obj[field], str) and obj[field]):
            errors.append(f"{field}: must be a non-empty string")

    if "entries" in obj:
        entries = obj["entries"]
        if not isinstance(entries, list):
            errors.append("entries: must be a list")
        else:
            _validate_shape_and_order(entries, errors)
            for i, entry in enumerate(entries):
                _validate_entry(entry, i, errors)

    if not ({"next_actor", "status", "entries"} & missing):
        _validate_turn_state(obj, errors)
    return errors


def _id_matches_filename(obj_id: str, path: Path) -> bool:
    match = FILENAME_RE.match(path.name)
    return bool(match) and match.group(1) == obj_id


# ---------------------------------------------------------------------------
# Filesystem helpers
# ---------------------------------------------------------------------------

def _next_id(relay_dir: Path) -> int:
    if not relay_dir.is_dir():
        return 1
    best = 0
    for candidate in relay_dir.glob(f"{ID_PREFIX}*.json"):
        match = re.match(r"^NXS-LOCAL-(\d{4})-", candidate.name)
        if match:
            best = max(best, int(match.group(1)))
    return best + 1


def _dump(obj: dict) -> str:
    return json.dumps(obj, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def _atomic_write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(dir=str(path.parent), prefix=f".{path.name}.", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(text)
        os.replace(tmp_name, path)
    except BaseException:
        try:
            os.unlink(tmp_name)
        except OSError:
            pass
        raise


def _load_json_file(path: Path) -> Any:
    if not path.is_file():
        raise LocalRelayError(f"no such file: {path}")
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise LocalRelayError(f"invalid JSON: {exc}") from exc


# ---------------------------------------------------------------------------
# create
# ---------------------------------------------------------------------------

def _cmd_create(args: argparse.Namespace) -> int:
    try:
        raw = gst._read_input(args.start)
    except gst.PacketError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_USAGE
    try:
        start_obj = json.loads(raw)
    except json.JSONDecodeError as exc:
        print(f"error: invalid JSON: {exc}", file=sys.stderr)
        return EXIT_INVALID
    if start_obj.get("message_type") is None:
        start_obj = dict(start_obj)
        start_obj.setdefault("protocol_version", 2)
        start_obj.setdefault("message_type", "SESSION_START")
    errors = gst.validate_fields(start_obj)
    if start_obj.get("message_type") != "SESSION_START":
        errors.append("message_type: must be SESSION_START (local relay files always open with one)")
    if errors:
        print(f"error: {'; '.join(errors)}", file=sys.stderr)
        return EXIT_INVALID

    relay_dir = Path(args.dir)
    next_id = _next_id(relay_dir)
    full_id = f"{ID_PREFIX}{next_id:04d}"
    slug = _slugify(args.slug or start_obj["movement"])
    target = relay_dir / f"{full_id}-{slug}.json"
    if target.exists():
        print(f"error: {target} already exists", file=sys.stderr)
        return EXIT_INVALID

    now = _utc_now_iso()
    other_role = "engineer" if args.role == "po" else "po"
    file_obj = {
        "schema_version": SCHEMA_VERSION,
        "id": full_id,
        "movement": start_obj["movement"],
        "refs": start_obj["refs"],
        "status": "OPEN",
        "next_actor": other_role,
        "created_at": now,
        "updated_at": now,
        "entries": [{
            "seq": 1, "marker": "SESSION_START", "actor": args.role,
            "timestamp": now, "report": start_obj["report"],
        }],
    }
    internal_errors = validate_relay_object(file_obj)
    if internal_errors:  # pragma: no cover -- defensive; construction above should always be valid
        print(f"error: internal consistency check failed: {'; '.join(internal_errors)}", file=sys.stderr)
        return EXIT_INVALID

    _atomic_write(target, _dump(file_obj))
    print(str(target))
    return EXIT_OK


# ---------------------------------------------------------------------------
# append
# ---------------------------------------------------------------------------

def _build_entry(args: argparse.Namespace, seq: int, timestamp: str) -> tuple[dict | None, str | None]:
    marker = args.marker
    entry: dict[str, Any] = {"seq": seq, "marker": marker, "actor": args.role, "timestamp": timestamp}
    if args.good_to_go:
        entry["good_to_go"] = True

    if marker == "SESSION_CLOSE":
        if not args.report:
            return None, "--report is required for SESSION_CLOSE"
        try:
            report_obj = json.loads(gst._read_input(args.report))
        except gst.PacketError as exc:
            return None, str(exc)
        except json.JSONDecodeError as exc:
            return None, f"--report is not valid JSON: {exc}"
        if not args.outcome:
            return None, "--outcome is required for SESSION_CLOSE"
        report_errors: list[str] = []
        gst._validate_node(report_obj, gst.SESSION_CLOSE_REPORT_SCHEMA, "report", report_errors)
        if report_errors:
            return None, "; ".join(report_errors)
        entry["report"] = report_obj
        entry["outcome"] = args.outcome
        return entry, None

    subject = args.subject or ("good to go" if args.good_to_go else None)
    text = args.text or ("good to go" if args.good_to_go else None)
    if not subject or not text:
        return None, "--subject and --text are required (or pass --good-to-go for the fast path)"
    entry["subject"] = subject
    entry["text"] = text

    if marker == "RELAY_DECISION":
        for field, value in (("authorized_by", args.authorized_by), ("scope", args.scope), ("supersedes", args.supersedes)):
            if not value:
                return None, f"--{field.replace('_', '-')} is required for RELAY_DECISION"
        entry["authorized_by"] = args.authorized_by
        entry["scope"] = args.scope
        entry["supersedes"] = args.supersedes
    elif marker not in NARRATIVE_MARKERS:
        return None, f"{marker} cannot be appended (SESSION_START only via create)"

    return entry, None


def _cmd_append(args: argparse.Namespace) -> int:
    path = Path(args.file)
    try:
        raw_before = path.read_text(encoding="utf-8")
    except OSError as exc:
        print(f"error: no such file: {path} ({exc})", file=sys.stderr)
        return EXIT_USAGE
    try:
        obj = json.loads(raw_before)
    except json.JSONDecodeError as exc:
        print(f"error: {path} is not valid JSON: {exc}", file=sys.stderr)
        return EXIT_INVALID
    errors = validate_relay_object(obj)
    if not _id_matches_filename(obj.get("id", ""), path):
        errors.append(f"id {obj.get('id')!r} does not match filename {path.name!r}")
    if errors:
        print(f"error: {path} fails validation, refusing to append: {'; '.join(errors)}", file=sys.stderr)
        return EXIT_INVALID

    if obj["status"] == "CLOSED":
        print(f"error: {path} is CLOSED; no further entries may be appended", file=sys.stderr)
        return EXIT_INVALID
    if obj["next_actor"] != args.role:
        print(f"error: not {args.role}'s turn (next_actor is {obj['next_actor']!r})", file=sys.stderr)
        return EXIT_INVALID
    if args.marker not in APPENDABLE_MARKERS:
        print(f"error: {args.marker} cannot be appended (SESSION_START only via create)", file=sys.stderr)
        return EXIT_INVALID
    if args.close and args.in_progress:
        print("error: --close and --in-progress are mutually exclusive", file=sys.stderr)
        return EXIT_USAGE
    if args.marker != "SESSION_CLOSE" and not args.close and not args.next:
        print("error: --next {po|engineer} is required unless --close is passed", file=sys.stderr)
        return EXIT_USAGE
    if args.marker == "SESSION_CLOSE" and (args.next or args.close):
        print("error: --next/--close are redundant with SESSION_CLOSE, which always closes", file=sys.stderr)
        return EXIT_USAGE

    now = _utc_now_iso()
    entry, build_error = _build_entry(args, len(obj["entries"]) + 1, now)
    if build_error:
        print(f"error: {build_error}", file=sys.stderr)
        return EXIT_INVALID

    closing = args.marker == "SESSION_CLOSE" or args.close
    obj["entries"].append(entry)
    obj["updated_at"] = now
    if closing:
        obj["next_actor"] = None
        obj["status"] = "CLOSED"
    else:
        obj["next_actor"] = args.next
        obj["status"] = "IN_PROGRESS" if args.in_progress else ("AWAITING_PO" if args.next == "po" else "AWAITING_ENGINEER")

    internal_errors = validate_relay_object(obj)
    if internal_errors:  # pragma: no cover -- defensive; construction above should always be valid
        print(f"error: internal consistency check failed: {'; '.join(internal_errors)}", file=sys.stderr)
        return EXIT_INVALID

    try:
        raw_now = path.read_text(encoding="utf-8")
    except OSError as exc:
        print(f"error: file disappeared before write: {exc}", file=sys.stderr)
        return EXIT_INVALID
    if raw_now != raw_before:
        print(f"error: {path} changed since it was read -- a concurrent write occurred; "
              f"re-run against the latest state", file=sys.stderr)
        return EXIT_INVALID

    _atomic_write(path, _dump(obj))
    print(json.dumps({"status": obj["status"], "next_actor": obj["next_actor"], "seq": entry["seq"]}))
    return EXIT_OK


# ---------------------------------------------------------------------------
# status / validate
# ---------------------------------------------------------------------------

def _cmd_status(args: argparse.Namespace) -> int:
    path = Path(args.file)
    try:
        obj = _load_json_file(path)
    except LocalRelayError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return EXIT_USAGE if str(exc).startswith("no such file") else EXIT_INVALID
    errors = validate_relay_object(obj) if isinstance(obj, dict) else ["must be a JSON object"]
    entries = obj.get("entries") if isinstance(obj, dict) else None
    last = entries[-1] if isinstance(entries, list) and entries and isinstance(entries[-1], dict) else {}
    print(json.dumps({
        "valid": not errors,
        "errors": errors,
        "id": obj.get("id") if isinstance(obj, dict) else None,
        "movement": obj.get("movement") if isinstance(obj, dict) else None,
        "status": obj.get("status") if isinstance(obj, dict) else None,
        "next_actor": obj.get("next_actor") if isinstance(obj, dict) else None,
        "entry_count": len(entries) if isinstance(entries, list) else 0,
        "last_marker": last.get("marker"),
        "last_actor": last.get("actor"),
        "last_timestamp": last.get("timestamp"),
    }, sort_keys=True))
    return EXIT_OK


def _cmd_validate(args: argparse.Namespace) -> int:
    path = Path(args.file)
    try:
        obj = _load_json_file(path)
    except LocalRelayError as exc:
        if str(exc).startswith("no such file"):
            print(f"error: {exc}", file=sys.stderr)
            return EXIT_USAGE
        print(json.dumps({"valid": False, "errors": [str(exc)]}))
        return EXIT_INVALID
    errors = validate_relay_object(obj)
    if isinstance(obj, dict) and not _id_matches_filename(obj.get("id", ""), path):
        errors.append(f"id {obj.get('id')!r} does not match filename {path.name!r}")
    print(json.dumps({"valid": not errors, "errors": errors}, sort_keys=True))
    return EXIT_OK if not errors else EXIT_INVALID


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="local_relay", description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    p_create = sub.add_parser("create", help="open a new relay file with a SESSION_START entry")
    p_create.add_argument("--start", required=True, help="bare SESSION_START packet JSON file, or - for stdin")
    p_create.add_argument("--role", required=True, choices=ROLES)
    p_create.add_argument("--dir", default="relay")
    p_create.add_argument("--slug", default=None)
    p_create.set_defaults(func=_cmd_create)

    p_append = sub.add_parser("append", help="append one entry; rejected if it is not --role's turn")
    p_append.add_argument("--file", required=True)
    p_append.add_argument("--role", required=True, choices=ROLES)
    p_append.add_argument("--marker", required=True, choices=APPENDABLE_MARKERS)
    p_append.add_argument("--subject", default=None)
    p_append.add_argument("--text", default=None)
    p_append.add_argument("--report", default=None, help="SESSION_CLOSE only: bare report JSON file, or -")
    p_append.add_argument("--outcome", default=None, choices=gst.OUTCOMES)
    p_append.add_argument("--authorized-by", dest="authorized_by", default=None)
    p_append.add_argument("--scope", default=None)
    p_append.add_argument("--supersedes", default=None)
    p_append.add_argument("--next", default=None, choices=ROLES)
    p_append.add_argument("--close", action="store_true")
    p_append.add_argument("--good-to-go", dest="good_to_go", action="store_true")
    p_append.add_argument("--in-progress", dest="in_progress", action="store_true")
    p_append.set_defaults(func=_cmd_append)

    p_status = sub.add_parser("status", help="read-only summary of one relay file")
    p_status.add_argument("--file", required=True)
    p_status.set_defaults(func=_cmd_status)

    p_validate = sub.add_parser("validate", help="schema-check one relay file")
    p_validate.add_argument("--file", required=True)
    p_validate.set_defaults(func=_cmd_validate)

    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
