package com.securityexpert.nexus.ui2.service.zonematrix;

import static org.junit.jupiter.api.Assertions.*;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.*;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Step 3 scale measurement and budget gate ({@code docs/design/ZONE_MATRIX_CONTRACT.md} sections 5.10, 9 {@code ZM-12}
 * and 10.1 item 3, FROZEN 2026-11-13).
 *
 * <p>Generates 10 synthetic policies modelling the estate's 10 largest policies (up to 16,500 rules, Check Point
 * multi-layer + inline layer + NAT and Palo Alto Panorama Pre/Post + NAT) against a published zone-matrix version at the
 * contract's full scale ceiling (500 zones, 200,000 disjoint IPv4 {@code /30} blocks, 1,200 explicit matrix cells).
 * Asserts the {@code ZM-12} budgets (&lt; 30 s and &lt; 512 MB heap delta for the largest 16,500-rule policy; &lt; 180 s
 * across all 10 policies) and emits counts-only timing metrics.</p>
 */
class ZoneMatrixScaleMeasurementTest {
    private static final Instant NOW = Instant.parse("2026-11-15T09:00:00Z");
    private static final Cell ANY_CELL = new Cell(List.of("obj-any"), false);

    @Test
    void zm12_tenLargestSyntheticPoliciesAgainstFiveHundredZonesAndTwoHundredThousandBlocksStayWithinBudget() {
        ZoneMatrixContent maxScaleMatrix = buildFiveHundredZoneTwoHundredThousandBlockMatrix();
        assertEquals(500, maxScaleMatrix.zones().size());

        long compileStart = System.nanoTime();
        CompiledVersion compiled = ZoneMatrixEvaluator.compile("00000000-0000-4000-8000-000000500200", 1, maxScaleMatrix);
        long compileMs = (System.nanoTime() - compileStart) / 1_000_000L;

        // 10 synthetic policies spanning 2,500 up to 16,500 rules (both CP and PAN shapes)
        int[] ruleSizes = {16_500, 14_000, 12_000, 10_500, 9_000, 7_500, 6_000, 5_000, 3_500, 2_500};
        String[] vendors = {"CP", "PAN", "CP", "PAN", "CP", "PAN", "CP", "PAN", "CP", "PAN"};

        List<PolicySnapshot> policies = new ArrayList<>(ruleSizes.length);
        for (int i = 0; i < ruleSizes.length; i++) {
            policies.add(buildSyntheticLargePolicy(i + 1, vendors[i], ruleSizes[i]));
        }

        // Warm up JIT on the smallest policy once so heap and wall-clock measurements reflect steady-state JVM execution
        ZoneMatrixEvaluator.evaluate(policies.get(policies.size() - 1), compiled, NOW);

        System.gc();
        long heapBeforeLargest = usedHeapBytes();
        long fleetStart = System.nanoTime();

        long largestDurationMs = 0;
        long largestHygieneMs = 0;
        long peakHeapDeltaBytes = 0;
        List<EvaluationResult> results = new ArrayList<>(policies.size());

        for (int i = 0; i < policies.size(); i++) {
            PolicySnapshot snap = policies.get(i);
            long before = System.nanoTime();
            EvaluationResult res = ZoneMatrixEvaluator.evaluate(snap, compiled, NOW);
            long elapsedMs = (System.nanoTime() - before) / 1_000_000L;
            long heapAfter = usedHeapBytes();
            long delta = Math.max(0L, heapAfter - heapBeforeLargest);
            if (i == 0) {
                largestDurationMs = elapsedMs;
                largestHygieneMs = res.hygieneMillis();
                peakHeapDeltaBytes = delta;
            }
            results.add(res);
            long covered = res.rules().stream().filter(r -> r.flags().contains("COVERED_BY_EARLIER_DENY")).count();
            long idIgnored = res.rules().stream().filter(r -> r.flags().contains("IDENTITY_IGNORED")).count();
            long timeIgnored = res.rules().stream().filter(r -> r.flags().contains("TIME_IGNORED")).count();
            System.out.printf(Locale.ROOT,
                    "ZM_SCALE_ROW index=%d vendor=%s rules=%d duration_ms=%d hygiene_ms=%d violation=%d compliant=%d not_evaluable=%d unknown=%d not_applicable=%d covered=%d id_ignored=%d time_ignored=%d%n",
                    i + 1,
                    snap.metadata().vendor(),
                    snap.ruleCount(),
                    elapsedMs,
                    res.hygieneMillis(),
                    res.outcomeCounts().getOrDefault(ZoneMatrixEvaluator.OUTCOME_VIOLATION, 0),
                    res.outcomeCounts().getOrDefault(ZoneMatrixEvaluator.OUTCOME_COMPLIANT, 0),
                    res.outcomeCounts().getOrDefault(ZoneMatrixEvaluator.OUTCOME_NOT_EVALUABLE, 0),
                    res.outcomeCounts().getOrDefault(ZoneMatrixEvaluator.OUTCOME_UNKNOWN, 0),
                    res.notApplicable().total(),
                    covered,
                    idIgnored,
                    timeIgnored);
        }

        long totalFleetMs = (System.nanoTime() - fleetStart) / 1_000_000L;
        Headline fleetHeadline = ZoneMatrixEvaluator.buildHeadline(results);
        long peakHeapDeltaMb = peakHeapDeltaBytes / (1024L * 1024L);

        System.out.printf(Locale.ROOT,
                "ZM_SCALE_SUMMARY compile_ms=%d largest_16500_ms=%d largest_hygiene_ms=%d largest_heap_delta_mb=%d ten_policies_total_ms=%d fleet_violating_rules=%d fleet_violated_pairs=%d%n",
                compileMs, largestDurationMs, largestHygieneMs, peakHeapDeltaMb, totalFleetMs,
                fleetHeadline.distinctViolatingRules(), fleetHeadline.violatedZonePairs());

        // ZM-12 contract gates: < 30,000 ms and < 512 MB heap delta for 16,500-rule policy; < 180,000 ms for 10 policies
        assertTrue(largestDurationMs < 30_000L,
                "ZM-12: 16,500-rule policy exceeded 30s budget: " + largestDurationMs + " ms");
        assertTrue(peakHeapDeltaMb < 512L,
                "ZM-12: 16,500-rule policy exceeded 512 MB heap budget: " + peakHeapDeltaMb + " MB");
        assertTrue(totalFleetMs < 180_000L,
                "ZM-12: 10 largest policies exceeded 180s budget: " + totalFleetMs + " ms");
        assertTrue(results.get(0).outcomeCounts().getOrDefault(ZoneMatrixEvaluator.OUTCOME_VIOLATION, 0) > 0);
        assertTrue(results.get(0).outcomeCounts().getOrDefault(ZoneMatrixEvaluator.OUTCOME_COMPLIANT, 0) > 0);
    }

