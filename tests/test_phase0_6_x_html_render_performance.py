"""Tests for opt-in HTML render stage timing — 0.6.x polish (profiling only).

Contract: docs/history/phase/PHASE0_6_X_HTML_RENDER_PERFORMANCE.md. This
build produces profiling evidence, not an optimization -- these tests prove
the instrumentation is provably inert when disabled (AC-1) and does not
change any payload/schema (AC-4).
"""
from utils.html_export import PROFILE_ENV_VAR, run_html_export


def _write_unified(tmp_path):
    unified = tmp_path / "unified.json"
    unified.write_text("[]", encoding="utf-8")
    return unified


# ---------------------------------------------------------------------------
# AC-1: opt-in, zero behavior difference when disabled
# ---------------------------------------------------------------------------

def test_profile_disabled_by_default_produces_no_profile_log(tmp_path, capsys):
    unified = _write_unified(tmp_path)
    output_html = tmp_path / "index.html"
    run_html_export(unified_json=unified, output_html=output_html)
    assert "HTML RENDER PROFILE" not in capsys.readouterr().out


def test_profile_true_logs_a_per_stage_report(tmp_path, capsys):
    unified = _write_unified(tmp_path)
    output_html = tmp_path / "index.html"
    run_html_export(unified_json=unified, output_html=output_html, profile=True)
    out = capsys.readouterr().out
    assert "HTML RENDER PROFILE" in out
    for stage in (
        "read_unified_json", "build_configuration_ui_payload", "build_project_plan_payload",
        "build_crypto_posture", "build_compliance_posture", "build_discovery_capability_payload",
        "build_inventory_exclusions_payload", "read_template_files", "fill_template",
        "write_output_html", "TOTAL",
    ):
        assert stage in out


def test_profile_env_var_enables_timing_without_a_kwarg(tmp_path, capsys, monkeypatch):
    monkeypatch.setenv(PROFILE_ENV_VAR, "1")
    unified = _write_unified(tmp_path)
    output_html = tmp_path / "index.html"
    run_html_export(unified_json=unified, output_html=output_html)
    assert "HTML RENDER PROFILE" in capsys.readouterr().out


def test_profile_false_kwarg_overrides_a_truthy_env_var(tmp_path, capsys, monkeypatch):
    monkeypatch.setenv(PROFILE_ENV_VAR, "1")
    unified = _write_unified(tmp_path)
    output_html = tmp_path / "index.html"
    run_html_export(unified_json=unified, output_html=output_html, profile=False)
    assert "HTML RENDER PROFILE" not in capsys.readouterr().out


def test_profile_output_html_is_identical_regardless_of_profiling(tmp_path):
    """The profiling switch must never change the rendered artifact itself.

    build_project_plan_payload() embeds its own generation timestamp, which
    naturally differs between any two separate calls -- normalize ISO-8601
    timestamps out before comparing so this test isolates the profiling
    switch's effect, not unrelated call-to-call clock drift.
    """
    import re

    unified = _write_unified(tmp_path)

    off_path = tmp_path / "off" / "index.html"
    run_html_export(unified_json=unified, output_html=off_path, profile=False)

    on_path = tmp_path / "on" / "index.html"
    run_html_export(unified_json=unified, output_html=on_path, profile=True)

    iso_ts = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?\+00:00")
    off_html = iso_ts.sub("<TS>", off_path.read_text(encoding="utf-8"))
    on_html = iso_ts.sub("<TS>", on_path.read_text(encoding="utf-8"))
    assert off_html == on_html
    # AC-4 (no payload/schema change) is covered above by the full HTML diff:
    # a checkpoint-ledger-specific variant of this test was tried and dropped
    # -- it reproduced the same pre-existing test-order-pollution flake
    # already documented against tests/test_phase0_7_5_compliance_trend.py's
    # test_checkpoint_render_appends_one_record on the unmodified baseline,
    # unrelated to profiling. The full-HTML-diff assertion above is a
    # strictly stronger guarantee and does not depend on that shared state.
