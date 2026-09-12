"""GOV.ORCH.3 Part A (WORKER.md) and Part C (authority cleanup) --
docs/design/GOV_ORCH_3_WORKER_BRIEF_GIT_GATES_AND_AUTHORITY_CLEANUP.md.
"""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import orchestrator as orch  # noqa: E402

from test_orchestrator import _start_report  # noqa: E402


def _session_start(**overrides) -> dict:
    return {
        "seq": 1, "marker": "SESSION_START", "actor": "po", "timestamp": "t",
        "report": _start_report(**overrides),
    }


# --- AC-A1/AC-A2: golden-file render ---------------------------------------

def test_render_worker_md_golden_file():
    session_start = _session_start(
        objective="Do the thing.",
        movement_type="IMPLEMENTATION",
        scope={"in": ["scripts/x.py"], "out": ["scripts/y.py"]},
        acceptance_criteria=["AC-1: it works"],
        invariants=["never breaks z"],
        risks=["might break z"],
        validation_plan=[
            {"name": "targeted", "argv": ["python3", "-m", "pytest", "-q", "tests/test_x.py"]},
            "manual smoke check",
        ],
        git={"lane": "feature/x", "base": "origin/main"},
        merge_gate="PO review",
    )
    rendered = orch.render_worker_md(movement_id="TEST_MOVEMENT", session_start=session_start, merge_mode="engineer")
    expected = (
        "# WORKER.md\n\n"
        "Movement: TEST_MOVEMENT\n\n"
        "Objective: Do the thing.\n\n"
        "Movement type: IMPLEMENTATION\n\n"
        "## Files you may read\n\n"
        "- scripts/x.py\n"
        "- tests/test_x.py\n\n"
        "## Files you must not touch\n\n"
        "- scripts/y.py\n\n"
        "## Acceptance criteria\n\n"
        "- AC-1: it works\n\n"
        "## Invariants and risks\n\n"
        "Invariants:\n- never breaks z\n\n"
        "Risks:\n- might break z\n\n"
        "## Validation commands\n\n"
        "# targeted\n```\npython3 -m pytest -q tests/test_x.py\n```\n\n"
        "manual:\n- manual smoke check\n\n"
        "## Git\n\n"
        "- base: origin/main\n"
        "- lane: feature/x\n"
        "- merge gate: PO review\n"
        "- merge mode: engineer\n\n"
        "## Relay closeout\n\n" + orch.RELAY_CLOSEOUT_TEXT + "\n\n"
        "## Standing rules\n\n" + orch.STANDING_RULES_TEXT + "\n"
    )
    assert rendered == expected


def test_render_worker_md_appends_no_merge_note_only_in_orchestrator_mode():
    session_start = _session_start()
    engineer = orch.render_worker_md(movement_id="M", session_start=session_start, merge_mode="engineer")
    orchestrator_mode = orch.render_worker_md(movement_id="M", session_start=session_start, merge_mode="orchestrator")
    from orchestrator_providers import NO_MERGE_PROMPT_NOTE
    assert NO_MERGE_PROMPT_NOTE not in engineer
    assert NO_MERGE_PROMPT_NOTE in orchestrator_mode


# --- closeout command: complete, runnable, no inline report, no `py` alias -

def test_relay_closeout_command_is_complete_and_runnable_as_printed():
    text = orch.RELAY_CLOSEOUT_TEXT
    assert "..." not in text
    assert re.search(r"--report\s+\S+", text)
    match = re.search(r"```\n(.+?)\n```", text, re.DOTALL)
    assert match, "expected a fenced closeout command"
    command = match.group(1)
    assert "..." not in command
    tokens = command.split()
    assert "--report" in tokens
    report_value = tokens[tokens.index("--report") + 1]
    assert report_value not in ("-", "")
    assert not report_value.startswith("{")
    assert "--role" in tokens and "engineer" in tokens
    assert "--marker" in tokens and "SESSION_CLOSE" in tokens


def test_relay_closeout_states_report_is_written_to_file_first():
    text = orch.RELAY_CLOSEOUT_TEXT
    assert "file" in text.lower()
    assert "inline" in text.lower()
    assert "--report" in text


