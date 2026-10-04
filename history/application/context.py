"""The shared bootstrap state ``main()`` used to hold as ~15 lexical locals.

Passed explicitly to every workflow entrypoint instead of being threaded
implicitly through one 1,690-line function (audit finding F3 / D-MOD-B3).
Keep this to the fields below: a workflow that needs something not here is a
signal the seam is wrong, not a reason to add a field.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass
class ApplicationContext:
    args: argparse.Namespace
    parser: argparse.ArgumentParser
    runtime_paths: Any | None = None  # RuntimePaths; None only for Phase-B modes
    support_bundle_output_root: Path | None = None
    provenance: str = "manual"
    admission_run_context: Any | None = None
    services: Any | None = None  # RuntimeCollectionServices; lazily built
