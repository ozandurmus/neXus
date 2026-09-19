# Workbench selected ordered work queue and admission

## Status

**DRAFT — successor candidate for parent review and explicit human freeze.**
Movement `NXS-LOCAL-0271`, ARCHITECTURE. This document authorizes no queue
storage, authenticated mutation, execution, pilot, or existing governance change.
Merging this draft does not freeze it. Implementation starts only under a named,
approved successor movement after freeze.

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

All decisions below are proposals until explicitly frozen. Reuse stdlib and the
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

Parent must show the exact candidate and record explicit human freeze of the
new storage/write/admission boundary. Confirm: (1) shared state root and every
CLI entrant participating, supported lock/durability assumptions; (2) typed
mutation allowlist and batch authorization including later additions;
(3) default 2/cap 3 and exact relevant activity domain; (4) parent attachment,
transfer and evidence required to resolve uncertain dispatch; (5) completed-batch
receipt retention; (6) named implementation movement, exact approved task and
backlog mapping. Until then all implementation/pilot work is BLOCKED. A new
unattended resume requirement needs its own proven supported scheduler link.

After freeze: implement the backend adapter and admission document/gate in the
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