def test_relay_closeout_does_not_assume_a_bare_py_interpreter_alias():
    text = orch.RELAY_CLOSEOUT_TEXT
    assert not re.search(r"(^|[\s`])py\s+scripts/local_relay\.py", text)
    assert "python3 scripts/local_relay.py" in text


# --- AC-4: exactly one pull-request instruction per merge mode ------------

def test_orchestrator_merge_mode_gives_one_pr_instruction_matching_the_merge_gate():
    from orchestrator_providers import NO_MERGE_PROMPT_NOTE, NO_MERGE_NO_PR_PROMPT_NOTE

    # Ordinary orchestrator merge gate: worker still opens the PR, just
    # never merges it -- exactly the fixed note, nothing contradicting it.
    ordinary = _session_start(merge_gate="PO review plus orchestrator verify.passed.")
    rendered = orch.render_worker_md(movement_id="M", session_start=ordinary, merge_mode="orchestrator")
    assert NO_MERGE_PROMPT_NOTE in rendered
    assert NO_MERGE_NO_PR_PROMPT_NOTE not in rendered

    # A packet whose own merge gate says the worker does not open a pull
    # request must not also be told to open one as usual.
    no_pr = _session_start(merge_gate="Reviewed by the orchestrator; the worker does not open a pull request.")
    rendered_no_pr = orch.render_worker_md(movement_id="M", session_start=no_pr, merge_mode="orchestrator")
    assert NO_MERGE_NO_PR_PROMPT_NOTE in rendered_no_pr
    assert NO_MERGE_PROMPT_NOTE not in rendered_no_pr
    assert "open the pull request as usual" not in rendered_no_pr.lower()


def test_render_worker_md_carries_every_scope_and_ac_string_verbatim():
    session_start = _session_start(
        scope={"in": ["a.py", "b.py"], "out": ["c.py"]},
        acceptance_criteria=["AC-1", "AC-2"],
        invariants=["inv-1"],
        risks=["risk-1"],
    )
    rendered = orch.render_worker_md(movement_id="M", session_start=session_start, merge_mode="engineer")
    for token in ("a.py", "b.py", "c.py", "AC-1", "AC-2", "inv-1", "risk-1"):
        assert token in rendered


# --- AC-A3: ENGINEER_PROMPT shrunk ------------------------------------------

def test_engineer_prompt_no_longer_mentions_interactive_reading_order():
    for banned in ("AGENTS.md", "AI_START_HERE.md", "CLAUDE.md"):
        assert banned not in orch.ENGINEER_PROMPT
    assert orch.ENGINEER_PROMPT == (
        "Read .nexus/WORKER.md and .nexus/approved_task.json in the current "
        "directory and follow them. Read nothing else unless WORKER.md names it."
    )


# --- AC-A4: WORKER.md never committed (mirrors approved_task.json) ---------

def test_gitignore_excludes_nexus_worker_md():
    gitignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
    assert ".nexus/" in gitignore.splitlines()


def test_worker_md_is_written_under_nexus_dir_alongside_approved_task(tmp_path):
    """WORKER.md lives at `.nexus/WORKER.md`, the same ignored directory as
    `approved_task.json` -- this repository's single `.nexus/` gitignore
    entry (asserted above) therefore excludes it too, with no separate
    pattern to add or forget."""
    session_start = _session_start()
    rendered = orch.render_worker_md(movement_id="M", session_start=session_start, merge_mode="engineer")
    nexus_dir = tmp_path / ".nexus"
    nexus_dir.mkdir()
    (nexus_dir / "WORKER.md").write_text(rendered, encoding="utf-8")
    assert (nexus_dir / "WORKER.md").is_file()


# --- AC-A5: AI_START_HERE.md orchestrated-worker paragraph ------------------

def test_ai_start_here_has_orchestrated_worker_paragraph():
    text = (ROOT / "AI_START_HERE.md").read_text(encoding="utf-8")
    normalized = re.sub(r"\s+", " ", text)
    assert (
        "If `.nexus/WORKER.md` exists in your working directory you are an "
        "orchestrated worker: follow it and skip the reading order below."
    ) in normalized


