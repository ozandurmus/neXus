# GOV.PO.2 — Product Owner visibility and bounded authorship

## Status

**DRAFT — NOT IMPLEMENTATION AUTHORITY, awaiting Product Owner freeze.**
Produced by a Phase A `PLAN` episode (movement
`GOV_PO_2_PO_VISIBILITY_AND_BOUNDED_AUTHORSHIP`, local relay
`relay/NXS-LOCAL-0002-gov-po-2-visibility.json`, 2026-09-08), on Product
Owner direction after a run of small PO tool-gate fix movements
(`ozandurmus/nexus-agent-relay#11`–`#16`, then the local relay protocol
itself) surfaced repeated friction between the PO role's actual working
needs and its current tool-gate boundary. Explicitly subordinate to, and
**non-amending of**, `docs/design/GOV_PO_ROLE_MIGRATION.md` (FROZEN —
PRODUCT OWNER APPROVED, 2026-09-07) unless and until the Product Owner
separately freezes this document. This movement changes no FROZEN
document's text — it is a new, separate DRAFT contract only.
`GOV_PO_2_IMPLEMENTATION` (the code/test/agent-definition half) is blocked
until that freeze decision.

## 1. Purpose

`docs/design/GOV_PO_ROLE_MIGRATION.md` ("GOV.PO.1") drew the PO role's
boundary before the role had actually run. Six gate-fix movements since
(`GOV_PO_1_GATE_1`–`GATE_4`, the `M10.1`/`gate_3` bookkeeping backfill, and
the local relay protocol) are not evidence that the boundary was wrong —
they are evidence that a boundary drawn from first principles needed real
use to find its actual seams. Three seams recurred:

1. **Observation gaps.** The PO role can read any file but cannot see a
   PR's actual diff (`gh pr diff`) or CI run detail (`gh run view`/
   `gh run list`) — only the coarse pass/fail summary `gh pr checks`
   already gives it. A `REVIEW` episode reviewing "is this PR's diff
   correct" today cannot look at the diff.
2. **A narrow but real authorship gap.** `GOV_PO_1_GATE_3_STATE_UPDATE`
   (`relay#16`) exists only because the PO role could describe exactly
   what a new frozen contract candidate should say, but had no path to
   author a `docs/design/*.md` draft itself — an engineering session had
   to be assigned as a scribe. This document itself is the concrete
   instance: it had to be filed as an engineering movement (this session)
   because the current gate cannot write a new `docs/design/*.md` file.
   This is stated as a **one-time consequence of the current restriction
   being planned around**, not as a precedent for routing future design
   authorship to engineers — once `GOV_PO_2_IMPLEMENTATION` ships the
   `po_drafts/` area (§3.2), a document exactly like this one is authored
   by the PO role itself, in `po_drafts/`, and only promoted out of it by
   a human or engineer act.
