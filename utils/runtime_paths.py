"""Runtime/repository path foundation for SecurityExpert.

DEV.0.3A deliberately resolves and validates an external runtime root without
migrating existing artifact consumers. Consumer migration is staged in
DEV.0.3B/DEV.0.3C.
"""

from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
import tempfile
from typing import Mapping, Optional


RUNTIME_ROOT_ENV = "SECURITYEXPERT_RUNTIME_ROOT"


class RuntimePathError(RuntimeError):
    """Raised when the runtime/repository path contract cannot be satisfied."""


@dataclass(frozen=True)
class RuntimePaths:
    repository_root: Path
    runtime_root: Path
    data_root: Path
    output_root: Path
    logs_root: Path


def default_output_root(*, repository_root: Optional[Path] = None) -> Path:
    """Resolve the default runtime output root for module-level consumers."""
    return resolve_runtime_paths(repository_root=repository_root).output_root


def discover_repository_root() -> Path:
    """Resolve the repository root from this source module, never from CWD."""
    root = Path(__file__).resolve().parents[1]
    if not (root / "main.py").is_file():
        raise RuntimePathError("repository root validation failed: stable application marker missing")
    return root


def _canonical(path: Path) -> Path:
    return path.expanduser().resolve(strict=False)


def _contains(parent: Path, child: Path) -> bool:
    try:
        child.relative_to(parent)
        return True
    except ValueError:
        return False


def _validate_separation(repository_root: Path, runtime_root: Path) -> None:
    repo = _canonical(repository_root)
    runtime = _canonical(runtime_root)
    if repo == runtime or _contains(repo, runtime) or _contains(runtime, repo):
        raise RuntimePathError("runtime root must be physically separate from repository root")


def _probe_writable(directory: Path) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    probe_path: Optional[Path] = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb",
            prefix=".securityexpert_write_probe_",
            dir=str(directory),
            delete=False,
        ) as handle:
            handle.write(b"ok")
            probe_path = Path(handle.name)
    except OSError as exc:
        raise RuntimePathError(f"runtime directory is not writable: {directory}") from exc
    finally:
        if probe_path is not None:
            try:
                probe_path.unlink(missing_ok=True)
            except OSError:
                pass


def _windows_default(environ: Mapping[str, str]) -> Path:
    if "LOCALAPPDATA" not in environ or not environ.get("LOCALAPPDATA", "").strip():
        raise RuntimePathError(
            f"{RUNTIME_ROOT_ENV} or --runtime-root is required when LOCALAPPDATA is unavailable"
        )
    return Path(environ["LOCALAPPDATA"]) / "SecurityExpert" / "runtime"


def _posix_default(environ: Mapping[str, str]) -> Path:
    """XDG Base Directory default for macOS/Linux, mirroring the Windows
    LOCALAPPDATA default's precedence and fail-closed behavior exactly:
    ``$XDG_DATA_HOME`` first, else ``$HOME/.local/share``. A local
    development/AI-session convenience only -- dev_python_env_tooling_friction.
    A container/server deployment (DEV.3) must still set
    SECURITYEXPERT_RUNTIME_ROOT explicitly to its mounted volume; this default
    exists to remove forced env setup for a local checkout, the same role
    LOCALAPPDATA already plays on a Windows laptop today.
    """
    xdg_data_home = environ.get("XDG_DATA_HOME", "").strip()
    if xdg_data_home:
        return Path(xdg_data_home) / "SecurityExpert" / "runtime"
    home = environ.get("HOME", "").strip()
    if not home:
        raise RuntimePathError(
            f"{RUNTIME_ROOT_ENV} or --runtime-root is required when neither "
            "XDG_DATA_HOME nor HOME is available"
        )
    return Path(home) / ".local" / "share" / "SecurityExpert" / "runtime"


def resolve_runtime_paths(
    cli_runtime_root: Optional[str] = None,
    *,
    environ: Optional[Mapping[str, str]] = None,
    repository_root: Optional[Path] = None,
    platform_name: Optional[str] = None,
) -> RuntimePaths:
    """Resolve, validate and prepare the DEV.0.3A runtime path foundation."""
    env = os.environ if environ is None else environ
    repo = _canonical(repository_root or discover_repository_root())

    if cli_runtime_root is not None:
        if not str(cli_runtime_root).strip():
            raise RuntimePathError("--runtime-root cannot be empty")
        selected = Path(cli_runtime_root)
        source = "cli"
    elif RUNTIME_ROOT_ENV in env:
        value = env.get(RUNTIME_ROOT_ENV, "")
        if not value.strip():
            raise RuntimePathError(f"{RUNTIME_ROOT_ENV} is set but empty")
        selected = Path(value)
        source = "environment"
    else:
        platform = os.name if platform_name is None else platform_name
        if platform == "nt":
            selected = _windows_default(env)
            source = "windows-default"
        else:
            selected = _posix_default(env)
            source = "posix-default"

    if source in {"cli", "environment"} and not selected.is_absolute():
        raise RuntimePathError(f"{source} runtime root must be an absolute path")

    runtime = _canonical(selected)
    _validate_separation(repo, runtime)

    paths = RuntimePaths(
        repository_root=repo,
        runtime_root=runtime,
        data_root=runtime / "data",
        output_root=runtime / "output",
        logs_root=runtime / "logs",
    )
    for directory in (paths.runtime_root, paths.data_root, paths.output_root, paths.logs_root):
        _probe_writable(directory)
    return paths
