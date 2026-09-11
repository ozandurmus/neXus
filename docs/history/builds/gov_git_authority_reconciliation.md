# gov_git_authority_reconciliation — GOV.GIT.1 -- Product Owner authorization and agent execution reconciliation

## Summary

Clarifies that Corporate Git remains Product Owner controlled through explicit authorization while an authorized agent may create a PR, push, merge, and verify the named action. Limits an agent to one concrete objection and makes a repeated informed Product Owner instruction controlling, subject only to exact higher-authority or failing-gate conflicts.

## Evidence

Governance-only branch gov-git-authority-reconciliation from origin/main fff475e5cb646c33f9416a93f18c0c4f3402739b. Historical GitHub audit: 107 PRs, all created under ozandurmus; 106 merged, all mergedBy ozandurmus; one open PR (#104). GitHub authentication identity cannot prove whether the human or an authorized agent operated the client. PR bodies carry Claude attribution on 101 PRs and Codex attribution on 2, but generated-body attribution is evidence of drafting only, not a reliable execution identity. Commit 64c8d79898999baab3f7cfa538912c68eed0a5df, authored by Claude and integrated through PR #33, introduced the ambiguous Corporate Git push/merge remains human-controlled sentence. Validation: relay/architecture convergence 28 passed; application/privacy 14 passed; repository privacy gate PASS/0; project metadata warnings []; build-history index and git diff --check clean.

## Risks forward

No product or M9 behavior changes. Claude remains M9 implementation owner; PR #104 is untouched. Governance PR and relay correction remain pending.
