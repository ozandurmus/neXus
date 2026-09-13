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
    py scripts/project_queue.py migrate-open-notes --include-open

Every write (`add`/`status`/`decide`) loads the relevant JSON, validates it,
applies the change, writes it back canonically (`json.dumps(indent=2,
ensure_ascii=False)` plus a trailing newline, preserving key order), then
re-renders `project/QUEUE.md`. A JSON file that fails to load is reported
with its line and column and nothing is written. `add` refuses a duplicate
id. `note` appends verbatim to `docs/history/backlog/<id>.md` and never
touches the JSON `note` field. `migrate-open-notes --include-open` is the
GOV.ORCH.5 amendment (proposed, see
`docs/design/GOV_ORCH_5_PROJECT_QUEUE_AND_COLD_START_DIET.md`): it moves
an `in_progress`/`planned` item's pre-existing inline `note` to the same
history file and pointer form the `status` transition already uses for
terminal items, verbatim, under an explicit flag rather than by default.

`build add` is one transaction over two authority files: it inserts the
newest-first `build_history.json` record AND advances `roadmap.json`
`now_next.now` / `current_build` to it, because the head record *is* the
current build under `utils.project_plan` rules R1/R2. `--advance-now` is
mandatory so that pointer move is stated, never implicit. Every `build`
write re-validates the resulting state against `utils.project_plan.
_cross_authority_warnings` and writes nothing if it would introduce a
contradiction, and regenerates `docs/history/INDEX.md`. See
`docs/design/GOV_ORCH_8_PROJECT_DATA_SPLIT_AND_SIZE_BUDGET.md`.

Offline, no network, no credentials. Reads/writes only files under `project/`
and `docs/history/{backlog,builds}/`, plus `docs/history/INDEX.md`.
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
BUILD_HISTORY = REPO / "project" / "build_history.json"
FEATURE_REGISTRY = REPO / "project" / "feature_registry.json"
ARCHIVE_BUILD_HISTORY = REPO / "project" / "archive" / "build_history_2026.json"
#: GOV.ORCH.9: the terminal reserve. `BACKLOG` (the active set) carries only
#: `planned`/`in_progress` items and is what the cold start loads; every
#: `done`/`deferred`/`automated_validated`/`real_env_validated` item lives
#: here instead, same row schema, never loaded at cold start.
ARCHIVE_BACKLOG_TERMINAL = REPO / "project" / "archive" / "backlog_terminal.json"
QUEUE = REPO / "project" / "QUEUE.md"
HISTORY_BACKLOG_DIR = REPO / "docs" / "history" / "backlog"
HISTORY_BUILDS_DIR = REPO / "docs" / "history" / "builds"
HISTORY_ROADMAP_DIR = REPO / "docs" / "history" / "roadmap"
HISTORY_FEATURES_DIR = REPO / "docs" / "history" / "features"
PRIVACY_TERMS_FILE = REPO / ".nexus" / "privacy_terms.txt"

# `utils.project_plan` owns the cross-authority gate and the build status
# vocabulary; `build_history_index` owns docs/history/INDEX.md. Both are
# imported lazily inside the functions that need them, so make their roots
# importable whichever way this module is loaded (CLI or test import).
for _root in (REPO, REPO / "scripts"):
    if str(_root) not in sys.path:
        sys.path.insert(0, str(_root))

#: Terminal statuses: their `note` narrative lives in docs/history/backlog/,
#: not in the JSON. Open statuses are exactly the complement: in_progress, planned.
TERMINAL_STATUSES = ("done", "automated_validated", "real_env_validated", "deferred")
OPEN_STATUSES = ("in_progress", "planned")
ALL_STATUSES = OPEN_STATUSES + TERMINAL_STATUSES

#: `add`/write validation: required keys per backlog item (contract 2.2).
REQUIRED_ITEM_KEYS = ("id", "title", "status", "priority", "category")

TITLE_TRUNCATE = 58
# Shortened from 80 on 2026-09-12. A defect-triage batch of five rows pushed
# QUEUE.md fifteen words past the 1500-word cold-start budget GOV.ORCH.5 2.1
# sets, leaving roughly sixty words of headroom -- not enough to absorb a
# normal batch. The rendered target was the cheapest thing to cut: at cold
# start the reader needs the id and the title, and the full pointer is in the
# row's own note under docs/history/backlog/. Dropping rows, or raising the
# budget, would each have hidden the problem instead.
TARGET_TRUNCATE = 40
QUESTION_TRUNCATE = 72

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


# --------------------------------------------------------------------------
# Redaction (GOV.ORCH.8 2.1): every moved narrative is redacted before it is
# written anywhere. IPv4 literals (except loopback/RFC 5737) -> <PRIVATE_IP>;
# vendor-prefixed device hostnames (e.g. FW-CKP-<...>-N) -> <DEVICE_NAME>; an
# uppercase org token embedded in such a name -> <ORG>; and any additional
# term listed one-per-line in the optional, gitignored
# .nexus/privacy_terms.txt -> <ORG>.
# --------------------------------------------------------------------------

_IPV4_RE = re.compile(r"(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])")
_DOC_IPV4_NETWORKS = ("192.0.2.", "198.51.100.", "203.0.113.")
_DEVICE_HOSTNAME_RE = re.compile(
    r"\b(FW|MDS|GW|VSX|SMS|CMA)-([A-Z0-9]+)-([A-Z0-9][A-Z0-9-]*?)-(\d+)\b"
)
_LOOPBACK_PREFIXES = ("127.",)


