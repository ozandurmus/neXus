# AI_START_HERE

Single entry point for any AI (or human) picking up this repository cold.
Read this file first, then follow the reading order below. Do not scan the
repository root or `docs/history/**` looking for context — everything you need
on the first pass is listed here.

Working language: **English** for all conversation, analysis, commit messages and
docs. Vendor CLI commands, API fields and code identifiers stay verbatim. Ask for
a Turkish translation or explanation only when you explicitly need one.

---

## What this is

**neXus / SecurityExpert** — a multi-vendor network-security *evidence* platform.
It collects and reconciles runtime inventory and current configuration from
Check Point (MDS/CMA), Check Point VSX, and Palo Alto Panorama / PAN-OS, then
publishes a single static HTML report plus a sanitized shareable support bundle.

Product maturity axis: `SEE → VERIFY → TRACE → RECOVER → OPERATE`.
`SEE` (inventory) is mature; `VERIFY` (configuration + alignment + compliance) is
in progress; `RECOVER` has shipped its first controlled writes; `OPERATE` has
shipped its read-only half.

**What the product may do is an explicit taxonomy, not a slogan**
(`utils/action_taxonomy.py` — the single source of truth):

| Class | What | Status |
| --- | --- | --- |
| 0 — read | discovery, inventory, config collection, compliance, verification, preflight, readiness | permitted; the overwhelming majority of the product |
| 1 — controlled recovery write | narrowly scoped recovery ops: backup creation, exact generated-artifact cleanup | permitted **only** through the `RB.x` contracts (per-entity ledger, minimum re-execution interval, distinct backup credential, fail-closed allowlist); **not** console-submittable |
| 2 — operational state change | failover, cluster role transition | **no member exists**; hard-gated by `FAILOVER_ENGINE_ARCHITECTURE.md` §10 |
| 3 — configuration write | config / object / policy-rule modification | prohibited |
| 4 — policy / deployment / remediation | policy install, automated remediation | prohibited |

The older "the product is read-only" shorthand stopped being true when `RB.x`
shipped; do not restore it. `"operational-write"` in existing code and durable
records is this repository's legacy name for **class 1** only.

- Product baseline: see `CURRENT_STATE.md`
- Engineering baseline: `DEV.1 — Corporate Git Foundation`

---

## How it works in 30 lines

**One Python CLI.** Dependencies: `lxml`, `paramiko`, `requests`. `--console`
is the one optional exception — a loopback web server, off by default,
requiring the separate `requirements-console.txt`.

```
live devices ──(SSH / cprid_util / HTTPS XML API — class 0 read)──► collectors
   → per-source JSON artifacts
   → merge         → unified.json          (unified inventory model)
   → snapshot      → *_effective.json      (LIVE / LAST_KNOWN_GOOD / NO_DATA / PARTIAL)
   → verify        → verification.json     (observe-only integrity report, non-blocking)
   → config evidence → content-addressed store + derived manifests
   → html_export   → output/index.html     (template + inlined css/js + 5 JSON payloads)
   → support_bundle → sanitized shareable zip (HMAC-tokenized identities)
```

