"""Non-interactive runtime value sourcing — DEV.2.1.

Resolves a runtime configuration value from, in per-value precedence:

1. ``<NAME>_FILE`` — path whose UTF-8 contents (leading/trailing whitespace and
   newlines stripped) are the value. Docker / Kubernetes secret-mount convention.
2. ``<NAME>`` — plain environment variable (also stripped).
3. (caller's responsibility) an interactive prompt, only when a TTY is available.

Fail closed: a ``<NAME>_FILE`` that is set but missing / unreadable / empty
raises :class:`RuntimeConfigError` rather than silently falling through to the
plain variable or a prompt.

No secret value ever appears in an exception message — errors name the variable,
never its content.
"""
from __future__ import annotations

import os
from pathlib import Path
from typing import Mapping


class RuntimeConfigError(RuntimeError):
    """Runtime configuration could not be resolved without interaction."""


def resolve_value(name: str, *, environ: Mapping[str, str] | None = None) -> str | None:
    """Return the resolved value for ``name``, or ``None`` if neither
    ``<name>_FILE`` nor ``<name>`` is set to a non-empty value.

    ``<name>_FILE`` wins when present: its file is read as UTF-8 and stripped.
    A set-but-unusable ``<name>_FILE`` (unreadable or empty) raises
    :class:`RuntimeConfigError`.
    """
    env = os.environ if environ is None else environ

    file_var = f"{name}_FILE"
    file_path = env.get(file_var)
    if file_path is not None and file_path.strip():
        try:
            text = Path(file_path).read_text(encoding="utf-8")
        except OSError as exc:
            raise RuntimeConfigError(
                f"{file_var} is set but its file could not be read"
            ) from exc
        value = text.strip()
        if not value:
            raise RuntimeConfigError(f"{file_var} is set but its file is empty")
        return value

    raw = env.get(name)
    if raw is not None and raw.strip():
        return raw.strip()

    return None