def _is_safe_ip(literal: str) -> bool:
    if literal in ("0.0.0.0",) or literal.startswith(_LOOPBACK_PREFIXES):
        return True
    return any(literal.startswith(net) for net in _DOC_IPV4_NETWORKS)


def _redact_ipv4(text: str) -> str:
    def _sub(match: "re.Match[str]") -> str:
        literal = match.group(0)
        return literal if _is_safe_ip(literal) else "<PRIVATE_IP>"

    return _IPV4_RE.sub(_sub, text)


def _redact_device_hostnames(text: str) -> str:
    return _DEVICE_HOSTNAME_RE.sub("<DEVICE_NAME>", text)


def _load_privacy_terms() -> list[str]:
    """Optional, gitignored `.nexus/privacy_terms.txt`: one literal term per
    line -> `<ORG>`. Missing file means no extra terms; blank lines and
    lines starting with `#` are ignored."""
    if not PRIVACY_TERMS_FILE.exists():
        return []
    terms: list[str] = []
    for line in PRIVACY_TERMS_FILE.read_text(encoding="utf-8").splitlines():
        term = line.strip()
        if term and not term.startswith("#"):
            terms.append(term)
    # Longest first so a term that is a substring of another does not shadow it.
    return sorted(set(terms), key=len, reverse=True)


def _redact_privacy_terms(text: str, terms: list[str] | None = None) -> str:
    for term in (terms if terms is not None else _load_privacy_terms()):
        if term:
            text = text.replace(term, "<ORG>")
    return text


def redact_narrative(text: str) -> str:
    """The one redaction pass applied to every moved narrative before it is
    written anywhere: IPv4 -> <PRIVATE_IP>, device hostnames -> <DEVICE_NAME>,
    then any configured privacy term -> <ORG>. Idempotent and safe to run on
    already-redacted text."""
    if not text:
        return text
    text = _redact_ipv4(text)
    text = _redact_device_hostnames(text)
    text = _redact_privacy_terms(text)
    return text


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
# GOV.ORCH.9: active set / terminal reserve split. `BACKLOG` and
# `ARCHIVE_BACKLOG_TERMINAL` are two halves of one logical backlog, split by
# status (`_file_for_status`). Every command that counts, lists, looks up by
# id, or writes an item goes through the helpers below so both files stay
# correct and the split is invisible to callers beyond needing both dicts.
# --------------------------------------------------------------------------

def _empty_backlog_doc(schema_version: str = "1.0") -> dict:
    return {"schema_version": schema_version, "items": []}


def load_backlog_terminal() -> dict:
    """The reserve, or an empty doc if it does not exist yet (pre-migration
    or a fixture that only sets up the active file)."""
    if not ARCHIVE_BACKLOG_TERMINAL.exists():
        return _empty_backlog_doc()
    return load_json(ARCHIVE_BACKLOG_TERMINAL)


def _file_for_status(status: str) -> str:
    """Which of the two backlog files an item with this status belongs in."""
    return "terminal" if status in TERMINAL_STATUSES else "active"


def load_backlog_both() -> tuple[dict, dict]:
    """Load the active set and the terminal reserve together."""
    return load_json(BACKLOG), load_backlog_terminal()


def write_backlog_both(active: dict, terminal: dict) -> None:
    write_json(BACKLOG, active)
    ARCHIVE_BACKLOG_TERMINAL.parent.mkdir(parents=True, exist_ok=True)
    write_json(ARCHIVE_BACKLOG_TERMINAL, terminal)


def all_backlog_items(active: dict, terminal: dict) -> list[dict]:
    """The full backlog, both files, in the order GOV.ORCH.8 2.2's `_load`
    pattern uses for build_history: active first, then the reserve."""
    return list(active.get("items") or []) + list(terminal.get("items") or [])


def find_backlog_item(active: dict, terminal: dict, item_id: str) -> tuple[dict | None, dict | None]:
    """Look an item up by id across both files. Returns (item, its home dict)
    or (None, None)."""
    for item in active.get("items") or []:
        if item.get("id") == item_id:
            return item, active
    for item in terminal.get("items") or []:
        if item.get("id") == item_id:
            return item, terminal
    return None, None


def move_backlog_item_if_needed(item: dict, home: dict, active: dict, terminal: dict) -> None:
    """After `item['status']` has been set, move it to the file its new
    status selects if that crosses the terminal boundary (GOV.ORCH.9 B-5)."""
    dest = active if _file_for_status(item["status"]) == "active" else terminal
    if dest is home:
        return
    home["items"] = [i for i in (home.get("items") or []) if i is not item]
    dest.setdefault("items", []).append(item)


def validate_backlog_split(active: dict, terminal: dict) -> None:
    """GOV.ORCH.9 B-1/B-2: every active item is open, every terminal item is
    terminal, and no id appears in both files."""
    validate_backlog_structure(active)
    validate_backlog_structure(terminal)
    seen: set[str] = set()
    for label, doc, allowed in (
        ("backlog.json", active, OPEN_STATUSES),
        ("archive/backlog_terminal.json", terminal, TERMINAL_STATUSES),
    ):
        for item in doc.get("items") or []:
            item_id = item.get("id")
            if item_id in seen:
                raise QueueToolError(f"backlog split: duplicate id {item_id!r} across both files")
            seen.add(item_id)
            status = item.get("status")
            if status not in allowed:
                raise QueueToolError(
                    f"{label}: item {item_id!r} has status {status!r}, which does not "
                    f"belong in this file (expected one of {', '.join(allowed)})"
                )


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


