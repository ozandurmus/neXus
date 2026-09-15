# UI 2.0 — C8 mockup-palace synthetic test environment contract

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-15.** Authored under
`docs/design/GOV_PO_3_APPROVED_MOVEMENT_ORCHESTRATION.md` (FROZEN) as bounded
`ARCHITECTURE` work (movement `NXS-LOCAL-0231`), on explicit Product Owner
instruction in this session to review and freeze
`docs/design/UI2_0_MOCKUP_PALACE_TEST_ENVIRONMENT.md` (SUPERSEDED by this
document) before implementation is dispatched. Supersedes that document for
every clause resolved below (its §11 items 1–7, and one correction to its
§4.5/§10 the freeze review's own source read surfaced). No `ui2/`
implementation, migration, or test-container launch is authorized by this
document — it fixes the seed scope, the isolation mechanism, and the
identity boundary; a follow-up `IMPLEMENT` movement, dispatched separately,
builds them (§9 below).

No production data, host, device, or credential was accessed to produce this
freeze; only repository source, tests, and design/contract documents were
read.

---

## 1. What this resolves, and what it does not

This contract resolves `UI2_0_MOCKUP_PALACE_TEST_ENVIRONMENT.md` (SUPERSEDED,
hereafter "the predecessor") §11 items 1 through 7, plus one correction the
freeze review's own targeted source read produced that the predecessor did
not have. It does not re-derive the predecessor's non-`UNKNOWN` content —
the fixture-to-Java mapping and classification (§3/§4 of the predecessor),
the growth rule (§5), or the reasoning for reusing the existing test-carrier
mechanism (§6) — those stand as historical record and are restated here only
to the extent this contract's own decisions depend on them.

## 2. Authority chain (highest first)

1. `AGENTS.md` — durable constitution: identity law, UNKNOWN/fail-closed
   law, evidence laws, raw-evidence law.
2. `docs/design/UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` (FROZEN) — §2
   module map (eleven Java subprojects, `cli`'s declared deps), §5 test
   carrier resolution rule, §4 pinned dependencies (UnboundID for LDAP).
3. `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` (FROZEN) — §3 table
   ownership matrix, §5 `provenance_records` DDL.
4. `docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` (FROZEN) — §2
   LDAP bind mechanism, §4.1 `role:viewer`, §5 RBAC enforcement path.
5. `docs/design/UI2_0_BASELINE_DIRECTORY.md` (FROZEN) — §3 screen-state
   vocabulary, §4 feature-contribution/growth ordering.
6. This document's predecessor, `UI2_0_MOCKUP_PALACE_TEST_ENVIRONMENT.md`
   (SUPERSEDED) — historical only, cited for provenance, never as authority
   for a clause this document restates or corrects.

## 3. Seed-loader location and module wiring (resolves predecessor §11 item 1)

**Decision: a new subcommand of the existing `cli` module, not a new Gradle
source set.** `ui2/cli` already exists on disk (`CliEntryPoint.java` plus its
test) and is exactly the shape of component a one-shot, locally-run seeding
operation belongs in — reusing it is smaller than inventing a second
mechanism next to `integration-tests` for the same purpose.

`cli`'s declared project dependencies today are `platform-core` and
`job-engine` only (`UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §2 module
map) — neither reaches `persistence` directly. The follow-up `IMPLEMENT`
movement (§9 item 1 below) must add `persistence` as a declared `cli`
dependency before the seed loader can write rows through it; this is named
here so that movement does not have to rediscover it.

**`jobs`/`job_steps` seeding depth: pre-`COMPLETED` only.** The seed loader
inserts `jobs`/`job_steps` rows already in a terminal `COMPLETED` state; it
does not exercise any live state transition. `C2`'s lifecycle columns and
state machine are that contract's own scope, not re-derived here, and a
seed loader driving a live lifecycle transition would be inventing job
-engine behavior this contract has no mandate to specify.

## 4. Database-is-a-seed-instance guard (resolves predecessor §11 item 2)

**Decision: a dedicated marker table, not a naming convention.** A naming
convention is a string the connecting service does not itself verify against
anything — the service would be *inferring* the database's identity from a
label, which is the same "casually inferring equivalence" pattern
`AGENTS.md`'s identity law forbids for opaque identifiers, applied here by
direct analogy to a connection target. A marker table requires an actual row
to exist, written only by the mockup-palace seed path, which the service can
positively verify with one query.

Fixed shape: a single-row sentinel table, `mockup_palace_marker(marker_id
TEXT PRIMARY KEY, created_at TIMESTAMPTZ NOT NULL)`, created by a migration
path applied **only** when provisioning a mockup-palace instance — never
part of the product's own `service/src/main/resources/db/migration/`
sequence Flyway applies to every real deployment (this is the same
separation principle `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §5 already
uses to keep the test carrier's `ui2_migrate`/`ui2_app` sequencing distinct
from anything a real deployment does). The connection-time guard the
predecessor's §8 already requires runs one `SELECT 1 FROM
mockup_palace_marker LIMIT 1` at startup, against the live connection;
absence — including a query error, a missing table, or any ambiguity —
refuses to start rather than serving any payload, mirroring
`UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §5 rule 2's "must never skip,
pass, or report success" posture for a missing carrier.

## 5. Spring profile name (resolves predecessor §11 item 3)

**Decision: `mockup-palace`, the predecessor's own working name, unchanged.**
A full-module grep of `ui2/` at freeze time found zero `@Profile`
annotations and zero `spring.profiles`/`profiles:` keys anywhere in the
codebase — the collision space this open item worried about is empty today.
Renaming a name nothing collides with would be inventing bikeshedding this
contract has no evidence to justify; the smallest diff keeps the name the
predecessor already proposed.

## 6. Test-only identity fixture (resolves predecessor §11 item 4)

**Decision: option 1 — an embedded, in-memory UnboundID directory, not a
stub identity provider.** UnboundID is already a pinned dependency
(`UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §4: "UnboundID for LDAP"); its
SDK's `InMemoryDirectoryServer` utility, bound to `127.0.0.1` on an ephemeral
port and started/stopped entirely within the `mockup-palace` profile's own
process lifecycle, is not a live corporate directory or any external
credentialed dependency — it satisfies `.nexus/WORKER.md`'s "no host/device/
credential access" invariant while exercising `C3` §2's real bind-and-resolve
code path, which is closer to what a real device workspace will actually run
against than a stub that bypasses authentication entirely.

Fixed boundary condition (restated from the predecessor's §9, which already
fixed this and is not reopened): the in-memory directory is seeded with
exactly one synthetic operator DN, bound only to `role:viewer` through the
same `role_bindings`/`RbacEvaluator` path `C3` §5 specifies for every other
identity — never a separate "test mode" authorization branch. No new role
token is introduced; `role:viewer`'s existing "read every projection UI 2.0
ships; read own audit rows... cannot submit any job or intent"
(`C3` §4.1) is exactly the least-privileged real role this environment
needs.

## 7. Seed scope correction (resolves predecessor §11 items 5–6; corrects §4.5/§10)

**Finding, not anticipated by the predecessor.** A targeted read of
`ui2/service/src/main/java/.../api/*.java` during this freeze review found
that `cp_inventory_projection` — the table the predecessor's §4.5 "net
result" names as "the one seedable Java slice" — has **no current
controller, service, or repository read path anywhere in `ui2/service`**.
`InventoryController` (the only controller with "inventory" in scope) reads
an entirely different table family — `device_inventory_run` /
`device_interface` / `device_route` / `device_interface_address`, from
`V13__device_inventory.sql` — never `cp_inventory_projection`. Grepping the
whole `service/src/main/java` tree for `cp_inventory_projection` returns
zero hits outside test files and the migration SQL itself.

This means seeding `cp_inventory_projection` today would write rows nothing
renders: the predecessor's own §10 visual-acceptance-matrix row "CP
inventory tab" (a device showing `product_version`/`ha_state`) cannot be
exercised by any capability that exists today, contrary to what §4.5's
"the one seedable Java slice" implied. Per `AGENTS.md`'s evidence laws
("collection success != semantic correctness") applied here by direct
analogy to schema/seed correctness: a table existing is not evidence a
screen can render from it.

**Decision: `cp_inventory_projection` seeding, and the "CP inventory tab"
visual-acceptance-matrix row, are deferred out of the v1 seed scope** — not
frozen as part of this initial slice. They re-enter scope only once a
capability's own Integrate movement adds both a real write path for the
table and a controller/service read path for it, governed by the
predecessor's §5 growth rule (which this contract does not alter).

**v1 seed scope, corrected:** the tables that already have a real,
confirmed controller read path today — `DeviceRegistrationController`'s
`GET /devices` and `GET /devices/{deviceId}` (fields: `device_id`, `role`,
`vendor_hint`, `enrollment_state`, `disabled`, `hostname`, `model`,
`software_version`, `ha_role`, `cluster_member_ref`, `job`; confirmed at
`DeviceRegistrationController.java` `toSummaryBody`/`toDetailBody`) — plus
`devices`, `endpoints` (identity placeholders, `B1-4b`), `credential_
references` (placeholder identity only), `jobs`/`job_steps` (§3 above,
`COMPLETED` only), `audit_log`, and `provenance_records` (linkage rows for
whichever job/device rows are seeded).

This still leaves one item genuinely open, named here rather than guessed:
`DeviceRegistrationController`'s `facts` object (`hostname`, `model`,
`software_version`, `ha_role`) was read from the controller's DTO shape
during this review, but its backing table/column source was not identified
this movement — the seed-loader `IMPLEMENT` movement (§9 item 1) must
locate it before writing seed rows for those four fields, rather than
inventing a source table here without having read it.

`device_inventory_run`-family seeding (the table `InventoryController`
actually reads) is likewise deferred, for the same reason as
`cp_inventory_projection`: resolving its exact seed shape was not this
freeze's mandate, and inventing it now risks exactly the "presenting a
capability the platform does not have" failure the predecessor's §4.5
itself warned against for the topology-matrix ambition.

**`provenance_records` column mapping, per the v1 scope above** (predecessor
§11 item 5, `C1` §5's confirmed DDL — 9 columns, `V1__initial_schema.sql`
lines 74–87, realized without deviation from the sketch): `provenance_id`
/ `run_id` / `step_id` — opaque synthesized IDs, application-generated per
the identity law's opaque-identifier discipline; `parser_version` /
`capability_version` — a fixed sentinel string, e.g.
`"mockup-palace-seed-v1"`; `capture_artifact_id` — `NULL`; `source_location`
— a fixed sentinel string naming the seed loader itself, never a real
device address or path; `sanitized_fragment` — `NULL`; `fingerprint_sha256`
— a SHA-256 digest of the sentinel content actually written; `collected_at`
— the seed run's own timestamp. No column is invented beyond `C1` §5's
fixed set.

**`product_version`/`ha_state` vocabulary (predecessor §11 item 6): moot for
v1**, since `cp_inventory_projection` is deferred by this contract (above).
Confirmed for whichever future movement re-admits it: both columns are
plain nullable `TEXT` with no `CHECK` constraint (`V1__initial_schema.sql`
lines 127–128) — no enum vocabulary exists anywhere in the codebase today,
matching the predecessor's own finding; that movement must not invent one
without a capability spec.

## 8. Deferred, unchanged from the predecessor (item 7)

The frontend-rendering-boundary (XSS-payload) device-name case (predecessor
§11 item 7 / §12 follow-up item 4) is **not resolved by this contract** —
no Java rendering surface exists yet to test it against, and nothing this
freeze reviewed changes that. It stays exactly as deferred as the
predecessor left it.

## 9. Successor implementation sequence (non-binding, ordered)

Not authorized by this document — named so a Product Owner dispatch has a
concrete next movement to approve, in dependency order:

1. **Seed-loader `cli` subcommand + `mockup_palace_marker` migration path +
   connection-time guard.** Adds `persistence` as a declared `cli`
   dependency (§3); writes the v1 scope (§7) only; locates the source of
   `DeviceRegistrationController`'s `facts` fields before seeding them (§7);
   proves the marker-table check via `UI2_0_B1_01A_PLATFORM_SKELETON_
   CONTRACT.md` §5's fail-closed discipline (a deliberately-broken guard
   must trip a test, not merely be absent from today's code).
2. **`mockup-palace` Spring profile + local browsing endpoint**, `127.0.0.1`
   -only, with the architecture-level "no transport class reachable from the
   profile's wiring graph" test the predecessor's §8 already specifies
   (unchanged, not reopened by this contract).
3. **Embedded UnboundID directory + `role:viewer` session wiring** (§6).
4. **Visual acceptance matrix**, scoped to the corrected v1 seed set (§7) —
   the predecessor's §10 table minus the "CP inventory tab" row, which is
   re-added only once item 7's deferral resolves.

None of items 1–4 may proceed without this contract's own scope (§3–§7)
being read first; none may silently re-admit `cp_inventory_projection` or
`device_inventory_run`-family seeding without a follow-up movement that
reads their real read paths, per §7 above.

## 10. Cross-references

- `docs/design/UI2_0_MOCKUP_PALACE_TEST_ENVIRONMENT.md` (SUPERSEDED by this
  document) — the predecessor draft; historical record of the fixture
  inventory (§3/§4), growth rule (§5), and test-carrier reasoning (§6) this
  contract does not restate.
- `docs/design/UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` (FROZEN) — §2
  module map, §4 pinned dependencies, §5 test carrier.
- `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` (FROZEN) — §3 table
  ownership, §5 `provenance_records`.
- `docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` (FROZEN) — §2
  LDAP bind, §4.1 `role:viewer`, §5 RBAC enforcement path.
- `docs/design/UI2_0_BASELINE_DIRECTORY.md` (FROZEN) — §3/§4 screen-state
  vocabulary and feature-contribution ordering.
- `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/DeviceRegistrationController.java`,
  `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/InventoryController.java`,
  `ui2/service/src/main/resources/db/migration/V1__initial_schema.sql` —
  the source this freeze read to correct §7's finding.
