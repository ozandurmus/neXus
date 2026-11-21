package com.securityexpert.nexus.ui2.service.estate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.api.EstateAsOfController;
import com.securityexpert.nexus.ui2.service.estate.EstateAsOfService.BackupRow;
import com.securityexpert.nexus.ui2.service.estate.EstateAsOfService.ComplianceRow;
import com.securityexpert.nexus.ui2.service.estate.EstateAsOfService.ConfigRunRow;
import com.securityexpert.nexus.ui2.service.estate.EstateAsOfService.DeviceMeta;
import com.securityexpert.nexus.ui2.service.estate.EstateAsOfService.EvidenceSnapshot;
import com.securityexpert.nexus.ui2.service.estate.EstateAsOfService.InvalidMomentException;
import com.securityexpert.nexus.ui2.service.estate.EstateAsOfService.ReadinessRow;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

class EstateAsOfServiceTest {

    private static byte[] syntheticHmacBytes() {
        byte[] out = new byte[32];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (i + 17);
        }
        return out;
    }

    private static final byte[] SYNTHETIC_HMAC_KEY = syntheticHmacBytes();

    private static DeviceSummaryRecord device(
            String id,
            String role,
            String vendor,
            String hostname,
            String model,
            String clusterRef,
            String virtualSystems,
            String mgmtIp,
            boolean backupTarget
    ) {
        return new DeviceSummaryRecord(
                id,
                role,
                vendor,
                DeviceEnrollmentState.ENROLLED,
                Optional.ofNullable(hostname),
                Optional.ofNullable(model),
                Optional.of("R81.20"),
                Optional.of("ACTIVE"),
                Optional.ofNullable(clusterRef),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.ofNullable(virtualSystems),
                Optional.ofNullable(mgmtIp),
                Optional.empty(),
                backupTarget,
                false
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void answersExactReadinessBackupAndComplianceAtMomentAndReportsUnknownWhereHistoryDoesNotReach() {
        Instant now = Instant.parse("2026-11-20T12:00:00Z");
        Instant at = Instant.parse("2026-11-12T02:00:00Z");
        Instant enrolledEarly = Instant.parse("2026-10-01T00:00:00Z");
        Instant enrolledLate = Instant.parse("2026-11-19T08:00:00Z");

        // 1. Cluster pair 1 (Check Point): READY 2 hours before 'at', backup created 1 hour before 'at' (pruned later,
        //    so still_stored_now=false), compliance evaluated before 'at' with 2 PASS, 1 CRITICAL FAIL, 1 NO_EVIDENCE.
        DeviceSummaryRecord cpActive = device("dev-cp-01", "gateway", "check_point", "SYNTH-GW-ALPHA-01", "6600",
                "SYNTH-CLS-ALPHA", null, "192.0.2.11", true);
        DeviceSummaryRecord cpStandby = device("dev-cp-02", "gateway", "check_point", "SYNTH-GW-ALPHA-02", "6600",
                "SYNTH-CLS-ALPHA", null, "192.0.2.12", true);

        // 2. Cluster pair 2 (Palo Alto): readiness first ran AFTER 'at' (so store returns nothing <= at -> UNKNOWN),
        //    backup is 30 hours old at 'at' (STALE) with an open size_anomaly, compliance was refreshed after 'at'
        //    by the 6h warmup on the SAME canonical_hash as the pre-'at' config run (MATCHING_CONFIG_HASH).
        DeviceSummaryRecord panActive = device("dev-pan-01", "gateway", "palo_alto", "SYNTH-PAN-BRAVO-01", "PA-5420",
                "SYNTH-CLS-BRAVO", null, "198.51.100.21", true);

        // 3. Standalone ASA gateway: no cluster (NOT_APPLICABLE failover), backup target with NO backup <= at (MISSING),
        //    configuration changed AFTER 'at' overwriting compliance_evaluation -> UNKNOWN (EVALUATION_OVERWRITTEN).
        DeviceSummaryRecord asaSolo = device("dev-asa-01", "gateway", "cisco_asa", "SYNTH-ASA-CHARLIE-01", "FPR-2130",
                null, null, "192.0.2.31", true);

        // 4. Management server: NOT_APPLICABLE on failover, backup (not a target), and compliance.
        DeviceSummaryRecord mds = device("dev-mds-01", "management_server", "check_point", "SYNTH-MDS-01", "MDS",
                null, null, "192.0.2.41", false);

        // 5. Newly enrolled firewall (created_at > at): enrolled_at_moment=false -> all three lenses UNKNOWN.
        DeviceSummaryRecord lateFw = device("dev-fgt-late", "gateway", "fortinet", "SYNTH-FGT-DELTA-01", "FG-600F",
                "SYNTH-CLS-DELTA", null, "198.51.100.51", true);

        Map<String, DeviceMeta> meta = Map.of(
                "dev-cp-01", new DeviceMeta("dev-cp-01", enrolledEarly, false),
                "dev-cp-02", new DeviceMeta("dev-cp-02", enrolledEarly, false),
                "dev-pan-01", new DeviceMeta("dev-pan-01", enrolledEarly, false),
                "dev-asa-01", new DeviceMeta("dev-asa-01", enrolledEarly, false),
                "dev-mds-01", new DeviceMeta("dev-mds-01", enrolledEarly, false),
                "dev-fgt-late", new DeviceMeta("dev-fgt-late", enrolledLate, false)
        );

        List<ReadinessRow> readiness = List.of(
                new ReadinessRow("run-cp-1", "check_point", "SYNTH-CLS-ALPHA", null, "DONE", "READY",
                        Instant.parse("2026-11-12T00:00:00Z"), null, "READY")
        );

        Map<String, BackupRow> backups = Map.of(
                "dev-cp-01", new BackupRow("dev-cp-01", "art-cp-1", Instant.parse("2026-11-12T01:00:00Z"),
                        104_857_600L, "standard", "unchanged", false),
                "dev-cp-02", new BackupRow("dev-cp-02", "art-cp-2", Instant.parse("2026-11-12T01:05:00Z"),
                        104_857_600L, "standard", "unchanged", true),
                "dev-pan-01", new BackupRow("dev-pan-01", "art-pan-1", Instant.parse("2026-11-10T18:00:00Z"),
                        52_428_800L, "standard", "changed", true)
        );

        Map<String, ConfigRunRow> configs = Map.of(
                "dev-cp-01", new ConfigRunRow("dev-cp-01", "cfg-cp-1", "hash-cp-v1", Instant.parse("2026-11-11T18:00:00Z")),
                "dev-pan-01", new ConfigRunRow("dev-pan-01", "cfg-pan-1", "hash-pan-same", Instant.parse("2026-11-11T06:00:00Z")),
                "dev-asa-01", new ConfigRunRow("dev-asa-01", "cfg-asa-old", "hash-asa-old", Instant.parse("2026-11-11T12:00:00Z"))
        );

        String cpComplianceJson = """
                {"items":[
                  {"controlId":"CP-1","severity":"HIGH","displayStatus":"PASS"},
                  {"controlId":"CP-2","severity":"MEDIUM","displayStatus":"PASS"},
                  {"controlId":"CP-3","severity":"CRITICAL","displayStatus":"FAIL"},
                  {"controlId":"CP-4","severity":"LOW","displayStatus":"DATA_UNAVAILABLE"},
                  {"controlId":"CP-5","severity":"LOW","displayStatus":"NOT_APPLICABLE"}
                ]}""";
        String panComplianceJson = """
                {"items":[
                  {"controlId":"PAN-1","severity":"CRITICAL","displayStatus":"PASS"},
                  {"controlId":"PAN-2","severity":"HIGH","displayStatus":"PASS"}
                ]}""";

        Map<String, ComplianceRow> compliance = Map.of(
                "dev-cp-01", new ComplianceRow("dev-cp-01", "hash-cp-v1", "cat-1",
                        Instant.parse("2026-11-11T18:05:00Z"), cpComplianceJson),
                // Refreshed after 'at' (2026-11-20), same canonical_hash as 2026-11-11T06:00:00Z config run
                "dev-pan-01", new ComplianceRow("dev-pan-01", "hash-pan-same", "cat-1",
                        Instant.parse("2026-11-20T06:00:00Z"), panComplianceJson),
                // Overwritten after 'at' by hash-asa-new != hash-asa-old
                "dev-asa-01", new ComplianceRow("dev-asa-01", "hash-asa-new", "cat-1",
                        Instant.parse("2026-11-18T00:10:00Z"), panComplianceJson)
        );

        EstateAsOfService service = new EstateAsOfService(
                requestedAt -> {
                    assertEquals(at, requestedAt);
                    return new EvidenceSnapshot(meta, readiness, backups, Set.of("dev-pan-01"), configs, compliance);
                },
                () -> List.of(cpActive, cpStandby, panActive, asaSolo, mds, lateFw),
                new TopologyNamePseudonymizer(SYNTHETIC_HMAC_KEY),
                () -> now
        );

        EstateAsOfResponse response = service.asOf(at, false);
        assertFalse(response.masked());
        Map<String, Object> body = response.body();
        assertEquals("2026-11-12T02:00:00Z", body.get("as_of"));
        assertEquals("2026-11-20T12:00:00Z", body.get("generated_at"));

        Map<String, Object> summary = (Map<String, Object>) body.get("summary");
        assertEquals(6, summary.get("total_units"));
        assertEquals(5, summary.get("enrolled_at_moment"));
        assertEquals(1, summary.get("enrolled_after_moment"));

        List<Map<String, Object>> units = (List<Map<String, Object>>) body.get("units");
        Map<String, Map<String, Object>> byId = units.stream()
                .collect(java.util.stream.Collectors.toMap(u -> (String) u.get("device_id"), u -> u));

        // Check Point active member: exact READY (2h = 7200s), PRESENT backup (1h = 3600s, pruned since), exact compliance
        Map<String, Object> cp1 = byId.get("dev-cp-01");
        Map<String, Object> cp1Fo = (Map<String, Object>) cp1.get("failover");
        assertEquals("READY", cp1Fo.get("outcome"));
        assertEquals("READY", cp1Fo.get("verdict"));
        assertEquals(Boolean.FALSE, cp1Fo.get("stale"));
        assertEquals("2026-11-12T00:00:00Z", cp1Fo.get("evidence_at"));
        assertEquals(7200L, cp1Fo.get("age_seconds"));

        Map<String, Object> cp1Bk = (Map<String, Object>) cp1.get("backup");
        assertEquals("PRESENT", cp1Bk.get("state"));
        assertEquals("OK", cp1Bk.get("verdict"));
        assertEquals(Boolean.FALSE, cp1Bk.get("still_stored_now"));
        assertEquals("2026-11-12T01:00:00Z", cp1Bk.get("evidence_at"));
        assertEquals(3600L, cp1Bk.get("age_seconds"));

        Map<String, Object> cp1Cmp = (Map<String, Object>) cp1.get("compliance");
        assertEquals("EVIDENCED", cp1Cmp.get("state"));
        assertEquals("FAIL", cp1Cmp.get("verdict"));
        assertEquals("EVALUATED_AT_OR_BEFORE", cp1Cmp.get("basis"));
        assertEquals(2, cp1Cmp.get("pass_count"));
        assertEquals(1, cp1Cmp.get("fail_count"));
        assertEquals(1, cp1Cmp.get("no_evidence_count"));
        assertEquals(1, cp1Cmp.get("critical_fail_count"));
        assertEquals("2026-11-11T18:00:00Z", cp1Cmp.get("evidence_at"));
        assertEquals(28_800L, cp1Cmp.get("age_seconds"));

        // Palo Alto: readiness UNKNOWN (no run <= at), backup PRESENT + STALE + ANOMALY (32h = 115200s), compliance MATCHING_CONFIG_HASH
        Map<String, Object> pan1 = byId.get("dev-pan-01");
        Map<String, Object> pan1Fo = (Map<String, Object>) pan1.get("failover");
        assertEquals("UNKNOWN", pan1Fo.get("outcome"));
        assertEquals("NO_READINESS_BEFORE_AT", pan1Fo.get("reason"));
        assertNull(pan1Fo.get("evidence_at"));

        Map<String, Object> pan1Bk = (Map<String, Object>) pan1.get("backup");
        assertEquals("PRESENT", pan1Bk.get("state"));
        assertEquals("ANOMALY", pan1Bk.get("verdict"));
        assertEquals(Boolean.TRUE, pan1Bk.get("stale"));
        assertEquals(Boolean.TRUE, pan1Bk.get("open_size_anomaly"));
        assertEquals(115_200L, pan1Bk.get("age_seconds"));

        Map<String, Object> pan1Cmp = (Map<String, Object>) pan1.get("compliance");
        assertEquals("EVIDENCED", pan1Cmp.get("state"));
        assertEquals("PASS", pan1Cmp.get("verdict"));
        assertEquals("MATCHING_CONFIG_HASH", pan1Cmp.get("basis"));
        assertEquals(2, pan1Cmp.get("pass_count"));
        assertEquals(0, pan1Cmp.get("fail_count"));
        assertEquals("2026-11-11T06:00:00Z", pan1Cmp.get("evidence_at"));

        // Cisco ASA: failover NOT_APPLICABLE, backup MISSING, compliance UNKNOWN (EVALUATION_OVERWRITTEN)
        Map<String, Object> asa1 = byId.get("dev-asa-01");
        assertEquals("NOT_APPLICABLE", ((Map<String, Object>) asa1.get("failover")).get("outcome"));
        assertEquals("MISSING", ((Map<String, Object>) asa1.get("backup")).get("state"));
        Map<String, Object> asa1Cmp = (Map<String, Object>) asa1.get("compliance");
        assertEquals("UNKNOWN", asa1Cmp.get("state"));
        assertEquals("EVALUATION_OVERWRITTEN", asa1Cmp.get("reason"));
        assertNull(asa1Cmp.get("pass_count"));
        assertNull(asa1Cmp.get("evidence_at"));

        // Management server: NOT_APPLICABLE on all three lenses
        Map<String, Object> mdsRow = byId.get("dev-mds-01");
        assertEquals("NOT_APPLICABLE", ((Map<String, Object>) mdsRow.get("failover")).get("outcome"));
        assertEquals("NOT_APPLICABLE", ((Map<String, Object>) mdsRow.get("backup")).get("state"));
        assertEquals("NOT_APPLICABLE", ((Map<String, Object>) mdsRow.get("compliance")).get("state"));

        // Post-'at' enrolled device: enrolled_at_moment=false -> UNKNOWN on all three lenses, never MISSING backup
        Map<String, Object> lateRow = byId.get("dev-fgt-late");
        assertEquals(Boolean.FALSE, lateRow.get("enrolled_at_moment"));
        assertEquals("UNKNOWN", ((Map<String, Object>) lateRow.get("failover")).get("state"));
        assertEquals("ENROLLED_AFTER_MOMENT", ((Map<String, Object>) lateRow.get("failover")).get("reason"));
        assertEquals("UNKNOWN", ((Map<String, Object>) lateRow.get("backup")).get("state"));
        assertEquals("ENROLLED_AFTER_MOMENT", ((Map<String, Object>) lateRow.get("backup")).get("reason"));
        assertEquals("UNKNOWN", ((Map<String, Object>) lateRow.get("compliance")).get("state"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void vsxClusterPropagatesNotReadyAndMissingVirtualSystemReadinessAcrossSubUnits() {
        Instant at = Instant.parse("2026-11-12T02:00:00Z");
        DeviceSummaryRecord vsx1 = device("dev-vsx-01", "gateway", "check_point", "FW-TANGO-01", "26000",
                "CLS-ROMEO-01", "VS-CORE (VSID 1), VS-DMZ (VSID 2)", "192.0.2.61", false);
        DeviceSummaryRecord vsx2 = device("dev-vsx-02", "gateway", "check_point", "FW-TANGO-02", "26000",
                "CLS-ROMEO-01", "VS-CORE (VSID 1), VS-DMZ (VSID 2)", "192.0.2.62", false);

        // Chassis READY (30h old -> STALE), VS 1 NOT_READY, VS 2 never checked
        List<ReadinessRow> readiness = List.of(
                new ReadinessRow("r-chassis", "check_point", "CLS-ROMEO-01", null, "DONE", "READY",
                        Instant.parse("2026-11-10T18:00:00Z"), null, "READY"),
                new ReadinessRow("r-vs1", "check_point", "CLS-ROMEO-01", "1", "DONE", "NOT_READY",
                        Instant.parse("2026-11-12T01:30:00Z"), "cp_policy_sync", "NOT_READY")
        );

        EstateAsOfService service = new EstateAsOfService(
                ignored -> new EvidenceSnapshot(Map.of(), readiness, Map.of(), Set.of(), Map.of(), Map.of()),
                () -> List.of(vsx1, vsx2),
                new TopologyNamePseudonymizer(SYNTHETIC_HMAC_KEY),
                () -> Instant.parse("2026-11-20T00:00:00Z")
        );

        Map<String, Object> body = service.asOf(at, true).body();
        List<Map<String, Object>> units = (List<Map<String, Object>>) body.get("units");
        Map<String, Object> fo = (Map<String, Object>) units.get(0).get("failover");
        assertEquals("NOT_READY", fo.get("outcome"));
        assertEquals("NOT_READY", fo.get("verdict"));
        assertEquals("cp_policy_sync", fo.get("failed_check"));
        assertEquals(0, fo.get("ready_sub_units"));
        assertEquals(1, fo.get("stale_sub_units"));
        assertEquals(1, fo.get("not_ready_sub_units"));
        assertEquals(1, fo.get("unknown_sub_units"));
        assertEquals("2026-11-12T01:30:00Z", fo.get("evidence_at"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aiviewMaskingProducesDeterministicPseudonymsNeverDoubleMasksAndRefusesUnmaskedLeak() {
        Instant at = Instant.parse("2026-11-12T02:00:00Z");
        TopologyNamePseudonymizer pseudonymizer = new TopologyNamePseudonymizer(SYNTHETIC_HMAC_KEY);
        DeviceSummaryRecord member = device("dev-mask-01", "gateway", "check_point", "SYNTH-RAW-EDGE-01", "6600",
                "SYNTH-RAW-CLUSTER", null, "192.0.2.71", false);
        DeviceSummaryRecord ipFallbackHost = device("dev-mask-02", "gateway", "palo_alto", "198.51.100.99", "PA-3410",
                null, null, "198.51.100.99", false);

        EstateAsOfService service = new EstateAsOfService(
                ignored -> new EvidenceSnapshot(Map.of(), List.of(), Map.of(), Set.of(), Map.of(), Map.of()),
                () -> List.of(member, ipFallbackHost),
                pseudonymizer,
                () -> Instant.parse("2026-11-20T00:00:00Z")
        );

        EstateAsOfController controller = new EstateAsOfController(service);
        MockHttpServletRequest aiviewReq = new MockHttpServletRequest("GET", "/api/v2/estate/as-of");
        aiviewReq.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, Boolean.TRUE);

        var entity = controller.asOf("2026-11-12T02:00:00+00:00", aiviewReq);
        assertEquals(200, entity.getStatusCode().value());
        String cacheControl = entity.getHeaders().getCacheControl();
        assertNotNull(cacheControl);
        assertTrue(cacheControl.contains("no-cache") && !cacheControl.contains("max-age"));
        assertEquals(List.of("Cookie"), entity.getHeaders().getVary());

        EstateAsOfResponse wrapped = entity.getBody();
        assertNotNull(wrapped);
        assertTrue(wrapped.masked());

        List<Map<String, Object>> unitsBeforeAdvice = (List<Map<String, Object>>) wrapped.body().get("units");
        String maskedHostBefore = (String) unitsBeforeAdvice.get(0).get("hostname");
        String maskedClusterBefore = (String) unitsBeforeAdvice.get(0).get("cluster_member_ref");
        String ipFallbackMasked = (String) unitsBeforeAdvice.get(1).get("hostname");

        assertTrue(maskedHostBefore.startsWith("FW-"), maskedHostBefore);
        assertTrue(maskedClusterBefore.startsWith("CLS-"), maskedClusterBefore);
        assertTrue(ipFallbackMasked.startsWith("UNKNOWN-HOST-"), ipFallbackMasked);

        // Pass through PrivacyMaskingResponseBodyAdvice: header is set, pseudonyms stay identical (no double-masking),
        // and no raw synthetic token survives anywhere in the serialized body.
        PrivacyMaskingResponseBodyAdvice advice = new PrivacyMaskingResponseBodyAdvice(
                new SubnetPreservingIpMasker(SYNTHETIC_HMAC_KEY), pseudonymizer);
        MockHttpServletResponse servletResp = new MockHttpServletResponse();
        Object written = advice.beforeBodyWrite(
                wrapped,
                null,
                MediaType.APPLICATION_JSON,
                StringHttpMessageConverter.class,
                new ServletServerHttpRequest(aiviewReq),
                new ServletServerHttpResponse(servletResp)
        );
        assertEquals("true", servletResp.getHeader(PrivacyMaskingResponseBodyAdvice.MASKED_HEADER));
        String fullText = String.valueOf(written);
        assertFalse(fullText.contains("SYNTH-RAW-EDGE-01"), fullText);
        assertFalse(fullText.contains("SYNTH-RAW-CLUSTER"), fullText);
        assertFalse(fullText.contains("198.51.100.99"), fullText);

        List<Map<String, Object>> unitsAfterAdvice = (List<Map<String, Object>>) ((Map<String, Object>) written).get("units");
        assertEquals(maskedHostBefore, unitsAfterAdvice.get(0).get("hostname"));
        assertEquals(maskedClusterBefore, unitsAfterAdvice.get(0).get("cluster_member_ref"));

        // Fail-closed guard: an unmasked EstateAsOfResponse can never pass through to a replay_viewer session.
        EstateAsOfResponse unmasked = service.asOf(at, false);
        assertThrows(IllegalStateException.class, () -> advice.beforeBodyWrite(
                unmasked,
                null,
                MediaType.APPLICATION_JSON,
                StringHttpMessageConverter.class,
                new ServletServerHttpRequest(aiviewReq),
                new ServletServerHttpResponse(new MockHttpServletResponse())
        ));
    }

    @Test
    @SuppressWarnings("unchecked")
    void warnsRatherThanRefusingFutureTimestampAndRejectsMalformedIsoTimestamp() {
        Instant now = Instant.parse("2026-11-20T12:00:00Z");
        EstateAsOfService service = new EstateAsOfService(
                ignored -> new EvidenceSnapshot(Map.of(), List.of(), Map.of(), Set.of(), Map.of(), Map.of()),
                List::of,
                new TopologyNamePseudonymizer(SYNTHETIC_HMAC_KEY),
                () -> now
        );
        EstateAsOfController controller = new EstateAsOfController(service);

        // Future timestamp warns (PO 2026-11-18 rule: warn, do not refuse on policy grounds)
        var futureResp = controller.asOf("2026-11-25T00:00:00Z", new MockHttpServletRequest());
        assertEquals(200, futureResp.getStatusCode().value());
        List<Map<String, Object>> warnings = (List<Map<String, Object>>) futureResp.getBody().body().get("warnings");
        assertEquals(1, warnings.size());
        assertEquals("AT_IN_FUTURE", warnings.get(0).get("code"));

        // Missing or malformed timestamp returns 400 TIME_INVALID
        for (String bad : new String[] {null, "", "   ", "not-a-timestamp", "2026-11-12 02:00:00"}) {
            InvalidMomentException ex = assertThrows(InvalidMomentException.class, () -> EstateAsOfService.parseAt(bad));
            var errResp = controller.invalidMoment(ex);
            assertEquals(400, errResp.getStatusCode().value());
            assertEquals("TIME_INVALID", errResp.getBody().get("code"));
        }
    }
}
