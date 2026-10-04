"""SecurityExpert application package.

The thin CLI/bootstrap layer that ``main.py`` used to inline. Split at the
orchestration seam per
``docs/design/SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md`` §5 and
``docs/history/phase/CODEBASE_MODULARIZATION_BACKEND.md``.

Import direction (enforced by the AC-3 static test):
``main.py -> application.cli -> {application.services, application.context,
application.workflows.*}``; workflow modules import ``application.services`` /
``application.context`` and, lazily and in-function, the vendor / ``utils.*``
callees. ``cli`` / ``services`` / ``context`` import no vendor, transport or
heavy-parser module at load time.
"""
