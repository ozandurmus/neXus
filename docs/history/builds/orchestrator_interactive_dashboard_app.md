# orchestrator_interactive_dashboard_app — GOV.PO.3 -- interactive PO + Orchestrator workbench

## Summary

Amends the earlier read-only-dashboard framing (relay/NXS-LOCAL-0013) into a human-action inbox, live movement tracking with a real detail panel, message delivery and authorized-command routing -- all read+relay-write only, no direct git/gh/shell execution from the dashboard backend. New scripts/orchestrator_dashboard.py (stdlib http.server, session-token + Origin/CSRF per its own AC-2 design note) and scripts/dashboard_assets/.

## Evidence

Relay relay/NXS-LOCAL-0021-orchestrator-interactive-dashboard-app.json, AC-2 design note reviewed and acknowledged via RELAY_DECISION before AC-3 implementation began. Real acceptance demo against isolated scratch relay/state fixtures (never touching the shared canonical relay or other live movements): tracked 3 concurrent movements, answered a pending decision, auto-routed an authorized action, exercised the message-delivery outbox path, confirmed platform-policy-block cards have no buttons, confirmed duplicate-click (409) and cross-origin (403) rejection; found and fixed a real bug during that demo (action/outbox registry files colliding with orchestrator.py's own state-record glob). Full one-shot regression: 2979 passed, 25 skipped, 2 pre-existing unrelated failures. PR #136 merged.
