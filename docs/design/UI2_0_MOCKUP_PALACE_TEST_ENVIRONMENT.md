# UI 2.0 — mockup-palace synthetic test environment (design)

## Status

**SUPERSEDED — 2026-09-15**, by
`docs/design/UI2_0_C8_MOCKUP_PALACE_TEST_ENVIRONMENT_CONTRACT.md` (FROZEN —
PRODUCT OWNER APPROVED, 2026-09-15), which resolves every item in §11 below
and freezes the scope this document only proposed. This document remains as
historical record of the fixture-inventory and mapping work (§3/§4); it is
not authority for any clause `C8` restates, corrects or resolves — read `C8`
first. One correction `C8` makes that this document's own §4.5/§10 did not
have: `cp_inventory_projection` has no current controller/service/repository
read path anywhere in `ui2/service/src/main/java` (confirmed by a targeted
grep during the freeze review), so it is not, in fact, "the one seedable Java
slice" this document's §4.5 called it — `C8` narrows the frozen v1 seed scope
accordingly. Everything below this notice is the original draft, unmodified.

**Original status (superseded).** DRAFT. Written under `docs/design/UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md`
(FROZEN) §5's test-harness carrier and `docs/design/UI2_0_BASELINE_CONTRACT.md`
(FROZEN) / `docs/design/UI2_0_BASELINE_DIRECTORY.md` (FROZEN) for the Java UI 2.0
schema, RBAC and screen-state facts this document maps against. This document
freezes nothing and authorizes no `ui2/` implementation
(`UI2_0_BASELINE_CONTRACT.md` acceptance sentence A-1, applied here by
analogy: accepting this draft does not permit building any of §7–§10 below).
Every genuinely new decision it surfaces is listed in §11 as `UNKNOWN`, not
silently resolved (`AGENTS.md` "UNKNOWN / fail-closed law", "Contract-status
law").

**Explicitly not Private Replay.** `docs/design/PRIVATE_REPLAY_ARCHITECTURE.md`
answers a different question — an agent-operated, privacy-filtered replay of
**real** collected evidence. This document's whole source is already-synthetic,
hand-authored fixture data (`tests/fixtures/uitest/`) that contains no
production or anonymized evidence of any kind; it does not use, extend, or
depend on Private Replay's role, filter, or storage model, and no clause below
should be read as proposing to.

## 1. Purpose and scope

**In scope:** designing a Java UI 2.0 local test environment — seeded from the
existing Python `uitest` fixture bundle's semantics, not its bytes — that a
human can browse read-only to eyeball rendered screens, the same purpose
`scripts/render_uitest.py` and `tests/test_con1_operator_console_read_only.py`
already serve for Line-1. This document inventories the fixture bundle,
proposes a Java mapping per field group with an explicit classification, and
defines the shape of a test carrier, a local browsing endpoint, a readonly
identity, and a visual acceptance matrix — each to the depth the workflow's
own B0-8 "schema-level, not full implementation design" discipline uses
(`UI2_0_BASELINE_DIRECTORY.md` §1 method 3).

**Out of scope, and not authorized by this document:** any `ui2/` source
change, any migration, any test-container launch, any production/k3s
configuration, any production data, anonymization, the Private Replay role,
any device/credential/host access, and any canonical state file
(`project/*.json`, `CURRENT_STATE.md`) — identical to this movement's own
`.nexus/WORKER.md` "Files you must not touch" list, restated here so this
document is self-contained for a reader who has not seen the dispatch.

## 2. Why a mapping document, not a ported fixture

`UI2_0_BASELINE_CONTRACT.md` §1 direction 3 ("Reference, not reuse"): Line-1
fixtures are read to produce sanitized test data; file layouts and page shapes
are not ported. Applied here: `tests/fixtures/uitest/*.json` is read as a
**semantic inventory** of what a populated UI should show, never copied
byte-for-byte into a Java seed, and never treated as a data-export source
(`.nexus/approved_task.json` requirement 1). The central finding this
document exists to state plainly, because it is easy to miss by only reading
the Python bundle's own README: **most of the Python bundle has no Java UI 2.0
persisted, API, or UI counterpart to seed into yet.** `UI2_0_C1_PLATFORM_
SCHEMA_CONTRACT.md` §3.1's ownership matrix — the complete list of tables that
exist at freeze time — names exactly one capability evidence table,
`cp_inventory_projection`, carrying two nullable derived facts
(`product_version`, `ha_state`). No table owns configuration/alignment,
crypto posture, discovery, or compliance state. This follows directly from
`UI2_0_BASELINE_CONTRACT.md`'s `FIRST-CAPABILITY` decision (CP inventory
narrow subset — `show version`, HA state — is the only capability accepted so
far) and `UI2_0_BASELINE_DIRECTORY.md` §4.5's ordering invariant (a capability
is not a screen entry, alarm source, or evidence table until it reaches
`CAP-RELEASED`). A mockup-palace design that silently assumed otherwise would
invent Java UI 2.0 vendor/schema truth the platform contracts do not yet
grant — exactly what `AGENTS.md` "Vendor semantics law" and "Identity law"
forbid.

