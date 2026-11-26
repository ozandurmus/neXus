# PO Decision Record — Policy Change Event Trigger Scoping (Q1–Q9) and 1-Day SIEM Fact-Check Plan (2026-11-26)

## Status

**FROZEN — PO APPROVED 2026-11-26.**

This record freezes the Product Owner's approval of the recommended `Q1`–`Q9` scoping package from
[POLICY_CHANGE_EVENT_TRIGGER_DESIGN_2026_11_26.md](POLICY_CHANGE_EVENT_TRIGGER_DESIGN_2026_11_26.md) (rev 2) and
establishes the mandatory **1-Day Read-Only SIEM / Collector Fact-Check Checklist** that gates feed selection (`Q1`),
ingress selection (`Q9`), and the Phase 1 Check Point pilot (`Q3`). The underlying design document
[POLICY_CHANGE_EVENT_TRIGGER_DESIGN_2026_11_26.md](POLICY_CHANGE_EVENT_TRIGGER_DESIGN_2026_11_26.md) remains
`DRAFT -- DO NOT FREEZE` as engineering investigation guidance while its Section 10 `UNKNOWN` items are measured.

---

## 1. Approved Scoping Package (`Q1`–`Q9`)

| Q | Approved Decision (`PO APPROVED 2026-11-26`) | Gating Prerequisite / Boundary |
|---|---|---|
| `Q1` | Filtered `syslog-ng` / `rsyslog` relay (`1A`, `TLS_MUTUAL` `6514`) **or** Splunk HTTPS webhook (`1B`, `POST` `443`, HMAC + nonce); `1C` direct `TLS_MUTUAL` only if no collector carries the logs | Gated on the **1-day read-only SIEM/collector fact check** (`§3` checklist); zero hostname/`host`/`dvc` joins (`NOT_EVALUABLE`) |
| `Q2` | RFC 5425 `TLS_MUTUAL` on dedicated TCP `6514` (if syslog chosen), dual-pin SHA-256 cert rotation (`current` + `next`), per-source `required_transport` anti-downgrade lock, wire-speed shedding, no direct ASA TCP/TLS without `logging permit-hostdown` | Skipped if `Q1 = 1B` (HTTPS webhook); plain TCP (RFC 6587) prohibited |
| `Q3` | **Check Point** — one small/medium, actively changed MDS Domain for the **14-day pilot** | Gated on verified **audit-only** stream (zero traffic logs), `Profile A`/`B` opaque identity, `DEFERRED_SOURCE_BUSY`, `00/06/12/18` non-starvation latch, `01:45–03:00` blackout, `CpShowChangesReader` cursor fix, kill-switch, and `UI2_JOB_WINDOW_MINUTES=60` |
| `Q4` | **Phase 1: no hourly device safety poll** | Relies on 6h scheduled collection (`00/06/12/18`), real-time transport/relay heartbeat liveness, and Check Point MDS Log Exporter health monitoring |
| `Q5` | **Phase 1: Policy plane only** | `ConfigurationRefreshScheduler` (`00:00` daily) unchanged; ASA/FortiGate config-derived policy triggers deferred to Phase 2 |
| `Q6` | Single bundled `EVENT_TRIGGERED` operational amendment containing **all 6 mandatory safeguards** | Covers `jobs.trigger_window_exemption`, `gate_registry.max_frequency` caps (`<=6`/unit/hr & `<=30`/hr fleet), `01:45–03:00` blackout + `00/06/12/18` non-starvation, `security_admin` kill-switch, `CHANGED_ONLY`/`skipHits`/`CpShowChangesReader` cursor, and `UI2_JOB_WINDOW_MINUTES=60` |
| `Q7` | Domain-level `CHANGED_ONLY` (`cp_policy_collect`) in Phase 1 with Domain-size-aware SLOs; gateway `cpstat` (`device_policy_install`) deferred to Phase 2 | `<= 3 min` p95 dispatch (idle); `<= 10 min` p95 (`< 5k` rules); `<= 35–45 min` p95 (`15k+` rules, `<= 4` sessions) |
| `Q8` | Separate `ui2-event-intake` pod (`PriorityClass: ui2-low`, zero device egress, zero device/artefact secrets) calling internal HTTP on `ui2-service` with SHA-256 token (`ui2_event_intake` DB role zero `jobs` grants) | Authorizes implementation updates to `MODULE_POD_RUNTIME_CONTRACT.md` §1 (`event_intake`), `module_runtime_control_module_check` (`V154`/`V258`), and `deploy/ui2/46-database-networkpolicy.yaml` |
| `Q9` | Conditional on `Q1`: HTTPS `443` `/api/v1/event-intake/*` if webhook (`1B`); Traefik `6514` TLS passthrough + `prerouting` negated `drop` if `TLS_MUTUAL` (`Profile A`/`B`), or `hostPort: 6514` + `prerouting` negated `drop` if `Profile C` | HOST-A `k3s` Traefik ServiceLB `prerouting` DNAT vs `input` must be verified on HOST-A before any firewall edit |

