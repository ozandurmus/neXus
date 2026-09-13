# Publish reports through a report-only viewer volume

status: done · target: DEPLOY.1 server arrival

The reverse proxy must mount only published reports. It must never receive the full runtime data tree, evidence payloads, recovery vault, or recovery wrapping key.

2026-09-13 realignment: closed as superseded. The report-only viewer volume isolated the Python product's generated HTML reports. UI 2.0 serves read models through the authenticated service and ships no report volume. PO_DECISION_RECORD_2026_09_12 section 2 (2026-09-12): all UI 2.0 feature work is Java written from scratch; the existing Python is know-how only. Backlog realignment 2026-09-13, approved by the Product Owner in session.