def _recent_builds(limit: int = 5) -> list[dict]:
    try:
        history = load_json(BUILD_HISTORY)
    except QueueToolError:
        return []
    return list((history.get("builds") or [])[:limit])


def render_queue_md(
    backlog: dict,
    roadmap: dict,
    backlog_terminal: dict | None = None,
    generated: str | None = None,
) -> str:
    """`backlog` is the active set (GOV.ORCH.9): it may still carry terminal
    rows when called on a not-yet-split fixture, so the open/deferred filters
    below run over `backlog` and `backlog_terminal` combined rather than
    assuming either file's contents. `backlog_terminal` defaults to empty for
    callers (and old fixtures) that only have one file."""
    backlog_terminal = backlog_terminal or _empty_backlog_doc()
    all_items = list(backlog.get("items") or []) + list(backlog_terminal.get("items") or [])
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
    items = [i for i in all_items if i.get("status") in OPEN_STATUSES]
    status_rank = {"in_progress": 0, "planned": 1}

    def sort_key(item: dict):
        return (
            _priority_rank(item.get("priority")),
            status_rank.get(item.get("status"), 9),
            item.get("id", ""),
        )

    # P0/P1 render in full; P2/P3 carry id and status only. A cold start acts
    # on the top of the queue -- a P2's title and target are looked up in its
    # own note when it is reached, and spending the 1500-word budget
    # (GOV.ORCH.5 2.1) on fifty of them crowds out the rows being worked. Every
    # row stays listed: dropping one, or raising the budget, would each hide
    # the growth instead of paying for it.
    for item in sorted(items, key=sort_key):
        priority = _priority_label(item.get("priority"))
        status = item.get("status", "")
        item_id = item.get("id", "")
        if _priority_rank(item.get("priority")) <= 1:
            title = _truncate(item.get("title", ""), TITLE_TRUNCATE)
            target = _truncate(item.get("target", ""), TARGET_TRUNCATE)
            lines.append(f"- {priority}/{status} {item_id} — {title} (target: {target})")
        else:
            lines.append(f"- {priority}/{status} {item_id}")

    # A deferred row is held work, not finished work. Rendering it keeps a
    # standing hold — for example a Product Owner gate on collection scope —
    # visible in the one planning file an agent reads at cold start. Without
    # this section the row vanishes from QUEUE.md and its reason survives only
    # in docs/history/backlog/, which is not read by default.
    deferred = [i for i in all_items if i.get("status") == "deferred"]
    if deferred:
        lines.append(
            "## Deferred — held, not finished (reason: docs/history/backlog/<id>.md)"
        )
        # Id and priority only: the hold's reason lives in the linked note,
        # and repeating each title here would spend the cold-start word
        # budget GOV.ORCH.5 §2.1 sets, for text the reader must follow the
        # link to act on anyway.
        for item in sorted(deferred, key=lambda i: (_priority_rank(i.get("priority")), i.get("id", ""))):
            lines.append(f"- {_priority_label(item.get('priority'))} {item.get('id', '')}")

    lines.append("## Open decisions")
    for decision in roadmap.get("open_decisions") or []:
        if decision.get("status") != "open":
            continue
        question = _truncate(decision.get("question", ""), QUESTION_TRUNCATE)
        lines.append(f"- {decision.get('id', '')} — {question}")

    lines.append("## Recent builds")
    for build in _recent_builds():
        status = build.get("status", "")
        lines.append(f"- {build.get('build', '')} ({status})" if status else f"- {build.get('build', '')}")

    return "\n".join(lines) + "\n"


def cmd_render(args: argparse.Namespace) -> int:
    backlog, backlog_terminal = load_backlog_both()
    roadmap = load_json(ROADMAP)
    validate_backlog_split(backlog, backlog_terminal)
    content = render_queue_md(backlog, roadmap, backlog_terminal)
    QUEUE.write_text(content, encoding="utf-8")
    print(f"wrote {_display_path(QUEUE)}")
    return 0


def cmd_check(args: argparse.Namespace) -> int:
    try:
        backlog, backlog_terminal = load_backlog_both()
        roadmap = load_json(ROADMAP)
    except QueueToolError as exc:
        print(str(exc))
        return 1
    try:
        validate_backlog_split(backlog, backlog_terminal)
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
    expected = render_queue_md(backlog, roadmap, backlog_terminal, generated=existing_generated)
    if current != expected:
        print(f"{_display_path(QUEUE)} is stale — run: py scripts/project_queue.py render")
        return 1
    print(f"{_display_path(QUEUE)} is up to date.")
    return 0


# --------------------------------------------------------------------------
# add / status / note / decide
# --------------------------------------------------------------------------