### 1.1 Clause-by-Clause Decision Detail

- **`Q1` — Feed Architecture (Gated on 1-Day Fact Check):**
  - **Approved:** Select **Option `1A`** (filtered `syslog-ng` / `rsyslog` relay over `TLS_MUTUAL` TCP `6514`, stamping an enrolled per-input `opaque_emitter_key` or preserving `serial`/`devid`) **or Option `1B`** (Splunk structured HTTPS webhook `POST` over `443` using per-source keyed HMAC-SHA256 `timestamp.nonce.body`, fail-closed PostgreSQL `event_intake_nonce` replay table, and dual `kid` key rotation), determined directly by the 1-day read-only SIEM/collector fact check (`§3`).
  - **Fallback (`1C`):** Direct device / MDS Log Exporter `TLS_MUTUAL` (`6514`) is authorized only where no collector carries the audit/config logs, and only after verifying an **audit-only** stream (zero Security/traffic logs).
  - **Hard Prohibition:** Overwatch **MUST NEVER** join Splunk `host`, `dvc`, `orig_host`, syslog `HOSTNAME`, Check Point `Device Name`, or Palo Alto `device_name` against inventory hostnames or pseudonyms. Any relay event lacking an enrolled `opaque_emitter_key` or verified opaque ID (`serial`, `devid`, `dg_id`) evaluates fail-closed to `NOT_EVALUABLE`.

- **`Q2` — Syslog Transport & Sender Authentication (If `Q1 = 1A` or `1C`; Skipped if `Q1 = 1B`):**
  - **Approved:** RFC 5425 mutual TLS (`TLS_MUTUAL`, `ClientAuth.REQUIRE`, TLS 1.2+ AEAD ciphers) on dedicated TCP `6514`, with:
    1. Dual-pin SHA-256 certificate rotation (`cert_sha256_current` + `cert_sha256_next` with `cert_next_valid_until`);
    2. Per-source `required_transport` anti-downgrade lock (`TRANSPORT_DOWNGRADE_REFUSED` if traffic arrives on a lower-grade listener);
    3. Wire-speed socket reading and in-memory shedding (TCP reads are **never** paused);
    4. **Hard Prohibition:** No direct Cisco ASA TCP or TLS syslog to Overwatch unless `logging permit-hostdown` is verified present in the unit's stored `running-config`.
  - Reduced-grade fallbacks (`UDP_LEGACY` `514` / separate-port `TLS_SERVER_ONLY`, `Profile C`) remain disabled by default and require explicit `security_admin` per-source opt-in only where mTLS and relay are proven impossible. Plain TCP (RFC 6587 Historic) is rejected.

