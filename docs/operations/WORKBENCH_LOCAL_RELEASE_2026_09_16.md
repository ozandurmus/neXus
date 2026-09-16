# Local Workbench release — 2026-09-16

Status: LOCAL DEPLOYMENT VERIFIED; active-parent real-worker pilot UNVERIFIED.

## Authorization and scope

Human directive in this task: deploy the implemented Workbench and update the
necessary locations. This authorizes the local user-owned Workbench rollout;
no corporate push/merge, remote host/device operation or real-job dispatch was
performed. It does not freeze or validate closed-turn automatic continuation.

## Installed configuration

- UI: `http://127.0.0.1:8767/`, bearer-authenticated, loopback only.
- Release: `/Users/OzanDur/.nexus-workbench-companion/releases/workbench-m3-20260916`.
- User LaunchAgent: `com.nexus.workbench-dashboard`, RunAtLoad/KeepAlive enabled.
- Canonical source/relay: `/Users/OzanDur/Codo/nexus` and its `relay` directory.
- Canonical orchestrator state: `/Users/OzanDur/Codo/nexus-nexus-worktrees/.state`.
- Source manifest: release-local `LOCAL_RELEASE.json`; source base `124d454` plus
  explicitly listed local changes. This is a local release, not a merged revision.
- Private stdout/stderr logs under the existing Companion state directory have
  mode 0600. The bootstrap bearer fragment is never copied into this record.
- Existing Companion observer and monitor configuration were preserved.

The installed v1 archive optimization was carried forward: terminal movement
rows use cached usage and avoid per-row Git/GitHub queries. The unrelated v1
source and original running dashboard were preserved as rollback evidence.

## Canonical source updates

Installed the selected-queue module, light dashboard/backend/assets and frozen
contracts in the shared checkout. Applied only the bounded admission patch to
its orchestrator; preserved pre-existing unrelated changes. Added the selected
batch paragraph to the existing PO procedure instead of overwriting that file.
Backlog item `workbench_selected_work_queue` stays in_progress until real-worker
acceptance. Changes remain local; no commit, push, PR or merge was performed.

Registered the shared Git common-directory admission-domain pointer so the
canonical runner and deployed runner use the same state/relay domain. Created an
empty PAUSED queue with no parent attachment, no claims and no dispatched work.

## Validation

- Source targeted deployment regression: 99 tests passed, including terminal-row cache/Git/log regression.
- Earlier full implementation regression: 3790 passed, 25 skipped.
- Real-data staging read: 63 pool items and 153 movements; board about 0.24s.
- Deployed service: root and CSS HTTP 200; authenticated queue/board HTTP 200;
  board about 0.19s. Queue selected count 0, NOT_ATTACHED, Start disabled.
- Authentication: missing token 401, foreign Origin mutation 403, malformed
  mutation 409. No successful queue mutation was used as a deployment test.
- Browser: real pool 63 loaded, light color scheme with background rgb(249,249,255),
  pool and selected-work panels visible. History/delivery classifications are
  distinct from active process counts; unresolved historical failures can appear
  in Needs you and must not be read as live workers.
- LaunchAgent observed running; no restart or exit recorded at verification.
- Canonical project queue projection and targeted whitespace checks passed.

## Operating boundary and next action

Users may select/reorder work in the browser. An active PO follows
`docs/reference/WORKBENCH_SELECTED_QUEUE_PARENT.md` to prepare approved movement
bindings, attach ownership, authorize the batch and consume/refill claims.
Selection alone starts no process and supplies no Git/deployment authority.
The disabled start control when NOT_ATTACHED is expected, not a failed rollout.
Use the updated canonical orchestrator; do not run the old v1 orchestrator as a
second admission owner. A real two-worker-plus-successor pilot remains a distinct
budgeted acceptance step. No worker was started by this deployment.

## Rollback

Preserved source files and absent-file manifest:
`/Users/OzanDur/.nexus-workbench-companion/rollback/workbench-m3-20260916`.
Previous application source: `/Users/OzanDur/.nexus-workbench-companion/releases/v1`.

Before rollback, pause admission and reconcile all workers/claims. Never switch
to an older unguarded runner while selected claims exist. Stop only the dashboard
LaunchAgent, point its WorkingDirectory at v1, and restore only the named source
files from the backup manifest if rolling back dispatch as well. Preserve runtime
queue/history and authentication data. Domain-pointer removal requires confirmed
zero activity and an explicit rollback decision; it is not routine cleanup.

## Deployment close report

Completed: local rollout, automatic dashboard startup configuration, real-data
endpoint/auth/browser checks, canonical runner/PO integration and rollback record.
Changed: local release/LaunchAgent, scoped canonical source/docs, queue metadata
and backlog note. Preserved: unrelated checkout changes, existing workers,
relay history, observer/monitor configuration, remote systems and production data.
Privacy: no token/credential/raw operational value was reproduced in this record.
Full repository privacy gate was not rerun on the pre-existing dirty checkout;
the clean implementation release previously passed it with zero findings.

Next: VALIDATION, Normal (strong), same task, separately scoped real-worker queue
pilot. Local UI rollout is complete; feature delivery remains in_progress until
that evidence exists. Main merge is not authorized by this deployment directive;
no stage/commit/push/PR commands were executed. Product main.py behavior is unchanged;
the local Workbench now serves the new queue and light Material 3 interface.

## Active-count correction — 2026-09-16

The user reported Active (54). Live inspection found 153 loaded movements,
74 exited process records and 79 disconnected relay-only records; no running
engineer was observed. Historical failed/PARTIAL/BLOCKED records had entered
Active because presentation classified them Needs review and treated every
non-Done label as active.

Corrected membership: Active requires positive execution evidence (including a
still-running validation wrapper), or nonterminal handed-over work. Archive
contains terminal records not currently executing. Needs you independently
includes unresolved delivery/observation issues, including historical records.
Needs you and Archive may overlap; their counts must never be summed as workers.
Canonical relay/worker/project state was not rewritten to fix the display.
Targeted dashboard/browser regression: 86 passed. The correction is deployed
on the same local service; archived history and pending review evidence remain.

## Info guide — 2026-09-16

Added a keyboard-accessible Info tab and contextual preparation-help link. The
guide explains purpose, pool/selected/history views, user versus PO duties,
preparation/binding, readiness and connection states, start/pause behavior,
review gates and active-turn limits. Tab switching preserves the selected
movement, filters and memory-only message drafts. No backend admission, user
selection or worker state changed. Deployed static assets without service restart
or token rotation; live browser verified the Info page and preparation section.

Targeted dashboard/browser tests: 87 passed. Final browser plus unique-session-
heading check passed. The preceding full count-correction regression reported
3792 passed / 25 skipped / one documentation-heading failure; that duplicate
heading was corrected and its exact check rerun successfully. No claim of a
second full-suite run for this static guide is made. No jobs were dispatched.