def cmd_add(args: argparse.Namespace) -> int:
    backlog, backlog_terminal = load_backlog_both()
    if any(i.get("id") == args.id for i in all_backlog_items(backlog, backlog_terminal)):
        raise QueueToolError(f"add: duplicate id {args.id!r} already exists")

    new_item = {
        "id": args.id,
        "category": args.category,
        "title": args.title,
        "status": args.status,
        "priority": args.priority,
        "target": args.target or "",
        "note": "",
    }
    validate_backlog_item(new_item)
    # GOV.ORCH.9 B-5: an item lands in whichever file its status selects --
    # the reserve for a terminal status, the active set for an open one.
    dest = backlog_terminal if _file_for_status(args.status) == "terminal" else backlog
    dest.setdefault("items", []).append(new_item)
    validate_backlog_split(backlog, backlog_terminal)
    write_backlog_both(backlog, backlog_terminal)

    roadmap = load_json(ROADMAP)
    QUEUE.write_text(render_queue_md(backlog, roadmap, backlog_terminal), encoding="utf-8")
    print(f"added {args.id!r}")
    return 0


def cmd_retitle(args: argparse.Namespace) -> int:
    """Correct a backlog item's title and/or target in place.

    GOV.ORCH.5 forbids hand-editing `project/*.json`, but until now the tool
    could only ever *add* a title -- never shorten or correct one. That left
    an overlong title written by mistake permanently in the file, pushing
    `backlog.json` against its regression ceiling with no sanctioned remedy.
    This closes that gap. It never changes an item's id, status or priority.
    """
    if args.title is None and args.target is None:
        raise QueueToolError("retitle: pass --title and/or --target")

    backlog, backlog_terminal = load_backlog_both()
    item, _home = find_backlog_item(backlog, backlog_terminal, args.id)
    if item is None:
        raise QueueToolError(f"retitle: no such id {args.id!r}")

    if args.title is not None:
        item["title"] = args.title
    if args.target is not None:
        item["target"] = args.target

    validate_backlog_item(item)
    validate_backlog_split(backlog, backlog_terminal)
    write_backlog_both(backlog, backlog_terminal)

    roadmap = load_json(ROADMAP)
    QUEUE.write_text(render_queue_md(backlog, roadmap, backlog_terminal), encoding="utf-8")
    print(f"retitled {args.id!r}")
    return 0


def cmd_migrate_backlog_terminal(args: argparse.Namespace) -> int:
    """GOV.ORCH.9's one-time migration: split `backlog.json` into the active
    set (planned/in_progress) and `archive/backlog_terminal.json` (the
    terminal reserve), row schema and content unchanged, nothing deleted.
    Reproducible and idempotent: running it again on an already-split pair
    of files is a no-op because every item already sits in the file its
    status selects."""
    backlog = load_json(BACKLOG)
    backlog_terminal = load_backlog_terminal()
    before_ids = {item.get("id") for item in all_backlog_items(backlog, backlog_terminal)}

    active_items = [i for i in (backlog.get("items") or []) if i.get("status") in OPEN_STATUSES]
    terminal_items = [i for i in (backlog.get("items") or []) if i.get("status") in TERMINAL_STATUSES]
    backlog["items"] = active_items
    backlog_terminal.setdefault("schema_version", backlog.get("schema_version", "1.0"))
    backlog_terminal["items"] = list(backlog_terminal.get("items") or []) + terminal_items

    after_ids = {item.get("id") for item in all_backlog_items(backlog, backlog_terminal)}
    if after_ids != before_ids:
        raise QueueToolError(
            "migrate-backlog-terminal: the split would change the item set; nothing written"
        )
    validate_backlog_split(backlog, backlog_terminal)
    write_backlog_both(backlog, backlog_terminal)

    roadmap = load_json(ROADMAP)
    QUEUE.write_text(render_queue_md(backlog, roadmap, backlog_terminal), encoding="utf-8")
    print(
        f"split: {len(active_items)} active item(s) in {_display_path(BACKLOG)}, "
        f"{len(backlog_terminal['items'])} terminal item(s) in "
        f"{_display_path(ARCHIVE_BACKLOG_TERMINAL)}"
    )
    return 0


def cmd_status(args: argparse.Namespace) -> int:
    backlog, backlog_terminal = load_backlog_both()
    item, home = find_backlog_item(backlog, backlog_terminal, args.id)
    if item is None:
        raise QueueToolError(f"status: no such id {args.id!r}")

    item["status"] = args.set
    if args.target is not None:
        item["target"] = args.target

    if args.set in TERMINAL_STATUSES and item.get("note"):
        _move_note_to_history(item)
        item["note"] = f"see docs/history/backlog/{item['id']}.md"

    # GOV.ORCH.9 B-5/acceptance item 6: a status change that crosses the
    # terminal boundary moves the item to the file its new status selects,
    # in either direction.
    move_backlog_item_if_needed(item, home, backlog, backlog_terminal)

    validate_backlog_split(backlog, backlog_terminal)
    write_backlog_both(backlog, backlog_terminal)

    roadmap = load_json(ROADMAP)
    QUEUE.write_text(render_queue_md(backlog, roadmap, backlog_terminal), encoding="utf-8")
    print(f"{args.id!r} -> {args.set}")
    return 0


def _history_heading_body(title: str, status: str, target: str) -> str:
    return f"# {title}\n\nstatus: {status} · target: {target}\n"


def _set_history_status_line(text: str, status: str) -> str:
    """Update the `status: ... · target: ...` heading line in place, leaving
    everything else in the file untouched. GOV.ORCH.11 N-4: a field is not
    narrative, so updating it is not a reduction."""
    lines = text.split("\n")
    for i, line in enumerate(lines):
        if line.startswith("status: "):
            rest = line.split(" · ", 1)
            target_part = rest[1] if len(rest) > 1 else None
            lines[i] = f"status: {status}" + (f" · {target_part}" if target_part else "")
            break
    return "\n".join(lines)