- **`Q3` — First Vendor for the 14-Day Pilot:**
  - **Approved:** **Check Point** — one small/medium, actively changed MDS Domain (e.g., `CpChangedOnlyDomains.TRIAL_DOMAIN` if actively changed, or the smallest active Domain), gated on:
    1. Verified **audit-only** stream (zero Security/traffic logs);
    2. `Profile A` (`MTLS_PINNED_1_TO_1` cert) or `Profile B` (`MULTIPLEXED_RELAY_OR_MANAGER` with enrolled `opaque_emitter_key`, zero hostname joins);
    3. `DEFERRED_SOURCE_BUSY`, `00/06/12/18` scheduled-run non-starvation latch, `01:45–03:00` backup blackout, `CpShowChangesReader` cursor fix, and `security_admin` kill-switch tested and verified under `UI2_JOB_WINDOW_MINUTES=60`.

- **`Q4` — Safety Poll Strategy:**
  - **Approved:** **Phase 1 no hourly device safety poll.** Rely on the guaranteed 6-hour scheduled policy collection (`00:00 / 06:00 / 12:00 / 18:00 Europe/Istanbul`), real-time transport/relay heartbeat liveness (`CONNECTED` / `HEARTBEAT_OK` / `DISCONNECTED` / `HEARTBEAT_STALE` / `PASSIVE_NO_HEARTBEAT` / `DEGRADED`), and Check Point MDS Log Exporter health monitoring. Using `show-last-published-session` as an hourly `MISSED_EVENT` oracle is explicitly rejected because SmartConsole Publish does not equal Install Policy (op 7).

- **`Q5` — Plane Scope:**
  - **Approved:** **Phase 1 Policy plane only** (`cp_policy_collect`). Non-policy device configuration triggers (`ConfigurationRefreshScheduler` `00:00` daily runs) remain unchanged in Phase 1; ASA/FortiGate config-derived policy triggers (`asa_configuration_collect`, `fgt_configuration_collect`) are deferred to Phase 2 per vendor.

- **`Q6` — Bundled `EVENT_TRIGGERED` Operational Amendment (All 6 Mandatory Safeguards):**
  - **Approved:** A single bundled `EVENT_TRIGGERED` operational amendment authorizing event-triggered `CLASS_0` read-only policy collection outside `00/06/12/18` windows **only** with all six safeguards enforced together:
    1. **Dedicated Claim Column (`jobs.trigger_window_exemption`):** Nullable `jobs.trigger_window_exemption` (`EVENT_TRIGGERED_POLICY`, `EVENT_TRIGGERED_CONFIG`) + `jobs.trigger_event_id UUID` + partial index in `JooqJobLeaseDao.CLAIM_SQL` and `ClaimStatementText.SQL` (never overloading admin `jobs.window_bypass_role` from `V168`).
    2. **Explicit `gate_registry.max_frequency` Amendments & Caps:** Flyway migration naming each affected `CLASS_0` `gate_id` (`V116`, `V123`, `V124`, `V125`, `V130`, `V163`, `V192` for CP Phase 1; `V117`, `V221` for PAN Phase 2; `V78`, `V80` for ASA/FGT Phase 2 — hit-count gates excluded), bounded by 90 s / 10 min debounce, 10-min per-unit cooldown, **`<= 6` triggers/unit/hour**, **`<= 30` triggers/hour fleet-wide**, max coalesced backlog = `1`, and strict no-retry (`retry_rule = none`).
    3. **`01:45–03:00` Backup Blackout & `00/06/12/18` Non-Starvation:** Hard blackout from `01:45–03:00 Europe/Istanbul` (and while any `cp_mds_export` or backup job is non-terminal on that target, recording `DEFERRED_BACKUP_WINDOW`); `XX:45–XX:59` pre-window guard (`DEFERRED_WINDOW_GUARD`), `DEFERRED_SOURCE_BUSY`, and durable sticky `pending_scheduled_run` (`FULL` over `CHANGED_ONLY`) latch so `00/06/12/18` and `00:00 FULL` runs are never dropped.
    4. **Audited `security_admin` Runtime Kill-Switch:** DB-backed `event_trigger_settings` switch (global + per-vendor + per-`event_source`) halting new submissions and unclaimed `trigger_window_exemption` claims immediately without pod restart.
    5. **Collector Bounds & Diff-Cursor Fix:** Check Point `CHANGED_ONLY` (never `FULL` / never `show-hits`), Panorama `skipHits = true` + ancestor-only DG fetch (Phase 2), and separate `last_diffed_marker` cursor for `CpShowChangesReader` in `PolicyCompletenessCoordinator` so out-of-window runs never drop `show-changes` diffs.
    6. **`UI2_JOB_WINDOW_MINUTES=60` Enforcement:** Automated integration tests and the live pilot must run and pass under `UI2_JOB_WINDOW_MINUTES=60` (reconciling the stale `"360"` setting in `deploy/ui2/57-policy-deployment.yaml`).

