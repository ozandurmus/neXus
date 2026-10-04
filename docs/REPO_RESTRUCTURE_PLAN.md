# neXus repository restructuring — Phase 1

Status: DRAFT — analysis and migration proposal, not an implementation contract.

Date: 2026-10-04. Baseline: `60013f22052f83120a64d6afa234b9149a2325a1`, branch `sa/repo-restructure-plan`.
Authorization: the Phase 1 PO brief permits only this document, `repo_inventory.csv`, and local commits. No move, deletion, configuration change, new command gate row, deployment, external consultation or remote Git operation is authorized by this plan. Project-state updates belong to the engineering session, not this lane.

## Decision and principal findings

Make `ui2/` unmistakably the product, preserve its module layout, and archive the retired Python product under `history/`. Separate the Python that operates Java delivery from the Python product. Do not archive directories solely because they contain Python or predate UI2.

The repository contains exactly **438 tracked Python files** at the baseline, from **3,495 tracked files**. `repo_inventory.csv` inventories every tracked Python file and every observed top-level entry, including hidden and local-only entries. The count is a Git-source inventory, not a recursive scan of installed dependencies, runtime stores or ignored caches. The CSV contains 511 unique rows: 438 Python files and 93 top-level entries (20 overlap), comprising 90 tracked top-level paths and three local-only metadata entries. Python classifications: 79 LIVE_TOOLING, 150 LEGACY_PRODUCT, 158 TEST_OF_LEGACY, 18 GENERATED/JUNK and 33 UNSURE.

Several proposed archive candidates are still live:

- `ui2/Containerfile:97–100` copies six project JSON files and archive JSON into the Java image and sets `UI2_PROJECT_PLAN_DIRECTORY`. `ProjectPlanReader.java` reads those files and searches ancestor directories for `project/` in development. **Moving all of `project/` breaks Java delivery and the Project Plan screen.** Keep its current location in this migration; separate historical records from active authority in a later, explicitly scoped task.
- `scripts/repository_privacy_check.py:11` imports `utils.repository_privacy`; `scripts/project_queue.py:851,906` imports `utils.project_plan`. `standalone_orchestrate.py` imports the orchestrator/provider modules and security summary helper. These transitive dependencies must survive.
- `.github/workflows/validation.yml` still installs Python product requirements, compiles the old product, runs its CLI privacy entry point, and discovers the complete Python suite. The workflow is not a Java-only delivery definition. A preparatory CI/test split is required before archiving code.
- `tests/` contains Java migration, command-gate, deployment, security, governance and tooling tests as well as legacy product tests. `roles/`, `config/`, `plugins/nexus-workbench-companion/`, `bin/` and `.claude/` serve active governance or optional Workbench tooling; they are not automatically legacy product.
- `docs/design/ui2_mockups/` is a Java-image build input (`ui2/Containerfile:35`). Age or placement under `docs/design/` does not make an asset archival.
- `AGENTS.md` still names Python taxonomy and failover invariants. A filesystem change cannot silently transfer authority to Java or retire these tests. The PO must settle that governance amendment before the affected files move.

The older `docs/design/LEGACY_PYTHON_SEPARATION_PLAN.md` is DRAFT, proposes a separate repository and broadly labels `plugins/` legacy. This brief chooses in-repository `history/`; current plugin registration and Java COPY evidence supersede those draft assumptions for this proposal. No source, contract status or historical outcome is changed here.

## Inventory contract and evidence

CSV columns: `path`, `top_level`, `python_file`, `tracked`, `kind`, `classification`, `proposed_destination`, `evidence_kind`, `references`, `notes`. A root Python file has both flags true and appears once. A mixed directory row never overrides its individual Python rows. References are baseline repository-relative `file:line` locations, separated with semicolons. Destinations are proposals, not an executable move manifest.

Classification meanings:

