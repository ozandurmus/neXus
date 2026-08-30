"""clean_baseline_bootstrap — partial/dev modes fail fast with actionable
guidance when a fresh runtime has no baseline artifacts to reuse, instead of a
deep traceback (and before any credential prompt or collector).
"""
import json

import pytest

import main

pytestmark = pytest.mark.runtime_platform


def _write(output_root, name, payload="[]"):
    p = output_root / "output"
    p.mkdir(parents=True, exist_ok=True)
    (p / name).write_text(payload, encoding="utf-8")
    return p / name


# --- _bootstrap_gaps / _require_bootstrap (pure) -------------------------

def test_bootstrap_gaps_lists_missing_prior_artifacts(tmp_path):
    out = tmp_path / "output"
    out.mkdir()
    gaps = dict(main._bootstrap_gaps("cp-config-probe", out))
    assert set(gaps) == {"cp_telemetry.json", "cp.json", "vsx.json"}
    for hint in gaps.values():
        assert "main.py" in hint


def test_bootstrap_gaps_empty_when_satisfied(tmp_path):
    out = tmp_path / "output"
    out.mkdir()
    for name in ("cp.json", "vsx.json", "panorama_runtime.json", "unified.json", "cp_telemetry.json"):
        (out / name).write_text("[]", encoding="utf-8")
    for mode in ("render-only", "cp-config-probe", "cp-config-collect", "cp", "vsx", "pan-config"):
        assert main._bootstrap_gaps(mode, out) == []


def test_full_checkpoint_has_no_prerequisites(tmp_path):
    out = tmp_path / "output"
    out.mkdir()
    assert main._bootstrap_gaps("all", out) == []
    assert main._bootstrap_gaps("panorama", out) == []
    assert main._require_bootstrap("all", out) is None


def test_require_bootstrap_exits_2_with_guidance(tmp_path, capsys):
    out = tmp_path / "output"
    out.mkdir()
    with pytest.raises(SystemExit) as exc:
        main._require_bootstrap("render-only", out)
    assert exc.value.code == 2
    err = capsys.readouterr().err
    assert "BOOTSTRAP REQUIRED" in err
    assert "unified.json" in err
    assert "py -B main.py" in err


# --- integration through main.main() -----------------------------------

def test_render_only_fresh_runtime_fails_fast(tmp_path, monkeypatch, capsys):
    called = []
    monkeypatch.setattr("utils.html_export.run_html_export", lambda *a, **k: called.append("render"))

    with pytest.raises(SystemExit) as exc:
        main.main(["--runtime-root", str(tmp_path), "--render-only"])

    assert exc.value.code == 2
    assert "BOOTSTRAP REQUIRED" in capsys.readouterr().err
    assert called == []  # never reached the renderer


def test_render_only_proceeds_when_unified_present(tmp_path, monkeypatch):
    _write(tmp_path, "unified.json")
    rendered = []
    monkeypatch.setattr("utils.html_export.run_html_export", lambda *a, **k: rendered.append(k.get("output_html")))

    main.main(["--runtime-root", str(tmp_path), "--render-only"])  # no SystemExit

    assert rendered  # bootstrap check passed, renderer ran


def test_cp_config_probe_fresh_runtime_fails_before_collector_and_prompt(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr("builtins.input", lambda *a, **k: pytest.fail("prompted for credentials"))
    monkeypatch.setattr("getpass.getpass", lambda *a, **k: pytest.fail("prompted for secret"))
    monkeypatch.setattr(
        "configuration.checkpoint_config_probe.run_checkpoint_config_probe",
        lambda *a, **k: pytest.fail("probe collector ran"),
    )

    with pytest.raises(SystemExit) as exc:
        main.main(["--runtime-root", str(tmp_path), "--cp-config-probe"])

    assert exc.value.code == 2
    err = capsys.readouterr().err
    assert "BOOTSTRAP REQUIRED" in err
    assert "cp_telemetry.json" in err and "vsx.json" in err


def test_only_cp_fresh_runtime_fails_before_credentials(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr("builtins.input", lambda *a, **k: pytest.fail("prompted for credentials"))
    monkeypatch.setattr("getpass.getpass", lambda *a, **k: pytest.fail("prompted for secret"))
    monkeypatch.setattr("checkpoint.cp_runner.run_cp", lambda *a, **k: pytest.fail("cp collector ran"))

    with pytest.raises(SystemExit) as exc:
        main.main(["--runtime-root", str(tmp_path), "--only", "cp"])

    assert exc.value.code == 2
    err = capsys.readouterr().err
    assert "BOOTSTRAP REQUIRED" in err
    assert "vsx.json" in err and "panorama_runtime.json" in err


def test_cp_config_collect_fresh_runtime_fails_fast(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr("builtins.input", lambda *a, **k: pytest.fail("prompted"))
    monkeypatch.setattr(
        "configuration.checkpoint_config_collector.run_checkpoint_config_collection",
        lambda *a, **k: pytest.fail("collector ran"),
    )
    with pytest.raises(SystemExit) as exc:
        main.main(["--runtime-root", str(tmp_path), "--cp-config-collect", "--cp-config-stage", "all"])
    assert exc.value.code == 2
    assert "BOOTSTRAP REQUIRED" in capsys.readouterr().err
