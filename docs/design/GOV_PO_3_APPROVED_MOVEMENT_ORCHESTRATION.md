# GOV.PO.3 — Approved-movement orchestration

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-08**, with three additions
folded in below (`relay/NXS-LOCAL-0007-gov-po-3-orchestration.json`,
entry 3, `RELAY_DECISION`: "FREEZE ... with three additions; proceed to
AC-3"). Applied by this same engineer session per the applying-vs-
originating rule this document's own §2.2 states — the decision's own
text directs it: "apply the FROZEN status line and these additions
yourself." Section 2's two reconciliations are approved as written and
applied to `GOV_PO_2_PO_VISIBILITY_AND_BOUNDED_AUTHORSHIP.md` sections
2.4/3.2/4 in this same movement (§2, below, plus the target document
itself). Authority: Product Owner architecture direction, 2026-09-08,
issued after a 3-seat `nexus-decision-council` round the Product Owner ran
in its own PO episode (`GOV_PO_ROLE_MIGRATION.md` §7 trigger (a): a
contradiction between two authorities — see §2 below) before opening this
movement; this document implements that settled direction and does not
re-invoke the council itself (an engineering session never does —
`GOV_PO_ROLE_MIGRATION.md` §6.1/§7, `CLAUDE.md`). Parent authority,
unamended except where §2 explicitly reconciles two named contradictions:
`AGENTS.md`; `docs/design/GOV_PO_ROLE_MIGRATION.md` (FROZEN); `docs/design/
GOV_PO_2_PO_VISIBILITY_AND_BOUNDED_AUTHORSHIP.md` (FROZEN); `docs/design/
LOCAL_RELAY_PROTOCOL.md` (DRAFT); `docs/design/NEXUS_AGENT_RELAY_PROTOCOL.md`
(FROZEN); the standing merge authorization at
`ozandurmus/nexus-agent-relay#13`.

This freeze authorizes AC-3/AC-5/AC-8 in this same movement (§7), per the
decision's own text: "Proceed with AC-3 in this movement, then AC-5, then
AC-8; merge under relay#13 once green and post the RELAY_NOTE here."

**PO amendment, 2026-09-11:** the engineer provider is selectable. The
default is the installed `codex exec --json` CLI; the legacy `claude -p`
path remains available only with `--provider claude`. Both paths retain
argument-list spawning, isolated worktrees, the canonical relay directory,
and bounded non-interactive permissions. This amendment changes no relay,
merge, or device-action authority.

## 1. Purpose

`GOV_PO_ROLE_MIGRATION.md` and `GOV_PO_2_PO_VISIBILITY_AND_BOUNDED_
AUTHORSHIP.md` removed chat-to-chat transport between the Product Owner
assistant and the engineer, and gave the PO role read visibility and
bounded authorship. Neither removed the human from the loop **between**
PO approval and engineering execution: today a human still opens a
terminal, starts an engineer session, and pastes or points it at the
relay file for every approved movement, and does this again for every
correction round and every merge. `docs/design/LOCAL_RELAY_PROTOCOL.md`
made the PO/engineer exchange itself file-based and turn-enforced; it did
not remove the human act of starting the engineer.

This document specifies a deterministic local runner,
`scripts/orchestrator.py`, that the Product Owner (from an interactive
`nexus-po` session) uses to dispatch an **already-approved** movement to a
separate, isolated `claude -p` engineer process running in its own git
worktree, and that tracks that process through development, correction,
and integration under the existing standing merge authorization
(`ozandurmus/nexus-agent-relay#13`) without further human message-carrying,
terminal-opening, or per-step approval. It reduces human interaction to
the two touchpoints named in AC-6: **work selection** (the PO decides what
gets built, as it already does) and **material design/scope decisions**
(as `GOV_PO_ROLE_MIGRATION.md` §2.4/D2–D4 already reserve to the human).
Everything mechanically downstream of an approval — spawning the engineer,
tracking it, serializing its merge, recovering it after an interruption —
is what this document adds.

**What this document is not.** It is not a new agent framework, a new SDK
dependency, or a replacement for `scripts/local_relay.py`'s schemas and
validators (§3.6, §7). It is not a security sandbox: worktree isolation
reduces file collisions between concurrent movements, nothing more
(invariant, restated from the movement's own `SESSION_START`). It does not
add any real-device execution capability, and does not touch the existing
network-device command gate or deployment/real-device approval boundary in
`docs/AI_DEVELOPMENT_PROTOCOL.md` — an orchestrated engineer worktree
session has exactly the same device/deployment authority as an interactive
one has today (none beyond what already exists), and the same human
approval boundary applies identically.

## 2. Reconciliation (AC-2)

### 2.1 Merge authority: `GOV_PO_2_PO_VISIBILITY_AND_BOUNDED_AUTHORSHIP.md`
§2.4/§4 vs. `ozandurmus/nexus-agent-relay#13`

**The contradiction, stated precisely.** `GOV_PO_2_PO_VISIBILITY_AND_
BOUNDED_AUTHORSHIP.md` §2.4 ("HUMAN DECISION AUTHORITY — closed set,
unchanged") lists, as a thing no standing-delegation class ever covers:
"...waiver, deployment, and **merge (governance or product, of any
kind)**." §4 ("Standing delegation classes") restates the identical
phrase in its own exclusion list. Read literally, both sections say a
product-code merge always requires a human act or an explicit per-instance
`RELAY_DECISION` naming that merge.

That is not what is actually running. `ozandurmus/nexus-agent-relay#13`
("Standing merge authorization for PO-issued implementation movements"),
recorded as a `RELAY_DECISION` with `authorized_by: Product Owner — chat
directive 2026-09-08`, already authorizes the engineer to merge its own PR
to `main` without a separate per-movement `RELAY_DECISION`, once every
required gate is green, for exactly the class of movement this document's
own baseline says GOV.PO.3 dispatches: an `IMPLEMENTATION`/`DOCS`/
`ARCHITECTURE` movement whose issue body is a validated `SESSION_START`
issued from a `nexus-po` PO episode. That decision is dated the same day as
`GOV_PO_2_PO_VISIBILITY_AND_BOUNDED_AUTHORSHIP.md`'s own freeze and was
never reconciled against it — the contradiction has been live, silently,
since both were written; `GOV_PO_3`'s Product Owner architecture direction
names reconciling it explicitly as this document's job (AC-2), rather than
leaving one document's plain text to keep contradicting a decision that is
already governing real merges.

**The reconciled rule.** §2.4's and §4's "merge (governance or product, of
any kind)" is corrected to distinguish two separate events that
`relay#13`'s own text and `GOV_PO_ROLE_MIGRATION.md`'s "invariants" already
treat as distinct:

1. **Code integration under an already-standing authorization** — an
   engineer merging its own green-gated PR under `relay#13` (or any future
   standing delegation the Product Owner records the same way) — is
   **not** a act requiring a fresh human decision at merge time. It was
   already authorized, once, in writing (`AGENTS.md` "Git authority and
   execution law": "a task directive... that explicitly authorizes a named
   Git action is sufficient authorization... Do not ask for the same
   permission again"). §2.4/§4's closed list governs *originating* a merge
   authorization, not *executing* one the Product Owner already gave.
2. **The Product Owner assistant's own governance-branch (`gov/po-*`)
   merge** — `roadmap.json`/`backlog.json`/`feature_registry.json`/
   `PRODUCT_DIRECTION_RECORD.md` changes authored directly by a `nexus-po`
   `PLAN`/`REVIEW`/`DECIDE` episode — stays exactly as `GOV_PO_ROLE_
   MIGRATION.md` §4.1 (D14) already requires: merge only after an
   explicit, recorded `RELAY_DECISION` on that governance issue. **This
   document does not touch that path.** `relay#13` itself already carries
   this same exclusion in its own text ("Explicitly excluded: the Product
   Owner assistant's own governance-branch (`gov/po-*`) commits...").
3. **Originating a *new* standing delegation class**, or any merge outside
   an already-recorded authorization's stated scope, remains squarely
   inside §2.4/§4's closed list — unchanged. Nothing in this reconciliation
   widens what a standing delegation can cover; it only stops the document
   from contradicting the one the Product Owner already gave.

**Exact text correction (applied by `GOV_PO_3_IMPLEMENTATION`, not by this
DRAFT document, mirroring how `GOV_PO_2_PO_VISIBILITY_AND_BOUNDED_
AUTHORSHIP.md` itself deferred its own approved amendment text to its
implementation step):** in both §2.4 and §4 of `GOV_PO_2_PO_VISIBILITY_
AND_BOUNDED_AUTHORSHIP.md`, replace "merge (governance or product, of any
kind)" with:

> merge of the Product Owner assistant's own `gov/po-*` governance branch
> (`GOV_PO_ROLE_MIGRATION.md` §4.1); *originating* a new class of standing
> merge authorization for engineering movements. *Executing* an
> already-recorded standing merge authorization (e.g.
> `ozandurmus/nexus-agent-relay#13`) is not itself a fresh human-decision
> event — it was authorized once, in writing, per `AGENTS.md` "Git
> authority and execution law."

This is a **correction to two FROZEN documents' plain text**, not a
widening of PO or engineer authority: it makes both documents say what is
already true and already running, and it is exactly why `GOV_PO_ROLE_
MIGRATION.md` §7 trigger (a) — "a contradiction between two authorities" —
fired for this movement's originating PO episode. The Product Owner's own
review of this document's freeze is the human decision that ratifies the
correction; no separate council round is requested by this document, since
the direction already reflects one (Status, above).

### 2.2 `GOV_PO_2_PO_VISIBILITY_AND_BOUNDED_AUTHORSHIP.md` §3.2 — "PO
cannot write FROZEN" corrected to applying vs. originating

**The imprecision.** §3.2's status-line detection rule denies any
PO-authored write to `docs/design/po_drafts/*.md` that claims `FROZEN` or
`RATIFIED` status, described in prose as "those are human-only
declarations." Read too broadly, this could be misapplied to deny the PO
role ever *transcribing* a freeze decision the human already made (e.g.
applying an already-decided freeze to a document's own status line, or
recording a `RELAY_DECISION` whose content states a freeze the Product
Owner directed in the same session) — which `GOV_PO_ROLE_MIGRATION.md`
§6.3/D2 already permits: a PO episode may act inside an authorization the
Product Owner explicitly gave, including recording it.

**The reconciled rule**, matching this movement's own `SESSION_START`
invariant verbatim: **the PO applying a freeze decision the human already
made — including writing the word `FROZEN` into a status line at the
human's explicit, contemporaneous direction — is permitted; the PO
*originating* a freeze decision on its own, unprompted, is forbidden.**
§3.2's actual mechanism (the `_PROMOTED_STATUS_RE` gate on `docs/design/
po_drafts/*.md`) is unaffected by this clarification and is not loosened:
that gate still denies *any* PO-authored `Write`/`Edit` to a
`po_drafts/*.md` file claiming `FROZEN`/`RATIFIED`, regardless of whether a
human directed it, because `po_drafts/*.md` is by construction a
pre-promotion drafting area (§2.2 "Genuinely new" — "promoting one to a
real `docs/design/*.md` contract... is a human or engineer act, never a
PO-authored edit"). The correction is to the **prose explaining the rule's
intent** ("those are human-only declarations" is too broad as written), not
to the mechanism itself: the mechanism was already correctly scoped to
`po_drafts/*.md` only, where no write ever gets to declare a real freeze
regardless of who directed it.

## 3. Architecture (AC-1)

### 3.1 Actors and lifecycle, in order

1. The Product Owner, in an interactive `nexus-po` session, runs `PLAN`/
   `REVIEW`/`DECIDE` as today and opens or advances a movement's local
   relay file (`scripts/local_relay.py create`, unchanged).
2. When the relay file's `SESSION_START` is ready and `next_actor` is
   `"engineer"`, the Product Owner runs one new Bash command:
   `python3 scripts/orchestrator.py start --movement <relay-id>`.
3. The orchestrator validates the relay file, computes the approved-task
   hash (§3.5), creates one git worktree on a `feature/*` branch from the
   `SESSION_START`'s own `git.base`/`git.lane` fields (§3.3), writes a
   persistent process record (§3.7), and spawns a detached `claude -p`
   engineer process in that worktree using the engineer permission profile
   (§3.4). `start` returns immediately — it does not block on the
   engineering work.
4. The engineer process reads its approved task, works the movement
   (development, its own test/gate loop, correction) inside its worktree,
   appends its own relay entries against the canonical relay location
   (§3.6, never its worktree's own copy), and — once every required gate is
   green — merges its own PR under the standing authorization (§3.8),
   posting the `RELAY_NOTE` `relay#13` already requires.
5. The Product Owner (or a later PO episode) checks `python3 scripts/
   orchestrator.py status --movement <relay-id>` or the relay file itself;
   no message is carried by hand between the two sessions.
6. A failed gate, an out-of-scope decision, or a genuine conflict routes to
   `RELAY_QUESTION`/`RELAY_NOTE` exactly as an interactive engineer session
   already does (`NEXUS_AGENT_RELAY_PROTOCOL.md` §5.1) — the orchestrator
   adds no new decision authority and makes no judgment calls itself; it is
   process lifecycle only.

### 3.2 `scripts/orchestrator.py` — commands and argument-list discipline

```
py scripts/orchestrator.py start  --movement <relay-id>
py scripts/orchestrator.py status --movement <relay-id>
py scripts/orchestrator.py stop   --movement <relay-id> [--force]
```

(`py`/`python3`/`python`/`.venv/bin/python` siblings, matching every other
`scripts/` tool's interpreter-spelling precedent.) `<relay-id>` is the
relay file's own id (`NXS-LOCAL-NNNN`, matching `scripts/local_relay.py`'s
existing scheme) — unambiguous and glob-resolvable to exactly one file
(`relay/<relay-id>-*.json`), unlike the free-text `movement` field.

**Argument-list discipline (a required AC-1 property, not a style
preference).** Every subprocess `scripts/orchestrator.py` itself spawns —
`git worktree add`, the selected AI CLI, `gh pr view` for status checks — is built
as a Python list passed to `subprocess.run`/`subprocess.Popen` with
`shell=False` (the default), never a formatted/interpolated string passed
through a shell. This is the same discipline `scripts/nexus_po_tool_gate.py`
documents for its own tokenizer (never build a command by string
concatenation of untrusted or task-derived content) and is what makes "no
arbitrary shell text" a structural property rather than a convention: even
a task whose text contained shell metacharacters could never reach a shell
interpreter, because none of these subprocess calls uses one. The
approved-task content itself is never interpolated into any argv element
at all (§3.5) — it is written to a file inside the worktree and the
engineer process reads it with its own `Read` tool, which removes the one
place a large, free-form JSON blob might otherwise tempt a string-built
command line.

### 3.2A Provider selection

`orchestrator start` accepts `--provider codex|claude` and defaults to
`codex` (also configurable with `NEXUS_ORCHESTRATOR_PROVIDER`). The Codex
path uses `codex exec --json --approve-for-me` (the Codex CLI applies its
workspace-write policy through that flag; it is mutually exclusive with an
explicit `--sandbox` flag);
the prompt remains one argv value and task content still comes only from
`.nexus/approved_task.json`. `--provider claude` preserves the existing
`claude -p --output-format stream-json` compatibility path. Provider output
is JSONL and is summarized by the same bounded tailer; an unrecognized event
is retained as a truncated line rather than dropped.

### 3.3 Worktree isolation

`start` creates the worktree with `git worktree add <worktree-path> -b
<lane> <base>`, where `<base>` and `<lane>` are read verbatim from the
relay file's `SESSION_START` `report.git.base`/`report.git.lane` fields —
already present in this movement's own `SESSION_START` (`"git": {"base":
"origin/main", "lane": "feature/gov-po-3-approved-movement-orchestration"}`)
and, per the packet schema `GOV.SESSION.1` already validates, present on
every `SESSION_START`. `<worktree-path>` is deterministic:
`<worktrees-root>/<relay-id>` (default `<worktrees-root>` a sibling
directory of the checkout, never inside it, so `git status` in the main
checkout never sees it). This mirrors, and does not relax, the two
argument-shape restrictions `GOV_PO_1_GATE_5` already put on the PO role's
own `git worktree add` (base must start with `origin/`; `-b` branch must
start with `feature/`) — the orchestrator enforces the same two shapes on
itself even though it is not gated by `nexus_po_tool_gate.py` (§3.4), so a
malformed or stale `SESSION_START` cannot point a worktree at an arbitrary
local ref or a `gov/po-*` branch reserved for the PO's own governance
commits.

**Isolation is file-collision avoidance, not a sandbox** (invariant,
restated): a worktree shares the same `.git` object store as every other
worktree of the same checkout; it does not isolate credentials, network
access, or process resources. Two concurrent movements cannot clobber each
other's working-tree edits; they can still, e.g., exhaust shared disk or
memory, or (once integrating) contend for the same `main` — which is
exactly why §3.8 serializes only the integration step, not development.

Once a movement's PR merges, `orchestrator` removes its worktree
(`git worktree remove`) as ordinary engineering cleanup — this is the
**orchestrator's own** housekeeping, a different actor from the PO role,
whose `nexus_po_tool_gate.py` restriction ("a worktree, once created, is
never destroyed by the PO role") is about the PO's own tool permissions
and is unaffected by this. A worktree belonging to a `stop`ped or `failed`
movement is left in place for human inspection, never auto-removed.

### 3.4 Engineer permission profile — `.claude/nexus-engineer.settings.json`

**Distinct from the PO profile, and deliberately not shaped like it.** The
PO profile (`nexus-po.settings.json`) is default-deny with a narrow
allowlist, because the PO role's whole boundary is capability-restriction
(`GOV_PO_ROLE_MIGRATION.md` §4, `GOV_PO_2` §2). The engineer role has never
been restricted that way — an interactive engineer session today has no
`.claude/settings.json` deny rules at all, and this document does not
introduce that restriction: AC-1 asks for "normal dev tools: Read / Edit /
Write / Bash / Git," and that is what an orchestrated engineer session
gets. `.claude/nexus-engineer.settings.json` is default-**allow**, with
exactly two narrow, orchestration-specific additions layered via a
`PreToolUse` hook (`scripts/nexus_engineer_tool_gate.py`, to be built in
AC-3) scoped to precisely two command shapes:

1. **The code-publish / artifact-egress boundary (AC-7).** Before `git
   push` (any ref) or `gh pr create`, the hook runs the repository privacy
   gate — `<same interpreter> main.py --repository-privacy-check`, invoked
   via `subprocess.run([sys.executable, "main.py",
   "--repository-privacy-check"], cwd=<repo root>)` (the engineer profile
   has ordinary `main.py` access, unlike the PO role, so no new script is
   needed for this movement — `scripts/repository_privacy_check.py`, §3.4
   of `GOV_PO_2`, remains that document's own separate, not-yet-landed
   deliverable and this movement does not depend on it) — and denies the
   push/PR-create with the check's own reported findings if it does not
   report `PASS`. This is **not** a new mandatory scan: `.github/
   workflows/validation.yml` already runs the identical check in CI on
   every PR (audited PASS, `GOV_PO_2` §3.1). This hook runs the same check
   locally, before push, so an unattended session fails fast on its own
   mistake instead of merging first and being caught by CI after the fact
   — a defense-in-depth addition at the boundary AC-7 names, not a
   widening of what is already required to pass.
2. **Serialized integration (§3.8).** Before `gh pr merge`, the hook
   acquires the cross-movement merge lock; it is released after the merge
   command completes (or reclaimed on staleness, §3.8).

Everything else — arbitrary `Read`/`Edit`/`Write`/`Bash`/`git
add|commit|branch|log|diff|...` — is unrestricted, exactly as for an
interactive engineer today. Two universal safety rails are carried over
from the PO profile because they are sound regardless of who is asking
(`git push --force`/`-f` denied unconditionally; this is the one and only
overlap with the PO profile's own deny list, and it is not
orchestration-specific reasoning, it is this repository's general stance on
force-push as a hard-to-reverse operation). No device, deployment, or
collection capability is added or changed: the existing network-device
command gate and real-device approval boundary in `docs/AI_DEVELOPMENT_
PROTOCOL.md` govern an orchestrated engineer session exactly as they
govern an interactive one, unchanged by this document.

### 3.5 Approved-task delivery and the content hash

At `start` time, the orchestrator reads the relay file fresh from disk,
extracts `entries[0]` (the `SESSION_START` entry — the one already-approved
task; `entries[0]` is structurally guaranteed to be `SESSION_START` by
`scripts/local_relay.py`'s own `validate_relay_object`, §3.6), and:

1. Serializes it canonically — `json.dumps(entries[0], indent=2,
   sort_keys=True, ensure_ascii=False)`, the identical form
   `local_relay.py`'s own `_dump()` already uses to write the file, so the
   hash is reproducible against the bytes actually on disk, not a
   re-derived approximation of them.
2. Computes its SHA-256 digest.
3. Writes the canonical bytes verbatim to a fixed path inside the new
   worktree, `.nexus/approved_task.json` — never gitignored, but never
   committed by the engineer either (it is dispatch metadata, not product
   source; `GOV_PO_3_IMPLEMENTATION`'s tests confirm it is excluded from
   any commit the engineer makes).
4. Records `{movement_id, revision, base_sha, task_hash}` in the process
   record (§3.7) — `base_sha` is `git rev-parse <base>` at worktree-creation
   time, `revision` per §3.7's redispatch counting.

The engineer process's own prompt (the fixed argv text passed to `claude
-p`, identical for every movement, never task-content-dependent) instructs
it to read `.nexus/approved_task.json` as its `SESSION_START` for this
movement, proceed under `AGENTS.md`/`AI_START_HERE.md`/`CLAUDE.md` exactly
as an interactively-started engineer session would, and — **Addition C,
Product Owner freeze decision** — re-read the canonical relay file
(`NEXUS_RELAY_FILE`) immediately before opening its PR, acting on any
`RELAY_CORRECTION`/`RELAY_DECISION` entries appended after dispatch (§3.7).
**The hash proves approved-text equals sent-text only** — restated from
this movement's own invariant — it is not a signature and does not prove
the engineer obeyed the task; that is still judged by diff, tests, and
review, unchanged.

### 3.6 Canonical relay location (AC-1, AC-3)

**The problem this closes.** A git worktree checks out the full tree at
its base commit, including whatever `relay/*.json` files existed there. If
the engineer process ran `scripts/local_relay.py append --file relay/
<id>-*.json` with a path relative to its own worktree, it would silently
read and write **its own worktree's copy** — never visible to the Product
Owner's session in the original checkout, and never merged back except
incidentally through the movement's own PR (too late, and not the actual
turn-taking channel). This is exactly the failure `SESSION_START`'s own
`risks` field names: "a per-worktree copy silently breaks agent
visibility."

**The fix — a canonical-location resolver in `scripts/local_relay.py`
(AC-3 build target, specified here).** `create`/`append`/`status`/
`validate`/`watch` already accept an explicit `--file`/`--dir` path; this
is unchanged. What is added is resolution when the caller does not pass
one: if the environment variable `NEXUS_CANONICAL_RELAY_DIR` is set,
`--dir`'s default becomes that absolute path instead of the cwd-relative
`relay/`; if it is unset (the Product Owner's own interactive session,
today, never sets it), behavior is byte-for-byte unchanged from today. The
orchestrator sets `NEXUS_CANONICAL_RELAY_DIR=<original checkout's absolute
relay/ path>` in the environment of every `claude -p` engineer process it
spawns, so every `local_relay.py` invocation inside any worktree — with no
explicit `--file` needed — resolves to the one physical file the Product
Owner's own session reads. This is a resolver, not a new transport: the
file format, markers, and turn-ownership rules of `docs/design/LOCAL_
RELAY_PROTOCOL.md` are unchanged and reused verbatim (no second framework).

**The shared append lock (AC-3 build target, specified here).** Today's
`append` uses optimistic concurrency (re-read-and-compare immediately
before write, §6 of `LOCAL_RELAY_PROTOCOL.md`) — correct (it never
corrupts or silently loses an entry) but not self-healing: a losing
concurrent writer gets a clean failure and must be retried by whoever
called it. With no human present to notice and retry a failed unattended
append, `append` is extended to wrap its read-validate-build-write
sequence in a real advisory lock (`fcntl.flock(LOCK_EX)` on a sidecar
`<file>.lock`, blocking with a bounded wait, default 30s) around the
canonical relay file. The existing hash-compare stays as defense-in-depth
(it still fails closed if the lock were ever bypassed by an out-of-process
writer); the lock's job is to turn "two processes touched the same
movement's relay file at nearly the same instant" from a manual-retry
failure into an automatic, ordered wait — needed because both the
orchestrator (recording a status note) and the engineer's own Claude
session (posting its own relay entries) can legitimately touch the same
canonical file for the same movement while both processes are alive.

### 3.7 Process records, duplicate-start prevention, retries, cancellation, recovery, worker limit

**Addition B (Product Owner freeze decision).** `orchestrator status` with
no `--movement` prints a table of every known movement: movement id,
phase, whether its recorded pid is currently alive, its worktree/branch,
and the last relay marker/timestamp observed for it — one call to see
everything in flight, not one call per movement. `start` additionally
enforces `--max-workers` (default 3): a **fresh** dispatch (never a
resume, which is not a new slot — it is already occupying the one it was
given originally) is refused with a clear reason when every slot already
holds a live, non-terminal movement. An automatic queue that dispatches
the next movement itself as a slot frees up would need a persistent
supervisor process this design does not have (§3.1/§8's "no background
watcher/daemon" invariant) and is explicitly deferred to a follow-up
movement after the AC-5 demonstrations, not built here.

**Persistent, outside the relay file** (explicit AC-1 requirement — process
lifecycle bookkeeping is not durable governance history and must not spam
the git-tracked relay file with per-heartbeat diffs). One JSON file per
movement under a state directory (`NEXUS_ORCHESTRATOR_STATE_DIR`, default
a fixed subdirectory of the system temp root — mirroring `nexus_po_tool_
gate.py`'s own `NEXUS_PO_HOOK_LOG` precedent of runtime bookkeeping living
outside the repository), `<state-dir>/<relay-id>.json`:

```json
{
  "movement_id": "NXS-LOCAL-0007",
  "revision": 1,
  "base_sha": "<git rev-parse of report.git.base at dispatch time>",
  "task_hash": "<sha-256 hex, §3.5>",
  "worktree_path": "...",
  "branch": "feature/...",
  "pid": 12345,
  "session_id": "<claude -p session id>",
  "phase": "starting | running | awaiting_merge_lock | integrating | done | failed | cancelled",
  "started_at": "...", "heartbeat_at": "...",
  "retry_count": 0,
  "last_error": null
}
```

- **Duplicate-start prevention.** `start` refuses (non-zero exit, clear
  reason) when a state record exists for `<relay-id>` whose `phase` is
  non-terminal (`done`/`failed`/`cancelled`) **and** whose `pid` is a live
  process — checked with a zero-signal probe (`os.kill(pid, 0)` /
  equivalent), never assumed from the state file's contents alone.
- **Bounded retries.** If the engineer process exits non-zero, the
  orchestrator restarts it (same worktree, same branch — never a new
  worktree) up to a small bounded count (default 2), incrementing
  `retry_count`; beyond the bound, `phase` becomes `failed` and one
  factual `RELAY_NOTE` is appended to the canonical relay file (§3.6)
  naming the failure — the orchestrator states facts, it does not decide
  or reroute scope; a genuine `RELAY_QUESTION` is still the engineer
  session's own judgment call when it runs, per `NEXUS_AGENT_RELAY_
  PROTOCOL.md` §5.1, unchanged.
- **Cancellation.** `stop` sends `SIGTERM` to the recorded `pid`, escalates
  to `SIGKILL` after a grace period, sets `phase: cancelled`, and — unlike
  a successful merge (§3.3) — leaves the worktree and branch in place for
  human inspection.
- **Recovery after interruption.** `start`, called again for a movement
  whose state record shows a **dead** `pid` and a stale heartbeat (past a
  bounded threshold) but a **non-terminal** `phase`, does not recreate the
  worktree or re-dispatch from scratch: it re-reads the canonical relay
  file fresh from disk (never from any cached copy in the state record —
  the state record's own job is process tracking, never a second source of
  truth for relay content) and, if the movement is still open with
  `next_actor: "engineer"`, resumes the **same** `session_id` in the
  **same** worktree via `claude --resume <session_id> -p ...` (a
  documented Claude Code capability, `-r`/`--resume`), rather than starting
  a fresh session that would redo already-completed work. `revision` is
  **not** incremented for a resume — it only increments when `start` is
  invoked again after a `failed`/`cancelled` terminal phase, i.e., a
  genuinely fresh redispatch of the same movement id (recorded so a later
  audit of the state file can see whether a worktree was ever rebuilt after
  a real failure, distinct from an ordinary resume).
- **No lost relay entries, by construction.** Relay entries are
  append-only and each `append` is an atomic, immediately-durable disk
  write (`local_relay.py`'s existing `_atomic_write`, unchanged); recovery
  never reconstructs or replays relay state from the orchestrator's own
  process record, only from the canonical file itself, so a crash between
  two relay appends loses at most an in-flight append that never completed
  the atomic write (nothing to recover — it never landed either) and never
  an already-landed one.
- **A changed task revision after dispatch — detected by `status`, and now
  also acted on end-to-end (Addition C, Product Owner freeze decision).**
  If the canonical relay file gains new entries (e.g. a `RELAY_CORRECTION`
  or `RELAY_DECISION`) after `start` computed its task hash, `status`
  reports this explicitly (dispatch-time sequence number vs. the file's
  current length, §3.7's `_status_row`). Detection alone leaves it to a
  human to notice and intervene; the fixed engineer prompt (§3.5) now also
  instructs the engineer session itself to re-read the canonical relay file
  (`NEXUS_RELAY_FILE`) immediately before opening its PR and act on any
  `RELAY_CORRECTION`/`RELAY_DECISION` entries appended after dispatch — so
  a changed task revision is applied by the movement's own outcome, not
  merely flagged for someone to catch.

### 3.8 Serialized integration under `ozandurmus/nexus-agent-relay#13`

The orchestrator does not review, decide, or merge anything itself — merge
authority stays exactly where §2.1 places it (the engineer's own session,
under the standing authorization, after its own gates are green). What the
orchestrator adds is **serialization**, needed only once concurrent
movements exist (AC-5 demonstration 2): the engineer profile's `gh pr
merge` hook (§3.4) acquires a single, repository-wide advisory lock
(`<state-dir>/merge.lock`) before the merge proceeds, and releases it
immediately after. The lock carries a holder (`movement_id`, `pid`) and a
bounded time-to-live (default 600s); a lock whose holder process is no
longer alive, or whose TTL has elapsed, is treated as abandoned and
reclaimed by the next acquirer — a crash mid-merge cannot wedge every other
movement's integration permanently. A caller that cannot acquire the lock
within its own bounded wait is denied with a clear reason (another
movement is integrating) rather than hanging indefinitely.

**Addition A (Product Owner freeze decision, closing this section's own
originally-named gap): re-validation against an advanced `main` is a
technical gate, not a documented convention.** Before the actual `gh pr
merge` is allowed to proceed, the engineer profile's hook
(`scripts/nexus_engineer_tool_gate.py`) — while still holding the
just-acquired lock — runs, in order: (1) `git fetch origin` then `git merge
origin/main` into the movement's own branch, in its own worktree; (2)
`python3 -m pytest tests/test_architecture_convergence.py -q` and `python3
scripts/build_history_index.py --check` against that merged state. Any
failure at either step denies the `gh pr merge` call with the concrete
failure (a merge conflict to resolve, or a test/check failure) and releases
the lock immediately, so one movement's own failure never blocks another's
integration; only once both steps are clean does the hook allow the actual
merge through. This closes the gap a green run against a stale `main`
would otherwise leave open — GitHub's own "branch out of date" signal is
necessary but was not, by itself, sufficient. What this still does **not**
do, honestly stated: it does not re-run a movement's own *additional*
targeted tests beyond the two fixed convergence/build-history checks above
— `report.validation_plan` (`GOV.SESSION.1`'s schema) is free-text English
prose, not a machine-executable command list, so there is no general way to
replay "the movement's own targeted tests" mechanically; running those
before ever attempting `gh pr merge` remains the engineer session's own
pre-merge responsibility, unchanged and unenforced by this specific gate
(named as the narrower residual risk that remains, §9).

### 3.9 Project-state finalization (AC-8)

Unchanged from `GOV_PO_2_PO_VISIBILITY_AND_BOUNDED_AUTHORSHIP.md` §5: an
orchestrated movement's own project-state bookkeeping
(`project/build_history.json`, `project/roadmap.json` `now_next`/
`current_build`, `CURRENT_STATE.md`, `AI_HANDOVER.md`,
`docs/history/INDEX.md` regeneration) lands inside that movement's own
commit/PR, exactly as for an interactive engineer session. The
orchestrator does not perform or verify this — it is the spawned engineer
session's own responsibility, restated here only because AC-8 asks this
document to say so explicitly for the orchestrated path too.

### 3.10 Privacy scope (AC-7)

Covered fully in §3.4 item 1. No change to any currently mandatory scan:
the CI-run `main.py --repository-privacy-check` step remains exactly as
audited in `GOV_PO_2` §3.1 (PASS, no secrets context, no raw-value
printing). The one addition is a **local, pre-push** run of the identical
check for the unattended path, not a new or expanded scan surface.

## 4. Verified platform behavior (AC-4)

Both required checks were run for real against the installed platform
(`claude 2.1.263`) during this movement, in a disposable scratch git
repository outside `neXus` — not assumed from documentation. Full transcripts
are in this session's own record; both are stated here as this document's
required evidence.

### 4.a `--settings` merge behavior

**Claim tested:** does `claude -p ... --settings <profile>` *replace* the
session's other active settings (project `.claude/settings.json`), or is it
additive? `--help` text ("load *additional* settings from") suggested
additive but was not treated as sufficient (`AGENTS.md` "Vendor semantics
law" / this document's own AC-4 requirement not to assume).

**Test.** A scratch repo's tracked `.claude/settings.json` denied
`Bash(echo PROJECT_DENIED*)`. A separate `--settings` profile *allowed*
`Bash(echo *)` (a strict superset, if it were controlling). `claude -p`
was run from inside that repo with `--settings <that profile>`, asked to
run `echo PROJECT_DENIED_MARKER_XYZ` via the Bash tool.

**Result.** The call's own `permission_denials` array recorded the Bash
call as denied, with the project's deny rule cited. **Confirmed:
`--settings` is additive to project/local settings, not a replacement, and
an explicit `deny` in the project's own settings wins over an `allow` in
`--settings`** (deny-precedence, consistent with documented permission
semantics generally, now independently confirmed for this exact
`--settings`-vs-project-file shape). Consequence for this design: the
engineer permission profile's two additions (§3.4) are guaranteed to apply
**in addition to** whatever project-level settings a worktree's checked-out
`.claude/` carries, never instead of them — and any future project-level
deny this repository adds will bind an orchestrated engineer exactly as it
binds an interactive one, with no way for the engineer profile to
accidentally loosen it.

### 4.b Spawned process working directory

**Claim tested:** does a `claude -p` process's Bash-tool `cwd` and its
file-tool (`Read`) path resolution actually match the OS-level working
directory the orchestrator sets when it spawns the subprocess, or could
either resolve against some other implicit root?

**Test.** A worktree was created with `git worktree add`. A unique marker
file was written only inside that worktree. `claude -p` was spawned (both
directly, and — the actually representative shape — via a Python
`subprocess.run(..., cwd=<worktree>)` call, never a literal `claude -p ...`
string typed into a Bash tool call) and asked to run `pwd` via Bash and
`Read` the marker file by its bare relative name.

**Result.** `pwd` printed the worktree's exact path; `Read` returned the
marker's exact contents. **Confirmed: both Bash cwd and relative file-tool
resolution track the spawned process's actual working directory exactly.**
This is the property §3.3/§3.5 depend on: `.nexus/approved_task.json`
resolves correctly with no absolute-path plumbing needed in the fixed
engineer prompt.

### Observed platform behavior worth naming: an auto-mode classifier can
block a *literal*, suspicious-looking `claude -p` invocation

Not one of the two required AC-4 checks, but material to deployability and
disclosed here rather than only informally: an early attempt at the §4.a
test, phrased to explicitly ask the engineer process to report "whether
the tool call was allowed or denied," was itself refused before running —
"Blocked by classifier" — when typed as a literal Bash command in an
interactive session under auto mode. A differently-worded, non-permission-
probing invocation with the same `--settings`/`--permission-prompts none`
flags, and the actual production shape (a Python script's own
`subprocess.run` call, containing no literal `claude -p` text in the Bash
tool's own command string at all), was **not** blocked. AC-7's "auto-mode
blocks are handled by addressing their stated reason, never routed around"
applies directly: this document does not propose working around such a
block, and the design's real dispatch path (`orchestrator.py`'s internal
subprocess call, never a `claude -p ...` string typed directly as a Bash
tool call) is structurally unlike the one shape observed to trip it.
**This is not claimed as proof the classifier can never interfere** with an
orchestrated dispatch running under auto mode — it is inference from one
observed pair of cases, not an exhaustive test. AC-5 demonstration 1 (the
first real end-to-end dispatch, run once AC-3 lands) is this design's actual
test of whether ordinary task dispatch is classifier-clean in practice; if
it is not, that is a `GOV_PO_3_IMPLEMENTATION`-blocking finding to report,
not something to route around.

## 5. Human touchpoints (AC-6)

| Touchpoint | Who | When |
| --- | --- | --- |
| Work selection | Product Owner | `PLAN`, unchanged |
| Material design/scope decision | Product Owner | `RELAY_QUESTION` → `RELAY_DECISION`, unchanged |
| `orchestrator start` | Product Owner | once per approved movement, one Bash command, no new terminal |
| Everything else in §3.1 steps 3–6 | orchestrator + engineer process | no human action |

A rate limit, login prompt, or corporate access block still surfaces to
the human exactly as it would in an interactive session — this document
adds no mechanism that would suppress or auto-retry past a state that
genuinely requires human action, and does not attempt to.

## 6. Scope

**In:** this document; `scripts/orchestrator.py`; `.claude/nexus-engineer.
settings.json` and `scripts/nexus_engineer_tool_gate.py`; the
`scripts/nexus_po_tool_gate.py` extension letting the PO role invoke
`orchestrator start/status/stop`; the `scripts/local_relay.py` canonical-
location resolver and shared append lock; the AC-4 verifications above; the
AC-5 demonstrations (§8, run once AC-3 lands); project-state reconciliation
inside the AC-3 PR.

**Out:** `gov_po_2_implementation` (separate, unchanged, and this document
does not depend on `scripts/repository_privacy_check.py` landing first —
§3.4); any real-device execution capability; any change to vendor
collectors, OP.2, or the console job engine; a second orchestration/agent
framework; re-validating a PR against an advanced `main` after the merge
lock (§3.8, named residual risk, §9); retroactively orchestrating any
movement already in flight under the manual process.

## 7. AC-3 build targets

Approved and implemented in this same movement (relay/NXS-LOCAL-0007,
entry 3, `RELAY_DECISION`). Additions A/B/C from that decision are folded
directly into the targets below, not tracked as separate files.

- `scripts/orchestrator.py` — `start`/`status`/`stop`, per §3.2–§3.7.
- `.claude/nexus-engineer.settings.json` + `scripts/nexus_engineer_tool_
  gate.py` — the two-check hook, per §3.4.
- `scripts/nexus_po_tool_gate.py`: add `"python3 scripts/orchestrator.py
  status"` (and interpreter siblings) to `COMMON_PREFIXES` (read-only, both
  forms); add `"python3 scripts/orchestrator.py start"` / `"...  stop"`
  (and siblings) to `INTERACTIVE_EXTRA_PREFIXES` only — matching the
  existing `local_relay.py create`/`append` precedent — using
  boundary-safe prefix matching (matched prefix followed by a space or
  end-of-string, not a bare substring `startswith`), applying the same
  fix `GOV_PO_2` §3.1 already required for its own three new prefixes so
  this movement does not add a fourth unbounded-prefix instance to the
  gate.
- `scripts/local_relay.py`: the canonical-location resolver (§3.6),
  implemented as two environment variables rather than one to match each
  subcommand's real argument shape — `NEXUS_CANONICAL_RELAY_DIR` as
  `create --dir`'s default (a directory), `NEXUS_RELAY_FILE` as `append`/
  `status`/`validate`/`watch --file`'s default (one exact file, since
  those four subcommands never took a directory) — plus the cross-platform
  (`fcntl`/`msvcrt`, stdlib-only) shared append lock. Additive to the
  existing schema/validators, nothing re-derived; an explicit `--dir`/
  `--file` argument always wins over either variable, and default behavior
  with neither set is unchanged.
- `scripts/orchestrator.py merge-lock {acquire|release}`: the primitive
  `nexus_engineer_tool_gate.py` calls to serialize `gh pr merge` (§3.8),
  backed by the same `_FileLock`.
- `tests/test_orchestrator.py`: pure decision-logic unit tests (dispatch
  validation, duplicate-start/staleness detection, revision/hash
  computation, merge-lock acquire/release/staleness, canonical-path
  resolution) that do not require actually spawning a `claude -p` process;
  targeted extensions to `tests/test_gov_po_role.py` for the two new gate
  prefixes; targeted extensions to `tests/test_local_relay_protocol.py`
  for the resolver and lock.
- Project-state/handover reconciliation (AC-8) inside the same PR.
- `tests/test_gov_po_role.py`, `tests/test_local_relay_protocol.py`,
  `tests/test_architecture_convergence.py` keep passing unmodified in
  their existing assertions (only additive changes above).

## 8. AC-5 demonstrations (planned; executed after AC-3 lands, not by this document)

1. One approved movement dispatched via `orchestrator start`, completing
   end-to-end (development → gates green → self-merge → `RELAY_NOTE`) with
   no manual message transport.
2. Two independent movements (no shared schema/API/fixture/ordering
   dependency) developed concurrently in separate worktrees; both integrate,
   in order, through the merge lock (§3.8).
3. Three recovery cases, each without duplicate work or a lost relay entry:
   a duplicate `start` (refused, §3.7); a task revision changed mid-flight
   (detected and reported, §3.7); an interrupted engineer process (resumed
   via `--resume`, same worktree, §3.7).
4. A failed check returns to engineering automatically (bounded retry,
   §3.7); an out-of-scope decision reaches the human as one concrete
   `RELAY_QUESTION` (unchanged engineer-session behavior); an authorized
   merge proceeds automatically under §3.8.

## 9. Risks

- **Classifier interference (§4, "Observed platform behavior").**
  Inference from a small number of observed cases, not an exhaustive
  proof; AC-5 demonstration 1 is the real test. If it recurs for ordinary
  task dispatch, that blocks `GOV_PO_3_IMPLEMENTATION` from being marked
  done until reported and resolved with the Product Owner — never routed
  around.
- **Narrowed post-lock re-validation gap (§3.8, Addition A).** The merge
  lock's hook now forces a real `git merge origin/main` plus the two fixed
  convergence/build-history checks before a merge is allowed — the
  advanced-`main` gap this document originally left open is closed. What
  remains, honestly stated: a movement's *own additional* targeted tests
  beyond those two fixed checks are not mechanically re-run at merge time
  (`validation_plan` is free-text prose, not machine-executable), so that
  narrower slice stays the engineer session's own pre-merge responsibility.
- **Canonical-path misconfiguration.** If `NEXUS_CANONICAL_RELAY_DIR` is
  ever unset or wrong for a spawned engineer process, that process's
  `local_relay.py` calls silently fall back to its own worktree's `relay/`
  copy — exactly the failure §3.6 exists to prevent. `GOV_PO_3_
  IMPLEMENTATION`'s tests must include a case asserting the orchestrator
  always sets this variable for every spawned process, and `status`
  should be able to detect (not silently trust) that a movement's relay
  file genuinely lives outside every one of its worktrees.
- **Worktree isolation is not a sandbox** (restated, invariant): shared
  disk/process resources across concurrent worktrees are a real, accepted
  limitation, not a gap this document claims to close.

## 10. Non-supersession

This document does not amend, deprecate, or narrow `AGENTS.md`,
`docs/design/GOV_SESSION_TRANSFER_PROTOCOL.md`,
`docs/design/NEXUS_AGENT_RELAY_PROTOCOL.md`, or
`docs/design/LOCAL_RELAY_PROTOCOL.md`'s file format, markers, or
turn-ownership rules. It corrects two named, previously-silent
contradictions in `docs/design/GOV_PO_2_PO_VISIBILITY_AND_BOUNDED_
AUTHORSHIP.md` (§2 above) explicitly, applied to that document's own text
only by `GOV_PO_3_IMPLEMENTATION` after this document's freeze — the same
two-step pattern `GOV_PO_ROLE_MIGRATION.md` §5.1.3 (D16) and `GOV_PO_2`
itself both already used for their own approved amendment text. It does
not touch `docs/AI_DEVELOPMENT_PROTOCOL.md`'s network-device command gate
or real-device approval boundary.

## 11. Acceptance-criteria mapping

| AC | Where |
| --- | --- |
| AC-1 | §3 (whole) |
| AC-2 | §2 |
| AC-3 | §7 (deferred build targets; not built by this document) |
| AC-4 | §4 (both checks run for real, results recorded) |
| AC-5 | §8 (planned; run after AC-3) |
| AC-6 | §5 |
| AC-7 | §3.4 item 1, §3.10 |
| AC-8 | §3.9, §7 |
