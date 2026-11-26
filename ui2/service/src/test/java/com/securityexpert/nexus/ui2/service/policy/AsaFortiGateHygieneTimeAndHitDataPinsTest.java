package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule;
import com.securityexpert.nexus.ui2.policy.asa.AsaConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyMapper;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGateConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyMapper;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Hygiene-side pins for two frozen ASA/FortiGate semantics of
 * {@code docs/design/PATH_ANALYSIS_VENDOR_SEMANTICS_AMENDMENT_2026_11_14_ASA_FORTIGATE.md} (FROZEN):
 * <ul>
 * <li>A0-FG-5 "schedule has no default" and A0-ASA-8 "time-range": the lookup already refuses to read these as "always"
 * (the conformance classes pin it); here the rule's hygiene time status ({@link PolicyRuleMetrics#time},
 * {@link PolicyHygiene.Assessment#timeStatus()}) is pinned to the same fail-closed reading.</li>
 * <li>"No hit data" (design section 5.4 item 8): local ASA and FortiGate snapshots carry no hit counters, so hygiene shows
 * "no hit data" (an UNKNOWN finding, hit source UNKNOWN) and never a hit-derived "unused" finding. The configuration-derived
 * ASA {@code ACL_NOT_BOUND} "unused" finding is a different finding and stays.</li>
 * </ul>
 * Synthetic configurations only (masked names, documentation addresses). Automated validation only.
 */
class AsaFortiGateHygieneTimeAndHitDataPinsTest {
    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final String NO_HITS = "Hit counts were not collected or are invalid.";
    private static final String SCHEDULE_UNKNOWN = "Schedule evidence is incomplete.";

    // ---- fixtures (small copies of the shapes the A0 conformance classes use) -------------------------------------------

    private static final String ASA_BASE = """
            ASA Version 9.18(4)22
            interface GigabitEthernet0/0
             nameif outside
             security-level 0
            interface GigabitEthernet0/1
             nameif inside
             security-level 100
            """;

    private static PolicySnapshot asa(String body) {
        return AsaPolicyMapper.map("dev-asa-1", "FW-TANGO-04", "2026-11-14T02:00:00Z", "artefact", AsaConfigPolicyParser.parse(ASA_BASE + body));
    }

    private static final String FGT_BODY = """
            #config-version=FGVM64-7.4.8-FW-build2795-250521:opmode=0:vdom=0:user=admin
            config system interface
                edit "port1"
                    set vdom "root"
                    set ip 192.0.2.1 255.255.255.0
                next
                edit "port2"
                    set vdom "root"
                    set ip 198.51.100.1 255.255.255.0
                next
            end
            config firewall address
                edit "all"
                next
                edit "HOST-WEB"
                    set subnet 198.51.100.20 255.255.255.255
                next
            end
            config firewall service custom
                edit "HTTPS"
                    set tcp-portrange 443
                next
            end
            """;

    /** One FortiOS 7.4 policy; {@code scheduleLine} is the whole line ("" = no schedule line, as {@code show} prints it). */
    private static PolicySnapshot fgt(String scheduleLine) {
        String config = FGT_BODY + """
                config firewall policy
                    edit 1
                        set srcintf "port1"
                        set dstintf "port2"
                        set srcaddr "all"
                        set dstaddr "HOST-WEB"
                        set service "HTTPS"
                        set action accept
                """ + scheduleLine + """
                    next
                end
                """;
        var snapshots = FortiGatePolicyMapper.map("dev-fgt-1", "FW-ROMEO-01", "2026-11-14T02:00:00Z", "artefact",
                FortiGateConfigPolicyParser.parse(config));
        assertEquals(1, snapshots.size());
        return snapshots.get(0);
    }

    private static List<Rule> rules(PolicySnapshot s, String source) {
        return s.sections().stream().filter(x -> source.equals(x.source())).findFirst().orElseThrow().rules();
    }

    private static PolicyHygiene.Assessment assess(PolicySnapshot s, Rule rule) {
        return PolicyHygiene.analyze(s, 90, NOW, PolicyHygiene.TOTAL_WORK).rules().get(rule.id());
    }

    private static boolean has(PolicyHygiene.Assessment a, String findingClass, String evidence) {
        return a.findings().stream().anyMatch(f -> f.findingClass().equals(findingClass) && f.evidence().equals(evidence));
    }

    // ---- A0-FG-5: a missing schedule is never "always" ------------------------------------------------------------------

    @Nested @DisplayName("A0-FG-5 hygiene")
    class Fg5Hygiene {
        @Test void aPolicyWithNoScheduleLineReadsUnknownNeverAlways() {
            var s = fgt("");
            var rule = rules(s, "FGT firewall policy").get(0);
            assertEquals(List.of("schedule"), rule.extras().get("fgt-missing-default"));
            assertFalse(rule.extras().containsKey("time"), "no schedule was collected, so none is recorded");

            assertEquals("unknown", PolicyRuleMetrics.time(rule, s.objects(), NOW, s.metadata().vendor()).status());
            var a = assess(s, rule);
            assertEquals("unknown", a.timeStatus(), "a rule whose schedule was not collected is not shown as always active");
            assertFalse(a.expiring());
            assertTrue(has(a, "unknown", SCHEDULE_UNKNOWN));
        }

        @Test void anExplicitAlwaysScheduleStillReadsAlways() {
            var s = fgt("        set schedule \"always\"\n");
            var rule = rules(s, "FGT firewall policy").get(0);
            assertNull(rule.extras().get("fgt-missing-default"));
            var a = assess(s, rule);
            assertEquals("always", a.timeStatus());
            assertFalse(a.findings().stream().anyMatch(f -> f.evidence().equals(SCHEDULE_UNKNOWN)));
        }

        @Test void aNamedScheduleWhoseObjectWasNotCollectedReadsUnknown() {
            var s = fgt("        set schedule \"SCH-OFFICE\"\n");
            var rule = rules(s, "FGT firewall policy").get(0);
            assertEquals(List.of("SCH-OFFICE"), rule.extras().get("time"));
            assertEquals("unknown", assess(s, rule).timeStatus());
        }
    }

    // ---- A0-ASA-8: time-range ---------------------------------------------------------------------------------------------

    @Nested @DisplayName("A0-ASA-8 hygiene")
    class Asa8Hygiene {
        @Test void aTimeRangeWhoseObjectIsAbsentReadsUnknownNeverAlways() {
            var s = asa("""
                    access-list ACL-OUT extended permit tcp any host 198.51.100.20 eq 443 time-range TR-OFFICE
                    access-group ACL-OUT in interface outside
                    """);
            var rule = firstSecurityRule(s);
            assertEquals(List.of("TR-OFFICE"), rule.extras().get("time"));
            assertTrue(s.objects().values().stream().noneMatch(o -> o.name().equals("TR-OFFICE") && o.status().equals("RESOLVED")),
                    "the range object is not resolved in the snapshot");
            assertEquals("unknown", PolicyRuleMetrics.time(rule, s.objects(), NOW, s.metadata().vendor()).status());
            var a = assess(s, rule);
            assertEquals("unknown", a.timeStatus());
            assertTrue(has(a, "unknown", SCHEDULE_UNKNOWN));
        }

        @Test void aTimeRangeDefinedInTheConfigurationIsNeverReadAsAlwaysEither() {
            // The parser keeps no schedule for a time-range block (it is on its skip list), so the reference stays unresolved.
            var s = asa("""
                    time-range TR-MAINT
                     periodic daily 01:00 to 02:00
                    access-list ACL-OUT extended permit tcp any host 198.51.100.20 eq 443 time-range TR-MAINT
                    access-group ACL-OUT in interface outside
                    """);
            var rule = firstSecurityRule(s);
            assertEquals(List.of("TR-MAINT"), rule.extras().get("time"));
            assertEquals("unknown", assess(s, rule).timeStatus());
        }

        @Test void aPlainAceWithoutATimeRangeReadsAlways() {
            var s = asa("""
                    access-list ACL-OUT extended permit tcp any host 198.51.100.20 eq 443
                    access-group ACL-OUT in interface outside
                    """);
            var rule = firstSecurityRule(s);
            assertFalse(rule.extras().containsKey("time"));
            var a = assess(s, rule);
            assertEquals("always", a.timeStatus());
            assertFalse(a.findings().stream().anyMatch(f -> f.evidence().equals(SCHEDULE_UNKNOWN)));
        }
    }

    private static Rule firstSecurityRule(PolicySnapshot s) {
        return s.sections().stream().filter(x -> !PolicySnapshot.outsideSecurityEvaluation(s.metadata(), x))
                .flatMap(x -> x.rules().stream()).filter(r -> r.extras().containsKey("asa-acl")).findFirst().orElseThrow();
    }

    // ---- "No hit data" ------------------------------------------------------------------------------------------------------

    private static PolicySnapshot asaWithBoundAndOrphanAcl() {
        return asa("""
                access-list ACL-OUT extended permit tcp any host 198.51.100.20 eq 443
                access-list ACL-ORPHAN extended permit tcp any host 198.51.100.21 eq 22
                access-group ACL-OUT in interface outside
                """);
    }

    private static void assertNoHitDerivedUnused(PolicySnapshot s) {
        var result = PolicyHygiene.analyze(s, 90, NOW, PolicyHygiene.TOTAL_WORK);
        assertFalse(result.rules().isEmpty());
        for (var a : result.rules().values()) {
            assertTrue(a.findings().stream().noneMatch(f -> f.findingClass().equals("unused") && PolicyHygiene.HIT_EVIDENCE.contains(f.evidence())),
                    "no hit counter was collected, so nothing can be hit-derived unused: " + a.findings());
            assertEquals("UNKNOWN", a.hitSource(), "the hit evidence source reads UNKNOWN");
            assertNull(a.hitsCollectedAt());
        }
    }

    private final PolicyQueryService query = spy(new PolicyQueryService(mock(TransactionBoundary.class), new ObjectMapper(),
            new TopologyNamePseudonymizer(new byte[32])));

    @Nested @DisplayName("no hit data")
    class NoHitData {
        @Test void anAsaSnapshotShowsNoHitDataAndNeverHitDerivedUnused() {
            var s = asaWithBoundAndOrphanAcl();
            assertNoHitDerivedUnused(s);

            var bound = firstSecurityRule(s);
            assertTrue(has(assess(s, bound), "unknown", NO_HITS), "the enforced rule carries the existing no-hit-data unknown finding");
            assertFalse(assess(s, bound).findings().stream().anyMatch(f -> f.findingClass().equals("unused")));

            // The configuration-derived finding is a different one: an ACL no access-group binds is "unused" on configuration
            // evidence, never on hit evidence, and it is not part of HIT_EVIDENCE.
            var orphan = s.sections().stream().flatMap(x -> x.rules().stream())
                    .filter(r -> List.of("none").equals(r.extras().get("asa-acl-use"))).findFirst().orElseThrow();
            var o = assess(s, orphan);
            assertTrue(has(o, "unused", PolicyHygiene.ASA_ACL_NOT_REFERENCED));
            assertFalse(PolicyHygiene.HIT_EVIDENCE.contains(PolicyHygiene.ASA_ACL_NOT_REFERENCED));

            var report = query.hygieneReport(s, 90);
            assertEquals(0, report.hitUnused(), "no hit-derived unused is counted");
            assertTrue(report.hitOnlyUnknown() >= 1, "the no-hit-data unknown is counted as hit-derived unknown");
            assertEquals(1, report.classes().get("unused"), "the one unused is the ACL_NOT_BOUND configuration finding");
        }

        @Test void aFortiGateSnapshotShowsNoHitDataAndNeverHitDerivedUnused() {
            var s = fgt("        set schedule \"always\"\n");
            assertNoHitDerivedUnused(s);
            var a = assess(s, rules(s, "FGT firewall policy").get(0));
            assertTrue(has(a, "unknown", NO_HITS));
            assertFalse(a.findings().stream().anyMatch(f -> f.findingClass().equals("unused")));

            var report = query.hygieneReport(s, 90);
            assertEquals(0, report.hitUnused());
            assertTrue(report.hitOnlyUnknown() >= 1);
            assertEquals(0, report.classes().get("unused"));
        }

        @Test void theFleetSummaryCountsNoUnusedForTheHitlessFortiGateAndOnlyTheConfigurationUnusedForAsa() {
            var asaSnapshot = asaWithBoundAndOrphanAcl();
            var fgtSnapshot = fgt("        set schedule \"always\"\n");
            doReturn(List.of(asaSnapshot.metadata(), fgtSnapshot.metadata())).when(query).catalog();
            doReturn(Optional.of(asaSnapshot)).when(query).find(asaSnapshot.metadata().id());
            doReturn(Optional.of(fgtSnapshot)).when(query).find(fgtSnapshot.metadata().id());
            var summary = new PolicyHygieneFleetSummary(query);
            while (summary.refreshNext()) { /* one container per pass */ }

            @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) summary.view().get("containers");
            assertEquals(2, rows.size());
            int asaUnused = -1, fgtUnused = -1;
            for (var row : rows) {
                assertEquals("READY", row.get("state"));
                @SuppressWarnings("unchecked") var counts = (Map<String, Integer>) row.get("counts");
                if (row.get("sourceId").equals("dev-asa-1")) asaUnused = counts.get("unused"); else fgtUnused = counts.get("unused");
            }
            assertEquals(1, asaUnused, "ASA: only the configuration-derived ACL_NOT_BOUND finding");
            assertEquals(0, fgtUnused, "FortiGate: no hit data is never shown as unused");
        }
    }
}
