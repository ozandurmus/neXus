# Private neXus Workbench Companion

## Status

**DRAFT — design ready for Product Owner review; not implementation authority.**
Movement: NXS-LOCAL-0244. Date: 2026-09-15.

The dispatch explicitly requires PO review before FROZEN status. No review of
this contract is recorded yet. Freeze requires a PO decision identifying the
reviewed revision; passing tests, opening a PR and orchestrator merge mode do
not replace that decision. This movement creates documentation only.

## 1. Decision and scope

Use one supervised, per-user local observer and one client-started read-only
MCP process. They share a small atomic derived snapshot file. No HTTP listener,
custom IPC protocol, database, background agent conversation or second UI is
needed. The observer works while the browser and coding clients are closed;
the skill answers a bounded inspection request and then stops.

The existing Workbench remains the human interface. Existing orchestrator
records and relay entries remain workflow evidence; existing usage accounting
and the dispatch ledger retain their accounting semantics. The companion may
record what it observed, never advance, repair or overwrite those sources.

Canonical relay correction seq 3 removes the dispatch's unrelated deployment
baseline: no deployment manifests, selectors, ports, NetworkPolicy, shared or
remote hosts, or host installation in this movement. Future remote operation
requires separate host authorization, transport/authentication and privacy
contracts; it is not a prerequisite for v1.

The governing observability contracts are:

- `docs/design/GOV_ORCH_4_WORKBENCH_OBSERVABILITY_TOKENS_AND_STUCK_DETECTION.md`.
- `docs/design/GOV_ORCH_4A_COST_FROM_THE_REQUESTED_MODEL_WHEN_THE_STREAM_IS_SILENT.md`.
- `docs/design/GOV_ORCH_4B_A_PARTICIPANT_THE_ORCHESTRATOR_DID_NOT_SPAWN.md`.
- `docs/design/GOV_ORCH_13_THE_DISPATCH_LEDGER.md`.

This proposal adds no device commands, vendor semantics, execution authority,
Git permission, deployment permission, approval channel or automatic recovery.

## 2. Source audit and reuse boundary

Source inspection at baseline `b8704077906b6cad4b742d2d8bc4a472b4fcdd04`:

| Existing implementation | Consequence for the companion |
|---|---|
| `scripts/orchestrator_dashboard.py::build_movement_summary` calls `build_pending_action_card`, Git/PR helpers and `compute_usage` | Do not call this wrapper, `build_board`, `gather_movements`, detail/traffic builders or dashboard HTTP routes. Summary generation can register actions and read logs. |
| `scripts/orchestrator.py::_cmd_status` saves reconciled records; `_status_row` reads the activity log | Do not invoke the status CLI or `_status_row`. Read validated source fields without reconciliation. |
| `scripts/orchestrator_usage.py::compute_usage` / `update_usage` parse the engineer log and write the canonical cache | Only consume an existing cache. The companion never becomes a second collector. |
| `usage_summary_only` reads the cache; `_public_shape` / `_cost_for` expose four cost sources but default some absent counters to zero | Reuse pure projection only for validated canonical cache shapes. Distinguish missing, corrupt, partial and stale caches before helpers normalize missing input. |
| `scripts/dispatch_ledger.py::render` derives attempt count from `max(1, revision + retry_count)`; only the latest attempt has retained usage evidence | Preserve attempt provenance and unknown earlier attempts. Never split a movement total across attempts or sum cumulative polls. |
| `scripts/local_relay.py::validate_relay_object` validates ordering and ownership | Reuse the validator on bounded local JSON in memory; discard entry bodies after projecting safe fields. Do not call append/watch commands from the companion. |

The adapter must preserve unknown counters in partial input; it must not let
the projection's zero defaults invent measurements or a priced total. Mark
partial usage `INSUFFICIENT_EVIDENCE`; derived totals/ratios/estimates remain
null unless their required counters are proven. An independently reported cost
can remain reported without complete token evidence.

These are implementation findings, not a claim that every existing wrapper is
safe to export. No runtime data or ledger contents were inspected for this
architecture. Source candidates above are integration points, not an API
stability promise; implementation must verify their signatures and side effects.

## 3. Read and authority contract