## 3. Source inventory — the Python `uitest` fixture bundle

Every file in `tests/fixtures/uitest/`, as it exists today (per
`tests/fixtures/uitest/README.md` and `tests/fixtures/uitest/build_fixture.py`):

| File | What it is | Feeds (Line-1) |
| --- | --- | --- |
| `unified.json` | Hand-authored device/interface/route/cluster topology matrix: CP standalone, ClusterXL (2 members), VSX host + virtual systems, VSX cluster + shared virtual system, one UNAVAILABLE gateway; PAN single, HA pair, multi-vsys, multi-vsys HA pair; plus two frontend-rendering-boundary (XSS-payload) device names | Network Inventory + Overview |
| `configuration_ui.json` | Per-device current-configuration sections, alignment findings across the full classification set, history/change-state, fleet rollup, backup-not-configured banner | Configuration module; `build_compliance_posture` input |
| `crypto_ui.json` | Per-subject crypto findings (PASS/FINDING/UNKNOWN) across `weak_algorithm`/`crypto_agility`/`pqc_readiness`, evidence-basis vocabulary | Compliance crypto card |
| `discovery_ui.json` | Per-entity discovery lifecycle/shell/planned-mode/plan-allowed rows, coordinator jobs, scheduler workflows | Discovery module |
| `state/compliance_checks.json` | A user-defined compliance check pack | Compliance module (enforced/advisory checks) |
| `state/control_assignments.json` | One WAIVED control | Compliance module (waivers) |
| `state/compliance_history.json` | Three historical compliance snapshots | 0.7.5 trend sparkline |
| `state/inventory_exclusions.json` | Three excluded identities | Exclusions module |
| `cp_config_telemetry.json` | Two VSX-cluster devices' `entity_id`/`ha_role`/`ha_cluster_mode` | injected as `checkpoint_config_result` into `run_html_export` |

