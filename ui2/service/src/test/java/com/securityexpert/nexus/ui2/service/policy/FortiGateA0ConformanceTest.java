package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Section;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGateConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGateDefaults;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyMapper;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.HopPolicySource;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathEngine;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathQuery;
import com.securityexpert.nexus.ui2.service.pathanalysis.engine.PathResult;
import com.securityexpert.nexus.ui2.service.pathanalysis.query.PathPolicyBinder;
import com.securityexpert.nexus.ui2.service.pathanalysis.query.PathPolicyCatalog;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyBuilder;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyGraph;
import com.securityexpert.nexus.ui2.service.pathanalysis.topology.TopologyInput;

/**
 * Conformance pin for the FortiGate entries A0-FG-1 .. A0-FG-27 of
 * {@code docs/design/PATH_ANALYSIS_VENDOR_SEMANTICS_AMENDMENT_2026_11_14_ASA_FORTIGATE.md} (FROZEN 2026-11-14,
 * sections 12-14): one nested group per entry, display name = the A0 id, each asserting what the entry's last column
 * ("model must do") requires. Every configuration is a small synthetic FortiOS {@code show} text with masked names and
 * documentation addresses (192.0.2.x, 198.51.100.x, 203.0.113.x, 2001:db8::); none is a real device.
 *
 * <p>Entry points are the ones the neighbouring tests use: {@link FortiGateConfigPolicyParser} +
 * {@link FortiGatePolicyMapper} for the stored model, {@link PolicyLookup} for the lookup path and {@link PathEngine}
 * for the packet-order and NAT entries (A0-FG-17..22). The FortiOS defaults of design section 4.3
 * ({@link FortiGateDefaults}) are load-bearing: an absent setting is a default only with a cited 7.4 default
 * (A0-FG-5/6), otherwise UNKNOWN. This class is the automated-validation pin only; real-environment validation is a
 * separate Product Owner gate.
 *
 * <pre>
 * A0 id     test (nested class / method)                                                         status
 * A0-FG-1   Fg1 printedSequenceNotPolicyIdDecidesOrder                                           PINNED
 * A0-FG-2   Fg2 firstMatchWinsInBothOrders                                                       PINNED
 * A0-FG-3   Fg3 unmatchedTrafficHitsTheSingleImplicitDeny                                        PINNED
 * A0-FG-4   Fg4 negateFlagsInvertTheirCellAndDefaultToDisable                                    PINNED / UNKNOWN-PINNED
 * A0-FG-5   Fg5 everyCitedDefaultIsApplied / scheduleHasNoDefault                                PINNED
 * A0-FG-6   Fg6 uncitedMinorLeavesRulesUnknown / absentSettingWithoutCitationIsUnknown           UNKNOWN-PINNED
 * A0-FG-7   Fg7 ipsecActionIsNeverTerminal                                                       UNKNOWN-PINNED
 * A0-FG-8   Fg8 internetServiceIgnoresDestinationAndService                                      UNKNOWN-PINNED
 * A0-FG-9   Fg9 interfacesAreFromAndToAndAZonePlacementIsNotDecidedByName                        PINNED
 * A0-FG-10  Fg10 zoneIntrazoneDefaultDenyAndIntrazoneAllow                                       PINNED / UNKNOWN-PINNED
 * A0-FG-11  Fg11 sameInterfaceIsAnOrdinaryLookup                                                 PINNED
 * A0-FG-11  Fg11 disagreementBetweenTwoEvaluationsIsUnknown (@Disabled)                          NOT MODELLED
 * A0-FG-12  Fg12 usersGroupsAndFssoGroupsLeaveTheRuleUnknown                                     UNKNOWN-PINNED
 * A0-FG-13  Fg13 localInPoliciesAreListedOnlyAndAddNoImplicitDeny                                PINNED
 * A0-FG-14  Fg14 proxyPoliciesAreListedOnlyAndCounted                                            PINNED
 * A0-FG-15  Fg15 policyBasedNgfwVdomIsUnsupportedPerVdom                                         PINNED
 * A0-FG-16  Fg16 ipv6CellsAreKeptButUnresolvedByTheIpv4Engine                                    UNKNOWN-PINNED
 * A0-FG-17  Fg17 vipDnatIsNotEvaluatedAndKeepsTheHopUnknown                                      UNKNOWN-PINNED
 * A0-FG-18  Fg18 vipPolicyBeforeAnOrdinaryPolicyIsUnknown                                        UNKNOWN-PINNED
 * A0-FG-18  Fg18 earlierOrdinaryAndLaterVipPolicyBothMatchingIsUnknown                         UNKNOWN-PINNED (FGT_VIP_PRIORITY_UNPROVEN)
 * A0-FG-19  Fg19 vipDestinationPolicyStaysUnknownForExtipAndMappedQueries                        UNKNOWN-PINNED
 * A0-FG-19  Fg19 extipAndMappedAddressAreEvaluatedInParallel (@Disabled)                         NOT MODELLED
 * A0-FG-20  Fg20 matchVipDefaultAndExplicitOptions                                               PINNED / UNKNOWN-PINNED
 * A0-FG-20  Fg20 denyPolicyDefaultMatchVipAlsoMatchesTheMappedAddress                          UNKNOWN-PINNED (FGT_MATCH_VIP_UNPROVEN)
 * A0-FG-21  Fg21 centralNatIsUnsupportedForNat                                                   UNKNOWN-PINNED
 * A0-FG-22  Fg22 ippoolSnatIsKeptAsExtrasAndEgressSnatIsUnknown                                  PINNED / UNKNOWN-PINNED
 * A0-FG-23  Fg23 addressTypesResolveAsTheEntryRequires                                           PINNED
 * A0-FG-24  Fg24 subnetLessIpmaskIsTheIpv4UniverseOn74Only                                       PINNED
 * A0-FG-25  Fg25 allIsResolvedByItsStoredDefinitionNeverByName                                   PINNED
 * A0-FG-26  Fg26 customServicesParseOnlyTheMeasuredForms                                         PINNED
 * A0-FG-27  Fg27 predefinedServiceWithoutDefinitionIsUnresolved                                  UNKNOWN-PINNED
 * </pre>
 */
class FortiGateA0ConformanceTest {

    // ---- Synthetic configuration builders ------------------------------------------------------------------------------

    private static final String DEVICE = "dev-fgt-1";
    private static final Instant COMPUTED_AT = Instant.parse("2026-11-04T08:00:00Z");

    private static String banner(String version) {
        return "#config-version=FGVM64-" + version + "-FW-build2795-250521:opmode=0:vdom=0:user=admin\n";
    }

    /** Interfaces, two address objects and three custom services every test can lean on. */
    private static final String BODY = """
            config system interface
                edit "port1"
                    set vdom "root"
                    set ip 192.0.2.1 255.255.255.0
                next
                edit "port2"
                    set vdom "root"
                    set ip 198.51.100.1 255.255.255.0
                next
                edit "port3"
                    set vdom "root"
                    set ip 203.0.113.1 255.255.255.0
                next
            end
            config firewall address
                edit "all"
                next
                edit "HOST-WEB"
                    set subnet 198.51.100.20 255.255.255.255
                next
                edit "HOST-CLIENT"
                    set subnet 192.0.2.5 255.255.255.255
                next
            end
            config firewall service custom
                edit "HTTPS"
                    set tcp-portrange 443
                next
                edit "SSH"
                    set tcp-portrange 22
                next
                edit "ALL"
                    set protocol ALL
                next
            end
            """;

    /** One FortiOS 7.4 configuration: the shared body, {@code extra} configuration blocks, then the policy table. */
    private static String cfg(String extra, String policies) {
        return cfg("7.4.8", extra, policies);
    }

    private static String cfg(String version, String extra, String policies) {
        return banner(version) + BODY + extra + "config firewall policy\n" + policies + "end\n";
    }

    private static PolicySnapshot snap(String config) {
        var snapshots = FortiGatePolicyMapper.map(DEVICE, "FortiGate " + DEVICE, "2026-11-14T02:00:00Z", "artefact",
                FortiGateConfigPolicyParser.parse(config));
        assertEquals(1, snapshots.size());
        return snapshots.get(0);
    }