- **`Q7` — Check Point Collection Granularity & Gateway `cpstat` Boundary:**
  - **Approved:** Domain-level `CHANGED_ONLY` (`cp_policy_collect`) in Phase 1 under Domain-size-aware SLOs (`<= 3 min` p95 dispatch when idle; `<= 10 min` p95 completion for `< 5,000`-rule Domains; `<= 35–45 min` p95 completion for `15,000+`-rule Domains across `<= 4` parallel sessions). Per-gateway `device_policy_install` (`cpstat`) refresh and per-package partial-collection seams are deferred to Phase 2.

- **`Q8` — Receiver Placement, Job Submission & Governance Footprint:**
  - **Approved:** Dedicated `ui2-event-intake` pod (`PriorityClass: ui2-low`, same image with `event_intake` entrypoint role, zero device-network egress, zero `ui2-credential-store-key` / `ui2-artefact-store-key` / `/app/artefact-store` PVC), submitting matured triggers via ClusterIP internal HTTP (`POST /internal/event-triggers/submit`) to `ui2-service` authenticated with a mounted SHA-256-digested bearer token Secret. The narrow `ui2_event_intake` DB role holds **zero grants on `jobs`, `policy_collection_request`, `devices`, `credentials`, or `audit_log`** (`SELECT` only on enrollment projection + `SELECT/INSERT/UPDATE/DELETE` on its four intake tables, with `audit_redaction_policy` Tier 1/2 entries).
  - Authorizes the required implementation-time updates to [MODULE_POD_RUNTIME_CONTRACT.md](MODULE_POD_RUNTIME_CONTRACT.md) §1 (`event_intake` zero-claim listener role), `module_runtime_control_module_check` (`V154` / `V258__script_execution_slice2.sql`), and `deploy/ui2/46-database-networkpolicy.yaml` (`ui2-db-ingress` TCP `5432`).

- **`Q9` — HOST-A & OpenShift Ingress Path (Conditional on `Q1`):**
  - **If `Q1 = 1B` (Splunk HTTPS webhook):** Reuse existing HTTPS `443` edge (`Traefik` `websecure` on HOST-A; standard HTTPS `Route` on OpenShift) forwarding only `/api/v1/event-intake/*` to `ui2-event-intake` (zero new firewall ports).
  - **If `Q1 = 1A` or `1C` (Syslog `TLS_MUTUAL` `6514`, `Profile A`/`B`):** Traefik TCP entryPoint `6514` with TLS passthrough + mandatory negated `drop` (`iifname "ens160" ct state new tcp dport 6514 ip saddr != @syslog_allowed drop` + explicit IPv6 `drop`) in `deploy/hosta/nexus-host-firewall.nft` `chain prerouting` (`priority -150` before `dstnat -100`), gated on verifying HOST-A k3s Traefik DNAT vs `input` behavior. On corporate OpenShift, use TLS `SNI` `443` passthrough `Route` (if client SNI is verified) or a platform-approved `LoadBalancer`/`NodePort` Service for `6514`.
  - **If `Profile C` peer IP preservation is ever needed:** `hostPort: 6514` on `ui2-event-intake` + the same `chain prerouting` negated `drop` (`priority -150`).

