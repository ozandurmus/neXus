# UI 2.0 — council review record and second-opinion brief

## Status

**REVIEW RECORD — not a decision, not implementation authority.** Produced
2026-09-09 in the Product Owner's engineering session (the standing
in-session PO precedent) as the second step of
`docs/design/UI2_0_ARCHITECTURE_DESIGN.md` §14: PO review → **this record and
an external second opinion** → a `DECIDE` episode on the freeze. It exists so
the external reviewer ("Astra") and the later `DECIDE` episode start from one
page: what the product is for, what was decided, what an independent
reasoning pass and five council seats would refuse, and which questions only
the Product Owner can answer.

Document under review: `docs/design/UI2_0_ARCHITECTURE_DESIGN.md` (DRAFT,
PR #160, movement `UI2_0_ARCHITECTURE_DESIGN_FABLE_HIGH`, relay/NXS-LOCAL-0047),
successor to `docs/design/UI2_0_ARCHITECTURE_REQUIREMENTS.md` (PR #140).

### Council round — honest disclosure (`GOV_PO_ROLE_MIGRATION.md` §7 step 5)

- Triggers held: (a) eight explicit contradictions with frozen/constitutional
  authority (`UA-1`…`UA-8`); (b) a freeze candidate introducing identity,
  credential, session, storage-schema and write (class 1 backup) boundaries;
  (c) size — the candidate bundles four separable products.
- Seats launched in one message, each as a fresh-context, read-only
  `nexus-council-seat` agent with its own brief and no access to this
  session's conversation: **Security Reviewer** (mandatory under (b)),
  **Senior Python Architect**, **Check Point / VSX Engineer**, **DevSecOps /
  Platform Engineer**, **UI/UX Product Designer**, **Technical Product
  Owner**.
- Five seats returned complete three-table answers. The **Technical Product
  Owner seat failed** mid-review on an API session-rate limit and was **not
  re-run** (credit discipline, Product Owner direction); its lens is covered
  only by the single-author reasoning in §3 and is marked as such.
- All seats and this session ran on the same model family (Claude); **no
  cross-model independence is claimed**. The council was invoked from the
  engineering session on the Product Owner's explicit in-session direction,
  not from a `nexus-po` episode — a recorded deviation from the skill's
  invocation rule, not a precedent.
- Transcript evidence: the seat transcripts live in this session's task
  output directory (`…/3c7f5012-77e3-42c4-8144-ee31065afac8/tasks/`), not in
  the repository; each seat listed every instruction it received verbatim so
  isolation can be audited from those files.
- §3 is a single-author self-critique written **before** any seat returned,
  and is described as such.

---

## 1. Purpose — what we are building and why

Today's console (`py main.py --console`) is an authenticated wrapper over
collection modules built to produce a static HTML/JSON export. Under real use
it broke (content on first load, breakage when a job ran) because that is
the shape it has: six unconditional `run_html_export` tail calls, eight
`*_ui.py` payload builders as the only read model, `output_root` files as
the inter-stage medium (`UI2_0_ARCHITECTURE_REQUIREMENTS.md` §2). It is a
demonstration surface.

The Product Owner's product goal is a **production-grade operator console a
real network security team runs daily**: onboard devices (discovery and
manual), pull configuration and inventory automatically after onboarding, run
compliance checks against a chosen benchmark, manage backups the way the team
already knows from Backbox (profiles, schedules, history, drift), watch
failover readiness, and trust every number on the screen. UI 2.0 is that
product's first real definition, and the Product Owner has judged it worth a
large, deliberate, **incremental** commitment: a separate application, a new
language stack (Java, corporate requirement), a real multi-admin
authentication model, and a real backup subsystem — rather than a patch on
the static-export console.

The bar every design choice is measured against (`UI2_0_ARCHITECTURE_DESIGN.md`
§1): *would a real operator trust and rely on this daily?* — honest state
always (`UNKNOWN` is an answer), actions safe by construction, no surprise
device contact, multi-operator by design, the Backbox operating model under
this product's evidence/identity/taxonomy laws, and every migrated feature
carrying its proof with it.

## 2. What we planned — the decisions as they stand

| id | Decision (Product Owner, 2026-09-09) | Where |
| --- | --- | --- |
| Operating model | **Two parallel tracks.** Line-1 (the existing Python feature line: M-series, PCP, CE, RB, OP) keeps shipping, indefinitely; UI 2.0 is a separate track. A feature is ported into UI 2.0 only after it is mature in Line-1, one feature at a time, never stop-the-world | design §3 |
| `C-1` | UI 2.0 is a **separate shell/application** with its own typed read API; `CON.0` §6 amended to "both surfaces read the same persisted projections", §3 bundler rule narrowed to the shared report bundle (replacement text drafted, `UA-1`/`UA-2`) | §4 |
| `C-2` | **RBAC is `D1`/`D7`, never menu-hiding**: every entry renders, every action shows its authorization outcome, the server refuses. Role model: action → role token (repo) → role binding (encrypted DB row) → AD group reference | §5 |
| `C-3` | **Backbox-style profile-based backup**: a profile is a versioned, four-eyes-approved ordered step sequence with expect-style gates; works for any device reachable over SSH/SFTP, including vendors with no native collector; device onboarding stays in the Registry; no static built-in backup method. Browser: state/readiness/history, profile selection, target selection, schedule intent. Execution: class 1 under the `RB.x` contracts, scheduler/CLI only. Phase 1 authoring = CLI/file import; Phase 2 browser editor needs an `AGENTS.md` amendment (`UA-5`) | §6 |
| `C-4` | **Concurrent multi-admin, single active session per identity** (takeover/refuse), LDAP/AD instead of OIDC, successor to `DEPLOY.1A`'s auth shape; `M14`'s decisions carried or amended one by one | §7 |
| `U-1` | **Full Java stack** for UI 2.0, reached per feature (F2 projected → F3 intent-routed → F4 shadow → F5 authoritative); Python stays the worker until a feature reaches F5. New features are written in Python now; conversion begins when UI 2.0 development starts | §3.2, §8 |
| `X-2` | **PostgreSQL** frozen as production storage engine; SQLite local; CAS/recovery blobs on the volume; **Oracle deferred, not rejected** (corporate default; revisit if mandated) | §9, `PCP_STORAGE_ENGINE_DECISION.md` |

Where Line-1 stands (2026-09-09): 22 PRs merged since 2026-09-08 (#140–#161)
including the requirements and design documents, CE.2 primitives (#154),
PCP.8 runbooks (#159), the DLP-scanner fixes (main's full regression is now
3182 passed / 27 skipped / 1 environment-only failure), credential profiles
(#146), event-signal intake slice 1 (#145), Postgres migrations + DML-only
role (#149). The **OP.1 failover plan compiler contract was FROZEN** today
(`op_degraded_verdict` = Option A) and its implementation slice, the
`cphaprob tablestat` evidence source and RB.5 (recovery readiness projection
+ UI module) are dispatched. A `P0` security finding is open and scheduled by
the Product Owner, not fixed: the older PAN runtime path sends credentials as
URL query parameters (`PAN_AUTH_TRANSPORT_CONVERGENCE_AUDIT.md`).

---

## 3. Independent reasoning (single author, this session, written before the seats returned)

**Keep as written:** §1's bar; the F0→F5 ladder with F4 shadow and "device
contact never doubles"; RBAC as `D1`/`D7` with three levels of indirection;
the closed step-kind expect engine with discard-raw, four-eyes, immutable
versions and run-by-reference; the schedule-edit intent as the strongest
browser action, refuse-never-clamp, enable-requires-connect-check; the
single-session table; "the Python test suite becomes the fixture table of the
Java port" plus a re-earned real-environment `MATCH`; the zero-cost Oracle
portability rules.

**Concerns raised on my own:**

1. **Freeze size.** Four separable products are bundled (shell/read API +
   projections; auth/sessions/RBAC; backup engine; Java stack + migration
   mechanics). One freeze with eight amendments is trigger (c) itself.
   Recommend two freezes: the *UI 2.0 platform contract* (§3, §4, §5, §7, §8,
   §9) and the *backup profile engine contract* (§6), which §14 already names
   as a separate `CONTRACT` movement.
2. **"Back up now" is absent from the browser.** For a Backbox-modelled
   product this is the most visible gap. The design's own §6.7 makes a
   schedule-edit the strongest browser action; a one-shot schedule is
   semantically "run now" executed by the scheduler — either a legitimate
   `D7`-gated, audited, mandatory-reason "run once" intent, or a loophole
   around "class 1 never on an HTTP surface". Decide explicitly.
3. **Phase 1 authoring is CLI/file only.** The Product Owner's stated need
   is authoring profiles for unknown devices *in the product*. Decide `UA-5`
   now, not after the skeleton.
4. **F1 entry = real-environment validated.** Correct, but CE.2, RB.3b and
   tablestat cannot enter UI 2.0 until the Product Owner runs hardware;
   choose the first UI 2.0 screens from features that already qualify
   (Registry/enrollment, HA readiness derivation, inventory/config
   projections).
5. **`main.py --worker` (F3) is an unscheduled Line-1 component** and the
   linchpin of the parallel tracks; it should be the first Line-1 `CONTRACT`
   after the freeze.
6. **Two privacy/posture decisions are taken inside the design** (persisted
   encrypted directory group references; a long-lived read-only directory
   service account). Defensible for a multi-admin server; the Product Owner
   must accept them explicitly against corporate directory policy.
7. **Oracle.** If the corporate mandate is real, jOOQ's Oracle dialect is a
   commercial licence; confirm with the DBA/procurement before the skeleton.
8. **Repository shape.** Same repository (`ui2/`), shared fixtures, one relay
   process, one privacy gate — at the cost of a second toolchain in CI.
9. **`DEPLOY.1` is real.** No server exists; UI 2.0's server shape must pass
   the `DEPLOY.1`/`1A` gates. If a server is found, the §14 skeleton can
   start on Docker there immediately.
10. **Two LDAP designs.** Resolve the parked `M14`/`M14L` question as a
    product question: keep `M14L` as a local-console design only, do not
    implement it before UI 2.0 §7; avoid two LDAP stacks.

---

## 4. Council synthesis

### 4.1 Supported consensus (two or more seats, or one seat plus §3, on the same point)

| id | Consensus | Held by |
| --- | --- | --- |
| K-1 | §5 (gate chain in one interceptor; `E3` never re-evaluated in `E4`; visible-but-refused actions; no client-side role concept) is sound as written | SR, UX, §3 |
| K-2 | "Back up now" absent from the browser is taxonomy-correct, but the *presentation* is unresolved: either a declared class 1 action rendered refused (today's console pattern) or no control with explanatory copy — a permanently refused control for every role breeds refusal fatigue | UX-D2, SR-C3, §3-2 |
| K-3 | §6.3's illustrative CP profile is **not** the frozen RB.3b sequence and must not ship as one: it invents `show backup status`/polling, discovers the artifact by listing and deletes that name (the exact §7.8 prohibition), uses pipes, and defines preconditions the engine (§7.7) must own. The shipped CP profile must be the collector's frozen tuple and order, or every non-gated string marked `UNKNOWN` | CP-D1…D4, SR-D2 |
| K-4 | `gate_review_ref` presence is not enough: the ref must resolve to a source-committed gate registry entry, step class must be derived from that record (never author-declared), the write-marker denylist applies to every `send`. Phase 2 browser authoring is unacceptable until this holds | SR-D1, CP-C3/CP-D1 |
| K-5 | F3's "`job_submissions` row (M4 shape, in PostgreSQL)" names a table that does not exist (M4 `job_submissions` is SQLite-only; the Postgres job table is `console_job`); `main.py --worker` does not exist; both need a named Line-1 contract + `DEV.4.6` migration before any F3 | PA-D2/D3, DO-D1, §3-5 |
| K-6 | Infrastructure the design assumes is not there: no Postgres service in any compose file, root image, no driver in the image, no `DEPLOY.1` server; two DDL owners (Python runner + Flyway) and Python startup `_ensure_schema` still present; the Python DSN is read from the plain environment without TLS. Record these as freeze preconditions/`§12` rows, not acceptance gates | DO-D2/D3/D4, §3-9 |
| K-7 | "Encrypted at rest under the application role's key" has no key source, custody or rotation; joins `recovery_offhost_key_custody` | SR-D8, DO-D8 |
| K-8 | "Same jar, three roles" weakens the frozen rule that only the worker holds device credentials: per-component secret sets, DB roles and volume mounts must be stated | SR-D9, DO-D7 |
| K-9 | Persisted encrypted group references and a read-only directory service account are corporate-policy decisions the Product Owner must accept explicitly (`UNKNOWN` from the repository) | SR-Q3, DO-Q3, §3-6 |
| K-10 | Same repository recommended; consequences: which CI job runs the JVM suite, privacy-gate suffix extension (`.java/.kts/.gradle/.ts/.sql/.properties`), a dependency-approval packet for ~9 new dependency families, and a rule that additive schema changes are minor (no cross-track gate) | PA-D7/Q1, DO-D5/D6/Q1, §3-8 |
| K-11 | The first F2 slice must use a concern with an existing PostgreSQL seam (or a new projection such as HA readiness); the Device Registry backend raises on `postgres` today and is a separately gated `PCP` §10 movement — otherwise the first UI 2.0 slice blocks on Line-1 storage work (the hidden stop-the-world) | PA-D4, §3-4 |

### 4.2 Material dissent left unresolved (with the seat that holds it)

| id | Seat | Claim | Resolves if |
| --- | --- | --- | --- |
| SR-D4 | Security Reviewer | Server-mode enrollment (`role:onboarding_admin`) contradicts frozen `CON.0` §4.1/§7 rule 11 ("server mode remains blocked until `DEPLOY.1A` supplies real OIDC/RBAC") — not listed among `UA-1`…`UA-8` | Add `UA-9` with replacement text, or a PO ruling that LDAP-group RBAC satisfies the `DEPLOY.1A` condition |
| SR-D3 | Security Reviewer | A class 1 profile can reference the full-privilege collection credential | Credential profiles typed by class (D4 backup-class); validation and `E7` refuse a non-backup credential on any `recovery-write` step |
| SR-D5 | Security Reviewer | `security_admin` can self-grant by binding a token to a group they belong to; no bootstrap path for the first binding | Four-eyes on binding create/revoke; refuse when the actor is in the target group; CLI-only bootstrap |
| SR-D6 | Security Reviewer | Scheduler-originated class 1 runs have no named actor for `E4` re-evaluation; a departed editor's schedule is the unattended-contact escalation `C-D7` feared | Define the actor (last schedule editor) and the lapse outcome (schedule auto-disabled, surfaced) |
| SR-D7 | Security Reviewer | Binding as the operator from a network-reachable login form is a failed-bind amplifier (AD lockout, username enumeration) | Per-source throttle before any bind; uniform error; no application-side per-identity lockout; documented AD lockout interaction |
| SR-D10 | Security Reviewer | 12-hex unkeyed fingerprint is reversible from a DN list yet not attributable by `security_admin` | PO decision: attributable (display name resolved at read time) or pseudonymous |
| CP-D3 | Check Point | `finally` delete with `record_and_continue` has no ineligibility latch; RB.3b AC-3 requires `CLEANUP_FAILED` to block the endpoint until cleared | Ledger outcomes `completed/failed/cleanup_failed` on `BackupRun`; `cleanup_failed` blocks at `E7`; write-dependent `finally` skipped when the write was never sent |
| CP-D5 | Check Point | No "no retry" for `recovery-write`; `role:operator` "retry" does not exclude class 1 | Engine contract with the three ledger rules; validator refuses retry on `recovery-write` |
| CP-D6 | Check Point | §6 is silent on VSX/ClusterXL: VS-scoped `device_id` must be refused; each cluster member is its own endpoint/artifact; Spark/Gaia Embedded unsupported by lifecycle classification | Assignment-time and `E7` refusals; artifact per member |
| CP-D7 | Check Point | A prompt-driven interactive shell (`reads until the prompt regex`) is a **new network-access pattern**; the validated CP transport is `exec_command` per channel and the Expert prompt shape is `UNKNOWN` from repository evidence | Decide exec-per-step (connect gate = auth + banner), or gate an interactive-shell transport with real-environment prompt evidence |
| CP-D8 | Check Point | `host_key_policy` as profile data could carry a non-strict value | Server-owned; validator accepts only `strict_known_hosts` |
| PA-D1 | Python Architect | "Device contact never doubles" (F4) has no mechanism: only `cp-config`/`recovery-*` accept targets; the coordinator enforces concurrency, not frequency; a Java shadow run cannot "replace" a plane-wide Python run on pilot targets | F4 entry for device-contacting features names the `PCP.6` seam for that collector as a precondition plus the routing component and the one-implementation-per-endpoint-per-window invariant |
| PA-D5 | Python Architect | Only part of a Python test suite is table-exportable (CE.2: 12 registration cases of 29; the rest are `monkeypatch` over call structure); "33 tests" is not derivable | Classify each test as exportable / re-expressed / not-portable; F5 proof lists re-expressed ones as Java-native tests |
| PA-D6 | Python Architect | `AG-J11`'s parity "triple" cites `AC-CS-39`, which names only two fields; the resolver is wired into neither `html_export` nor the console today, so the three-surface parity test has no Python side | Cite the clause defining `evidence_presentation` parity or narrow to the pair; make `AG-J11` conditional on the Line-1 movement that wires the resolver |
| UX-D1 | UI/UX | Two different `STALE`/`PARTIAL` meanings on one backup screen (readiness state vs evidence-age qualifier) with no distinct labels/copy | §6.8 names both labels and copy; an `AC-CS-27`-style gate for the backup screens |
| UX-D3 | UI/UX | Takeover/refuse asked up-front on every login; `SESSION_SUPERSEDED` delivered as a 401 on "the next request" may hit a background fetch in a SPA and lose a half-typed reason | Ask only after a 409 conflict; render supersession as a blocking full-page state with time and audit reference |
| UX-D4 | UI/UX | During F2/F3 coexistence an operator can see two `collected_at` values for one device (today's console reads `output_root`, UI 2.0 reads Postgres) | Coexistence rule: once a feature is F2+, today's module reads the same projection or is retired; every module shows an "as of / source" stamp |
| UX-D5 | UI/UX | Backup overview and Profiles have no named navigation root (`PO-NAV-2` promotion trigger for the reserved Recovery root) | §6.8 names the root per surface against `NAVIGATION_INFORMATION_ARCHITECTURE.md` §4.3/§6.4 |

Not dissented by any seat (sound as written): §5.1, §5.3, §6.4, §6.5, §6.6 rule
structure, §6.7, §6.10 Phase 1, §6.11 defaults, §7.4, §8.4, §9 portability
rules, §8.3 migration/packaging/build rows.

### 4.3 Questions only the Product Owner can answer (deduplicated)

1. **Freeze slicing.** One freeze, or two (platform contract vs backup
   profile engine contract)? (§3-1)
2. **"Back up now".** (a) declared class 1 action rendered refused, (b) no
   control with explanatory copy, or (c) a `D7`-gated, audited, mandatory-
   reason "run once" intent — and is (c) a taxonomy amendment? (UX-Q2, §3-2)
3. **Phase 2 browser profile editor.** Amend `AGENTS.md` (`UA-5`) now, only
   as *composition* from a source-committed command-gate registry (SR-Q4), or
   not at all?
4. **Shipped CP profile.** Literally the RB.3b collector's frozen sequence
   executed by the engine, or a re-gated re-expression? (CP-Q1)
5. **Operator-authored profiles for unknown platforms.** May they contain any
   `recovery-write` step before a gate record exists, or class 0 only?
   (CP-Q2)
6. **Transport.** Is a prompt-driven interactive SSH shell approved as a new
   network-access pattern, or is exec-per-step the rule? (CP-Q4)
7. **Server-mode enrollment vs `CON.0` §4.1.** Does LDAP-group RBAC satisfy
   the `DEPLOY.1A` precondition, or is `UA-9` required? (SR-Q1)
8. **Audit attribution.** Attributable (names resolvable by
   `security_admin`) or pseudonymous (fingerprint only)? (SR-Q2)
9. **Directory posture.** Accept persisted encrypted group references and a
   long-lived read-only directory service account under corporate directory
   policy? (SR-Q3, DO-Q3, §3-6)
10. **Scheduler actor.** Who is the actor of a scheduler-originated class 1
    run for `E4`, and what happens when their membership lapses? (SR-Q5)
11. **Key custody** for `role_bindings`/`actor_authz_state` encryption —
    where does it live? (SR-Q6, DO-D8)
12. **Repository and CI.** Same repository (`ui2/`)? Does the JVM suite run
    in `validate` on every PR, or under the "no automatic full regression"
    policy? (PA-Q1, DO-Q1)
13. **Schema ownership.** Which single tool owns the shared PostgreSQL
    schema (Python runner or Flyway), and is retiring Python startup DDL a
    precondition? (DO-Q2)
14. **Sequencing authority.** Do Line-1 movements that exist only to serve
    UI 2.0 (worker mode, registry Postgres backend, projection writers,
    conditional `run_html_export`) compete with the M-series, and who owns
    the order? (PA-Q2)
15. **First F4 candidates.** Offline-only (HA readiness, resolver) first, or
    device-contacting features that must wait for `PCP.6`? (PA-Q3)
16. **Skeleton timing.** Wait for `DEPLOY.1` server arrival, or build against
    local Testcontainers until then? (DO-Q5, §3-9)
17. **Platform facts** (`UNKNOWN` from the repository): distroless JDK 21 in
    the corporate registry; Testcontainers/Docker on CI runners; Gradle vs
    Maven standard; jOOQ Oracle licence acceptability. (DO-Q4, U-J1…U-J3)
18. **Login UX.** Takeover/refuse on every login or only after a conflict;
    retire today's console module per feature as soon as its UI 2.0 module
    ships (to avoid two freshness stamps)? (UX-Q1, UX-Q4)
19. **Backbox references.** Will screenshots arrive before the §6 `CONTRACT`
    movement, or do the §6.11 defaults stand for it? (UX-Q5)
20. **`M14` disposition.** Keep `M14L` as a local-console design only, not
    implemented before UI 2.0 §7? (§3-10)

---

## 5. Questions for the external second opinion (Astra)

Context to give: §1 (purpose), §2 (decisions), the design document itself,
and this record. Asked specifically, in order of leverage:

1. **Stack.** Spring Boot 3 + jOOQ + Flyway + UnboundID + Apache MINA SSHD +
   TypeScript SPA + Gradle for a corporate Java estate with Oracle as the
   default database: would you change any row of design §8.3, and is there a
   reason to prefer JPA for portability despite the auditability argument?
2. **Expect engine.** Design §6.3's closed step kinds (`connect`/`exec`/
   `poll`/`sftp_get`/`verify`/`finally`) with per-step regex gates and no
   branching: sufficient for the Backbox operating model, or does Backbox
   rely on behaviours this model forbids (interactive prompts, conditional
   branches, pagination handling)? Exec-per-step vs an interactive shell?
3. **Migration ladder.** F4 "shadow" where the Java executor *replaces* the
   Python run on pilot targets and parity is a fingerprint `MATCH`: sound, or
   is a dual-run comparison safer despite the device-contact budget cost?
4. **Sessions.** Single-active-session-per-identity with takeover/refuse:
   standard practice or friction for a 3-admin team? Asked at login or after
   conflict?
5. **Freeze shape.** Would you freeze the platform contract and the backup
   engine contract separately, and in which order would you ship the first
   two screens?
6. **Anything the council missed** that would make a daily operator distrust
   the product.

---

## 6. Recommended path to the freeze (for the `DECIDE` episode)

1. Product Owner answers §4.3 items 1–9 first (they change the document's
   text); items 10–20 can be answered in the `DECIDE` episode.
2. Amend `UI2_0_ARCHITECTURE_DESIGN.md` accordingly: replace the §6.3 sample
   with the RB.3b sequence (K-3), add the gate-registry resolution rule
   (K-4), name the real job table and the worker contract (K-5), add the
   infrastructure/preconditions rows (K-6), key custody (K-7), per-component
   secrets (K-8), `UA-9` or the PO ruling (SR-D4), the VSX/ClusterXL rules
   (CP-D6), the transport decision (CP-D7), and the UX items (UX-D1…D5).
3. Freeze in two decisions: the **UI 2.0 platform contract** (§3, §4, §5,
   §7, §8, §9 with `UA-1`…`UA-3`, `UA-6`…`UA-9`) and the **backup profile
   engine contract** (§6 with `UA-4`, `UA-5` Phase 2 decided).
4. First engineering movements after the platform freeze, in order: a Line-1
   `CONTRACT` for `main.py --worker` + the job table (K-5); the UI 2.0
   skeleton (service, login, sessions, role bindings, gate chain) against
   Testcontainers or the `DEPLOY.1` server if it exists; the first F2 on a
   concern with an existing Postgres seam (K-11).

Tier for the `DECIDE` episode and both `CONTRACT` movements: `Sonnet 5,
extended thinking (high)`. This record required no higher tier than the
design it reviews.
