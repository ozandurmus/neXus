# gov_po_1_role_migration_contract — GOV.PO.1 -- Product Owner assistant role migration and two-context governance (contract freeze)

## Summary

Freezes docs/design/GOV_PO_ROLE_MIGRATION.md: the Product Owner assistant role moves from a separate vendor tool to a repository-defined Claude Code role (nexus-po) with two isolated contexts, a human-only decision authority, a ratified PRODUCT_DIRECTION_RECORD.md, four PO episode types, movement-size planning targets, an isolation acceptance-test gate for delegated episodes, and one approved narrow session-lifecycle amendment for comment-only PO episodes. Contract only: no skill, agent definition, rule text or relay clause is installed by this build; the knowledge-extraction prompt (.github/prompts/po-knowledge-extraction.prompt.md) is added as a repository artifact.

## Evidence

Baseline origin/main ae5eb34fee25b16e759f2ed39e9890fe6d11a130 verified (M9 PR #104 merged via 5fc88a21…, GOV.GIT.1 PR #108 merged via 9d0a52cf…, relay #3 final SESSION_CLOSE validates). Four review rounds recorded inside the document (revisions 1-4) against three written Product Owner directives; sixteen Product Owner decisions D1-D16 recorded verbatim in the contract's section 3. Platform isolation claims checked against official Claude Code documentation and recorded as documented/implied/undocumented with demonstrated status NOT_RUN. Validation: architecture-convergence, relay-protocol and session-transfer suites; repository privacy gate; build-history index check; git diff --check. INTEGRATION: PR #109 TRUE-MERGED to main via d66e7dacf57627f2dfd31d2b593bb2a49ce55c76 on 2026-09-08 under an explicit Product Owner RELAY_DECISION recorded on relay #4 (chat directive 2026-09-07 'Merge edelim'); head 825bec98 verified as an ancestor of origin/main.

## Risks forward

Nothing is implemented yet: nexus-po skill/agent, council skill, tracked permission rules, hook script, T7 test, the AGENTS.md/relay amendment text, and the direction record all await the section-10 sequence (step 0 extraction by the human in the previous tool; step 2 IMPLEMENTATION; step 3 DOCS reconciliation; step 4 ratification; step 5 first Phase A PLAN episode; step 6 isolation VALIDATION before Phase B). Same-model context separation is isolation, not cross-model independence; optional independent review stays available for security/identity/governance contracts.