| Category | Meaning and handling |
| --- | --- |
| LIVE_TOOLING | Proven retained Java delivery, safety, governance, packaging or supporting-test dependency. The requested vocabulary has no LIVE_PRODUCT: `ui2/` uses this retention bucket with an explicit product note. |
| LEGACY_PRODUCT | Retired Python product/packaging/rendering code. Existing broad CI execution is a migration blocker, not evidence that Java imports it. |
| TEST_OF_LEGACY | Tests and fixtures for the retired Python product, including its HTML render harness. Archive after separating live coverage. |
| DOCS | Documentation/governance. Retain active authority and build assets; archive only individually retired material. |
| GENERATED/JUNK | Scratch, logs, replies, patches and ad hoc session material. Classification does not authorize deletion or imply privacy-safe contents. |
| UNSURE | Mixed ownership or no proven current execution chain. Keep in place until the stated consumer/owner check is complete. |

Evidence method: enumerate `git ls-files -z`; count `.py` suffixes; enumerate immediate filesystem entries without reading local metadata; search repository paths and inspect Python import statements, workflow commands, Docker/Containerfile COPY inputs, Gradle definitions, manifests, shell wrappers, agent instructions and systemd units. Follow imports rather than treating every script as live. Tests of retained tools count as live coverage: their imports/subject references and current workflow discovery establish the relationship; this does not claim a recent test invocation. Comments and runbook references establish a documentation dependency only. One-off consultation and measurement scripts remain UNSURE unless an active invocation is established.

Boundaries: no runtime directories, credential stores, root log/reply contents, installed dependency tree or Git object contents were inspected for inventory. No historical narrative was bulk-read. External cron, installed systemd units, local agent settings and memory pointers cannot be verified from this worktree: UNKNOWN, with follow-up below. No Graphify scan was run. `graphify-out/` was not observed as a tracked or top-level path in this checkout.

### Retained dependency groups

| Group | Current roots and proof | Proposed destination |
| --- | --- | --- |
| Delivery | `standalone_orchestrate.py` → `orchestrator.py`, `orchestrator_providers.py`; orchestrator → verify, usage, dashboard and transfer; `.githooks/pre-push` → `nexus_worker_prepush.py`; `.claude` hook settings → tool gates | `tools/delivery/`; retain native `.githooks/`, `.github/`, `.claude/` discovery locations |
| Build and integration | `hosta_deploy.sh` → `deploy/ui2-image-build/run_build.sh`; integration wrapper → `hosta_integration_remote.py` → `integration_runner.sh` and `IntegrationSummary.java` | `tools/delivery/`; keep k8s and host units in `deploy/` |
| E2E | standalone → `hosta_e2e.sh`, `hosta_preview_e2e.sh`; build runner and e2e wrapper → `hosta_e2e_image.py`; preview wrapper → `hosta_preview_e2e.py` | `tools/e2e/`; frontend Playwright sources stay under `ui2/frontend/e2e/` |
| Security | build runner/systemd → `security_host.py` → manifests/summary; manifests mount `security_run.py`, `security_scan.sh`, `security_dast.py`, summary; runbook → `mirror_security_images.py` | `tools/security/`; retain `security/baseline.yaml`, `deploy/security/` |
| Privacy | standalone and PO gate → `repository_privacy_check.py` → `utils/repository_privacy.py` | `tools/privacy/` plus dedicated live tests |
| Governance | CI → scope routing, queue and build-history index; queue → `utils/project_plan.py`; AGENTS → `utils/action_taxonomy.py`; transfer/relay consumers remain active | `tools/delivery/`; retain `project/` and active governance entry points pending separate authority decision |
| Optional Workbench | `bin/nexus-workbench-observer` → companion/MCP; companion → relay/usage; plugin registration and launchagent generator retain entry points | `tools/delivery/`, with explicit compatibility launchers where installed callers require them |
| Live Python tests | CI routing/privacy, Java taxonomy parity, SQL migration uniqueness/audit context, CP/PAN policy gate fixtures, deploy/e2e/security and governance tests | `tools/tests/`; preserve their fixture dependencies and minimal conftest |

