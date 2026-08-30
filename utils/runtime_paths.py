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
RECOVERY_ROOT_ENV = "SECURITYEXPERT_RECOVERY_ROOT"


class RuntimePathError(RuntimeError):
    """Raised when the runtime/repository path contract cannot be satisfied."""


@dataclass(frozen=True)
class RuntimePaths:
    repository_root: Path
    runtime_root: Path
    data_root: Path
    output_root: Path
    logs_root: Path


@dataclass(frozen=True)
class RecoveryPaths:
    """RB.1 recovery-plane store root. Never derived from `RuntimePaths` --
    docs/design/BACKUP_RECOVERY_CONTRACTS.md §2 requires a volume physically
    separate from both the repository *and* the runtime/evidence root."""
    recovery_root: Path
    vault_root: Path
    groups_root: Path
    retention_root: Path


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


def _validate_disjoint(label_a: str, a: Path, label_b: str, b: Path) -> None:
    left = _canonical(a)
    right = _canonical(b)
    if left == right or _contains(left, right) or _contains(right, left):
        raise RuntimePathError(f"{label_a} must be physically separate from {label_b}")


def _validate_separation(repository_root: Path, runtime_root: Path) -> None:
    _validate_disjoint("runtime root", runtime_root, "repository root", repository_root)


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


def resolve_recovery_root(
    cli_recovery_root: Optional[str] = None,
    *,
    environ: Optional[Mapping[str, str]] = None,
    repository_root: Optional[Path] = None,
    runtime_root: Optional[Path] = None,
) -> RecoveryPaths:
    """Resolve, validate and prepare the RB.1 recovery-plane store root.

    Unlike `resolve_runtime_paths`, there is deliberately **no OS-default
    fallback**: `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §2 requires this to
    be an explicit, mandatory, absolute operator decision, not an inferred
    convenience path -- a recovery volume silently defaulting into place would
    reproduce exactly the "assumed we had backups" failure mode
    `docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` §1 exists to prevent.

    Validated separate from the repository root (as `resolve_runtime_paths`
    already is) *and* from `runtime_root` when given -- evidence and recovery
    are different volumes with different lifecycles (architecture §4).
    """
    env = os.environ if environ is None else environ
    repo = _canonical(repository_root or discover_repository_root())

    if cli_recovery_root is not None:
        if not str(cli_recovery_root).strip():
            raise RuntimePathError("--recovery-root cannot be empty")
        selected = Path(cli_recovery_root)
        source = "cli"
    elif RECOVERY_ROOT_ENV in env:
        value = env.get(RECOVERY_ROOT_ENV, "")
        if not value.strip():
            raise RuntimePathError(f"{RECOVERY_ROOT_ENV} is set but empty")
        selected = Path(value)
        source = "environment"
    else:
        raise RuntimePathError(
            f"{RECOVERY_ROOT_ENV} (or an explicit recovery root) is required -- "
            "the recovery-plane store has no default location by design"
        )

    if not selected.is_absolute():
        raise RuntimePathError(f"{source} recovery root must be an absolute path")

    recovery = _canonical(selected)
    _validate_disjoint("recovery root", recovery, "repository root", repo)
    if runtime_root is not None:
        _validate_disjoint("recovery root", recovery, "runtime root", runtime_root)

    paths = RecoveryPaths(
        recovery_root=recovery,
        vault_root=recovery / "vault",
        groups_root=recovery / "groups",
        retention_root=recovery / "retention",
    )
    for directory in (paths.recovery_root, paths.vault_root, paths.groups_root, paths.retention_root):
        _probe_writable(directory)
    return paths
