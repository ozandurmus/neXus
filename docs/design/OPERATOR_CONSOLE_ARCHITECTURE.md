# Operator Console Architecture — local control plane (`CON.x`)

**Status:** ARCHITECTURE FROZEN 2026-08-31. Design record only — no source file
changed by the session that produced it.
**Scope:** a second, authenticated, action-capable delivery surface for the
existing engine. Local (loopback) first; the same application becomes the
server control plane only behind the `DEPLOY.1` gates.
**Does not authorize:** any new device command, any device write beyond the
already-approved `D3` pilot, a public/network-exposed listener, credentials in
a browser, or production deployment.

Track: **`CON.x` — Operator Console**, phases `CON.0` (this document) through
`CON.5`. Per-phase contracts live in `docs/history/phase/CON_*.md`; this
document is the shared architecture they all bind to, and it is the only place
the cross-phase decisions live.

---

## PROJE ÖZETİ (Türkçe)

- **Proje nedir:** SecurityExpert, Check Point ve Palo Alto cihazlarından
  envanter ve yapılandırma kanıtı toplayıp tek bir rapor üreten okuma-ağırlıklı
  bir güvenlik kanıt platformu.
- **Bu görev nedir:** Bugün ürünün tek yüzü, çalıştırdıktan sonra üretilen
  statik bir HTML rapor. Bunun yanına, mühendisin kurumsal laptopunda geçici
  olarak ayağa kalkan, sadece o makineden erişilebilen **canlı bir operatör
  konsolu** ekliyoruz: listeden bir cihaz seçip "envanteri yeniden çek" ya da
  (izin verilen pilot cihazlar için) "yedek al" diyebildiğin ekran.
- **Neden / ne kazanırız:** BackBox 2027'de yenilenmiyor. BackBox'ın günlük
  kullanımdaki değeri sadece yedek almak değil, **bir ekrandan görüp oradan
  aksiyon alabilmek**. Bugün bunun için komut satırına geçmek gerekiyor; bu,
  ürünü günlük operasyon aracı olmaktan çıkarıyor.
- **Tür:** büyük özellik / mimari.
- **Gelecekte ne çözer / neyi açar:** Aynı uygulama, sunucu geldiğinde kurumsal
  kimlik doğrulama (OIDC) arkasında ekip konsoluna dönüşür. Zamanlama, kurtarma
  ekranı ve ileride kontrollü operasyonlar hep bu yüzeyin üstünde büyür.
- **Bu belge ne DEĞİL:** kod değil, karar ve sınır belgesi. Uygulama, fazların
  kendi sözleşmelerine göre ayrı oturumlarda yapılacak.

---

## 1. Framing — the report is a deliverable, not the product surface

`output/index.html` is a portable, dependency-free, shareable evidence
artifact. That is a product feature: it survives without a server, it goes into
the sanitized support bundle, it can be archived and read years later, and it
is the thing a non-operator can be handed. Every property that makes it good at
that makes it bad at being an operational console: it is a snapshot, it has no
identity, no authorization, no audit, and no way to talk back to the engine.

The mistake this document exists to prevent is *making the report dynamic*.
A shared report containing a `Back-Up` button is either dead (confusing) or
live (a device-command channel handed to whoever received the file). Both are
unacceptable, and the second is explicitly forbidden by
`SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md` §2.

## 2. Decision

Add a **second delivery surface** — the Operator Console — served by the same
engine, sharing the same UI source modules, and reusing the existing
orchestration, admission, ledger and evidence layers without duplicating any of
them.

```text
                        ┌── utils/html_export ──────► output/index.html
engine                  │                             portable · shareable · NO action surface
(main.py + utils/*)  ───┤
                        └── console/ (CON.x) ───────► http://127.0.0.1:<port>
                                                      authenticated · action-capable · single operator
```

Two deliveries. One UI source tree. One orchestration core. One evidence store.

## 3. What this is not

These are hard boundaries, not preferences. Each maps to an existing frozen
prohibition.

