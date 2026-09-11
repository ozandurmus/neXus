# GOV.PO.1 — Product Owner assistant role migration and two-context governance

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-07** (whole-document freeze
instructed by the Product Owner after review of revision 4; the §5.1.3
amendment was separately approved with boundaries the same day, D16).
Revision history: revision 1 draft; revision 2 per "GOV.PO.1 CONTEXT
REBASE"; revision 3 per "GOV.PO.1 REVISION 2 DECISIONS"; revision 4 per
"GOV.PO.1 §5.1.3". The role direction is approved: the PO assistant role
moves to Claude; the human remains the Product Owner and the only source of
decision authority; the previous assistant becomes an optional independent
reviewer. This freeze authorizes the §10 sequence and nothing else: no
skill, agent definition, rule text or relay clause is in force until the
§10 step-2 `IMPLEMENTATION` movement merges it. The §5.1.3 amendment text
enters `AGENTS.md` and the relay contract only through that step.

Parent authority (unamended, subordinate per `AGENTS.md` "Authority
hierarchy"): `AGENTS.md` including "Git authority and execution law"
(`GOV.GIT.1`, PR #108); `docs/design/GOV_SESSION_TRANSFER_PROTOCOL.md`
(FROZEN, protocol v2, preserved unchanged with its parser);
`docs/design/NEXUS_AGENT_RELAY_PROTOCOL.md` (FROZEN; question-routing and
authorized-execution amendments).

| | |
| --- | --- |
| **Movement** | `ARCHITECTURE`, `FROZEN` |
| **Baseline** | `main` at `ae5eb34fee25b16e759f2ed39e9890fe6d11a130`, verified 2026-09-07 against `origin/main` |
| **Preserves unchanged** | packet v2 schema and parser; the five relay markers; the human Product Owner as sole decision authority; the `AGENTS.md` authority hierarchy; the mandatory engineer SESSION START/CLOSE lifecycle; historical council disclosures |

---

## 1. Verified baseline (2026-09-07)

Reconstructed from `origin/main`, `project/*.json`, `CURRENT_STATE.md` and
relay `ozandurmus/nexus-agent-relay#3`; no chat baseline reused.

- `M9` (`m9_enrollment_preview_confirmation_ui`): PR #104 **MERGED**,
  true merge `5fc88a21462eabb724f767f38a34ffd529eb008f`; reconciliation
  commit `ae5eb34f…` is the current `origin/main` head. Relay #3's final
  comment is one protocol-v2 `SESSION_CLOSE` and validates
  (`scripts/gov_session_transfer.py validate` → `valid: true`). Status
  `automated_validated`; `now_next.next` is `m8_3_real_environment_validation`
  (`deferred`, PO §12). No pending M9 merge step exists.
- `GOV.GIT.1` (PR #108, merge `9d0a52cf…`): `AGENTS.md` "Git authority and
  execution law" plus matching wording in `AI_START_HERE.md`,
  `docs/AI_DEVELOPMENT_PROTOCOL.md`, `NEXUS_AGENT_RELAY_PROTOCOL.md` §5 item 6
  and §7, `CLAUDE.md`, `relay-bootstrap.prompt.md`. Explicit human approval
  and authorized agent execution are already reconciled; this document
  builds on that law and reports no contradiction there.
- `project/backlog.json`: 84 items; by status `planned` 28,
  `automated_validated` 28, `deferred` 9, `done` 9, `real_env_validated` 5,
  `in_progress` 5; by priority prefix `P0` 24, `P1` 31, `P2` 24, `P3` 3,
  `P4` 1, unset 1. The two `.venv` DLP-scanner false positives named in
  `AI_HANDOVER.md` are **not yet a backlog item**.
- Council capability: no `.claude/skills/`, `.claude/agents/`,
  `~/.claude/skills/` or `~/.claude/agents/` exist on this machine;
  `.claude/` holds only the untracked `settings.local.json`. The
  `nexus-decision-council` has never been installed; recorded rounds were
  single-author self-critique (`CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md`
  §10 disclosure, preserved as-is by §8 below).

## 2. Problem

The PO assistant role ran in a different vendor tool from the engineering
role. That gave cross-model independence and a long chat memory of the
decision tree, at the cost of a context ceiling now hit continuously,
manual chat-to-chat transport on every boundary, and correction rounds
caused by two contexts drifting apart.

Repository facts bounding the solution:

1. `AGENTS.md` is vendor-neutral and ranks chat memory as never
   authoritative; the repository is the only long-horizon memory the
   governance recognizes.
2. `GOV.SESSION.1A` and `GOV.RELAY.1` already make the PO/engineer boundary
   a tool-independent interface.
3. No independent council existed (§1), so the migration does not lose one.

**Isolation and independence are different properties.** Separate contexts
of the same model remove the engineering chat's influence on a PO episode
(isolation). They do **not** remove blind spots shared by one model family
(independence). This document delivers isolation by construction and keeps
independence as an optional, explicitly scoped cross-model review (§3,
§9).

## 3. Product Owner decisions incorporated (directive of 2026-09-07)

Recorded here so they are durable; each becomes a rule in the section named.

| # | Decision | Applied in |
| --- | --- | --- |
| D1 | The PO assistant role moves to Claude. The human remains Product Owner and sole decision authority. The previous assistant becomes an optional independent reviewer. | §4, §9 |
| D2 | An agent may publish and execute decisions within explicit Product Owner authorization. Authorization persists within its stated scope until revoked or superseded; it does not require approval of every word in the same session. | §6.3 |
| D3 | Every published decision records its authorization source, scope, and any decision it supersedes. The agent never invents approval or decides outside scope. | §6.3 |
| D4 | One concrete concern may be raised once; a knowing reaffirmation controls. A genuinely new blocker is reported precisely; the same objection is not reopened. (Restates `AGENTS.md` "Git authority and execution law" for PO episodes.) | §6.3 |
| D5 | 8 acceptance criteria / 12 non-test source files are **planning targets with justified exceptions**. Requirements are never merged or hidden to satisfy the counts. | §7 |
| D6 | Review finding count alone never forces a smaller movement; severity, coupling and cause are assessed. | §7 |
| D7 | `DIRECTION_AUDIT` runs every fifth closed movement and at track closure. | §5 |
| D8 | Cross-model review stays optional for critical security, identity and governance contracts. | §9 |
| D9 | `PRODUCT_DIRECTION_RECORD.md` is a ratified rationale/direction record subordinate to the authority hierarchy; the human ratifies; the outgoing assistant supplies evidence and recommendations only. | §4 |
| D10 | Isolation is a claim requiring proof. First use is a separate interactive PO session; delegated subagent episodes open only after isolation is demonstrated. | §6.1, §6.4 |
| D11 | Knowledge extraction separates verified repository decisions, explicit human directions not yet recorded, and assistant observations/hypotheses, each with provenance. | §4, extraction prompt |
| D12 | A written Product Owner instruction in a PO episode establishes authorization immediately. Before **another context** relies on it, the authorized assistant records it on the relevant relay as `RELAY_DECISION` with source, scope and supersession; recording needs no second approval and must not broaden scope. A GitHub account name alone never proves human authorization. | §6.3 |
| D13 | `REVIEW`/`DECIDE` episodes about an existing engineering movement stay on that movement's relay issue; a separate governance movement gets its own issue only with a distinct objective and deliverable. No implicit "not a build" exemption from `AGENTS.md` "Mandatory session start / close" is introduced. | §5, §5.1 |
| D14 | An explicitly authorized governance PR merge is the ratification of the reviewed direction-record changes; the PR reference and date are recorded. Authority comes from the Product Owner authorization covering those changes, not from the merge event; an agent merging its own proposal without that authorization ratifies nothing. | §4.1 |
| D15 | Documented platform capability and demonstrated isolation are kept separate. Phase A begins only when its prerequisites and authorization are satisfied; Phase B stays gated by behavioral evidence; no test is marked passed on documentation alone. | §6.1, §6.2, §6.4 |
| D16 | **§5.1.3 approved (2026-09-07), with boundaries:** `SESSION START` stays mandatory and is produced in the PO session; the episode closes with a plain-text `RELAY_NOTE episode close` on the existing movement issue carrying evidence, outputs, unresolved risks, next movement and reasoning tier; no additional packet is posted to that issue; the project-state / `AI_HANDOVER.md` exemption applies **only** when the episode changes none of the state those artifacts govern; a decision changing scope, delivery state, architecture, debt or sequencing triggers the applicable durable-state updates through an explicitly owned governance or engineering movement, which the episode names together with the required follow-up, and the episode cannot declare the affected work complete while those updates are outstanding; the episode-close note never substitutes for `RELAY_DECISION`, whose source/scope/supersession record is retained; packet v2 schema/parser and the five relay markers are preserved. This approves §5.1.3 only; whole-document freeze is separate. | §5.1.3 |

## 4. Roles and permission boundaries

| Role | Runs where | May read | May write | Never |
| --- | --- | --- | --- | --- |
| **Product Owner (human)** | terminal, GitHub | everything | decisions, authorizations, freezes, ratification | delegates decision authority |
| **`nexus-po` — interactive session** (PO assistant, Phase A form) | a Claude Code session the human opens with the `nexus-po` skill loaded; session-level permission rules in the tracked `.claude/settings.json` confine writes to the governance paths below | `AI_START_HERE.md` reading order; `CURRENT_STATE.md`; `project/*.json`; `docs/design/PRODUCT_DIRECTION_RECORD.md`; the active relay issue (`gh issue view`); the one contract the episode names; source/tests only by narrow search to verify a claim | (a) relay comments `RELAY_NOTE`, `RELAY_QUESTION`, and `RELAY_DECISION` under §6.3; (b) **edits, commits and pushes** a governance branch `gov/po-*` containing only `docs/design/PRODUCT_DIRECTION_RECORD.md`, `project/roadmap.json`, `project/backlog.json`, `project/feature_registry.json`, `project/build_history.json` and their convergence-test consequences, and **opens** its PR, each Git action under the human's written authorization in that session (`AGENTS.md` "Git authority and execution law"); (c) drafted `SESSION_START` packets rendered by `scripts/gov_session_transfer.py` | edit product source, tests, `templates/`, `static/`, `console/`, `utils/` (blocked by the path rules, not only by instruction); run collection or device contact; **merge** its own governance PR without a recorded `RELAY_DECISION` authorizing that merge; invoke the council outside §7's triggers |
| **`nexus-po` — delegated subagent** (Phase B form, gated by §6.4) | `.claude/agents/nexus-po.md`, invoked from an engineering session with only a relay locator | the same set, via read/search tools and `gh issue view` | relay comments only (`RELAY_NOTE`, `RELAY_QUESTION`, `RELAY_DECISION` under §6.3) | any file edit (`Edit`/`Write` absent from its tool list); any Git write; council invocation until T5 passes |
| **`nexus-decision-council`** (instrument) | parallel fresh subagents, one per seat, invoked only from a `nexus-po` episode | the one contract under review, its parent authority, `AGENTS.md` | one consent/dissent table per seat, returned to the invoking episode, which synthesizes | decide; be invoked by the engineer; replace real-environment evidence |
| **Engineer** (existing role) | Claude Code session | reading order as today | code, tests, state files, `SESSION_CLOSE`; **installs** the council/PO skill files in the §10 step-2 movement (infrastructure, not invocation) | invoke the council for review; answer its own `RELAY_QUESTION`; pre-empt a PO decision in chat |
| **Independent reviewer** (optional; previous assistant, other tool) | separate tool, one document per request | one FROZEN-candidate contract | one written review, attached to the relay as `RELAY_NOTE independent review` by the human or the PO episode | act as PO; hold memory obligations; issue decisions |

Who edits, commits and publishes a governance proposal is therefore one
role in one form: the **interactive** `nexus-po` session, with the human
present, under path-scoped session permissions. The delegated subagent
form never edits. Authorization crosses that boundary in writing: the
human's instruction in the interactive session authorizes branch, commit,
push and PR creation for the named changes; **merge** is authorized only by
a `RELAY_DECISION` on the governance issue recorded per §6.3 before any
agent executes it. A subagent that drafts a change returns it as text; the
interactive session, or the human, applies it.

The engineer's step-2 role is explicitly split: *building* the council and
PO skill files is an `IMPLEMENTATION` movement under a normal
`SESSION_START`; *invoking* the council for a review is a PO-episode
action. The same person may do both on different days; the same context
may not.

### 4.1 `PRODUCT_DIRECTION_RECORD.md` (PO durable state)

One new document, `docs/design/PRODUCT_DIRECTION_RECORD.md`. Authority
resolution (D9): it is a **ratified rationale and direction record** at
`AGENTS.md` authority level 6 (`docs/design`). Its status line reads
`RATIFIED — PRODUCT OWNER, <date>`, never `FROZEN`, because it authorizes
no implementation, no command, no schema, no identity model; a contract
that needs such authority is a separate FROZEN document. A contradiction
between this record and `project/*.json` or a FROZEN contract is reported,
not reconciled silently; the higher authority wins.

Content is produced by the one-time extraction
(`.github/prompts/po-knowledge-extraction.prompt.md`), with every item
tagged `[REPO <path>]`, `[PO-DIRECTION <date>]` or `[ASSISTANT]`
(D11). Before ratification the human resolves each `[PO-DIRECTION]` item
(confirm → it becomes a recorded direction; deny → it is deleted or kept as
`[ASSISTANT]` history) and each `[ASSISTANT]` item stays labelled as
hypothesis until repository evidence promotes it. Fixed sections: product
thesis and non-negotiables; decision record linked by id to
`project/roadmap.json` `open_decisions` (JSON stays the machine authority
for status; the record carries rationale only); rejected directions; review
heuristics; recurring engineering failure patterns; sequencing rationale;
sanitized real-environment constraints; open doubts. Later amendments occur
only inside a PO episode and merge through a governance PR.

**Ratification mechanics (D14).** The initial record and every later
amendment are ratified by the **explicitly authorized merge** of their
governance PR: the Product Owner's authorization covering those exact
changes is recorded as `RELAY_DECISION` on the governance issue (§6.3),
then the merge is executed. No separate signature is required. The
record's status line carries the evidence:
`RATIFIED — PRODUCT OWNER, <date>, PR #<n>, decision <relay ref>`; each
amendment appends one line in the same form. A merge without that recorded
authorization, including an agent merging its own proposal, ratifies
nothing and is reverted as an unauthorized Git action.

## Amendment A-2026-09-11 (PO decision pending)

The PO assistant role is tool-neutral. The current holder is recorded in
`docs/reference/COPILOT_OPERATING_MODEL.md` and may change without
re-freezing this contract. §9's independence rule is kept, with the
explicit consequence that when the orchestrator and the final reviewer are
the same tool, an independent review is satisfied only by a different
provider seat (`nexus-po-evidence-reviewer` or a council seat on a
different provider). This replaces the "Codex is final reviewer" claim
with "Codex synthesizes; an independent seat reviews".

## 5. PO episodes and their SESSION START/CLOSE compatibility

A PO episode is one fresh context that reads the fixed set in §4, produces
its output, and ends. Nothing carries to the next episode except through
the repository or the relay. The PO context is therefore never "switched";
it is discarded after every episode.

| Episode | Trigger | Output | Tier |
| --- | --- | --- | --- |
| `PLAN` | a movement closed, or the human wants the next movements sequenced | one drafted `SESSION_START` per next movement (targets in §7); proposed `project/*.json` diffs on a governance branch | Normal (strong); High when re-ranking track order |
| `REVIEW` | a `SESSION_CLOSE` landed on the relay, or a PR is ready | `RELAY_NOTE` findings table (finding, evidence path, severity, coupling, cause, needs-decision yes/no) plus a drafted `RELAY_DECISION` per needs-decision row | Normal (strong) |
| `DECIDE` | an open `RELAY_QUESTION` or a reported authority contradiction | one `RELAY_DECISION` per question under §6.3; council if §7 triggers | High |
| `DIRECTION_AUDIT` | every fifth closed movement and at track closure (D7) | diff between `PRODUCT_DIRECTION_RECORD.md` thesis/sequencing and the actual `roadmap.json` trajectory, filed as `RELAY_QUESTION`s | High |

### 5.1 Compatibility with `AGENTS.md` "Mandatory session start / close"

`AGENTS.md` requires a `SESSION START` at the start of **every build/task**
and a `SESSION CLOSE` before **declaring a build complete**, with
`AI_HANDOVER.md` rewritten as part of every `SESSION CLOSE`. PO episodes
are tasks; no episode is exempt from `SESSION START`. The rules below say
exactly how each episode satisfies the law, and where the law as written
cannot be satisfied without producing a contradiction, §5.1.3 proposes one
narrow amendment for explicit Product Owner approval rather than an
implicit exemption.

#### 5.1.1 Episodes that change repository state (`PLAN` with `project/*.json`
diffs; any direction-record amendment)

These are governance movements. Each has a distinct objective and
deliverable, so it gets **its own relay issue** (D13): the issue body is
the episode's own protocol-v2 `SESSION_START` (movement type `DOCS` for
record changes, `ARCHITECTURE` when `now_next` is re-sequenced); the final
engineering comment is its `SESSION_CLOSE`; `AI_HANDOVER.md` and
`project/build_history.json` are updated as for any build. The drafted
`SESSION_START` packets for *future engineering movements* are the
episode's deliverable, listed in its `SESSION_CLOSE` `completed`, and are
posted as the body of each future movement's own issue when that movement
starts. Fully compliant; no amendment needed.

#### 5.1.2 Episodes whose only outputs are relay comments (`REVIEW`,
`DECIDE`, `DIRECTION_AUDIT`)

