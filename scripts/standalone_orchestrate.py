#!/usr/bin/env python3
"""Deprecated compatibility entrypoint; use tools/delivery/standalone_orchestrate.py."""
import os
from pathlib import Path
import sys

os.execv(sys.executable, [sys.executable, str(Path(__file__).resolve().parents[1] / "tools/delivery/standalone_orchestrate.py"), *sys.argv[1:]])