`scripts/render_uitest.py` injects `configuration_ui.json`, `crypto_ui.json`
and `discovery_ui.json` as three monkeypatched builder return values (their
real inputs — collector telemetry, PAN XML on disk, live stores — are out of
a UI-render check's scope) and runs `build_compliance_posture`,
`build_project_plan_payload`, the template fill, and `_script_json` for real.
`unified.json` and `state/inventory_exclusions.json` are read for real, not
injected. `tests/test_con1_operator_console_read_only.py`'s
`uitest_runtime_paths` fixture reuses the identical pattern to boot the
read-only operator console (CON.1) against the same bundle — the closest
existing precedent to what §8/§9 below propose for Java.

## 4. Fixture-to-Java mapping, by file, with classification

Classification vocabulary (fixed by this document, reused throughout):

- **DIRECT** — a Java column exists today and a Python field maps onto it
  with no invented semantics.
- **SYNTHESIZED** — a Java column exists today, but no Python field supplies
  it; new test-only values must be authored directly against the Java schema.
- **SYNTHESIZED-ADJACENT** — a Java column exists today and a Python field is
  semantically close, but the field name/vocabulary differs and exact
  equivalence is not proven by any frozen contract (flagged, not assumed).
- **UNAVAILABLE** — no Java table, capability, or screen owns this concern
  yet; nothing to seed until the owning capability reaches `CAP-RELEASED`
  (§4.1/§4.5 of `UI2_0_BASELINE_DIRECTORY.md`).

### 4.1 `unified.json`

| Python field group | Java target | Classification |
| --- | --- | --- |
| `device` (name) | `devices` — used only as a human-readable label input to a seed script; `devices.device_id` itself must stay an opaque, application-generated `TEXT` value per `C1` §3.2's identity-law note, never the device name itself | SYNTHESIZED (the *value* carried over; the *identifier discipline* is new) |
| `source` (`cp`/`vsx`/`panorama`) | `devices.vendor_hint` | DIRECT |
| management/interface address (e.g. PAN `management_ip`) | `endpoints.address_ref` | DIRECT, with the CLASS 2 handling `C1` §3.2 already requires (never copied into `audit_log` free text) |
| `interfaces`, `routes` | none | UNAVAILABLE — no capability's evidence table carries interface/route facts yet |
| `cluster`, `cluster_topology`, `vsys`, `vs_id` | none | UNAVAILABLE — VSX/ClusterXL multiplicity is a schema concern `UI2_0_BASELINE_DIRECTORY.md` §2.3 assigns to `C1` §3 / `C4` §2/§4 in principle, but no table exists to carry it today |
| `inventory_status` (`fresh`, `data_state`, `availability_state`, `collected_at`, `stale_reason`) | partially: `cp_inventory_projection.collected_at`; completeness/staleness itself is `provenance_records`' concern (`C1` §5) | SYNTHESIZED-ADJACENT — this document does not re-derive `provenance_records`' exact column shape; a mockup-palace seed loader must read `C1` §5 directly, not infer it from this table |
| the two XSS-payload device names | none currently — no Java rendering surface exists to prove the same frontend-boundary property against yet | UNAVAILABLE (see §12 follow-up item 4) |

### 4.2 `configuration_ui.json`, `crypto_ui.json`, `discovery_ui.json`

**All three files, in full: UNAVAILABLE.** No Java table or capability owns
configuration/alignment, crypto posture, or discovery today (§2 above). Two
narrow field-level correspondences are worth recording precisely, because a
later capability's seed data will want them and a future reader should not
have to re-derive this from scratch:

- `configuration_ui.json` devices' `sw_version` (e.g. `"R81.20"`,
  `"11.1.3"`) is the closest existing Python field to
  `cp_inventory_projection.product_version` — SYNTHESIZED-ADJACENT, same
  caveat as above (no frozen capability spec yet fixes the exact string
  format `product_version` expects).
- `cp_config_telemetry.json`'s per-device `ha_role` (`"ACTIVE"`) is the
  closest existing Python field to `cp_inventory_projection.ha_state` —
  SYNTHESIZED-ADJACENT, same caveat: the column is untyped `TEXT` today and
  no enum vocabulary is frozen for it.

Everything else in these three files (alignment findings, classification
counts, crypto rule packs, discovery lifecycle/coordinator/scheduler state)
has no Java target at all.

### 4.3 `state/compliance_checks.json`, `state/control_assignments.json`, `state/compliance_history.json`, `state/inventory_exclusions.json`

**All four: UNAVAILABLE.** No persisted-state table for compliance packs,
waivers, history, or exclusions exists in `C1` §3's ownership matrix.

### 4.4 `cp_config_telemetry.json`