`e2e_canary_digests.py`, `successor_index.py` and the launchagent generator have supporting test/registration evidence but no proven scheduled invocation in the inspected delivery chain. Retain as tooling; confirm operational ownership before removal. `ui2_extract_fixtures.py` imports legacy `support_bundle`, which imports completeness/logger/runtime-path helpers: keep the extractor UNSURE until the owner confirms whether it is still required. Do not accidentally preserve all collectors merely to support a retired extraction task.

## Target layout

```text
ui2/                       Java product, all existing modules and frontend unchanged
  service/ worker/ job-engine/ persistence/ scheduler/ platform-core/
  capability-registry/ ldap-adapter/ cli/ architecture-tests/ integration-tests/
  frontend/                React/TypeScript; generated assets packaged by service
  Containerfile gradlew gradle/ build.gradle.kts settings.gradle.kts

deploy/                    Java k3s manifests, build Jobs, host and security units
tools/
  delivery/                orchestration, governance, build/integration helpers
  security/                scanner pipeline and report projection
  e2e/                     host wrappers and offline canary helpers
  privacy/                 standalone gate and scanner implementation
  tests/                   retained Python tooling/Java-delivery tests and fixtures
  requirements-dev.txt     existing tooling test dependencies only; no new dependency
security/baseline.yaml     exact security acceptances stay at their current path
project/                   live Java plan projection and governance authorities retained
docs/
  README.md                Java-first index
  operations/ reference/   current product and delivery guidance
  design/                  active contracts and build-used assets, status preserved
history/
  README.md                archive purpose, baseline and offline-only warning
  application/ checkpoint/ panorama/ configuration/ console/ replay/ signal_intake/
  utils/ templates/ static/ migrations/ tests/ examples/
  main.py config.py requirements*.txt pytest.ini
  Dockerfile docker-compose*.yml
  scripts/ tools/render-harness/ docs/ scratch/
```

Root retains README, AGENTS, AI_HANDOVER and native Git/build configuration. Keep `AI_START_HERE.md`, `CLAUDE.md`, `PO.md`, `CURRENT_STATE.md`, `PRIVACY_AND_DATA_HANDLING.md`, `roles/`, `.github/`, `.githooks/`, `.claude/`, `bin/` and plugin registration until their discovery/authority consumers are explicitly migrated. Keep LICENSE/NOTICE if later supplied by the owner; neither is tracked at this baseline. Do not invent licensing terms. Retaining `project/` and `security/` is a deliberate evidence-based exception to the illustrative minimal-root layout, not an undocumented relocation.

The archive README should say: “This directory preserves the retired Python SecurityExpert/neXus product and its historical tests and documentation. The maintained product is `../ui2/`. These files are not part of the Java build or default test discovery. Historical device commands, approvals and deployment recipes do not authorize execution today. Use a separately approved, isolated environment for historical reproduction; no live credentials or runtime state belongs here.”

Tracked root junk initially moves to `history/scratch/` unless an exact file has an owner-approved deletion decision and no remaining caller. Add ignore rules for future scratch outputs, but retain tracked history. Untracked disposable outputs may be removed only after ownership/retention confirmation; `.git`, `.nexus` and `.standalone` are not junk. Moving a secret-bearing artifact into history does not sanitize it, and deletion from the current tree does not erase Git history.

## Move risk register and exact follow-up edits

