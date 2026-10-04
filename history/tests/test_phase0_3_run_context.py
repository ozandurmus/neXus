from pathlib import Path

from utils import run_context
import pytest

pytestmark = pytest.mark.inventory


def test_run_context_removes_stale_target_and_captures_fresh_artifact(tmp_path, monkeypatch):
    output_dir = tmp_path / "output"
    runs_dir = tmp_path / "data" / "runs"
    output_dir.mkdir(parents=True)
    stale = output_dir / "cp.json"
    stale.write_text("stale", encoding="utf-8")

    monkeypatch.setattr(run_context, "OUTPUT_DIR", output_dir)
    monkeypatch.setattr(run_context, "RUNS_DIR", runs_dir)
    monkeypatch.setattr(run_context, "info", lambda _msg: None)

    ctx = run_context.RunContext.create()
    ctx.clear_legacy_targets(["cp.json"])
    assert not stale.exists()

    stale.write_text('[{"source":"cp"}]', encoding="utf-8")
    captured = ctx.capture("cp.json", "parsed")

    assert captured.exists()
    assert (ctx.stage_dir / "cp.json").exists()
    assert "cp.json" in ctx.artifacts
