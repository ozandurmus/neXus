# gov_po_1_gate_4_issue_close_path — GOV_PO_1_GATE_4 -- allow gh issue close from interactive PO sessions

## Summary

Adds 'gh issue close' to scripts/nexus_po_tool_gate.py::decide()'s interactive-form allowlist so a Product Owner in an interactive nexus-po session can close a resolved relay issue without a manual terminal command; an inline --comment/-c value must carry one of the five relay markers, exactly like the existing 'gh issue comment' rule, while a close with no comment is allowed unconditionally. Delegated form still denies 'gh issue close' entirely, matching the existing 'gh issue create'/'gh pr create' precedent.

## Evidence

Relay ozandurmus/nexus-agent-relay#14, validated protocol-v2 SESSION_START. Verified gh issue close's actual flags via `gh issue close --help` before implementing: only --comment/-c (inline text), --reason, --duplicate-of exist -- no file-based comment flag, so no --body-file-style scratch restriction is needed. Validation: tests/test_gov_po_role.py 79 passed (4 new: no-comment close allowed, RELAY_NOTE-marked close allowed, unmarked-comment close denied, delegated close denied regardless of comment); git diff --check clean; repository privacy gate PASS/0 findings via `py main.py --repository-privacy-check` (a stray gitignored .DS_Store OS artifact was flagged and removed, unrelated to this change).

## Risks forward

gov_po_1_gate_3_agent_tool_council_path (PR #116, merged) still has no build_history/CURRENT_STATE/history-index record of its own -- its own SESSION_CLOSE deferred that bookkeeping to avoid a racy commit against a concurrent M10.1 session sharing the checkout at the time; that concurrency has since cleared (both PR #116 and PR #117 are merged to origin/main) and a small follow-up DOCS movement should backfill gate_3's own record. This movement does not touch 'gh issue reopen', 'gh issue edit', 'gh issue delete', 'gh pr close', or any other decide() branch.
