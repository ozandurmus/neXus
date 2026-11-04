package com.securityexpert.nexus.ui2.service.pathanalysis.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathGoldenAccess;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.Ipv4Prefix;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyBuilder;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyFixtures;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyInput;
import com.securityexpert.nexus.ui2.service.privacy.PseudonymRegistry;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/**
 * D-3 conditions on the reverse map, one by one, over the A4 synthetic chain estate (documentation addresses only):
 * masked-only input, a per-snapshot in-memory map, ambiguity refused, no side effect on the maskers, and refusal codes
 * that the API turns into one uniform answer.
 */
class PathPseudonymMapTest {

    private static final byte[] KEY = "synthetic-path-query-mask-key-0001".getBytes(StandardCharsets.UTF_8);

    private final SubnetPreservingIpMasker ips = new SubnetPreservingIpMasker(KEY);
    private final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(KEY);
    private final TopologyGraph chain = graph();

    static TopologyGraph graph() {
        return TopologyBuilder.build(new TopologyInput("tps-a5a-test", Instant.parse("2026-11-04T07:00:00Z"),
                TopologyFixtures.devices(PathGoldenAccess.estate("chain.json")), List.of(),
                "synthetic-a5a-salt".getBytes(StandardCharsets.UTF_8)));
    }

    private PathPseudonymMap map() {
        return PathPseudonymMap.build(chain, PathPseudonymMap.over(ips, names));
    }

    private TopologyGraph.Node tango() {
        return chain.nodes().stream().filter(n -> n.activeMemberDeviceId().filter("dev-t04"::equals).isPresent())
                .findFirst().orElseThrow();
    }

    @Test
    void aMaskedHostAiviewWasShownResolvesToItsRawValue() {
        ips.mask("198.51.100.1/26"); // aiview read the interface address: the /26 is registered
        String shown = ips.mask("198.51.100.20");
        assertTrue(shown.matches("2(4[0-9]|5[0-5])\\..*"), "masked into the reserved space");
        assertEquals(new PathPseudonymMap.Resolved("198.51.100.20"), map().resolveAddress(shown));
        // A host offset aiview picked inside a masked subnet it saw (never handed out) resolves too.
        assertEquals(new PathPseudonymMap.Resolved("198.51.100.21"), map().resolveAddress(ips.peek("198.51.100.21")));
        // A host never registered under a prefix falls back to /24 in the masker; the map knows those /24s as well.
        var fresh = new SubnetPreservingIpMasker(KEY);
        String fallback = fresh.mask("203.0.113.10");
        assertEquals(new PathPseudonymMap.Resolved("203.0.113.10"),
                PathPseudonymMap.build(chain, PathPseudonymMap.over(fresh, names)).resolveAddress(fallback));
    }

    @Test
    void condition1ARawValueIsRefused() {
        ips.mask("198.51.100.1/26");
        for (String raw : List.of("198.51.100.20", "203.0.113.10", "127.0.0.1", "0.0.0.0")) {
            assertEquals(new PathPseudonymMap.Refused(PathPseudonymMap.RAW_VALUE_REFUSED), map().resolveAddress(raw), raw);
        }
        assertEquals(new PathPseudonymMap.Refused(PathPseudonymMap.RAW_VALUE_REFUSED),
                map().resolveAddress(ips.mask("198.51.100.1/26")), "a prefix is not a host");
        names.maskPolicyName("interface", "eth1");
        assertEquals(new PathPseudonymMap.Refused(PathPseudonymMap.RAW_VALUE_REFUSED),
                map().resolveInterface(tango().nodeId(), "eth1"));
    }

    @Test
    void unknownAndOutsideTheSnapshotAreRefused() {
        assertEquals(new PathPseudonymMap.Refused(PathPseudonymMap.UNKNOWN_PSEUDONYM), map().resolveAddress("250.1.2.3"));
        // Masked under a subnet the masker knows but the snapshot does not contain.
        ips.mask("192.0.2.129/25");
        String elsewhere = ips.mask("192.0.2.200");
        assertEquals(new PathPseudonymMap.Refused(PathPseudonymMap.UNKNOWN_PSEUDONYM), map().resolveAddress(elsewhere));
        assertEquals(new PathPseudonymMap.Refused(PathPseudonymMap.UNKNOWN_PSEUDONYM),
                map().resolveInterface(tango().nodeId(), "OBJ-NEVER-SHOWN-01"));
        assertEquals(new PathPseudonymMap.Refused(PathPseudonymMap.UNKNOWN_PSEUDONYM),
                map().resolveInterface("node-unknown", names.maskPolicyName("interface", "eth1")));
    }