    private static long usedHeapBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /**
     * Builds a synthetic 500-zone, 200,000-block {@link ZoneMatrixContent} inside RFC 2544 benchmarking space
     * ({@code 198.18.0.0/15} plus {@code 100.64.0.0/10} CGNAT synthetic test prefixes — 200,000 disjoint {@code /30}s,
     * 400 blocks per zone) and 1,200 explicit matrix cells.
     */
    private static ZoneMatrixContent buildFiveHundredZoneTwoHundredThousandBlockMatrix() {
        List<ZoneMatrixContent.Zone> zones = new ArrayList<>(500);
        // Base 100.64.0.0 = 0x64400000L has 4,194,304 addresses (1,048,576 /30 blocks), plenty for 200,000 disjoint /30s
        long baseIp = (100L << 24) | (64L << 16);
        int blockCursor = 0;
        for (int z = 0; z < 500; z++) {
            String zoneId = String.format(Locale.ROOT, "00000000-0000-4000-8000-%012d", z + 1);
            List<ZoneBlock> blocks = new ArrayList<>(400);
            for (int b = 0; b < 400; b++) {
                long addr = baseIp + ((long) blockCursor * 4L);
                blockCursor++;
                blocks.add(new ZoneBlock(ZoneBlock.Family.IPV4, BigInteger.valueOf(addr), 30));
            }
            zones.add(new ZoneMatrixContent.Zone(zoneId, "Z-SYNTH-" + (z + 1), "", "APPLICATION", blocks));
        }

        List<ZoneMatrixContent.ServiceSet> sets = List.of(
                new ZoneMatrixContent.ServiceSet("web", List.of("tcp/80", "tcp/443")),
                new ZoneMatrixContent.ServiceSet("db", List.of("tcp/1521", "tcp/5432")));

        List<ZoneMatrixContent.Cell> cells = new ArrayList<>(1200);
        for (int i = 0; i < 400; i++) {
            String src = zones.get(i).id();
            String dstAllowListed = zones.get((i + 1) % 500).id();
            String dstAllowAny = zones.get((i + 2) % 500).id();
            String dstCriticalDeny = zones.get((i + 3) % 500).id();
            cells.add(new ZoneMatrixContent.Cell(src, dstAllowListed, "ALLOW_LISTED", List.of("set:web", "tcp/8443"),
                    "HIGH", "synthetic tier", "", "EMPTY", null, null));
            cells.add(new ZoneMatrixContent.Cell(src, dstAllowAny, "ALLOW_ANY", List.of(),
                    "MEDIUM", "synthetic peer", "", "EMPTY", null, null));
            cells.add(new ZoneMatrixContent.Cell(src, dstCriticalDeny, "DENY_ALL", List.of(),
                    "CRITICAL", "synthetic isolation", "", "EMPTY", null, null));
        }
        ZoneMatrixContent.Matrix matrix = new ZoneMatrixContent.Matrix("DENY_ALL", "HIGH", null, sets, cells);
        return new ZoneMatrixContent(zones, matrix);
    }