---

## 2. Non-Scope and Unchanged Boundaries

- **Zero device logging writes:** Overwatch never executes device-side logging commands or generates bypass scripts; all device/collector forwarding rules remain owned by the firewall and SIEM teams.
- **Zero raw log retention & strict `aiview` masking:** Events are parsed in memory into closed enums/counts/HMAC-keyed dedupe keys and immediately discarded (`AGENTS.md` Raw-evidence law). UI and `aiview` render strictly through `TopologyNamePseudonymizer` (`FW-…`, `CLS-…`, `DOM-…`).
- **No implementation artefacts in this record:** No SQL migration, `gate_registry` row, Kubernetes manifest, or `nftables` edit is committed by this decision record; those follow in dedicated implementation steps once the 1-day fact check settles `Q1`/`Q9`.

---

## 3. 1-Day Read-Only SIEM / Collector Fact-Check Checklist (Step 0)

**Execution & Privacy Rules (`AGENTS.md`):**
- Read-only inspection only with the SIEM/collector and firewall teams (no collector/device config changes, no Overwatch credentials shared).
- Record **only** booleans (`true`/`false`/`UNKNOWN`), integer counts, closed enums, `MATCH`/`MISMATCH`, and field-presence flags (`PRESENT`/`ABSENT`/`UNKNOWN`).
- **Never** copy raw log lines, hostnames, serial numbers, IPs, usernames, rule/object names, or session names into this repository or chat.

### 3.1 Core Feed & Identity Questions (`§3` — Gates `Q1` and `Q9`)

| ID | Prerequisite (`§3`) | Read-Only Verification Method | Required Fact-Check Output (Booleans / Counts / Enums Only) | Status (`DONE`/`OPEN`) |
|---|---|---|---|---|
| `FC-1` | **Pre-SIEM `syslog-ng` / `rsyslog` tier** | Confirm with SIEM/collector team whether a `syslog-ng` or `rsyslog` aggregation tier sits in front of Splunk (or if devices forward straight to Splunk forwarders), and whether it supports RFC 5425 `TLS_MUTUAL` (`6514`), upstream event filtering (excluding traffic logs), and per-input `opaque_emitter_key` tagging. | `pre_siem_syslog_tier=<bool>`, `collector_engine=<syslog-ng\|rsyslog\|splunk_uf_only\|other>`, `supports_tls_mutual_6514=<bool>`, `supports_upstream_audit_filter=<bool>`, `supports_per_input_emitter_key=<bool>` | `OPEN` |
| `FC-2` | **Vendor audit/config log coverage today** | Count/boolean check (last 7–30 days) for whether these specific event IDs/classes already arrive at the collector/Splunk: CP MDS op `7`/`8`/`39`/`40`/`41`; PAN `CONFIG` `cmd=commit` `result=Succeeded` + Panorama push (`commit-all`); FortiGate `44546`/`44547` & FortiManager `21023`/`21024`/`21015`; Cisco ASA `111004`/`111005`/`111010`. | `cp_op7_ingested=<bool>`, `pan_config_commit_ingested=<bool>`, `panorama_push_ingested=<bool>`, `fgt_44546_44547_ingested=<bool>`, `fmg_21023_21015_ingested=<bool>`, `asa_111004_111005_ingested=<bool>` | `OPEN` |
| `FC-3` | **Opaque non-hostname ID survival** | Inspect normalized schema/fields at both the syslog tier (if any) and Splunk to confirm whether non-hostname opaque identifiers survive (`serial`, `devid`, `dg_id` / `dg_hier_level_1..4`, or a dedicated per-Domain/per-device collector input port/tag mapped to `opaque_emitter_key`), or whether only `host` / `dvc` / `orig_host` (hostnames or NATed forwarder IPs) survive. | `syslog_preserves_opaque_id=<bool>`, `syslog_per_domain_input_possible=<bool>`, `splunk_preserves_serial_or_devid=<bool>`, `splunk_preserves_dg_id=<bool>`, `splunk_only_host_dvc=<bool>` (if `true`, Splunk webhook `1B` is **BLOCKED** unless an upstream tag stamps `opaque_emitter_key`) | `OPEN` |
| `FC-4` | **Splunk real-time vs scheduled alert latency** | Confirm with SIEM governance whether sub-minute real-time webhook alerts (`POST` HTTPS `443`) are permitted, or only scheduled cron searches (record indexing lag + cron interval in minutes), plus outbound HTTPS `443` reachability from Splunk to Overwatch. | `splunk_realtime_alerts_permitted=<bool>`, `splunk_scheduled_min_interval_min=<int\|na>`, `splunk_indexing_lag_p95_sec=<int\|UNKNOWN>`, `splunk_https_443_egress_ok=<bool>` | `OPEN` |

