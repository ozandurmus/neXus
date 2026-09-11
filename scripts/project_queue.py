#!/usr/bin/env python3
"""GOV.ORCH.5: the project queue tool.

`project/QUEUE.md` is the only planning file an agent reads at cold start;
`project/roadmap.json` and `project/backlog.json` remain the render data
source, written only through this tool (never hand-edited). See
`docs/design/GOV_ORCH_5_PROJECT_QUEUE_AND_COLD_START_DIET.md`.

    py scripts/project_queue.py render
    py scripts/project_queue.py check
    py scripts/project_queue.py add    --id <id> --title ... --priority P2 --category ... [--target ...]
    py scripts/project_queue.py status --id <id> --set in_progress|done|deferred|automated_validated|real_env_validated|planned [--target ...]
    py scripts/project_queue.py note   --id <id> --text ...
    py scripts/project_queue.py decide --id <decision-id> --decision ...

Every write (`add`/`status`/`decide`) loads the relevant JSON, validates it,
applies the change, writes it back canonically (`json.dumps(indent=2,
ensure_ascii=False)` plus a trailing newline, preserving key order), then
re-renders `project/QUEUE.md`. A JSON file that fails to load is reported
with its line and column and nothing is written. `add` refuses a duplicate
id. `note` appends verbatim to `docs/history/backlog/<id>.md` and never
touches the JSON `note` field.

Offline, no network, no credentials. Reads/writes only files under `project/`
and `docs/history/backlog/`.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
BACKLOG = REPO / "project" / "backlog.json"
ROADMAP = REPO / "project" / "roadmap.json"
QUEUE = REPO / "project" / "QUEUE.md"
HISTORY_BACKLOG_DIR = REPO / "docs" / "history" / "backlog"

#: Terminal statuses: their `note` narrative lives in docs/history/backlog/,
#: not in the JSON. Open statuses are exactly the complement: in_progress, planned.
TERMINAL_STATUSES = ("done", "automated_validated", "real_env_validated", "deferred")
OPEN_STATUSES = ("in_progress", "planned")
ALL_STATUSES = OPEN_STATUSES + TERMINAL_STATUSES

#: `add`/write validation: required keys per backlog item (contract 2.2).
REQUIRED_ITEM_KEYS = ("id", "title", "status", "priority", "category")

TITLE_TRUNCATE = 100
TARGET_TRUNCATE = 80
QUESTION_TRUNCATE = 100

_PRIORITY_RE = re.compile(r"^\s*(P\d)\b")


class QueueToolError(Exception):
    """Raised for a reported, non-traceback CLI failure."""


def _truncate(text: str, limit: int) -> str:
    text = " ".join((text or "").split())
    return text if len(text) <= limit else text[:limit].rstrip()


def _priority_label(priority: str | None) -> str:
    match = _PRIORITY_RE.match(priority or "")
    return match.group(1) if match else (priority or "P?")


def _priority_rank(priority: str | None) -> int:
    match = _PRIORITY_RE.match(priority or "")
    return int(match.group(1)[1:]) if match else 99


def _display_path(path: Path) -> str:
    try:
        return str(path.relative_to(REPO))
    except ValueError:
        return str(path)


def canonical_dump(data: dict) -> str:
    """The one canonical on-disk form: 2-space indent, non-ASCII kept
    literal, key order preserved (Python dicts already preserve insertion/
    parse order), trailing newline."""
    return json.dumps(data, indent=2, ensure_ascii=False) + "\n"


def load_json(path: Path) -> dict:
    """Load `path` as JSON. Raises QueueToolError with the exact line/column
    of a parse failure -- caller writes nothing when this raises."""
    try:
        raw = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise QueueToolError(f"{path}: cannot read ({exc})") from exc
    try:
        return json.loads(raw)
    except json.JSONDecodeError as exc:
        raise QueueToolError(
            f"{path}: invalid JSON at line {exc.lineno} column {exc.colno}: {exc.msg}"
        ) from exc


def write_json(path: Path, data: dict) -> None:
    path.write_text(canonical_dump(data), encoding="utf-8")


def validate_backlog_item(item: dict) -> None:
    """Strict schema check (contract 2.2's "required keys per item"), applied
    to an item this tool is creating or fully rewriting -- e.g. `add`'s new
    item. Not applied to every pre-existing item on `render`/`check`: some
    legacy rows predate this tool and this movement does not backfill or
    reformat data it was not asked to touch (see AGENTS.md project-state
    update rule amendment and this contract's "no reformat" instruction)."""
    missing = [key for key in REQUIRED_ITEM_KEYS if not item.get(key)]
    if missing:
        raise QueueToolError(
            f"backlog item {item.get('id', '<no id>')!r} missing required field(s): "
            + ", ".join(missing)
        )
    if item["status"] not in ALL_STATUSES:
        raise QueueToolError(
            f"backlog item {item['id']!r} has unknown status {item['status']!r}; "
            f"expected one of {', '.join(ALL_STATUSES)}"
        )


def validate_backlog_structure(data: dict) -> None:
    """Structural check applied on every load: `items` is a list of objects,
    each with an `id`, and no duplicate ids. Does not require every legacy
    item to carry every field `validate_backlog_item` demands of a new one."""
    items = data.get("items")
    if not isinstance(items, list):
        raise QueueToolError("backlog.json: 'items' must be a list")
    seen: set[str] = set()
    for item in items:
        if not isinstance(item, dict) or not item.get("id"):
            raise QueueToolError("backlog.json: every item must be an object with an 'id'")
        if item["id"] in seen:
            raise QueueToolError(f"backlog.json: duplicate id {item['id']!r}")
        seen.add(item["id"])


def validate_backlog(data: dict) -> None:
    validate_backlog_structure(data)


# --------------------------------------------------------------------------
# render
# --------------------------------------------------------------------------

_GENERATED_RE = re.compile(r"Generated:\s*(\S+)")


def _now_next_line(entry: dict | None) -> str | None:
    if not entry or not entry.get("id", entry.get("build")):
        return None
    ident = entry.get("build") or entry.get("id")
    title = entry.get("title", "")
    status = entry.get("status", "")
    return f"- {ident} — {title} ({status})" if status else f"- {ident} — {title}"


def render_queue_md(backlog: dict, roadmap: dict, generated: str | None = None) -> str:
    generated = generated or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    current_build = roadmap.get("current_build", "")
    current_track = roadmap.get("current_track", "")

    lines: list[str] = []
    lines.append(
        "# Project queue (generated — do not edit; run: py scripts/project_queue.py render)"
    )
    lines.append(f"Build: {current_build} · Track: {current_track} · Generated: {generated}")
    lines.append("")

    now_next = roadmap.get("now_next") or {}
    lines.append("## Now")
    now_line = _now_next_line(now_next.get("now"))
    if now_line:
        lines.append(now_line)
    lines.append("## Next")
    next_line = _now_next_line(now_next.get("next"))
    if next_line:
        lines.append(next_line)

    lines.append("## Open backlog (P1 first, then P2, then P3; in_progress before planned)")
    items = [i for i in (backlog.get("items") or []) if i.get("status") in OPEN_STATUSES]
    status_rank = {"in_progress": 0, "planned": 1}

    def sort_key(item: dict):
        return (
            _priority_rank(item.get("priority")),
            status_rank.get(item.get("status"), 9),
            item.get("id", ""),
        )

    for item in sorted(items, key=sort_key):
        priority = _priority_label(item.get("priority"))
        status = item.get("status", "")
        title = _truncate(item.get("title", ""), TITLE_TRUNCATE)
        target = _truncate(item.get("target", ""), TARGET_TRUNCATE)
        lines.append(f"- {priority}/{status} {item.get('id', '')} — {title} (target: {target})")

    lines.append("## Open decisions")
    for decision in roadmap.get("open_decisions") or []:
        if decision.get("status") != "open":
            continue
        question = _truncate(decision.get("question", ""), QUESTION_TRUNCATE)
        lines.append(f"- {decision.get('id', '')} — {question}")

    return "\n".join(lines) + "\n"


def cmd_render(args: argparse.Namespace) -> int:
    backlog = load_json(BACKLOG)
    roadmap = load_json(ROADMAP)
    validate_backlog(backlog)
    content = render_queue_md(backlog, roadmap)
    QUEUE.write_text(content, encoding="utf-8")
    print(f"wrote {_display_path(QUEUE)}")
    return 0


def cmd_check(args: argparse.Namespace) -> int:
    try:
        backlog = load_json(BACKLOG)
        roadmap = load_json(ROADMAP)
    except QueueToolError as exc:
        print(str(exc))
        return 1
    try:
        validate_backlog(backlog)
    except QueueToolError as exc:
        print(str(exc))
        return 1

    current = QUEUE.read_text(encoding="utf-8") if QUEUE.exists() else ""
    # Reuse the existing file's own "Generated" timestamp so re-rendering in
    # memory for comparison does not spuriously differ on that single,
    # non-substantive field (the build_history_index.py --check pattern,
    # adapted for the one line that legitimately changes every render).
    existing_generated = None
    if current:
        match = _GENERATED_RE.search(current)
        existing_generated = match.group(1) if match else None
    expected = render_queue_md(backlog, roadmap, generated=existing_generated)
    if current != expected:
        print(f"{_display_path(QUEUE)} is stale — run: py scripts/project_queue.py render")
        return 1
    print(f"{_display_path(QUEUE)} is up to date.")
    return 0


# --------------------------------------------------------------------------
# add / status / note / decide
# --------------------------------------------------------------------------

def cmd_add(args: argparse.Namespace) -> int:
    backlog = load_json(BACKLOG)
    items = backlog.get("items")
    if not isinstance(items, list):
        raise QueueToolError("backlog.json: 'items' must be a list")
    if any(i.get("id") == args.id for i in items):
        raise QueueToolError(f"add: duplicate id {args.id!r} already exists")

    new_item = {
        "id": args.id,
        "category": args.category,
        "title": args.title,
        "status": "planned",
        "priority": args.priority,
        "target": args.target or "",
        "note": "",
    }
    validate_backlog_item(new_item)
    items.append(new_item)
    validate_backlog(backlog)
    write_json(BACKLOG, backlog)

    roadmap = load_json(ROADMAP)
    QUEUE.write_text(render_queue_md(backlog, roadmap), encoding="utf-8")
    print(f"added {args.id!r}")
    return 0


def cmd_status(args: argparse.Namespace) -> int:
    backlog = load_json(BACKLOG)
    items = backlog.get("items") or []
    item = next((i for i in items if i.get("id") == args.id), None)
    if item is None:
        raise QueueToolError(f"status: no such id {args.id!r}")

    item["status"] = args.set
    if args.target is not None:
        item["target"] = args.target

    if args.set in TERMINAL_STATUSES and item.get("note"):
        _move_note_to_history(item)
        item["note"] = f"see docs/history/backlog/{item['id']}.md"

    validate_backlog(backlog)
    write_json(BACKLOG, backlog)

    roadmap = load_json(ROADMAP)
    QUEUE.write_text(render_queue_md(backlog, roadmap), encoding="utf-8")
    print(f"{args.id!r} -> {args.set}")
    return 0


def _history_heading_body(title: str, status: str, target: str) -> str:
    return f"# {title}\n\nstatus: {status} · target: {target}\n"


def _move_note_to_history(item: dict) -> None:
    """Move `item['note']` verbatim to docs/history/backlog/<id>.md. Does
    not touch item['note'] itself -- the caller replaces it afterward."""
    HISTORY_BACKLOG_DIR.mkdir(parents=True, exist_ok=True)
    path = HISTORY_BACKLOG_DIR / f"{item['id']}.md"
    header = _history_heading_body(item.get("title", ""), item.get("status", ""), item.get("target", ""))
    note = item.get("note") or ""
    path.write_text(header + "\n" + note + "\n", encoding="utf-8")


def cmd_note(args: argparse.Namespace) -> int:
    backlog = load_json(BACKLOG)
    items = backlog.get("items") or []
    item = next((i for i in items if i.get("id") == args.id), None)
    if item is None:
        raise QueueToolError(f"note: no such id {args.id!r}")

    HISTORY_BACKLOG_DIR.mkdir(parents=True, exist_ok=True)
    path = HISTORY_BACKLOG_DIR / f"{item['id']}.md"
    if path.exists():
        existing = path.read_text(encoding="utf-8")
        path.write_text(existing.rstrip("\n") + "\n\n" + args.text + "\n", encoding="utf-8")
    else:
        header = _history_heading_body(item.get("title", ""), item.get("status", ""), item.get("target", ""))
        path.write_text(header + "\n" + args.text + "\n", encoding="utf-8")
    print(f"noted {args.id!r} -> {_display_path(path)}")
    return 0


def cmd_decide(args: argparse.Namespace) -> int:
    roadmap = load_json(ROADMAP)
    decisions = roadmap.get("open_decisions") or []
    decision = next((d for d in decisions if d.get("id") == args.id), None)
    if decision is None:
        raise QueueToolError(f"decide: no such decision id {args.id!r}")
    if decision.get("status") != "open":
        raise QueueToolError(f"decide: decision {args.id!r} is not open (status={decision.get('status')!r})")

    decision["status"] = "decided"
    decision["decision"] = args.decision
    decision["decided_on"] = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    write_json(ROADMAP, roadmap)

    backlog = load_json(BACKLOG)
    QUEUE.write_text(render_queue_md(backlog, roadmap), encoding="utf-8")
    print(f"decided {args.id!r}")
    return 0


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    p_render = sub.add_parser("render", help="regenerate project/QUEUE.md")
    p_render.set_defaults(func=cmd_render)

    p_check = sub.add_parser("check", help="exit 1 if project/QUEUE.md is stale or backlog.json is invalid")
    p_check.set_defaults(func=cmd_check)

    p_add = sub.add_parser("add", help="add a new backlog item")
    p_add.add_argument("--id", required=True)
    p_add.add_argument("--title", required=True)
    p_add.add_argument("--priority", required=True)
    p_add.add_argument("--category", required=True)
    p_add.add_argument("--target", default="")
    p_add.set_defaults(func=cmd_add)

    p_status = sub.add_parser("status", help="update a backlog item's status")
    p_status.add_argument("--id", required=True)
    p_status.add_argument("--set", required=True, choices=ALL_STATUSES)
    p_status.add_argument("--target", default=None)
    p_status.set_defaults(func=cmd_status)

    p_note = sub.add_parser("note", help="append a note to docs/history/backlog/<id>.md")
    p_note.add_argument("--id", required=True)
    p_note.add_argument("--text", required=True)
    p_note.set_defaults(func=cmd_note)

    p_decide = sub.add_parser("decide", help="close an open roadmap decision")
    p_decide.add_argument("--id", required=True)
    p_decide.add_argument("--decision", required=True)
    p_decide.set_defaults(func=cmd_decide)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except QueueToolError as exc:
        print(str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