def _move_note_to_history(item: dict) -> None:
    """Move `item['note']` verbatim to docs/history/backlog/<id>.md. Does
    not touch item['note'] itself -- the caller replaces it afterward.

    GOV.ORCH.11 N-2/N-3/N-4: this write is append-only, in the same shape
    `_migrate_note_to_history` already uses. A note that is merely the
    pointer this call is about to leave in item['note'] carries no
    information about itself and is never written into the file. When the
    file already exists, only its status line is updated in place."""
    HISTORY_BACKLOG_DIR.mkdir(parents=True, exist_ok=True)
    path = HISTORY_BACKLOG_DIR / f"{item['id']}.md"
    status = item.get("status", "")
    note_raw = item.get("note") or ""
    pointer = f"see docs/history/backlog/{item['id']}.md"
    is_pointer = note_raw == pointer
    dated = datetime.now(timezone.utc).strftime("%Y-%m-%d")

    if path.exists():
        existing = _set_history_status_line(path.read_text(encoding="utf-8"), status)
        if is_pointer:
            path.write_text(existing, encoding="utf-8")
            return
        note = redact_narrative(note_raw)
        path.write_text(existing.rstrip("\n") + f"\n\n## {dated}\n\n" + note + "\n", encoding="utf-8")
        return

    header = _history_heading_body(item.get("title", ""), status, item.get("target", ""))
    if is_pointer:
        path.write_text(header + "\n", encoding="utf-8")
        return
    note = redact_narrative(note_raw)
    path.write_text(header + "\n" + note + "\n", encoding="utf-8")


def _migrate_note_to_history(item: dict, dated: str) -> None:
    """Move `item['note']` verbatim into docs/history/backlog/<id>.md for the
    GOV.ORCH.5 amendment's open-item sweep. Creates the file (with the
    standard heading) if it does not exist yet; appends under a dated
    heading if it already does, so a bulk open-item move is distinguishable
    from any narrative a prior `note` command already appended there. Does
    not touch item['note'] itself -- the caller replaces it afterward."""
    HISTORY_BACKLOG_DIR.mkdir(parents=True, exist_ok=True)
    path = HISTORY_BACKLOG_DIR / f"{item['id']}.md"
    note = redact_narrative(item.get("note") or "")
    if path.exists():
        existing = path.read_text(encoding="utf-8")
        path.write_text(existing.rstrip("\n") + f"\n\n## {dated}\n\n" + note + "\n", encoding="utf-8")
    else:
        header = _history_heading_body(item.get("title", ""), item.get("status", ""), item.get("target", ""))
        path.write_text(header + "\n" + note + "\n", encoding="utf-8")


def cmd_migrate_open_notes(args: argparse.Namespace) -> int:
    """GOV.ORCH.5 amendment (PROPOSED — PENDING PRODUCT OWNER APPROVAL,
    docs/design/GOV_ORCH_5_PROJECT_QUEUE_AND_COLD_START_DIET.md): move the
    `note` of every note-bearing open item (`in_progress`/`planned`) into
    docs/history/backlog/<id>.md, the same way terminal items already move
    on `status`. Gated behind `--include-open` -- required, not the default
    -- because it is a distinct, one-time-per-item write the original
    GOV.ORCH.5 design explicitly exempted; it never changes an item's
    status, priority, title or target."""
    if not args.include_open:
        raise QueueToolError("migrate-open-notes: pass --include-open to confirm this move")

    backlog = load_json(BACKLOG)
    items = backlog.get("items") or []
    dated = datetime.now(timezone.utc).strftime("%Y-%m-%d")

    moved: list[str] = []
    for item in items:
        if item.get("status") not in OPEN_STATUSES:
            continue
        note = item.get("note") or ""
        pointer = f"see docs/history/backlog/{item['id']}.md"
        if not note or note == pointer:
            continue
        _migrate_note_to_history(item, dated)
        item["note"] = pointer
        moved.append(item["id"])

    if moved:
        validate_backlog(backlog)
        write_json(BACKLOG, backlog)
        roadmap = load_json(ROADMAP)
        QUEUE.write_text(render_queue_md(backlog, roadmap, load_backlog_terminal()), encoding="utf-8")

    print(f"migrated {len(moved)} open item note(s): {', '.join(moved) if moved else '(none)'}")
    return 0


def cmd_note(args: argparse.Namespace) -> int:
    backlog, backlog_terminal = load_backlog_both()
    item, _home = find_backlog_item(backlog, backlog_terminal, args.id)
    if item is None:
        raise QueueToolError(f"note: no such id {args.id!r}")

    HISTORY_BACKLOG_DIR.mkdir(parents=True, exist_ok=True)
    path = HISTORY_BACKLOG_DIR / f"{item['id']}.md"
    text = redact_narrative(args.text)
    if path.exists():
        existing = path.read_text(encoding="utf-8")
        path.write_text(existing.rstrip("\n") + "\n\n" + text + "\n", encoding="utf-8")
    else:
        header = _history_heading_body(item.get("title", ""), item.get("status", ""), item.get("target", ""))
        path.write_text(header + "\n" + text + "\n", encoding="utf-8")
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

    backlog, backlog_terminal = load_backlog_both()
    QUEUE.write_text(render_queue_md(backlog, roadmap, backlog_terminal), encoding="utf-8")
    print(f"decided {args.id!r}")
    return 0


