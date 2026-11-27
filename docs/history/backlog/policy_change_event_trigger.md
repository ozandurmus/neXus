# Policy change event trigger: Q1–Q9 freeze, 1-day SIEM fact-check gate, and Q6/Q8 prerequisites

status: planned · target: docs/design/PO_DECISION_RECORD_2026_11_26_POLICY_CHANGE_EVENT_TRIGGER.md

## PO 2026-11-26 Q1–Q9 Freeze Summary

`docs/design/PO_DECISION_RECORD_2026_11_26_POLICY_CHANGE_EVENT_TRIGGER.md` (`FROZEN — PO APPROVED 2026-11-26`) freezes the Phase 1 scoping package (`Q1`–`Q9`), while `docs/design/POLICY_CHANGE_EVENT_TRIGGER_DESIGN_2026_11_26.md` remains `DRAFT -- DO NOT FREEZE` pending Step 0 measurement:

- `Q1`/`Q2`: Select filtered `syslog-ng`/`rsyslog` relay over RFC 5425 `TLS_MUTUAL` TCP `6514` (`1A`, dual-pin SHA-256 cert rotation, per-source `required_transport`, wire-speed shedding) or Splunk structured HTTPS `443` webhook (`1B`, keyed HMAC-SHA256 + `event_intake_nonce`), with direct device/MDS `TLS_MUTUAL` (`1C`) only as audit-only fallback; zero hostname/`host`/`dvc` joins (`NOT_EVALUABLE`).
- `Q3`/`Q4`/`Q5`: Phase 1 is policy-plane only (`cp_policy_collect`), 14-day pilot on one small/medium actively changed Check Point MDS Domain, with no hourly device safety poll (relying on 6h `00/06/12/18` scheduled runs plus transport/exporter health).
- `Q6`: Bundled `EVENT_TRIGGERED` operational amendment enforcing all 6 mandatory safeguards (`jobs.trigger_window_exemption` + `trigger_event_id`, explicit `gate_registry.max_frequency` amendments capped at `<= 6`/unit/hr and `<= 30`/hr fleet with no retry, `01:45–03:00` backup blackout + `00/06/12/18` non-starvation latch, `security_admin` runtime kill-switch, `CHANGED_ONLY`/`skipHits`/`CpShowChangesReader` cursor fix, and `UI2_JOB_WINDOW_MINUTES=60`).
- `Q7`/`Q8`/`Q9`: Domain-level `CHANGED_ONLY` SLOs in Phase 1 (`cpstat` deferred to Phase 2); dedicated `ui2-event-intake` pod (`PriorityClass: ui2-low`, `event_intake` role, zero device egress, zero credential/artefact secrets, `ui2_event_intake` DB role with zero `jobs` grants) calling `ui2-service` over internal HTTP; conditional `443` or `6514` ingress gated on `Q1` and HOST-A verification.

## 1-Day SIEM / Collector Fact-Check Gate (`FC-1`..`FC-10`)

Step 0 requires a read-only SIEM/collector and firewall fact check recording only booleans, counts, closed enums, `MATCH`/`MISMATCH`, and field-presence flags (zero raw log lines, hostnames, serials, IPs, or principals):

- `FC-1`..`FC-4`: Pre-SIEM `syslog-ng`/`rsyslog` tier availability (`TLS_MUTUAL` `6514`, upstream audit filter, per-input `opaque_emitter_key`), vendor audit/config event ingestion coverage, opaque non-hostname ID survival (`serial`/`devid`/`dg_id` vs `host`/`dvc`), and Splunk real-time vs scheduled alert latency + HTTPS `443` egress. Deterministically selects `1A`, `1B`, `1C`, or `HOLD`.
- `FC-5`..`FC-10`: Check Point R82 MDS audit-only `FilterConfiguration.xml` & Log Exporter health (`cp_traffic_log_leak_count=0`), Cisco ASA `logging permit-hostdown` presence, Palo Alto `serial` header & Panorama `commit-all` markers, Fortinet `44547` `devid`/`cfgpath`/mTLS check, HOST-A Traefik DNAT vs `input` placement, and OpenShift TLS `SNI` + sender buffer retention.

## 2026-11-27 Q6/Q8 Prerequisite Reconciliation (Automated Validation Only)

- Updated `deploy/ui2/57-policy-deployment.yaml` to set `UI2_JOB_WINDOW_MINUTES: "60"` (replacing the stale development-mode `"360"` value and citing `Q6` safeguard 6).
- Updated `docs/design/MODULE_POD_RUNTIME_CONTRACT.md` §1 to add `event_intake` to the closed roles list and ownership table as a zero-claim listener role (`event_intake (0)`) per `Q8`, with an amendment note citing the frozen PO decision record.
- Added manifest/contract regression assertion in `tools/tests/test_ui2_deployment_manifests.py`; no Flyway migration, `gate_registry` edit, `nftables` edit, deployment, or device contact was performed (automated validation only).
