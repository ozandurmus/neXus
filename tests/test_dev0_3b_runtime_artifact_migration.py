import json
from pathlib import Path

from utils.run_context import RunContext
from utils.runtime_paths import resolve_runtime_paths


def test_run_context_uses_external_runtime_and_ignores_stale_repository(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "main.py").write_text("# marker", encoding="utf-8")
    runtime = tmp_path / "runtime"
    paths = resolve_runtime_paths(str(runtime), repository_root=repo)

    stale = repo / "output" / "cp.json"
    stale.parent.mkdir()
    stale.write_text("stale", encoding="utf-8")

    ctx = RunContext.create(data_root=paths.data_root, output_root=paths.output_root)
    fresh = paths.output_root / "cp.json"
    fresh.write_text(json.dumps([]), encoding="utf-8")
    captured = ctx.capture("cp.json", "parsed")

    assert captured.exists()
    assert json.loads(captured.read_text(encoding="utf-8")) == []
    assert stale.read_text(encoding="utf-8") == "stale"
    assert ctx.root.is_relative_to(paths.data_root / "runs")


def test_external_runtime_roots_do_not_overlap_repository(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "main.py").write_text("# marker", encoding="utf-8")
    paths = resolve_runtime_paths(str(tmp_path / "runtime"), repository_root=repo)
    assert paths.output_root.parent == paths.runtime_root
    assert paths.data_root.parent == paths.runtime_root
    assert paths.logs_root.parent == paths.runtime_root
    assert not paths.output_root.is_relative_to(paths.repository_root)
