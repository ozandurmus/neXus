"""Live project-state and governance checks, independent of Python product code."""
import json
import re
import subprocess
import sys
from pathlib import Path

import pytest

pytestmark = pytest.mark.runtime_platform

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "project"


def _load(name: str) -> dict:
    return json.loads((PROJECT / name).read_text(encoding="utf-8"))




def test_project_metadata_has_no_cross_authority_contradictions():
    """The JSON↔JSON gate. Extends the pre-existing `metadata_warnings`
    assertion with the cross-file rules; it was green while three files each
    named a different current build."""
    from utils.project_plan import build_project_plan_payload

    assert build_project_plan_payload()["metadata_warnings"] == []

def test_current_state_names_the_same_active_build_as_the_roadmap():
    """The Markdown↔JSON gate.

    CURRENT_STATE.md is prose and cannot be derived, so the one machine-checkable
    thing is that it names the build the metadata says is current. That single
    token is exactly what went stale before.
    """
    now_build = _load("roadmap.json")["now_next"]["now"]["build"]
    current_state = (ROOT / "CURRENT_STATE.md").read_text(encoding="utf-8")
    assert now_build in current_state, (
        f"CURRENT_STATE.md does not mention the current build {now_build!r} "
        f"declared by project/roadmap.json"
    )

    # "Mentioned anywhere" is not enough, and that weakness was real: the id
    # also appears in the Snapshot's `now_next.next` line, so this gate passed
    # while the "Active build" heading still named a superseded predecessor --
    # exactly the staleness it exists to catch. The current build must be named
    # under that heading.
    heading = "## Active build"
    assert heading in current_state, "CURRENT_STATE.md has no 'Active build' section"
    section = current_state.split(heading, 1)[1]
    section = section.split("\n## ", 1)[0]
    assert now_build in section, (
        f"CURRENT_STATE.md's 'Active build' section does not name {now_build!r}, "
        f"the build project/roadmap.json declares current; it names something else"
    )

def test_current_state_stays_a_checkpoint_not_a_history():
    """AGENTS.md "Handover economy": CURRENT_STATE.md is a hot-path checkpoint
    a cold chat reads in one pass. It had grown to 764 lines by absorbing a
    narrative for eleven predecessor builds that all already had their own
    build_history record and phase doc."""
    lines = (ROOT / "CURRENT_STATE.md").read_text(encoding="utf-8").splitlines()
    assert len(lines) <= 200, (
        f"CURRENT_STATE.md is {len(lines)} lines; predecessor build detail belongs "
        f"in project/build_history.json and its linked phase doc"
    )

def test_build_history_index_is_derived_not_hand_maintained():
    """docs/history/INDEX.md claimed to be generated from build_history.json
    while actually being hand-edited, and drifted to a newest row of `0.7.4`.
    It is now really generated; this proves the checked-in copy is current."""
    result = subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "build_history_index.py"), "--check"],
        capture_output=True, text=True, cwd=str(ROOT),
    )
    assert result.returncode == 0, result.stdout + result.stderr

def test_every_build_history_doc_link_resolves():
    """build_history.json is the designated route into archived detail
    (AGENTS.md). A dead link there silently removes a build's evidence."""
    broken = [
        path
        for build in _load("build_history.json")["builds"]
        for path in (build.get("docs") or {}).values()
        if not (ROOT / path).exists()
    ]
    assert broken == []

def test_ai_handover_if_present_declares_itself_non_authoritative():
    """AI_HANDOVER.md used to compete with CURRENT_STATE.md/roadmap.json as a
    project-state authority. DEV.4 kept it only as an explicitly-labeled
    convenience summary; if it is ever removed entirely that is also fine —
    this test only forbids it silently becoming authoritative again."""
    handover = ROOT / "AI_HANDOVER.md"
    if not handover.exists():
        return
    text = handover.read_text(encoding="utf-8")
    assert "NON-AUTHORITATIVE DERIVED SUMMARY" in text
    assert "DO NOT USE AS PROJECT-STATE AUTHORITY" in text

