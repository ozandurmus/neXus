"""Regression: the generated report's inline <script> must be valid JavaScript.

Root cause of the original defect: ``html_export`` filled the template with a
sequence of ``str.replace()`` calls. ``project/backlog.json`` and
``project/build_history.json`` carry a note that mentions the literal token
``__CRYPTO_JSON_PLACEHOLDER__``; once the project-plan payload was embedded, the
later ``html.replace("__CRYPTO_JSON_PLACEHOLDER__", <crypto json>)`` spliced a
brace/quote-laden JSON object into the middle of the ``projectPlanData`` string
literal, producing a ``SyntaxError`` that stopped the entire script — every nav
button went dead while the static Overview panel still rendered.

The fix: substitute every sentinel in one pass so inserted content is never
re-scanned (``utils.html_export._fill_template``).
"""
import json
import re

from utils import html_export
from utils.html_export import _fill_template, run_html_export
import pytest

pytestmark = pytest.mark.configuration


_SCRIPT_CONSTS = (
    "rawData", "configUiData", "complianceUiData",
    "cryptoUiData", "projectPlanData", "discoveryUiData",
)

_JSON_SENTINELS = (
    "__DATA_JSON_PLACEHOLDER__", "__CONFIG_JSON_PLACEHOLDER__",
    "__COMPLIANCE_JSON_PLACEHOLDER__", "__PROJECT_PLAN_JSON_PLACEHOLDER__",
    "__CRYPTO_JSON_PLACEHOLDER__", "__DISCOVERY_JSON_PLACEHOLDER__",
)


def _render(tmp_path):
    unified = tmp_path / "unified.json"
    unified.write_text("[]", encoding="utf-8")
    output_html = tmp_path / "index.html"
    run_html_export(unified_json=unified, output_html=output_html)
    return output_html.read_text(encoding="utf-8")


def _script_literal(html: str, name: str) -> str:
    # CON.1 C1-2/C1-3: initializeReport({...}) object literal, not a
    # top-level `const name = ...;` declaration — see templates/index.html.
    m = re.search(rf"\b{re.escape(name)}: (.*?),\n", html)
    assert m, f"{name} declaration not found in the generated <script>"
    return m.group(1).replace("<\\/", "</")


def test_every_embedded_payload_is_valid_json(tmp_path):
    """The real project/*.json content (which contains a sentinel token in a
    note) must not corrupt any payload literal."""
    html = _render(tmp_path)
    for name in _SCRIPT_CONSTS:
        json.loads(_script_literal(html, name))  # raises on the pre-fix corruption


def test_project_plan_keeps_the_sentinel_token_as_data(tmp_path, monkeypatch):
    """Proof the token was embedded as text, not expanded: it must still be
    present verbatim inside projectPlanData, and that payload must still parse.

    Whether the *real* project/backlog.json or project/build_history.json
    currently happens to carry a note mentioning the literal sentinel text is
    incidental repository content, not a contract -- the archive split
    (GOV.ORCH.8 2.2) can move that exact record out of the live payload at
    any time (see docs/history/backlog/project_plan_build_history_archive_
    split_lookup.md), and the record_contract for build_history.json does not
    promise any particular note text will persist. A proof that only holds
    while some unrelated backlog/build_history entry happens to contain this
    string is not a proof at all -- it is state that a clean checkout may or
    may not have. This test instead supplies a synthetic project-plan payload
    that is guaranteed to contain the sentinel text in a data field, so the
    property under test -- one-pass substitution never re-scans/expands a
    sentinel that appears verbatim inside already-embedded data -- holds
    identically on a clean checkout and on a warm tree.
    """
    # Deliberately NOT derived from the real project/*.json content -- any
    # field copied from build_project_plan_payload() could itself carry (or
    # lack) the sentinel by repository coincidence, which is exactly the
    # state-dependence being eliminated here.
    synthetic_payload = {
        "schema_version": "1.0",
        "generated_at": "2026-01-01T00:00:00+00:00",
        "current_build": "synthetic",
        "current_track": "synthetic",
        "progress_contract": "",
        "overall_progress_percent": 0,
        "current_track_progress_percent": 0,
        "tracks": [],
        "now_next": {},
        "roadmap_notes": [
            "regression note: embeds __CRYPTO_JSON_PLACEHOLDER__ verbatim as prose"
        ],
        "backlog": [],
        "backlog_counts": {},
        "completed_features": [],
        "build_history": [],
        "archived_build_count": 0,
        "metadata_warnings": [],
    }
    monkeypatch.setattr(html_export, "build_project_plan_payload", lambda: synthetic_payload)

    html = _render(tmp_path)
    plan = _script_literal(html, "projectPlanData")
    assert "__CRYPTO_JSON_PLACEHOLDER__" in plan
    json.loads(plan)


def test_no_json_sentinel_survives_in_a_template_position(tmp_path):
    html = _render(tmp_path)
    # The template carries each sentinel exactly once; after rendering none may
    # remain in a *template* slot. __CRYPTO_JSON_PLACEHOLDER__ legitimately
    # remains as prose inside projectPlanData, so it is checked structurally
    # (valid JSON) above rather than by absence here.
    for sentinel in _JSON_SENTINELS:
        if sentinel == "__CRYPTO_JSON_PLACEHOLDER__":
            continue
        assert sentinel not in html
    assert "/* __STYLE_PLACEHOLDER__ */" not in html
    assert "/* __SCRIPT_PLACEHOLDER__ */" not in html


def test_fill_template_does_not_rescan_inserted_content():
    template = "A=__A__ | B=__B__"
    out = _fill_template(template, {
        "__A__": 'value with __B__ inside it',
        "__B__": "REAL_B",
    })
    assert out == "A=value with __B__ inside it | B=REAL_B"


def test_fill_template_replaces_each_template_sentinel_once():
    template = "__X__ __X__ __LONGER_X__"
    out = _fill_template(template, {"__X__": "1", "__LONGER_X__": "2"})
    assert out == "1 1 2"