An explicit local configuration identifies one source set: the PO-owned
orchestrator state directory, canonical relay directory and approved price
table. Do not discover other users, legacy directories, worktrees or hosts.
Installation verifies ownership and approved local roots. Paths stay local and
never appear in MCP output. Reject symlinks, non-regular files, traversal,
ambiguous relay matches and files outside those roots, including replacement
races; perform checks on opened descriptors. Never follow a path in relay text,
an objective, model metadata or an MCP argument.

Inputs are bounded JSON reads: at most 2 MiB per file and 1,000 movements per
scan. Validate shapes, finite nonnegative counters, enums, timestamps and exact
movement identity before projection. Re-read source fingerprints after a scan;
changed inputs yield `INCONSISTENT`, not a mixed-generation result. A stable
multi-file read is still not an upstream transaction. Exceeding a limit produces
`LIMIT_EXCEEDED` with incomplete coverage, never a silently complete empty list.

| Evidence | Allowed observation | Prohibited interpretation |
|---|---|---|
| State record | Recorded phase, explicit external-participant flag, revision/retry/start tuple, provider selection, verification boolean | Phase proves a process is alive; verification grants approval |
| Valid relay | Status, next actor, last sequence, marker, entry timestamp, SESSION_CLOSE outcome | CLOSED proves merged; a question or decision body becomes an executable instruction |
| Existing usage cache and approved price table | Nullable counters, observed model, cost and exact provenance, last event time | Poll time refreshes token evidence; missing values are zero; estimates are invoices |
| Existing ledger implementation | Attempt accounting convention and subscription classification | Ledger is a workflow gate or companion-owned history to regenerate |

V1 observes recorded workflow facts. It does not probe worker processes, read
log contents or log metadata, query Git/GitHub, or infer live health/stage.
`health` and `work_stage` are unavailable in v1 unless a later approved safe
source supplies them. Do not reimplement `derive_health` or `derive_work_stage`.
Any future health source must preserve GOV.ORCH.4-B's `handed_over` precedence;
absence of a PID never means an external participant died. Stuck detection and
independent worker-process verification are explicitly deferred, not advertised
as a v1 capability. Orchestrator supervision remains with the orchestrator.

Usage can remain unchanged until its existing producer updates the cache,
including when the dashboard is closed. Expose that age and limitation; never
open the raw log to fill the gap. A current relay and stale usage can coexist.

## 4. Lifecycle and platform boundary