| Risk / path | Required edit in the migration PR | Validation / stop condition |
| --- | --- | --- |
| `utils/repository_privacy.py`, standalone privacy script | Move scanner/entry point together; update import, root discovery (`parent.parent`), and exact scanner self-exemption currently at line 250. Replace `_safe_test_fixture` root-only test recognition with explicit retained and archived test roots, preserving only existing fixture semantics. Do not exempt all `history/`. | Scanner positive/negative fixtures at new paths; new finding still fails; archive remains scanned. |
| Privacy merge-base comparisons | `baseline_finding_keys`/`finding_key` and orchestrator/CI callers compare paths. Pure moves can appear new against the old baseline. Review each moved finding with old/new identity; do not auto-accept new findings or reset baseline to HEAD. Switch CI from `main.py` maintenance mode to the standalone gate with equivalent baseline support before removing the CLI. | Baseline and non-baseline privacy tests; preserve unavailable-baseline fail-closed behavior. |
| `.gitleaks.toml`, `security/baseline.yaml` | Keep both roots. Current seven path-scoped gitleaks exemptions target unchanged Java files, so no path rewrite is needed for those. For any other moved accepted finding, review its exact rule/path/line and update only that acceptance; preserve historical commit acceptances, owner and expiry. `security_summary.py` keys include tool/rule/location/commit. | Tree and full-history scans plus exact-match baseline tests; no wildcard archive exemption. |
| `security_scan.sh`, `security_manifests.py`, `security_run.py`, `security_host.py` | Rewrite mounted script sources and command paths from `scripts/` to `tools/security/`; keep full-tree Semgrep/Trivy filesystem and full Git-history gitleaks coverage. Trivy configuration target stays `deploy/`; review archived Docker/Compose inputs explicitly if they move outside it. Keep baseline lookup and source commit/image digest agreement. | Offline `test_security_scans.py`; complete real scanner gate later, not merely unit tests. |
| `.github/workflows/validation.yml`, `ci_regression_scope.py` | Replace legacy compileall/import roots and requirement installs; retain live Python tests explicitly; add closed mappings for `history/` and `tools/` changes without silently skipping security-sensitive paths. Replace legacy product smoke/render/state checks with separated equivalent live checks. Keep existing required job IDs. | Routing tests, workflow tests and every PR gate below. Existing `:discovery:unitTest` reference has no corresponding module in settings; correct routing when that branch is touched. |
| `tests/conftest.py`, `pytest.ini`, Python test imports | Extract retained fixtures/environment cleanup; live tests import tools from their new locations. Set default `testpaths = tools/tests` and `norecursedirs = history`; archive tests get separate opt-in configuration rooted in `history/`. Rewrite `parents[1]`, `sys.path` and `tests.*` imports. Move required synthetic fixtures with their tests. | Collected default node IDs contain no archived tests; retained tests run without legacy product imports. |
| `tests/test_architecture_convergence.py`, governance/privacy bridge tests | Split project-state checks from Python product invariants; port or retain each active invariant explicitly. Tests invoking `application.workflows.maintenance`/`main.py` must be switched before archiving those imports. Keep Java taxonomy parity until the PO changes its authority. | Equivalent invariant coverage and documented PO amendment; otherwise leave affected files in place. |
| `utils/action_taxonomy.py`, `AGENTS.md`, role/startup instructions | Keep taxonomy accessible until PO selects the authoritative successor and updates constitutional pointers. Amend reading order and command examples to new tooling roots; distinguish historical Python failover invariants from current Java behavior. Never freeze this draft or redefine network permissions through a move. | Governance/contract-reference/parity tests; no unapproved semantic change. |
| `project/`, `utils/project_plan.py`, Java `ProjectPlanReader` | Keep `project/` unchanged in this sequence. Move only the Python helper and update queue imports/root discovery. A future project relocation must update `ProjectPlanReader.defaultProjectDirectory`, `UI2_PROJECT_PLAN_DIRECTORY`, Containerfile COPY, archive resolution, frontend provenance fixtures and reader tests together. | ProjectPlanReader/ProjectPlanPanel tests and queue/index checks. |
| `deploy/ui2-image-build/run_build.sh:33,43`, `ui2/Containerfile:35,97–100` | Preserve project/deploy-info generation, `tar` context `ui2 project docs`, mockup COPY and archive JSON COPY. When old docs leave `docs/`, confirm only intended build assets remain; do not introduce `history/` into the product image. | Build context listing and container image build; no Python product/runtime in Java image. |
| Root `Dockerfile`, Compose, `.dockerignore`, `deploy/nginx/`, `deploy/secrets/` | Archive Python packaging together after proving no live consumer. Root `.dockerignore` excludes docs/tests and is not streamed by the current host build; do not apply it blindly to Java builds. Keep or replace it with an explicit Java-context policy that preserves required mockups/project inputs. | Build-context and compose reference review, no accidental secret inclusion. |
| All moved scripts: relative imports, `__file__`, `cwd`, subprocess paths | Rewrite literal callers/imports and root discovery per CSV evidence. Cross-group imports must resolve from root and arbitrary caller CWD using stdlib/package structure, not a new loader framework. Keep only bounded compatibility wrappers required by installed callers. | Existing tooling tests plus one synthetic smoke from root and another CWD. |
| `hosta_*`, `integration_runner.sh`, `IntegrationSummary.java` | Update standalone dispatch and every shell/Python wrapper; update `deploy/ui2-image-build/33-integration-postgres.yaml` and script ConfigMap inputs. Keep wrapper behavior, flags, failure propagation and image pinning. | `test_hosta_test_modes`, `test_faster_ship`, `test_preview_e2e`, deployment manifest tests. No host execution on this lane. |
| `deploy/security/security-source-refresh.service`, host backup units/timers | Rewrite security ExecStart path when tools move; host backup script remains under deploy. Inventory installed unit and any external cron references in a separately authorized host task before removing compatibility paths. No cron definition was observed in tracked deploy files; external state remains UNKNOWN. | Unit text tests now; operator-authorized installed-unit verification later. |
| `.githooks/pre-push`, `.claude/*.settings.json`, `.github` prompts, `config/` | Rewrite hook/tool command paths and PO allowlist/path sets; preserve deny behavior. If moving config to delivery tools, update model-price and write-scope readers and their tests. Native vendor discovery directories remain at root. | Hook installation/scope/tool-gate tests; no bypass via old or new path. |
| Workbench plugin, `bin/`, launchagent generator | Rewrite `run_module`, imports and generator release paths; retain native plugin manifest location. Installed launchagent/plugin paths need separate local-owner verification, not automatic regeneration in this plan. | Existing companion tests; optional integration remains UNKNOWN until inspected. |
| `roles/`, `relay/`, transfer/queue/index scripts | Do not archive active role or relay authority. Move closed relay records only after updating local-relay/orchestrator locators and append locks. Queue/history index output paths and relative links must change together with docs moves. | Relay/packet tests, queue check and history index check. |
| `docs/history/`, active `docs/design/`, `docs/ARCHITECTURE.md` | Retire individual legacy docs to `history/docs/`; keep active frozen contracts, command gates and successor index reachable. Update live links in AGENTS, runbooks, code/test contract references, build-history index generator and project references via their authorized writers. Preserve dated text; use a relocation index rather than rewriting outcomes. | Cross-reference/status tests; every live contract link resolves. |
| `tools/render-harness/`, `scripts/render_*`, `tests/fixtures/uitest/` | Archive as one legacy unit after CI split; update its own relative asset/test paths for optional offline reproduction. Keep Java frontend tests and Playwright e2e under UI2. | Java tests independent of archived harness; archived unit not in default discovery. |
| Scratch root scripts, logs/replies/patches | Check callers before move/delete, including tests: `_realenv_r06_coalesce_probe.py` is covered by `tests/test_phase0_6_1c_r06_coalesce_probe.py`. Preserve that relationship in history. Do not claim all scratch has no callers. | No dangling source/test reference; privacy review before archive. |
| `.gitignore`, graphify outputs, memory/handover pointers | Add scoped output ignores for new tool/cache roots and `graphify-out/` only if actually used; keep runtime secrets excluded. No graph rebuild. Engineering session updates current handover/state paths; external memories remain non-authoritative and require their owner's separate update. | Clean tracked diff; old paths resolve through relocation map or updated live caller. |