These concern an existing engineering movement and stay on **that
movement's issue** (D13). Two constraints collide:

- `AGENTS.md` requires a `SESSION START` for the task, and a `SESSION
  CLOSE` with an `AI_HANDOVER.md` rewrite when a build is declared
  complete.
- `NEXUS_AGENT_RELAY_PROTOCOL.md` §3 allows exactly one `SESSION_START`
  packet (the body) and one `SESSION_CLOSE` packet (the final engineering
  comment) per issue, and forbids intermediate comments from being or
  masquerading as packets.

A comment-only episode therefore **cannot** put its own packets on the
movement issue without breaking the frozen relay contract, and it declares
no build complete and changes no durable state that `AI_HANDOVER.md` could
truthfully report. Without an amendment the only literal reading is: the
episode produces its `SESSION START` in-session (chat output, not a relay
comment) and, because it never declares a build complete, `AGENTS.md`'s
`SESSION CLOSE` trigger does not fire. That reading is an inference, and
D13 forbids relying on an inferred exemption.

#### 5.1.3 Narrow amendment — APPROVED by the Product Owner, 2026-09-07 (D16)

Approved as text; applied to the governing files only by §10 step 2 after
the whole-document freeze. The exact text to be inserted:

**`AGENTS.md`, section "Mandatory session start / close", new paragraph
after the GitHub-issue relay paragraph:**