Covered in §4.2 (the `ha_role`/`ha_cluster_mode` correspondence). No other
field.

### 4.5 Net result

The entire Python bundle's "topology matrix" — the property the bundle's own
README calls its central design goal ("every device shape and UI branch") —
has exactly one seedable Java slice today: `devices`, `endpoints`,
`credential_references` (identity placeholders, `B1-4b`), `jobs`/`job_steps`
(identity columns only), `audit_log`, `provenance_records`, and
`cp_inventory_projection`'s two nullable facts. This is not a defect in this
document's mapping — it is an accurate report of where the Java platform
actually is (`UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §6: "no product
screens" exists yet either). A mockup-palace design that promised a
topology-matrix-scale seed today would be presenting a capability the
platform does not have, which `AGENTS.md` "Evidence laws" (collection success
!= semantic correctness) and "UNKNOWN / fail-closed law" both forbid by
extension.

## 5. Growth rule (mirrors the Python bundle's own)

`tests/fixtures/uitest/README.md`'s own growth rule: "when a build adds or
changes a `configuration_ui`/`compliance_overview`/`crypto`/`discovery`
payload field, or a UI module/tab, extend the matching fixture here in the
same change." This document adopts the identical discipline for the Java
side, tied to `UI2_0_BASELINE_DIRECTORY.md` §4's feature-contribution
contract rather than restated independently: **when a capability's Integrate
movement (§4.1, `CAP-RELEASED`) ships a new evidence table, screen entry, or
job type, the mockup-palace seed loader (§7) is extended in the same PR** —
otherwise the environment keeps rendering a stale slice of the product while
looking green, the same failure mode the Python README names for the
`0.7.4a` dead-button bug. This rule is stated now so the follow-up
implementation slice (§12) does not have to re-derive it.

## 6. Test carrier

**Reused, not reinvented.** `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §5
already fixes the carrier-resolution rule for any database-backed Java test:
an external real PostgreSQL 16 server via `UI2_TEST_JDBC_URL`, otherwise a
Testcontainers `PostgreSQL` container; absent either, the test **fails**,
never skips. This document proposes the mockup-palace environment resolve
its carrier through the identical mechanism and module boundary
(`integration-tests`) — no second carrier concept, no second environment
variable. What is new is layered on top, not underneath: a distinct seed
dataset (§7) applied after Flyway migration, using the same `ui2_migrate`
DSN sequencing §5 rule 3 already specifies, and only then exposing the
`ui2_app` connection the local browsing endpoint (§8) actually serves from.
PostgreSQL 16 remains the only proving version, unchanged.

## 7. Seed loader (mechanism sketch, not a build target)

A seed loader is a Java component, run once per mockup-palace database
instance after Flyway migration, that inserts rows directly into the tables
§4 names — **never** through the real SSH/PAN-API transport adapters, and
never through the job engine's actual execution path. This is the direct
Java analogue of `render_uitest.py`'s own `_injected_builders` pattern (three
builder functions rebound to return fixture payloads instead of running the
real collector-backed builders) — same idea, ported as a principle per
`RUNTIME-DIRECTION`, not as code. Two invariants this document fixes now
because §9/§10 depend on them:

1. **No transport class is ever constructed.** The seed loader writes
   `devices`/`endpoints`/`cp_inventory_projection`/`jobs`/`job_steps`/
   `audit_log`/`provenance_records` rows with synthesized values (§4); it
   never instantiates an SSH client, a PAN API client, or any class capable
   of a real network call. This is the property §9 requires be proven
   structurally, mirroring `Ui2ArchitectureTest`'s own `dir1`–`dir10`
   discipline (`UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §3: "each rule
   must be proved to fail" — a deliberately injected transport-construction
   violation must trip the check, not merely be absent from today's code).
2. **A seeded row is indistinguishable in shape from a real one, never
   flagged with a bypass column.** The seed loader produces ordinary rows
   through ordinary schema; there is no `is_mockup_data` escape hatch on a
   product table, because a schema-level bypass column is exactly the kind
   of "administrative test bypass" `.nexus/WORKER.md`'s invariants forbid by
   analogy for identity. Whether the *database itself* is provably a
   mockup-palace instance is a separate, connection-level concern — §8.

**Genuinely new, left open (§11):** the exact seed-loader module location
(a new `integration-tests`-adjacent Gradle source set, or a `cli` subcommand
per `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §2's `cli` module), and
whether `jobs`/`job_steps` rows are seeded already-`COMPLETED` (simplest,
but `C2`'s lifecycle columns are that contract's own, not sketched here) or
left absent entirely for the current thin slice.

## 8. Local browsing endpoint and isolation

**Isolation goal, restated from `.nexus/WORKER.md`'s invariants:** the
environment must prove, not merely claim, that it never becomes a path to a
production database or a real device.

Proposed shape, modeled on the *pattern* Line-1's CON.1 console already
proves out (`tests/test_con1_operator_console_read_only.py`, read as
precedent per this movement's own permitted-files list — not ported as
code, per `RUNTIME-DIRECTION`):

- The `service` module's HTTP listener binds to `127.0.0.1` only, under a
  dedicated Spring profile (working name `mockup-palace`; final name is
  §11's own open item) distinct from any profile a real deployment activates.
- **A connection-time guard, not a schema column (§7 item 2).** Before
  serving any request, the service verifies the connected database is
  provably a mockup-palace instance — the exact mechanism (a dedicated
  Flyway-managed marker table separate from any product table, versus a
  database/role naming convention checked at startup) is left open (§11);
  the requirement this document fixes is that **the check happens once, at
  startup, against the live connection**, and refuses to start rather than
  serving any payload if it cannot prove the database is a seed instance —
  the same fail-closed posture `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md`
  §5 rule 2 already uses for a missing test carrier ("must never skip, pass,
  or report success").
- **No device transports initialize — proven, not asserted.** An
  architecture-level test (same `ClassFileImporter`-over-bytecode technique
  `Ui2ArchitectureTest` already uses, extended with one more rule) asserts
  that no class reachable from the `mockup-palace` profile's Spring wiring
  graph is, or transitively constructs, the SSH transport adapter or any
  vendor API client class. This is the direct answer to
  `.nexus/approved_task.json` requirement 3's "how the local browsing
  endpoint... proves no device transports initialize" — a bytecode-level
  proof, not a documentation claim, exactly the standard §3 of
  `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` already set for `DIR-1`
  through `DIR-9`.
- A launch task (CLI entry point, exact shape open — §11) starts the
  profile, prints the local URL and a one-time session token in the same
  spirit as `console/auth.py generate_launch_token`'s pattern (never logged,
  §9's session is bound to `role:viewer` only) — again pattern-reference,
  not code reuse.

## 9. Readonly identity

**No new role, no bypass.** `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md`
§4.1 already defines `role:viewer`: "read every projection UI 2.0 ships; read
own audit rows" and explicitly cannot "submit any job or intent." This is
exactly the readonly, no-write/no-collection-authority identity
`.nexus/WORKER.md`'s invariants require, and using it is not introducing an
"administrative test bypass" — it is the least-privileged real role the
platform already defines, used as designed.

**What is genuinely open.** `C3` binds authentication to an LDAP directory
(§2 of that contract) via a real bind, and `role_bindings` resolve through AD
group references (§4.2). A mockup-palace instance must not depend on a real
corporate directory (that would violate the "no host/device/credential
access" invariant by another name — a live LDAP bind is itself an external
credentialed dependency). Two shapes are visible and neither is decided here:

1. An **embedded, in-memory test directory** seeded with exactly one
   synthetic operator DN bound only to `role:viewer`, using UnboundID — the
   same LDAP library `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §4 already
   pins as a dependency, so this would not add a new library, only a new use
   of an existing one, scoped to the `mockup-palace` profile.
2. A **profile-scoped stub identity provider** that never contacts LDAP at
   all for this one profile.

Option 1 keeps `C3`'s real bind-and-resolve code path exercised (closer to
what B1-9's device workspace will actually run against in production);
option 2 is simpler but exercises less of the real authentication path. This
document does not choose between them — see §11 — but fixes the boundary
condition either option must satisfy: **the resulting session is bound to
`role:viewer` and nothing else, through the same `role_bindings`/RBAC
enforcement path `C3` §5 specifies, never through a separate "test mode"
authorization check.**

## 10. Visual acceptance matrix

Scoped honestly to what §4.5 says is actually seedable today, not to the
Python bundle's topology-matrix ambition. Per `UI2_0_B1_01A_PLATFORM_
SKELETON_CONTRACT.md` §6, no Java product screen exists at draft time
either (`frontend` workspace and build tasks exist; no product screens) —
so this matrix names the dimensions a `role:viewer` read-only walk should
cover **once** a device workspace (`B1-9`) and a jobs screen (`B1-7`) exist,
not a runnable test today:

| Dimension | What the walk checks |
| --- | --- |
| Device list renders | at least one CP-vendor and, once a second capability ships, one PAN-vendor `devices` row renders with its `vendor_hint` |
| CP inventory tab | a device with a populated `cp_inventory_projection` row shows `product_version`/`ha_state`; a device with a `NULL` fact renders `UNKNOWN`, never a blank or an invented value (`AGENTS.md` UNKNOWN/fail-closed law) |
| Job/audit trail | a seeded `jobs`/`audit_log` row (if §7's open item chooses to seed one) renders in whatever job/audit screen exists, with `actor_fingerprint` shown as the opaque fingerprint, never a resolved identity (`C3` §3.2, `AGENTS.md` "Sensitive identity reporting law") |
| RBAC visible-but-refused | every write-capable action affordance a `role:viewer` session encounters renders visible, disabled, and labelled with `C3` §5.2's exact `reason_code` (`actor_not_in_required_group` or `role_token_unbound`) — never silently absent from the payload (`C3` §8 acceptance criterion 3, `UI2_0_BASELINE_DIRECTORY.md` §8 criterion 3) |
| Zero console errors | mirrors `tests/test_con1_operator_console_read_only.py`'s AC-1 pattern (Playwright walk, assert no browser console error) — reused as a **pattern**, not the CON.1 test itself, since UI 2.0 has its own frontend runtime |
| No mutating route reachable | mirrors CON.1 AC-2 — a `role:viewer` session's own route surface is asserted GET/HEAD-only end to end, stronger than CON.1's "the console has exactly one mutating route" because this environment's one identity submits nothing at all |

**What this matrix deliberately does not yet include:** ClusterXL/VSX
multiplicity, configuration alignment, crypto posture, discovery, or
compliance screens — all UNAVAILABLE per §4.2/§4.3. Each is added to this
matrix by the same capability's own Integrate movement, per §5's growth
rule, never assumed in advance.

## 11. Open decisions (`UNKNOWN`, not decided by this document)

1. **Seed-loader module location and job/audit seeding depth** (§7). Left to
   a follow-up movement; this document fixes only the two invariants of §7.
2. **Database-is-a-seed-instance guard mechanism** (§8) — marker table vs.
   naming convention vs. another mechanism. This document fixes only that
   the check must happen at connection time and fail closed.
3. **`mockup-palace` Spring profile name and launch-task shape** (§8) —
   working names only; not frozen.
4. **Test-only LDAP fixture: embedded directory vs. stub identity provider**
   (§9, options 1/2). Genuinely undecided; both satisfy the `role:viewer`-
   only boundary condition §9 fixes.
5. **Exact `provenance_records` mapping for `inventory_status`** (§4.1) —
   this document does not re-derive `C1` §5's schema; a follow-up movement
   must read it directly before the seed loader writes a `provenance_records`
   row.
6. **`product_version`/`ha_state` string vocabulary** (§4.2) — no capability
   spec fixes the exact values `cp_inventory_projection` expects yet; the
   seed loader cannot invent a canonical vocabulary the capability spec
   itself has not published.
7. **Whether a frontend-rendering-boundary (XSS-payload) device name case
   belongs in the Java seed once a rendering surface exists** (§4.1,
   §12 item 4) — noted, not decided.

**Frozen contracts this design already leans on, named so a follow-up
movement does not have to re-derive them:** `UI2_0_B1_01A_PLATFORM_SKELETON_
CONTRACT.md` §5 (test carrier), `UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §3
(table ownership), `UI2_0_C2_JOB_EXECUTION_CONTRACT.md` (job lifecycle,
referenced not re-specified), `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md`
§4/§5 (`role:viewer`, visible-but-refused), `UI2_0_C4_CAPABILITY_REGISTRY_
GATE_RESOLUTION_CONTRACT.md` (capability maturity gating), `UI2_0_BASELINE_
DIRECTORY.md` §3/§4 (screen-state vocabulary, feature-contribution
ordering).

## 12. Follow-up implementation slice

Not authorized by this document (§1); named so a Product Owner review has a
concrete next movement to approve or amend, in dependency order:

1. **Mockup-palace seed-loader contract.** A short DRAFT contract fixing
   §11 item 1, scoped to the current thin slice (§4.5) only.
2. **Test-only identity fixture contract.** Resolves §11 item 4, scoped to
   `role:viewer` only — no other role is in scope for a readonly browsing
   environment.
3. **Local launch profile and connection guard contract.** Resolves §11
   items 2/3, including the architecture-test rule §8 names (no transport
   class reachable from the profile's wiring graph).
4. **Frontend-rendering-boundary parity, deferred.** Once `B1-9`/`B1-7` ship
   a real rendering surface, revisit whether the two XSS-payload device
   names (§3, `unified.json`) need a Java-side equivalent case — out of
   scope until a rendering surface exists to test.
5. **Per-capability seed extension, ongoing.** Every future capability's
   Integrate movement extends the seed loader per §5's growth rule; this is
   not a one-time follow-up but a standing discipline this document
   establishes.

None of items 1–3 may proceed to implementation without its own frozen
contract, per `AGENTS.md` "Mandatory build lifecycle" (a new test-only seed/
identity/isolation boundary is exactly the kind of new semantics the
constitution requires a `CONTRACT` step for before `IMPLEMENT`).

## 13. Cross-references

- `docs/design/UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` (FROZEN) — §5 test
  carrier, §3 architecture-test discipline, §2 module map.
- `docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN) — §1 direction 3
  ("Reference, not reuse"), `FIRST-CAPABILITY` row, `VISUAL-DESIGN-LANGUAGE`
  row, acceptance sentence A-1.
- `docs/design/UI2_0_BASELINE_DIRECTORY.md` (FROZEN) — §3 screen-state
  vocabulary, §4 feature-contribution contract and ordering invariant.
- `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` — §3 table ownership
  matrix, §5 provenance records (open item, §11).
- `docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` — job lifecycle, referenced
  not re-specified.
- `docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` — §4.1
  `role:viewer`, §5 visible-but-refused, §2 LDAP bind (open item, §11).
- `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` —
  capability maturity gating.
- `tests/fixtures/uitest/README.md`, `tests/fixtures/uitest/build_fixture.py`
  — the source inventory (§3/§4).
- `scripts/render_uitest.py`, `tests/test_con1_operator_console_read_only.py`
  — the Line-1 patterns this document references (never ports) for local
  read-only browsing (§7, §8, §10).
- `docs/design/PRIVATE_REPLAY_ARCHITECTURE.md` — explicitly not used or
  depended on by this document (Status, above).
