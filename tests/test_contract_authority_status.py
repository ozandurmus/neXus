"""contract_authority_status — a FROZEN contract may not cite a DRAFT one.

`AGENTS.md` "Authority hierarchy" item 2 and "Contract-status law" both state,
in prose, that a `DRAFT` / `DO NOT FREEZE` document "must not be treated as
implementation authority, cited as approving a command/schema/identity model".
Nothing machine-checked the citation direction, and it had already drifted: the
four FROZEN UI 2.0 B1 contracts cited the never-frozen
`UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md` as authority sixteen times before
`UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` was frozen to replace it.

`tests/test_architecture_convergence.py::
test_a_draft_contract_never_backs_a_terminal_build_history_record` checks the
adjacent but different rule (a draft doc backing a terminal *build record*).
This module checks document → document citation.

**Scope** is deliberately narrow and cheap: `docs/design/*.md`, which is where
contracts live. `docs/history/**` is a historical record and is not
implementation authority in the first place (`AGENTS.md` authority hierarchy
item 6); it is explicitly not scanned by default.

**No filename is hardcoded.** Every document's status is read from its own
`## Status` line, so the gate holds for contracts that do not exist yet.

**What counts as a citation of authority.** A reference to another `.md`
document inside a prose paragraph that also carries authority language
(`_AUTHORITY_CUES`) — "per X", an authority chain, "authorized by", "binding",
"mandates", "governs". A bare mention inside a `## Status` line or a
`## Cross-references` list is a pointer, not an authority claim, and those
sections are skipped. A paragraph that marks the reference as historical or
withdrawn (`_PROVENANCE_MARKERS`) is provenance, not authority, and is allowed
— a FROZEN contract that needs to record where a behaviour came from must say
so in the same paragraph.

**Pre-existing findings.** `_KNOWN_DRAFT_AUTHORITY_CITATIONS` records the
findings that already existed when this gate was written (2026-09-12), keyed to
the sha256 of the exact citing paragraph so an edit to any of them re-opens the
gate. They are **unreconciled contradictions reported to the contract owner**,
not approvals. This module's job is to stop the set from growing; closing an
entry is the contract owner's decision, not this test's.

The two `C1` and `C3` authority-chain entries were closed on 2026-09-12 and
their entries deleted: `UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` "Correction C-1"
and `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` "Correction C-1" moved the
cited DRAFT out of each contract's authority chain into a labelled
evidence/precedent block that states it authorizes nothing. What remains
catalogued is `UI2_0_ARCHITECTURE_DESIGN.md`'s own two citations.

**Known detector gap, reported not fixed.** `_classify` matches the bare token
`FROZEN`, so a status line reading "DRAFT ... NOT frozen, NOT implementation
authority" — `UI2_0_ARCHITECTURE_DESIGN.md`'s — classifies as `frozen`. That
document is therefore scanned as a citer (the two entries below) while
citations *of* it go unflagged, including its place in `C1` §1 and `C3` §1.4's
authority chains. Tightening the match would newly fail several FROZEN
contracts, which is a contract-owner adjudication, not a test change; both
corrections name this explicitly.
"""
from __future__ import annotations

import hashlib
import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
DESIGN_DIR = ROOT / "docs" / "design"

_STATUS_HEADING_RE = re.compile(r"^#{2,6} Status\s*$", re.MULTILINE)
_H1_RE = re.compile(r"^# .*$", re.MULTILINE)
_HEADING_SPLIT_RE = re.compile(r"^(#{2,6} .*)$", re.MULTILINE)
# A `.md` document referenced in prose: contracts here cite each other by bare
# filename in backticks or by repo-relative path; both end in the same basename.
_MD_REFERENCE_RE = re.compile(r"([A-Za-z0-9_./-]+\.md)")

_DRAFT_MARKERS = ("DRAFT", "DO NOT FREEZE")
_FROZEN_MARKERS = ("FROZEN",)
_RETIRED_MARKERS = ("SUPERSEDED", "DEPRECATED")

_AUTHORITY_CUES = (
    "per ",
    "authority",
    "authoriz",
    "as required by",
    "binding",
    "mandat",
    "governs",
    "contract home",
)

# Turns a citation into provenance rather than authority.
_PROVENANCE_MARKERS = (
    "superseded",
    "withdrawn",
    "not authority",
    "historical only",
    "never frozen",
    "no longer authority",
)