**Repository is not runtime.** Code lives in the repo; every run artifact
(`data/`, `output/`, `logs/`, CAS, last-known-good, HMAC key, credentials) lives
outside it — on Windows under `%LOCALAPPDATA%\SecurityExpert\runtime\`.
`utils/runtime_paths.resolve_runtime_paths()` enforces the separation.

### CLI modes (`main.py`)

| Command | Purpose |
| --- | --- |
| `py .\main.py` | Full integration checkpoint: CP + VSX + CP-config + Panorama + PAN-config + snapshot + merge + verify + HTML + support bundle, under one `RunContext`. Required before closing any build. |
| `py .\main.py --only cp` / `--only vsx` / `--only pan-config` | Collect one plane fresh, reuse the rest; HTML is marked NOT A CHECKPOINT. |
| `py .\main.py --render-only` | Rebuild HTML from the last `unified.json` + telemetry. No network, no credentials. |
| `py .\main.py --cp-config-collect --cp-config-stage all` / `--cp-config-probe` | Check Point current-configuration collection / evidence probe only. |
| `py .\main.py --repository-privacy-check` | Local/offline Corporate-Git privacy gate. No network, no credentials, matched values never printed. |
| `py .\main.py --storage-analyze` / `--storage-deduplicate [--apply]` | Content-addressed storage inspection / dedup migration (dry-run default). |
| `py .\main.py --scheduler-once` | Evaluate the default-disabled RuntimeRoot scheduler policy once; no loop. |
| `py .\main.py --console [--console-port N]` | Operator console (`CON.1`+`CON.2`): authenticated loopback HTTP service serving the existing UI live from local artifacts, plus a job engine. The browser submits **typed intent** (`job_type` + `entity_id` targets) against a closed server-side registry — never a command, an argv fragment or a path. Only class 0 job types are submittable; everything else returns 409 with the refusing class named. Requires `pip install -r requirements-console.txt`. |
| `py .\main.py --ha-readiness-check` | `OP.0a` HA readiness assessment over already-collected evidence. Offline, no credential, no device contact; writes `data/state/ha_readiness.json`. Cannot emit `SAFE_TO_FAILOVER` by construction — see below. |
| `py .\main.py --restore-readiness-check` / `--recovery-attest` / `--recovery-store-check` / `--recovery-validate` | `RB.x` recovery plane, class 0 halves: readiness derivation, backup/snapshot attestation, store inspection, artifact validation. |
| `py .\main.py --recovery-collect --recovery-vendor <checkpoint\|panorama>` | `RB.x` recovery collection — **class 1**. Ledgered, allowlisted, separately credentialed; never reachable from the console. |
| `py .\main.py --persistent-secret-material-check` / `--compliance-trend-reconstruct` | Trust-material preflight (`DEV.2.2`) / compliance-trend retro-fill (`0.7.7`). |

Vendor/config imports are lazy — maintenance modes return before touching them.

### Directory map

| Path | Responsibility |
| --- | --- |
| `main.py` | CLI, mode matrix, orchestration, stage ordering |
| `config.py` | `Config` — auth + endpoint + runtime-paths carrier |
| `checkpoint/cp_runner.py` + `scripts/cp_inventory.sh` | CP inventory (SSH to MDS → `cprid_util` per managed gateway) |
| `checkpoint/vsx_runner.py`, `vsx_parser.py` | VSX inventory (nested SSH + `vsenv <VSID>`) |
| `checkpoint/direct_ssh_probe.py` | observe-only direct-SSH fallback probe |
| `panorama/panorama_runtime_runner.py` | PAN inventory (HTTPS XML API) |
| `configuration/panorama_config_collector.py` | PAN config + expected compiler + setting alignment + semantic validation |
| `configuration/checkpoint_config_collector.py`, `checkpoint_config_probe.py` | CP config (interactive PTY SSH handshake, secret-aware redaction) |
| `configuration/pan_*`, `*_alignment*.py`, `current_config_projection.py` | expected-vs-actual classification, UI projection |
| `utils/collection_executor.py` | admission coordinator + limited scheduler (single entry gate for every collector) |
| `utils/run_context.py` | run isolation, staged artifact capture + atomic manifest |
| `utils/runtime_paths.py` | repository ↔ runtime path foundation |
| `utils/merge.py` / `snapshot.py` / `verification.py` | unified model / last-known-good / integrity |
| `utils/config_evidence.py`, `config_storage.py`, `config_history.py` | content-addressed store + dedup + read-only history |
| `utils/html_export.py`, `config_ui.py`, `compliance_posture.py`, `discovery_capability_ui.py`, `project_plan.py` | HTML payload builders |
| `utils/support_bundle.py`, `completeness.py` | sanitized shareable zip |
| `utils/logger.py`, `cp_ssh_trust.py`, `pan_tls_trust.py`, `repository_privacy.py`, `inventory_exclusions.py` | log redaction, trust preflight, DLP gate, exclusion policy |
| `templates/index.html` + `static/{app.js,style.css}` | single-page UI (Overview / Network Inventory / Configuration / Compliance / Discovery / Project Plan) |
| `console/` + `templates/console.html` + `static/console_actions.js` | operator console (`--console`): `registry.py` is the closed job vocabulary, `runner.py` the single-worker executor, `jobs.py` the durable records; imports no vendor/collector module |
| `utils/action_taxonomy.py` | the five action classes — what each surface may execute, and why not |
| `utils/failover/` | `OP.0a` HA readiness assessment **only**; the absence of a plan/executor/adapter is test-enforced |
| `project/*.json` | living plan metadata (roadmap / backlog / feature_registry / build_history) — embedded into the Project Plan UI on every render |
| `tests/` | phase-scoped suites; for the current baseline see `CURRENT_STATE.md` (never hard-code it here — it went stale by ~750 tests) |

Full mechanism detail: **`docs/ARCHITECTURE.md`**.

---

## Reading order (every new session)

```
0. AI_START_HERE.md        — this file: the idea, how it works, this order
1. CURRENT_STATE.md        — active build, next task, blockers, xfails, test baseline
2. AI_HANDOVER.md          — what the previous session did, your exact next action
3. project/roadmap.json + project/backlog.json — pull the task by id / target
4. docs/ARCHITECTURE.md    — only the sections your task touches
5. the current build/design doc, if the task names one
6. relevant source + tests, via narrow search

On demand only:  docs/history/**  (reach it through project/build_history.json links)
Never by default: docs/history/SECURITYEXPERT_AI_CONTINUATION_PACK.md,
                  docs/history/phase/PHASE*.md, docs/history/validation/VALIDATION*.txt
```

Governance and engineering law: `AGENTS.md` (canonical) and
`docs/AI_DEVELOPMENT_PROTOCOL.md` (detailed lifecycle). Tool-specific deltas:
`CLAUDE.md`, `.github/copilot-instructions.md`. These reference this reading
order rather than restating it.

At the start of a meaningful build, produce a `SESSION START`; at the end,
update durable state and rewrite `AI_HANDOVER.md`, then produce a
`SESSION CLOSE` (see `AGENTS.md`).

---

## Notes

- **Historical builds are a data pattern, not prose to scan.**
  `project/build_history.json` is the timeline index — one structured record per
  build, each linking to its archived agreement/validation doc under
  `docs/history/`. `docs/history/INDEX.md` is the one-line-per-build human view.
- **Privacy:** no credential, management IP, device name, serial, or raw
  configuration in any repository file — docs and metadata included.
- **claude-mem** (if installed on this machine) is a per-developer local memory
  convenience. It is not shared, not in git, and not authoritative. The
  repository is the source of truth for handover and state — never rely on
  local memory for continuation.
