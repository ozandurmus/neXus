"""event_signal_intake Slice 1 -- external change signal intake boundary.

An HMAC-authenticated ASGI service (``signal_intake/app.py``) that validates,
deduplicates and rate-limits an inbound signal (e.g. a Splunk correlation
action), resolves canonical device identity against the existing
``PCP.1`` Device Registry, and -- on success -- enqueues a bounded,
coordinator-managed, read-only collection through the *existing*, unmodified
``CON.2`` job engine (``console.jobs``/``console.runner``). It never writes
evidence itself. See ``docs/design/EVENT_SIGNAL_INTAKE_ARCHITECTURE.md``
(FROZEN -- Slice 1 only).

Slice 1 ships no network exposure: no ``main.py`` CLI flag, no bind address,
no server module. This package is only reachable via an in-process ASGI test
client (``fastapi.testclient``) today -- see the design doc's "Explicitly
out of scope" section.
"""
