# Workbench selected ordered work queue and admission

## Status

**FROZEN — HUMAN PRODUCT OWNER DIRECTION, 2026-09-16.**
The user requested implementation of this candidate and the reference's light
Material 3 design; after discussion, confirmed that consumption during an active
PO session is sufficient for this release. Closed-session continuation is a
future requirement, not part of this freeze. Implementation successor:
`workbench_selected_work_queue`, lane `codex/workbench-selected-queue-m3`.
The decisions in §2 apply with the bounded implementation clarifications in §5.
No live-job budget, Git delivery or host deployment is granted by this freeze.

## 1. Verified baseline and references

Inspected source baseline: `origin/main` and HEAD
`124d454f2bffa37904534654bbb1d3067fa8b505` (PR #417 delivery baseline).
`scripts/dashboard_assets/index.html` contains the movement table and
Active / Needs you / Archive buttons; `dashboard.js` uses one `/api/board`
snapshot and preserves movement details. This is the actual current-main source,
not the attachment's earlier-checkout assumption. Deployed browser revision is
**UNVERIFIED**; no deployment inspection was authorized. PR #418 remains a
separate delivery; this candidate neither validates nor changes its outcome.

| Existing boundary | Exact evidence and successor consequence |
| --- | --- |
| Work selection and Git authority | `docs/design/GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md` §§1, 2.1, 3.5; `scripts/orchestrator.py::task_hash`, `run_preflight`, `_do_start`: dispatch uses the canonical relay's first SESSION_START entry, hash, actual base SHA, movement ID and approved lane. Selection cannot supply replacement scope or authority. |
| Current Workbench | `docs/design/GOV_ORCH_4_WORKBENCH_OBSERVABILITY_TOKENS_AND_STUCK_DETECTION.md` §4 excludes additional writes. `docs/design/GOV_ORCH_4C_TABLE_FIRST_MATERIAL_3_WORKBENCH_CONTRACT.md` §§2–4 preserves table/navigation/authentication. The successor must explicitly extend only selected-queue writes; existing rules remain intact. |
| Planning model | `docs/design/GOV_ORCH_5_PROJECT_QUEUE_AND_COLD_START_DIET.md` §2; `scripts/project_queue.py::load_backlog_both`, `all_backlog_items`, `OPEN_STATUSES`, `render_queue_md`: use canonical active/terminal backlog references; agents read `project/QUEUE.md`. Backlog/roadmap edits still go through this tool. |
| Identity links | Canonical backlog `id` is an opaque source ID, distinct from relay `movement` / process `movement_id`, `revision`, `task_hash`, `base_sha`, and attempt. `project/QUEUE.md` has `workbench_borrow_from_agent_orchestration_tools`; no dedicated selected-queue backlog ID appears. That broad item is not this movement's authorization. Parent must assign an exact successor task/backlog mapping; none is invented here. |
| Admission today | `scripts/orchestrator.py::DEFAULT_MAX_WORKERS` is **3**, matching GOV.ORCH.3 §3.7. `_active_count` counts live nonterminal engineer PIDs in the supplied state directory; `decide_start` checks fresh dispatch but has resume exceptions. This is not a global atomic reservation mechanism. |
| Persistence today | `scripts/local_relay.py::_FileLock` uses bounded OS advisory locks with an unsupported-platform no-op fallback; `_atomic_write` uses tempfile + `os.replace`, without fsync. `orchestrator._save_state` reuses replacement. Neither helper alone proves durable claim/dispatch atomicity. |
| Completion today | `orchestrator._cmd_run` awaits `_wait_for_engineer`, verifies via `scripts/orchestrator_verify.py::verify_movement`, and invokes existing integration in orchestrator merge mode after green evidence. No selected-batch parent activation/wake mechanism is established by these functions. Native attached supervision is a dispatch premise, to be proven in the later pilot, not a new daemon entitlement. |

## 2. Proposed decisions for freeze

The decisions below are approved for the active-parent first release. Reuse stdlib and the
existing orchestrator/dashboard; add no framework, daemon, Companion, plugin,
ranking engine, price/accounting system, or duplicate project/evidence store.

| Boundary | Proposed minimum decision |
| --- | --- |
| Storage owner | Orchestrator backend owns one repository-scoped admission document at `<state_dir>/admission/selected_queue.json`, outside the `NXS-LOCAL-*.json` movement glob. One active selected batch per canonical repository/state root. Worktree-local copies are prohibited. Dashboard and parent use the same backend operations. |
| Stored content | Schema version, queue ID/revision, exact ordered source IDs and entry IDs, control state, authorization/approved-task reference and hash, parent attachment ID/generation, operation receipts, claim IDs/generations and movement/attempt references, completion receipts, reservation/conflict references. Canonical task scope, model configuration, budgets, usage, verification and delivery remain in their existing stores. Store no prompts, argv, browser paths, vendor output, secrets or duplicated task text. |
| Canonical pool adapter | Reuse project_queue's validated canonical loaders and open status vocabulary through a bounded backend adapter; classify category/priority/readiness from canonical fields and approved task evidence. Readiness is derived, never inferred from a truncated QUEUE title or cached READY flag. No project-state edit on selection/removal/start. |
| Authenticated mutations | Closed operations: ADD exact source IDs, MOVE pending entry up/down, REMOVE unclaimed entry, START, PAUSE, RESUME. Proposed same-origin bearer-authenticated POST `/api/selected-queue/mutations`; read-only GET `/api/selected-queue`. Require typed payload, expected queue revision and client operation ID; reject unknown keys/operations, duplicate membership, stale revisions, missing source IDs, invalid body/size, and attempts to mutate claimed/running entries. IDs resolve server-side, never to browser-supplied paths/commands. Preserve existing bearer/Origin semantics and token handling. |
| Mutation idempotency | Under the shared lock, persist operation ID + request digest + resulting revision/receipt in the same document. Exact replay returns the recorded receipt; reuse with different payload is rejected. Do not prune receipts for an active batch; retention after archival needs a freeze decision. Concurrent poll/render cannot perform a mutation. |
| Authorization | START references a parent-approved bounded batch decision and per-item canonical approved task/budget/dispatch authority. Authentication alone is not dispatch approval. Selection alone records order. New or changed entries after START are unapproved until explicitly added to the batch authority; RESUME cannot expand it. Required implementation contracts must be frozen; independently approved architecture work may use its own scope. Git, host, device and deployment authority remain separate. |
| Single admission domain | All fresh/resume start paths, including direct CLI starts outside this batch, participate in one repository-scoped lock/reservation gate. Existing `start` and `run` share `_do_start`; integrate there rather than guarding only the dashboard. Refuse alternate state-root bypass or unknown relevant activity. Until all entrants participate, automatic parallel admission is blocked. |
| Atomic claim | Hold the shared admission lock while reconciling capacity, validating queue/parent generation, authority/hash/baseline, dependency and scope compatibility, and writing a claim + reservation + preassigned canonical movement/attempt association. Choose the first compatible READY entry in saved order. Commit one document before any spawn. Recheck the claim/generation at the orchestrator dispatch boundary; only that boundary can start execution. |
| Durability and fences | Reuse `_FileLock` with supported-lock capability required; no no-op locking for admission. Reuse replacement, with a narrowly scoped durable admission write (file fsync before replace, directory fsync after where supported). A write/lock/capability failure blocks spawn. Parent/claim generations fence stale consumers under the same lock. No assertion of exactly-once spawn across filesystem/process boundaries. |
| Crash or uncertain spawn | Persist DISPATCHING before calling the existing orchestrator. `_do_start` currently spawns before `_save_state`; successor must make the pre-spawn association/reservation discoverable there. Lost acknowledgement, malformed/missing records, dead PID or parent timeout alone produce DISPATCH_UNCERTAIN and retain capacity/conflict reservations. Reconcile exact movement/attempt/claim with existing process, relay and run evidence; never submit a new movement or use `decide_start`'s interrupted-run resume automatically. |
| Recovery | Before any refill on startup/reattach, enumerate relevant canonical and legacy movements, active engineer/wrapper evidence and admission reservations; deduplicate exact movement/attempt associations. Unknown external activity blocks admission. Reattach only after explicit parent ownership transfer and reconciliation. A positively proven pre-spawn failure can release its reservation; otherwise PO resolves uncertainty with evidence before any authorized retry. No TTL/stale auto-reclaim, infinite retry, or PID-only proof of absence. |
| Concurrency | Selected-batch default **2**, current authorized upper cap **3** from this dispatch and CLI baseline. START records the chosen limit and authority; default is not inherited from CLI's 3. Occupancy is the union of active relevant engineers, required run/validation wrappers, and claims/uncertain dispatch reservations, deduplicated by exact attempt. Count outside-batch work in the same domain. Resume consumes/retains a reservation and cannot exploit current resume exceptions to exceed capacity. Unknown occupancy prevents new claims. Lowering a limit below occupancy stops new admission without killing work. |
| Dependencies and conflicts | Keep saved order unchanged when skipping blocked entries. READY requires satisfied dependencies, valid authority/budget/runtime and proven compatibility with active work and pending integration scopes. Unknown independence, shared migration/schema/file conflicts or missing evidence block with concrete reason. Execution completion does not satisfy a dependency requiring integration/release. |
| Completion and release | Idempotently consume completion keyed by movement/attempt and terminal report reference; reconcile underlying engineer and required wrapper inactivity before execution-slot release. Supervisor exit, relay CLOSED, worker exit zero, or dashboard Done classification alone cannot release capacity or establish delivery success. Retain canonical result, close outcome, verify, integration and release acceptance separately. Pending integration may release execution capacity once wrappers stop but retains conflict/dependency protection; existing integration remains serialized. Failure becomes Needs PO action while unrelated authorized work may continue. |
| Pause/removal | PAUSE prevents subsequent dispatch as well as new claims: any reserved but unspawned claim remains held until safe reconciliation. It never kills workers. RESUME revalidates authority/attachment and preserves order. REMOVE applies only before claim and never deletes canonical backlog. Worker stop/cancellation remains a separate existing controlled action. |
| Parent activation | One explicitly attached active parent owns admission/refill, consumes native-supervised completion and answers side questions while continuing authorized work. Attachment/ownership changes are parent-only backend operations, never a browser claim to be an agent. Old generation cannot claim/spawn/release. Display loop RUNNING / WAITING / PAUSED / NOT_ATTACHED / UNKNOWN separately from persisted queue control and item state. Closed/idle conversation has no proven wake link; detach blocks admission, not current workers. No autonomous service claim. |

Minimal item projection: READY / BLOCKED / CLAIMED / DISPATCHING / RUNNING /
DISPATCH_UNCERTAIN / TERMINAL, plus blocker, next actor and exact movement link.
Delivery evidence stays in existing Active / Needs you / Archive details. Pool
and selected sequence use existing Material 3 styling, searchable canonical
source IDs/titles, labelled native move/remove/pause controls, and keyboard
access. Two desktop regions stack on narrow screens. Polling cadence stays
unchanged; no dragging dependency or alternate state classifier is required.

## 3. Human freeze items and implementation sequence

Freeze resolutions: one canonical repository/state-root admission domain;
POSIX locking plus file/directory fsync, fail-closed elsewhere; the typed mutation
allowlist below; default two slots and existing maximum three; explicit active
parent ownership; no automatic uncertain-dispatch reclaim. Retain completed-batch
receipts without automatic pruning. Source task mapping is
`workbench_selected_work_queue`; queue entries retain actual existing backlog IDs.
Closed-turn wake-up stays deferred.
Implement the backend adapter and admission document/gate in the
existing orchestration path; prove synthetic claims/recovery first. Connect the
existing active-parent/native-supervision completion/refill workflow, then run
the separately authorized small pilot. Add Material 3 pool/selected controls
using the same operations and browser interaction tests. These steps form one
usable feature: persistence or rendered UI alone is not completion. Keep #417,
#418 and unrelated delivery independent; no second worker dispatch here.

## 4. Later acceptance outline — NOT EXECUTED

Use an isolated temporary state root, 20 opaque synthetic task IDs with canonical
approved-task fixtures, deterministic fake spawn/completion adapter and two
contending admission callers. Consume no model budget or live data. Exercise
all 20 tasks, retaining source/movement/attempt links and saved order.

| Proof | Required assertion |
| --- | --- |
| Initial fill/refill | At default 2, exactly two dispatches; each reconciled terminal completion refills automatically without a human message; all eligible selected tasks eventually handled. Explicit cap-3 authorization permits three, never four. |
| Existing activity | Two existing engineers/wrappers/claims prevent additional default-2 starts; duplicate observations of one attempt count once; resumed/direct CLI work uses the same gate; unknown occupancy blocks. |
| Ordered skipping | Blocked, dependent, conflicting and unknown-independence entries retain position/reason; unrelated READY successors start. Pending integration protects overlapping scopes. |
| Idempotency/fencing | Simultaneous claims, duplicate mutation/completion delivery and obsolete parent generation cannot duplicate spawn, release twice or exceed ceiling; reused operation ID with changed payload fails. |
| Failure windows | Crash before durable claim, after claim, after DISPATCHING, after spawn before movement save, after save before acknowledgement, and during completion release: restart reconciles before refill, no duplicate execution, uncertainty remains reserved. Unsupported locks/durability failure prevent spawn. |
| Control/restart | Pause racing a claim/spawn prevents later starts without killing work; resume preserves order/authority; revision races fail; reorder/remove cannot change running scope; membership survives restart with reconciled active work. |
| Security/ownership | Missing bearer, foreign Origin, unknown operation/key/ID, arbitrary prompt/path/argv, stale revision, missing authorization and unfrozen implementation scope fail closed. START with NOT_ATTACHED cannot dispatch; persistence does not wake a parent. |
| Delivery honesty | Worker success with integration failure remains visible; wrapper still active prevents release; failed attempt needs PO action; dependencies requiring merge/release stay blocked until that evidence. |
| Browser agreement | Accessible Add/move/remove/pause/resume, refresh/order preservation, blocker/next actor and identical movement links across selected queue/details/archive; token/draft/response-order regressions remain green. |

Then obtain separate pilot approval for two small independent authorized jobs
plus one selected successor, explicit budgets/baselines and limit 2. Pass only
when a real completion reaches the attached parent, it reconciles/releases the
slot and starts the successor without another human message, with matching
Workbench movement/state and distinct verification/integration/release evidence.
An interrupted/unattached parent yields NOT_ATTACHED or UNKNOWN, not success.
Synthetic proof and this pilot are separate gates; neither is production
certification. Full/affected/browser/render/privacy checks belong to the later
implementation's approved validation plan.

Contract-only checks for this movement: targeted authority/cross-reference tests,
repository privacy gate, `git diff --check` and `git diff --check origin/main`.
No synthetic execution matrix, model-budget pilot, UI implementation test or
real-environment validation is claimed by this DRAFT.

## 5. First-release implementation details

- The shared Git common directory stores only the canonical state/relay-root
  pointer. Linked worktrees therefore cannot opt out by choosing another state
  root once the domain is attached. All participating orchestrators must run this
  version; an older executable is outside the proven domain and must not be used
  concurrently. Updating/activating the production runner is a delivery gate.
- `scripts/selected_queue.py` is the parent CLI. `bind` records a parent-approved
  dispatch binding: source entry to existing canonical SESSION_START hash and
  baseline, provider/model/effort/budget and explicit conflict/dependency scopes.
  These are dispatch authorization metadata, not a second task specification.
  No raw prompt or browser-provided path/argv is stored. `approve` authorizes the
  current bound membership; later additions/binding changes require approval.
- `attach` requires an explicit active parent task ID and returns a fenced
  generation. This is a parent attestation; native model turns do not provide
  a dedicated OS PID. An optional real owning-execution PID may corroborate it;
  never use a long-lived application PID as proof of an active chat. The parent
  must `detach` on completion/cancellation. Read-side contact older than two
  minutes displays UNKNOWN and disables browser start; this is observability,
  never a stale-claim release or worker-progress signal. Actual parent commands
  revalidate ownership and refresh contact. No closed-turn recovery is claimed.
- Parent-only `next` atomically reserves one compatible item. The native
  supervisor invokes the existing `orchestrator run` with the returned claim,
  parent ID and generation. A completed run is consumed through `complete`
  after the wrapper exits, then the parent requests `next` again. No additional
  model poller, background queue runner or implicit integration approval exists.
- `accept` is a separate parent delivery attestation after inspection of existing
  gates; it releases dependent/conflicting work, not Git or deployment authority.
  Worker exit and terminal receipt alone never invoke `accept`.
- Stale/conflicting/unknown legacy activity blocks admission. Uncertain spawn
  reservations remain held for evidence-based PO resolution. No automatic retry
  or forced release is exposed in the first release.
- Receipt storage is retained and not automatically pruned. One active selected
  sequence per domain is supported; batch archival/rotation is deferred.
- The current table-first UI's essential evidence remains accessible in row
  disclosures and below-table details. The primary row follows the supplied
  reference: wide work title, readable status, aligned elapsed/tokens/cost.
  Fixed light appearance is the user's explicit direction; palette tokens reuse
  the existing UI2 Material 3 theme bridge.

- Additive delivery presentation correction: failed verification/integration and
  PARTIAL/BLOCKED closes remain in Needs you even with a CLOSED relay. Only an
  explicit PR MERGED observation displays merged; closure alone displays closed.
  This supersedes the closure-to-merged presentation shortcut in earlier table
  contracts without changing relay or execution status authority.

## 6. Implementation record — 2026-09-16

Status: IMPLEMENTED, AUTOMATED VALIDATION PASSED; live acceptance UNVERIFIED.
Local lane: `codex/workbench-selected-queue-m3`, base `124d454`.

Implemented the shared `selected_queue.py` admission document, typed UI mutation
endpoint, parent CLI, generation-fenced claims, canonical state-root registration,
existing start/run/resume gate, explicit completion/delivery acceptance, durable
operation receipts, pause/resume, dependency/conflict checks and uncertain-spawn
reservation preservation. The fixed light Material 3 UI reuses the product
palette, adds pool/selected panels, and follows the supplied five-column reference
with technical disclosures and below-list details. PO procedure documents the
active-turn native-supervision/refill loop; no new scheduler or model poller.

Evidence:
- Final full suite: `python3 -m pytest -q -n auto --dist worksteal -p no:cacheprovider`
  — 3790 passed, 25 skipped, 21 warnings in 185.67 seconds.
- Affected backend/browser/authority group: 104 passed before the last two focused
  queue tests; final full suite includes those tests.
- Selected-queue/render/state-consistency group: 22 passed, 1 skipped. The optional
  alternate DOM harness is unavailable; the real Chromium harness is included in
  the passing suite. Skips are not passing evidence for their unavailable paths.
- Standalone repository privacy gate: PASS, 0 findings. Test-generated runtime
  artifacts in this newly created isolated checkout were preserved outside it;
  no shared-checkout runtime or trust material was deleted.
- Project queue projection, successor index and diff whitespace checks passed.
- Browser inspection with synthetic same-origin data verified light surfaces,
  side-by-side desktop panels, stacked narrow layout, selection/add, persistent
  reorder across reload and disabled start while no parent is attached. Existing
  browser regression covers drafts, response ordering, keyboard and token gates.

Not performed: live model-budget pilot, real queue activation, service replacement,
Git push/PR/merge, host/device operation or production-data access. The original
shared dirty checkout and running Workbench service were preserved. No claim of
complete live delivery, closed-parent wake-up or production certification.

Before live activation, deploy the reviewed code through the existing authorized
path so every relevant orchestrator entrant uses the admission gate. Then run the
separately scoped/budgeted two-job-plus-successor pilot and verify the parent
consumes completion and starts the queued successor without a human reminder.
Do not use an older installed runner alongside this admission domain.

### Deployment close report

Completed: local implementation and synthetic/backend/browser validation above.
Preserved: shared checkout, running services, original source task identities,
relay authority, model budgets, Git/host/device approval boundaries. No real
engineer was dispatched. UI change is visible when serving this branch's
Workbench assets; normal product `main.py` behavior is unchanged.

Durable updates: selected queue backlog entry remains in_progress, its note links
here, successor index regenerated, AI_HANDOVER rewritten in this isolated lane.
No delivery state was advanced to DONE. Full/privacy/state/targeted evidence is
recorded above; live pilot remains UNVERIFIED.

Next: VALIDATION, Normal (strong), same task, actual active-parent pilot after the
reviewed service version is activated. Git target is main; merge BLOCKED pending
explicit Git authority and live acceptance requirements. Recommended dispatch
commands, only after that authority, from this checkout:

```sh
git add AI_HANDOVER.md roles/PO.md scripts/selected_queue.py scripts/orchestrator.py scripts/orchestrator_dashboard.py scripts/dashboard_assets/index.html scripts/dashboard_assets/dashboard.js scripts/dashboard_assets/dashboard.css tests/test_selected_queue.py tests/test_orchestrator_dashboard.py tests/test_orchestrator_dashboard_browser.py docs/design/WORKBENCH_SELECTED_WORK_QUEUE_CONTRACT.md docs/design/DECISION_RECORD_SUCCESSOR_INDEX.md docs/reference/WORKBENCH_SELECTED_QUEUE_PARENT.md docs/history/backlog/workbench_selected_work_queue.md project/backlog.json project/QUEUE.md
git commit -m "Add selected Workbench queue and light Material 3 interface"
git push -u origin codex/workbench-selected-queue-m3
gh pr create --base main --head codex/workbench-selected-queue-m3 --title "Add selected Workbench queue and light Material 3 interface" --body-file /Users/OzanDur/Codo/reports/workbench-selected-queue-pr-body.md
```

These commands were not executed. The independently requested Sites publication
contains the original synthetic visualization byte-for-byte and no repository or
runtime data. It is not the implemented Workbench service.

## 7. Local activation — 2026-09-16

The human subsequently authorized local deployment. The reviewed implementation
now serves the real Workbench on port 8767 under a user LaunchAgent; canonical
source, tests, PO procedure and admission-domain registration are updated.
See `docs/operations/WORKBENCH_LOCAL_RELEASE_2026_09_16.md` for endpoint/browser
evidence and rollback. No real jobs were dispatched. The separately scoped
active-parent refill pilot remains UNVERIFIED; feature status stays in_progress.

## 8. Active execution versus attention — 2026-09-16 correction

The human reported historical records being counted as active. This bounded
correction supersedes GOV.ORCH.4-C's Active-includes-Needs-you membership:
Active requires observed execution or nonterminal handed-over work; Archive
retains terminal inactive records regardless of failed/partial outcome; Needs
you independently retains unresolved evidence. Attention is not execution.
Required wrapper activity remains active even after engineer close. No change
to canonical task outcome, delivery acceptance or dispatch authorization.