def test_no_device_write_automation_claim_does_not_reappear():
    """.github/copilot-instructions.md carried a stale absolute claim ("No
    device write/change automation is permitted at the current product
    maturity") that predated the CLASS 1 recovery-write contracts (`RB.x`) and
    contradicted the action taxonomy. DEV.4 removed it; it must not resurface
    verbatim in any canonical governance doc."""
    stale = "No device write/change automation is permitted"
    for doc in (
        "AGENTS.md",
        "AI_START_HERE.md",
        "CURRENT_STATE.md",
        "CLAUDE.md",
        "docs/AI_DEVELOPMENT_PROTOCOL.md",
        ".github/copilot-instructions.md",
    ):
        text = (ROOT / doc).read_text(encoding="utf-8")
        assert stale not in text, f"{doc} reintroduced the stale absolute claim"

def test_no_authoritative_instruction_requires_the_turkish_session_preamble():
    """AI_START_HERE.md's SESSION START schema required a Turkish-language
    stakeholder preamble on every engineering report, and AGENTS.md pointed
    at that template -- contradicting the English working-language contract
    and reproduced verbatim by agents that followed the schema. The rule now
    has one owner (AGENTS.md "Engineering-output language law") and no
    agent instruction surface may require the preamble again. Guards the
    instruction surface only: historical phase/design docs that carry their
    own summary sections are records, not instructions."""
    agents = " ".join((ROOT / "AGENTS.md").read_text(encoding="utf-8").split())
    assert "Engineering-output language law" in agents
    assert "English by default" in agents
    instruction_docs = [
        ROOT / p
        for p in (
            "AGENTS.md",
            "AI_START_HERE.md",
            "CLAUDE.md",
            "docs/AI_DEVELOPMENT_PROTOCOL.md",
            ".github/copilot-instructions.md",
        )
    ]
    instruction_docs += sorted((ROOT / ".github" / "instructions").glob("*.md"))
    instruction_docs += sorted((ROOT / ".github" / "prompts").glob("*.md"))
    turkish_preamble_markers = ("PROJE \u00d6ZET\u0130", "Proje nedir", "Bu g\u00f6rev nedir")
    for doc in instruction_docs:
        text = doc.read_text(encoding="utf-8")
        for marker in turkish_preamble_markers:
            assert marker not in text, (
                f"{doc.relative_to(ROOT)} requires the Turkish session preamble again; "
                "AGENTS.md 'Engineering-output language law' is the single owner"
            )

def test_agents_md_encodes_the_opaque_identifier_law():
    """The identity law that came directly out of the PAN HA serial-matching
    incident (no int() cast, no leading-zero strip, no digit-only
    normalization, no guessed equality) must live in the constitution, not
    only in a chat transcript or a single build's phase doc."""
    text = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    assert "opaque" in text.lower()
    for token in ("MATCH", "MISMATCH", "NOT_EVALUABLE"):
        assert token in text, f"AGENTS.md is missing the {token!r} vocabulary"

def test_command_gate_and_validation_tiers_stay_documented():
    """The network-device command gate and the automated-vs-real-environment
    distinction are the two governance mechanisms every device-facing build
    depends on; they must keep a canonical home."""
    agents = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    protocol = (ROOT / "docs" / "AI_DEVELOPMENT_PROTOCOL.md").read_text(encoding="utf-8")
    assert "network-device command gate" in agents
    assert "network-device command gate" in protocol
    assert "real-environment validation" in agents or "real-environment evidence" in agents
    assert "AUTOMATED_VALIDATED" in agents and "REAL_ENV_VALIDATED" in agents

def test_agents_md_encodes_evidence_identity_and_readiness_distinctions():
    """Two of the eleven evidence-law pairs are load-bearing enough to check
    directly: an evidence-plane identity is not an operational identity, and a
    green readiness assessment is not itself an authorization to act."""
    text = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    assert "Evidence identity != operational identity" in text
    assert "Readiness != authorization" in text

