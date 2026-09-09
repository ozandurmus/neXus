"""GOV.PO.2 standalone repository-privacy entry-point equivalence."""
from __future__ import annotations

import ast
import contextlib
import importlib.util
import io
import subprocess
import sys
from pathlib import Path

import pytest

import main
from application.workflows import maintenance as maintenance_wf

pytestmark = pytest.mark.runtime_platform

ROOT = Path(__file__).resolve().parent.parent
SCRIPT_PATH = ROOT / "scripts/repository_privacy_check.py"


def _load_script():
    spec = importlib.util.spec_from_file_location("gov_po_2_repository_privacy_check", SCRIPT_PATH)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _write(root: Path, rel: str, text: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def _run_main() -> tuple[int, str]:
    output = io.StringIO()
    with contextlib.redirect_stdout(output), pytest.raises(SystemExit) as exc:
        main.main(["--repository-privacy-check"])
    return exc.value.code, output.getvalue()


def _run_standalone(module) -> tuple[int, str]:
    output = io.StringIO()
    with contextlib.redirect_stdout(output):
        code = module.main()
    return code, output.getvalue()


def _without_banner(output: str) -> str:
    return output.split("\n", 1)[1]


@pytest.mark.parametrize(
    "files",
    [
        {"safe.py": "ENDPOINT = '192.0.2.10'\n"},
        {"unsafe.py": "api_key = 'synthetic-finding-for-test'\n"},
    ],
)
def test_standalone_matches_main_for_pass_and_finding_cases(tmp_path, monkeypatch, files):
    for rel, text in files.items():
        _write(tmp_path, rel, text)

    standalone = _load_script()
    monkeypatch.setattr(maintenance_wf, "_REPO_ROOT", tmp_path)
    monkeypatch.setattr(standalone, "_REPO_ROOT", tmp_path)

    main_code, main_output = _run_main()
    standalone_code, standalone_output = _run_standalone(standalone)

    assert standalone_code == main_code
    assert _without_banner(standalone_output) == _without_banner(main_output)


def test_standalone_imports_only_the_authorized_privacy_symbols():
    tree = ast.parse(SCRIPT_PATH.read_text(encoding="utf-8"))
    privacy_imports = [
        node for node in tree.body
        if isinstance(node, ast.ImportFrom) and node.module == "utils.repository_privacy"
    ]
    assert len(privacy_imports) == 1
    assert {alias.name for alias in privacy_imports[0].names} == {
        "RepositoryPrivacyError",
        "scan_repository",
    }
    assert not any(
        isinstance(node, ast.ImportFrom) and (
            node.module == "application" or (node.module or "").startswith("application.")
        )
        for node in ast.walk(tree)
    )


def test_standalone_is_directly_executable_from_the_repository():
    result = subprocess.run(
        [sys.executable, str(SCRIPT_PATH)],
        cwd=ROOT,
        capture_output=True,
        text=True,
        timeout=60,
    )
    assert result.returncode in {0, 1}
    assert "ModuleNotFoundError" not in result.stderr
    assert "No matched values were printed." in result.stdout