    @Test
    void condition3AMaskedValueOfTwoRawValuesIsAmbiguous() {
        // A masker that (as a hash collision would) gives the client LAN and the DMZ the same masked /26.
        PathPseudonymMap.Masking colliding = new PathPseudonymMap.Masking() {
            @Override
            public String peek(String host) {
                long h = Ipv4Prefix.parseHost(host).orElseThrow().host();
                boolean inEither = Ipv4Prefix.parse("198.51.100.0/26").orElseThrow().contains(h)
                        || Ipv4Prefix.parse("203.0.113.0/26").orElseThrow().contains(h);
                return inEither ? "245.1.1." + (h & 63) : "[REDACTED_IP]";
            }

            @Override
            public String maskNetwork(String cidr) {
                return cidr.equals("198.51.100.0/26") || cidr.equals("203.0.113.0/26") ? "245.1.1.0/26" : "[REDACTED_IP]";
            }

            @Override
            public String maskPrefixed(String hostWithLength) {
                Ipv4Prefix.HostAddress h = Ipv4Prefix.parseHost(hostWithLength).orElseThrow();
                return h.length().filter(len -> len == 26).isPresent() && peek(h.hostText()).startsWith("245.")
                        ? peek(h.hostText()) + "/26" : "[REDACTED_IP]";
            }

            @Override
            public List<String> registeredSubnets() {
                return List.of();
            }

            @Override
            public Optional<String> peekInterface(String rawInterfaceName) {
                return Optional.of("OBJ-SAME-01"); // every interface carries the same label
            }
        };
        var map = PathPseudonymMap.build(chain, colliding);
        assertEquals(new PathPseudonymMap.Refused(PathPseudonymMap.AMBIGUOUS_PSEUDONYM), map.resolveAddress("245.1.1.20"));
        assertEquals(new PathPseudonymMap.Refused(PathPseudonymMap.AMBIGUOUS_PSEUDONYM),
                map.resolveInterface(tango().nodeId(), "OBJ-SAME-01"));
        // Never "last write wins" (the TopologyNamePseudonymizer.resolveClusterName behaviour D-3 rules out).
    }

    @Test
    void condition4ResolutionChangesNoMaskerState() {
        ips.mask("198.51.100.1/26");
        String label = names.maskPolicyName("interface", "eth1");
        List<String> registeredBefore = ips.registeredSubnets();
        AtomicInteger claims = new AtomicInteger();
        PseudonymRegistry inner = PseudonymRegistry.inMemory();
        names.setRegistry((kind, rawKey, candidates) -> {
            claims.incrementAndGet();
            return inner.claim(kind, rawKey, candidates);
        });
        String picked = ips.peek("198.51.100.30");
        var map = map();
        assertEquals(new PathPseudonymMap.Resolved("198.51.100.30"), map.resolveAddress(picked));
        assertEquals(new PathPseudonymMap.Resolved("eth1"), map.resolveInterface(tango().nodeId(), label));
        map.resolveInterface(tango().nodeId(), "OBJ-NEVER-SHOWN-01");
        map.resolveAddress("250.1.2.3");
        assertEquals(registeredBefore, ips.registeredSubnets(), "no subnet registered");
        assertEquals(0, claims.get(), "no pseudonym registry claim");
        // Nothing was cached: registering a narrower subnet now changes what the masker hands out for that host.
        ips.mask("198.51.100.17/28");
        assertNotEquals(picked, ips.mask("198.51.100.30"), "the resolution did not warm the masker cache");
    }

    @Test
    void condition2TheMapIsPerSnapshotInMemoryAndNeverPrintsItsContents() {
        var resolver = new PathPseudonymResolver(PathPseudonymMap.over(ips, names));
        var first = resolver.mapFor(chain);
        assertSame(first, resolver.mapFor(chain), "one map per held snapshot");
        var other = graph();
        var second = resolver.mapFor(other);
        assertFalse(first == second, "a new snapshot gets a new map");
        assertEquals(other.snapshotId(), second.snapshotId());
        String printed = first.toString() + new PathPseudonymMap.Resolved("198.51.100.20");
        assertFalse(printed.matches("(?s).*\\d+\\.\\d+\\.\\d+\\.\\d+.*"), printed);
    }
}