# --------------------------------------------------------------------------
# build add / status / note  (GOV.ORCH.8 2.3 -- mirrors add/status/note above,
# operating on project/build_history.json instead of backlog.json)
# --------------------------------------------------------------------------

BUILD_TERMINAL_STATUSES = ("done", "complete", "complete_with_followup", "automated_validated", "real_env_validated")
SUMMARY_TRUNCATE = 240


def cmd_build(args: argparse.Namespace) -> int:
    return args.build_func(args)


def _load_build_history() -> dict:
    return load_json(BUILD_HISTORY)


def _find_build(history: dict, build_id: str) -> dict | None:
    return next((b for b in (history.get("builds") or []) if b.get("build") == build_id), None)


def _build_history_doc_path(build_id: str) -> Path:
    return HISTORY_BUILDS_DIR / f"{build_id}.md"


def _write_build_history_doc(build: dict) -> None:
    """Write/refresh docs/history/builds/<build>.md from a build row's full
    (redacted) summary/evidence/risks_forward. Verbatim after redaction --
    nothing is deleted."""
    HISTORY_BUILDS_DIR.mkdir(parents=True, exist_ok=True)
    path = _build_history_doc_path(build["build"])
    lines = [f"# {build['build']} — {build.get('title', '')}", ""]
    for section, key in (("Summary", "summary"), ("Evidence", "evidence"), ("Risks forward", "risks_forward")):
        value = redact_narrative(build.get(f"_full_{key}") or build.get(key) or "")
        if value:
            lines.append(f"## {section}")
            lines.append("")
            lines.append(value)
            lines.append("")
    path.write_text("\n".join(lines).rstrip("\n") + "\n", encoding="utf-8")


def _sentence_count(text: str) -> int:
    """Count sentences for the build_history record_contract's `summary
    (<= 2 sentences)` limit. Terminators are . ! ? ; a trailing terminator
    does not open a new sentence."""
    parts = [p for p in re.split(r"[.!?]+(?:\s|$)", (text or "").strip()) if p.strip()]
    return len(parts)


def _promote_next(now_next: dict, new_build: str) -> None:
    """If the build becoming NOW is the one roadmap called NEXT, NEXT must move
    on: promote the first `upcoming` row still `planned`. The horizon contract
    says NOW and NEXT are exactly one each and cannot be the same build."""
    nxt = now_next.get("next") or {}
    if str(nxt.get("build") or "") != new_build:
        return
    upcoming = now_next.get("upcoming") or []
    for index, row in enumerate(upcoming):
        if str(row.get("status") or "") == "planned":
            now_next["next"] = upcoming.pop(index)
            return
    now_next["next"] = {}


def _authority_warnings(history: dict, roadmap: dict) -> list[str]:
    """Run the real cross-authority gate (utils.project_plan rules R1..R6) over
    an in-memory project state. The gate is the authority; this tool validates
    against it rather than restating or weakening it."""
    from utils.project_plan import _cross_authority_warnings

    features = (load_json(FEATURE_REGISTRY).get("features") or []) if FEATURE_REGISTRY.exists() else []
    backlog_items = all_backlog_items(*load_backlog_both()) if BACKLOG.exists() else []
    return _cross_authority_warnings(
        roadmap,
        [row for row in (features or []) if isinstance(row, dict)],
        [row for row in (history.get("builds") or []) if isinstance(row, dict)],
        [row for row in (backlog_items or []) if isinstance(row, dict)],
    )


def _assert_no_new_contradiction(command: str, history: dict, roadmap: dict) -> None:
    """Validate before writing: the state this command would produce must not
    contradict itself under any rule it is responsible for. Compared as a delta
    against the on-disk state, so pre-existing unrelated debt (e.g. an old
    decision missing its decide_by gate) does not block an otherwise legal
    write, while any contradiction this command would introduce aborts it with
    nothing written."""
    before = set(_authority_warnings(_load_build_history(), load_json(ROADMAP)))
    introduced = [w for w in _authority_warnings(history, roadmap) if w not in before]
    if introduced:
        raise QueueToolError(
            f"{command}: the resulting project state would contradict itself; "
            "nothing written:\n  - " + "\n  - ".join(introduced)
        )


def _refresh_build_history_index() -> None:
    """docs/history/INDEX.md is generated from build_history.json and is gated
    by scripts/build_history_index.py --check, so any build_history write must
    regenerate it in the same command. No-op when BUILD_HISTORY has been
    redirected away from the repository file (tests against a fixture copy),
    because the generator reads the real repository path."""
    if BUILD_HISTORY != REPO / "project" / "build_history.json":
        return
    import build_history_index

    build_history_index.INDEX.write_text(build_history_index.render(), encoding="utf-8")


