# CP_REMOTE_COLLECTION_DONE_MARKER_DIAGNOSTICS — cp_remote_collection_done_marker_diagnostics: CP remote collection - DONE-marker-loss diagnostic hardening

- Moved from now_next.now (2026-09-03 roadmap correction, OP.0b S4 session): stalled pending a real-device recurrence with the new diagnostic fields (exit_status/processed_gw/total_gw/stderr_bytes/last_marker/stderr_classification) -- no device access in this session, so it cannot progress here. Full detail: project/build_history.json 'cp_remote_collection_done_marker_diagnostics'.
- Does not block OP.0b S4/S5/S6 -- independent subsystem (CP inventory collection channel-drain diagnostics vs. the OP.0b preflight command gate).
- Resume when a real recurrence report with the new fields is available.