## Small, independently shippable PR sequence

All PRs are future work requiring their own authorization. Each must leave main usable, preserve a reversible move map, and pass the **same common gate**: Java `unitTest` + `architectureTest` + PostgreSQL integration tests in the approved container/integration environment; frontend TypeScript, full Vitest and build; retained Python tooling tests; repository privacy; full security source/history/image gate for the exact candidate commit/image; `git diff --check`. Required infrastructure unavailable means BLOCKED/UNVERIFIED, not green. A document-only PR may reuse an unchanged image only if the security pipeline explicitly proves the commit/context relationship; no assumed waiver.

| PR | Independently reviewable result | Approximate size / risk | Rollback and prerequisites |
| --- | --- | --- | --- |
| PR0 — separate live gates | Extract live Python test/gate dependencies, standalone baseline parity, default discovery and requirement split. Keep source locations initially; narrow CI to Java plus live tooling while retaining opt-in archive-test recipe. Obtain explicit governance decision for taxonomy/invariant succession before removing those dependencies. | 15–30 edited files; HIGH because gates/authority are involved. Split into two green commits/PRs if necessary. | Revert CI/fixture changes; keep legacy product intact until complete. |
| PR1 — root clutter | Inventory-backed move of tracked scratch into history/scratch, with exact deletions only when retention is decided. Move referenced tests with their subject or update their paths. Add targeted ignores. | About 35–45 paths, predominantly renames; LOW/MEDIUM. | Revert rename commit; no history rewrite. Depends on PR0 where legacy tests leave default CI. |
| PR2 — archive Python product | Move LEGACY_PRODUCT and TEST_OF_LEGACY rows plus their non-Python assets as a coherent tree; add history/README. Retain live utils slices and unresolved items. Archive legacy Docker/Compose/render units, not Java manifests. | Several hundred paths; roughly 300 Python files before mixed-file split, plus assets. MEDIUM/HIGH rename volume, minimal semantic diff. | Revert move/path-update commit; CI must already be independent. No source operation or new device command. |
| PR3 — group live tooling | Move retained scripts/helpers/tests to tools groups; update hooks, CI, mounted commands, wrappers, import/root discovery and optional Workbench launchers atomically. Keep security baseline and project roots. | About 100–150 paths including non-Python helpers and tests; HIGH path coupling. Can split privacy/security/delivery/e2e into independently green sub-PRs. | Compatibility entry points until installed callers are verified; revert group at a time. |
| PR4 — documentation archive/index | Move only retired docs, update live links and generated indexes with authorized tooling, add docs/README, refresh cold-start entry points. Preserve active FROZEN docs and mockups. | Hundreds of docs may qualify; stage by subject, about 50–100 paths per PR. MEDIUM, HIGH for authority pointers. | Relocation index and link checks; revert individual doc batches. No mass status rewrite. |
| PR5 — Java front page | Replace root README with the draft below, publish docs index, and prepare repository description/topics for a separately authorized settings update. Remove temporary wrappers only after caller verification, otherwise leave that to a later task. | 2–6 docs/settings changes; LOW. | Revert documentation/settings text. Settings update is not authorized by Phase 1. |