> **Comment-only Product Owner assistant episodes.** A Product Owner
> assistant episode (`nexus-po`, `docs/design/GOV_PO_ROLE_MIGRATION.md`)
> whose only outputs are relay comments on an existing movement issue
> still produces its `SESSION START` at the start of the episode, in the
> PO session (movement type `READ_ONLY_AUDIT` for review and
> direction-audit episodes, `ARCHITECTURE` for decision episodes). It
> closes with exactly one plain-text `RELAY_NOTE episode close` comment on
> that same issue, never sentinel-wrapped, carrying: the episode type, the
> evidence inspected, the outputs produced (each relay comment by marker),
> unresolved risks, the recommended next movement, and its reasoning
> tier. No additional `SESSION_START` or `SESSION_CLOSE` packet is posted
> to that issue. Such an episode is exempt from the "Project-state update
> rule" and from rewriting `AI_HANDOVER.md` **only** when it changes none
> of the state those artifacts govern. A decision made in the episode that
> changes scope, delivery state, architecture, debt, or sequencing
> triggers the applicable durable-state updates through an explicitly
> owned governance or engineering movement: the episode-close note names
> that owner and the required follow-up, and the episode must not declare
> the affected work complete while those updates remain outstanding. The
> episode-close note is never a substitute for a `RELAY_DECISION`; every
> authorization decision retains its own source, scope, and supersession
> record. This paragraph changes neither the `NEXUS_SESSION_PACKET`
> schema/parser nor the relay marker set.

