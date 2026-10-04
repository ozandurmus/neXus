"""Resolve split legacy imports only during explicit archive test runs."""
from pathlib import Path
from tempfile import TemporaryDirectory

import pytest

import utils

# Live helpers stay at the repository root; retired modules live beside us.
# Never extend this path from the live tests or tooling entry points.
_environment = pytest.MonkeyPatch()
_environment.setattr(utils, "__path__", [*utils.__path__, str(Path(__file__).resolve().parent / "utils")])

# Historical modules initialize runtime paths while importing. Never inherit
# an operator's configured runtime during offline archive collection.
_runtime = TemporaryDirectory(prefix="nexus-archive-tests-")
_environment.setenv("SECURITYEXPERT_RUNTIME_ROOT", str(Path(_runtime.name) / "runtime"))
_environment.setenv("SECURITYEXPERT_RECOVERY_ROOT", str(Path(_runtime.name) / "recovery"))


def pytest_unconfigure():
    _environment.undo()
    _runtime.cleanup()