### 3.2 Vendor & Platform `§10` Prerequisites (`FC-5`–`FC-10`)

| ID | Prerequisite (`§10`) | Read-Only Verification Method | Required Fact-Check Output (Booleans / Counts / Enums Only) | Status (`DONE`/`OPEN`) |
|---|---|---|---|---|
| `FC-5` | **Check Point R82 MDS audit-only `FilterConfiguration.xml` & Log Exporter check (`§2.1`, `§10`)** | Inspect existing `cp_log_export` config / `FilterConfiguration.xml` on R82 MDS (with firewall team) to confirm: (a) an audit-only target exports **0 Security/traffic logs** (`filter-blade-in` / `FilterConfiguration.xml`); (b) whether JSON/Splunk format exports `loguid` or package/gateway/Domain fields; (c) RSA key compatibility (`TLS-Configuration.htm`); (d) baseline health of the 17 existing Log Exporter targets. | `cp_r82_audit_only_filter_verified=<bool>`, `cp_traffic_log_leak_count=0`, `cp_json_has_loguid=<bool\|UNKNOWN>`, `cp_op7_has_package_or_domain_field=<bool\|UNKNOWN>`, `cp_exporter_healthy_targets=<int>/<int>` | `OPEN` |
| `FC-6` | **Cisco ASA `logging permit-hostdown` & transport check (`§2.4`, `§10`)** | Check already-stored Overwatch `asa_configuration_collect` `running-config` snapshots locally (or verify with firewall team) for `logging permit-hostdown` presence across enrolled ASAs, current `logging host` transport (`udp`/`tcp`/`secure`), and whether any ASA firmware in the estate supports mTLS client certs. *(FMC out of scope.)* | `asa_total_count=<int>`, `asa_permit_hostdown_present_count=<int>`, `asa_direct_tcp_tls_safe=<bool>`, `asa_mtls_supported=<bool\|UNKNOWN>` | `OPEN` |
| `FC-7` | **Palo Alto `serial` header & Panorama `commit-all` shape (`§2.2`, `§10`)** | Inspect field-presence on forwarded PAN-OS Config logs and Panorama push (`commit-all`) logs: confirm whether `serial` is present in forwarded headers and whether Panorama push emits `dg_id` / `dg_hier_level_1..4` and what `cmd`/event marker identifies `commit-all`. | `pan_config_header_has_serial=<bool\|UNKNOWN>`, `panorama_commit_all_marker_observed=<bool\|UNKNOWN>`, `panorama_push_has_dg_id=<bool\|UNKNOWN>` | `OPEN` |
| `FC-8` | **Fortinet `44547` `devid`, `cfgpath`, & mTLS check (`§2.3`, `§10`)** | Inspect field-presence on FortiGate `44547`/`44546` and FortiManager `21023`/`21015`: confirm `44547` prepends `devid` matching inventory `devices.serial` byte-for-byte (`MATCH`/`MISMATCH`), confirm `cfgpath` prefixes covering policy/NAT/object edits, and confirm whether FortiOS `certificate` presents an mTLS client cert. | `fgt_44547_has_devid=<bool\|UNKNOWN>`, `fgt_devid_serial_exact_match=<MATCH\|MISMATCH\|UNKNOWN>`, `fgt_policy_cfgpath_prefixes_verified=<bool\|UNKNOWN>`, `fgt_mtls_client_cert_supported=<bool\|UNKNOWN>` | `OPEN` |
| `FC-9` | **HOST-A Traefik DNAT vs `input` verification (`§4.3`, `§10`)** | On HOST-A (read-only `nft list ruleset` / `k3s` ServiceLB check during authorized host check), verify whether Traefik receives `80/443` via ServiceLB `prerouting` `dstnat` (`priority -100`) or host `input`, confirming exact placement of the `chain prerouting` (`priority -150`) negated `drop` rule if `6514` is exposed. | `hosta_traefik_uses_prerouting_dnat=<bool>`, `hosta_prerouting_negated_drop_required=<bool>` | `OPEN` |
| `FC-10` | **OpenShift TLS `SNI` & vendor TCP/TLS buffer retention (`§4.3`, `§10`)** | Confirm whether the chosen `Q1` sender (`syslog-ng`/`rsyslog` or CP Log Exporter / PAN / FGT) emits TLS `SNI` `server_name` on syslog-TLS (`443` OpenShift Route passthrough vs `LoadBalancer`/`NodePort` Service for `6514`), and verify sender queue/buffer retention across a ~30–60 s receiver pod restart. | `sender_emits_tls_sni=<bool\|UNKNOWN>`, `openshift_ingress_mode=<route_443\|route_sni_passthrough\|lb_6514>`, `sender_restart_buffer_verified=<bool\|UNKNOWN>` | `OPEN` |