# Headings whose body is a pointer list or a status declaration, not an
# authority claim.
_SKIPPED_HEADING_TOKENS = ("cross-reference", "status")

# (citing document, cited document, sha256 of the citing paragraph) -> context.
# Keyed on the paragraph hash, never on a line number or a bare filename, so an
# exemption cannot generalize to a new or edited citation.
_KNOWN_DRAFT_AUTHORITY_CITATIONS: dict[tuple[str, str, str], str] = {
("UI2_0_ARCHITECTURE_DESIGN.md",
     "UI2_0_ARCHITECTURE_REQUIREMENTS.md",
     "f17149b4ebdf5f6f5dc76b6e1762c20b44d80d4c620afc22b6b474872bea9654"):
        "## 1. Purpose — the bar (`AC-0`) — prose mention of its own DRAFT evidentiary base; the sentence carries authority language",
    ("UI2_0_ARCHITECTURE_DESIGN.md",
     "PCP_STORAGE_ENGINE_DECISION.md",
     "4813226e125bbc85040ef6239e62b1cb472d27d35c9a466ec5f9d0e7ca0dddcd"):
        "## 12. Still open — must close before freeze or before the named slice — open-items table names the DRAFT storage decision as the closer of U-J1; the closer is itself unfrozen",
}


def _first_nonblank(text: str) -> str:
    for line in text.splitlines():
        if line.strip():
            return line.strip()
    return ""


def _status_line(path: Path) -> str:
    """A document's self-declared status line.

    Two conventions are in use here and both must be read, or whole contract
    families go silently unclassified — which is how the B1 family (status
    declared as a bold line straight under the H1 title, no `## Status`
    heading) escaped the first version of this gate:

    1. the first non-blank line after a `## Status` heading;
    2. otherwise the first non-blank line after the H1 title.

    The returned line is only ever matched for a status *token*, never for
    prose, so convention 2 degrades to `unknown` on a document that opens with
    ordinary text rather than a status declaration.
    """
    text = path.read_text(encoding="utf-8")
    match = _STATUS_HEADING_RE.search(text)
    if match:
        return _first_nonblank(text[match.end():])
    title = _H1_RE.search(text)
    if title:
        return _first_nonblank(text[title.end():])
    return ""


def _classify(status_line: str) -> str:
    """`frozen`, `draft`, `retired` or `unknown`, from the document's own
    declared status token. `retired` wins: a SUPERSEDED document's status line
    routinely still names the draft it used to be."""
    upper = status_line.upper()
    if any(marker in upper for marker in _RETIRED_MARKERS):
        return "retired"
    if any(marker in upper for marker in _FROZEN_MARKERS):
        return "frozen"
    if any(marker in upper for marker in _DRAFT_MARKERS):
        return "draft"
    return "unknown"


def _sections(text: str) -> list[tuple[str, str]]:
    parts = _HEADING_SPLIT_RE.split(text)
    sections = [("(preamble)", parts[0])]
    for index in range(1, len(parts), 2):
        sections.append((parts[index], parts[index + 1]))
    return sections


def _design_docs() -> dict[str, Path]:
    return {path.name: path for path in sorted(DESIGN_DIR.glob("*.md"))}


def _status_by_name() -> dict[str, str]:
    return {name: _classify(_status_line(path)) for name, path in _design_docs().items()}


def _paragraph_key(paragraph: str) -> str:
    """Hash of the paragraph's normalized text — whitespace-collapsed so a
    re-wrap is not a false re-open, but any wording change is."""
    return hashlib.sha256(" ".join(paragraph.split()).encode("utf-8")).hexdigest()


def _findings() -> list[tuple[tuple[str, str, str], str]]:
    """Every FROZEN → DRAFT authority citation in `docs/design/*.md`, as
    ((citing doc, cited doc, paragraph hash), human-readable context)."""
    status = _status_by_name()
    found: list[tuple[tuple[str, str, str], str]] = []
    for name, path in _design_docs().items():
        if status[name] != "frozen":
            continue
        for heading, body in _sections(path.read_text(encoding="utf-8")):
            lowered_heading = heading.lower()
            if any(token in lowered_heading for token in _SKIPPED_HEADING_TOKENS):
                continue
            for paragraph in re.split(r"\n\s*\n", body):
                lowered = paragraph.lower()
                if any(marker in lowered for marker in _PROVENANCE_MARKERS):
                    continue
                if not any(cue in lowered for cue in _AUTHORITY_CUES):
                    continue
                for reference in sorted(set(_MD_REFERENCE_RE.findall(paragraph))):
                    cited = Path(reference).name
                    if cited == name or status.get(cited) != "draft":
                        continue
                    context = f"{heading.strip()} :: {' '.join(paragraph.split())[:140]}"
                    found.append(((name, cited, _paragraph_key(paragraph)), context))
    return found


