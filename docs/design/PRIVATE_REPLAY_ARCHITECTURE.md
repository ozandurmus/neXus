# Agent-Operated Private Test Environment

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-08 — Phase A only.** Phase A
(offline typed exporter, `PRIVATE_REPLAY`, `SYNTHETIC_SCENARIO`, isolated
replay providers, agent-perspective isolation tests) is this repository's
implementation authority for that scope. **Phase B (`LIVE_PRIVATE` —
agent-mediated real collection) is explicitly NOT frozen and NOT authorized
by this document.** Its own text below already says so throughout
("deferred design only", "grants no authority to initiate device
commands", "Passing Phase A does not authorize Phase B") — this status
line does not relax that. Phase B requires its own separate design
iteration, its own explicit Product Owner authorization, and reconciliation
with `docs/AI_DEVELOPMENT_PROTOCOL.md`'s network-device command gate and
`utils/action_taxonomy.py` before any dispatch.

Authored as an external design collaboration between the Product Owner and
a separate Claude session (2026-09-08), reviewed and counter-reviewed by
this repository's own engineering/orchestrator session in the same
session, then revised to accept all three counter-review points (offline-first
sequencing; reuse of the existing command gate instead of a parallel one;
agent-tool-perspective adversarial isolation testing). Tracked in
`project/backlog.json` id `private_replay_agent_operated_test_environment`
(status `planned`, priority `P0` for Phase A slices 1-3 only, as of this
freeze).

No code has been written against this contract yet. The next step is an
`ARCHITECTURE`/`IMPLEMENTATION` movement scoped to delivery slice 1
(dependency inventory + typed privacy/fidelity policy + isolated replay
provider design), not a direct jump to the exporter.

---

Let a coding agent initiate and manage real collection and UI validation after the user supplies access through a protected local credential channel. The user does not run collection commands or manually execute the browser test plan. A trusted local execution service runs the existing approved application workflows; the agent receives only privacy-filtered progress, results and UI data. Preserve relationships and problem states without exposing the original runtime to the agent.

User clarification: agent-operated collection remains the eventual end-to-end objective. The 2026-09-08 counter-review changes delivery order: build `PRIVATE_REPLAY` and `SYNTHETIC_SCENARIO` first; defer `LIVE_PRIVATE` to a separate, later, explicitly authorized phase. The first phase delivers agent-operated UI/workflow testing without granting real-device-command initiation or access to production credentials/runtime. It does not claim to fulfill the eventual live-collection objective.

The proposal does not establish that a topology-preserving dataset is anonymous. Device counts, topology, versions, timing and unusual failures can reveal information even after identities are replaced. Offer a relationship-preserving private replay profile and a separately generated synthetic scenario profile. Prefer synthetic scenarios for broadly distributed regression fixtures.

## Source evidence inspected

Read-only inspection of the local neXus checkout on 2026-09-08; no claim about latest remote main.

- `utils/support_bundle.py`: `Tokenizer` provides HMAC-SHA256 pseudonyms. `_sanitize_dict` transforms selected key names and otherwise retains values. This is a useful primitive, not a complete export policy for arbitrary application data.
- `tests/test_phase0_3_support_bundle.py`: covers deterministic pseudonyms and exclusion of selected fixture identifiers; it does not establish privacy coverage for every UI surface.
- `utils/html_export.py`: `build_report_payloads` supplies eight payloads shared by the report and console. Its inputs include more than unified inventory, including configuration, lifecycle and capability state.
- `console/app.py`: separate jobs, job events, registry and enrollment surfaces exist. `create_app` can construct and start a real `ConsoleJobRunner` when one is not supplied. Masking `/api/payloads` alone would leave important surfaces uncovered.
- `tests/test_html_render_harness.py`: existing populated fixtures, topology assertions and browser navigation checks provide a starting point for wider replay testing.
- `docs/AI_DEVELOPMENT_PROTOCOL.md`, "Network-device command gate" and "Approval boundaries": existing command review and authorization requirements, including the ten required command-gate fields.
- `utils/action_taxonomy.py`: canonical action classification, permission and console-submission rules; no new private-mode taxonomy is proposed.
- `project/backlog.json`: counter-review and blocked/unprioritized status verified for `private_replay_agent_operated_test_environment`.

Only source, tests and project documentation were inspected. No runtime, live devices, credentials, support archives or raw evidence were accessed. No application files or project delivery state were changed by the drafting of this document. Tests were not executed for this design-only proposal.

## Phase A: offline execution model — first proposed delivery

```text
Existing already-collected snapshot, within the trusted data boundary
  -> offline typed exporter + privacy and relationship validation
  -> validated private replay package

Agent environment, with no production runtime or device access
  -> validated package or generated synthetic scenario
  -> isolated stores and simulated jobs
  -> existing UI, workflow tests and safe test evidence
```

Phase A introduces no live runner initiation, credential-entry service or live action mediator. The exporter handles sensitive input only within the trusted boundary; the agent must not obtain raw snapshot access to generate its own package. Package preparation may use existing authorized local procedures and previously collected data. The concrete trusted export handoff is part of the design work, not permission for an agent to read production runtime. Synthetic development and isolation testing can proceed with fabricated inputs. This phase removes live-device contact from its testing path; it still requires privacy validation and isolation of sensitive source artifacts.

Illustrative Phase A interface: `py main.py --replay <private-replay-package> --console`. It is not implemented. Do not expose a live-mode switch or silently fall back to production services in this delivery.

## Phase B: LIVE_PRIVATE execution model — deferred design only

Everything in this section, including credential entry, session handles and the live CLI, is a future design candidate. It is excluded from Phase A and grants no authority to initiate device commands. Its isolation prerequisites must be demonstrated with synthetic secrets and a dummy service before introducing real credentials or production connectivity.

```text
Agent orchestrator
  -> request protected user credential entry
  -> submit bounded typed collection intent

Trusted local execution service (outside agent filesystem/process access)
  -> existing approved credential and transport path
  -> existing approved collectors and parsers
    -> consistent completed/degraded run snapshot
    -> typed privacy export + relationship validation
    -> staged package + privacy/coverage checks
    -> atomic publication of private replay package

Agent-accessible isolated UI environment
  validated replay package
    -> isolated data stores + replay services
    -> existing payload builders, routes and UI
    -> browser interaction, safe downloads, test evidence
```

Collection and agent-facing UI are separate processes and trust boundaries. The agent starts and monitors preparation through typed intents and sanitized status events, then opens the UI after privacy validation succeeds. The user only supplies the credential challenge and any genuinely required scope authorization, not shell commands or routine test clicks. Collection logs must not be streamed into an agent-visible terminal transcript. Export failure must not open the ordinary production HTML as a fallback.

Illustrative future CLI, not commands currently implemented:

```text
py main.py --incognito
py main.py --incognito --render-only
py main.py --replay <private-replay-package> --console
```

Proposed semantics: the agent invokes the first command through a client of the trusted execution service; the service requests protected credential entry, runs the existing user-authorized collection scope and publishes a private UI dataset. The second exports from a coherent previously collected snapshot without contacting devices. The third runs only the imported replay environment. Parse replay mode before constructing production configuration, credentials or collection services. Conflicting live/replay flags fail at argument validation.

No username, password or HMAC key is passed on the command line or through the model transcript. A normal chat request for a password, or merely hiding terminal echo, does not provide the required boundary. Use a local credential form or broker-owned prompt excluded from agent screenshots, accessibility capture and tool outputs. Prefer ephemeral credential handling; do not add credential persistence by default. The execution service reuses the existing governed application credential/transport route, not a parallel diagnostic SSH path.

The agent receives an opaque session handle bound server-side to permitted operation types, selected targets, expiry, request budgets and cancellation. The handle grants no generic shell, arbitrary path, credential-read or arbitrary network capability. Production identities are resolved only inside the trusted service. Enforce scope and concurrency through the existing collection admission controls; never turn a UI test retry into an unbounded fleet recollection. The target policy must be known from existing configuration or a protected selection step; username/password alone cannot determine authorized scope.

### Reconcile with existing authority; do not create a parallel gate

`docs/AI_DEVELOPMENT_PROTOCOL.md` ("Network-device command gate" and "Approval boundaries") and `utils/action_taxonomy.py` remain authoritative. A session handle may enforce restrictions derived from an approved scope; possession of it, green readiness, or private-mode selection does not independently authorize an action.

Before Phase B dispatch, map each proposed typed intent to its existing workflow, action class, command approval evidence, target scope and admission controls. Identify where the existing command gate already covers the behavior and where a change needs review. Before adding/changing a device command, document the existing gate's ten fields: purpose, action class, vendor/platform/shell/context, timeout, retry, maximum frequency per endpoint, session reuse, unsupported behavior, secret-bearing output risk and safe telemetry. Apply the repository's existing exceptions as written; do not require a duplicate gate merely for unchanged, already-covered command behavior. Agent initiation and any new network-access pattern still require their own explicit reconciliation and authorization under the existing procedures.

The proposed initial live scope is existing permitted class 0 collection only, subject to that review. Class 1 recovery writes remain under their RB.x contracts and unavailable through the console or this new mediator. Classes 2-4 remain unavailable under the inspected taxonomy. The service must consume the canonical classification/refusal logic rather than maintain a competing allow/deny list. A frozen contract must explicitly reconcile the existing human-operated real-environment procedure before agent-initiated live collection is enabled.

## Three explicit execution modes

- `LIVE_PRIVATE` (Phase B, deferred): the agent triggers permitted real collection and reads privacy-filtered results only after separate authorization and isolation evidence. In-scope UI refresh/collection actions use the same mediator. Live action events and result publication cross the same privacy checks; live identity mappings never reach the browser.
- `PRIVATE_REPLAY`: the agent uses an immutable transformed dataset and simulated job providers with no production contact.
- `SYNTHETIC_SCENARIO`: the agent uses intentionally generated cases, including failures absent from the real environment.

Never silently switch between these modes. Each UI, job and test report identifies the mode. Live-private status is a privacy/execution label, not an override of evidence freshness, capability, authorization or action-class semantics.

## Privacy compiler

Use a versioned schema policy for every exported record and every output surface. Each field has an explicit treatment: retain an approved semantic value, replace identity, synthesize a typed value, derive a safe summary, or exclude secret material. Unclassified fields or schema versions block publication and report only schema path and classification, never the matched value.

| Data | Proposed treatment |
| --- | --- |
| Device, cluster, context, manager, object and principal identities | Consistent pseudonymous IDs and synthetic display labels |
| Credentials, tokens, private keys, session material | Omit; use synthetic presence/state representations where the UI needs them |
| IPs, networks, MACs, domains, serials and fingerprints | Typed replacements, with explicitly defined equality and relationship preservation |
| Free text, descriptions, paths, tags, errors and raw command/configuration text | Exclude by default; derive safe templates or use syntax-aware synthesis where needed |
| Roles, evidence states, capability states, findings and relationships | Preserve approved semantics, including missing, contradictory and unknown evidence |
| Times and histories | Coherent shifted timeline and replay clock; preserve ordering, age and expiry relationships |
| Counts, versions, capacities and topology | Explicit fidelity policy; retain only when needed and record the remaining disclosure |
| Artifact IDs, references and filenames | Rewrite consistently; regenerate hashes for sanitized content, never copy raw content fingerprints by default |

Sensitive strings may occur in dictionary keys, embedded JSON/XML, arrays, URLs and filenames, not just leaf values. A generic regular-expression pass is not the export contract. Leak scanning is defense in depth after typed transformation.

Raw configuration views require vendor-format-aware synthetic text or a declared unsupported surface. Never ship a raw configuration file with only hostnames substituted. A surface cannot be counted as covered by quietly removing its meaningful contents.

## Identity and topology

Separate machine identity from readable labels. Proposed labels include `cp-fw-1`, `cp-cluster-1`, `cp-cluster-1-member-1`, `cp-vsx-1`, `cp-vsx-1-vs-1` and `pan-fw-1`. Assign labels once in the export dataset; reuse them across menus, histories and downloads. Do not derive joins or security decisions from labels or infer an entity type from its old hostname.

Use an export-specific secret and domain-separated HMAC pseudonyms. The domain must represent the identity namespace, not whichever field name happens to carry it. Maintain an explicit registry of IDs and references, and check uniqueness and collisions before publication. Keep identities opaque; do not cast identifiers to integers or merge formatting variants.

Preserve only relationships established by the source model. Existing registry device IDs and evidence entity IDs remain distinct unless an explicit source relationship connects them. Missing or ambiguous mappings must remain missing or ambiguous. Local mapping material and the key never enter the replay package. A fresh export normally gets a fresh key; a deliberate multi-snapshot dataset may share one private mapping scope to preserve history.

Network transformation is a separate algorithm from identity HMAC. It must preserve the relationships required by the selected tests: same versus different address, subnet membership, prefix containment, default routes and next-hop/interface links. Do not independently randomize every IP. Address overlaps and context namespaces need an explicit policy. If the selected synthetic address space cannot represent a source relationship, fail or report reduced coverage; do not silently change the result.

## Replay runtime and UI coverage

Use the same product UI and, where inputs are covered, the same payload builders and local decision logic. Introduce explicit replay providers for evidence, registry, clock and job execution. Avoid scattering privacy conditionals through every menu. Do not implement a second set of normal product classification rules solely for the demo.

All replay state writes go to a disposable replay runtime. Replay startup must require replay providers; omission is an error, never a fallback to the current real runner. Preserve existing action-class restrictions and rejection behavior.

| Surface | Replay behavior |
| --- | --- |
| Overview, Devices, Configuration, Compliance and supported Operations views | Render and calculate from transformed evidence using covered production logic |
| Search, filters, sorting, detail tabs and navigation | Exercise ordinary UI code with stable synthetic values |
| Configuration/history comparisons and downloads | Resolve only sanitized artifacts and references |
| Collect now and refresh | Phase A: simulate the job lifecycle. Deferred Phase B only: submit an approved in-scope intent to the trusted execution service and publish sanitized real results |
| Enrollment/probe and registry changes | Phase A: use synthetic targets and isolated registry state. Any future live probe or registry mutation needs separate explicit scoping and review; it is not included in Phase A or inherited from collection access |
| Unsupported or prohibited operations | Preserve ordinary unavailable/refused behavior; do not enable them to make all menus look successful |
| Logs, errors, job events and exports | Emit only approved replay data and safe diagnostic templates |

The console remains authenticated and loopback-only. The agent environment must not mount the production runtime, credentials, key, original logs or mapping table, or inspect the trusted service's process memory. Deny direct outbound device/network access at the environment boundary; allow only the authenticated local console and, for live-private sessions, the bounded execution-service interface. Only the trusted service has the approved production connectivity. A CLI flag or different directory alone does not restrict an agent that still has access to the original filesystem. Use a separate account, container or VM with an enforced filesystem and network policy as appropriate to the host. Concrete isolation support on the deployment host must be verified before claiming this guarantee.

Do not allow arbitrary URL navigation or resource loading from replay values. Use an isolated browser profile/origin so production caches and storage cannot appear in the test session.

### Isolation tests from the agent's actual perspective

Threat model: a curious or exploratory LLM-driven coding agent with the actual Read, shell/Bash, environment-inspection, browser and other tools granted on the deployment host. An API-only malicious-client model is insufficient. Identify the real agent OS identity, tool execution identities, inherited permissions and available escalation paths; a container around the service does not suffice when agent tools can inspect the host or control that container.

Use synthetic canary credentials, identities, raw evidence and mappings in a dummy trusted service. From the same tool surfaces and permissions the deployed agent would receive, attempt reads via direct/absolute paths, parent traversal, symlinks, mounted shares and backups; inspect process arguments, environment, inherited descriptors and host-supported process/debug interfaces; inspect logs, temporary files, crash dumps, browser storage and credential-entry screenshots/accessibility output. Test direct device-like network access against controlled test endpoints, alternate interpreters/subprocesses and exposed host/container-control interfaces. Check restart, failure and cleanup paths as well as normal operation. Do not use real credentials or real customer evidence as adversarial test material.

Record tool/permission profile, attempted capability, expected denial and observed result without exposing matched values. Include positive controls proving the protected test material exists and the intended safe UI remains usable. An unavailable inspection mechanism is explicitly not tested, not a passing denial. Any successful protected read, capture or unauthorized connection fails the boundary, even if UI redaction and API malformed-request tests pass. Change the isolation arrangement and re-test; do not repair a filesystem escape merely by adding string masking.

Phase A must demonstrate that the agent cannot access protected export inputs. Phase B additionally requires this adversarial evidence on the actual deployment host/profile against the dummy service before real-device connectivity or real credential entry is enabled. Runtime/profile/tool-permission changes invalidate the relevant assurance and require revalidation. No such isolation test has been run or passed by this document edit.

## Package and scenarios

The package contains a versioned manifest, sanitized evidence/projections, sanitized artifacts needed by covered views, synthetic identity references, an initial replay state, a coverage report and privacy-validation results. It contains no executable scripts or production access configuration. Checksums prove package consistency, not anonymity. Validate import paths, sizes, schema versions and references before use; reject traversal and links outside the extraction root.

Keep transformed real-state reproduction separate from synthetic scenario overlays. The reproduction preserves actual observed `UNKNOWN`, `MISMATCH`, stale and failed states. Overlays add explicitly simulated cases absent from that run: unavailable peer, partial collection, timeout, trust mismatch, missing configuration, large tables and expired evidence. All outputs carry `PRIVATE REPLAY` or `SYNTHETIC SCENARIO` provenance distinct from production evidence, including screenshots and downloads.

## Acceptance gates

1. Every rendered module, API route, event stream, download and persisted replay artifact has a field policy and coverage owner. New unclassified surfaces fail coverage validation.
2. Local export checks compare against source identifiers without exposing them. Adversarial synthetic fixtures test secrets in text, paths, dictionary keys, encoded fields and exception messages. An unmapped sensitive value blocks publication.
3. Identity/reference checks preserve cardinality, equality, cluster membership, context boundaries and source inconsistencies. Compare approved derived outcomes before and after transformation; report only safe differences.
4. Phase A tests prove replay cannot construct a live runner, load production credentials, read protected export inputs/runtime paths or contact a device, including restart and malformed-request cases. The agent-perspective adversarial matrix above is mandatory, beyond API tests. Deferred Phase B requires pre-live host isolation evidence plus execution-service rejection of out-of-scope, expired, repeated-over-budget and arbitrary-command requests, and proof that credential entry cannot enter agent-visible capture or output.
5. Browser tests cover meaningful assertions per menu: populated states, error states, filters, sorting, details, history, downloads, job progress and enrollment/refusal behavior. Clicking each tab is a starting point, not complete functional coverage.
6. Existing relevant tests and the HTML render harness pass. Apply full regression and repository privacy checks according to the scope of the eventual implementation.
7. Phase A reports agent-executed UI validation, not human sign-off or real-device validation. Replay tests never discharge outstanding live transport, identity-trust or vendor-command validation debt. Deferred Phase B, only after existing-gate reconciliation and explicit authorization, must record real contact, scope, safe evidence relationships and actual result provenance. Only the specifically exercised criteria may receive that evidence. This document does not amend the human-operated real-environment procedure.

## Delivery slices

1. Phase A design: inventory UI/API/artifact dependencies; define typed privacy/fidelity policies, trusted offline export handoff, package format, isolated replay providers and agent-perspective isolation tests. Resolve coverage gaps before scoping dispatch. No live-access contract amendment is needed to begin this offline design.
2. Phase A exporter: build the offline typed exporter, package validator and relationship checks using synthetic inputs first, extending appropriate support-bundle primitives. Validate the trusted handoff before private real-derived packages enter the agent environment. No credential service or live runner initiation is included.
3. Phase A workflows: implement isolated console providers and deterministic synthetic job/enrollment scenarios. Expand the existing render harness into a route/action/state coverage matrix. Demonstrate privacy and agent-perspective isolation; report offline capabilities and limitations explicitly.
4. Phase B prerequisite design, separately prioritized: reconcile LIVE_PRIVATE intents with the existing command gate, taxonomy, admission and real-environment procedures; prove isolation on the target host using dummy services and canaries. Passing Phase A does not authorize Phase B.
5. Phase B implementation/validation, separately authorized only after prerequisite evidence: protected credential entry, bounded live mediation and in-scope live UI actions. Do not bundle these into the Phase A exporter movement.

The backlog item `private_replay_agent_operated_test_environment` carries this freeze forward as priority `P0`, scoped to delivery slice 1 as the next dispatchable movement. Slices 2-3 follow once slice 1's contract/inventory is itself reviewed. Slices 4-5 (Phase B) stay a separate, later, separately-authorized item -- not implied by this freeze.

## Counter-review disposition — 2026-09-08

All three pushbacks from this repository's engineering/orchestrator session were accepted: offline-first sequencing replaces the previously bundled live/exporter slice; existing repository command/authorization rules are explicitly reused rather than paralleled; and actual agent-tool filesystem/process/environment/capture attacks are required isolation tests, not just API-malformed-request tests.

## Privacy reference

NIST SP 800-188 describes de-identification, pseudonymization, quasi-identifiers and residual re-identification risk: https://csrc.nist.gov/pubs/sp/800/188/final. Applying that principle to network topology is an architectural inference in this proposal, not a claim that NIST assessed this application.