| Not this | Why | Source |
|---|---|---|
| A dynamic `index.html` | Destroys the portable evidence artifact; puts an action surface into a shareable file | §1 above; `PROJECT_VISION.md` data/privacy model |
| A generic REST wrapper around `main.py` | The browser would become a command channel with an open argument surface | `SERVER_PRODUCTIZATION…` §1 |
| Browser-supplied device commands | Same, one layer down | `SERVER_PRODUCTIZATION…` header + §1 |
| Control plane bolted onto the nginx viewer | The viewer is a read model and must never hold credentials, evidence or recovery material | `SERVER_PRODUCTIZATION…` §2 |
| A network-exposed listener | The report itself is `LOCAL OPERATOR SENSITIVE`; the console is strictly more sensitive | `docker-compose.yml` nginx loopback comment |
| A second orchestration path | Two paths diverge; one of them eventually skips the ledger | `AGENTS.md` engineering laws |
| A frontend framework / bundler | Breaks the "one portable inline script, no build step" invariant just frozen | `CODEBASE_MODULARIZATION_FRONTEND.md` D-MOD1 |
| Higher device polling or concurrency | The console must not increase device contact relative to a CLI run | `AGENTS.md`; `CURRENT_STATE.md` standing priority 2 |

## 4. The intent boundary — the browser sends intent, never a command

This is the single decision that makes an action-capable UI acceptable at
current maturity.

```text
browser  ──►  POST /api/jobs { job_type, targets[], idempotency_key }
                     │
                     ├── job_type resolved against a CLOSED server-side registry
                     ├── targets resolved against unified.json (resolve_entity_id)
                     ├── argv built by a fixed template owned by the server
                     └── executed through the SAME path the scheduler uses
```

- The browser never transmits a command string, a flag, a hostname, an address,
  a credential, or a free-form argument that reaches a device.
- The only variable input that survives validation is a list of `entity_id`
  values that must already exist in `unified.json`; an unresolvable id fails at
  request time, before any device is contacted (the semantics
  `utils/recovery_collect.py` already implements).
- The registry is a compile-time constant in source, reviewed like the existing
  `ALLOWLISTED_WORKFLOWS` (`utils/collection_executor.py:200`) — not
  configuration, not user-editable, not extendable at runtime.

Consequence: the console's blast radius is exactly the registry's contents,
which is the same posture the CLI already has.

## 5. Reuse map — the engine is already shaped for this

Nothing in the list below is new work. It is the reason this track is a thin
surface rather than a rewrite, and every phase contract binds to it.

| Console need | Already exists | Location |
|---|---|---|
| Re-entrant orchestration entry | `main(argv, runtime_services=…, provenance=…, admission_run_context=…)` — the scheduler already calls it exactly this way | `main.py:361` |
| A documented UI trigger point | *"`main.py`, the scheduler, and any future UI-triggered action all call `run_recovery_collection` identically"* | `utils/recovery_collect.py:1` |
| Fixed argv construction per workflow | `_scheduler_workflow_argv` | `main.py:289` |
| Per-endpoint lock, per-vendor budget, coalescing | `execute_admitted_collection` + `CollectionCoordinator` | `utils/collection_executor.py:408` |
| Durable `operational-write` ceiling (1 per 24 h, fail-closed) | `OperationalWriteLedger` | `utils/recovery_operational_ledger.py` |
| "Why is this blocked" carried to a UI | `RecoveryCollectionBlockedError` / `RecoveryCollectionSkipped` | `utils/recovery_collect.py` |
| Pilot ceiling for CP backup | `SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES`, empty and fail-closed | RB.3b `B10` |
| Run isolation + manifest | `RunContext` | `utils/run_context.py` |
| Multi-process-ready state store | `evidence_backend` (filesystem default, opt-in PostgreSQL) | `utils/evidence_backend.py` |
| Payload builders (one source of truth) | `build_configuration_ui_payload`, `build_compliance_posture`, `build_project_plan_payload`, … | `utils/html_export.py` imports |
| Readiness model for the recovery screen | `compute_restore_readiness` | `utils/restore_readiness.py` |

**The console adds four things and nothing else:** an HTTP boundary, an
authentication boundary, a durable job record, and one new provenance value.

## 6. Two delivery modes, one UI source

After `codebase_modularization` (frontend) lands, `static/*.js` is a set of
responsibility-owned modules composed in an explicit dependency order.

- **Static mode** — `utils/html_export` concatenates the modules into the one
  inline `<script>` exactly as today, with payloads substituted as inline
  constants. `console_actions.js` is **not** included. There is no action
  surface in the file at all — not hidden, not disabled: absent.
