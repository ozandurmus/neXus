# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy".

## 1. Snapshot

- Date: 2026-09-07. `gov_po_1_role_migration_contract` (`GOV.PO.1`) —
  **contract FROZEN, AUTOMATED_VALIDATED**, governance-only. Branch
  `gov-po-1-role-migration-contract` from `origin/main`
  `ae5eb34fee25b16e759f2ed39e9890fe6d11a130`; PR opened, unmerged, merge
  awaits an explicit Product Owner `RELAY_DECISION`.
- Predecessor `M9` **MERGED** via PR #104 (true merge `5fc88a21…`);
  `GOV.GIT.1` **MERGED** via PR #108. `M8.3` stays deferred, `M7` stays
  blocked.

## 2. What changed

- `docs/design/GOV_PO_ROLE_MIGRATION.md` (new, FROZEN): sixteen recorded
  Product Owner decisions (D1–D16), roles and permission boundaries
  (interactive vs delegated `nexus-po`, council as instrument, engineer,
  optional independent reviewer), `PRODUCT_DIRECTION_RECORD.md`
  ratification mechanics, four PO episode types with SESSION START/CLOSE
  compatibility, the approved §5.1.3 amendment text (exact `AGENTS.md` and
  relay wording), isolation claims checked against platform docs, seven
  behavioral acceptance tests (all `NOT_RUN`), size planning targets, and
  the §10 sequence.
- `.github/prompts/po-knowledge-extraction.prompt.md` (new): one-time
  extraction prompt with `[REPO]`/`[PO-DIRECTION]`/`[ASSISTANT]` provenance.
- `project/build_history.json`, `project/roadmap.json`, `docs/history/INDEX.md`,
  `CURRENT_STATE.md`: new build record and checkpoint. No source, test,
  rule or relay-contract text changed.

## 3. Exact next action

`GOV.PO.1` §10, in order: step 0 (human runs the extraction prompt in the
previous tool, saves `docs/design/PRODUCT_DIRECTION_RECORD.md` as `DRAFT`);
step 2 `IMPLEMENTATION` (`.claude/agents/nexus-po.md`,
`.claude/skills/nexus-po/SKILL.md`, `.claude/skills/nexus-decision-council/SKILL.md`,
tracked `.claude/settings.json` rules + `scripts/` hook, tool-neutral PO
prompts, `CLAUDE.md` delta, T7 test, relay §6.3 clause, the approved §5.1.3
amendment verbatim into `AGENTS.md` and the relay contract). Step 0 and
step 2 are independent and may run in parallel. Do not start Phase A
until §6.1 prerequisites hold.

## 4. Test delta

Architecture-convergence, relay-protocol and session-transfer suites,
build-history index check, repository privacy gate, `git diff --check`
— results in the `SESSION_CLOSE` on the movement's relay issue. No full
regression (docs/metadata-only change, risk-based).

## 5. Risks / notes forward

- Nothing from the contract is in force yet; a brief naming `nexus-po` or
  the council before step 2 merges names an uninstalled capability.
- Same-model context separation is isolation, not independence; §9
  independent review stays optional for security/identity/governance
  contracts.
- The `.venv` DLP-scanner false positives are still not a backlog item
  (owed by §10 step 5).
