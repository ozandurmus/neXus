import importlib
from pathlib import Path

from utils.runtime_paths import resolve_runtime_paths


MODULES = [
    "configuration.panorama_config_collector",
    "configuration.checkpoint_config_collector",
    "utils.run_context",
    "utils.merge",
    "utils.support_bundle",
    "utils.config_storage",
]


def test_output_dir_defaults_bind_to_runtime_paths_output_root(monkeypatch, tmp_path):
    runtime_root = tmp_path / "runtime"
    monkeypatch.setenv("SECURITYEXPERT_RUNTIME_ROOT", str(runtime_root))

    repo_root = Path(__file__).resolve().parents[1]
    expected_output = resolve_runtime_paths(repository_root=repo_root).output_root

    for module_name in MODULES:
        module = importlib.import_module(module_name)
        module = importlib.reload(module)
        assert Path(module.OUTPUT_DIR) == expected_output
        assert not Path(module.OUTPUT_DIR).is_relative_to(repo_root)
