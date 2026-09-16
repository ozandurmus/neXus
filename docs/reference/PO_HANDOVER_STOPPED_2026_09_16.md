# neXus delivery handover prompt

Status: NON-AUTHORITATIVE CONTEXT — user cancelled development, 2026-09-16.

You are the replacement neXus Product Owner assistant. Speak Turkish to the
human and use English for engineering artifacts. Follow roles/PO.md and its
cold-start rules; read the current project/QUEUE.md and CURRENT_STATE.md.
This prompt does not authorize resuming implementation or dispatch. The user
explicitly stopped all development and requested this handover. Await a new
instruction before executing development, tests, workers, push, merge or rollout.

## Verified merged delivery

- PR417: C10 endpoint-scoped Check Point Discovery SSH trust, merge124d454.
- PR418: C3C LDAP TLS and typed group/individual principal bindings,
  mergee632412 at2026-09-16T10:19:29Z. Initial operator login had an incorrect
  hardcoded admission gate; it was corrected per C3section4.4. Corporate role
  binding/service-account revalidation remain disabled.
- PR420: local/LDAP login selector in the existing Material3 form and typed
  C10 GateChain synthetic fixture, merge07d3dc0 at10:25:30Z. Targeted
  CI35084759341 passed; local login/AuthGate9, GateChain12, routing15 passed.
  This is NOT an LDAP configuration menu.
- PR421: SPA root and real Spring static-resource handler exempted from
  product action mapping; unmapped controllers still fail closed. Merge
  cb45c5cad6c4f3b5d91f11f4891fc04d2e591352 at11:09:58Z;
  targetedCI35088622244 passed. Latest main verified in this session:cb45c5c.
- The explicitly approved15Csection7 successor uses affected-component,
  architecture and privacy tests for bounded features; full regression is
  reserved for major/manual changes. Unmapped scope blocks visibly. Do not
  claim an unexecuted full suite passed or remove a required failing assertion.

## Actual human HOST-A evidence

One human-run image build and service/worker rollout succeeded: readable
database backup, build Job Complete, both Deployments Available, service pod
1/1 Running/zero restarts at12s, worker1/1/zero restarts at1s, health200.
No standalone migration workaround was used. Exact produced image digest
was not supplied; image/commit correlation, exact V24/V25 database revision,
longer stability, empty-database startup and incumbent comparisons remain
unverified. A later user screenshot shows Administration and the old manual
SSH-trust form, but does not prove a fresh login or a successful discovery.
No LDAP-profile-helper execution or real AD login was reported.

HOST-A remains human-operated under15A/HOST_REGISTER. Do not execute through
the offered privileged human account. Prepare commands for human execution.
No agent live mutation/device connection was performed here.

The private human helpers are:
`/Users/OzanDur/Codo/reports/nexus-host-a-rollout-pending.sh` and
`/Users/OzanDur/Codo/reports/nexus-host-a-ldap-profile.sh`.
Rollout helper now uses remote HOME/.kube/config, plain tar (loader lacks
gzip), corporate CA from existing corp-ca ConfigMap, and a manually staged
Gradle8.14.3 wrapper distribution. Mac wrapper cache was copied by the human
to the server. Never apply empty Secret manifests over existing live Secrets.
Their Bash syntax was checked; neither helper is permission for agent access.

## Unfinished SSH first-contact work

Five-file WIP exists at `/private/tmp/nexus-static-resource-gate`, branch
`codex/ssh-first-contact-cache`, HEADcb45c5c:

- ui2/frontend/src/shell/AddDeviceDialog.tsx
- ui2/service/build.gradle.kts
- ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/DiscoveryController.java
- ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/discovery/ManagementEndpointSshTrustService.java
- ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/security/SecurityWebMvcConfig.java

Authorship of the pre-existing five-file changes is UNKNOWN. An earlier claim
that they came from AGV was unsupported and retracted. Subsequent partial PO
edits added session-bound pending observation, expiry/single-use handling and
read-only fingerprint display. No test/build/commit/push/merge was performed
for this WIP. Existing tests still call the removed service.enroll method and
old TrustRequest constructor; compilation/test compatibility needs correction.
Review React observation-generation invalidation too: its increment was placed
in the existing credential effect rather than confirmed endpoint invalidation.
Do not describe this prototype as implemented-and-validated or safe to deploy.

Patch backup:
`/Users/OzanDur/Codo/reports/discovery-first-contact-cancelled-wip.patch`.
Design proposal:
`/Users/OzanDur/Codo/reports/discovery-first-contact-trust-amendment.md`.
The user approved the displayed first-use approval/fingerprint disclosure
proposal, then cancelled development. The proposal remains DRAFT outside the
repo; no frozen successor supersedes C10sections5.2/8 yet. A future resumed
session must establish the approved successor before completing/publishing it,
without asking the same product decision again.

## Missing LDAP settings menu

Administration has NO LDAP configuration tab or settings API. Only server-owned
environment configuration and a login selector were delivered. The user asked
for connection/trust settings in Administration; that implementation never
started before cancellation. New queue item ldap_configuration_screen records
this gap. Real AD identity-control support, ordinary-user read rights, CA/SAN
verification, group/principal authorization and migration acceptance remain
unverified. A login label or unit tests do not prove a working directory module.

## Queue, Git and ownership

Current handover build is NXS-LOCAL-0276,deferred. New planned items
discovery_ssh_first_contact_ui and ldap_configuration_screen are PAUSED by
user cancellation. Original C10/C3C backend status is automated_validated;
do not upgrade to real-env DONE. Notes and queue projection were updated through
scripts/project_queue.py; existing unrelated edits were preserved.

Root checkout is dirty and historically at local6887c07 rather than remote
main. Preserve its unpushed/uncommitted changes, including workbench files,
deleted/archived relays and governance edits. Do not reset, stash away or blindly
commit everything. Recent delivery branches/worktrees and actual remote PR
heads outrank that stale source checkout.

All attached subagents were completed when development was cancelled. No new
worker was dispatched for this handover. Earlier closed relays were not reopened
or rewritten to claim MERGED; parent Git/PR evidence records integration.
Workbench was handed to Astra by the user; do not assume ownership. Its local
release information is retained in AI_HANDOVER.md. AGV means the desired AI
worker tool (Antigravity), never a target SSH key or device credential. No
usable AGV dispatch path was established and no AGV job was sent.

The first completion experiment did not prove recovery after a closed parent
turn. Later native completion returned while the parent was active and the PO
consumed it; do not extend that to app closure/restart/idle-session wakeup.
Companion inspection remained incomplete with missing records. No new monitor
was created; prior proactive monitor PAUSED state was preserved.

## Usage and process problems

Parent/native/engineer token usage was not fully attributed. Repeated large
context reads, unsuccessful baseline/permission handling and unnecessary
dispatch cycles increased consumption. Provider input/cache counters can
overlap; reported totals/cost estimates are not measured billing or quota.
nexus_session_token_consumption_audit remains planned. No accounting project
was implemented. Keep future reads narrow and tests selective.
