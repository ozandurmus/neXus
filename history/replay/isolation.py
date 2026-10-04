"""The replay isolation boundary: a confined package root, plus the four
explicit replay-provider interfaces the frozen document names.

``docs/design/PRIVATE_REPLAY_ARCHITECTURE.md``, "Replay runtime and UI
coverage": "Introduce explicit replay providers for evidence, registry,
clock and job execution." and "The agent environment must not mount the
production runtime, credentials, key, original logs or mapping table...".

Scope of what this module actually proves (read this before relying on
it): ``ConfinedPackageRoot`` is a code-level path-confinement primitive --
it defends the file-access surface a replay provider itself exposes
against absolute paths, ``..`` traversal and symlinks that resolve outside
the package root. It is not host/OS sandboxing. The frozen document is
explicit that "concrete isolation support on the deployment host must be
verified before claiming" the full guarantee (separate account/container/
VM, filesystem and network policy) -- that is a deployment-time property
of *where this code runs*, not something a Python class can establish on
its own, and it is not claimed here.

Only ``EvidenceReplayProvider`` has a concrete slice-1 implementation, used
to prove the confinement boundary end-to-end with a real read path.
``RegistryReplayProvider``, ``ClockReplayProvider`` and ``JobReplayProvider``
are interfaces only -- concrete "isolated console providers" are delivery
slice 3's work (frozen document, "Delivery slices" item 3).
"""
from __future__ import annotations

import json
from abc import ABC, abstractmethod
from dataclasses import dataclass
from pathlib import Path
from typing import Any


class ReplayIsolationError(RuntimeError):
    """Raised instead of resolving a path outside a ``ConfinedPackageRoot``.

    The message never includes the rejected path's contents, only that
    confinement was violated -- the same "report classification, never the
    matched value" posture ``replay.privacy_policy`` uses.
    """


@dataclass(frozen=True)
class ConfinedPackageRoot:
    """Every path a replay provider touches must be resolved through this.

    Construction canonicalizes ``root`` once; ``resolve`` canonicalizes the
    requested path and requires it to remain inside that canonical root
    after resolution -- so a symlink inside the root that points outside it
    is rejected exactly like an absolute path or a ``..`` escape, because
    all three are caught by the same post-resolution containment check
    (mirrors ``utils.runtime_paths._contains``, the same pattern this
    repository already uses to keep the runtime root and repository root
    physically separate).
    """

    root: Path

    def __post_init__(self) -> None:
        try:
            canonical = Path(self.root).expanduser().resolve(strict=True)
        except OSError as exc:
            raise ReplayIsolationError("package root does not exist") from exc
        if not canonical.is_dir():
            raise ReplayIsolationError("package root must be an existing directory")
        object.__setattr__(self, "root", canonical)

    def resolve(self, relative_path: str) -> Path:
        if Path(relative_path).is_absolute():
            raise ReplayIsolationError("absolute paths are not permitted inside a replay package")
        candidate = (self.root / relative_path).resolve(strict=True)
        try:
            candidate.relative_to(self.root)
        except ValueError:
            raise ReplayIsolationError("path resolves outside the confined package root") from None
        return candidate

    def read_text(self, relative_path: str, encoding: str = "utf-8") -> str:
        return self.resolve(relative_path).read_text(encoding=encoding)

    def read_json(self, relative_path: str) -> Any:
        return json.loads(self.read_text(relative_path))


class EvidenceReplayProvider(ABC):
    """Serves transformed evidence/projections to the replay UI. Never the
    production evidence store, never a live collector."""

    @abstractmethod
    def load_unified_inventory(self) -> list[dict[str, Any]]:
        """The package's already-sanitized ``unified.json`` equivalent."""


class RegistryReplayProvider(ABC):
    """Serves isolated Device Registry state to the replay UI. Interface
    only in slice 1 -- concrete implementation is slice 3's "isolated
    registry state" (frozen document, "Replay runtime and UI coverage")."""

    @abstractmethod
    def list_devices(self) -> list[dict[str, Any]]:
        ...


class ClockReplayProvider(ABC):
    """Serves the coherent shifted replay timeline (frozen document,
    "Times and histories"). Interface only in slice 1."""

    @abstractmethod
    def now(self) -> str:
        ...


class JobReplayProvider(ABC):
    """Simulates the job lifecycle for "Collect now"/refresh actions
    (frozen document: "Phase A: simulate the job lifecycle"). Interface
    only in slice 1 -- concrete deterministic synthetic job scenarios are
    slice 3's work."""

    @abstractmethod
    def submit(self, job_type: str, targets: list[str]) -> dict[str, Any]:
        ...


class PackageEvidenceReplayProvider(EvidenceReplayProvider):
    """The slice-1 concrete evidence provider: reads exactly one file,
    ``unified.json``, through a ``ConfinedPackageRoot``. Deliberately thin
    -- proving the isolation boundary holds for a real read path, not
    reproducing the exporter/package-validator that slice 2 builds."""

    def __init__(self, package_root: ConfinedPackageRoot):
        self._package_root = package_root

    def load_unified_inventory(self) -> list[dict[str, Any]]:
        data = self._package_root.read_json("unified.json")
        if not isinstance(data, list):
            raise ReplayIsolationError("unified.json must contain a JSON array")
        return data