    private static PolicySnapshot buildSyntheticLargePolicy(int index, String vendor, int totalRules) {
        Map<String, PolicyObject> objects = new LinkedHashMap<>(1500);
        objects.put("obj-any", new PolicyObject("obj-any", "ANY", "any", List.of(), List.of("any"), "RESOLVED"));
        objects.put("svc-https", new PolicyObject("svc-https", "HTTPS", "service", List.of(),
                "CP".equals(vendor) ? List.of("protocol: tcp", "port: 443") : List.of("protocol/tcp/port: 443"), "RESOLVED"));
        objects.put("svc-web-extra", new PolicyObject("svc-web-extra", "SSH", "service", List.of(),
                "CP".equals(vendor) ? List.of("protocol: tcp", "port: 22") : List.of("protocol/tcp/port: 22"), "RESOLVED"));
        objects.put("svc-dns", new PolicyObject("svc-dns", "DNS", "service", List.of(),
                "CP".equals(vendor) ? List.of("protocol: udp", "port: 53") : List.of("protocol/udp/port: 53"), "RESOLVED"));
        objects.put("v6-doc", new PolicyObject("v6-doc", "V6-DOC", "address", List.of(),
                List.of("ipv6-address: 2001:db8::10"), "RESOLVED"));
        objects.put("cp-role", new PolicyObject("cp-role", "SYNTH-ROLE", "access-role", List.of(),
                List.of("networks: addr-0"), "DYNAMIC"));
        objects.put("sched-recurring", new PolicyObject("sched-recurring", "BUSINESS-HOURS",
                "CP".equals(vendor) ? "time" : "schedule", List.of(), List.of("recurring: weekly"), "RESOLVED"));

        long baseIp = (100L << 24) | (64L << 16);
        // 500 host/subnet address objects + 100 address groups spanning the 500 synthetic zones
        for (int z = 0; z < 500; z++) {
            long zoneStart = baseIp + ((long) z * 400L * 4L);
            String ip = ((zoneStart >>> 24) & 0xFF) + "." + ((zoneStart >>> 16) & 0xFF) + "."
                    + ((zoneStart >>> 8) & 0xFF) + "." + (zoneStart & 0xFF);
            List<String> addrValues = "CP".equals(vendor)
                    ? List.of("subnet4: " + ip, "mask-length4: 26")
                    : List.of("ip-netmask: " + ip + "/26");
            objects.put("addr-" + z, new PolicyObject("addr-" + z, "NET-" + z,
                    "CP".equals(vendor) ? "network" : "address", List.of(), addrValues, "RESOLVED"));
        }
        for (int g = 0; g < 100; g++) {
            int z1 = (g * 5) % 500;
            int z2 = (g * 5 + 1) % 500;
            objects.put("grp-" + g, new PolicyObject("grp-" + g, "GRP-" + g, "group",
                    List.of("addr-" + z1, "addr-" + z2), List.of(), "RESOLVED"));
        }

        int natCount = Math.min(200, totalRules / 20);
        int secCount = totalRules - natCount;
        List<Rule> secRules = new ArrayList<>(secCount);
        String allowAction = "CP".equals(vendor) ? "Accept" : "allow";
        String denyAction = "CP".equals(vendor) ? "Drop" : "deny";

        String metaId = "synth-pol-" + index;
        Map<String, List<String>> secExtras = "CP".equals(vendor)
                ? Map.of("layer-name", List.of("Network"), "install-on", List.of("obj-any"), "time", List.of("obj-any"))
                : Map.of("from", List.of("any"), "to", List.of("any"));

        for (int r = 0; r < secCount; r++) {
            int zSrc = r % 400;
            String id = "p" + index + "-r" + (r + 1);
            Boolean enabled = (r % 25 == 0) ? Boolean.FALSE : Boolean.TRUE;
            boolean seedBroadDeny = (r < 400) && (r % 8 == 2);
            String action = seedBroadDeny || (!((r >= 400) && (r % 8 == 2)) && (r % 5 == 0)) ? denyAction : allowAction;
            Cell srcCell = (r % 37 == 0) ? new Cell(List.of("v6-doc"), false)
                    : ("CP".equals(vendor) && r % 23 == 3) ? new Cell(List.of("cp-role"), false)
                    : (r % 12 == 0) ? new Cell(List.of("grp-" + (r % 100)), false)
                    : new Cell(List.of("addr-" + zSrc), false);
            int dstZone = switch (r % 4) {
                case 0 -> (zSrc + 1) % 500; // ALLOW_LISTED
                case 1 -> (zSrc + 2) % 500; // ALLOW_ANY
                default -> (zSrc + 3) % 500; // DENY_ALL
            };
            Cell dstCell = new Cell(List.of("addr-" + dstZone), false);
            Cell svcCell = seedBroadDeny ? ANY_CELL
                    : (r % 3 == 0) ? new Cell(List.of("svc-https"), false)
                    : new Cell(List.of("svc-https", "svc-web-extra"), false);
            Map<String, List<String>> ruleExtras = secExtras;
            if (r % 29 == 7) {
                ruleExtras = "CP".equals(vendor)
                        ? Map.of("layer-name", List.of("Network"), "install-on", List.of("obj-any"), "time", List.of("sched-recurring"))
                        : Map.of("from", List.of("any"), "to", List.of("any"), "schedule", List.of("sched-recurring"));
            } else if (!"CP".equals(vendor) && r % 23 == 3) {
                ruleExtras = Map.of("from", List.of("any"), "to", List.of("any"), "source-user", List.of("synth-group"));
            }
            secRules.add(new Rule(id, "uid-" + id, r + 1, "SYNTH-RULE-" + (r + 1), enabled,
                    srcCell, dstCell, svcCell, ANY_CELL, action, "Log", "", ruleExtras));
        }

        List<Rule> natRules = new ArrayList<>(natCount);
        for (int n = 0; n < natCount; n++) {
            String id = "p" + index + "-nat-" + (n + 1);
            int origZ = (n * 2) % 400;
            int xlZ = (origZ + 3) % 500;
            if ("CP".equals(vendor)) {
                natRules.add(new Rule(id, "uid-" + id, n + 1, "SYNTH-NAT-" + (n + 1), true,
                        ANY_CELL, new Cell(List.of("addr-" + origZ), false), new Cell(List.of("svc-https"), false),
                        ANY_CELL, "static", "", "", Map.of("translated-destination", List.of("addr-" + xlZ))));
            } else {
                natRules.add(new Rule(id, "uid-" + id, n + 1, "SYNTH-NAT-" + (n + 1), true,
                        ANY_CELL, new Cell(List.of("addr-" + origZ), false), new Cell(List.of("svc-https"), false),
                        ANY_CELL, "destination-nat", "", "", Map.of("translated-destination", List.of("addr-" + xlZ))));
            }
        }

        List<Section> sections = "CP".equals(vendor)
                ? List.of(
                        new Section("sec-access", "Network", "CP access layer", null, secRules),
                        new Section("sec-nat", "NAT", "CP NAT rulebase", null, natRules))
                : List.of(
                        new Section(PolicySnapshot.ref(metaId, "PAN device-group", "pre-rulebase"),
                                "Pre rules", "PAN device-group", null, secRules),
                        new Section("sec-nat", PolicySnapshot.PAN_PRE_NAT, "PAN device-group", null, natRules));

        Metadata metadata = new Metadata(
                metaId,
                "synth-src-" + index,
                "FW-ALPHA-0" + ((index % 9) + 1),
                vendor,
                "synth-dom-" + index,
                "DOM-BRAVO-0" + ((index % 9) + 1),
                "SYNTH-POLICY-" + index,
                "2026-11-15T08:00:00Z",
                "art-" + index,
                List.of(new Target("synth-gw-" + index, "FW-TANGO-0" + ((index % 9) + 1), "vsys1", "IN_SYNC")));
        return new PolicySnapshot(metadata, sections, objects);
    }
}