3. **Bookkeeping drift.** `gov_po_1_gate_3_agent_tool_council_path`
   deferred its own `project/build_history.json` record twice (once at its
   own `SESSION_CLOSE`, again at `gov_po_1_gate_4_issue_close_path`'s)
   before a third, dedicated movement (`relay#16`) backfilled it. The
   deferral was individually reasonable each time (a genuine concurrent-
   checkout race), but the pattern — bookkeeping as a separable, postponable
   follow-up — is itself the defect worth closing, not any one instance of
   it.

This document specifies precisely enough, for `GOV_PO_2_IMPLEMENTATION` to
build deterministically, a capability-based boundary that closes seams 1–3
without widening the PO role's actual authority: every new capability
below is either strictly read-only, confined to a new DRAFT-only authoring
area with a tool-enforced status-line check, or a closed naming convention
over an authorization the human already had to give explicitly today. The
human Product Owner's own decision authority is unchanged and is restated
below as a closed list, not because it was ambiguous, but because a
capability-based document should say what it does *not* touch as plainly
as what it does.

## 2. Four authorities

`GOV_PO_ROLE_MIGRATION.md` §4 already separates roles by a permissions
table (may read / may write / never). This document organizes the same
territory by **capability class** instead, because "PO may write path X"
and "PO may decide Y" are different kinds of claim that a permissions table
flattens onto one axis. The four classes below are additive to §4, not a
replacement for it — every cell in §4's table still holds; this section
names *why* each cell holds, in a shape `GOV_PO_2_IMPLEMENTATION`'s
acceptance criteria can reference directly.

### 2.1 OBSERVATION — read-only inspection

**Already true today**, unchanged by this document: unrestricted file read
via `READ_TOOLS` (`Read`, `Grep`, `Glob`, `LS`, `ToolSearch`, `WebFetch`,
`WebSearch`, `TodoWrite`) in both PO forms; `gh issue view/list`, `gh pr
view/checks/list`, read-only `git` (`status`, `log`, `diff`, `show`,
`rev-parse`, `fetch`, `branch --show-current`/`--list`, `merge-base`,
`cat-file`, `ls-files`); `scripts/gov_session_transfer.py
validate`/`local_relay.py status`/`validate` (both forms, read-only).

**Genuinely new** (§3.1, §3.5): `gh pr diff`, `gh run view`, `gh run list`
as Bash prefixes in both forms — closing the PR-diff and CI-detail gap
named in §1 item 1; and the `nexus-po-evidence-reviewer` agent, a
read-only fresh-context reviewer for exactly this evidence, scoped in
§3.5.

### 2.2 GOVERNANCE AUTHORSHIP — writes confined to a named, non-authoritative area

**Already true today:** `Edit`/`Write` on the eight exact `GOVERNANCE_PATHS`
entries (`docs/design/PRODUCT_DIRECTION_RECORD.md`,
`project/roadmap.json`, `project/backlog.json`,
`project/feature_registry.json`, `project/build_history.json`,
`docs/history/INDEX.md`, `CURRENT_STATE.md`, `AI_HANDOVER.md`) and the flat
`relay/*.json` local-relay pattern, interactive form only.

**Narrowed** (§3.3): `docs/history/INDEX.md` leaves `GOVERNANCE_PATHS` — it
becomes reachable only through the already-allowlisted
`scripts/build_history_index.py`, matching the discipline every engineering
movement in this session already followed by hand (`gov_po_1_gate_4`,
`relay#16`) rather than hand-editing a file whose own header claims to be
generated.

**Genuinely new** (§3.2): `Edit`/`Write` on `docs/design/po_drafts/*.md`,
interactive form only, gated by a deterministic status-line check (§3.2)
that rejects any PO-authored write claiming `FROZEN` or `RATIFIED` status
for the drafted document. A document under `po_drafts/` is never
implementation authority by construction (`AGENTS.md` "Authority
hierarchy" item 2 already treats a `DRAFT` document this way); promoting
one to a real `docs/design/*.md` contract — renaming it out of
`po_drafts/`, writing its `FROZEN`/`RATIFIED` status line — is a human or
engineer act, never a PO-authored edit, exactly as this document itself
cannot declare its own freeze.

### 2.3 ENGINEERING EXECUTION — unchanged

Code, tests, `templates/`/`static/`/`console/`/`utils/`/`configuration/`/
`checkpoint/`/`panorama/` source, state-file bookkeeping *inside an
engineering movement's own commit* (§5), Git writes beyond a `gov/po-*`
branch, merges, and `SESSION_CLOSE` remain exclusively the engineer's role.
Nothing in this document moves any of this to the PO role, in either
direction.

### 2.4 HUMAN DECISION AUTHORITY — closed set, unchanged

Restated as a closed list, not because §4 left it ambiguous but because a
capability-based document should be explicit about what it deliberately
does not touch: **freeze, ratification, scope expansion, any security /
identity / credential / storage-schema boundary, real-device contact,
waiver, deployment, and merge (governance or product, of any kind)**. No
standing-delegation class in §4 below ever covers an item on this list; an
agent-published `RELAY_DECISION` still requires the Product Owner's written
authorization behind it exactly as `GOV_PO_ROLE_MIGRATION.md` §6.3 (D2–D4,
D12) already requires. This document proposes no exception to that.

## 3. Exact mechanisms (`GOV_PO_2_IMPLEMENTATION` build targets)

Everything in this section is a specification for the later `IMPLEMENTATION`
movement, not code shipped by this movement (§7 scope).

### 3.1 New read-only Bash prefixes

Add to `COMMON_PREFIXES` in `scripts/nexus_po_tool_gate.py` (both forms,
matching the existing `gh pr view`/`gh pr checks`/`gh pr list` precedent):

```
"gh pr diff", "gh run view", "gh run list"
```

Explicitly **not** added: `gh run cancel`, `gh run rerun`, `gh run watch`
(the last has a side-effecting default of streaming and can trigger
re-runs via its own flags) — anything in the `gh run` surface with a
write or long-blocking effect stays out. This is a straight prefix
addition; no new content-inspection logic is needed since these commands
take no PO-authored text as input.

### 3.2 `docs/design/po_drafts/*.md` and its status-line detection rule

**Path pattern.** `docs/design/po_drafts/*.md` — flat directory (no
subdirectories), added as a second pattern list in
`scripts/nexus_po_tool_gate.py` alongside (not merged into)
`GOVERNANCE_PATHS`, the same way `LOCAL_RELAY_FILE_PATTERN` was added
(`GOV_PO_1_LOCAL_RELAY_PROTOCOL` precedent): `Edit`/`Write` on a target
matching this pattern is allowed in interactive form only, subject to the
check below; delegated form is unaffected (it has no `Edit`/`Write` tool
at all).

**Status-line detection rule.** A PO-authored write must never claim
`FROZEN` or `RATIFIED` status for the document it is writing — those are
human-only declarations (§2.4). The repository's own status-line
convention, verified against three current documents
(`GOV_PO_ROLE_MIGRATION.md`: `**FROZEN — PRODUCT OWNER APPROVED,
2026-09-07**`; `GOV_SESSION_TRANSFER_PROTOCOL.md`:
`**FROZEN — PRODUCT OWNER APPROVED, ...**`; `LOCAL_RELAY_PROTOCOL.md`:
`**DRAFT — not ratified.**`), is a bolded span whose first word, at the
start of its line, is the status token. The exact rule:

```python
_PROMOTED_STATUS_RE = re.compile(r'^\s*\*\*\s*(FROZEN|RATIFIED)\b', re.MULTILINE)
```

Applied to the write's own new content — `tool_input["content"]` for
`Write`, `tool_input["new_string"]` for `Edit` — when the resolved target
path matches `docs/design/po_drafts/*.md`. A match denies the write. This
is deliberately narrower than a bare substring search for `FROZEN`/
`RATIFIED` anywhere in the text: this very document's own §1 discusses the
FROZEN `GOV_PO_ROLE_MIGRATION.md` in prose repeatedly, and a bare-word ban
would make it impossible for a `po_drafts/*.md` document to even reference
its own frozen parent authority, which every such document must do. Anchoring
on "first word of a bolded line" matches the convention precisely and
verifiably (three real examples above) without banning ordinary references
to frozen contracts elsewhere in the same file.

**Honest limitation.** This is a heuristic over free-text Markdown, not a
cryptographic guarantee — a sufficiently determined edit could phrase a
status claim to evade the regex (e.g. splitting the bold span, or omitting
the leading `**`). The PO assistant is not modeled as an adversarial actor
in this trust boundary (`GOV_PO_ROLE_MIGRATION.md` §2's whole premise), so
this is not a security control against a hostile PO session; it is a
fail-fast correctness check that catches the honest mistake ("the PO
episode wrote FROZEN because it echoed the target document's eventual
intended status"). The real control, as for any `po_drafts/*.md` output,
remains the human's own review of the file's content before ever treating
it as authoritative — this document does not claim otherwise.

### 3.3 `docs/history/INDEX.md` leaves `GOVERNANCE_PATHS`

Remove `"docs/history/INDEX.md"` from the `GOVERNANCE_PATHS` tuple in
`scripts/nexus_po_tool_gate.py`. It remains reachable exactly as it is for
every role today: only through `scripts/build_history_index.py` (already
allowlisted in both `COMMON_PREFIXES`, read-only `--check`, and
`INTERACTIVE_EXTRA_PREFIXES`, generating). This is a pure narrowing — no
new write surface, one fewer way to hand-edit a file whose own file header
already claims to be generated, and one fewer way to drift out of sync
with `project/build_history.json` (the exact defect
`scripts/build_history_index.py`'s own module docstring was written to
close, per `tests/test_architecture_convergence.py`).

### 3.4 The offline repository-privacy-check mechanism

**The gap.** No PO form can currently run the repository privacy gate at
all. `.claude/nexus-po.settings.json`'s `permissions.deny` blocks every
`main.py` invocation outright (`Bash(python main.py *)` and its `python3`/
`py`/`.venv/bin/python` siblings, unconditionally — not scoped to a
specific flag), and independently, `nexus_po_tool_gate.py::decide()`'s own
`DANGEROUS_BARE_WORDS`-adjacent check denies the bare token `main.py` (or
any path ending `/main.py`) unconditionally in both forms. A PO episode
drafting a `docs/design/po_drafts/*.md` document, or `project/*.json`
bookkeeping, today has no way to verify it introduced no privacy-sensitive
value before handing it to the human.

**Decision: a new standalone script, not a `main.py` exception.**
`main.py` itself is a thin bootstrap (`main.py`'s own module docstring:
"Thin CLI/bootstrap layer... delegates to `application.cli.run`"); even the
one branch that matters here,
`application.workflows.maintenance.repository_privacy_check()`, does
nothing but call `utils.repository_privacy.scan_repository(_REPO_ROOT)`
and print its report — but *reaching* that branch through `main.py`
requires importing `application.cli`, which at module level imports
`application.workflows.checkpoint`/`maintenance`/`recovery` and
`application.context.ApplicationContext` — the entire vendor-collector
import surface — merely to parse arguments and dispatch. A narrow,
exact-match gate exception for `<interpreter> main.py
--repository-privacy-check` (and nothing else) would also need to
enumerate every accepted interpreter spelling
(`python3`/`python`/`py`/`.venv/bin/python`, mirroring the existing
`scripts/gov_session_transfer.py` prefix list), and would silently need
re-verification every time `application/cli.py`'s argument surface changes
near that flag (e.g. a future flag combination check). A standalone
script avoids both costs and matches the established `scripts/` pattern
(`gov_session_transfer.py`, `local_relay.py`, `build_history_index.py`):
narrow, stdlib-plus-one-import, offline, no vendor surface.

**Specification:** `scripts/repository_privacy_check.py`, importing
*only* `utils.repository_privacy.RepositoryPrivacyError` and
`scan_repository` (the exact two names
`application/workflows/maintenance.py::repository_privacy_check()`
already imports lazily) — no import of `application.*`, `config`, or any
vendor/collector module. It reproduces
`repository_privacy_check()`'s exact print format and exit-code contract
(`0` on `PASS`, `1` on any other gate value, `2` on
`RepositoryPrivacyError`) so its output is identical to `main.py
--repository-privacy-check`'s today, byte-for-byte other than the banner
line. Add `"python3 scripts/repository_privacy_check.py"` (and its
`python`/`py`/`.venv/bin/python` siblings) to `COMMON_PREFIXES` — read-only,
no arguments, safe in both forms, mirroring
`gov_session_transfer.py validate`'s own treatment.

### 3.5 The `nexus-po-evidence-reviewer` agent

New `.claude/agents/nexus-po-evidence-reviewer.md`, modeled exactly on
`.claude/agents/nexus-council-seat.md`: `tools: Read, Grep, Glob`;
`disallowedTools: Agent, Bash, Edit, Write, MultiEdit, NotebookEdit`;
`permissionMode: plan`; no `memory` field; not a `fork`. It has **no**
`Bash` tool, so it cannot itself call `gh pr diff`/`gh run view` — the
calling PO session gathers that read-only evidence first (§3.1) and hands
the diff/log text as part of the seat's brief, exactly as a
`nexus-council-seat` brief already carries "the exact document and section
range under review" rather than letting the seat go fetch it. Its brief
names: the PR or run under review, the exact diff or log excerpt, the
claim being checked, and its protected concern (correctness, scope
match, or privacy — one per invocation, not a general "review this").

**Gate-side change:** generalize the existing single-string check in
`nexus_po_tool_gate.py::decide()`'s `Agent` branch from one hardcoded
literal to membership in a small closed set:

```python
ALLOWED_INTERACTIVE_SUBAGENTS = {"nexus-council-seat", "nexus-po-evidence-reviewer"}
...
if tool == "Agent":
    if form == "interactive" and tool_input.get("subagent_type") in ALLOWED_INTERACTIVE_SUBAGENTS:
        return True, f"{tool_input['subagent_type']} launch (GOV_PO_ROLE_MIGRATION.md section 7/8, GOV.PO.2 section 3.5)"
    return False, "..."
```

Never a general `subagent_type`-agnostic allow — `GOV_PO_1_GATE_3`'s own
acceptance criteria (exact-string match, near-miss/missing/empty all
denied) apply identically to this second entry, and
`GOV_PO_2_IMPLEMENTATION`'s own tests must re-run the equivalent of
`gov_po_1_gate_3`'s AC-1/AC-2/AC-3 cases for both allowed subagent types
plus at least one near-miss for each.

## 4. Standing delegation classes (D2 generalized, not a new mechanism)

`GOV_PO_ROLE_MIGRATION.md` D2 already allows the Product Owner to
authorize a decision "once, in writing," with the authorization
persisting until revoked or superseded. This section names the specific
classes of *routine* decision the Product Owner may choose to authorize
this way, so a `RELAY_DECISION`'s `authorized_by` field can cite a class
by name (`authorized_by: Product Owner — standing delegation "routine
review", <date>`) instead of re-quoting the same instruction on every
occurrence. **This is a naming convention over an authorization
mechanism that already exists (D2); it adds no new tool, gate check, or
enforcement path** — `nexus_po_tool_gate.py` does not parse or validate
`authorized_by` text today and this document does not ask it to.

Candidate classes: **routine review** (a `REVIEW` episode's own findings
table and any `needs-decision: no` rows), **correction** (a
`RELAY_CORRECTION` for stale or malformed prior relay material),
**sequencing** (`now_next.now`/`now_next.next` reassignment within
already-`upcoming` rows), **backlog prioritization** (re-ranking existing
`project/backlog.json` items, not adding or removing scope), **movement
slicing** (splitting an oversized frozen contract into sequential slices,
the `M8.1`–`M8.4`/`M10.1`–`M10.3` precedent), and **next-prompt
production** (drafting the next movement's `SESSION_START`).

**Explicitly excluded from every standing-delegation class — the same
closed list as §2.4, restated for emphasis because this is exactly where
a standing delegation could be misread as broader than it is:** freeze,
ratification, scope expansion, any security/identity/credential/
storage-schema boundary, real-device contact, waiver, deployment, and
merge of any kind. A `RELAY_DECISION` citing a standing-delegation class
for one of these is an invalid authority claim regardless of the class
name used, exactly as an unlabeled decision comment is today
(`NEXUS_AGENT_RELAY_PROTOCOL.md` §1).

## 5. Project-state bookkeeping stays inside the engineering movement

**Policy, not a new gate mechanism** — restating and generalizing the
practice already followed for `gov_po_1_gate_4_issue_close_path` and the
`relay#16` backfill, so the pattern that produced the `gate_3` deferred-
bookkeeping incident (§1 item 3) does not recur: project-state bookkeeping
(`project/build_history.json`, `project/roadmap.json` `now_next`/
`current_build`, `CURRENT_STATE.md`, `AI_HANDOVER.md`,
`docs/history/INDEX.md` regeneration) for any engineering movement that
touches code or tests is that movement's own engineer's responsibility,
landed in that movement's own commit/PR — never split off into a separate
PO-opened follow-up movement merely because a concurrent-checkout race or
similar transient condition makes it inconvenient in the moment. A genuine
blocking condition (an actually-shared, actually-racing checkout, as
`gate_3` had) is reported as an explicit, named risk in that movement's own
`SESSION_CLOSE` `next` field with a concrete owner and objective — exactly
as `gate_3`'s did — but the default expectation this document states is
that engineers land their own bookkeeping in the same commit, and treat a
deferral as the exception requiring justification, not the routine path.

## 6. Recommended freeze path: independent review before freeze

This document's own eventual freeze decision — a separate, future `DECIDE`
episode, not this `PLAN` — should invoke `nexus-decision-council` under
`GOV_PO_ROLE_MIGRATION.md` §7 trigger (b): *"a freeze candidate introducing
a security, identity, credential, storage-schema or write boundary."* This
document proposes a real widening of what the PO role may author
(`po_drafts/*.md`) and spawn (`nexus-po-evidence-reviewer`), even though
every new capability stays either strictly read-only or DRAFT-only with a
tool-enforced status check — it is exactly the class of change trigger (b)
names for council review rather than freezing on the human's own read
alone. This movement records the recommendation; it does not act on it
(`GOV_PO_ROLE_MIGRATION.md` §7: council is invoked only from `PLAN`/`DECIDE`
episodes, never performed inside the episode that merely recommends it).

## 7. Scope

**In:** `docs/design/GOV_PO_2_PO_VISIBILITY_AND_BOUNDED_AUTHORSHIP.md`
(this document, new); this movement's own project-state bookkeeping
(`project/build_history.json`, `project/roadmap.json`, `CURRENT_STATE.md`,
`AI_HANDOVER.md`, `docs/history/INDEX.md` regeneration); the local relay
file `relay/NXS-LOCAL-0002-gov-po-2-visibility.json` (this movement's own
`SESSION_CLOSE` entry, appended via `scripts/local_relay.py`).

**Out (all deferred to `GOV_PO_2_IMPLEMENTATION`, after freeze):** any
change to `scripts/nexus_po_tool_gate.py`, `.claude/nexus-po.settings.json`,
any agent definition file (new or existing), or
`tests/test_gov_po_role.py`; any change to a FROZEN document's own text;
freezing or ratifying this document itself (the Product Owner's separate,
later act); invoking `nexus-decision-council` (§6 only records the
recommendation for that future episode).

## 8. Non-supersession

This document does not amend, deprecate, or narrow
`docs/design/GOV_PO_ROLE_MIGRATION.md`, `docs/design/
NEXUS_AGENT_RELAY_PROTOCOL.md`, `docs/design/GOV_SESSION_TRANSFER_PROTOCOL.md`,
or `docs/design/LOCAL_RELAY_PROTOCOL.md`. All four stay exactly as they
are; this document proposes an amendment candidate to
`GOV_PO_ROLE_MIGRATION.md`'s §4 permission boundaries and §6 gate
mechanics, applied only if and when the Product Owner freezes it and
`GOV_PO_2_IMPLEMENTATION` lands it — the same two-step pattern
`GOV_PO_ROLE_MIGRATION.md` §5.1.3 (D16) itself used for its own approved
amendment text.
