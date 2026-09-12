"""GOV.ORCH.8 2.4 / AC-1, AC-6, AC-7, amended by GOV.ORCH.9 section 3: size
budgets for the project planning files, enforced so narrative cannot simply
be re-appended in place and grow these files back to where this movement
found them.

GOV.ORCH.9 (2026-09-13, "the active set and terminal reserve"): backlog.json
now carries only `planned`/`in_progress` items -- every `done`/`deferred`/
`automated_validated`/`real_env_validated` item moved to
project/archive/backlog_terminal.json (docs/design/
GOV_ORCH_9_BACKLOG_ACTIVE_SET_AND_TERMINAL_RESERVE.md). backlog.json is
capped at 40 KiB, enforced with no known-gap escape clause; the reserve is
capped at 60 KiB, enforced. The prior 63 KiB regression ceiling and the
conditional assertion that tolerated backlog.json sitting above 60 KiB are
both removed: after the split they described a state that no longer exists.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROJECT = ROOT / "project"

BACKLOG_LIMIT = 40 * 1024
BACKLOG_TERMINAL_LIMIT = 60 * 1024
ROADMAP_LIMIT = 40 * 1024
BUILD_HISTORY_LIMIT = 80 * 1024
FEATURE_REGISTRY_LIMIT = 70 * 1024
QUEUE_WORD_LIMIT = 1500

#: 2.4: no field named `note`, `evidence`, `risks_forward` longer than 400
#: chars in any project JSON.
_LONG_TEXT_FIELDS = ("note", "evidence", "risks_forward")
_LONG_TEXT_LIMIT = 400


def _size(name: str) -> int:
    return len((PROJECT / name).read_bytes())


def test_backlog_json_within_budget():
    size = _size("backlog.json")
    assert size <= BACKLOG_LIMIT, (
        f"project/backlog.json grew to {size} bytes -- above the "
        f"{BACKLOG_LIMIT} byte GOV.ORCH.9 active-set budget"
    )


def test_backlog_terminal_archive_within_budget():
    size = len((PROJECT / "archive" / "backlog_terminal.json").read_bytes())
    assert size <= BACKLOG_TERMINAL_LIMIT, (
        f"project/archive/backlog_terminal.json grew to {size} bytes -- above "
        f"the {BACKLOG_TERMINAL_LIMIT} byte GOV.ORCH.9 reserve budget"
    )


def test_roadmap_json_within_budget():
    assert _size("roadmap.json") <= ROADMAP_LIMIT


def test_build_history_json_within_budget():
    assert _size("build_history.json") <= BUILD_HISTORY_LIMIT


def test_feature_registry_json_within_budget():
    assert _size("feature_registry.json") <= FEATURE_REGISTRY_LIMIT


def test_queue_md_within_word_budget():
    words = (PROJECT / "QUEUE.md").read_text(encoding="utf-8").split()
    assert len(words) < QUEUE_WORD_LIMIT


def _iter_project_json_values(root):
    if isinstance(root, dict):
        for key, value in root.items():
            yield key, value
            yield from _iter_project_json_values(value)
    elif isinstance(root, list):
        for item in root:
            yield from _iter_project_json_values(item)


#: backlog.json is GOV.ORCH.5's own file, out of GOV.ORCH.8 scope (see
#: module docstring) -- it is not touched here and pre-existing long
#: `note` fields in it are not this movement's regression to fix.
_FIELD_RULE_FILES = ("roadmap.json", "build_history.json", "feature_registry.json")


def test_no_long_text_field_in_any_project_json():
    for path in sorted(PROJECT.glob("*.json")):
        if path.name not in _FIELD_RULE_FILES:
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        for key, value in _iter_project_json_values(data):
            if key in _LONG_TEXT_FIELDS and isinstance(value, str):
                assert len(value) <= _LONG_TEXT_LIMIT, (
                    f"{path.name}: field {key!r} is {len(value)} chars "
                    f"(> {_LONG_TEXT_LIMIT}) -- move it to docs/history/ and "
                    f"leave a pointer"
                )


#: The pointer form GOV.ORCH.5's terminal-item move and its 2026-09-12
#: open-item amendment (section 8) both leave behind -- see
#: scripts/project_queue.py `_history_heading_body`/`_move_note_to_history`/
#: `_migrate_note_to_history`.
_BACKLOG_NOTE_POINTER_RE = re.compile(r"^see docs/history/backlog/[^/\s]+\.md$")


def test_no_backlog_item_note_longer_than_pointer_form():
    data = json.loads((PROJECT / "backlog.json").read_text(encoding="utf-8"))
    for item in data.get("items") or []:
        note = item.get("note") or ""
        assert note == "" or _BACKLOG_NOTE_POINTER_RE.match(note), (
            f"backlog item {item.get('id')!r} carries a note longer than the "
            f"pointer form ({note!r}); move it with "
            "py scripts/project_queue.py note/status/migrate-open-notes"
        )


def test_no_long_text_field_in_archive_json():
    archive_dir = PROJECT / "archive"
    if not archive_dir.is_dir():
        return
    for path in sorted(archive_dir.glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        for key, value in _iter_project_json_values(data):
            if key in _LONG_TEXT_FIELDS and isinstance(value, str):
                assert len(value) <= _LONG_TEXT_LIMIT
