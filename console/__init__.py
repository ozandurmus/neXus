"""CON.1 — operator console, read-only surface.

An authenticated loopback ASGI service (``console/app.py``) that serves the
existing UI modules and the existing report payloads, live, with zero action
capability. See ``docs/history/phase/CON_1_OPERATOR_CONSOLE_READ_ONLY.md``.

This package is never imported by any other mode (``main.py``'s collection /
render / maintenance code paths) — only ``application/workflows`` imports it,
and only inside the ``--console`` branch, after the ``C1-8`` fail-closed
preflight has already confirmed the optional dependency is installed.
"""
