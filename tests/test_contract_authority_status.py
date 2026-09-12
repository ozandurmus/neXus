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
evidence/precedent block that states it authorizes nothing. The two entries keyed on
`UI2_0_ARCHITECTURE_DESIGN.md` as a *citer* were also deleted: they existed
only because that document misclassified as frozen. It is a DRAFT, and one
DRAFT citing another is not a FROZEN-cites-DRAFT violation, so it is outside
this gate's scope — not an exemption.

**Detector gap closed 2026-09-12.** `_classify` matched the bare token
`FROZEN` anywhere in the status line, so `UI2_0_ARCHITECTURE_DESIGN.md`'s line
— "DRAFT — design resolved, NOT frozen, NOT implementation authority" —
classified as `frozen`. The gate was blind in the worst direction: it read a
DRAFT as a frozen contract, so every citation *of* it went unchecked. It now
reads the declared token, the leading one before the first dash or comma,
falling back to the whole line only when that prefix names none.

That fix surfaced eleven further findings, all catalogued below and none
reconciled here. The load-bearing ones are structural rather than stray:
`UI2_0_ARCHITECTURE_DESIGN.md` (DRAFT) sits at item 4 of the authority chain
of **four** FROZEN C-series contracts — `C1` §1, `C2` §1.3, `C3` §1.4,
`C4` §1.3 — and `UI2_0_D1_DEVICE_WRITE_CLASS_AND_STEP_KIND_DECISION.md`
(DRAFT) is cited by `UI2_0_BASELINE_CONTRACT.md` §2's decision table. The
whole UI 2.0 C-series therefore rests on a document that says of itself it is
not implementation authority. That is a Product Owner adjudication, not an
engineering edit: rewriting four frozen contracts to re-rank their own
authority chain would change what they require.

