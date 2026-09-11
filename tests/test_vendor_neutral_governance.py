"""GOV.ORCH.7 section 2.2 / AC-3 -- vendor/model names stay out of the
neutral governance surface.

Checked files: `AGENTS.md`, `AI_START_HERE.md`, `CURRENT_STATE.md`,
`roles/*.md`, `docs/reference/COPILOT_OPERATING_MODEL.md`,
`.github/prompts/*.md`. A fixed name list is scanned for; two vendor shims
(`CLAUDE.md`, `.github/copilot-instructions.md`) and
`docs/reference/MODEL_TIER_MAP.md` are the only files allowed to name a
model or vendor product at all -- they are excluded from the scan outright,
not exception-listed, because naming a vendor tool is their entire purpose.

`_ALLOWED_EXCEPTIONS` is the closed list of literal strings inside a
*checked* file that legitimately still contain a banned token, each mapped
to the test (in this suite or another) that pins that exact string. Any
banned token found outside this list, in a checked file, fails the test --
the list must never be widened to "make the test pass" without a named
pinning test justifying each entry.
"""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

#: Vendor/model product names this test bans from the checked governance
#: surface (GOV.ORCH.7 section 2.2's fixed list, verbatim).
_BANNED_TOKENS = (
    "Sonnet", "Opus", "Haiku", "Fable", "Astra", "Terra", "Sol", "GPT",
    "Codex", "Copilot", "Claude",
)

#: Files/globs excluded outright -- their entire purpose is to name a
#: vendor tool or a current model, per GOV.ORCH.7 section 2.2.
_EXCLUDED_FILES = {
    ROOT / "CLAUDE.md",
    ROOT / ".github" / "copilot-instructions.md",
    ROOT / "docs" / "reference" / "MODEL_TIER_MAP.md",
}

#: Checked files/globs (GOV.ORCH.7 section 2.2's own list).
_CHECKED_FILES = [
    ROOT / "AGENTS.md",
    ROOT / "AI_START_HERE.md",
    ROOT / "CURRENT_STATE.md",
    ROOT / "docs" / "reference" / "COPILOT_OPERATING_MODEL.md",
]
_CHECKED_GLOBS = [
    ROOT / "roles",
    ROOT / ".github" / "prompts",
]

#: (relative path, exact literal string, pinning test) -- empty by design:
#: GOV.ORCH.7 removed every vendor/model mention from the checked surface
#: rather than carve exceptions for it. Kept as a structural placeholder so
#: a future genuinely-unavoidable pinned string (e.g. a historical commit
#: trailer quoted verbatim) has one documented place to be added, with its
#: own pinning test named, rather than a bare `# noqa`-style skip.
_ALLOWED_EXCEPTIONS: tuple[tuple[str, str, str], ...] = ()


def _checked_paths():
    for path in _CHECKED_FILES:
        yield path
    for base in _CHECKED_GLOBS:
        for path in sorted(base.glob("*.md")):
            yield path


def _strip_exceptions(relative: str, text: str) -> str:
    for exc_relative, literal, _pinning_test in _ALLOWED_EXCEPTIONS:
        if exc_relative == relative:
            text = text.replace(literal, "")
    return text


def test_no_vendor_or_model_name_outside_the_shims_and_pinned_exceptions():
    violations = {}
    for path in _checked_paths():
        if path in _EXCLUDED_FILES or not path.is_file():
            continue
        relative = str(path.relative_to(ROOT))
        text = _strip_exceptions(relative, path.read_text(encoding="utf-8", errors="ignore"))
        hits = [token for token in _BANNED_TOKENS if token in text]
        if hits:
            violations[relative] = hits
    assert not violations, f"vendor/model names found in checked governance files: {violations}"


def test_allowed_exceptions_each_name_a_real_pinning_test():
    """Every entry in `_ALLOWED_EXCEPTIONS` must name a test that actually
    exists in this repository's test suite -- an exception list is only
    honest if its justification is checkable."""
    import ast

    tests_dir = ROOT / "tests"
    known_tests = set()
    for test_file in tests_dir.glob("test_*.py"):
        tree = ast.parse(test_file.read_text(encoding="utf-8"), filename=str(test_file))
        for node in ast.walk(tree):
            if isinstance(node, ast.FunctionDef) and node.name.startswith("test_"):
                known_tests.add(f"{test_file.name}::{node.name}")

    for _relative, _literal, pinning_test in _ALLOWED_EXCEPTIONS:
        assert pinning_test in known_tests, f"unknown pinning test: {pinning_test!r}"


def test_the_two_shims_are_the_only_files_allowed_to_name_a_vendor():
    """`docs/reference/MODEL_TIER_MAP.md` is also excluded (it is the one
    file whose entire content is the vendor/model mapping), but only
    `CLAUDE.md` and `.github/copilot-instructions.md` are *shims* in the
    contract's sense (section 2.2) -- pinned here so the excluded set
    cannot silently grow beyond what the contract actually names."""
    assert _EXCLUDED_FILES == {
        ROOT / "CLAUDE.md",
        ROOT / ".github" / "copilot-instructions.md",
        ROOT / "docs" / "reference" / "MODEL_TIER_MAP.md",
    }