def cmd_build_add(args: argparse.Namespace) -> int:
    """Add a build_history record AND advance the roadmap pointer in one
    operation.

    build_history.json is newest-first by its own record_contract, so a record
    inserted at the head *is* the current build -- utils.project_plan rule R1
    requires roadmap now_next.now.build and current_build to equal it. Adding
    the record without moving the pointer leaves project/ contradicting itself,
    and the pointer may not be hand-edited (GOV.ORCH.5 / GOV.ORCH.8). So the
    two writes are one transaction, and --advance-now is mandatory: the caller
    states the pointer move in the command rather than having it happen
    implicitly. Recording an older build *behind* the head is a different
    operation with its own ordering contract and is not supported here.
    """
    from utils.project_plan import STATUS_VALUES

    if args.status not in STATUS_VALUES:
        raise QueueToolError(
            f"build add: status {args.status!r} is outside the build_history status "
            f"vocabulary ({', '.join(sorted(STATUS_VALUES))})"
        )
    full_summary = redact_narrative(args.summary or "")
    if _sentence_count(full_summary) > 2:
        raise QueueToolError(
            "build add: summary is longer than the 2 sentences build_history.json's "
            "record_contract allows; put the detail in the linked document"
        )

    history = _load_build_history()
    builds = history.setdefault("builds", [])
    if _find_build(history, args.build):
        raise QueueToolError(f"build add: duplicate build {args.build!r} already exists")

    new_row = {
        "build": args.build,
        "status": args.status,
        "title": args.title,
        "summary": _truncate(full_summary, SUMMARY_TRUNCATE),
    }
    if args.started:
        new_row["started"] = args.started
    if args.completed:
        new_row["completed"] = args.completed
    new_row["movement"] = args.movement or ""
    docs = {
        key: value for key, value in (
            ("agreement", args.doc_agreement),
            ("validation", args.doc_validation),
            ("handover", args.doc_handover),
        ) if value
    }
    new_row["docs"] = docs
    if len(full_summary) > SUMMARY_TRUNCATE:
        new_row["detail"] = f"docs/history/builds/{args.build}.md"
    builds.insert(0, new_row)

    # Pointer advance, same transaction. Existing keys keep their order; the
    # displaced NOW is not rewritten anywhere -- its build_history record and
    # its now_next detail document both stay as they are.
    roadmap = load_json(ROADMAP)
    now_next = roadmap.setdefault("now_next", {})
    _promote_next(now_next, args.build)
    new_now = {"build": args.build, "title": args.title, "status": args.status}
    if args.started:
        new_now["started"] = args.started
    if args.completed:
        new_now["completed"] = args.completed
    if args.doc_agreement:
        new_now["contract_doc"] = args.doc_agreement
    now_next["now"] = new_now
    roadmap["current_build"] = args.build

    _assert_no_new_contradiction("build add", history, roadmap)

    write_json(BUILD_HISTORY, history)
    write_json(ROADMAP, roadmap)
    QUEUE.write_text(render_queue_md(load_json(BACKLOG), roadmap, load_backlog_terminal()), encoding="utf-8")
    _refresh_build_history_index()
    if full_summary:
        _write_build_history_doc({**new_row, "_full_summary": full_summary})
    print(f"added build {args.build!r}; roadmap now_next.now and current_build -> {args.build!r}")
    # CURRENT_STATE.md is prose (authority level 4) and cannot be derived, so it
    # is not tool-written -- but tests/test_architecture_convergence.py gates it
    # against this pointer. Name it rather than letting it go quietly stale.
    if args.build not in (REPO / "CURRENT_STATE.md").read_text(encoding="utf-8"):
        print(
            f"  NOTE: CURRENT_STATE.md 'Active build' still names an older build; "
            f"update it to {args.build!r} (prose, hand-authored, not written by this tool)"
        )
    return 0


def cmd_build_status(args: argparse.Namespace) -> int:
    history = _load_build_history()
    build = _find_build(history, args.build)
    if build is None:
        raise QueueToolError(f"build status: no such build {args.build!r}")
    build["status"] = args.set
    roadmap = load_json(ROADMAP)
    if str((roadmap.get("now_next") or {}).get("now", {}).get("build") or "") == args.build:
        roadmap["now_next"]["now"]["status"] = args.set
    _assert_no_new_contradiction("build status", history, roadmap)
    write_json(BUILD_HISTORY, history)
    write_json(ROADMAP, roadmap)
    QUEUE.write_text(render_queue_md(load_json(BACKLOG), roadmap, load_backlog_terminal()), encoding="utf-8")
    _refresh_build_history_index()
    print(f"{args.build!r} -> {args.set}")
    return 0


def cmd_build_note(args: argparse.Namespace) -> int:
    """Append a redacted note to docs/history/builds/<build>.md's Evidence
    section; never writes narrative into build_history.json."""
    history = _load_build_history()
    build = _find_build(history, args.build)
    if build is None:
        raise QueueToolError(f"build note: no such build {args.build!r}")
    HISTORY_BUILDS_DIR.mkdir(parents=True, exist_ok=True)
    path = _build_history_doc_path(args.build)
    text = redact_narrative(args.text)
    if path.exists():
        existing = path.read_text(encoding="utf-8")
        path.write_text(existing.rstrip("\n") + "\n\n" + text + "\n", encoding="utf-8")
    else:
        path.write_text(f"# {args.build} — {build.get('title', '')}\n\n## Evidence\n\n{text}\n", encoding="utf-8")
    if not build.get("detail"):
        build["detail"] = f"docs/history/builds/{args.build}.md"
        write_json(BUILD_HISTORY, history)
    print(f"noted build {args.build!r} -> {_display_path(path)}")
    return 0


# --------------------------------------------------------------------------
# decision open / decide  (GOV.ORCH.8 2.3 -- "decision" mirrors "decide" but
# also covers opening a new roadmap decision)
# --------------------------------------------------------------------------

def cmd_decision(args: argparse.Namespace) -> int:
    return args.decision_func(args)