Preflight every PR against its actual base: inventory counts drift, and this document is not a freeze. Do not combine product behavior changes, database migrations, device commands or a microservices redesign with relocation. SQL files under `ui2/service/src/main/resources/db/migration/` and the command-gate fixture remain unchanged; root `migrations/` is the legacy Python schema, a different system.

## Proposed front-page README text

The following is actual proposed README content for PR5. It describes existing entry points until PR3 is merged; at publication substitute the approved new `tools/` paths from the move map. Commands are operator documentation, not authorization for this lane to run them.

````markdown
# neXus

neXus is a multi-vendor network-security evidence and operations platform.
The maintained product is **UI2: Java 21 with a React/TypeScript frontend**,
deployed as container workloads on Kubernetes/k3s.

**SEE → VERIFY → TRACE → RECOVER → OPERATE**

neXus brings inventory, configuration, compliance, policy visibility, encrypted
backups and controlled operations into one evidence-based workspace. Inventory
reports observed runtime facts; configuration reports configured state;
alignment compares intent with actual evidence. Missing evidence remains
UNKNOWN rather than becoming a guessed answer.

## Architecture

    Browser (React/TypeScript; AIView masked inspection)
        |
        v
    service — authentication, RBAC, API, packaged frontend
        |                       |
        v                       v
    job-engine + scheduler    persistence — PostgreSQL
        |
        v
    worker — capability registry — gated vendor transports
        |                              |
        v                              v
    parsed evidence                managed devices
        |
        v
    safe projections + encrypted artefact storage