# --- AC-C1: exactly one SESSION START / SESSION CLOSE template heading -----

_SESSION_START_HEADING = re.compile(r"^#+\s*SESSION START(?!\s+template)", re.MULTILINE)
_SESSION_CLOSE_HEADING = re.compile(r"^#+\s*SESSION CLOSE(?!\s+template)", re.MULTILINE)


def _repo_text_files(exclude_history: bool = True):
    for dirpath, dirnames, filenames in os.walk(ROOT):
        rel_dir = Path(dirpath).relative_to(ROOT)
        if ".git" in rel_dir.parts or "node_modules" in rel_dir.parts:
            dirnames[:] = []
            continue
        if exclude_history and rel_dir.parts[:2] == ("docs", "history"):
            dirnames[:] = []
            continue
        for name in filenames:
            if name.endswith(".md"):
                yield Path(dirpath) / name


def test_exactly_one_session_start_and_session_close_heading_outside_history():
    start_hits, close_hits = [], []
    for path in _repo_text_files():
        text = path.read_text(encoding="utf-8", errors="ignore")
        if _SESSION_START_HEADING.search(text):
            start_hits.append(path)
        if _SESSION_CLOSE_HEADING.search(text):
            close_hits.append(path)
    assert len(start_hits) == 1, f"expected exactly one SESSION START heading, found: {start_hits}"
    assert len(close_hits) == 1, f"expected exactly one SESSION CLOSE heading, found: {close_hits}"
    assert start_hits[0].name == "AI_START_HERE.md"
    assert close_hits[0].name == "AI_START_HERE.md"


# --- AC-C2: no "Codex is ... final reviewer" sentence -----------------------

def test_copilot_operating_model_has_no_codex_final_reviewer_sentence():
    text = (ROOT / "docs/reference/COPILOT_OPERATING_MODEL.md").read_text(encoding="utf-8")
    assert not re.search(r"final reviewer", text, re.IGNORECASE)
    assert "independent seat" in text


def test_copilot_operating_model_session_templates_and_matrix_are_pointers():
    text = (ROOT / "docs/reference/COPILOT_OPERATING_MODEL.md").read_text(encoding="utf-8")
    assert "SESSION START\nProduct baseline:" not in text
    assert "SESSION CLOSE\nBuild/task:" not in text
    assert "AI_START_HERE.md" in text


# --- AC-C3: amendment block marked ratified; FROZEN untouched -

def test_gov_po_role_migration_amendment_block_and_frozen_status():
    text = (ROOT / "docs/design/GOV_PO_ROLE_MIGRATION.md").read_text(encoding="utf-8")
    assert "## Amendment A-2026-09-11 (RATIFIED by the Product Owner, 2026-09-11)" in text
    assert "FROZEN — PRODUCT OWNER APPROVED, 2026-09-07" in text


def test_agents_md_has_contradiction_report_entry():
    text = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    assert "Contradiction report" in text


# --- AC-6/AC-7 (NXS-LOCAL-0126): roles/PO.md pre-dispatch checklist --------

def test_po_role_brief_section_3_has_pre_dispatch_checklist():
    """AC-6/AC-7: `roles/PO.md` section 3 carries the pre-dispatch checklist
    -- budget set from scope, each validation-plan step run once locally,
    the test tree searched before a deletion-shaped movement, the packet
    telling the worker to commit early, and the branch lane confirmed
    unused. This test fails if the checklist is removed."""
    text = (ROOT / "roles/PO.md").read_text(encoding="utf-8")
    section_3 = text.split("## 3.")[1].split("\n## 4.")[0]
    assert "Pre-dispatch checklist" in section_3
    assert "budget defaults low" in section_3
    assert "CONTRACT, AUDIT, or DEPLOYMENT movement will not fit in it" in section_3
    assert "set from this movement's own scope" in section_3
    assert "run once, locally" in section_3
    assert "test tree has been searched" in section_3
    assert "commit early and often" in section_3
    assert "branch lane is confirmed unused" in section_3