### 3.3 Deterministic `Q1` / `Q9` Selection Gate (Post Fact-Check)

1. **Select `Q1 = Option 1A` (`syslog-ng`/`rsyslog` `TLS_MUTUAL` `6514`) + `Q9 = 9A`:** when `FC-1` confirms a pre-SIEM syslog tier with `TLS_MUTUAL` + upstream audit filtering, `FC-2` confirms `cp_op7_ingested=true`, and `FC-3` confirms `syslog_preserves_opaque_id=true` or `syslog_per_domain_input_possible=true` (zero hostname joins).
2. **Select `Q1 = Option 1B` (Splunk HTTPS webhook `443`) + `Q9 = 443`:** when `FC-1` shows no usable pre-SIEM syslog tier (or `1A` is unavailable), `FC-2` confirms `cp_op7_ingested=true`, `FC-3` confirms Splunk preserves an opaque non-hostname ID or enrolled `opaque_emitter_key` (`splunk_only_host_dvc=false`), **and** `FC-4` confirms sub-minute real-time webhook alerts over `443` are permitted.
3. **Select `Q1 = Option 1C` (Direct CP R82 MDS `TLS_MUTUAL` `6514`) + `Q9 = 9A`:** only if neither `1A` nor `1B` qualifies **and** `FC-5` proves `cp_r82_audit_only_filter_verified=true` with `cp_traffic_log_leak_count=0` and per-Domain `Profile A` mTLS certs.
4. **HOLD (Do Not Proceed to Intake Implementation):** if `FC-3` shows hostname-only identity (`NOT_EVALUABLE`) across all candidate paths, or if `1C` is selected without `FC-5` proving an audit-only stream (`cp_traffic_log_leak_count=0`).