- **Console mode** — `console/` serves the same modules as separate assets plus
  `console_actions.js`, and the payloads arrive over `GET /api/payloads`.

One runtime flag, set by the page shell, decides which world a module is in:

```js
window.SECURITYEXPERT_MODE = "static" | "console";   // set before any module runs
```

The single frontend change this requires is that report initialization becomes
a **function call** rather than top-level execution over inline constants:
`initializeReport(payloads)`. Static mode calls it with the inlined constants;
console mode calls it after a fetch. This is owned by `app_bootstrap.js` and is
therefore a change to a file `codebase_modularization` is already rewriting —
which is why **`CON.1` must not start before that build is DONE** (§10).

Invariant: **the console never introduces a payload shape the exporter does not
also produce.** If the console needs a field, the builder gains it and both
surfaces get it. Enforced by an equality test in `CON.1` (AC-4 there).

## 7. Security model (hard rules)

1. **Loopback only.** The listener binds `127.0.0.1`. Binding anything else is
   a `DEPLOY.1A`-class decision, not a flag someone flips — the same rule the
   compose file already applies to the nginx report viewer.
2. **Cookieless bearer authentication.** A token is generated per launch,
   printed once to the operator's terminal inside the URL fragment
   (`http://127.0.0.1:<port>/#t=<token>`). The shell reads it from
   `location.hash`, strips it via `history.replaceState`, holds it in memory,
   and sends it as `Authorization: Bearer`. **No cookie exists**, therefore no
   ambient credential exists, therefore the class of CSRF where another local
   page drives the console is structurally impossible — not merely mitigated.
   `Origin` / `Sec-Fetch-Site` are checked as defence in depth. The fragment is
   never transmitted to the server and never lands in an access log.
3. **The shell is data-free.** `GET /` and `/assets/*` are unauthenticated
   because they contain no evidence — only repository code. Every `/api/*`
   route requires the token; comparison is constant-time.
4. **Credentials never reach the browser.** They are resolved in the engine
   process exactly as they are today. No route returns, echoes, or accepts one.
5. **The console is in the worker trust zone, never the viewer zone.** It may
   not run in, or be reachable from, the nginx viewer container. It must never
   be given the recovery volume as a served path.
6. **No recovery payload is ever reachable over HTTP.** Manifests, readiness and
   validation verdicts only — never bytes, never a download link, never a
   decrypt path (`BACKUP_AND_RECOVERY_ARCHITECTURE.md` §11, restated as a
   route-table test in `CON.4`).
7. **Three independent gates for an `operational-write`.** The UI hides a
   non-allowlisted target, the API refuses it, and the collector refuses it.
   No layer is trusted to be the only one.
8. **Job records are identity-lean.** A job record carries `entity_id`,
   job type, timing and outcome. No credential, no management address, no raw
   device output, no backup bytes. It lives under `data_root`, never under
   `output_root`, and never enters the support bundle.
9. **Audit before action.** A job record reaches durable storage in `queued`
   state before the runner may start it. A crash mid-action must leave
   evidence that the action was attempted.
10. **Device contact frequency is unchanged.** The UI polls the job store, never
    a device. Auto-refresh reads artifacts on disk. No console feature may
    cause a collector to run more often than an operator explicitly asked.

## 8. Deployment shapes

| Shape | When | Identity | Status |
|---|---|---|---|
| **Laptop console** — `py main.py --console`, loopback, per-launch token, single operator | now (`CON.1`–`CON.5`) | the OS user who launched it | this track |
| **Server control plane** — same ASGI app behind an authenticated reverse proxy | only after the `DEPLOY.1` gates | corporate OIDC + role mapping, audited | out of scope here; blocked |
| **Report viewer** — nginx serving `output/` read-only | today | none (loopback) | unchanged; never becomes the console |

The application code is identical between shape 1 and shape 2. What changes is
the identity/authorization layer and the deployment profile — which is the whole
point of putting the boundary in the right place now.

## 9. UX contract — honest affordances

An action row is always in exactly one of five states, and the reason is always
shown, never implied by a greyed-out button:

| State | Meaning | Source of truth |
|---|---|---|
| `AVAILABLE` | the operator may run this now | registry + gates |
| `BLOCKED` | a gate refuses, with the specific reason rendered (e.g. *"not in the `D3` pilot allowlist"*, *"backup credential not configured"*) | `RecoveryCollectionBlockedError`, allowlist, credential preflight |
| `RUNNING` | a job is in flight; live state from the job store | job record |
| `SKIPPED` | the correct outcome, not a failure — e.g. the ledger already records a backup inside the 24 h window | `RecoveryCollectionSkipped` |
| `RESULT` | terminal state with a link to the produced evidence/manifest | job record + manifest |