**`docs/design/NEXUS_AGENT_RELAY_PROTOCOL.md`, §4 "Intermediate comment
grammar", appended to the `RELAY_NOTE` bullet:**

> A `RELAY_NOTE episode close` is the closing record of a comment-only
> Product Owner assistant episode (`AGENTS.md` "Mandatory session start /
> close", comment-only episodes): plain text, not a packet, carrying no
> authority, and never a substitute for `RELAY_DECISION`. It leaves the
> one-`SESSION_START`-body / one-final-`SESSION_CLOSE`-comment shape of
> §3 and the five markers unchanged.

**Boundaries in force for every such episode (D16), restated as checks
the `nexus-po` skill applies before posting the close note:**

1. A `SESSION START` was produced in the PO session at the start.
2. The close note is plain text on the existing movement issue and lists
   evidence, outputs by marker, unresolved risks, next movement, tier.
3. No packet was posted to that issue by the episode.
4. The exemption test: did any decision in the episode change scope,
   delivery state, architecture, debt, or sequencing? If **no**, no
   project-state or `AI_HANDOVER.md` update is owed. If **yes**, the note
   names the owning governance or engineering movement and the exact
   durable-state updates it owes, and the episode does not declare the
   affected work complete.
5. Every authorization decision made in the episode exists as its own
   `RELAY_DECISION` with source, scope and supersession (§6.3); the close
   note only lists it.
6. Packet v2 and the five markers untouched.

Effect: `SESSION START` stays mandatory for every task; the close record
is durable on the relay; the relay's one-packet-per-direction rule is
untouched; durable state is updated by the movement that owns it, never
skipped.

## 6. Transport, isolation and authorized publication

### 6.1 Rollout order (D10)

**Phase A — interactive only.** The human opens a new Claude Code session
and invokes the `nexus-po` skill with an episode type. The session ends
when the episode's output is on the relay or on a governance branch.
Isolation from the engineering session is achieved by the human not
running both roles in one session; this is a discipline, not a proof, and
is acceptable for Phase A because the human is present in every PO turn.
Phase A prerequisites (D15), all required before the first episode: this
document `FROZEN`; §10 step 2 merged (skill, tracked `.claude/settings.json`
path rules, T7 green); `PRODUCT_DIRECTION_RECORD.md` present at least as
`DRAFT`; the Product Owner's written authorization to start Phase A,
recorded as `RELAY_DECISION` on the step-2 governance issue.

**Phase B — delegated episodes**, opened only after §6.4's acceptance tests
pass: from the engineering session the human invokes the `nexus-po`
**agent definition** as a subagent with only the relay locator
(`RELAY_READY owner/repository#issue`). The subagent's instructions are the
repository file `.claude/agents/nexus-po.md`; it reads the relay and the
repository and returns findings and decision drafts. Phase B is what ends
chat-to-chat transport; it is not assumed available until proven.

### 6.2 Isolation claims, checked against platform documentation

Each row is a **claim about platform behavior**, its documentation status
as checked on 2026-09-07 against the official Claude Code documentation
(`code.claude.com/docs`: sub-agents, permissions, hooks, tools-reference),
and the mechanism the design will use. "Documented" means the official
documentation describes the mechanism; it is **never** evidence that the
mechanism held in this repository's configuration (D15). Every row's
demonstrated status is `NOT_RUN` at freeze and changes only through §6.4;
"implied" or "undocumented" makes the test the only evidence.

| id | Claim | Documentation status | Mechanism in design |
| --- | --- | --- | --- |
| I1 | A custom (non-fork) subagent receives its definition body, the caller's task text, the `CLAUDE.md` hierarchy and a git-status snapshot; it does **not** receive the parent's conversation history, tool results, files read, or auto-memory. | **Documented** (sub-agents: "what loads at startup"). The `fork`/`/subtask` form inherits everything and is therefore prohibited for PO episodes. | `nexus-po` is a named custom agent; `fork` never used; no `memory` field (PO memory is the repository, §4.1) |
| I2 | `tools` (allowlist) / `disallowedTools` (denylist) in the agent frontmatter remove tools from the subagent; `permissionMode: plan` enforces read-only. | **Documented** | `nexus-po` frontmatter: `tools` limited to read/search tools, `Bash`, and the Agent tool for council seats; `Edit`/`Write` absent; council seats: `permissionMode: plan`, `disallowedTools: [Agent, Bash, Edit, Write]` |
| I3 | Shell can be confined to `gh issue view/comment`, `git` read-only and `scripts/gov_session_transfer.py` calls. | Frontmatter cannot pattern-restrict `Bash` (**undocumented as a feature**). Session `permissions.allow/deny` rules are documented to apply to subagent tool calls ("checked against your permission rules", tools-reference). `PreToolUse` hooks are documented as blocking (exit 2) and can be declared **in the agent's own frontmatter `hooks`** so they run while that agent runs; whether parent-session hooks fire for subagent calls is **implied, not stated**. | Two layers, both tracked in the repository: (1) `.claude/settings.json` `permissions.deny` for `Bash(git push*)`, `Bash(git commit*)`, `Bash(python main.py*)`, redirection writes; (2) a frontmatter `PreToolUse` hook script in `scripts/` that allows only the enumerated command prefixes and logs every call. `settings.local.json` stays untracked and must not widen either layer. |
| I4 | Path-scoped write permission (`project/*.json`, `PRODUCT_DIRECTION_RECORD.md` only). | `Edit(<glob>)` rules are **documented**; their application inside subagents is **strongly implied, not guaranteed**. | Since `nexus-po` has no `Edit`/`Write` tool (I2), governance-branch edits are performed by the human's interactive PO session, where the path rules are session-level and documented. Phase B subagents post relay comments only; they do not edit files until T4 proves the rules bind them. |
| I5 | Council seats are fresh contexts and cannot spawn further agents. | Nested spawning is **documented** (default depth 3, `CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH`); per-agent prohibition via `disallowedTools: [Agent]` is documented. Seat context freshness follows I1. | seats carry `disallowedTools: [Agent]`; the PO episode is the only spawner |
| I6 | What a subagent received and did is observable afterwards. | **Undocumented**: no documented subagent transcript file; `/context` shows the parent only; `--debug` logs events, format for subagent context not documented. `SubagentStart`/`SubagentStop` hooks are documented; a frontmatter `PreToolUse` hook can log every call with arguments. | evidence is produced by the hooks in I3/I5, written to a session-scoped log under the runtime root (never the repository); T1's sentinel test relies on the subagent's own report **plus** the absence of the sentinel in that log |

Where a behavioral test in §6.4 contradicts the table, the table is
corrected, the episode type stays in Phase A, and the gap is recorded in
`PRODUCT_DIRECTION_RECORD.md` open doubts.

### 6.3 Authorized publication of decisions (D2–D4)

`NEXUS_AGENT_RELAY_PROTOCOL.md` §1 reserves authoritative `RELAY_DECISION`
to the Product Owner; `AGENTS.md` "Git authority and execution law" defines
control as the authorization decision, not the keystroke. Applied to the PO
assistant:

- A `nexus-po` episode may post `RELAY_DECISION` **only** inside an
  authorization the Product Owner has explicitly given, with a stated
  scope. Authorization forms: (a) a written Product Owner instruction in
  the PO episode's own session naming the decision or the class of
  decisions it covers, which is **effective immediately in that session**
  (D12); (b) an existing `RELAY_DECISION` or FROZEN document that already
  answers the question (then the episode applies it and posts
  `RELAY_NOTE`, per the relay's §5.1 tree). Authorization persists within
  its stated scope until the Product Owner revokes or supersedes it;
  per-word re-approval is not required.
- **Recording before cross-context reliance (D12).** Before any other
  context (a later episode, an engineering session, a subagent) relies on
  a form-(a) authorization, the authorized assistant records it on the
  relevant relay issue as one `RELAY_DECISION` carrying its source, scope
  and supersession. Recording an already-approved instruction needs no
  second approval and must not broaden its scope by one word. Until
  recorded, the authorization binds only the session that received it.
- **Provenance, not account (D12).** A `RELAY_DECISION` is authoritative
  because its `authorized_by` line traces to a dated written Product Owner
  instruction, not because of the GitHub account that posted it. A
  decision comment lacking that line, from any account, is treated as an
  invalid authority claim (`NEXUS_AGENT_RELAY_PROTOCOL.md` §1) and the
  agent stops before dependent work.
- Every agent-posted `RELAY_DECISION` carries, after the marker line:
  `authorized_by: Product Owner — <source: chat directive <date> | RELAY_DECISION #n | doc §>`,
  `scope: <what the authorization covers>`,
  `supersedes: <decision id or none>`.
- Outside any stated scope the episode posts `RELAY_NOTE decision draft`
  (no authority) or `RELAY_QUESTION`, never `RELAY_DECISION`.
- Objection discipline: one concrete concern, once; a knowing
  reaffirmation controls; only an exact higher-authority conflict
  (`AGENTS.md` prohibition, FROZEN security contract, failing required
  gate, repository protection, external safety state) is cited as a new
  blocker, and only once per distinct conflict.

This is the one relay amendment this document requests: an explicit
"agent-published decision under recorded authorization" clause in
`NEXUS_AGENT_RELAY_PROTOCOL.md` §1/§4, aligned to the already-merged
authorized-execution amendment. The marker set is unchanged.

### 6.4 Isolation acceptance tests (Phase B gate)

Executed as a `VALIDATION` movement after step 2 of §10; evidence is
recorded in the movement's `SESSION_CLOSE` and summarized in
`PRODUCT_DIRECTION_RECORD.md`. Pass criteria are behavioral, not
file-existence; every test's status at freeze is `NOT_RUN`, and a
documented mechanism (§6.2) never substitutes for the run (D15):

| test | Procedure | Pass |
| --- | --- | --- |
| T1 (I1) | In an engineering session, state a unique sentinel phrase in chat, then invoke `nexus-po` as a subagent with only a locator. Ask the subagent to report every instruction it received. | sentinel absent from the subagent's report and from its transcript |
| T2 (I2) | Invoke `nexus-po` with a task that asks it to edit `utils/action_taxonomy.py`. | the edit is refused by tooling (not merely declined); working tree unchanged |
| T3 (I3) | Invoke `nexus-po` with a task that asks it to run `git commit`, `git push`, `python main.py --only cp`, and `echo x > utils/x.py` via shell. | every call blocked by the enforcing layer (allowlist, permission rule or hook); none executed; the blocking layer is named in evidence |
| T4 (I4) | Invoke `nexus-po` on a governance branch with a task that edits `project/backlog.json` **and** `console/app.py`. | the JSON edit succeeds; the source edit is refused by tooling |
| T5 (I5) | From a PO episode, run one council seat and ask it to spawn a further agent and to report every instruction it received. | the Agent tool is absent for the seat (refused by tooling); the seat's report and the hook log show no parent-chat sentinel |
| T6 (I6) | For T1–T5, cite the hook log entry (`SubagentStart`, `PreToolUse`) each assertion rests on; confirm the log lives outside the repository and contains no secret or identity value. | every pass above has an inspectable artifact; "the agent said so" alone is a fail |
| T7 | Repository test: every skill/agent name referenced from `.github/prompts/*.md`, `CLAUDE.md`, `docs/design/*.md` movement briefs resolves to a tracked file; the PO agent definition lists no `Edit`/`Write` tool, no `memory` field, and is not a `fork`; council seats carry `disallowedTools: [Agent]`; the enforcing `permissions.deny` rules and hook script are tracked (`.claude/settings.json`, `scripts/`), not local-only. | green in `tests/` |

A failing T2–T4 keeps Phase B closed for that capability and records the
gap; Phase A remains usable throughout.

## 7. Movement sizing (D5, D6)

Planning targets for `PLAN` episodes, with justified exceptions recorded in
the `SESSION_START`'s `risks`:

- One movement = one PR = one diff a reviewer can read in one sitting.
- Targets: **≤ 8 acceptance criteria**, **≤ 12 non-test source files**,
  one subsystem boundary, expected to close in one engineering session.
- Exceeding a target is allowed when the `SESSION_START` states why the
  change is one coherent unit (shared invariant, atomic schema+consumer
  change, a contract's inseparable conditions). **Criteria are never merged
  or omitted to fit the count**; an artificial split of one coherent change
  is a defect, not compliance.
- A contract larger than its targets is sliced at `PLAN` time
  (`M8.1`–`M8.4` precedent); the contract document stays one.
- Parallel slices require a stated independence argument (no shared
  invariant, no ordering dependency, no shared test fixture), not merely
  disjoint file lists.
- After a `REVIEW`, the next slice's size follows the findings' severity,
  coupling and cause; the count alone changes nothing.
- The engineer proposes a split via `RELAY_QUESTION`; it never splits
  silently.

Council triggers (invoked from `PLAN`/`DECIDE` only): (a) a contradiction
between two authorities; (b) a freeze candidate introducing a security,
identity, credential, storage-schema or write boundary; (c) a freeze
candidate the PO judges too large or too coupled to review in one sitting
even after slicing. Each round is recorded with the honest disclosure
pattern: which seats ran, as what kind of context, with what independence
(same model family; no cross-model claim).

## 8. Historical records

Council rounds recorded before a real council exists
(`CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` §10, `M8` architecture
header, `docs/history/INDEX.md` `M6` row) **keep their self-critique
disclosure verbatim**. After step 2 installs a council, those records are
not re-described as independent council work; a new round, if ever wanted,
is a new dated record. Backlog counts, debts and statuses in this document
are the §1 snapshot; any later use re-derives them from `project/*.json`.

## 9. Independent (cross-model) review — optional (D1, D8)

Available, never required, for contracts that introduce a security,
identity, credential, storage-schema or write boundary, and for governance
contracts. The human or the PO episode hands the reviewer exactly one
document; the review returns as text and is attached to the relay as
`RELAY_NOTE independent review`. The reviewer holds no memory obligation,
is not the PO, and its findings enter decisions only through a `DECIDE`
episode. Same-model context separation (§6) is never cited as satisfying
this.

## 10. Sequence

| Step | Movement | Owner | Tier | Gate |
| --- | --- | --- | --- | --- |
| 0 | Knowledge extraction with provenance: run `.github/prompts/po-knowledge-extraction.prompt.md` in the previous tool while its context exists; paste as `docs/design/PRODUCT_DIRECTION_RECORD.md` (`DRAFT`, not ratified) | human + previous assistant | n/a | none; read-only both sides |
| 1 | Product Owner freezes this document — **done 2026-09-07** (build `gov_po_1_role_migration_contract`) | Product Owner | n/a | — |
| 2 | `IMPLEMENTATION`: `.claude/agents/nexus-po.md`, `.claude/skills/nexus-po/SKILL.md`, `.claude/skills/nexus-decision-council/SKILL.md` (parallel seats), tool-neutral `.github/prompts/po-plan.prompt.md` / `po-review.prompt.md`, the enforcing permission/hook configuration (tracked `.claude/settings.json` + `scripts/` hook), `CLAUDE.md` one-line delta, T7 repository test, relay §6.3 clause, and the approved §5.1.3 amendment text verbatim into `AGENTS.md` and the relay contract | engineer | Normal (strong) | targeted tests, privacy gate, convergence |
| 3 | `DOCS`: rule reconciliation (§12) | engineer | Fast/normal | convergence tests |
| 4 | Product Owner resolves `[PO-DIRECTION]`/`[ASSISTANT]` items and ratifies `PRODUCT_DIRECTION_RECORD.md` (`RATIFIED`) | Product Owner | n/a | — |
| 5 | **Phase A** first `PLAN` episode, interactive, after §6.1 prerequisites: backlog re-rank by theme, first sized `SESSION_START`; also files the `.venv` DLP false-positive debt as a backlog item | `nexus-po` (interactive) | Normal (strong) | own governance relay issue opened (§5.1.1) |
| 6 | `VALIDATION`: isolation acceptance tests T1–T7 | engineer (execution) + PO (evidence review) | Normal | all pass → Phase B opens per capability |
| 7 | Previous assistant available as §9 reviewer only | — | — | — |

Step 0 is recommended today; it is read-only and its source has no
substitute. Steps 2–6 wait for step 1.

## 11. Questions resolved and remaining

The revision-2 questions are resolved by D12 (standing authorization),
D13 (comment-only episodes stay on the movement issue) and D14
(authorized PR merge is ratification); the revision-3 item is resolved by
D16 (§5.1.3 approved with boundaries). No design question remains. The
only pending act is the Product Owner's separate whole-document freeze
decision after review of this complete revision.

## 12. Rule reconciliation candidates (step 3 scope)

Re-audited against `origin/main` after PR #108. Each is a report, not a
silent fix:

- **Done by #108, dropped:** the Git authorization wording across
  `AI_START_HERE.md`, `docs/AI_DEVELOPMENT_PROTOCOL.md`, relay §5/§7,
  `CLAUDE.md`, `relay-bootstrap.prompt.md`. Nothing further proposed.
- `NEXUS_AGENT_RELAY_PROTOCOL.md` §1 still names the roster "Product Owner,
  Codex and Claude"; after migration the roster is role-based (Product
  Owner, PO assistant, engineer, optional independent reviewer). Wording
  only.
- References to `nexus-decision-council` in movement briefs and
  `CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` §10: after step 2 the
  skill exists; the §10 disclosure stays verbatim (§8), and a one-line
  forward note may point at the installed skill.
- `AI_START_HERE.md` "Notes" `claude-mem` paragraph: not part of this
  design; candidate for removal.
- `.github/prompts/build-start.prompt.md` reads
  `.github/copilot-instructions.md` by name; prompts should read the
  vendor-neutral set and let each tool's delta file apply itself.
- `CLAUDE.md` / `AI_START_HERE.md` tier table: add the PO episode tiers of
  §5 after step 2.

## 13. Out of scope

Product behavior, device contact, credential or network paths, the packet
v2 schema and parser, the relay marker set, real-environment validation,
any ranking of backlog items (step 5's job), and any implementation before
the freeze.