def cmd_decision_open(args: argparse.Namespace) -> int:
    roadmap = load_json(ROADMAP)
    decisions = roadmap.setdefault("open_decisions", [])
    if any(d.get("id") == args.id for d in decisions):
        raise QueueToolError(f"decision open: duplicate decision id {args.id!r} already exists")
    decisions.append({
        "id": args.id,
        "area": args.area or "",
        "status": "open",
        "question": args.question,
        "options": args.options or "",
        "recommendation": args.recommendation or "",
        "decide_by": args.decide_by,
    })
    write_json(ROADMAP, roadmap)
    backlog, backlog_terminal = load_backlog_both()
    QUEUE.write_text(render_queue_md(backlog, roadmap, backlog_terminal), encoding="utf-8")
    print(f"opened decision {args.id!r}")
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
    p_add.add_argument(
        "--status", default="planned", choices=ALL_STATUSES,
        help="GOV.ORCH.9: a terminal status lands the new item in the reserve "
             "directly (default: planned, in the active set)",
    )
    p_add.set_defaults(func=cmd_add)

    p_status = sub.add_parser("status", help="update a backlog item's status")
    p_status.add_argument("--id", required=True)
    p_status.add_argument("--set", required=True, choices=ALL_STATUSES)
    p_status.add_argument("--target", default=None)
    p_status.set_defaults(func=cmd_status)

    p_retitle = sub.add_parser("retitle", help="correct a backlog item's title/target")
    p_retitle.add_argument("--id", required=True)
    p_retitle.add_argument("--title", default=None)
    p_retitle.add_argument("--target", default=None)
    p_retitle.set_defaults(func=cmd_retitle)

    p_migrate_terminal = sub.add_parser(
        "migrate-backlog-terminal",
        help="GOV.ORCH.9: one-time split of backlog.json into the active set "
             "and archive/backlog_terminal.json",
    )
    p_migrate_terminal.set_defaults(func=cmd_migrate_backlog_terminal)

    p_migrate = sub.add_parser(
        "migrate-open-notes",
        help="GOV.ORCH.5 amendment (proposed): move open-item notes to docs/history/backlog/",
    )
    p_migrate.add_argument(
        "--include-open", action="store_true", required=True,
        help="required: confirms this move of in_progress/planned item notes",
    )
    p_migrate.set_defaults(func=cmd_migrate_open_notes)

    p_note = sub.add_parser("note", help="append a note to docs/history/backlog/<id>.md")
    p_note.add_argument("--id", required=True)
    p_note.add_argument("--text", required=True)
    p_note.set_defaults(func=cmd_note)

    p_decide = sub.add_parser("decide", help="close an open roadmap decision")
    p_decide.add_argument("--id", required=True)
    p_decide.add_argument("--decision", required=True)
    p_decide.set_defaults(func=cmd_decide)

    p_build = sub.add_parser("build", help="build_history.json: add|status|note")
    build_sub = p_build.add_subparsers(dest="build_command", required=True)

    p_build_add = build_sub.add_parser("add", help="add a new build_history record")
    p_build_add.add_argument("--build", required=True)
    p_build_add.add_argument("--title", required=True)
    p_build_add.add_argument("--status", required=True)
    p_build_add.add_argument("--movement", default="")
    p_build_add.add_argument("--summary", default="")
    p_build_add.add_argument("--started", default="")
    p_build_add.add_argument("--completed", default="")
    p_build_add.add_argument("--doc-agreement", dest="doc_agreement", default="")
    p_build_add.add_argument("--doc-validation", dest="doc_validation", default="")
    p_build_add.add_argument("--doc-handover", dest="doc_handover", default="")
    p_build_add.add_argument(
        "--advance-now", dest="advance_now", action="store_true", required=True,
        help="required: acknowledge that this record becomes the newest build "
             "history row and therefore roadmap now_next.now / current_build",
    )
    p_build_add.set_defaults(build_func=cmd_build_add)

    p_build_status = build_sub.add_parser("status", help="update a build's status")
    p_build_status.add_argument("--build", required=True)
    p_build_status.add_argument("--set", required=True)
    p_build_status.set_defaults(build_func=cmd_build_status)

    p_build_note = build_sub.add_parser("note", help="append a note to docs/history/builds/<build>.md")
    p_build_note.add_argument("--build", required=True)
    p_build_note.add_argument("--text", required=True)
    p_build_note.set_defaults(build_func=cmd_build_note)

    p_build.set_defaults(func=cmd_build)

    p_decision = sub.add_parser("decision", help="roadmap open_decisions: open|decide")
    decision_sub = p_decision.add_subparsers(dest="decision_command", required=True)

    p_decision_open = decision_sub.add_parser("open", help="open a new roadmap decision")
    p_decision_open.add_argument("--id", required=True)
    p_decision_open.add_argument("--question", required=True)
    p_decision_open.add_argument("--decide-by", dest="decide_by", required=True)
    p_decision_open.add_argument("--area", default="")
    p_decision_open.add_argument("--options", default="")
    p_decision_open.add_argument("--recommendation", default="")
    p_decision_open.set_defaults(decision_func=cmd_decision_open)

    p_decision_decide = decision_sub.add_parser("decide", help="close an open roadmap decision")
    p_decision_decide.add_argument("--id", required=True)
    p_decision_decide.add_argument("--decision", required=True)
    p_decision_decide.set_defaults(decision_func=cmd_decide)

    p_decision.set_defaults(func=cmd_decision)

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