The browser submits typed intent to the service. Device transport and command
authorization stay server-side. Build/deployment scripts are tooling, not the
product runtime.

## Repository and modules

- `ui2/service`: API, authentication/security wiring and frontend packaging.
- `ui2/worker`: vendor collection and controlled job execution.
- `ui2/job-engine`: job lifecycle and execution contracts.
- `ui2/persistence`: persistence adapters; SQL migrations live in service resources.
- `ui2/scheduler`: scheduling support.
- `ui2/platform-core`: shared platform types and boundaries.
- `ui2/capability-registry`: capability definitions and command-gate resolution.
- `ui2/ldap-adapter`, `ui2/cli`: directory integration and administrative CLI.
- `ui2/architecture-tests`, `ui2/integration-tests`: architecture and database gates.
- `ui2/frontend`: React application, unit tests and Playwright e2e.
- `deploy/`: workload manifests and controlled delivery definitions.
- `tools/`: Python/shell delivery, security, e2e and privacy tooling.
- `docs/`: current product documentation and active contracts.
- `history/`: retired Python product, tests and historical documents; excluded
  from the Java build and default test discovery.

The eleven Java modules are declared in `ui2/settings.gradle.kts`.
The frontend is an npm workspace, not an additional Gradle module.

## Build and test

Use the established development runtime and installed frontend dependencies:

```sh
cd ui2/frontend
npx tsc --noEmit -p .
npx vitest run
npm run build
```

Run Java in the approved build/integration container, from the repository root:

```sh
./ui2/gradlew --no-daemon --console=plain -p ui2 -PfrontendPrebuilt=true unitTest architectureTest
./ui2/gradlew --no-daemon --console=plain -p ui2 -PfrontendPrebuilt=true :service:bootJar
./ui2/gradlew --no-daemon --console=plain -p ui2 -PfrontendPrebuilt=true :integration-tests:integrationTest
```

`frontendPrebuilt=true` requires the frontend build output to be present.
Integration tests require the approved PostgreSQL test environment; inability
to connect is not a passing result. Run the standalone repository privacy gate
and retained tooling tests using the paths listed in the delivery runbook.

## Deploy

Deployment follows the reviewed `deploy/ui2/` manifests and
`deploy/ui2-image-build/` pipeline: build with kaniko, pass security gates,
roll out the approved image and verify with masked AIView e2e.
The existing delivery entry point is `scripts/hosta_deploy.sh`; integration
and e2e wrappers are `scripts/hosta_integration.sh` and `scripts/hosta_e2e.sh`.
Use their current runbook arguments and required authorization; deployment
credentials and endpoint values are supplied outside source control.
The frontend is served by Java; no long-running Node service is required.

See [documentation](docs/README.md) for build, deployment and operating guides.

## Security and privacy

All UI inspections and shared verification evidence use the `aiview` persona
(`role:replay_viewer`) and deterministic server-side pseudonyms. Secrets, raw
operational identities and sensitive vendor responses do not belong in Git,
logs, screenshots or chat. Inspect relationships and safe derived states.

RBAC, trusted transport policy, command gates, execution ledgers and encrypted
artefact storage enforce distinct boundaries. A ready state does not authorize
an operation; a successful request does not prove vendor semantics. Device
commands and writes remain subject to their exact approved contracts. AIView
may run approved diagnostic reads and readiness checks; it does not authorize
writes, failover execution, approvals or schedules.

