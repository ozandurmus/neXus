from pathlib import Path
import tempfile

import pytest

from utils.runtime_paths import RuntimePathError, RuntimePaths, resolve_runtime_paths


def _repo(tmp_path):
    repo = tmp_path / "repo"
    repo.mkdir()
    return repo


def test_cli_runtime_root_wins_over_environment(tmp_path):
    repo = _repo(tmp_path)
    cli_root = tmp_path / "cli-runtime"
    env_root = tmp_path / "env-runtime"

    paths = resolve_runtime_paths(
        str(cli_root),
        environ={"SECURITYEXPERT_RUNTIME_ROOT": str(env_root)},
        repository_root=repo,
        platform_name="nt",
    )

    assert isinstance(paths, RuntimePaths)
    assert paths.runtime_root == cli_root.resolve()
    assert paths.data_root == cli_root.resolve() / "data"
    assert paths.output_root == cli_root.resolve() / "output"
    assert paths.logs_root == cli_root.resolve() / "logs"


def test_environment_runtime_root_wins_over_windows_default(tmp_path):
    repo = _repo(tmp_path)
    env_root = tmp_path / "env-runtime"
    local = tmp_path / "local-app-data"

    paths = resolve_runtime_paths(
        environ={
            "SECURITYEXPERT_RUNTIME_ROOT": str(env_root),
            "LOCALAPPDATA": str(local),
        },
        repository_root=repo,
        platform_name="nt",
    )

    assert paths.runtime_root == env_root.resolve()


def test_windows_default_uses_localappdata(tmp_path):
    repo = _repo(tmp_path)
    local = tmp_path / "local-app-data"

    paths = resolve_runtime_paths(
        environ={"LOCALAPPDATA": str(local)},
        repository_root=repo,
        platform_name="nt",
    )

    assert paths.runtime_root == (local / "SecurityExpert" / "runtime").resolve()


@pytest.mark.parametrize("value", ["", "relative/runtime"])
def test_invalid_explicit_cli_root_fails_closed(tmp_path, value):
    repo = _repo(tmp_path)
    with pytest.raises(RuntimePathError):
        resolve_runtime_paths(value, environ={}, repository_root=repo, platform_name="nt")


def test_empty_environment_root_fails_closed_without_default_fallback(tmp_path):
    repo = _repo(tmp_path)
    with pytest.raises(RuntimePathError, match="set but empty"):
        resolve_runtime_paths(
            environ={"SECURITYEXPERT_RUNTIME_ROOT": "", "LOCALAPPDATA": str(tmp_path / "local")},
            repository_root=repo,
            platform_name="nt",
        )


def test_posix_default_uses_xdg_data_home(tmp_path):
    repo = _repo(tmp_path)
    xdg = tmp_path / "xdg-data"

    paths = resolve_runtime_paths(
        environ={"XDG_DATA_HOME": str(xdg), "HOME": str(tmp_path / "home")},
        repository_root=repo,
        platform_name="posix",
    )

    assert paths.runtime_root == (xdg / "SecurityExpert" / "runtime").resolve()


def test_posix_default_falls_back_to_home_local_share(tmp_path):
    repo = _repo(tmp_path)
    home = tmp_path / "home"

    paths = resolve_runtime_paths(
        environ={"HOME": str(home)},
        repository_root=repo,
        platform_name="posix",
    )

    assert paths.runtime_root == (home / ".local" / "share" / "SecurityExpert" / "runtime").resolve()


def test_environment_runtime_root_wins_over_posix_default(tmp_path):
    repo = _repo(tmp_path)
    env_root = tmp_path / "env-runtime"

    paths = resolve_runtime_paths(
        environ={"SECURITYEXPERT_RUNTIME_ROOT": str(env_root), "HOME": str(tmp_path / "home")},
        repository_root=repo,
        platform_name="posix",
    )

    assert paths.runtime_root == env_root.resolve()


def test_posix_requires_explicit_or_environment_root_when_home_and_xdg_unavailable(tmp_path):
    repo = _repo(tmp_path)
    with pytest.raises(RuntimePathError, match="XDG_DATA_HOME nor HOME"):
        resolve_runtime_paths(environ={}, repository_root=repo, platform_name="posix")


@pytest.mark.parametrize("runtime_selector", ["same", "child", "parent"])
def test_repository_runtime_overlap_is_rejected(tmp_path, runtime_selector):
    repo = tmp_path / "parent" / "repo"
    repo.mkdir(parents=True)
    if runtime_selector == "same":
        runtime = repo
    elif runtime_selector == "child":
        runtime = repo / "runtime"
    else:
        runtime = repo.parent

    with pytest.raises(RuntimePathError, match="physically separate"):
        resolve_runtime_paths(
            str(runtime.resolve()), environ={}, repository_root=repo, platform_name="nt"
        )


def test_runtime_directories_are_created_and_write_probe_is_cleaned(tmp_path):
    repo = _repo(tmp_path)
    runtime = tmp_path / "runtime"

    paths = resolve_runtime_paths(
        str(runtime), environ={}, repository_root=repo, platform_name="nt"
    )

    for directory in (paths.runtime_root, paths.data_root, paths.output_root, paths.logs_root):
        assert directory.is_dir()
        assert not list(directory.glob(".securityexpert_write_probe_*"))


def test_writability_probe_failure_is_startup_error(tmp_path, monkeypatch):
    repo = _repo(tmp_path)
    runtime = tmp_path / "runtime"

    def fail_probe(*args, **kwargs):
        raise PermissionError("synthetic denial")

    monkeypatch.setattr(tempfile, "NamedTemporaryFile", fail_probe)
    with pytest.raises(RuntimePathError, match="not writable"):
        resolve_runtime_paths(
            str(runtime), environ={}, repository_root=repo, platform_name="nt"
        )


def test_resolution_is_independent_from_current_working_directory(tmp_path, monkeypatch):
    repo = _repo(tmp_path)
    runtime = tmp_path / "runtime"
    elsewhere = tmp_path / "elsewhere"
    elsewhere.mkdir()
    monkeypatch.chdir(elsewhere)

    paths = resolve_runtime_paths(
        str(runtime), environ={}, repository_root=repo, platform_name="nt"
    )

    assert paths.repository_root == repo.resolve()
    assert paths.runtime_root == runtime.resolve()


def test_symlink_runtime_escape_to_repository_is_rejected_when_supported(tmp_path):
    repo = _repo(tmp_path)
    link = tmp_path / "runtime-link"
    try:
        link.symlink_to(repo, target_is_directory=True)
    except (OSError, NotImplementedError):
        pytest.skip("symlink creation is unavailable on this platform")

    with pytest.raises(RuntimePathError, match="physically separate"):
        resolve_runtime_paths(
            str(link.absolute()), environ={}, repository_root=repo, platform_name="nt"
        )


def test_main_rejects_invalid_runtime_root_before_prompt_or_collection(monkeypatch):
    import sys
    import main as main_module

    def forbidden_input(*args, **kwargs):
        raise AssertionError("credential prompt must not run after path bootstrap failure")

    monkeypatch.setattr("builtins.input", forbidden_input)
    monkeypatch.setattr(sys, "argv", ["main.py", "--runtime-root", "relative/path", "--only", "cp"])

    with pytest.raises(SystemExit) as exc:
        main_module.main()
    assert exc.value.code == 2
