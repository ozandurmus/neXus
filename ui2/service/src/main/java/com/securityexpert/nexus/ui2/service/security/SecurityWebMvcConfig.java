package com.securityexpert.nexus.ui2.service.security;

import java.util.Map;
import java.util.Set;

import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link GateChainInterceptor} against every route this movement
 * gates (contract §7: "{@code E1}-{@code E6} run in one interceptor chain
 * no route can bypass"). {@code /login} and {@code /login/resolve} are
 * deliberately absent from the route map: they run before any session
 * exists (C3 §3.4).
 *
 * <p>No {@code bootJar} is produced at B1 scope (contract §2 skeleton
 * note carried over from B1-1), so this configuration is exercised by
 * Spring context tests only, never a running server, in this slice.</p>
 */
public final class SecurityWebMvcConfig implements WebMvcConfigurer {

    static final Map<String, String> ACTION_ID_BY_ROUTE = Map.ofEntries(
            Map.entry("GET /api/v2/lifecycle", ActionRegistry.DEVICE_READ),
            // Zone matrix, delivery step 1 (ZONE_MATRIX_CONTRACT.md section 3.4, FROZEN 2026-11-13). The blocks view
            // (?blocks=1) and the lookup are checked again in the controller against zone_matrix_zones_read.
            Map.entry("GET /api/v2/zone-matrix/versions", ActionRegistry.ZONE_MATRIX_READ),
            Map.entry("GET /api/v2/zone-matrix/versions/*", ActionRegistry.ZONE_MATRIX_READ),
            Map.entry("GET /api/v2/zone-matrix/draft", ActionRegistry.ZONE_MATRIX_READ),
            Map.entry("POST /api/v2/zone-matrix/lookup", ActionRegistry.ZONE_MATRIX_ZONES_READ),
            Map.entry("POST /api/v2/zone-matrix/draft/zones", ActionRegistry.ZONE_MATRIX_EDIT),
            Map.entry("PUT /api/v2/zone-matrix/draft/zones/*", ActionRegistry.ZONE_MATRIX_EDIT),
            Map.entry("POST /api/v2/zone-matrix/draft/zones/*/delete", ActionRegistry.ZONE_MATRIX_EDIT),
            Map.entry("POST /api/v2/zone-matrix/draft/discard", ActionRegistry.ZONE_MATRIX_EDIT),
            Map.entry("POST /api/v2/zone-matrix/draft/publish", ActionRegistry.ZONE_MATRIX_PUBLISH),
            // Zone matrix, delivery step 2 (sections 3.4, 4 and 6.1): the matrix, the template and reference confirmation.
            Map.entry("GET /api/v2/zone-matrix/template", ActionRegistry.ZONE_MATRIX_READ),
            Map.entry("POST /api/v2/zone-matrix/draft/cells", ActionRegistry.ZONE_MATRIX_EDIT),
            Map.entry("POST /api/v2/zone-matrix/draft/cells/clear", ActionRegistry.ZONE_MATRIX_EDIT),
            Map.entry("POST /api/v2/zone-matrix/draft/default", ActionRegistry.ZONE_MATRIX_EDIT),
            Map.entry("POST /api/v2/zone-matrix/draft/service-sets", ActionRegistry.ZONE_MATRIX_EDIT),
            Map.entry("POST /api/v2/zone-matrix/draft/service-sets/delete", ActionRegistry.ZONE_MATRIX_EDIT),
            Map.entry("POST /api/v2/zone-matrix/draft/template-import", ActionRegistry.ZONE_MATRIX_EDIT),
            Map.entry("POST /api/v2/zone-matrix/draft/cells/confirm-reference", ActionRegistry.ZONE_MATRIX_REFERENCE_CONFIRM),
            // Zone matrix, delivery step 3 (sections 3.4, 5 and 10.1): stored-policy catalogue and on-demand evaluation.
            Map.entry("GET /api/v2/zone-matrix/policies", ActionRegistry.ZONE_MATRIX_READ),
            Map.entry("POST /api/v2/zone-matrix/evaluate", ActionRegistry.ZONE_MATRIX_EVALUATE),
            // Zone matrix, delivery step 4b-2 (section 5.1): the on-demand durable run, same action as the evaluation.
            Map.entry("POST /api/v2/zone-matrix/runs", ActionRegistry.ZONE_MATRIX_EVALUATE),
            // Zone matrix, delivery step 4b-2b (sections 7.1 and 7.2): the read-only stored summary; names masked in the controller.
            Map.entry("GET /api/v2/zone-matrix/summary", ActionRegistry.ZONE_MATRIX_READ),
            Map.entry("GET /devices/*/lifecycle", ActionRegistry.DEVICE_READ),
            Map.entry("GET /devices/*/software-history", ActionRegistry.DEVICE_READ),
            Map.entry("GET /api/v2/software-history/changes", ActionRegistry.DEVICE_READ),
            Map.entry("GET /api/v2/lifecycle/catalog", ActionRegistry.DEVICE_READ),
            // Advisory matching part 1 (ADVISORY_MATCHING_CONTRACT.md section 8): read access follows the Lifecycle view.
            Map.entry("GET /api/v2/lifecycle/advisories", ActionRegistry.DEVICE_READ),
            Map.entry("GET /api/v2/lifecycle/advisories/*", ActionRegistry.DEVICE_READ),
            Map.entry("GET /api/v2/lifecycle/advisory-sources", ActionRegistry.DEVICE_READ),
            Map.entry("POST /api/v2/lifecycle/catalog", ActionRegistry.LIFECYCLE_CATALOG_WRITE),
            Map.entry("PUT /api/v2/lifecycle/catalog/*", ActionRegistry.LIFECYCLE_CATALOG_WRITE),
            Map.entry("POST /api/v2/lifecycle/catalog/*/delete", ActionRegistry.LIFECYCLE_CATALOG_WRITE),
            Map.entry("POST /api/v2/lifecycle/catalog/import", ActionRegistry.LIFECYCLE_CATALOG_WRITE),
            Map.entry("GET /api/v2/lifecycle/catalog-sources", ActionRegistry.DEVICE_READ),
            Map.entry("POST /api/v2/lifecycle/catalog/vendor-page", ActionRegistry.LIFECYCLE_CATALOG_WRITE),
            Map.entry("GET /api/v2/job-window", ActionRegistry.DEVICE_READ),
            // PO record 2026-11-03 D-8 (path analysis A3): topology read, overrides, rebuild.
            Map.entry("GET /api/v2/topology", ActionRegistry.TOPOLOGY_READ),
            Map.entry("GET /api/v2/topology/overrides", ActionRegistry.TOPOLOGY_READ),
            Map.entry("POST /api/v2/topology/overrides", ActionRegistry.TOPOLOGY_OVERRIDE_WRITE),
            Map.entry("POST /api/v2/topology/overrides/*/revoke", ActionRegistry.TOPOLOGY_OVERRIDE_WRITE),
            Map.entry("POST /api/v2/topology/rebuild", ActionRegistry.TOPOLOGY_REBUILD),
            // PO record 2026-11-03 D-2/D-3/D-8 (path analysis A5a): the read-only path query.
            Map.entry("POST /api/v2/path-analysis/query", ActionRegistry.PATH_ANALYSIS_READ),
            // A6: validation of a just-computed path against the vendors' own read-only matchers.
            Map.entry("POST /api/v2/path-analysis/validate", ActionRegistry.PATH_ANALYSIS_VALIDATE),
            Map.entry("GET /api/v2/path-analysis/validate/*", ActionRegistry.PATH_ANALYSIS_VALIDATE),
            // PO decision record 2026-10-15 §6: Packet Analysis. One id segment per route (single wildcard).
            Map.entry("GET /api/v2/packet-analysis/cases", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("POST /api/v2/packet-analysis/cases", ActionRegistry.PACKET_ANALYSIS_RUN),
            Map.entry("GET /api/v2/packet-analysis/cases/*", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("DELETE /api/v2/packet-analysis/cases/*", ActionRegistry.PACKET_ANALYSIS_RUN),
            Map.entry("POST /api/v2/packet-analysis/cases/*/captures", ActionRegistry.PACKET_ANALYSIS_RUN),
            Map.entry("POST /api/v2/packet-analysis/cases/*/analyze", ActionRegistry.PACKET_ANALYSIS_RUN),
            Map.entry("POST /api/v2/packet-analysis/cases/*/telemetry", ActionRegistry.PACKET_ANALYSIS_RUN),
            Map.entry("PUT /api/v2/packet-analysis/telemetry/*", ActionRegistry.PACKET_ANALYSIS_RUN),
            Map.entry("DELETE /api/v2/packet-analysis/telemetry/*", ActionRegistry.PACKET_ANALYSIS_RUN),
            Map.entry("GET /api/v2/packet-analysis/cases/*/state", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("GET /api/v2/packet-analysis/cases/*/flows", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("GET /api/v2/packet-analysis/cases/*/frames", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("GET /api/v2/packet-analysis/cases/*/diagnosis", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("GET /api/v2/packet-analysis/cases/*/charts", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("GET /api/v2/packet-analysis/cases/*/ladder", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("GET /api/v2/packet-analysis/cases/*/waterfall", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("GET /api/v2/packet-analysis/cases/*/timeseries", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("GET /api/v2/packet-analysis/cases/*/topology/overlay", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("GET /api/v2/packet-analysis/cases/*/topology/inspect", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("GET /api/v2/packet-analysis/cases/*/findings", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("GET /api/v2/packet-analysis/cases/*/events", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("GET /api/v2/packet-analysis/cases/*/dns", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("GET /api/v2/packet-analysis/cases/*/field-diff", ActionRegistry.PACKET_ANALYSIS_RAW_VIEW),
            Map.entry("GET /api/v2/packet-analysis/cases/*/export/findings", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("GET /api/v2/packet-analysis/cases/*/export/download", ActionRegistry.PACKET_ANALYSIS_RAW_VIEW),
            Map.entry("GET /api/v2/packet-analysis/cases/*/export/expert-report", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("GET /api/v2/packet-analysis/cases/*/sessions/evidence", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("GET /api/v2/packet-analysis/cases/*/reference/template", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("POST /api/v2/packet-analysis/cases/*/compare", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("GET /api/v2/packet-analysis/cases/*/correlation", ActionRegistry.PACKET_ANALYSIS_READ),
            // Device events (mockup 2026-10-17 screen 4): gated like the case read; the
            // stored-log facts also need system_log_read (checked in the controller, NOT_PERMITTED rows otherwise).
            Map.entry("GET /api/v2/packet-analysis/cases/*/device-events", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("PUT /api/v2/packet-analysis/cases/*/topology", ActionRegistry.PACKET_ANALYSIS_RUN),
            Map.entry("GET /api/v2/packet-analysis/runs/*", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("GET /api/v2/packet-analysis/runs/*/report", ActionRegistry.PACKET_ANALYSIS_READ),
            Map.entry("GET /api/v2/packet-analysis/captures/*/download", ActionRegistry.PACKET_ANALYSIS_RAW_VIEW),
            // Data Sanitizer (DATA_SANITIZER_CONTRACT.md §3.1). One id segment per route (single wildcard).
            Map.entry("POST /api/v2/sanitize", ActionRegistry.DATA_SANITIZE_RUN),
            Map.entry("GET /api/v2/sanitize/jobs", ActionRegistry.DATA_SANITIZE_INFO),
            Map.entry("GET /api/v2/sanitize/jobs/*", ActionRegistry.DATA_SANITIZE_RUN),
            Map.entry("GET /api/v2/sanitize/jobs/*/download", ActionRegistry.DATA_SANITIZE_RUN),
            Map.entry("POST /api/v2/sanitize/revert", ActionRegistry.DATA_SANITIZE_REVERT),
            Map.entry("GET /api/v2/sanitize/reverts/*", ActionRegistry.DATA_SANITIZE_REVERT),
            Map.entry("GET /api/v2/sanitize/reverts/*/download", ActionRegistry.DATA_SANITIZE_REVERT),
            Map.entry("GET /api/v2/policy/sources", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/policy/domains/*/objects", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/policy/domains/*/object-usage", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/policy/domains/*/objects/duplicates", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/policy/domains/*/installation", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/policy/domains/*/unused", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/policy/domains/*/gateways", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/policy/domains/*/hits", ActionRegistry.POLICY_READ),
            Map.entry("POST /api/v2/policy/policies/*/offline-analyses", ActionRegistry.POLICY_OFFLINE_WRITE),
            Map.entry("GET /api/v2/policy/policies/*/offline-analyses", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/policy/offline-analyses/*", ActionRegistry.POLICY_READ),
            Map.entry("DELETE /api/v2/policy/offline-analyses/*", ActionRegistry.POLICY_OFFLINE_WRITE),
            Map.entry("POST /api/v2/policy/policies/*/lookup", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/cutover/segments", ActionRegistry.CUTOVER_ASSESSMENT_READ),
            Map.entry("POST /api/v2/cutover/segments", ActionRegistry.CUTOVER_ASSESSMENT_WRITE),
            Map.entry("GET /api/v2/cutover/segments/*", ActionRegistry.CUTOVER_ASSESSMENT_READ),
            Map.entry("PUT /api/v2/cutover/segments/*", ActionRegistry.CUTOVER_ASSESSMENT_WRITE),
            Map.entry("DELETE /api/v2/cutover/segments/*", ActionRegistry.CUTOVER_ASSESSMENT_WRITE),
            Map.entry("POST /api/v2/cutover/segments/*/assessments", ActionRegistry.CUTOVER_ASSESSMENT_WRITE),
            Map.entry("GET /api/v2/cutover/assessments/*", ActionRegistry.CUTOVER_ASSESSMENT_READ),
            Map.entry("GET /api/v2/cutover/assessments/*/flows", ActionRegistry.CUTOVER_ASSESSMENT_READ),
            Map.entry("DELETE /api/v2/cutover/assessments/*", ActionRegistry.CUTOVER_ASSESSMENT_WRITE),
            Map.entry("GET /api/v2/policy/hygiene-summary", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/policy/ha-pairs", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/policy/policies/*/hygiene", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/policy/policies/*/hygiene.csv", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/policy/policies/*/insights", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/policy/policies/*/insight", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/policy/policies/*/rules", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/policy/changes", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/policy/changes.csv", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/policy/changes/actor-candidates", ActionRegistry.POLICY_READ),
            // Operations > Change feed: gated like Policy > Changes; the stored-log sources also need system_log_read (checked in the controller).
            Map.entry("GET /api/v2/changes/feed", ActionRegistry.POLICY_READ),
            // Operations > Policy installation, Phase 1 (OPERATIONS_POLICY_INSTALL_P1_MONITORING_CONTRACT_2026_11_18.md): GET-only monitoring.
            Map.entry("GET /api/v2/operations/policy-install/status", ActionRegistry.POLICY_INSTALL_READ),
            // Operations > Static routes, Phase R0 (OPERATIONS_STATIC_ROUTE_R0_READ_COMMANDS_2026_11_18.md): CLASS_0 read-only preview; no write routes exist.
            Map.entry("POST /api/v2/operations/static-route/preview", ActionRegistry.STATIC_ROUTE_PREVIEW),
            Map.entry("GET /api/v2/operations/static-route/preview", ActionRegistry.STATIC_ROUTE_READ),
            Map.entry("GET /api/v2/operations/static-route/preview/*", ActionRegistry.STATIC_ROUTE_READ),
            Map.entry("GET /api/v2/policy/tree", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/policy/collections/*", ActionRegistry.POLICY_READ),
            Map.entry("POST /api/v2/policy/sources/*/collect", ActionRegistry.POLICY_COLLECT),
            Map.entry("GET /api/v2/policy/devices", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/policy/devices/*", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/policy/policies/*", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/policy/policies/*/history", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/policy/objects/*", ActionRegistry.POLICY_READ),
            Map.entry("GET /api/v2/cp-failover/units", ActionRegistry.CP_FAILOVER_READ),
            Map.entry("GET /api/v2/cp-failover/summary", ActionRegistry.CP_FAILOVER_READ),
            Map.entry("GET /api/v2/cp-failover/units/*", ActionRegistry.CP_FAILOVER_READ),
            Map.entry("POST /api/v2/cp-failover/approvals", ActionRegistry.CP_FAILOVER_APPROVE),
            Map.entry("GET /api/v2/cp-failover/approvals", ActionRegistry.CP_FAILOVER_READ),
            Map.entry("POST /api/v2/cp-failover/approvals/*/approve", ActionRegistry.CP_FAILOVER_APPROVE),
            Map.entry("POST /api/v2/cp-failover/approvals/*/revoke", ActionRegistry.CP_FAILOVER_APPROVE),
            Map.entry("POST /api/v2/cp-failover/runs", ActionRegistry.CP_FAILOVER_START),
            Map.entry("POST /api/v2/cp-failover/units/*/readiness", ActionRegistry.CP_READINESS_START),
            Map.entry("POST /api/v2/pan-failover/units/*/readiness", ActionRegistry.CP_READINESS_START),
            Map.entry("GET /api/v2/cp-failover/runs", ActionRegistry.CP_FAILOVER_READ),
            Map.entry("GET /api/v2/cp-failover/runs/*", ActionRegistry.CP_FAILOVER_READ),
            // PO 2026-10-20 item 1: open incidents are read by every failover reader; release is security_admin only.
            Map.entry("GET /api/v2/cp-failover/incidents", ActionRegistry.CP_FAILOVER_READ),
            Map.entry("GET /api/v2/pan-failover/incidents", ActionRegistry.CP_FAILOVER_READ),
            Map.entry("POST /api/v2/cp-failover/runs/*/incident/release", ActionRegistry.CP_FAILOVER_INCIDENT_RELEASE),
            Map.entry("POST /api/v2/cp-failover/runs/*/resume-return", ActionRegistry.CP_FAILOVER_RESUME_RETURN),
            Map.entry("POST /api/v2/cp-failover/runs/*/resume-return/approve", ActionRegistry.CP_FAILOVER_RESUME_RETURN),
            Map.entry("GET /api/v2/pan-failover/units", ActionRegistry.CP_FAILOVER_READ),
            Map.entry("GET /api/v2/pan-failover/units/*", ActionRegistry.CP_FAILOVER_READ),
            Map.entry("GET /api/v2/pan-failover/summary", ActionRegistry.CP_FAILOVER_READ),
            Map.entry("GET /api/v2/pan-failover/runs", ActionRegistry.CP_FAILOVER_READ),
            Map.entry("GET /api/v2/pan-failover/runs/*", ActionRegistry.CP_FAILOVER_READ),
            Map.entry("GET /api/v2/pan-failover/approvals", ActionRegistry.CP_FAILOVER_READ),
            // PO 2026-10-22: PAN failover writes opened for the gartest pilot (same actions and roles as Check Point).
            // The resume-return step stays Check Point only: the PAN executor has no resume path.
            Map.entry("POST /api/v2/pan-failover/approvals", ActionRegistry.CP_FAILOVER_APPROVE),
            Map.entry("POST /api/v2/pan-failover/approvals/*/approve", ActionRegistry.CP_FAILOVER_APPROVE),
            Map.entry("POST /api/v2/pan-failover/approvals/*/revoke", ActionRegistry.CP_FAILOVER_APPROVE),
            Map.entry("POST /api/v2/pan-failover/runs", ActionRegistry.CP_FAILOVER_START),
            Map.entry("POST /api/v2/pan-failover/runs/*/incident/release", ActionRegistry.CP_FAILOVER_INCIDENT_RELEASE),
            // PO 2026-10-24 (PO_DECISION_RECORD_2026_10_24_PAN_CONFIG_SYNC.md): Palo Alto only; the Check Point prefix stays unmapped.
            Map.entry("POST /api/v2/pan-failover/units/*/config-sync", ActionRegistry.PAN_FAILOVER_CONFIG_SYNC),
            Map.entry("GET /api/v2/pan-failover/config-syncs/*", ActionRegistry.CP_FAILOVER_READ),
            // Every failover GET is mapped under both prefixes; the Check Point one answers CONFIG_SYNC_NOT_SUPPORTED.
            Map.entry("GET /api/v2/cp-failover/config-syncs/*", ActionRegistry.CP_FAILOVER_READ),
            // PO_DECISION_REQUEST_2026_11_13_ASA_FAILOVER_READINESS.md: Cisco ASA is read-only plus the readiness request.
            // Every other ASA route (approvals, runs POST, revoke, incident release, resume, config sync) stays unmapped.
            Map.entry("GET /api/v2/asa-failover/units", ActionRegistry.CP_FAILOVER_READ),
            Map.entry("GET /api/v2/asa-failover/units/*", ActionRegistry.CP_FAILOVER_READ),
            Map.entry("GET /api/v2/asa-failover/summary", ActionRegistry.CP_FAILOVER_READ),
            Map.entry("GET /api/v2/asa-failover/runs", ActionRegistry.CP_FAILOVER_READ),
            Map.entry("GET /api/v2/asa-failover/runs/*", ActionRegistry.CP_FAILOVER_READ),
            Map.entry("POST /api/v2/asa-failover/units/*/readiness", ActionRegistry.CP_READINESS_START),
            // PO_DECISION_RECORD_2026_11_17_FGT_FAILOVER_READINESS_READS.md: FortiGate is read-only plus the readiness request, like ASA.
            Map.entry("GET /api/v2/fgt-failover/units", ActionRegistry.CP_FAILOVER_READ),
            Map.entry("GET /api/v2/fgt-failover/units/*", ActionRegistry.CP_FAILOVER_READ),
            Map.entry("GET /api/v2/fgt-failover/summary", ActionRegistry.CP_FAILOVER_READ),
            Map.entry("GET /api/v2/fgt-failover/runs", ActionRegistry.CP_FAILOVER_READ),
            Map.entry("GET /api/v2/fgt-failover/runs/*", ActionRegistry.CP_FAILOVER_READ),
            Map.entry("POST /api/v2/fgt-failover/units/*/readiness", ActionRegistry.CP_READINESS_START),
            Map.entry("POST /role-bindings/selections", ActionRegistry.ROLE_BINDING_CREATE),
            Map.entry("GET /role-bindings", ActionRegistry.ROLE_BINDING_CREATE),
            Map.entry("POST /role-bindings", ActionRegistry.ROLE_BINDING_CREATE),
            Map.entry("POST /role-bindings/revoke", ActionRegistry.ROLE_BINDING_REVOKE),
            Map.entry("GET /sessions", ActionRegistry.SESSION_REVOKE),
            Map.entry("POST /sessions/revoke", ActionRegistry.SESSION_REVOKE),
            Map.entry("POST /devices/add-single", ActionRegistry.DEVICE_REGISTER),
            Map.entry("POST /devices/*/confirm", ActionRegistry.DEVICE_REGISTER),
            // V77: re-admit the step a stopped onboarding flow stopped at.
            Map.entry("POST /devices/*/onboarding/retry", ActionRegistry.DEVICE_REGISTER),
            Map.entry("POST /devices/*/delete", ActionRegistry.DEVICE_DELETE),
            // V64: a device's second secret (a credential-store reference, never a value)
            // One wildcard per route: the interceptor matches a single "*" segment (V64 shipped "secrets/*" and every call was refused).
            Map.entry("POST /devices/*/secrets/export_passphrase", ActionRegistry.DEVICE_REGISTER),
            Map.entry("POST /devices/*/secrets/backup_receiver", ActionRegistry.DEVICE_REGISTER),
            // PO, 2026-09-24: a device's login credential can be changed after it is added.
            Map.entry("PUT /devices/*/credential", ActionRegistry.DEVICE_REGISTER),
            // GET /devices/{id} is this route map's first path-variable route --
            // "GET /devices/*" is GateChainInterceptor's one-segment wildcard
            // suffix, matched only after an exact-route lookup misses.
            Map.entry("GET /devices", ActionRegistry.DEVICE_READ),
            Map.entry("GET /devices/*", ActionRegistry.DEVICE_READ),
            // NXS-LOCAL-0160 "Routes": the id segment is not the route's
            // last segment for these three -- GateChainInterceptor's
            // wildcard fallback tries every interior segment, not only the
            // last one, so a wildcard entry naming the id's own position
            // ("*") is enough; no change to any single-segment route above.
            Map.entry("GET /devices/*/inventory", ActionRegistry.DEVICE_READ),
            Map.entry("GET /devices/*/management-tree", ActionRegistry.DEVICE_READ),
            Map.entry("POST /devices/*/management-tree/acknowledge", ActionRegistry.DISCOVERY_ACKNOWLEDGE),
            Map.entry("POST /devices/*/inventory/collect", ActionRegistry.DEVICE_INVENTORY_COLLECT),
            Map.entry("GET /api/v2/diagnostics/preview", ActionRegistry.FMG_DIAGNOSTIC_READ),
            Map.entry("GET /api/v2/diagnostics/targets", ActionRegistry.FMG_DIAGNOSTIC_READ),
            Map.entry("GET /api/v2/diagnostics/history", ActionRegistry.FMG_DIAGNOSTIC_READ),
            Map.entry("GET /api/v2/diagnostics/ports", ActionRegistry.FMG_DIAGNOSTIC_READ),
            Map.entry("POST /api/v2/diagnostics", ActionRegistry.FMG_DIAGNOSTIC_RUN),
            Map.entry("GET /api/v2/diagnostics/*", ActionRegistry.FMG_DIAGNOSTIC_READ),
            // System-log programme phase A (M3/M4): summaries are a policy-grade read (masked for aiview); starting
            // the measurement for every enrolled Palo Alto firewall is an administrator action.
            Map.entry("GET /api/v2/diagnostics/system-log-measurements", ActionRegistry.POLICY_READ),
            Map.entry("POST /api/v2/diagnostics/system-log-measurements/start", ActionRegistry.SYSTEM_LOG_MEASURE_START),
            // The one-off Spike Detective layout review (phase B contract section 8.3; PO approval 2026-10-19): one Check
            // Point gateway, two read-only listings, a summary row only -- the same read-only measurement action.
            Map.entry("POST /api/v2/diagnostics/system-log-measurements/spike-layout-review", ActionRegistry.SYSTEM_LOG_MEASURE_START),
            // System-log programme phase B: the manual start of the incremental collection (operation_admin, security_admin).
            Map.entry("POST /api/v2/system-logs/collect", ActionRegistry.SYSTEM_LOG_COLLECT_START),
            // Phase B read side (section 14). system_log_read_unmasked is not a route of its own: the text route is a
            // system_log_read, and the controller checks the second action when the caller asks for unmasked text.
            Map.entry("GET /api/v2/system-logs/*/sources", ActionRegistry.SYSTEM_LOG_READ),
            Map.entry("GET /api/v2/system-logs/*/chunks", ActionRegistry.SYSTEM_LOG_READ),
            Map.entry("GET /api/v2/system-logs/chunks/*/text", ActionRegistry.SYSTEM_LOG_READ),
            Map.entry("GET /api/v2/system-logs/gaps", ActionRegistry.SYSTEM_LOG_READ),
            Map.entry("GET /api/v2/system-logs/crash-files", ActionRegistry.SYSTEM_LOG_READ),
            Map.entry("GET /api/v2/system-logs/*/hcp", ActionRegistry.SYSTEM_LOG_READ),
            Map.entry("GET /api/v2/system-logs/*/spike-data", ActionRegistry.SYSTEM_LOG_READ),
            // Operations > System health (docs/design/SYSTEM_HEALTH_SCREEN_2026_10_20.md): counters over the same tables.
            Map.entry("GET /api/v2/system-logs/health", ActionRegistry.SYSTEM_LOG_READ),
            // Log detection engine P1 (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md section 9): findings, chips, coverage and the
            // budget counters are system_log_read (aiview included, masked; the aiview action set is unchanged); the acknowledgement is operation_admin and above; the switch is security_admin.
            Map.entry("GET /api/v2/log-findings", ActionRegistry.SYSTEM_LOG_READ),
            Map.entry("GET /api/v2/log-findings/chips", ActionRegistry.SYSTEM_LOG_READ),
            Map.entry("GET /api/v2/log-findings/coverage", ActionRegistry.SYSTEM_LOG_READ),
            Map.entry("GET /api/v2/log-findings/budget", ActionRegistry.SYSTEM_LOG_READ),
            Map.entry("GET /api/v2/log-findings/*", ActionRegistry.SYSTEM_LOG_READ),
            Map.entry("POST /api/v2/log-findings/*/acknowledge", ActionRegistry.LOG_FINDING_ACKNOWLEDGE),
            Map.entry("GET /api/v2/config/log-detection", ActionRegistry.LOG_DETECTION_SETTINGS_READ),
            Map.entry("PUT /api/v2/config/log-detection", ActionRegistry.LOG_DETECTION_SETTINGS_WRITE),
            // Phase B settings (section 14): the one row of retention, caps and the two switches; security_admin only.
            Map.entry("GET /api/v2/config/system-logs", ActionRegistry.SYSTEM_LOG_SETTINGS_READ),
            Map.entry("PUT /api/v2/config/system-logs", ActionRegistry.SYSTEM_LOG_SETTINGS_WRITE),
            // SNMP polling settings (SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md section 11); security_admin only.
            Map.entry("GET /api/v2/config/snmp-polling", ActionRegistry.SNMP_POLLING_SETTINGS_READ),
            Map.entry("PUT /api/v2/config/snmp-polling", ActionRegistry.SNMP_POLLING_SETTINGS_WRITE),
            // Local-account password policy (off by default); security_admin only.
            Map.entry("GET /api/v2/config/local-password-policy", ActionRegistry.LOCAL_PASSWORD_POLICY_READ),
            Map.entry("PUT /api/v2/config/local-password-policy", ActionRegistry.LOCAL_PASSWORD_POLICY_WRITE),
            // Local-account scope (ALL_LOCAL default, BREAK_GLASS_ONLY optional); security_admin only.
            Map.entry("GET /api/v2/config/local-auth-mode", ActionRegistry.LOCAL_AUTH_MODE_READ),
            Map.entry("PUT /api/v2/config/local-auth-mode", ActionRegistry.LOCAL_AUTH_MODE_WRITE),
            // V203: the per-family scheduled-job switches (Administration > Platform > Scheduled jobs).
            Map.entry("GET /api/v2/config/scheduled-jobs", ActionRegistry.SCHEDULED_JOBS_READ),
            Map.entry("PUT /api/v2/config/scheduled-jobs/*", ActionRegistry.SCHEDULED_JOBS_SET),
            Map.entry("POST /devices/inventory/collect-all", ActionRegistry.DEVICE_INVENTORY_COLLECT),
            Map.entry("GET /clusters/*/inventory", ActionRegistry.DEVICE_READ),
            Map.entry("GET /clusters/*/member-diff", ActionRegistry.DEVICE_READ),
            // NXS-LOCAL-0165 "Routes": same wildcard shapes as the inventory
            // routes above; /devices/*/configuration/text is a four-segment
            // route (GateChainInterceptor tries a single-segment wildcard at
            // every position, so the id at position 2 resolves the same way
            // /discovery/runs/*/import's own position-3 wildcard already does).
            Map.entry("GET /configuration", ActionRegistry.DEVICE_READ),
            Map.entry("GET /devices/*/https-certificate", ActionRegistry.DEVICE_READ),
            Map.entry("POST /devices/*/https-certificate/accept", ActionRegistry.HTTPS_CERTIFICATE_ACCEPT),
            Map.entry("PUT /devices/*/https-certificate/strict", ActionRegistry.HTTPS_CERTIFICATE_STRICT),
            Map.entry("GET /devices/*/configuration", ActionRegistry.DEVICE_READ),
            Map.entry("GET /devices/*/configuration/text", ActionRegistry.DEVICE_CONFIGURATION_TEXT_READ),
            Map.entry("POST /devices/*/configuration/collect", ActionRegistry.DEVICE_CONFIGURATION_COLLECT),
            Map.entry("POST /devices/configuration/collect-all", ActionRegistry.DEVICE_CONFIGURATION_COLLECT),
            Map.entry("GET /compliance", ActionRegistry.DEVICE_READ),
            Map.entry("GET /compliance/overview", ActionRegistry.DEVICE_READ),
            Map.entry("GET /compliance/controls", ActionRegistry.DEVICE_READ),
            Map.entry("GET /api/v2/compliance/controls/*", ActionRegistry.DEVICE_READ),
            Map.entry("GET /devices/*/compliance", ActionRegistry.DEVICE_READ),
            Map.entry("POST /compliance/evaluate", ActionRegistry.DEVICE_READ),
            Map.entry("GET /notifications", ActionRegistry.NOTIFICATIONS_READ),
            Map.entry("GET /roles", ActionRegistry.RBAC_ROLE_READ),
            Map.entry("POST /roles", ActionRegistry.RBAC_ROLE_WRITE),
            Map.entry("PUT /roles/*", ActionRegistry.RBAC_ROLE_WRITE),
            Map.entry("DELETE /roles/*", ActionRegistry.RBAC_ROLE_WRITE),
            Map.entry("GET /api/v2/config/ldap", ActionRegistry.LDAP_CONFIG_READ),
            Map.entry("PUT /api/v2/config/ldap", ActionRegistry.LDAP_CONFIG_WRITE),
            Map.entry("POST /api/v2/config/ldap/test", ActionRegistry.LDAP_CONFIG_WRITE),
            Map.entry("GET /api/v2/config/saml", ActionRegistry.SAML_CONFIG_READ),
            Map.entry("PUT /api/v2/config/saml", ActionRegistry.SAML_CONFIG_WRITE),
            Map.entry("GET /api/v2/ssh-trust", ActionRegistry.SSH_TRUST_READ),
            Map.entry("PUT /api/v2/ssh-trust/mode", ActionRegistry.SSH_TRUST_MANAGE),
            Map.entry("POST /api/v2/ssh-trust/observations/*/accept", ActionRegistry.SSH_TRUST_MANAGE),
            Map.entry("POST /api/v2/ssh-trust/observations/*/reject", ActionRegistry.SSH_TRUST_MANAGE),
            Map.entry("GET /api/v2/config/notifications", ActionRegistry.NOTIFICATION_CONFIG_READ),
            Map.entry("PUT /api/v2/config/notifications", ActionRegistry.NOTIFICATION_CONFIG_WRITE),
            Map.entry("POST /api/v2/config/notifications/test-syslog", ActionRegistry.NOTIFICATION_CONFIG_WRITE),
            Map.entry("POST /api/v2/config/notifications/test-mail", ActionRegistry.NOTIFICATION_CONFIG_WRITE),
            Map.entry("GET /api/v2/system/pods", ActionRegistry.SYSTEM_STATUS_READ),
            Map.entry("GET /api/v2/overview", ActionRegistry.SYSTEM_STATUS_READ),
            Map.entry("GET /api/v2/estate/as-of", ActionRegistry.DEVICE_READ),
            Map.entry("GET /api/v2/search", ActionRegistry.GLOBAL_SEARCH_READ),
            Map.entry("GET /api/v2/system/storage", ActionRegistry.SYSTEM_STATUS_READ),
            Map.entry("GET /api/v2/security/scan-summary/latest", ActionRegistry.SECURITY_SCAN_SUMMARY_READ),
            Map.entry("GET /audit-logs", ActionRegistry.AUDIT_LOG_READ),
            Map.entry("POST /api/v2/jobs/*/cancel", ActionRegistry.JOB_CANCEL),
            Map.entry("POST /api/v2/jobs/*/reconcile", ActionRegistry.JOB_RECONCILE),
            Map.entry("GET /api/v2/jobs", ActionRegistry.JOB_LOG_READ),
            Map.entry("GET /api/v2/jobs/facets", ActionRegistry.JOB_LOG_READ),
            Map.entry("GET /api/v2/jobs/stats", ActionRegistry.JOB_LOG_READ),
            Map.entry("GET /api/v2/jobs/export.csv", ActionRegistry.JOB_LOG_READ),
            Map.entry("GET /jobs/*/transcript", ActionRegistry.JOB_TRANSCRIPT_READ),
            Map.entry("GET /api/v2/jobs/*/transcript/health", ActionRegistry.JOB_LOG_READ),
            // 13G: one resource, body-only (no path variable), matching
            // /role-bindings/revoke's own shape.
            Map.entry("POST /local-identities", ActionRegistry.LOCAL_IDENTITY_CREATE),
            Map.entry("GET /local-identities", ActionRegistry.LOCAL_IDENTITY_LIST),
            Map.entry("POST /local-identities/set-password", ActionRegistry.LOCAL_IDENTITY_SET_PASSWORD),
            Map.entry("POST /local-identities/disable", ActionRegistry.LOCAL_IDENTITY_DISABLE),
            Map.entry("POST /local-identities/enable", ActionRegistry.LOCAL_IDENTITY_ENABLE),
            // 2026-09-14 CS-1..CS-5: one resource, body-only (no path
            // variable), matching /local-identities' own shape.
            Map.entry("POST /credentials", ActionRegistry.CREDENTIAL_CREATE),
            Map.entry("GET /credentials", ActionRegistry.CREDENTIAL_LIST),
            Map.entry("POST /credentials/replace-secret", ActionRegistry.CREDENTIAL_REPLACE_SECRET),
            Map.entry("POST /credentials/delete", ActionRegistry.CREDENTIAL_DELETE),
            // 14F section 3: body-only start (no path variable, matching
            // /devices/add-single's own shape), a single-segment wildcard
            // read, and a two-segment-deep wildcard import (the run id is
            // not the route's last segment, matching /devices/*/inventory/
            // collect's own wildcard shape above).
            Map.entry("POST /discovery/ssh-trust/enroll", ActionRegistry.DISCOVERY_SSH_TRUST_ENROLL),
            Map.entry("POST /discovery/ssh-trust/re-enroll", ActionRegistry.DISCOVERY_SSH_TRUST_RE_ENROLL),
            Map.entry("POST /discovery/pan-trust/enroll", ActionRegistry.DISCOVERY_PAN_TRUST_ENROLL),
            Map.entry("POST /discovery/pan-trust/re-enroll", ActionRegistry.DISCOVERY_PAN_TRUST_RE_ENROLL),
            Map.entry("POST /discovery/runs", ActionRegistry.DISCOVERY_RUN_START),
            Map.entry("GET /discovery/runs/*", ActionRegistry.DISCOVERY_RUN_READ),
            Map.entry("POST /discovery/runs/*/import", ActionRegistry.DISCOVERY_RUN_IMPORT),
            // NXS-LOCAL-0175 "Service and screen": same wildcard shapes as
            // the inventory/configuration routes above. BK-14: no route
            // here ever returns an artefact byte, path or decrypt
            // affordance -- these three surface posture rows only.
            Map.entry("POST /devices/*/backup/collect", ActionRegistry.DEVICE_BACKUP_COLLECT),
            Map.entry("GET /devices/*/backups", ActionRegistry.DEVICE_BACKUP_READ),
            Map.entry("GET /backups", ActionRegistry.DEVICE_BACKUP_READ),
            Map.entry("POST /backups/collect-all", ActionRegistry.DEVICE_BACKUP_COLLECT),
            Map.entry("POST /api/v2/backups/*/run", ActionRegistry.DEVICE_BACKUP_COLLECT),
            Map.entry("PUT /devices/*/backup-target", ActionRegistry.DEVICE_BACKUP_TARGET_SET),
            // PO decision record 2026-09-22: the one route that returns artefact bytes, role-gated.
            Map.entry("POST /backups/*/download", ActionRegistry.DEVICE_BACKUP_RETRIEVE),
            Map.entry("POST /backups/*/download-ticket", ActionRegistry.DEVICE_BACKUP_RETRIEVE),
            Map.entry("GET /backups/*/download", ActionRegistry.DEVICE_BACKUP_RETRIEVE),
            // V41 content listing and compare: posture reads (names/sizes/digests, never content).
            Map.entry("GET /backups/*/entries", ActionRegistry.DEVICE_BACKUP_READ),
            Map.entry("GET /backups/*/compare", ActionRegistry.DEVICE_BACKUP_READ),
            // "List now" decrypts on the service: gated like the download.
            Map.entry("POST /backups/*/relist", ActionRegistry.DEVICE_BACKUP_RETRIEVE),
            // V70: remove a backup whose device no longer exists (the worker deletes it).
            Map.entry("POST /backups/*/delete", ActionRegistry.DEVICE_BACKUP_ORPHAN_DELETE),
            // Measured live (2026-09-22): both routes answered 403 ACTION_MAPPING_REQUIRED, so the
            // Backups screen read "Policy unavailable" -- the routes existed, their mapping did not.
            Map.entry("GET /api/v2/backups/policies", ActionRegistry.DEVICE_BACKUP_READ),
            Map.entry("PUT /api/v2/backups/policies", ActionRegistry.DEVICE_BACKUP_POLICY_SET),
            // V225: per-store storage quota; read is the backup read, write is backup_admin.
            Map.entry("GET /api/v2/backups/store-quotas", ActionRegistry.DEVICE_BACKUP_READ),
            Map.entry("PUT /api/v2/backups/store-quotas/*", ActionRegistry.BACKUP_STORE_QUOTA_SET),
            Map.entry("PUT /devices/*/backup-baseline", ActionRegistry.DEVICE_BACKUP_BASELINE_SET),
            Map.entry("GET /api/v2/backups/deviations", ActionRegistry.DEVICE_BACKUP_READ),
            Map.entry("POST /api/v2/backups/deviations/size-anomalies/*/accept", ActionRegistry.BACKUP_SIZE_ANOMALY_ACCEPT),
            // WORKER.md (movement NXS-LOCAL-0174): body-only, no path variable,
            // same shape as /notifications' own route.
            // Support bundle collection (SUPPORT_BUNDLE_COLLECTION_DESIGN_2026_11_14.md 5.10.1).
            Map.entry("GET /api/v2/support-bundles", ActionRegistry.SUPPORT_BUNDLE_READ),
            Map.entry("GET /api/v2/support-bundles/catalogue", ActionRegistry.SUPPORT_BUNDLE_READ),
            Map.entry("GET /api/v2/support-bundles/*", ActionRegistry.SUPPORT_BUNDLE_READ),
            Map.entry("GET /api/v2/devices/*/support-bundles", ActionRegistry.SUPPORT_BUNDLE_READ),
            Map.entry("POST /api/v2/devices/*/support-bundles", ActionRegistry.SUPPORT_BUNDLE_REQUEST),
            Map.entry("POST /api/v2/support-bundles/*/cancel", ActionRegistry.SUPPORT_BUNDLE_CANCEL),
            Map.entry("POST /api/v2/support-bundles/*/download-ticket", ActionRegistry.SUPPORT_BUNDLE_RETRIEVE),
            Map.entry("GET /api/v2/support-bundles/*/download", ActionRegistry.SUPPORT_BUNDLE_RETRIEVE),
            Map.entry("POST /api/v2/support-bundles/*/download", ActionRegistry.SUPPORT_BUNDLE_RETRIEVE),
            Map.entry("POST /api/v2/support-bundles/*/delete", ActionRegistry.SUPPORT_BUNDLE_DELETE),
            Map.entry("POST /api/v2/support-bundles/*/cleanup-workdir", ActionRegistry.SUPPORT_BUNDLE_CLEANUP_WORKDIR),
            Map.entry("POST /api/v2/support-bundles/*/uploads", ActionRegistry.SUPPORT_BUNDLE_UPLOAD),
            Map.entry("GET /api/v2/support-bundles/*/uploads", ActionRegistry.SUPPORT_BUNDLE_READ),
            Map.entry("GET /api/v2/support-bundles/upload-destinations", ActionRegistry.SUPPORT_BUNDLE_READ),
            // Reports R1 (REPORTS_R1_CONTRACT.md): catalogue, preview and history read; generate and download.
            Map.entry("GET /api/v2/reports/catalogue", ActionRegistry.REPORT_READ),
            Map.entry("POST /api/v2/reports/preview", ActionRegistry.REPORT_READ),
            Map.entry("GET /api/v2/reports/runs", ActionRegistry.REPORT_READ),
            Map.entry("GET /api/v2/reports/runs/*", ActionRegistry.REPORT_READ),
            Map.entry("POST /api/v2/reports/runs", ActionRegistry.REPORT_GENERATE),
            Map.entry("GET /api/v2/reports/runs/*/download", ActionRegistry.REPORT_GENERATE),
            // Script Execution slice 1 & 2 (SCRIPT_EXECUTION_CONTRACT.md section 3.6, PO decision 2026-11-18).
            Map.entry("GET /api/v2/scripts", ActionRegistry.SCRIPT_READ),
            Map.entry("GET /api/v2/scripts/*", ActionRegistry.SCRIPT_READ),
            Map.entry("POST /api/v2/scripts", ActionRegistry.SCRIPT_WRITE),
            Map.entry("POST /api/v2/scripts/*/versions", ActionRegistry.SCRIPT_WRITE),
            Map.entry("PATCH /api/v2/scripts/*", ActionRegistry.SCRIPT_WRITE),
            Map.entry("POST /api/v2/scripts/*/enable", ActionRegistry.SCRIPT_WRITE),
            Map.entry("POST /api/v2/scripts/*/disable", ActionRegistry.SCRIPT_WRITE),
            Map.entry("POST /api/v2/scripts/*/run", ActionRegistry.SCRIPT_RUN),
            Map.entry("GET /api/v2/scripts/*/runs", ActionRegistry.SCRIPT_READ),
            Map.entry("GET /api/v2/scripts/runs/*", ActionRegistry.SCRIPT_READ),
            Map.entry("GET /api/v2/scripts/runs/*/output", ActionRegistry.SCRIPT_OUTPUT_READ),
            Map.entry("GET /project-plan", ActionRegistry.PROJECT_PLAN_READ));

    /**
     * Routes whose endpoint performs its own deliberately non-RBAC security
     * check. Every other route must have an action mapping and is refused by
     * {@link GateChainInterceptor}; this prevents a new controller from
     * becoming open merely because its mapping was omitted here.
     */
    static final Set<String> EXPLICITLY_UNGATED_ROUTES = ungated();

    private static Set<String> ungated() {
        var routes = new java.util.HashSet<>(Set.of(
                "GET /",
                "POST /login",
                "POST /internal/machine-session",
                "POST /auth/login",
                "POST /login/resolve",
                "POST /session/logout",
                "GET /session/status",
                "GET /error",
                "POST /error",
                "POST /local-credentials/change-password",
                "GET /healthz",
                // PO 2026-11-15: HEAD answers like GET (Spring MVC serves HEAD from the GET handler).
                "HEAD /healthz",
                // Kubelet probes (deploy/ui2/50-service-deployment.yaml): /livez shallow, /readyz database check.
                "GET /livez",
                "HEAD /livez",
                "GET /readyz",
                "HEAD /readyz",
                // Migration plan Step 4: the SAML 2.0 handshake runs before any session exists. SamlLoginController answers 404
                // to every one of them while SAML is switched off.
                "GET " + SamlSettingsService.AUTHENTICATE_PATH,
                "POST " + SamlSettingsService.ACS_PATH,
                "GET " + SamlSettingsService.METADATA_PATH));
        // Advisory ingest (ADVISORY_MATCHING_CONTRACT.md section 4): internal port only, authenticated by the fetcher's own
        // token. The lookup is exact, so each source key is listed; an unknown source stays unmapped and is refused.
        for (String source : com.securityexpert.nexus.ui2.service.advisorystore.AdvisorySources.KEYS) {
            routes.add("POST /internal/advisories/" + source);
            routes.add("POST /internal/advisories/" + source + "/complete");
        }
        // Lifecycle-source ingest (LIFECYCLE_CATALOG_SOURCES_CONTRACT.md section 3.1): the same token and port.
        for (String source : com.securityexpert.nexus.ui2.service.lifecyclesource.LifecycleSources.FEED_KEYS) {
            routes.add("POST /internal/lifecycle-sources/" + source);
            routes.add("POST /internal/lifecycle-sources/" + source + "/complete");
        }
        // Script Execution broker (SCRIPT_EXECUTION_CONTRACT.md Amendment 2026-11-18 (b) item 4): internal port only,
        // authenticated by the broker's own token and the job lease it presents.
        for (String path : com.securityexpert.nexus.ui2.service.api.ScriptRunnerInternalController.PATHS) {
            routes.add("POST " + path);
        }
        return Set.copyOf(routes);
    }

    /** The route decision both the interceptor and the Spring Security manager use, over this class's own route map. */
    public static RouteActionGate routeGate(GateChain gateChain, LocalIdentityResolver localIdentityResolver,
            LocalRoleTokenResolver localRoleTokenResolver) {
        return new RouteActionGate(gateChain, ACTION_ID_BY_ROUTE, EXPLICITLY_UNGATED_ROUTES, localIdentityResolver,
                localRoleTokenResolver);
    }

    private final GateChain gateChain;
    private final LocalIdentityResolver localIdentityResolver;
    private final LocalRoleTokenResolver localRoleTokenResolver;
    private final AdminWindowBypass adminWindowBypass;
    private final boolean registerGateInterceptor;

    public SecurityWebMvcConfig(GateChain gateChain) {
        this(gateChain, null, null);
    }

    public SecurityWebMvcConfig(GateChain gateChain, LocalIdentityResolver localIdentityResolver,
            LocalRoleTokenResolver localRoleTokenResolver) {
        this(gateChain, localIdentityResolver, localRoleTokenResolver, null);
    }

    /** @param adminWindowBypass PO 2026-10-20: when set, {@link ManualAdmissionInterceptor} runs after the gate chain */
    public SecurityWebMvcConfig(GateChain gateChain, LocalIdentityResolver localIdentityResolver,
            LocalRoleTokenResolver localRoleTokenResolver, AdminWindowBypass adminWindowBypass) {
        this(gateChain, localIdentityResolver, localRoleTokenResolver, adminWindowBypass, true);
    }

    /**
     * @param registerGateInterceptor {@code false} once {@link NexusRouteActionAuthorizationManager} decides the route in
     *     the security filter chain (migration plan Step 5); {@code true} is the rollback path and the default
     */
    public SecurityWebMvcConfig(GateChain gateChain, LocalIdentityResolver localIdentityResolver,
            LocalRoleTokenResolver localRoleTokenResolver, AdminWindowBypass adminWindowBypass,
            boolean registerGateInterceptor) {
        this.registerGateInterceptor = registerGateInterceptor;
        this.gateChain = gateChain;
        this.localIdentityResolver = localIdentityResolver;
        this.localRoleTokenResolver = localRoleTokenResolver;
        this.adminWindowBypass = adminWindowBypass;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (registerGateInterceptor) {
            registry.addInterceptor(new GateChainInterceptor(gateChain, ACTION_ID_BY_ROUTE, EXPLICITLY_UNGATED_ROUTES,
                    localIdentityResolver, localRoleTokenResolver));
        }
        // Registration order is execution order: the grant is only ever opened for a request the gate chain passed.
        if (adminWindowBypass != null) registry.addInterceptor(new ManualAdmissionInterceptor(adminWindowBypass));
    }
}