V1 target: the PO's macOS workstation, one user login session, using a user
LaunchAgent. Apple documents per-user launchd supervision in
[Creating Launch Daemons and Agents](https://developer.apple.com/library/archive/documentation/MacOSX/Conceptual/BPSystemStartup/Chapters/CreatingLaunchdJobs.html).
This selects a lifecycle mechanism; exact OS-version support and launch settings
remain UNVERIFIED until the synthetic workstation acceptance slice.

The future installer uses an immutable, versioned local release outside any
ephemeral worker checkout, a fixed executable/argv and minimal environment.
No shell command strings, self-daemonization, elevated privileges, container
socket, cloud credential, dashboard bearer token or automatic update channel.
The service owns only its configuration, lock and derived state directory.
User ownership and restrictive permissions do not sandbox a compromised process
against other files owned by that same user; v1 makes no such isolation claim.

- Start at user login after explicit installation. Remain independent of browser,
  terminal and MCP-client lifetimes. No pre-login or logged-out monitoring promise.
- Take one OS advisory lock for the configured observer directory. A second
  instance exits; a stale PID file is never grounds to kill another process.
- Poll every 30 seconds, one bounded scan at a time; skip overlapping ticks.
  Native supervisor restarts are throttled to at most one start per 30 seconds.
  Missing sources are a degraded observation, not a reason to crash/restart.
- Service exit flushes only a completed snapshot. Restart validates the last
  snapshot, reconciles sources once, then resumes normal polling. Unsupported
  snapshot schema stops writes; never silently resets history or downgrades it.
- Sleep, logout and service downtime are observation gaps. On resume or login,
  reconcile once; do not replay elapsed polling ticks or claim continuous
  observation. A wall-clock rollback invalidates freshness until the next
  successful scan. Monotonic elapsed time controls polling within a process.
- A missing, unreadable, malformed or inconsistent source retains its last valid
  observation as stale. It never becomes `failed`, `done`, zero cost or deleted
  history. Recovery emits only the presently observed transition, with a gap flag.

Linux user services, Windows services/tasks, WSL, shared accounts and remote
clients are deferred. No portable supervisor abstraction is required for v1.

## 5. Minimal durable observation

Use a single versioned JSON snapshot, written through same-directory temporary
file, file flush/fsync and atomic replacement, with directory durability checked
on the supported filesystem. Directory mode 0700, files and temporary files
0600. Never store this state in a source checkout, relay directory or sync/share
folder. Snapshot readers are read-only; the observer is the only writer.

Persist only:

| Section | Fields and meaning |
|---|---|
| Envelope | Schema version, opaque source-set token, observer generation, last-attempt time, last-successful completed scan time, coverage/error enums |
| Latest observations | Exact validated movement ID; observation time; per-source status/fingerprint; record revision, retry count and start time; recorded phase; external-participant boolean; relay status/next actor/sequence/marker/time/outcome; nullable verification; usage fields below |
| Usage | Nullable token counters/turns/cache ratio, cost, `cost_source`, source event time, model requested and observed kept distinct, provenance/audit-exception classification, price-table fingerprint |
| Transitions | Observation sequence, movement ID, record-generation tuple, relay sequence when present, event enum, observed time, gap flag |
| Notification bookkeeping | Last considered transition sequence, coalescing time, delivery-attempt enum; no workflow acknowledgment |

Unknown strings are not automatically safe text: provider/model/effort and audit
exceptions require a pinned allowlist from the approved local configuration;
unrecognized values become null plus `UNRECOGNIZED`, never echoed or matched by
heuristic. Movement IDs must match the canonical source identity exactly and
stay opaque. Fingerprints cover only allowed derived fields, not raw transcripts.
Do not retain objectives, subjects, relay bodies, assessments, failure text,
paths, branches, PR URLs, credentials, raw logs, transcripts or arbitrary JSON.
Parser exceptions produce fixed error codes, never raw offending input.

Keep latest observations and at most 2,000 transition entries from the last
30 days within a 2 MiB snapshot. Prune expired/oldest transitions first and
report the history floor and dropped count. At the remaining size/movement cap,
stop admitting additional observations, expose `LIMIT_EXCEEDED`, and preserve
the prior valid observations; no silent eviction of active observations. An
otherwise valid scan may update bounded envelope error/coverage and last-attempt
time while retaining last-good observations and last-successful scan time.
Thus MCP can report current degradation without presenting retained data as new.
Removed sources become unavailable; pruning transitions never deletes upstream history.
This is bounded observation history, not the dispatch ledger or an audit archive.

An unchanged-source scan refreshes only the observation check time. It does not
refresh usage event time, create another transition or add tokens/cost. A changed
record-generation tuple starts a new observed generation; a regressed relay
sequence or changed source identity triggers reconciliation with a gap marker.
The record tuple is provenance, not a newly invented authoritative attempt ID.
The existing movement-keyed usage cache does not prove which attempt produced
it: expose `attempt_attribution: UNKNOWN` and never bill it to the new generation.
Earlier attempt data absent upstream remains unknown; do not reconstruct it from
the current total. Preserve `reported`, `estimated`,
`estimated_from_requested_model` and `unavailable` exactly. No new price guesses,
budget enforcement or grand spend total; subscription comparables stay distinct.

On corrupt state, keep the corrupt file private for explicit local recovery,
disable notifications and report `STATE_INVALID`; do not export its contents or
silently delete it. Source corruption alone must not overwrite valid observations.

## 6. Notifications

Native macOS notifications are an optional, explicitly enabled local output.
Until its native integration is validated, notification delivery is
`UNSUPPORTED`; safe transitions remain queryable through MCP. No email, Slack,
webhook, push relay, remote delivery, sound requirement or lock-screen detail.

V1 event classes: `ATTENTION_REQUESTED` from a new relay turn to PO,
`RECORDED_FAILURE` from a newly recorded failed phase, `CLOSE_OBSERVED` from a
new SESSION_CLOSE, and `SOURCE_UNAVAILABLE` / `SOURCE_RECOVERED` from source
quality transitions. None means merged, authorized, a verified dead worker or
a request to execute recovery. Event subjects and bodies are never copied.

All native messages use fixed text: "neXus Workbench has an update. Open
Workbench to inspect." No movement identity, objective, cost, path, link token
or action buttons. If a click target is supported, it opens only a locally
configured Workbench landing page without credentials or source-controlled URL;
it neither starts Workbench nor submits intent. Otherwise omit the click target.

Baseline observations after first install produce no historical notifications.
During normal scans, derive a transition only when its allowed source fields
change. Persist its sequence and notification-attempt marker atomically before
calling the native notifier. A crash after that commit can lose the alert; v1
chooses at-most-once attempts, not exactly-once delivery. OS acceptance is not
proof the user saw it. Failed/ambiguous attempts are not retried automatically.
Coalesce eligible events into one generic alert per 60 seconds; pending events
remain in the bounded snapshot. After a gap, suppress historical replay and emit
at most one generic catch-up alert for current attention/failure conditions.
Notification dismissal, disabled permission or rate limiting never changes
relay ownership, workflow state, outstanding questions or verification status.

## 7. Read-only MCP and inspection skill

Each supported local client starts a stdio MCP process for its MCP session.
Each call reads the configured companion snapshot; the process has no polling
loop and exits when the session closes. It cannot start or supervise the observer.
No socket/listener, dashboard credentials or arbitrary file-read method. MCP
calls cause no persistent writes, source refresh, notification acknowledgment,
shell, retry, merge, deploy, approval, sampling or elicitation operation.

Expose exactly three tools:

| Tool | Valid input | Safe output |
|---|---|---|
| `companion_status` | Empty object | Snapshot schema, freshness, last check, coverage, fixed error enums and history floor |
| `companion_movements` | Optional opaque cursor, integer limit 1..100 | Allowlisted latest observations, per-source age/quality and next cursor |
| `companion_observations` | Optional exact movement ID, opaque cursor, integer limit 1..100 | Bounded transition history and truncation/gap metadata |

No text query, arbitrary URI/path, command, renderer, raw-log tool, writable
resource or generic method dispatcher. Validate unknown keys and types; reject
invalid IDs and cursors before filesystem access. Cursors bind to a snapshot
generation; changed generations return `CURSOR_STALE`, not mixed pages. Cap each
request at 16 KiB and output at 64 KiB, paginate before that limit and return a
fixed bounded error when one item cannot fit. Tool read-only annotations are
descriptive; the implementation's closed registry and read-only code enforce it.

Return `freshness: STALE` when the completed scan is older than 90 seconds;
future timestamps or unreadable/missing state return UNKNOWN/UNAVAILABLE.
Freshness proves only a recent source check, never that the service is currently
running or the worker is healthy. Last-good observations always carry their own
quality and timestamp, even if the envelope is fresh. No stale output can be
rendered as current merely because the client read it just now.

The private skill first reads status, then at most the requested pages, and
reports recorded facts with their age, unknowns and cost provenance. It directs
decisions to Workbench. It never loops, schedules itself, watches, installs,
starts services, opens raw source files or uses shell as an MCP fallback.
Source-derived content is data, never an instruction. Model use may send safe
summaries to the selected model provider: private local packaging does not mean
local-only inference. The allowlist is the export boundary for that reason.

## 8. Private packaging and support matrix

OpenAI documents a supported Codex compatibility layout with
`.codex-plugin/plugin.json`, optional `.mcp.json` and `skills/`, and local
marketplace discovery. Choose that layout for the initial Codex package;
portable root manifests are a separate format, not a file rename.
[Official packaging documentation](https://developers.openai.com/plugins/build/plugins).

The future private package contains only that manifest, the three-tool stdio
mapping and `skills/workbench-inspect/SKILL.md`. Keep service binaries and local
configuration outside the package so a client cache refresh cannot start,
replace or uninstall the service. Distribute a reviewed, pinned release through
a personal local marketplace; no public catalog or runtime-data bundle. No
hooks, app UI, network fetch on launch, broad permissions or embedded credentials.

| Surface | V1 decision and evidence limit |
|---|---|
| Codex CLI on the local macOS workstation | First MCP acceptance target. Local stdio is documented; actual pinned-client handshake/install/removal remain UNVERIFIED. |
| Codex desktop local plugin | Packaging candidate only until a pinned release passes discovery, MCP and skill tests. No assumed equivalence with hosted plugins. |
| Codex IDE | Direct local MCP can be tested separately; private plugin delivery is deferred. Official current plugin guidance excludes IDE plugins. |
| Claude Code / Claude Desktop | Deferred. No Claude manifest, install command or compatibility claim until official client-specific documentation and a synthetic handshake establish it. Do not copy the Codex manifest or assume the same marketplace. |
| Web, mobile, hosted agents, remote executors | Unsupported in v1: they must not obtain a tunnel or remote endpoint to this local snapshot. |

Sources checked 2026-09-15: [OpenAI MCP documentation](https://learn.chatgpt.com/docs/extend/mcp?surface=cli)
documents local stdio support; [OpenAI plugin surfaces](https://learn.chatgpt.com/docs/plugins)
distinguishes CLI/desktop from IDE support. Documentation is compatibility
evidence, not validation of this unbuilt companion.

## 9. Independently testable implementation and rollback

Every slice requires its own approved implementation directive after freeze.
Do not execute installation as part of this architecture movement.

| Slice | Deliverable and synthetic acceptance | Rollback |
|---|---|---|
| C1: read adapter | Validated in-memory source projection; tests reject path/symlink escapes, duplicates, malformed/oversize/partial input and arbitrary text; monkeypatch forbidden helpers to fail if called; upstream byte hashes unchanged | Remove adapter; upstream untouched |
| C2: observer state | Foreground local observer and atomic bounded snapshot; tests cover same poll twice, source loss/recovery, record retry, sequence regression, missing usage, all four cost sources, unknown attempt attribution, partial writes, corrupt/newer schema, size/retention caps and clock rollback | Stop observer; retain private state; compatible reader can inspect it |
| C3: MCP reader | Closed three-tool stdio server; protocol tests exercise initialization/tool listing/calls, strict input bounds, pagination generations, error redaction and fresh/stale/missing state; concurrent readers never see partial JSON; source and snapshot hashes unchanged by calls | Disable/remove MCP registration; observer keeps working |
| C4: private Codex package | Pinned local client discovers only three tools and one finite inspection skill; injection sentinel is never returned, raw-file/shell fallback unavailable, uninstall removes only owned registration | Remove package entry; do not remove source/history or unrelated plugins |
| C5: workstation lifecycle and notifier | Separate approved synthetic macOS installation; show browser/client closed still observes fixture change, crash restart is throttled, duplicate start blocked, sleep/login reconciles gaps, denied notifications remain safe, crash-at-delivery behavior matches at-most-once attempts | Disable/unload only companion agent and notifier; remove owned binaries/config; preserve snapshot by default |

For C1/C2, compare projection with the named pure usage helpers on synthetic
inputs; no fixtures constructed from real logs. Assert null is distinct from
zero, requested/observed models stay distinct, estimates never become reported,
an external participant is never labelled dead, and SESSION_CLOSE never implies
merge. Tests must deny subprocess/network/source-write calls on observation and
MCP paths, allowing only the future fixed native notification integration in C5.

C5 records exact OS/client/package versions and observed results before any
platform is advertised as supported. Human workstation validation is separate
from unit/protocol tests. Shared-host or production validation is out of scope.

Uninstall stops supervision before removing executables, so keep-alive cannot
resurrect the service. Remove only manifest-recorded companion files and its
own plugin/MCP entries, preserve unrelated configuration, canonical records,
relay, ledger and private derived history. A separate explicit purge can delete
only companion-owned derived history. A downgrade never rewrites a newer schema;
restore a compatible binary or keep the service stopped. No upstream migration.

## 10. Review and validation record

Definition of Done: PO-reviewed FROZEN contract, explicit deferred platforms,
bounded reversible slices, named authority/privacy/diff gates passing, PR and
SESSION_CLOSE handed to the orchestrator. No runtime implementation is claimed.

Architecture validation commands:

```text
python3 -m pytest -q -p no:cacheprovider tests/test_contract_authority_status.py tests/test_design_cross_references_resolve.py
python3 scripts/repository_privacy_check.py
git diff --check
git diff --check origin/main
```

Pending gate: Product Owner review and recorded freeze decision for this text.
The architecture remains PARTIAL until that gate is satisfied. The next
movement is ARCHITECTURE review, then C1 IMPLEMENTATION at ordinary feature
implementation reasoning; escalate for a new source or security boundary.

Validation on 2026-09-15: both named authority modules passed (8 tests);
repository privacy gate passed with zero findings. Diff checks are required
again immediately before commit. These static checks do not validate the
unbuilt lifecycle, MCP, client packaging or native notification integration.