Entries marked DETECTOR FALSE POSITIVE are citations this gate flags but that
carry no authority claim — a read list, a "reference-only, not ported"
exclusion, or this gate's own contradiction report. They are catalogued rather
than silently skipped so that tightening `_PROVENANCE_MARKERS` later is a
visible change with a known expected effect.
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
    # --- REAL CONTRADICTIONS, surfaced 2026-09-12 by the _classify fix. ---
    # A DRAFT stands in a FROZEN contract's own declared authority chain. Same
    # class as the B1-1 defect, one layer deeper: these are the C-series.
    # Product Owner adjudication required — re-ranking a frozen contract's
    # authority chain changes what that contract requires.
    ("UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md",
     "UI2_0_ARCHITECTURE_DESIGN.md",
     "3449b4a234f146951b420f1251ae228971d2b36fd0b57682567b01c0c42fc9a2"):
        "REAL CONTRADICTION — §1 authority chain item 4 ranks a DRAFT as authority; C1 §9 (storage) and §10 (invariants) rely on it in body",
    ("UI2_0_C2_JOB_EXECUTION_CONTRACT.md",
     "UI2_0_ARCHITECTURE_DESIGN.md",
     "66e74d90764e9f06ec41287bb16e643add54f2b10b9b8c7cc290eb0ce2d7d3f5"):
        "REAL CONTRADICTION — §1.3 authority chain ranks a DRAFT as authority",
    ("UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md",
     "UI2_0_ARCHITECTURE_DESIGN.md",
     "c98ea099eed59f63bfb3308b084a24f6b768179c2d3e1cc81506ca5ecd460afc"):
        "REAL CONTRADICTION — §1.4 authority chain item 4 ranks a DRAFT as authority; §6's U-4 disposition reads through it",
    ("UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md",
     "UI2_0_ARCHITECTURE_DESIGN.md",
     "d651908d1e65ad900dc76cea399a7c05c34de424596a949269ad53071d6d47c7"):
        "REAL CONTRADICTION — §1.3 authority chain ranks a DRAFT as authority",
    ("UI2_0_BASELINE_CONTRACT.md",
     "UI2_0_D1_DEVICE_WRITE_CLASS_AND_STEP_KIND_DECISION.md",
     "2ac37736d29723d81bea9ca73a960d437189454a7e211360919be290f44a4ea9"):
        "REAL CONTRADICTION — §2's Phase 0 decision table cites a document whose own status is 'DRAFT — OPTION A SELECTED IN RELAY; FROZEN BASELINE AMENDMENT PENDING'; the amendment is pending, so the decision is not yet frozen authority",
    ("UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md",
     "UI2_0_D1_DEVICE_WRITE_CLASS_AND_STEP_KIND_DECISION.md",
     "8a11cba89570c68c8db28f7a2fb9ded0fd8651eeb4f439e20f169bc90d7a6da7"):
        "REAL CONTRADICTION — a FROZEN-but-NOT-YET-APPLIED amendment bundle resting on the same pending DRAFT decision",
    ("UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md",
     "UI2_0_D1_DEVICE_WRITE_CLASS_AND_STEP_KIND_DECISION.md",
     "5c94a641d2f436fc9035a3f39eaff405e1f8ead30c3dc957abc2143387200e0f"):
        "REAL CONTRADICTION — Step 5's proposed baseline row derives from the same pending DRAFT decision",

    # --- DETECTOR FALSE POSITIVES. No authority is claimed in these. ---
    ("UI2_0_C2_JOB_EXECUTION_CONTRACT.md",
     "UI2_0_ARCHITECTURE_DESIGN.md",
     "2dd6b1a9a684c85ebb3707fac6f2e6e8091d2cc60abab11832cd6951a88c3b03"):
        "DETECTOR FALSE POSITIVE — §10's 'Documents checked' read list, in a section reporting no contradictions",
    ("UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md",
     "UI2_0_ARCHITECTURE_DESIGN.md",
     "9b71604037ea0457ff8f5243d82ef8bd6febfd01515e574e6b5978b010da49c8"):
        "DETECTOR FALSE POSITIVE — Correction C-1's own report of this very contradiction, which says 'Not reconciled here, reported instead'",
    ("UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md",
     "UI2_0_ARCHITECTURE_DESIGN.md",
     "9dda89653888bd8f491773a8f437b8ec2dde4dbd601665f52c18dad5d3109722"):
        "DETECTOR FALSE POSITIVE — §1.4 'Reference-only, not ported' explicitly refuses the cited profile as a command sequence",
    ("UI2_0_C5_AMENDMENTS_BUNDLE.md",
     "UI2_0_ARCHITECTURE_DESIGN.md",
     "d3c6768d8de2b33a80859687f3e15a8400557394b881c2c2533cf08025c533bd"):
        "DETECTOR FALSE POSITIVE — §2 names the design document as the target of an amendment, not as authority over C5",
    ("UI2_0_C5_AMENDMENTS_BUNDLE.md",
     "UI2_0_ARCHITECTURE_DESIGN.md",
     "3f0dd77fedaa4a91d52f2355adc88c5e8485d942104a5ecd5410b684fac68fd3"):
        "DETECTOR FALSE POSITIVE — §7's 'Documents checked while writing this' read list",
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

    # Match the declared token first, not any occurrence of it. A status line
    # reading "DRAFT — design resolved, NOT frozen, NOT implementation
    # authority" contains the substring FROZEN and classified as frozen,
    # which made this gate blind in the worst direction: it read a DRAFT as a
    # frozen contract, so every citation *of* that document went unchecked.
    # The declared status is the leading token, before the first dash or
    # comma; the whole line is only a fallback when that prefix names none.
    prefix = re.split(r"[—–\-,:.]", upper.lstrip("*# "), maxsplit=1)[0]
    for scope in (prefix, upper):
        if any(marker in scope for marker in _RETIRED_MARKERS):
            return "retired"
        if any(marker in scope for marker in _DRAFT_MARKERS):
            return "draft"
        if any(marker in scope for marker in _FROZEN_MARKERS):
            return "frozen"
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
