# One SSH session per device reused for every command; probe opens a channel per command

status: planned · target: PO rule 2026-09-12; command gate says session, code preserves connection

2026-09-13 realignment: closed as superseded. The Product Owner rule of 2026-09-12 (one SSH session per device, a channel per command) stands and is carried as a UI 2.0 transport rule; the Python code that preserved a connection rather than a session is know-how only. PO_DECISION_RECORD_2026_09_12 section 2 (2026-09-12): all UI 2.0 feature work is Java written from scratch; the existing Python is know-how only. Backlog realignment 2026-09-13, approved by the Product Owner in session.
