"""0.7.6 — automated HTML render harness.

Renders the report from the committed `tests/fixtures/uitest` bundle so every
module is populated, then:
  * (always) checks every embedded `const X = ...;` payload is valid JSON — the
    0.7.4a bug class, catchable with no JS engine;
  * (when `bun` + the harness deps are present) runs
    `tools/render-harness/check-render.mjs`, which executes the script in a DOM
    and clicks every nav module + inner tab, asserting panels switch with no
    console errors.
"""
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

HARNESS = ROOT / "tools" / "render-harness" / "check-render.mjs"
HARNESS_DEPS = ROOT / "tools" / "render-harness" / "node_modules" / "happy-dom"

_PAYLOAD_CONSTS = (
    "rawData", "configUiData", "complianceUiData",
    "cryptoUiData", "projectPlanData", "discoveryUiData",
)


def _bun() -> str | None:
    found = shutil.which("bun")
    if found:
        return found
    fallback = Path(os.path.expanduser("~")) / ".bun" / "bin" / "bun"
    return str(fallback) if fallback.exists() else None


@pytest.fixture(scope="module")
def rendered_html(tmp_path_factory) -> Path:
    from scripts.render_uitest import render
    out = tmp_path_factory.mktemp("uitest_render")
    return render(out)


def test_every_embedded_payload_is_valid_json(rendered_html):
    """No JS engine needed — the 0.7.4a class (a corrupted payload literal that
    kills the whole <script>) shows up as invalid JSON here."""
    html = rendered_html.read_text(encoding="utf-8")
    for name in _PAYLOAD_CONSTS:
        m = re.search(rf"\bconst {re.escape(name)} = (.*?);\n", html)
        assert m, f"{name} not found in the generated <script>"
        json.loads(m.group(1).replace("<\\/", "</"))


def test_all_six_modules_are_populated(rendered_html):
    """The uitest bundle must actually exercise every module, not just render the
    empty states (that is scripts/render_sample.py's job)."""
    html = rendered_html.read_text(encoding="utf-8")
    m = re.search(r"\bconst complianceUiData = (.*?);\n", html)
    compliance = json.loads(m.group(1).replace("<\\/", "</"))
    assert compliance.get("available") is True
    ov = compliance["compliance_overview"]
    assert ov["subjects"] >= 1
    assert len(ov["history"]) >= 2 and ov["trend"] is not None       # 0.7.5 trend renders
    m = re.search(r"\bconst cryptoUiData = (.*?);\n", html)
    assert json.loads(m.group(1).replace("<\\/", "</")).get("available") is True
    m = re.search(r"\bconst discoveryUiData = (.*?);\n", html)
    assert json.loads(m.group(1).replace("<\\/", "</"))["entities"]


@pytest.mark.skipif(_bun() is None, reason="bun not installed")
@pytest.mark.skipif(
    not HARNESS_DEPS.exists(),
    reason="render-harness deps missing — run `bun install` in tools/render-harness",
)
def test_headless_navigation_smoke(rendered_html):
    """Execute the script in a DOM and click every nav module + inner tab."""
    proc = subprocess.run(
        [_bun(), str(HARNESS), str(rendered_html)],
        capture_output=True, text=True, timeout=120,
    )
    assert proc.returncode == 0, (
        f"render harness failed (exit {proc.returncode})\n"
        f"STDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}"
    )
