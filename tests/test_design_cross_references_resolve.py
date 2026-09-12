"""Every `docs/design/<file>.md` path a design document cites must exist.

A contract that points at a document which is not there is a contract whose
authority chain cannot be followed — and the authority-status gate
(`tests/test_contract_authority_status.py`) cannot help, because it classifies
a cited document by reading its status line and a nonexistent file has none.
So a typo in a path does not merely break a link: it removes that citation
from every authority check in this repository, silently.

This gate was written after exactly that: `UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md`
§5.3 cited `docs/design/D1_DEVICE_WRITE_CLASS_AND_STEP_KIND_DECISION.md`,
missing the `UI2_0_` prefix the real file carries. It was found by reading, not
by a test.

Scope is `docs/design/*.md` only. `docs/history/**` is historical record and is
allowed to name documents that have since been renamed or removed.
"""
from __future__ import annotations

import re
from pathlib import Path

DESIGN_DIR = Path(__file__).resolve().parent.parent / "docs" / "design"

_REFERENCE_RE = re.compile(r"docs/design/([A-Za-z0-9_.\-]+\.md)")

# Placeholders inside illustrative examples, not real citations. Each entry is
# the exact cited name plus the file it appears in, so a real citation that
# happens to share a name is never exempted.
_EXAMPLE_PLACEHOLDERS = {
    ("LOCAL_RELAY_PROTOCOL.md", "SOME_CONTRACT.md"),
}


def _citations() -> list[tuple[str, str]]:
    found: list[tuple[str, str]] = []
    for path in sorted(DESIGN_DIR.glob("*.md")):
        text = path.read_text(encoding="utf-8")
        for cited in sorted(set(_REFERENCE_RE.findall(text))):
            found.append((path.name, cited))
    return found


def test_the_scan_finds_citations_at_all():
    """Guards against the whole gate passing because it matched nothing."""
    citations = _citations()
    assert len(citations) > 100, (
        f"only {len(citations)} citations found across docs/design — the scan is "
        "probably broken, and a gate that scans nothing proves nothing"
    )


def test_every_cited_design_document_exists():
    broken = [
        f"{citing} -> docs/design/{cited}"
        for citing, cited in _citations()
        if (citing, cited) not in _EXAMPLE_PLACEHOLDERS
        and not (DESIGN_DIR / cited).exists()
    ]
    assert broken == [], (
        "design documents cite paths that do not exist. A broken path also "
        "removes that citation from the authority-status gate, because a "
        "nonexistent file has no status line to classify:\n  "
        + "\n  ".join(broken)
    )


def test_every_placeholder_exemption_is_still_a_placeholder():
    """An exemption that no longer matches anything must be deleted, so the
    list cannot quietly grandfather a citation that has since become real."""
    citations = set(_citations())
    stale = sorted(entry for entry in _EXAMPLE_PLACEHOLDERS if entry not in citations)
    assert stale == [], f"delete these stale placeholder exemptions: {stale}"