def test_agent_relay_governance_has_one_shared_bootstrap_and_required_pointers():
    """GOV.RELAY.1 is one shared protocol, not tool-specific relay folklore."""
    contract = ROOT / "docs" / "design" / "NEXUS_AGENT_RELAY_PROTOCOL.md"
    bootstrap = ROOT / ".github" / "prompts" / "relay-bootstrap.prompt.md"
    assert contract.is_file()
    assert bootstrap.is_file()
    for relative in (
        "AGENTS.md",
        "AI_START_HERE.md",
        "CLAUDE.md",
        ".github/copilot-instructions.md",
        ".github/prompts/build-start.prompt.md",
        ".github/prompts/build-close.prompt.md",
    ):
        text = (ROOT / relative).read_text(encoding="utf-8")
        assert ".github/prompts/relay-bootstrap.prompt.md" in text, relative

def test_git_authority_has_one_unambiguous_execution_meaning():
    """PO control is an authorization boundary, not a manual-click mandate."""
    agents = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
    detail = (ROOT / "docs" / "AI_DEVELOPMENT_PROTOCOL.md").read_text(encoding="utf-8")
    assert "## Git authority and execution law" in agents
    assert "does not require the human to type the command or click" in agents
    assert "Do not ask for the same permission again" in agents
    assert "Corporate Git push/merge remains human-controlled" not in agents
    assert "approval controls the decision, not who operates" in detail

_STATUS_HEADING_RE = re.compile(r"^## Status\s*$", re.MULTILINE)

_DRAFT_STATUS_MARKERS = ("DRAFT", "DO NOT FREEZE")

_FROZEN_STATUS_MARKERS = ("FROZEN",)

def _contract_doc_status_line(path: Path) -> str:
    """The first non-blank line following a doc's '## Status' heading -- the
    convention every phase/design doc in this repository already follows for
    declaring DRAFT / DO NOT FREEZE / CONTRACT_FROZEN / SUPERSEDED /
    DEPRECATED (AGENTS.md Contract-status law)."""
    match = _STATUS_HEADING_RE.search(path.read_text(encoding="utf-8"))
    if not match:
        return ""
    for line in path.read_text(encoding="utf-8")[match.end():].splitlines():
        if line.strip():
            return line.strip()
    return ""

def test_a_draft_contract_never_backs_a_terminal_build_history_record():
    """The machine check for: 'A DRAFT / DO NOT FREEZE contract cannot be
    treated as the current FROZEN implementation authority.' Walks every doc
    a build_history.json record cites, and where that doc's own status line
    says DRAFT/DO NOT FREEZE (and not FROZEN), asserts the citing record's
    own status is not one that claims a finished, authoritative outcome. This
    is authority-semantics, not prose-matching: it keys off the doc's self-
    declared status token, so it holds for any future DRAFT contract, not
    only OP.0b.0."""
    from utils.project_plan import _TERMINAL_BUILD_STATUSES

    for build in _load("build_history.json")["builds"]:
        for doc_path in (build.get("docs") or {}).values():
            full = ROOT / doc_path
            if full.suffix != ".md" or not full.exists():
                continue
            status_line = _contract_doc_status_line(full)
            if not status_line:
                continue
            is_draft = any(marker in status_line for marker in _DRAFT_STATUS_MARKERS)
            is_frozen = any(marker in status_line for marker in _FROZEN_STATUS_MARKERS)
            if not is_draft or is_frozen:
                continue
            build_status = str(build.get("status") or "")
            assert build_status not in _TERMINAL_BUILD_STATUSES, (
                f"{doc_path} status line ({status_line!r}) is DRAFT/DO NOT FREEZE, "
                f"but build_history record {build.get('build')!r} claims terminal "
                f"status {build_status!r} -- a draft cannot back frozen authority"
            )