def _new_findings() -> list[str]:
    return [
        f"{key[0]} (FROZEN) cites {key[1]} (DRAFT/DO NOT FREEZE) as authority — {context}"
        for key, context in _findings()
        if key not in _KNOWN_DRAFT_AUTHORITY_CITATIONS
    ]


def test_the_gate_actually_scanned_something():
    """A gate that scanned nothing proves nothing (B1-1a §3 rule 1). Pin that
    the corpus exists, that status lines parse, and that both statuses this rule
    compares are genuinely present in the repository."""
    status = _status_by_name()
    assert len(status) >= 10, f"docs/design/*.md corpus too small: {len(status)}"
    counts = {value: list(status.values()).count(value) for value in set(status.values())}
    assert counts.get("frozen", 0) >= 5, f"no FROZEN contracts parsed: {counts}"
    assert counts.get("draft", 0) >= 1, f"no DRAFT contracts parsed: {counts}"


def test_no_new_frozen_contract_cites_a_draft_contract_as_authority():
    """`AGENTS.md` Authority hierarchy item 2 / Contract-status law, machine
    checked in the citation direction. Pre-existing findings are catalogued in
    `_KNOWN_DRAFT_AUTHORITY_CITATIONS` and reported to the contract owner; this
    asserts the set does not grow."""
    new = _new_findings()
    assert not new, (
        "FROZEN contracts citing DRAFT/DO NOT FREEZE documents as authority:\n"
        + "\n".join(f"  - {line}" for line in new)
    )


def test_every_catalogued_exemption_still_describes_a_real_citation():
    """A stale exemption is a hole. Every catalogued key must still be produced
    by the detector; once a contract owner fixes one, its entry must be
    deleted."""
    live = {key for key, _ in _findings()}
    stale = sorted(key for key in _KNOWN_DRAFT_AUTHORITY_CITATIONS if key not in live)
    assert not stale, (
        "catalogued FROZEN->DRAFT exemptions no longer match any citation — "
        f"delete them: {stale}"
    )


def test_the_gate_catches_an_injected_violation():
    """Self-proof: the detector must fire on a real FROZEN→DRAFT authority
    citation. Uses the live corpus's own statuses, so it cannot pass
    vacuously."""
    status = _status_by_name()
    frozen = next((name for name, value in status.items() if value == "frozen"), None)
    draft = next((name for name, value in status.items() if value == "draft"), None)
    if not frozen or not draft:
        pytest.fail(f"corpus lacks a FROZEN or a DRAFT contract to prove against: {status}")
    path = DESIGN_DIR / frozen
    original = path.read_text(encoding="utf-8")
    try:
        path.write_text(
            original.rstrip("\n")
            + f"\n\n## 99. Injected\n\nPer `{draft}` §1, this behaviour is authorized.\n",
            encoding="utf-8",
        )
        assert _new_findings(), "detector missed an injected FROZEN -> DRAFT authority citation"
    finally:
        path.write_text(original, encoding="utf-8")
    assert not _new_findings(), "detector left state behind after the injected violation"


def test_an_exemption_does_not_generalize_to_a_new_citation_in_the_same_pair():
    """The allowlist is keyed to an exact paragraph, not to a document pair: a
    second, different citation between the same two documents must still
    fail."""
    if not _KNOWN_DRAFT_AUTHORITY_CITATIONS:
        pytest.skip("no catalogued exemptions to prove non-generalization against")
    citing, cited, _ = next(iter(_KNOWN_DRAFT_AUTHORITY_CITATIONS))
    path = DESIGN_DIR / citing
    original = path.read_text(encoding="utf-8")
    try:
        path.write_text(
            original.rstrip("\n")
            + f"\n\n## 98. Injected\n\nPer `{cited}` §2, a second distinct claim.\n",
            encoding="utf-8",
        )
        assert _new_findings(), (
            f"exemption for ({citing}, {cited}) wrongly generalized to a new citation"
        )
    finally:
        path.write_text(original, encoding="utf-8")
