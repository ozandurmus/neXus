"""Generate `docs/history/INDEX.md` from `project/build_history.json`.

    py scripts/build_history_index.py            # rewrite the file
    py scripts/build_history_index.py --check     # exit 1 if it is stale

`INDEX.md` already claimed in its own header to be "Generated from
project/build_history.json" — it was not. It was hand-maintained, and it had
drifted to a newest row of `0.7.4` while the history itself carried dozens of
later builds. A claim of being derived is only worth anything if something
actually derives it, so this script does, and
`tests/test_architecture_convergence.py` fails when the checked-in file no
longer matches.

Offline, no network, no credentials. Reads two repository files and writes one.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
HISTORY = REPO / "project" / "build_history.json"
INDEX = REPO / "docs" / "history" / "INDEX.md"

#: Long enough to be useful at a glance, short enough that the table stays a
#: table. The full text is in build_history.json; the detail is in the linked doc.
SUMMARY_LIMIT = 400

HEADER = """# Build History Index

One line per build, newest first. **Generated** from `project/build_history.json`
by `scripts/build_history_index.py` — do not hand-edit; edit the JSON and
regenerate. Open a row's linked document only when you need that build's detail.

| Build | Status | Dates | Title | Summary | Docs |
| --- | --- | --- | --- | --- | --- |
"""


def _cell(text: str) -> str:
    """Make a value safe inside a Markdown table cell."""
    return " ".join(str(text or "").split()).replace("|", "\\|")


def _dates(build: dict) -> str:
    started, completed = build.get("started"), build.get("completed")
    if started and completed and started != completed:
        return f"{started} → {completed}"
    return _cell(completed or started or "—")


def _docs(build: dict) -> str:
    docs = build.get("docs") or {}
    if not docs:
        return "—"
    return " ".join(f"[{_cell(kind)}]({path})" for kind, path in sorted(docs.items()))


def render() -> str:
    builds = json.loads(HISTORY.read_text(encoding="utf-8")).get("builds") or []
    rows = []
    for build in builds:
        summary = _cell(build.get("summary"))
        if len(summary) > SUMMARY_LIMIT:
            summary = summary[:SUMMARY_LIMIT].rstrip() + "…"
        rows.append(
            f"| `{_cell(build.get('build'))}` | {_cell(build.get('status')) or '—'} "
            f"| {_dates(build)} | {_cell(build.get('title'))} | {summary} | {_docs(build)} |"
        )
    return HEADER + "\n".join(rows) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="verify the checked-in file matches; do not write")
    args = parser.parse_args()

    expected = render()
    if args.check:
        current = INDEX.read_text(encoding="utf-8") if INDEX.exists() else ""
        if current != expected:
            print("docs/history/INDEX.md is stale — run: py scripts/build_history_index.py")
            return 1
        print("docs/history/INDEX.md is up to date.")
        return 0

    INDEX.write_text(expected, encoding="utf-8")
    print(f"wrote {INDEX.relative_to(REPO)} ({expected.count(chr(10)) - HEADER.count(chr(10))} builds)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