    /**
     * A policy table entry. Defaults: port1 -> port2, all -> all, HTTPS, always, accept. An override {@code "key value"}
     * replaces the setting, a bare {@code "key"} removes it (an absent line, as {@code show} omits defaults).
     */
    private static String pol(int id, String... overrides) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("srcintf", "\"port1\"");
        settings.put("dstintf", "\"port2\"");
        settings.put("srcaddr", "\"all\"");
        settings.put("dstaddr", "\"all\"");
        settings.put("action", "accept");
        settings.put("schedule", "\"always\"");
        settings.put("service", "\"HTTPS\"");
        for (String override : overrides) {
            int space = override.indexOf(' ');
            if (space < 0) settings.remove(override);
            else settings.put(override.substring(0, space), override.substring(space + 1));
        }
        StringBuilder text = new StringBuilder("    edit " + id + "\n");
        settings.forEach((key, value) -> text.append("        set ").append(key).append(' ').append(value).append('\n'));
        return text.append("    next\n").toString();
    }

    private static Map<String, Object> look(PolicySnapshot s, String src, String dst, String service, String in, String out) {
        return PolicyLookup.evaluate(s, new PolicyLookup.Query(src, dst, service, in, out, null, null), 250000).body();
    }

    /** The default packet of the lookup tests: client to web host over HTTPS, port1 -> port2. */
    private static Map<String, Object> https(PolicySnapshot s) {
        return look(s, "192.0.2.5", "198.51.100.20", "tcp/443", "port1", "port2");
    }

    private static Section policySection(PolicySnapshot s) {
        return s.sections().stream().filter(x -> "FGT firewall policy".equals(x.source())).findFirst().orElseThrow();
    }

    private static Section sectionBySource(PolicySnapshot s, String source) {
        return s.sections().stream().filter(x -> source.equals(x.source())).findFirst().orElseThrow();
    }

    private static Rule rule(PolicySnapshot s, int index) {
        return policySection(s).rules().get(index);
    }

    private static PolicyObject object(PolicySnapshot s, String name) {
        return s.objects().values().stream().filter(o -> o.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no object " + name));
    }


    private static List<?> undecidable(Map<String, Object> result) {
        return (List<?>) result.get("undecidableRules");
    }

    private static String refName(PolicySnapshot s, String ref) {
        return s.objects().get(ref).name();
    }

    // ---- Path engine builders (copied shape of PathEngineFortiGateTest, which is package-private elsewhere) -------------

    private static final String PATH_BASE = """
            #config-version=FG10E1-7.4.4-FW-build2662-240514:opmode=0:vdom=0
            config system interface
                edit "port1"
                    set vdom "root"
                    set ip 198.51.100.1 255.255.255.0
                next
                edit "port2"
                    set vdom "root"
                    set ip 203.0.113.1 255.255.255.0
                next
            end
            config firewall address
                edit "ADDR-CLIENT"
                    set subnet 198.51.100.0 255.255.255.0
                next
                edit "ADDR-WEB"
                    set subnet 203.0.113.10 255.255.255.255
                next
            end
            config firewall service custom
                edit "SVC-HTTPS"
                    set tcp-portrange 443
                next
            end
            """;

    private static final String PATH_ALLOW = """
            config firewall policy
                edit 1
                    set name "ALLOW-HTTPS"
                    set srcintf "port1"
                    set dstintf "port2"
                    set srcaddr "ADDR-CLIENT"
                    set dstaddr "ADDR-WEB"
                    set action accept
                    set schedule "always"
                    set service "SVC-HTTPS"
                next
            end
            """;

    private static HopPolicySource binder(List<PolicySnapshot> snapshots) {
        var catalog = new PathPolicyCatalog() {
            @Override public List<PolicySnapshot.Metadata> catalog() {
                return snapshots.stream().map(PolicySnapshot::metadata).toList();
            }
            @Override public Optional<PolicySnapshot> find(String policyId) {
                return snapshots.stream().filter(s -> s.metadata().id().equals(policyId)).findFirst();
            }
            @Override public List<PolicyCollectionRepository.TargetBinding> bindings(String policyId) {
                return List.of();
            }
            @Override public Optional<String> panSerial(String deviceId) {
                return Optional.empty();
            }
        };
        return new PathPolicyBinder(catalog).forQuery(COMPUTED_AT);
    }

    private static InventoryRoute connected(String destination, String iface) {
        return new InventoryRoute("r-" + destination + "-" + iface, destination, Optional.empty(), Optional.of(iface),
                "connected", Optional.empty(), Optional.of(true), Optional.empty(), Optional.empty(), Optional.of("CONNECTED"));
    }

    private static InventoryInterface iface(String name, String address) {
        return new InventoryInterface("if-" + name, name, Optional.empty(), "physical", "up",
                List.of(new InventoryAddress("a-" + address, address, InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER)));
    }

    private static TopologyGraph singleVdomGraph() {
        var run = new InventoryRun("run-fgt", DEVICE, "job-fgt", Instant.parse("2026-11-04T06:00:00Z"), 1,
                List.of(new InventoryContext(InventoryContext.PHYSICAL,
                        List.of(iface("port1", "198.51.100.1/24"), iface("port2", "203.0.113.1/24")),
                        List.of(connected("198.51.100.0/24", "port1"), connected("203.0.113.0/24", "port2")))));
        var device = new TopologyInput.DeviceFacts(DEVICE, TopologyInput.VENDOR_FORTINET, Optional.of("FW-FOXTROT-12"),
                Optional.empty(), Optional.of(run));
        return TopologyBuilder.build(new TopologyInput("tps-fgt", Instant.parse("2026-11-04T07:00:00Z"), List.of(device),
                List.of(), "synthetic-fgt-topology-salt-0001".getBytes(StandardCharsets.UTF_8)));
    }

    private static PathResult.Hop pathHop(String pathConfig) {
        var snapshots = FortiGatePolicyMapper.map(DEVICE, "FortiGate " + DEVICE, "2026-11-04T05:00:00Z", "artefact",
                FortiGateConfigPolicyParser.parse(pathConfig));
        PathResult result = PathEngine.analyze(singleVdomGraph(), binder(snapshots),
                PathQuery.of("198.51.100.20", "203.0.113.10", "tcp", 443), COMPUTED_AT);
        assertEquals(1, result.branches().size());
        assertEquals(1, result.branches().get(0).hops().size());
        return result.branches().get(0).hops().get(0);
    }

    // =====================================================================================================================

    @Nested @DisplayName("A0-FG-1")
    class Fg1 {
        @Test void printedSequenceNotPolicyIdDecidesOrder() {
            // Policy 20 is printed before policy 10; the model keeps the stored position, never the id order.
            var s = snap(cfg("", pol(20, "name \"PRINTED-FIRST\"", "service \"SSH\"", "action deny")
                    + pol(10, "name \"PRINTED-SECOND\"", "service \"HTTPS\" \"SSH\"")));
            assertTrue(s.failures().isEmpty(), String.valueOf(s.failures()));
            assertEquals(List.of("PRINTED-FIRST", "PRINTED-SECOND"), policySection(s).rules().stream().map(Rule::name).toList());
            assertEquals(List.of(1, 2), policySection(s).rules().stream().map(Rule::number).toList());

            var ssh = look(s, "192.0.2.5", "198.51.100.20", "tcp/22", "port1", "port2");
            assertEquals("DENIED", ssh.get("verdict"), "policy id 20 is checked first although 10 < 20");
            @SuppressWarnings("unchecked") var deciding = (Map<String, Object>) ssh.get("decidingRule");
            assertEquals("PRINTED-FIRST", deciding.get("name"));
            assertEquals("ALLOWED", https(s).get("verdict"));
        }
    }

    @Nested @DisplayName("A0-FG-2")
    class Fg2 {
        @Test void firstMatchWinsInBothOrders() {
            var acceptFirst = snap(cfg("", pol(1) + pol(2, "action deny")));
            assertEquals("ALLOWED", https(acceptFirst).get("verdict"));
            assertEquals("first matching rule", https(acceptFirst).get("lookupReason"));
            var denyFirst = snap(cfg("", pol(2, "action deny") + pol(1)));
            assertEquals("DENIED", https(denyFirst).get("verdict"));
            assertEquals("first matching rule", https(denyFirst).get("lookupReason"));
        }
    }

    @Nested @DisplayName("A0-FG-3")
    class Fg3 {
        @Test void unmatchedTrafficHitsTheSingleImplicitDeny() {
            var s = snap(cfg("", pol(1, "service \"SSH\"")));
            var unmatched = https(s);
            assertEquals("DENIED", unmatched.get("verdict"));
            assertEquals(PolicyLookup.FGT_IMPLICIT_DENY, unmatched.get("lookupReason"));
            assertNull(unmatched.get("decidingRule"), "the implicit deny is not a numbered policy");

            // One fixed implicit section per VDOM with one DENY rule; "policy ID 0" naming is not used.
            Section implicit = sectionBySource(s, "FGT implicit");
            assertEquals(1, implicit.rules().size());
            Rule deny = implicit.rules().get(0);
            assertEquals("implicit-deny", deny.name());
            assertEquals("deny", deny.action());
            assertEquals(List.of("any"), deny.extras().get("from"));
            assertEquals(List.of("any"), deny.extras().get("to"));
            assertTrue(PolicySnapshot.outsideSecurityEvaluation(s.metadata(), implicit));

            // An empty policy table is a pure implicit deny.
            var empty = snap(cfg("", ""));
            assertEquals("DENIED", https(empty).get("verdict"));
            assertEquals(PolicyLookup.FGT_IMPLICIT_DENY, https(empty).get("lookupReason"));
        }
    }

    @Nested @DisplayName("A0-FG-4")
    class Fg4 {
        @Test void negateFlagsInvertTheirCellAndDefaultToDisable() {
            var s = snap(cfg("", pol(1, "srcaddr \"HOST-CLIENT\"", "srcaddr-negate enable")
                    + pol(2, "dstaddr \"HOST-WEB\"", "dstaddr-negate enable", "action deny")
                    + pol(3, "service \"HTTPS\"", "service-negate enable", "action deny")
                    + pol(4, "srcaddr \"HOST-CLIENT\"", "dstaddr \"HOST-WEB\"")));
            assertTrue(rule(s, 0).source().negated());
            assertFalse(rule(s, 0).destination().negated());
            assertTrue(rule(s, 1).destination().negated());
            assertFalse(rule(s, 1).source().negated());
            assertTrue(rule(s, 2).service().negated());
            assertFalse(rule(s, 3).source().negated(), "absent negate flag = disable");
            assertFalse(rule(s, 3).destination().negated());
            assertFalse(rule(s, 3).service().negated());

            // Negated cells are complement-only, "shadowing only" (A0-N1/N2): the lookup does not decide on them.
            var negated = snap(cfg("", pol(1, "srcaddr \"HOST-CLIENT\"", "srcaddr-negate enable")));
            for (String source : List.of("192.0.2.77", "192.0.2.5")) {
                var result = look(negated, source, "198.51.100.20", "tcp/443", "port1", "port2");
                assertEquals("UNKNOWN", result.get("verdict"), source);
                assertEquals("negated selector", ((Map<?, ?>) undecidable(result).get(0)).get("lookupReason"), source);
            }
            var dstNegated = snap(cfg("", pol(1, "dstaddr \"HOST-WEB\"", "dstaddr-negate enable")));
            assertEquals("UNKNOWN", https(dstNegated).get("verdict"));
            var svcNegated = snap(cfg("", pol(1, "service \"HTTPS\"", "service-negate enable")));
            assertEquals("UNKNOWN", look(svcNegated, "192.0.2.5", "198.51.100.20", "tcp/22", "port1", "port2").get("verdict"));
        }
    }

    @Nested @DisplayName("A0-FG-5")
    class Fg5 {
        @Test void everyCitedDefaultIsApplied() {
            // The 7.4 table carries each default of the entry; the absent lines below take exactly those values.
            assertEquals("deny", FortiGateDefaults.defaultValue("7.4", "firewall policy", "action"));
            assertEquals("enable", FortiGateDefaults.defaultValue("7.4", "firewall policy", "status"));
            assertEquals("disable", FortiGateDefaults.defaultValue("7.4", "firewall policy", "nat"));
            for (String negate : List.of("srcaddr-negate", "dstaddr-negate", "service-negate"))
                assertEquals("disable", FortiGateDefaults.defaultValue("7.4", "firewall policy", negate), negate);

            // Policy with no action, status, nat or negate line.
            var s = snap(cfg("", pol(1, "action")));
            Rule r = rule(s, 0);
            assertEquals("deny", r.action(), "absent action = deny");
            assertEquals(Boolean.TRUE, r.enabled(), "absent status = enabled");
            assertFalse(r.extras().containsKey("source-translation-type"), "absent nat = disable");
            assertFalse(r.source().negated());
            assertFalse(r.destination().negated());
            assertFalse(r.service().negated());
            assertFalse(r.extras().containsKey("fgt-missing-default"), "nothing here is an unproven default");
            var result = https(s);
            assertEquals("DENIED", result.get("verdict"));
            assertEquals("first matching rule", result.get("lookupReason"), "the implicit action deny is the deciding rule");

            // Explicit values still win over the defaults.
            var explicit = snap(cfg("", pol(1, "status disable") + pol(2)));
            assertEquals(Boolean.FALSE, rule(explicit, 0).enabled());
            assertEquals("ALLOWED", https(explicit).get("verdict"), "a disabled policy is skipped");
        }

        @Test void scheduleHasNoDefault() {
            assertNull(FortiGateDefaults.defaultValue("7.4", "firewall policy", "schedule"));
            var s = snap(cfg("", pol(1, "schedule")));
            assertEquals(List.of("schedule"), rule(s, 0).extras().get("fgt-missing-default"));
            var result = https(s);
            assertEquals("UNKNOWN", result.get("verdict"), "a missing schedule is never read as 'always'");
            assertEquals(1, undecidable(result).size());
        }
    }

    @Nested @DisplayName("A0-FG-6")
    class Fg6 {
        @Test void uncitedMinorLeavesRulesUnknown() {
            // No cited defaults for 7.2: the snapshot says so and the lookup refuses to decide.
            assertFalse(FortiGateDefaults.isSupportedMinor("7.2"));
            var s = snap(cfg("7.2.9", "", pol(1, "status") + pol(2, "action")));
            assertEquals(1, s.failures().size());
            assertEquals("FGT_DEFAULTS_UNPROVEN", s.failures().get(0).reason());
            assertNull(rule(s, 0).enabled(), "an absent status without a cited default stays unknown");
            assertNotNull(rule(s, 0).extras().get("fgt-missing-default"));
            assertEquals("UNKNOWN", rule(s, 1).action(), "an absent action without a cited default stays unknown");
            var result = https(s);
            assertEquals("UNKNOWN", result.get("verdict"));
            assertEquals("snapshot collection incomplete", result.get("lookupReason"));

            // A configuration that names no FortiOS version at all has no proven table either.
            var versionless = snap(BODY + "config firewall policy\n" + pol(1) + "end\n");
            assertEquals("FGT_DEFAULTS_UNPROVEN", versionless.failures().get(0).reason());
            assertEquals("UNKNOWN", https(versionless).get("verdict"));
        }

        @Test void absentSettingWithoutCitationIsUnknown() {
            // On 7.4 itself the settings without a cited default (A0-FG-5: no default for these) mark the rule UNKNOWN.
            for (String setting : List.of("srcintf", "dstintf", "srcaddr", "dstaddr", "service", "schedule")) {
                var s = snap(cfg("", pol(1, setting)));
                assertTrue(rule(s, 0).extras().get("fgt-missing-default").contains(setting), setting);
                assertEquals("UNKNOWN", https(s).get("verdict"), setting + " absent");
            }
            assertTrue(FortiGateDefaults.POLICY_SETTINGS_WITHOUT_DEFAULT.containsAll(
                    List.of("srcintf", "dstintf", "srcaddr", "dstaddr", "service", "schedule")));
        }
    }

    @Nested @DisplayName("A0-FG-7")
    class Fg7 {
        @Test void ipsecActionIsNeverTerminal() {
            var s = snap(cfg("", pol(1, "action ipsec", "vpntunnel \"TUN-BRANCH-01\"") + pol(2)));
            Rule ipsec = rule(s, 0);
            assertEquals("UNKNOWN", ipsec.action());
            assertEquals(List.of("ipsec"), ipsec.extras().get("fgt-action"));
            // The later accept does not decide: the ipsec rule may have matched first.
            var result = https(s);
            assertEquals("UNKNOWN", result.get("verdict"));
            assertEquals("earlier rule could match", result.get("lookupReason"));
            assertEquals(1, undecidable(result).size());
            assertEquals("non-terminal or unknown action", ((Map<?, ?>) undecidable(result).get(0)).get("lookupReason"));
            // Without anything after it the fall-through is UNKNOWN too, not the implicit deny.
            var alone = snap(cfg("", pol(1, "action ipsec", "vpntunnel \"TUN-BRANCH-01\"")));
            assertEquals("UNKNOWN", https(alone).get("verdict"));
        }
    }

    @Nested @DisplayName("A0-FG-8")
    class Fg8 {
        @Test void internetServiceIgnoresDestinationAndService() {
            var s = snap(cfg("", pol(1, "internet-service enable", "internet-service-name \"Example-Cloud\"",
                    "dstaddr \"HOST-WEB\"", "service \"SSH\"")));
            Rule r = rule(s, 0);
            PolicyObject dst = s.objects().get(r.destination().refs().get(0));
            PolicyObject svc = s.objects().get(r.service().refs().get(0));
            assertEquals("DYNAMIC", dst.status());
            assertEquals("DYNAMIC", svc.status());
            // dstaddr/service written next to internet-service are not used by the model either.
            assertEquals(1, r.destination().refs().size());
            assertEquals(1, r.service().refs().size());
            assertFalse(r.destination().refs().stream().anyMatch(ref -> refName(s, ref).equals("HOST-WEB")));
            assertFalse(r.service().refs().stream().anyMatch(ref -> refName(s, ref).equals("SSH")));
            assertEquals(List.of("Example-Cloud"), r.extras().get("fgt-internet-service"));
            // No ISDB data is collected: the lookup cannot decide.
            var covered = look(s, "192.0.2.5", "198.51.100.20", "tcp/22", "port1", "port2");
            assertEquals("UNKNOWN", covered.get("verdict"));
            var elsewhere = look(s, "192.0.2.5", "203.0.113.9", "tcp/443", "port1", "port2");
            assertEquals("UNKNOWN", elsewhere.get("verdict"), "the ISDB rule may cover any destination and service");

            var source = snap(cfg("", pol(1, "internet-service-src enable", "internet-service-src-name \"Example-Source\"")));
            assertEquals("DYNAMIC", source.objects().get(rule(source, 0).source().refs().get(0)).status());
            assertEquals("UNKNOWN", https(source).get("verdict"));
        }
    }

    @Nested @DisplayName("A0-FG-9")
    class Fg9 {
        private static final String ZONES = """
                config system zone
                    edit "ZONE-DMZ"
                        set interface "port2"
                    next
                end
                config system sdwan
                    set status enable
                    config zone
                        edit "virtual-wan-link"
                        next
                    end
                    config members
                        edit 1
                            set interface "port3"
                            set zone "virtual-wan-link"
                        next
                    end
                end
                """;

        @Test void interfacesAreFromAndToAndAZonePlacementIsNotDecidedByName() {
            var plain = snap(cfg("", pol(1)));
            assertEquals(List.of("port1"), rule(plain, 0).extras().get("from"));
            assertEquals(List.of("port2"), rule(plain, 0).extras().get("to"));
            assertEquals("ALLOWED", https(plain).get("verdict"));
            assertEquals("DENIED", look(plain, "192.0.2.5", "198.51.100.20", "tcp/443", "port2", "port1").get("verdict"),
                    "ingress/egress interface names are matched, the reverse pair is not");

            var s = snap(cfg(ZONES, pol(1, "dstintf \"ZONE-DMZ\"") + pol(2, "srcintf \"virtual-wan-link\"")));
            assertEquals(List.of("ZONE-DMZ"), rule(s, 0).extras().get("to"));
            assertEquals(List.of("virtual-wan-link"), rule(s, 1).extras().get("from"));
            assertEquals("zone", object(s, "ZONE-DMZ").type());
            assertTrue(object(s, "ZONE-DMZ").values().contains("interface: port2"), "a zone matches its member interfaces");
            assertEquals("sdwan-zone", object(s, "virtual-wan-link").type());
            // A zone-placed policy is not compared by name: the packet's interface port2 is a member of ZONE-DMZ, but the
            // rule is left undecidable rather than matched or skipped by a name comparison.
            var inZone = look(s, "192.0.2.5", "198.51.100.20", "tcp/443", "port1", "port2");
            assertEquals("UNKNOWN", inZone.get("verdict"));
            assertFalse(undecidable(inZone).isEmpty());
            assertEquals("zone membership not evaluated", ((Map<?, ?>) undecidable(inZone).get(0)).get("lookupReason"));
        }
    }

    @Nested @DisplayName("A0-FG-10")
    class Fg10 {
        @Test void zoneIntrazoneDefaultDenyAndIntrazoneAllow() {
            // Default (no intrazone line): cited 7.4 default deny; no synthetic allow, the implicit deny still decides.
            var strict = snap(cfg("config system zone\n    edit \"ZONE-STRICT\"\n        set interface \"port1\"\n    next\nend\n",
                    pol(1, "service \"SSH\"")));
            assertEquals("intrazone: deny", object(strict, "ZONE-STRICT").values().get(0));
            assertEquals("RESOLVED", object(strict, "ZONE-STRICT").status());
            assertEquals(1, sectionBySource(strict, "FGT implicit").rules().size());
            var denied = https(strict);
            assertEquals("DENIED", denied.get("verdict"));
            assertEquals(PolicyLookup.FGT_IMPLICIT_DENY, denied.get("lookupReason"));

            // intrazone allow: an implicit ALLOW for that zone pair precedes the implicit deny; the lookup does not
            // compare zone membership by name, so the fall-through is UNKNOWN.
            var open = snap(cfg("config system zone\n    edit \"ZONE-OPEN\"\n        set intrazone allow\n        set interface \"port1\"\n    next\nend\n",
                    pol(1, "service \"SSH\"")));
            List<Rule> implicit = sectionBySource(open, "FGT implicit").rules();
            assertEquals(2, implicit.size());
            assertEquals("ZONE-OPEN intrazone allow", implicit.get(0).name());
            assertEquals("accept", implicit.get(0).action());
            assertEquals(List.of("ZONE-OPEN"), implicit.get(0).extras().get("from"));
            assertEquals(List.of("ZONE-OPEN"), implicit.get(0).extras().get("to"));
            assertEquals("implicit-deny", implicit.get(1).name());
            var fallThrough = https(open);
            assertEquals("UNKNOWN", fallThrough.get("verdict"));
            assertEquals(PolicyLookup.FGT_INTRAZONE_UNKNOWN, fallThrough.get("lookupReason"));
            // A matching policy still decides before the fall-through.
            assertEquals("ALLOWED", look(snap(cfg("config system zone\n    edit \"ZONE-OPEN\"\n        set intrazone allow\n        set interface \"port1\"\n    next\nend\n", pol(1))),
                    "192.0.2.5", "198.51.100.20", "tcp/443", "port1", "port2").get("verdict"));

            // On a minor without a cited default the zone's intrazone setting is unknown, not deny.
            var uncited = snap(cfg("7.2.9", "config system zone\n    edit \"ZONE-X\"\n        set interface \"port1\"\n    next\nend\n", pol(1)));
            assertEquals("UNKNOWN", object(uncited, "ZONE-X").status());
        }
    }

    @Nested @DisplayName("A0-FG-11")
    class Fg11 {
        @Test void sameInterfaceIsAnOrdinaryLookup() {
            var s = snap(cfg("", pol(1, "srcintf \"port1\"", "dstintf \"port1\"", "service \"SSH\"")
                    + pol(2, "srcintf \"port1\"", "dstintf \"port2\"", "service \"HTTPS\"")));
            var same = look(s, "192.0.2.5", "192.0.2.77", "tcp/22", "port1", "port1");
            assertEquals("ALLOWED", same.get("verdict"));
            assertEquals("first matching rule", same.get("lookupReason"));
            // A policy naming port1 -> port2 does not cover port1 -> port1; the implicit deny does.
            var other = look(s, "192.0.2.5", "192.0.2.77", "tcp/443", "port1", "port1");
            assertEquals("DENIED", other.get("verdict"));
            assertEquals(PolicyLookup.FGT_IMPLICIT_DENY, other.get("lookupReason"));
        }

        @org.junit.jupiter.api.Disabled("A0-FG-11: not modelled: the lookup runs once with from = to; no second evaluation exists, so a disagreement that should make it UNKNOWN cannot arise")
        @Test void disagreementBetweenTwoEvaluationsIsUnknown() {
        }
    }

    @Nested @DisplayName("A0-FG-12")
    class Fg12 {
        @Test void usersGroupsAndFssoGroupsLeaveTheRuleUnknown() {
            for (String criterion : List.of("groups \"GRP-OPS\"", "users \"alice\"", "fsso-groups \"FSSO-OPS\"")) {
                var s = snap(cfg("config user group\n    edit \"GRP-OPS\"\n    next\nend\n",
                        pol(1, criterion, "action deny") + pol(2)));
                assertTrue(rule(s, 0).extras().containsKey("user"), criterion);
                var result = https(s);
                assertEquals("UNKNOWN", result.get("verdict"), criterion + ": the later accept must not decide");
                assertEquals("earlier rule could match", result.get("lookupReason"), criterion);
                assertEquals("user or category constraint", ((Map<?, ?>) undecidable(result).get(0)).get("lookupReason"), criterion);
            }
            // A disabled identity rule is skipped, as any disabled policy.
            var disabled = snap(cfg("", pol(1, "groups \"GRP-OPS\"", "status disable") + pol(2)));
            assertEquals("ALLOWED", https(disabled).get("verdict"));
        }
    }

    @Nested @DisplayName("A0-FG-13")
    class Fg13 {
        @Test void localInPoliciesAreListedOnlyAndAddNoImplicitDeny() {
            var s = snap(cfg("""
                    config firewall local-in-policy
                        edit 1
                            set intf "port1"
                            set srcaddr "all"
                            set dstaddr "all"
                            set action deny
                            set service "ALL"
                            set schedule "always"
                        next
                    end
                    config firewall local-in-policy6
                        edit 2
                            set intf "port1"
                        next
                    end
                    """, pol(1)));
            Section listed = sectionBySource(s, "FGT not evaluated");
            assertTrue(PolicySnapshot.outsideSecurityEvaluation(s.metadata(), listed));
            assertEquals(2, listed.rules().size());
            for (Rule r : listed.rules()) {
                assertEquals("UNKNOWN", r.action());
                assertTrue(r.extras().get("fgt-not-evaluated-reason").get(0).contains("A0-FG-13"));
            }
            assertEquals(List.of("firewall local-in-policy"), listed.rules().get(0).extras().get("fgt-policy-table"));
            // A local-in deny covering the packet does not touch the transit lookup, and adds no implicit deny of its own.
            assertEquals("ALLOWED", https(s).get("verdict"));
            assertEquals(1, sectionBySource(s, "FGT implicit").rules().size());
            assertEquals(1, policySection(s).rules().size());
        }
    }

    @Nested @DisplayName("A0-FG-14")
    class Fg14 {
        @Test void proxyPoliciesAreListedOnlyAndCounted() {
            var parsed = FortiGateConfigPolicyParser.parse(cfg("""
                    config firewall proxy-policy
                        edit 1
                            set name "PROXY-WEB"
                            set proxy transparent-web
                            set action accept
                        next
                    end
                    """, pol(1, "action deny")));
            assertEquals(1, parsed.vdoms().get("root").counters().nonEvaluatedPolicies(), "counted by the measurement");
            var s = FortiGatePolicyMapper.map(DEVICE, "FortiGate " + DEVICE, "2026-11-14T02:00:00Z", "artefact", parsed).get(0);
            Section listed = sectionBySource(s, "FGT not evaluated");
            assertEquals(1, listed.rules().size());
            assertEquals(List.of("firewall proxy-policy"), listed.rules().get(0).extras().get("fgt-policy-table"));
            assertTrue(listed.rules().get(0).extras().get("fgt-not-evaluated-reason").get(0).contains("A0-FG-14"));
            assertEquals("UNKNOWN", listed.rules().get(0).action());
            // The transit lookup is the ordinary policy table only: the proxy policy's accept neither adds nor removes.
            assertEquals("DENIED", https(s).get("verdict"));
        }
    }

    @Nested @DisplayName("A0-FG-15")
    class Fg15 {
        @Test void policyBasedNgfwVdomIsUnsupportedPerVdom() {
            String config = """
                    #config-version=FG80F-7.4.8-FW-build2795-250521:opmode=0:vdom=1:user=admin
                    config vdom
                    edit root
                    next
                    edit VDOM-ALPHA
                    next
                    edit VDOM-BETA
                    next
                    end
                    config global
                    config system interface
                        edit "lan1"
                            set vdom "VDOM-ALPHA"
                            set ip 192.0.2.1 255.255.255.0
                        next
                        edit "lan2"
                            set vdom "VDOM-BETA"
                            set ip 198.51.100.1 255.255.255.0
                        next
                    end
                    end
                    config vdom
                    edit VDOM-ALPHA
                    config system settings
                        set ngfw-mode policy-based
                    end
                    end
                    config vdom
                    edit VDOM-BETA
                    config firewall address
                        edit "all"
                        next
                    end
                    config firewall service custom
                        edit "ALL"
                            set protocol ALL
                        next
                    end
                    config firewall security-policy
                        edit 1
                            set name "NGFW-LISTED"
                        next
                    end
                    config firewall policy
                        edit 1
                            set srcintf "lan2"
                            set dstintf "any"
                            set srcaddr "all"
                            set dstaddr "all"
                            set action accept
                            set schedule "always"
                            set service "ALL"
                        next
                    end
                    end
                    """;
            var snapshots = FortiGatePolicyMapper.map(DEVICE, "FortiGate " + DEVICE, "2026-11-14T02:00:00Z", "artefact",
                    FortiGateConfigPolicyParser.parse(config));
            assertEquals(3, snapshots.size());
            PolicySnapshot alpha = snapshots.stream().filter(x -> x.metadata().containerName().equals("VDOM-ALPHA")).findFirst().orElseThrow();
            PolicySnapshot beta = snapshots.stream().filter(x -> x.metadata().containerName().equals("VDOM-BETA")).findFirst().orElseThrow();
            // The policy-based VDOM is refused as a whole; the neighbouring profile-based VDOM is untouched.
            assertFalse(alpha.ruleComplete());
            assertTrue(alpha.sections().isEmpty());
            assertEquals("FGT_UNSUPPORTED_MODE", alpha.failures().get(0).reason());
            assertEquals("ngfw-mode:policy-based", alpha.failures().get(0).layerName());
            assertEquals("UNKNOWN", look(alpha, "192.0.2.5", "198.51.100.20", "tcp/443", "lan1", "lan2").get("verdict"));
            assertEquals("snapshot collection incomplete", look(alpha, "192.0.2.5", "198.51.100.20", "tcp/443", "lan1", "lan2").get("lookupReason"));

            assertTrue(beta.ruleComplete());
            assertEquals("ALLOWED", look(beta, "198.51.100.5", "192.0.2.20", "tcp/443", "lan2", "lan1").get("verdict"));
            // A security-policy table in a profile-based VDOM is listed, never evaluated.
            Section listed = sectionBySource(beta, "FGT not evaluated");
            assertEquals(List.of("firewall security-policy"), listed.rules().get(0).extras().get("fgt-policy-table"));
            assertTrue(listed.rules().get(0).extras().get("fgt-not-evaluated-reason").get(0).contains("A0-FG-15"));
        }
    }

    @Nested @DisplayName("A0-FG-16")
    class Fg16 {
        @Test void ipv6CellsAreKeptButUnresolvedByTheIpv4Engine() {
            var s = snap(cfg("""
                    config firewall address6
                        edit "V6-HOST"
                            set ip6 2001:db8::10/128
                        next
                    end
                    config firewall addrgrp6
                        edit "V6-GRP"
                            set member "V6-HOST"
                        next
                    end
                    """, pol(1, "srcaddr", "dstaddr", "srcaddr6 \"V6-HOST\"", "dstaddr6 \"V6-GRP\"")));
            // One table per VDOM: the v6 policy sits in the same section as the v4 ones, not in a separate one.
            assertEquals(1, s.sections().stream().filter(x -> "FGT firewall policy".equals(x.source())).count());
            Rule r = rule(s, 0);
            assertFalse(r.extras().containsKey("fgt-missing-default"), "*addr6 cells replace srcaddr/dstaddr, not a gap");
            assertEquals(List.of("V6-HOST"), r.source().refs().stream().map(ref -> refName(s, ref)).toList());
            assertEquals(List.of("V6-GRP"), r.destination().refs().stream().map(ref -> refName(s, ref)).toList());
            assertEquals("UNSUPPORTED", object(s, "V6-HOST").status());
            assertEquals("UNSUPPORTED", object(s, "V6-GRP").status());
            var result = https(s);
            assertEquals("UNKNOWN", result.get("verdict"), "the IPv4 engine cannot rule a v6 cell in or out");
            assertEquals(1, undecidable(result).size());
        }
    }

    @Nested @DisplayName("A0-FG-17")
    class Fg17 {
        @Test void vipDnatIsNotEvaluatedAndKeepsTheHopUnknown() {
            // DNAT happens before routing and policy; the model does not evaluate it, so a VIP that touches the tuple's
            // destination keeps the hop UNKNOWN while the security evaluation on the arriving tuple stays as context.
            var hop = pathHop(PATH_BASE + """
                    config firewall vip
                        edit "VIP-WEB"
                            set extip 203.0.113.10
                            set mappedip "192.0.2.10"
                            set extintf "port1"
                        next
                    end
                    """ + PATH_ALLOW);
            assertEquals("UNKNOWN", hop.ownVerdict());
            assertEquals(HopPolicyEvaluator.NAT_UNKNOWN, hop.reason());
            assertEquals(HopNatEvaluator.FGT_NAT_NOT_EVALUATED, hop.nat().orElseThrow().reason());
            assertEquals("ALLOWED", hop.security().orElseThrow().verdict());

            // A VIP elsewhere does not touch the tuple: DNAT-free path, ordinary policy lookup.
            var unrelated = pathHop(PATH_BASE + """
                    config firewall vip
                        edit "VIP-OTHER"
                            set extip 192.0.2.50
                            set mappedip "192.0.2.150"
                            set extintf "port1"
                        next
                    end
                    """ + PATH_ALLOW);
            assertEquals("ALLOWED", unrelated.ownVerdict());
            assertEquals(HopNatEvaluator.NAT_NO_RULE_MATCHED, unrelated.nat().orElseThrow().reason());

            // The VIP rule lives in the NAT section, outside the security evaluation: it never allows a packet itself.
            var noPolicy = snap(cfg("config firewall vip\n    edit \"VIP-WEB\"\n        set extip 198.51.100.20\n        set mappedip \"192.0.2.10\"\n        set extintf \"port1\"\n    next\nend\n", ""));
            Section vip = sectionBySource(noPolicy, "FGT NAT");
            assertEquals("destination-nat", vip.rules().get(0).action());
            assertTrue(PolicySnapshot.outsideSecurityEvaluation(noPolicy.metadata(), vip));
            assertEquals("DENIED", https(noPolicy).get("verdict"));
        }
    }

    private static final String VIP_WEB = """
            config firewall vip
                edit "VIP-WEB"
                    set extip 198.51.100.20
                    set mappedip "192.0.2.10"
                    set extintf "port1"
                next
            end
            """;

    @Nested @DisplayName("A0-FG-18")
    class Fg18 {
        @Test void vipPolicyBeforeAnOrdinaryPolicyIsUnknown() {
            var s = snap(cfg(VIP_WEB, pol(1, "dstaddr \"VIP-WEB\"") + pol(2, "dstaddr \"HOST-WEB\"")));
            assertEquals(List.of("VIP-WEB"), rule(s, 0).extras().get("fgt-vip"));
            var result = https(s);
            assertEquals("UNKNOWN", result.get("verdict"), "the priority of VIP policies is unproven");
            assertEquals("earlier rule could match", result.get("lookupReason"));
            assertEquals(1, undecidable(result).size());
        }

        @Test void earlierOrdinaryAndLaterVipPolicyBothMatchingIsUnknown() {
            var s = snap(cfg(VIP_WEB, pol(1, "dstaddr \"HOST-WEB\"") + pol(2, "dstaddr \"VIP-WEB\"", "action deny")));
            var result = https(s);
            assertEquals("UNKNOWN", result.get("verdict"));
            assertEquals("FGT_VIP_PRIORITY_UNPROVEN", result.get("lookupReason"));
            assertTrue(PolicyLookup.REASONS.contains("FGT_VIP_PRIORITY_UNPROVEN"));
            // The earlier ordinary policy stays the reported rule; the later VIP policy is the undecided one.
            assertEquals("accept", ((Map<?, ?>) result.get("decidingRule")).get("action"));
            assertEquals(1, undecidable(result).size());
            var later = (Map<?, ?>) undecidable(result).get(0);
            assertEquals("deny", later.get("action"));
            assertEquals("FGT_VIP_PRIORITY_UNPROVEN", later.get("lookupReason"));
        }

        @Test void anEarlierDenyAndALaterVipPolicyBothMatchingIsUnknownToo() {
            var s = snap(cfg(VIP_WEB, pol(1, "dstaddr \"HOST-WEB\"", "action deny") + pol(2, "dstaddr \"VIP-WEB\"")));
            var result = https(s);
            assertEquals("UNKNOWN", result.get("verdict"));
            assertEquals("FGT_VIP_PRIORITY_UNPROVEN", result.get("lookupReason"));
        }

        @Test void aLaterVipPolicyTheQueryCannotMeetKeepsTheEarlierDecision() {
            String extra = VIP_WEB + "config firewall address\n    edit \"HOST-OTHER\"\n        set subnet 192.0.2.99 255.255.255.255\n    next\nend\n";
            // A different service: the VIP policy only takes SSH, the packet is HTTPS.
            var service = snap(cfg(extra, pol(1, "dstaddr \"HOST-WEB\"") + pol(2, "dstaddr \"VIP-WEB\"", "service \"SSH\"")));
            assertEquals("ALLOWED", https(service).get("verdict"));
            assertEquals("first matching rule", https(service).get("lookupReason"));
            // A different source: the VIP policy only takes another host.
            var source = snap(cfg(extra, pol(1, "dstaddr \"HOST-WEB\"") + pol(2, "srcaddr \"HOST-OTHER\"", "dstaddr \"VIP-WEB\"")));
            assertEquals("ALLOWED", https(source).get("verdict"));
            // A different ingress interface.
            var ingress = snap(cfg(extra, pol(1, "dstaddr \"HOST-WEB\"") + pol(2, "srcintf \"port3\"", "dstaddr \"VIP-WEB\"")));
            assertEquals("ALLOWED", https(ingress).get("verdict"));
            // The VIP policy is not disjoint on any resolved dimension: UNKNOWN again (the control of the three above).
            var same = snap(cfg(extra, pol(1, "dstaddr \"HOST-WEB\"") + pol(2, "dstaddr \"VIP-WEB\"")));
            assertEquals("UNKNOWN", https(same).get("verdict"));
        }

        @Test void withoutAVipPolicyAfterTheMatchNothingChanges() {
            // A VIP exists in the VDOM, but no later policy carries it.
            var vipDefined = snap(cfg(VIP_WEB, pol(1, "dstaddr \"HOST-WEB\"") + pol(2, "action deny")));
            assertEquals("ALLOWED", https(vipDefined).get("verdict"));
            assertEquals("first matching rule", https(vipDefined).get("lookupReason"));
            // No VIP anywhere.
            var none = snap(cfg("", pol(1, "dstaddr \"HOST-WEB\"") + pol(2, "action deny")));
            assertEquals("ALLOWED", https(none).get("verdict"));
            // A VIP policy BEFORE the match is the already pinned earlier-unknown case, a later one is not looked at past a disabled rule.
            var disabled = snap(cfg(VIP_WEB, pol(1, "dstaddr \"HOST-WEB\"") + pol(2, "dstaddr \"VIP-WEB\"", "status disable")));
            assertEquals("ALLOWED", https(disabled).get("verdict"));
        }
    }

    @Nested @DisplayName("A0-FG-19")
    class Fg19 {
        @Test void vipDestinationPolicyStaysUnknownForExtipAndMappedQueries() {
            var s = snap(cfg(VIP_WEB, pol(1, "dstaddr \"VIP-WEB\"")));
            // The address dictionary resolves the VIP object to its external address and keeps the mapped one beside it.
            PolicyObject vip = object(s, "VIP-WEB");
            assertEquals("RESOLVED", vip.status());
            assertTrue(vip.values().contains("ip-netmask: 198.51.100.20/32"));
            assertTrue(vip.values().contains("vip-mapped: 192.0.2.10"));
            // Pre-DNAT (extip) and post-DNAT (mapped) queries are both undecided; a VIP-destination rule is never
            // silently allowed or denied.
            assertEquals("UNKNOWN", https(s).get("verdict"));
            assertEquals("UNKNOWN", look(s, "192.0.2.5", "192.0.2.10", "tcp/443", "port1", "port2").get("verdict"));
        }

        @org.junit.jupiter.api.Disabled("A0-FG-19: not modelled: the lookup does not evaluate the VIP cell as extip and as the mapped address in parallel; any VIP-destination rule is simply UNKNOWN (conservative, never differing verdicts)")
        @Test void extipAndMappedAddressAreEvaluatedInParallel() {
        }
    }

    @Nested @DisplayName("A0-FG-20")
    class Fg20 {
        @Test void matchVipDefaultAndExplicitOptions() {
            assertEquals("enable", FortiGateDefaults.defaultValue("7.4", "firewall policy", "match-vip"));
            assertEquals("disable", FortiGateDefaults.defaultValue("7.4", "firewall policy", "match-vip-only"));

            // No VIP in the VDOM: the options never change what a policy matches, the policy is decided.
            var noVip = snap(cfg("", pol(1, "action deny", "match-vip enable")));
            assertEquals(List.of("enable"), rule(noVip, 0).extras().get("fgt-match-vip"));
            assertEquals("DENIED", https(noVip).get("verdict"));
            assertEquals("first matching rule", https(noVip).get("lookupReason"));
            var noVipOnly = snap(cfg("", pol(1, "match-vip-only enable")));
            assertEquals("ALLOWED", https(noVipOnly).get("verdict"));

            // The default is not written into the rule; only an explicit line becomes an extra.
            var implicit = snap(cfg("", pol(1)));
            assertFalse(rule(implicit, 0).extras().containsKey("fgt-match-vip"));
            assertFalse(rule(implicit, 0).extras().containsKey("fgt-match-vip-only"));

            // With a VIP in the VDOM a policy carrying match-vip or match-vip-only is a contradiction risk: UNKNOWN.
            for (String option : List.of("match-vip enable", "match-vip disable", "match-vip-only enable")) {
                var withVip = snap(cfg(VIP_WEB, pol(1, "action deny", option) + pol(2)));
                var result = https(withVip);
                assertEquals("UNKNOWN", result.get("verdict"), option);
                assertEquals("unsupported rule constraint", ((Map<?, ?>) undecidable(result).get(0)).get("lookupReason"), option);
            }
        }

        private static final String MAPPED_NET = VIP_WEB + "config firewall address\n    edit \"NET-MAPPED\"\n        set subnet 192.0.2.0 255.255.255.0\n    next\n"
                + "    edit \"HOST-OTHER\"\n        set subnet 192.0.2.99 255.255.255.255\n    next\nend\n";

        @Test void denyPolicyDefaultMatchVipAlsoMatchesTheMappedAddress() {
            var s = snap(cfg(MAPPED_NET, pol(1, "dstaddr \"NET-MAPPED\"", "action deny") + pol(2, "dstaddr \"HOST-WEB\"")));
            var result = https(s);
            assertEquals("UNKNOWN", result.get("verdict"));
            assertEquals("earlier rule could match", result.get("lookupReason"));
            assertEquals(1, undecidable(result).size());
            var deny = (Map<?, ?>) undecidable(result).get(0);
            assertEquals("deny", deny.get("action"));
            assertEquals("FGT_MATCH_VIP_UNPROVEN", deny.get("lookupReason"));
            assertTrue(PolicyLookup.REASONS.contains("FGT_MATCH_VIP_UNPROVEN"));
        }

        @Test void anAcceptPolicyOnTheMappedAddressIsNotUsedAndNeverDecidesTheExtipPacket() {
            // "match-vip" applies to deny policies only: the accept on the mapped address does not match the extip packet.
            var s = snap(cfg(MAPPED_NET, pol(1, "dstaddr \"NET-MAPPED\"") + pol(2, "dstaddr \"HOST-WEB\"", "action deny")));
            assertEquals("DENIED", https(s).get("verdict"));
            assertEquals("first matching rule", https(s).get("lookupReason"));
        }

        @Test void aDenyPolicyTheVipCannotReachIsNotAffected() {
            // No VIP covers the queried address: the deny on the mapped network is ordinary and does not match.
            var other = snap(cfg(MAPPED_NET, pol(1, "dstaddr \"NET-MAPPED\"", "action deny") + pol(2)));
            assertEquals("ALLOWED", look(other, "192.0.2.5", "198.51.100.77", "tcp/443", "port1", "port2").get("verdict"));
            // The deny policy does not meet the source, the service or the ingress interface; destination NAT changes none of them.
            assertEquals("ALLOWED", https(snap(cfg(MAPPED_NET,
                    pol(1, "srcaddr \"HOST-OTHER\"", "dstaddr \"NET-MAPPED\"", "action deny") + pol(2, "dstaddr \"HOST-WEB\"")))).get("verdict"));
            assertEquals("ALLOWED", https(snap(cfg(MAPPED_NET,
                    pol(1, "dstaddr \"NET-MAPPED\"", "action deny", "service \"SSH\"") + pol(2, "dstaddr \"HOST-WEB\"")))).get("verdict"));
            assertEquals("ALLOWED", https(snap(cfg(MAPPED_NET,
                    pol(1, "srcintf \"port3\"", "dstaddr \"NET-MAPPED\"", "action deny") + pol(2, "dstaddr \"HOST-WEB\"")))).get("verdict"));
            // A packet addressed to the mapped address itself is matched by the deny policy as written.
            assertEquals("DENIED", look(snap(cfg(MAPPED_NET, pol(1, "dstaddr \"NET-MAPPED\"", "action deny") + pol(2, "dstaddr \"HOST-WEB\""))),
                    "192.0.2.5", "192.0.2.10", "tcp/443", "port1", "port2").get("verdict"));
        }

        @Test void withoutAVipTheDenyPolicyOnTheMappedNetworkIsOrdinary() {
            var s = snap(cfg("config firewall address\n    edit \"NET-MAPPED\"\n        set subnet 192.0.2.0 255.255.255.0\n    next\nend\n",
                    pol(1, "dstaddr \"NET-MAPPED\"", "action deny") + pol(2, "dstaddr \"HOST-WEB\"")));
            assertEquals("ALLOWED", https(s).get("verdict"));
        }
    }

    @Nested @DisplayName("A0-FG-21")
    class Fg21 {
        @Test void centralNatIsUnsupportedForNat() {
            String central = """
                    config system settings
                        set central-nat enable
                    end
                    """;
            // central-nat enable with no map entry at all: the NAT section exists but is empty -> NAT cannot be ruled out.
            var parsedEmpty = FortiGatePolicyMapper.map(DEVICE, "FortiGate " + DEVICE, "2026-11-04T05:00:00Z", "artefact",
                    FortiGateConfigPolicyParser.parse(PATH_BASE + central + PATH_ALLOW));
            assertTrue(sectionBySource(parsedEmpty.get(0), "FGT NAT").rules().isEmpty());
            var hopEmpty = pathHop(PATH_BASE + central + PATH_ALLOW);
            assertEquals("UNKNOWN", hopEmpty.ownVerdict());
            assertEquals(HopPolicyEvaluator.NAT_UNKNOWN, hopEmpty.reason());
            assertEquals(HopNatEvaluator.FGT_NAT_NOT_EVALUATED, hopEmpty.nat().orElseThrow().reason());

            // A central-snat-map entry is listed and marked UNSUPPORTED; applied after the security policy, it never changes
            // the security lookup.
            String map = """
                    config firewall central-snat-map
                        edit 1
                            set srcintf "port1"
                            set dstintf "port2"
                            set orig-addr "ADDR-CLIENT"
                            set dst-addr "ADDR-WEB"
                        next
                    end
                    """;
            var s = FortiGatePolicyMapper.map(DEVICE, "FortiGate " + DEVICE, "2026-11-04T05:00:00Z", "artefact",
                    FortiGateConfigPolicyParser.parse(PATH_BASE + map + PATH_ALLOW)).get(0);
            Section snat = s.sections().stream().filter(x -> x.name().equals("FGT central SNAT")).findFirst().orElseThrow();
            assertTrue(PolicySnapshot.outsideSecurityEvaluation(s.metadata(), snat));
            assertEquals(List.of("UNSUPPORTED"), snat.rules().get(0).extras().get("fgt-central-snat"));
            assertEquals("ALLOWED", look(s, "198.51.100.20", "203.0.113.10", "tcp/443", "port1", "port2").get("verdict"));
            var hop = pathHop(PATH_BASE + map + PATH_ALLOW);
            assertEquals("UNKNOWN", hop.ownVerdict());
            assertEquals(HopNatEvaluator.FGT_NAT_NOT_EVALUATED, hop.nat().orElseThrow().reason());
            assertEquals("ALLOWED", hop.security().orElseThrow().verdict(), "security evaluation stays as context");
        }
    }

    @Nested @DisplayName("A0-FG-22")
    class Fg22 {
        @Test void ippoolSnatIsKeptAsExtrasAndEgressSnatIsUnknown() {
            String pool = "config firewall ippool\n    edit \"POOL-EGRESS\"\n        set startip 203.0.113.50\n        set endip 203.0.113.60\n    next\nend\n";
            var s = snap(cfg(pool, pol(1, "nat enable", "ippool enable", "poolname \"POOL-EGRESS\"")
                    + pol(2, "nat enable") + pol(3, "ippool enable", "poolname \"POOL-EGRESS\"")));
            // nat enable + ippool + poolname: NAT extras on the rule pointing at the resolved pool.
            Rule pooled = rule(s, 0);
            assertEquals(List.of("dynamic-ip-and-port"), pooled.extras().get("source-translation-type"));
            PolicyObject poolObject = s.objects().get(pooled.extras().get("translated-source").get(0));
            assertEquals("POOL-EGRESS", poolObject.name());
            assertEquals("RESOLVED", poolObject.status());
            assertEquals(List.of("ip-range: 203.0.113.50-203.0.113.60"), poolObject.values());
            // nat enable without a pool: SNAT to the egress interface address is not stated -> UNKNOWN object.
            PolicyObject egress = s.objects().get(rule(s, 1).extras().get("translated-source").get(0));
            assertEquals("UNKNOWN", egress.status());
            // ippool without nat: nothing is translated.
            assertFalse(rule(s, 2).extras().containsKey("source-translation-type"));
            // The SNAT extras never change what the security policy matches.
            assertEquals("ALLOWED", https(s).get("verdict"));

            // On the exit path a reachable accept with SNAT keeps the hop UNKNOWN; a later, unreached one does not.
            var hop = pathHop(PATH_BASE + """
                    config firewall policy
                        edit 1
                            set srcintf "port1"
                            set dstintf "port2"
                            set srcaddr "ADDR-CLIENT"
                            set dstaddr "ADDR-WEB"
                            set action accept
                            set schedule "always"
                            set service "SVC-HTTPS"
                            set nat enable
                        next
                    end
                    """);
            assertEquals("UNKNOWN", hop.ownVerdict());
            assertEquals(HopPolicyEvaluator.NAT_UNKNOWN, hop.reason());
            assertEquals(HopNatEvaluator.FGT_NAT_NOT_EVALUATED, hop.nat().orElseThrow().reason());
        }
    }

    @Nested @DisplayName("A0-FG-23")
    class Fg23 {
        @Test void addressTypesResolveAsTheEntryRequires() {
            var s = snap(cfg("""
                    config system interface
                        edit "port9"
                            set vdom "root"
                            set ip 203.0.113.65 255.255.255.192
                        next
                    end
                    config firewall address
                        edit "A-IPMASK"
                            set type ipmask
                            set subnet 192.0.2.0 255.255.255.0
                        next
                        edit "A-IPRANGE"
                            set type iprange
                            set start-ip 198.51.100.10
                            set end-ip 198.51.100.20
                        next
                        edit "A-FQDN"
                            set type fqdn
                            set fqdn "portal.example.invalid"
                        next
                        edit "A-GEO"
                            set type geography
                            set country "US"
                        next
                        edit "A-DYNAMIC"
                            set type dynamic
                        next
                        edit "A-ROUTETAG"
                            set type route-tag
                        next
                        edit "A-WILDCARD"
                            set type wildcard
                            set wildcard 192.0.2.0 255.255.0.255
                        next
                        edit "A-MAC"
                            set type mac
                        next
                        edit "A-IFSUBNET"
                            set type interface-subnet
                            set interface "port9"
                        next
                    end
                    """, pol(1)));
            assertEquals("RESOLVED", object(s, "A-IPMASK").status());
            assertEquals(List.of("ip-netmask: 192.0.2.0/24"), object(s, "A-IPMASK").values());
            assertEquals("RESOLVED", object(s, "A-IPRANGE").status());
            assertEquals(List.of("ip-range: 198.51.100.10-198.51.100.20"), object(s, "A-IPRANGE").values());
            for (String dynamic : List.of("A-FQDN", "A-GEO", "A-DYNAMIC", "A-ROUTETAG"))
                assertEquals("DYNAMIC", object(s, dynamic).status(), dynamic);
            for (String unsupported : List.of("A-WILDCARD", "A-MAC"))
                assertEquals("UNSUPPORTED", object(s, unsupported).status(), unsupported);
            // interface-subnet: from the interface's configured address (MEDIUM in the entry).
            assertEquals("RESOLVED", object(s, "A-IFSUBNET").status());
            assertEquals(List.of("ip-netmask: 203.0.113.65/26"), object(s, "A-IFSUBNET").values());
        }
    }

    @Nested @DisplayName("A0-FG-24")
    class Fg24 {
        @Test void subnetLessIpmaskIsTheIpv4UniverseOn74Only() {
            assertEquals("ipmask", FortiGateDefaults.defaultValue("7.4", "firewall address", "type"));
            assertEquals("0.0.0.0 0.0.0.0", FortiGateDefaults.defaultValue("7.4", "firewall address", "subnet"));
            String bare = "config firewall address\n    edit \"BARE\"\n    next\nend\n";
            var s = snap(cfg(bare, pol(1, "srcaddr \"BARE\"", "dstaddr \"BARE\"")));
            PolicyObject bareObject = object(s, "BARE");
            assertEquals("RESOLVED", bareObject.status());
            assertEquals(List.of("ip-netmask: 0.0.0.0/0"), bareObject.values());
            // The subnet-less object covers every IPv4 address.
            assertEquals("ALLOWED", look(s, "203.0.113.99", "192.0.2.200", "tcp/443", "port1", "port2").get("verdict"));
            // The same text on a minor without the cited default is unknown, never the universe.
            var uncited = snap(cfg("7.2.9", bare, pol(1, "srcaddr \"BARE\"", "dstaddr \"BARE\"")));
            assertEquals("UNKNOWN", object(uncited, "BARE").status());
            assertEquals("UNKNOWN", look(uncited, "203.0.113.99", "192.0.2.200", "tcp/443", "port1", "port2").get("verdict"));
        }
    }

    @Nested @DisplayName("A0-FG-25")
    class Fg25 {
        @Test void allIsResolvedByItsStoredDefinitionNeverByName() {
            // A stored "all" with an ordinary subnet is exactly that subnet: the name carries no meaning.
            String odd = "config firewall address\n    edit \"all\"\n        set subnet 192.0.2.0 255.255.255.0\n    next\nend\n";
            String bodyWithoutAll = BODY.replace("    edit \"all\"\n    next\n", "");
            var s = snap(banner("7.4.8") + bodyWithoutAll + odd + "config firewall policy\n" + pol(1) + "end\n");
            assertEquals(List.of("ip-netmask: 192.0.2.0/24"), object(s, "all").values());
            assertEquals("ALLOWED", look(s, "192.0.2.5", "192.0.2.9", "tcp/443", "port1", "port2").get("verdict"));
            assertEquals("DENIED", look(s, "203.0.113.5", "192.0.2.9", "tcp/443", "port1", "port2").get("verdict"));

            // A policy naming "all" with no stored definition: unresolved, not "any".
            var undefined = snap(banner("7.4.8") + bodyWithoutAll + "config firewall policy\n" + pol(1) + "end\n");
            assertEquals("UNRESOLVED", object(undefined, "all").status());
            assertEquals("UNKNOWN", https(undefined).get("verdict"));

            // "none" stays unresolved even when stored with a host mask, in policies and in groups.
            var none = snap(cfg("config firewall address\n    edit \"none\"\n        set subnet 0.0.0.0 255.255.255.255\n    next\nend\n"
                    + "config firewall addrgrp\n    edit \"GRP-NONE\"\n        set member \"none\"\n    next\nend\n",
                    pol(1, "srcaddr \"none\"")));
            assertEquals("UNRESOLVED", object(none, "none").status());
            assertEquals("UNKNOWN", https(none).get("verdict"));

            // Nested groups are expanded by the resolver.
            var nested = snap(cfg("""
                    config firewall addrgrp
                        edit "GRP-INNER"
                            set member "HOST-CLIENT"
                        next
                        edit "GRP-OUTER"
                            set member "GRP-INNER"
                        next
                    end
                    """, pol(1, "srcaddr \"GRP-OUTER\"")));
            assertEquals("ALLOWED", https(nested).get("verdict"));
            assertEquals("DENIED", look(nested, "192.0.2.77", "198.51.100.20", "tcp/443", "port1", "port2").get("verdict"));
            // A group cycle never resolves.
            var cycle = snap(cfg("""
                    config firewall addrgrp
                        edit "GRP-A"
                            set member "GRP-B"
                        next
                        edit "GRP-B"
                            set member "GRP-A"
                        next
                    end
                    """, pol(1, "srcaddr \"GRP-A\"")));
            assertEquals("UNKNOWN", https(cycle).get("verdict"));
        }
    }

    @Nested @DisplayName("A0-FG-26")
    class Fg26 {
        @Test void customServicesParseOnlyTheMeasuredForms() {
            var s = snap(cfg("""
                    config firewall service custom
                        edit "S-MULTI"
                            set tcp-portrange 80 443 8000-8010
                        next
                        edit "S-UDP"
                            set udp-portrange 53
                        next
                        edit "S-BOTH"
                            set tcp-portrange 53
                            set udp-portrange 53
                        next
                        edit "S-SCTP"
                            set sctp-portrange 3868
                        next
                        edit "S-ICMP"
                            set protocol ICMP
                            set icmptype 8
                            set icmpcode 0
                        next
                        edit "S-ICMP6"
                            set protocol ICMP6
                            set icmptype 128
                        next
                        edit "S-GRE"
                            set protocol IP
                            set protocol-number 47
                        next
                        edit "S-IP-BARE"
                            set protocol IP
                        next
                        edit "S-SRC"
                            set tcp-portrange 443:1024-65535
                        next
                        edit "S-SRC-MULTI"
                            set tcp-portrange 443:1024-65535 8443
                        next
                        edit "S-BAD"
                            set tcp-portrange 99999
                        next
                    end
                    """, pol(1)));
            // Multiple destination ranges on one protocol, protocol default TCP/UDP/SCTP.
            assertEquals("RESOLVED", object(s, "S-MULTI").status());
            assertEquals(List.of("protocol/tcp/port: 80,443,8000-8010"), object(s, "S-MULTI").values());
            assertEquals(List.of("protocol/udp/port: 53"), object(s, "S-UDP").values());
            assertEquals(List.of("protocol/tcp/port: 53", "protocol/udp/port: 53"), object(s, "S-BOTH").values());
            assertEquals("UNSUPPORTED", object(s, "S-SCTP").status(), "sctp-portrange is not evaluated");
            // ICMP / ICMP6 with type and code.
            assertEquals("RESOLVED", object(s, "S-ICMP").status());
            assertEquals(List.of("protocol: icmp", "icmp-type: 8", "icmp-code: 0"), object(s, "S-ICMP").values());
            assertEquals("RESOLVED", object(s, "S-ICMP6").status());
            assertEquals(List.of("protocol: icmp6", "icmp-type: 128"), object(s, "S-ICMP6").values());
            // protocol-number is kept but not evaluated; a bare IP service has no number: unknown.
            assertEquals("UNSUPPORTED", object(s, "S-GRE").status());
            assertTrue(object(s, "S-GRE").values().contains("protocol-number: 47"));
            assertEquals("UNKNOWN", object(s, "S-IP-BARE").status());
            // dst:src syntax only in the measured shape; several ranges with a source become a group of exact pairs.
            assertEquals(List.of("protocol/tcp/source-port: 1024-65535", "protocol/tcp/port: 443"), object(s, "S-SRC").values());
            assertEquals("service-group", object(s, "S-SRC-MULTI").type());
            assertEquals(2, object(s, "S-SRC-MULTI").members().size());
            // A form outside the measured syntax is UNSUPPORTED, never guessed.
            assertEquals("UNSUPPORTED", object(s, "S-BAD").status());

            // The ranges are what the lookup compares.
            var lookup = snap(cfg("config firewall service custom\n    edit \"S-RANGE\"\n        set tcp-portrange 80 8000-8010\n    next\nend\n",
                    pol(1, "service \"S-RANGE\"")));
            assertEquals("ALLOWED", look(lookup, "192.0.2.5", "198.51.100.20", "tcp/8005", "port1", "port2").get("verdict"));
            assertEquals("ALLOWED", look(lookup, "192.0.2.5", "198.51.100.20", "tcp/80", "port1", "port2").get("verdict"));
            assertEquals("DENIED", look(lookup, "192.0.2.5", "198.51.100.20", "tcp/8011", "port1", "port2").get("verdict"));
            assertEquals("DENIED", look(lookup, "192.0.2.5", "198.51.100.20", "udp/80", "port1", "port2").get("verdict"));
        }
    }

    @Nested @DisplayName("A0-FG-27")
    class Fg27 {
        @Test void predefinedServiceWithoutDefinitionIsUnresolved() {
            // "HTTP" is a predefined FortiGate service but no stored definition is in this configuration.
            var s = snap(cfg("config firewall service group\n    edit \"SVC-GRP\"\n        set member \"HTTPS\" \"DNS\"\n    next\nend\n",
                    pol(1, "service \"HTTP\"") + pol(2, "service \"SVC-GRP\"")));
            assertEquals("UNRESOLVED", object(s, "HTTP").status());
            assertEquals("unresolved", object(s, "HTTP").type());
            assertEquals("UNRESOLVED", object(s, "DNS").status(), "an undefined group member is unresolved too");
            var result = https(s);
            assertEquals("UNKNOWN", result.get("verdict"), "the undefined service may be what the packet uses");
            assertEquals(2, undecidable(result).size());
            // A group whose members are all stored resolves; the stored definitions decide.
            var ok = snap(cfg("config firewall service group\n    edit \"SVC-GRP\"\n        set member \"HTTPS\" \"SSH\"\n    next\nend\n", pol(1, "service \"SVC-GRP\"")));
            assertEquals("RESOLVED", object(ok, "SVC-GRP").status());
            assertEquals("ALLOWED", https(ok).get("verdict"));
            assertEquals("ALLOWED", look(ok, "192.0.2.5", "198.51.100.20", "tcp/22", "port1", "port2").get("verdict"));
        }
    }
}