`read`-class actions are one click. `operational-write`-class actions require a
preflight → typed confirmation → mandatory reason (§ `CON.3`). The difference is
visible in the UI, deliberately: the product should never make writing to a
production firewall feel like refreshing a table.

## 10. Phasing

Hard ordering constraint: **`codebase_modularization` (frontend) must be DONE
before `CON.1` starts.** Sharing UI code out of one 4,905-line flat file is not
a thing to attempt twice.

| Phase | Scope | Contract | Device risk | Blocked on |
|---|---|---|---|---|
| **`CON.0`** | this architecture; open decisions `C-D1`…`C-D8` | this document | none | product-owner decisions |
| **`CON.1`** | read-only console: transport, auth, CSP, dual-mode UI, live payloads. **Zero actions.** | `CON_1_OPERATOR_CONSOLE_READ_ONLY.md` | none — no vendor import, no credential | `codebase_modularization`; `C-D1`, `C-D2` |
| **`CON.2`** | job engine + `read`-class actions ("re-pull inventory", attestation, re-render) | `CON_2_CONSOLE_JOB_ENGINE_READ_ACTIONS.md` | same as a CLI run, no new commands | `CON.1`; `C-D3` |
| **`CON.3`** | `operational-write` actions — the Back-Up button, inside the `D3` allowlist | `CON_3_CONSOLE_OPERATIONAL_WRITE_ACTIONS.md` | the already-approved `D3` pilot only | `CON.2`; **`RB.3b` REAL_ENV_VALIDATED**; `C-D4`, `C-D6` |
| **`CON.4`** | Recovery module (`RB.5` surface, backup architecture §11) in both delivery modes | `CON_4_CONSOLE_RECOVERY_MODULE.md` | none — manifests only | `CON.2`; `RB.4` |
| **`CON.5`** | scheduler surface, read-only | `CON_5_CONSOLE_SCHEDULER_SURFACE.md` | none | `CON.2`; `C-D7` |
| **`CON.6`** | server mode behind OIDC/RBAC | *not contracted* | — | the full `DEPLOY.1` gate set |

The "living product" feeling arrives at `CON.2`, and nothing up to and including
`CON.2` changes device interaction semantics.

## 11. Open decisions

| id | Decision | Owner | Recommendation | Blocks |
|---|---|---|---|---|
| **`C-D1`** | Approve `fastapi` + `uvicorn` as a new **optional** dependency set (`requirements-console.txt`, same pattern as `requirements-postgres.txt`); core CLI/report path keeps its four dependencies. | product owner + security | **Approve.** Boundary-level request validation is a security control, and it is the same stack the server control plane needs. Alternative (stdlib `http.server`) is adequate for `CON.1` and expensive from `CON.2` on, where auth/validation/streaming would be hand-rolled. | `CON.1` |
| **`C-D2`** | Local authentication model. | security | **Cookieless per-launch bearer token in the URL fragment** (§7.2). Rejected: no-auth-on-loopback (any local process or browser page reaches it); cookie sessions (reintroduce ambient credentials and CSRF surface). | `CON.1` |
| **`C-D3`** | Provenance vocabulary: add `Provenance.CONSOLE = "console"` alongside `manual`/`scheduled`, or reuse `manual`. | product owner | **Add `"console"`.** A UI-triggered device action must be distinguishable from a CLI one in every manifest and audit record; conflating them destroys the audit trail on the first day it matters. | `CON.2` |
| **`C-D4`** | Maximum targets per `operational-write` request during the pilot. | network-security leads | **1.** The pilot's value is proving the path, not throughput; a per-request ceiling of one makes an accidental multi-device write structurally impossible. Revisit after the first real runs. | `CON.3` |
| **`C-D5`** | May the console run on the server before OIDC/RBAC exists? | security | **No.** Loopback laptop only until the `DEPLOY.1` gates pass. The compose `worker` service must not publish a console port; `docker-compose.yml` gains no console mapping in this track. | `CON.6` |
| **`C-D6`** | Mandatory operator `reason` text on every `operational-write`, stored in the job record and the ledger — and its retention. | product owner | **Yes, mandatory**, minimum 8 characters, redaction-registry filtered before persistence, never included in the support bundle. It is the difference between an audit log and a list of timestamps. | `CON.3` |
| **`C-D7`** | May scheduler policy be *edited* from the console? | product owner + security | **Not in this track.** Editing the policy from a browser is a privilege-escalation path into unattended device contact and needs its own gate. `CON.5` ships a read-only view. | `CON.5` |
| **`C-D8`** | Track identity: `CON.x` as its own roadmap track (chosen here), versus folding the work into `DEV.x` or `0.8.x`. | product owner | **Keep `CON.x`.** It is a delivery-surface track that spans several product themes; burying it in an engineering track hides an operator-visible capability from the roadmap. | roadmap presentation only |

