# gov_po_1_gate_3_agent_tool_council_path — GOV_PO_1_GATE_3 -- allow nexus-council-seat Agent launches from interactive PO sessions

## Summary

The 2026-09-08 relay #11 DECIDE episode found scripts/nexus_po_tool_gate.py::decide() unconditionally denies every Agent tool call, so the nexus-decision-council round GOV_PO_ROLE_MIGRATION.md section 7/8 requires could not actually run; the episode resolved that DECIDE question in a single PO-assistant context, disclosed as such, and this movement lands the narrow fix it authorized. Adds exactly one Agent exception -- form == "interactive" AND tool_input["subagent_type"] == "nexus-council-seat" -- verified against the council skill's own documented launch instructions and the seat agent's frontmatter name; every other Agent spawn, in either form or any other/missing/near-miss subagent_type, stays denied exactly as before.

## Evidence

Relay ozandurmus/nexus-agent-relay#12, validated protocol-v2 SESSION_START. Verified the actual decide() Agent branch (lines 234-235, unconditional deny) and the exact subagent_type string the nexus-decision-council skill launches with (.claude/skills/nexus-decision-council/SKILL.md: "the nexus-council-seat agent definition", matching .claude/agents/nexus-council-seat.md frontmatter name) before implementing. Validation: tests/test_gov_po_role.py 75 passed (3 new: interactive+exact-subagent-type allowed, delegated still denied regardless of subagent_type, and 6 near-miss/missing/empty subagent_type cases denied in interactive form); git diff --check clean; repository privacy gate PASS/0 findings via `py main.py --repository-privacy-check`. PR #116 merged to main as c58d471a6c250a516e36dfbffb7fd7c838072827 (2026-09-08T05:36:20Z) under the Product Owner's RELAY_DECISION on relay#12.

## Risks forward

This build's own build_history/history-index record was deferred twice (once at its own SESSION_CLOSE, to avoid a racy commit against a concurrent M10.1 session sharing the checkout at the time; again at gov_po_1_gate_4_issue_close_path's SESSION_CLOSE) and is only backfilled now, from relay#16, once that checkout concurrency had cleared. No code/test/product behavior changes in this backfill.