Repository privacy checks and security scanning are required delivery gates.
See [AGENTS.md](AGENTS.md) and
[Privacy and data handling](PRIVACY_AND_DATA_HANDLING.md) for authoritative rules.

## Roadmap

The current queue is [project/QUEUE.md](project/QUEUE.md), with a concise
checkpoint in [CURRENT_STATE.md](CURRENT_STATE.md). Follow those records for
vendor validation, evidence quality, operational controls and delivery status.
Planned features and automated tests are not claims of live validation.
````

Publication checklist: replace draft tooling paths only after the matching migration ships; resolve the docs-index link; retain current product limitations from authoritative state, not stale rollout counts. No blanket claim that all vendor features, failover or restore are production-ready.

Proposed GitHub description:

> Multi-vendor network-security evidence and operations platform. Java 21, React/TypeScript and Kubernetes, with masked AIView inspection and gated execution.

Proposed topics: `network-security`, `network-automation`, `java`, `spring-boot`, `react`, `typescript`, `kubernetes`, `evidence`, `compliance`, `privacy`.

Proposed `docs/README.md` text:

> # neXus documentation
>
> Start with the root README for the Java product and module map. Read AGENTS.md and AI_START_HERE.md before engineering work. CURRENT_STATE.md and project/QUEUE.md own the current delivery picture.
>
> - Build and deployment: operations/UI2_LOCAL_KUBERNETES_DEPLOYMENT.md; deploy/ui2-image-build/ and deploy/security/README.md.
> - Verification: reference/E2E_SCREEN_TESTS.md and the approved integration runbook.
> - Security and privacy: ../PRIVACY_AND_DATA_HANDLING.md and AI_DEVELOPMENT_PROTOCOL.md.
> - Active contracts: design/DECISION_RECORD_SUCCESSOR_INDEX.md; follow each document's own status.
> - Archive: ../history/README.md. Archived Python instructions do not describe the current product.

Rewrite these prose-relative paths as working Markdown links when publishing; the mixed starting locations are intentional until PR4. Do not relocate active contract evidence just to shorten the index.

## Phase 1 verification and remaining uncertainty

- Inventory verification: PASS. A stdlib CSV check asserted unique rows, exact equality with all 438 Git-tracked Python paths and all 93 immediate entries, nonempty LIVE references, and existing reference files/line numbers. All source counts refer to the pinned baseline, excluding these two new documents. Reference existence is not proof of external invocation.
- Frontend: `npx tsc --noEmit -p .` passed. An initial `npx vitest run --cacheDir .cache/vitest` invocation failed because Vitest 2.1.9 rejects that option. Rerun `npx vitest run` passed **51 files / 490 tests** using the existing `.cache/vite` worktree configuration; `npm run build` passed. React act warnings were emitted; the e2e canary test reported the absent digest file. This is not a live e2e pass. Installed Node is 24 while package engines declare 22; no runtime/dependencies were changed.
- `python3 scripts/repository_privacy_check.py`: PASS, zero findings. `git diff --check`: PASS. Scope check: exactly the two requested new documents; no modified tracked source/state files. The staged diff is checked again before commit.
- No tests were added: Phase 1 changes only two documents. No product behavior or UI effect.
- Java/Gradle, image build, full security scanner gate, authenticated e2e, installed units and real-environment behavior were **NOT RUN / UNVERIFIED**: this lane forbids Gradle sandbox execution, host/network access and deployment. No Java task is specifically required for Phase 1. Future migration PRs must obtain the complete common gate above.
- UNSURE rows are deliberate stop points, particularly mixed governance tests, one-off reviews/measurements and the fixture extractor. A static inventory does not prove external callers are absent.
- No active state, queue, history record or handover was edited. The engineering session owns that closeout under the task-specific scope exception.