## 12. Amendments to existing documents

1. **`docs/design/SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md`** —
   §1 and §2 are amended by an appended note recording (a) that the "real
   operational need" its prohibition was conditioned on is now stated (the
   BackBox exit), and (b) that the sanctioned form of the control plane is this
   document's console: separate from the viewer, closed job registry, no
   browser-supplied commands. The prohibitions themselves are **not** relaxed.
2. **`docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` §11** — the Recovery
   module it specifies is delivered by `CON.4` in both delivery modes; no change
   to its content is required, and its "manifests and readiness only" rule is
   restated here as §7.6.
3. **`AI_START_HERE.md`** — the "One Python CLI, no web server" line becomes
   accurate again only after `CON.1` lands; the amendment is part of `CON.1`'s
   own definition of done, not of this document.
4. **Amendment (`PCP.0`, 2026-09-05, `PRODUCT_CONTROL_PLANE_ARCHITECTURE.md`
   §22 item 1)** — the device experience and first-run onboarding described
   there (`PCP.4`) are `CON.x` surface work under this document, extended
   with a registry-driven experience; nothing in §3 ("what this is not"),
   §4 (the intent boundary), §6 (payload parity), §7 (security model) or
   §10 (phasing) is relaxed. **Neither the manual-enrollment intent nor the
   candidate-id enrollment intent is added to §4 as an authorized write** —
   both are recorded as pending the `pcp_console_registry_write_gate` open
   decision, whichever way that decision lands. A closed candidate id is a
   *narrower* input than a free-typed endpoint; it is not, by itself, an
   authorization for a persistent product-state write to reach the console
   before `DEPLOY.1A`. All prohibitions in this document stand unrelaxed.

## 13. Risks

- **Scope drift into a platform rewrite.** The console is a surface over an
  existing engine. The moment a phase proposes a new collector, a new payload
  shape the exporter lacks, or a second orchestration path, it has left this
  architecture. Each contract restates this as an explicit out-of-scope line.
- **The static report silently regressing.** Every console phase that touches
  `static/*` or `utils/html_export.py` must show the render harness green and
  prove the exported report still contains no action surface.
- **Auth theatre.** A loopback bind alone is not authentication on a shared
  corporate laptop with other local software. `C-D2` exists because "it's only
  localhost" is the failure mode, not the control.
- **BackBox parity is still not vendor parity.** `D1` (the inventory of what
  BackBox actually backs up today) remains open. A polished console makes the
  CP/PAN story excellent and changes nothing about F5 / Cisco / Fortinet
  devices. It can make the gap *easier to overlook*, which is a product risk,
  not an engineering one — `CON.4`'s coverage view must therefore count devices
  it does not cover as `UNKNOWN`, never omit them.
- **Ledger bypass.** If any console action ever calls a collector directly
  instead of going through `main()` / `run_recovery_collection`, the 24 h
  `operational-write` ceiling stops being enforced. `CON.2` AC-2 exists solely
  to make that structurally checkable.

## 14. Next movement / model

`CON.0` is complete with this document plus the five phase contracts. The next
movement is `IMPLEMENTATION` of `codebase_modularization` (frontend), which is
already contract-frozen and is `CON.1`'s hard precondition — recommended tier
**`Sonnet 5, normal`**. `CON.1` and `CON.2` implementation are likewise
**`Sonnet 5, normal`** against these contracts. Escalate to extended thinking
only for `CON.3` (an `operational-write` surface: security boundary) and for
any phase closure that changes a decision recorded here.
