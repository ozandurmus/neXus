"""Render the SecurityExpert UI from the committed `tests/fixtures/uitest` bundle
so every module renders *populated* -- the input for the render harness
(`tools/render-harness/check-render.mjs`) and a realistic local eyeball.

    py -V:3.12 scripts/render_uitest.py [--out DIR]

Unlike `scripts/render_sample.py` (which renders the honest empty state for
Configuration / Compliance / Discovery), this injects the fixture payloads for
those three builders and lets the real `build_compliance_posture`,
`_fill_template` and `_script_json` run. No network, no credentials, no device.
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
import tempfile
from contextlib import contextmanager
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
if str(REPO) not in sys.path:
    sys.path.insert(0, str(REPO))

FIXTURE = REPO / "tests" / "fixtures" / "uitest"


def _load(name: str):
    return json.loads((FIXTURE / name).read_text(encoding="utf-8"))


@contextmanager
def _injected_builders(module, **replacements):
    """Temporarily rebind module-level payload builders, always restoring them.

    Restoring is not cosmetic: this module is imported and called in-process by
    the test suite, so an unrestored rebind silently changes the behaviour of
    every subsequent caller in that process.
    """
    originals = {name: getattr(module, name) for name in replacements}
    try:
        for name, replacement in replacements.items():
            setattr(module, name, replacement)
        yield
    finally:
        for name, original in originals.items():
            setattr(module, name, original)


def render(out_root: Path, *, profile: bool = False) -> Path:
    (out_root / "output").mkdir(parents=True, exist_ok=True)
    data_root = out_root / "data"
    (data_root / "state").mkdir(parents=True, exist_ok=True)
    for f in (FIXTURE / "state").iterdir():
        shutil.copy2(f, data_root / "state" / f.name)

    unified_path = out_root / "output" / "unified.json"
    unified_path.write_text(json.dumps(_load("unified.json"), indent=2), encoding="utf-8")

    configuration_ui = _load("configuration_ui.json")
    crypto_ui = _load("crypto_ui.json")
    discovery_ui = _load("discovery_ui.json")

    from utils import html_export

    # Inject the three builders whose real inputs (collector telemetry, PAN XML on
    # disk, live stores) are out of scope for a UI render check. build_compliance_
    # posture, build_project_plan_payload, the template fill and _script_json all
    # still run for real.
    #
    # The injection MUST be undone before returning. `render()` is called
    # in-process by tests/test_frontend_rendering_boundary.py and
    # tests/test_html_render_harness.py; a bare rebind here leaked into every
    # later run_html_export() in the same worker and made two unrelated tests
    # fail against fixture data instead of their own inputs. That was the whole
    # of the long-standing "order-dependent" pair -- keep the try/finally.
    index_html = out_root / "output" / "index.html"
    with _injected_builders(
        html_export,
        build_configuration_ui_payload=lambda *a, **k: configuration_ui,
        build_crypto_posture=lambda *a, **k: crypto_ui,
        build_discovery_capability_payload=lambda *a, **k: discovery_ui,
    ):
        html_export.run_html_export(
            unified_json=unified_path,
            output_html=index_html,
            repository_root=REPO,
            data_root=data_root,
            workflow_context={"mode": "uitest", "label": "UI test bundle",
                              "checkpoint": False, "mixed_cycle": True},
            record_checkpoint=False,
            profile=profile,
        )
    return index_html


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", help="output directory (default: a temp dir outside the repo)")
    parser.add_argument(
        "--profile", action="store_true",
        help="log an opt-in per-stage render-time breakdown (html_render_performance)",
    )
    args = parser.parse_args()
    out_root = (Path(args.out).expanduser().resolve() if args.out
                else Path(tempfile.mkdtemp(prefix="securityexpert_uitest_render_")))
    index_html = render(out_root, profile=args.profile)
    print()
    print("UI test bundle rendered (all six modules populated).")
    print(f"  fixture    : {FIXTURE}")
    print(f"  index.html : {index_html}")


if __name__ == "__main__":
    main()
