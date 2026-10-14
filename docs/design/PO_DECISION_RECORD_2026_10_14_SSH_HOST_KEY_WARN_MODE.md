# SSH host-key mismatch warning mode

Status: RATIFIED — Product Owner chat, 2026-10-14.
Scope: hostkey-warn-mode; local implementation and validation only.

The Product Owner reaffirmed the 2026-09-13 decision: a changed pinned SSH
host key must be visible and audited rather than silently preventing read
collection. The persisted global mode is WARN by default, with STRICT as an
optional security-admin setting. This decision supersedes strict-only mismatch
refusal for the read scope below, including the Check Point constitution note.

Only jobs whose persisted action_class is exactly `read` may authenticate after
a mismatch in WARN mode. Every other class, unknown job context, and failover
execution refuse. A mutation must never follow an unverified identity. The PO
may widen this boundary only in a later explicit decision. This decision adds
no device command and no command gate entry. Missing pinned trust and existing
first-contact policy are unchanged; WARN applies only to MISMATCH.

Before allowing authentication, atomically persist an observation and audit:
device/job references, endpoint, key algorithm, presented and expected SHA-256
fingerprints, first/last seen, count, and OPEN/ACCEPTED/REJECTED status. Fingerprints
may be shown to aiview; device names, addresses and serials still pass through
the existing masking projection. Audit records carry derived references/status,
not operational identities. Storage/audit failure refuses authentication.

Mark jobs and devices when mismatch admission occurs. Historical jobs and the
device's collection warning remain marked after acceptance: acceptance does not
retroactively verify evidence. Show warnings in the device workspace and
Operations, alongside separately labelled existing management-plane inventory;
missing manager evidence remains UNKNOWN. Direct observations are not manager
corroboration. Job history displays `host_key_mismatch_warned`.

Only security_admin may change mode or accept/reject an observation. Accept
requires the exact text `ACCEPT NEW SSH KEY` and independentlyVerified=true as
the operator's attestation through ManagementEndpointSshTrustService. The
existing re-enrollment supersedes the old trust row and activates the observed
key, atomically with resolution. Stale observations cannot replace a subsequently
changed pin. Reject preserves the pin and forces strict mismatch refusal for
that device until an observed replacement key is explicitly accepted. Rejected history is retained;
acceptance clears the device/endpoint refusal override. Rejection
also covers a different presented key for the same endpoint. No automatic
pin replacement, optional per-device WARN override, or new dependencies.

Validation: transport policy, non-read/failover refusal, strict mode, durable
observations/audit, resolution, masking, route/RBAC, migration and frontend
warning/actions tests. Deployment, real-device checks, push, PR and merge are
outside this lane. Any eventual PR must state the read-only mismatch boundary.

## Local validation and remaining evidence

- `npx tsc --noEmit -p .`, `npx vitest run` (60 files, 560 tests), and
  `npm run build`: PASS.
- `./gradlew :persistence:compileTestJava :worker:compileTestJava :service:compileTestJava -x frontendCi -x frontendBuild`: PASS.
- `./gradlew test --continue -x :integration-tests:test -PfrontendPrebuilt=true`:
  PASS, 1,830 tests across ten modules, no failures or skips.
- `python3 tools/privacy/repository_privacy_check.py` and `git diff --check`: PASS.
- Full `compileJava compileTestJava` is blocked by pre-existing integration-tests
  dependency-lock omissions for spring-tx, spring-jdbc and spring-boot-starter-jdbc.
  No dependency or lockfile was changed. The initial unqualified Gradle call
  transitively started frontendCi; subsequent calls disable it/use the existing
  frontendPrebuilt switch to respect the no-install boundary.
- V147 has a schema/idempotence/audit-projection test. PostgreSQL application and
  BEGIN/ROLLBACK execution, real SSH handshake, deployment and masked live UI
  acceptance remain UNVERIFIED; no device or host contact was performed.
- Baseline discrepancy: Ui2WorkerMain enables UI2_SSH_ALLOW_TOFU unless explicitly
  false, and existing tests cover auto-enrollment. Thus the brief's assertion
  that every unpinned connection already refuses is not true of this baseline.
  This mismatch-only lane preserves that first-contact behavior; where the
  existing verifier returns MISSING it still refuses. No WARN exception applies
  to MISSING. Changing TOFU requires a separate PO scope decision.
- Management discovery exposes model/version but no proven serial field here;
  the warning shows serial UNKNOWN instead of treating an opaque discovery
  identifier or direct-device facts as management-plane serial evidence.
