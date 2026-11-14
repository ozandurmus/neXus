package com.securityexpert.nexus.ui2.policy.fortigate;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.CollectionFailure;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Section;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGateDefaults.CitedDefault;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.VdomConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FortiGatePolicyParserAndMapperTest {

    private static final String DEVICE_ID = "dev-fgt-tango-04";
    private static final String DEVICE_NAME = "FGT-TANGO-04";
    private static final String COLLECTED_AT = "2026-11-14T10:00:00Z";
    private static final String ARTEFACT_REF = "cas-fgt-0001";

    private static final String SINGLE_VDOM_7_4_CONFIG = """
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
                edit "port3"
                    set vdom "root"
                    set ip 203.0.113.1 255.255.255.0
                next
            end
            config system zone
                edit "ZONE-DMZ"
                    set intrazone allow
                    set interface "port2"
                next
                edit "ZONE-STRICT"
                    set interface "port3"
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
                        set interface "port1"
                        set zone "virtual-wan-link"
                    next
                end
            end
            config firewall address
                edit "all"
                next
                edit "none"
                    set subnet 0.0.0.0 255.255.255.255
                next
                edit "HOST-APP-01"
                    set subnet 192.0.2.10 255.255.255.255
                next
                edit "NET-INSIDE"
                    set type ipmask
                    set subnet 192.0.2.0 255.255.255.0
                next
                edit "RANGE-POOL"
                    set type iprange
                    set start-ip 198.51.100.10
                    set end-ip 198.51.100.20
                next
                edit "FQDN-PORTAL"
                    set type fqdn
                    set fqdn "portal.example.invalid"
                next
                edit "GEO-COUNTRY"
                    set type geography
                    set country "US"
                next
                edit "WILDCARD-OBJ"
                    set type wildcard
                    set wildcard 192.0.2.0 255.255.0.255
                next
                edit "NONCONTIG-MASK"
                    set subnet 192.0.2.0 255.0.255.0
                next
                edit "IF-SUBNET-PORT2"
                    set type interface-subnet
                    set interface "port2"
                next
            end
            config firewall addrgrp
                edit "GRP-SERVERS"
                    set member "HOST-APP-01" "NET-INSIDE"
                next
                edit "GRP-WITH-EXCLUDE"
                    set member "NET-INSIDE"
                    set exclude enable
                    set exclude-member "HOST-APP-01"
                next
            end
            config firewall address6
                edit "IPV6-HOST"
                    set ip6 2001:db8::10/128
                next
            end
            config firewall addrgrp6
                edit "IPV6-GRP"
                    set member "IPV6-HOST"
                next
            end
            config firewall service custom
                edit "ALL"
                    set protocol ALL
                next
                edit "TCP-8443"
                    set tcp-portrange 8443
                next
                edit "UDP-53"
                    set udp-portrange 53
                next
                edit "SVC-WITH-SRC"
                    set tcp-portrange 443:1024-65535
                next
                edit "SVC-MULTI-SRC"
                    set tcp-portrange 443:1024-65535 8443
                next
                edit "ICMP-ECHO"
                    set protocol ICMP
                    set icmptype 8
                    set icmpcode 0
                next
                edit "IP-GRE"
                    set protocol IP
                    set protocol-number 47
                next
                edit "IP-DEFAULT-PROTO"
                    set protocol IP
                next
                edit "SCTP-SVC"
                    set sctp-portrange 3868
                next
            end
            config firewall service group
                edit "SVC-GRP-WEB"
                    set member "TCP-8443" "UDP-53"
                next
            end
            config firewall vip
                edit "VIP-WEB-01"
                    set extip 203.0.113.10
                    set mappedip "198.51.100.10"
                    set extintf "port1"
                    set portforward enable
                    set extport 443
                    set mappedport 8443
                next
            end
            config firewall vipgrp
                edit "VIPGRP-WEB"
                    set interface "port1"
                    set member "VIP-WEB-01"
                next
            end
            config firewall ippool
                edit "POOL-EGRESS"
                    set startip 203.0.113.50
                    set endip 203.0.113.60
                next
            end
            config firewall central-snat-map
                edit 1
                    set srcintf "port2"
                    set dstintf "port1"
                    set orig-addr "NET-INSIDE"
                    set dst-addr "all"
                    set nat-ippool "POOL-EGRESS"
                next
            end
            config firewall policy
                edit 10
                    set name "ALLOW-DMZ-WEB"
                    set uuid 00000000-0000-0000-0000-000000000010
                    set srcintf "virtual-wan-link"
                    set dstintf "ZONE-DMZ"
                    set action accept
                    set srcaddr "all"
                    set dstaddr "VIP-WEB-01"
                    set schedule "always"
                    set service "SVC-GRP-WEB"
                    set nat enable
                    set ippool enable
                    set poolname "POOL-EGRESS"
                    set comments "First in sequence despite policyid 10"
                next
                edit 2
                    set srcintf "ZONE-DMZ"
                    set dstintf "port3"
                    set srcaddr "GRP-SERVERS"
                    set dstaddr "RANGE-POOL"
                    set schedule "WORK-HOURS"
                    set service "SVC-MULTI-SRC"
                    set srcaddr-negate enable
                    set users "alice"
                    set groups "OPS-TEAM"
                next
                edit 5
                    set name "DISABLED-POLICY"
                    set srcintf "port2"
                    set dstintf "port1"
                    set action accept
                    set status disable
                    set srcaddr "HOST-APP-01"
                    set dstaddr "all"
                    set schedule "always"
                    set service "ALL"
                next
                edit 7
                    set name "ISDB-POLICY"
                    set srcintf "port2"
                    set dstintf "port1"
                    set action accept
                    set srcaddr "NET-INSIDE"
                    set internet-service enable
                    set internet-service-name "Example-Cloud"
                    set schedule "always"
                next
                edit 8
                    set name "IPSEC-POLICY"
                    set srcintf "port2"
                    set dstintf "port1"
                    set action ipsec
                    set vpntunnel "TUN-BRANCH-01"
                    set srcaddr "NET-INSIDE"
                    set dstaddr "all"
                    set schedule "always"
                    set service "ALL"
                next
                edit 9
                    set name "MISSING-SCHEDULE-POLICY"
                    set srcintf "port2"
                    set dstintf "port1"
                    set action accept
                    set srcaddr "NET-INSIDE"
                    set dstaddr "all"
                    set service "ALL"
                next
                edit 11
                    set name "NONE-ADDRESS-POLICY"
                    set srcintf "port2"
                    set dstintf "port1"
                    set action accept
                    set srcaddr "none"
                    set dstaddr "all"
                    set schedule "always"
                    set service "ALL"
                next
            end
            config firewall local-in-policy
                edit 1
                    set intf "port1"
                    set srcaddr "all"
                    set dstaddr "all"
                    set action accept
                    set service "ALL"
                    set schedule "always"
                next
            end
            """;

    @Test
    void citedDefaultsTableCarriesCompleteProvenanceFor74() {
        assertTrue(FortiGateDefaults.isSupportedMinor("7.4"));
        assertFalse(FortiGateDefaults.isSupportedMinor("7.2"));
        assertFalse(FortiGateDefaults.isSupportedMinor(""));

        Map<String, CitedDefault> table = FortiGateDefaults.entriesForMinor("7.4");
        assertFalse(table.isEmpty());
        for (CitedDefault cd : table.values()) {
            assertEquals("7.4", cd.minorVersion());
            assertFalse(cd.table().isBlank());
            assertFalse(cd.setting().isBlank());
            assertFalse(cd.defaultValue().isBlank());
            assertTrue(cd.amendmentId().startsWith("A0-FG-"));
            assertTrue(cd.sourceKey().startsWith("FG-"));
            assertTrue(cd.sourceUrl().startsWith("https://docs.fortinet.com/"));
            assertFalse(cd.quote().isBlank());
        }

        assertEquals("deny", FortiGateDefaults.defaultValue("7.4", "firewall policy", "action"));
        assertEquals("enable", FortiGateDefaults.defaultValue("7.4", "firewall policy", "status"));
        assertEquals("disable", FortiGateDefaults.defaultValue("7.4", "firewall policy", "nat"));
        assertEquals("ipmask", FortiGateDefaults.defaultValue("7.4", "firewall address", "type"));
        assertEquals("0.0.0.0 0.0.0.0", FortiGateDefaults.defaultValue("7.4", "firewall address", "subnet"));
        assertEquals("TCP/UDP/SCTP", FortiGateDefaults.defaultValue("7.4", "firewall service custom", "protocol"));
        assertEquals("deny", FortiGateDefaults.defaultValue("7.4", "system zone", "intrazone"));
        assertNull(FortiGateDefaults.defaultValue("7.4", "firewall policy", "schedule"));
    }

    @Test
    void parsesAndMapsSingleVdomPreservingSequenceOrderAndDefaults() {
        FortiGatePolicyConfig config = FortiGateConfigPolicyParser.parse(SINGLE_VDOM_7_4_CONFIG);
        assertEquals("7.4", config.minorVersion());
        assertFalse(config.multiVdom());
        assertEquals(List.of("root"), List.copyOf(config.vdoms().keySet()));

        VdomConfig root = config.vdoms().get("root");
        assertNotNull(root);
        assertEquals(7, root.policies().size());
        // Sequence order must be 10, 2, 5, 7, 8, 9, 11 (never sorted by policyid)
        assertEquals(
                List.of("10", "2", "5", "7", "8", "9", "11"),
                root.policies().stream().map(p -> p.policyId()).toList());

        List<PolicySnapshot> snapshots = FortiGatePolicyMapper.map(
                DEVICE_ID, DEVICE_NAME, COLLECTED_AT, ARTEFACT_REF, config);
        assertEquals(1, snapshots.size());
        PolicySnapshot snapshot = snapshots.get(0);

        assertEquals("FGT", snapshot.metadata().vendor());
        assertEquals("root", snapshot.metadata().containerName());
        assertTrue(snapshot.ruleComplete());
        assertTrue(snapshot.failures().isEmpty());

        Map<String, Section> sectionsByName = new LinkedHashMap<>();
        for (Section s : snapshot.sections()) {
            sectionsByName.put(s.name(), s);
        }

        Section mainSection = sectionsByName.get("root policy");
        assertNotNull(mainSection);
        assertEquals("FGT firewall policy", mainSection.source());
        assertFalse(PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), mainSection));
        assertEquals(7, mainSection.rules().size());

        // Rule 1 (policyid 10): explicit accept, SD-WAN zone source, VIP destination, pool NAT
        Rule r1 = mainSection.rules().get(0);
        assertEquals(1, r1.number());
        assertEquals("ALLOW-DMZ-WEB", r1.name());
        assertEquals("00000000-0000-0000-0000-000000000010", r1.uuid());
        assertEquals("accept", r1.action());
        assertEquals(Boolean.TRUE, r1.enabled());
        assertEquals(List.of("virtual-wan-link"), r1.extras().get("from"));
        assertEquals(List.of("ZONE-DMZ"), r1.extras().get("to"));
        assertEquals(List.of("dynamic-ip-and-port"), r1.extras().get("source-translation-type"));
        assertNotNull(r1.extras().get("translated-source"));
        assertEquals(List.of("VIP-WEB-01"), r1.extras().get("fgt-vip"));

        // Rule 2 (policyid 2): omitted name -> policyId "2", omitted action -> default "deny",
        // non-always schedule -> extras.time, user/group -> extras.user,
        // srcaddr-negate enable -> source.negated() true
        Rule r2 = mainSection.rules().get(1);
        assertEquals(2, r2.number());
        assertEquals("2", r2.name());
        assertEquals("deny", r2.action());
        assertEquals(Boolean.TRUE, r2.enabled());
        assertTrue(r2.source().negated());
        assertFalse(r2.destination().negated());
        assertEquals(List.of("WORK-HOURS"), r2.extras().get("time"));
        assertEquals(List.of("OPS-TEAM", "alice"), r2.extras().get("user"));

        // Rule 3 (policyid 5): status disable -> enabled=false
        Rule r3 = mainSection.rules().get(2);
        assertEquals(3, r3.number());
        assertEquals(Boolean.FALSE, r3.enabled());
        assertEquals("accept", r3.action());

        // Rule 4 (policyid 7): internet-service enable -> DYNAMIC destination and service objects
        Rule r4 = mainSection.rules().get(3);
        PolicyObject r4DstObj = snapshot.objects().get(r4.destination().refs().get(0));
        PolicyObject r4SvcObj = snapshot.objects().get(r4.service().refs().get(0));
        assertEquals("DYNAMIC", r4DstObj.status());
        assertEquals("DYNAMIC", r4SvcObj.status());
        assertEquals(List.of("Example-Cloud"), r4.extras().get("fgt-internet-service"));

        // Rule 5 (policyid 8): action ipsec -> UNKNOWN
        Rule r5 = mainSection.rules().get(4);
        assertEquals("UNKNOWN", r5.action());
        assertEquals(List.of("ipsec"), r5.extras().get("fgt-action"));
        assertEquals(List.of("TUN-BRANCH-01"), r5.extras().get("vpn"));

        // Rule 6 (policyid 9): missing schedule (no default in A0-FG-5) -> fgt-missing-default
        Rule r6 = mainSection.rules().get(5);
        assertEquals(List.of("schedule"), r6.extras().get("fgt-missing-default"));

        // Rule 7 (policyid 11): srcaddr "none" -> UNRESOLVED (A0-FG-25)
        Rule r7 = mainSection.rules().get(6);
        PolicyObject r7SrcObj = snapshot.objects().get(r7.source().refs().get(0));
        assertEquals("UNRESOLVED", r7SrcObj.status());

        // Implicit section: ZONE-DMZ has intrazone allow (synthetic rule before implicit-deny),
        // ZONE-STRICT has default intrazone deny (no synthetic allow rule)
        Section implicitSection = sectionsByName.get("FGT implicit deny");
        assertNotNull(implicitSection);
        assertTrue(PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), implicitSection));
        assertEquals(2, implicitSection.rules().size());
        Rule intrazoneRule = implicitSection.rules().get(0);
        assertEquals("ZONE-DMZ intrazone allow", intrazoneRule.name());
        assertEquals("accept", intrazoneRule.action());
        assertEquals(List.of("ZONE-DMZ"), intrazoneRule.extras().get("from"));
        assertEquals(List.of("ZONE-DMZ"), intrazoneRule.extras().get("to"));
        Rule implicitDeny = implicitSection.rules().get(1);
        assertEquals("implicit-deny", implicitDeny.name());
        assertEquals("deny", implicitDeny.action());

        // VIP, central SNAT, and not-evaluated sections are outside security evaluation
        Section vipSection = sectionsByName.get("FGT VIP");
        assertNotNull(vipSection);
        assertTrue(PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), vipSection));
        assertEquals(1, vipSection.rules().size());

        Section snatSection = sectionsByName.get("FGT central SNAT");
        assertNotNull(snatSection);
        assertTrue(PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), snatSection));
        assertEquals(1, snatSection.rules().size());

        Section notEvaluatedSection = sectionsByName.get("FGT not evaluated");
        assertNotNull(notEvaluatedSection);
        assertTrue(PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), notEvaluatedSection));
        assertEquals(1, notEvaluatedSection.rules().size());
        assertEquals("UNKNOWN", notEvaluatedSection.rules().get(0).action());
    }

    @Test
    void mapsAddressesServicesAndVipObjectsToExpectedRepresentations() {
        FortiGatePolicyConfig config = FortiGateConfigPolicyParser.parse(SINGLE_VDOM_7_4_CONFIG);
        PolicySnapshot snapshot = FortiGatePolicyMapper.map(
                DEVICE_ID, DEVICE_NAME, COLLECTED_AT, ARTEFACT_REF, config).get(0);

        Map<String, PolicyObject> objectsByName = new LinkedHashMap<>();
        for (PolicyObject obj : snapshot.objects().values()) {
            objectsByName.put(obj.name(), obj);
        }

        // 'all' resolved via its config firewall address definition + 7.4 defaults (0.0.0.0/0)
        PolicyObject allAddr = objectsByName.get("all");
        assertNotNull(allAddr);
        assertEquals("network", allAddr.type());
        assertEquals("RESOLVED", allAddr.status());
        assertEquals(List.of("ip-netmask: 0.0.0.0/0"), allAddr.values());

        // 'none' is always UNRESOLVED (A0-FG-25) despite subnet 0.0.0.0 255.255.255.255
        PolicyObject noneAddr = objectsByName.get("none");
        assertNotNull(noneAddr);
        assertEquals("UNRESOLVED", noneAddr.status());

        // Host and subnet ipmask addresses
        PolicyObject hostAddr = objectsByName.get("HOST-APP-01");
        assertEquals("host", hostAddr.type());
        assertEquals("RESOLVED", hostAddr.status());
        assertEquals(List.of("ip-netmask: 192.0.2.10/32"), hostAddr.values());

        PolicyObject netAddr = objectsByName.get("NET-INSIDE");
        assertEquals("network", netAddr.type());
        assertEquals("RESOLVED", netAddr.status());
        assertEquals(List.of("ip-netmask: 192.0.2.0/24"), netAddr.values());

        // IP range
        PolicyObject rangeAddr = objectsByName.get("RANGE-POOL");
        assertEquals("address-range", rangeAddr.type());
        assertEquals("RESOLVED", rangeAddr.status());
        assertEquals(List.of("ip-range: 198.51.100.10-198.51.100.20"), rangeAddr.values());

        // interface-subnet resolved from port2's IP/mask
        PolicyObject ifSubnet = objectsByName.get("IF-SUBNET-PORT2");
        assertEquals("network", ifSubnet.type());
        assertEquals("RESOLVED", ifSubnet.status());
        assertEquals(List.of("ip-netmask: 198.51.100.1/24"), ifSubnet.values());

        // Dynamic and unsupported address types
        assertEquals("DYNAMIC", objectsByName.get("FQDN-PORTAL").status());
        assertEquals("DYNAMIC", objectsByName.get("GEO-COUNTRY").status());
        assertEquals("UNSUPPORTED", objectsByName.get("WILDCARD-OBJ").status());
        assertEquals("UNSUPPORTED", objectsByName.get("NONCONTIG-MASK").status());
        assertEquals("UNSUPPORTED", objectsByName.get("IPV6-HOST").status());
        assertEquals("UNSUPPORTED", objectsByName.get("IPV6-GRP").status());

        // Address group with exclude enable -> UNSUPPORTED (A0-FG-9)
        assertEquals("RESOLVED", objectsByName.get("GRP-SERVERS").status());
        assertEquals("UNSUPPORTED", objectsByName.get("GRP-WITH-EXCLUDE").status());

        // Services: ALL -> any, TCP/UDP portranges, source-port split service-group
        PolicyObject allSvc = objectsByName.get("ALL");
        assertNotNull(allSvc);
        assertEquals("any", allSvc.type());
        assertEquals("RESOLVED", allSvc.status());

        PolicyObject tcpSvc = objectsByName.get("TCP-8443");
        assertEquals("service", tcpSvc.type());
        assertEquals("RESOLVED", tcpSvc.status());
        assertEquals(List.of("protocol/tcp/port: 8443"), tcpSvc.values());

        PolicyObject srcSvc = objectsByName.get("SVC-WITH-SRC");
        assertEquals("service", srcSvc.type());
        assertEquals("RESOLVED", srcSvc.status());
        assertEquals(
                List.of("protocol/tcp/source-port: 1024-65535", "protocol/tcp/port: 443"),
                srcSvc.values());

        // SVC-MULTI-SRC has two ranges on TCP where one has :src -> split into a service-group of 2 children
        PolicyObject multiSrcGrp = objectsByName.get("SVC-MULTI-SRC");
        assertNotNull(multiSrcGrp);
        assertEquals("service-group", multiSrcGrp.type());
        assertEquals("RESOLVED", multiSrcGrp.status());
        assertEquals(2, multiSrcGrp.members().size());

        // ICMP, IP with protocol-number, IP without protocol-number, SCTP
        assertEquals("RESOLVED", objectsByName.get("ICMP-ECHO").status());
        assertEquals("UNSUPPORTED", objectsByName.get("IP-GRE").status());
        assertEquals("UNKNOWN", objectsByName.get("IP-DEFAULT-PROTO").status());
        assertEquals("UNSUPPORTED", objectsByName.get("SCTP-SVC").status());

        // VIP object in policy destination carries both extip and mappedip
        PolicyObject vipObj = objectsByName.get("VIP-WEB-01");
        assertNotNull(vipObj);
        assertEquals("RESOLVED", vipObj.status());
        assertTrue(vipObj.values().contains("ip-netmask: 203.0.113.10/32"));
        assertTrue(vipObj.values().contains("vip-mapped: 198.51.100.10"));
    }

    @Test
    void parsesMultiVdomWithGlobalInterfacesMultilineCommentsAndSingleClosingEnd() {
        String multiVdomConfig = """
                #config-version=FGT80F-7.4.8-FW-build2795-250521:opmode=0:vdom=1:user=admin
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
                    edit "wan1"
                        set vdom "VDOM-ALPHA"
                        set ip 203.0.113.1 255.255.255.0
                    next
                    edit "lan1"
                        set vdom "VDOM-ALPHA"
                        set ip 192.0.2.1 255.255.255.0
                    next
                    edit "beta1"
                        set vdom "VDOM-BETA"
                        set ip 198.51.100.1 255.255.255.0
                    next
                end
                end
                config vdom
                edit root
                config system settings
                end
                end
                config vdom
                edit VDOM-ALPHA
                config firewall address
                    edit "all"
                    next
                    edit "LAN-IF-SUBNET"
                        set type interface-subnet
                        set interface "lan1"
                    next
                end
                config firewall service custom
                    edit "ALL"
                        set protocol ALL
                    next
                end
                config firewall policy
                    edit 1
                        set name "ALPHA-OUT"
                        set srcintf "lan1"
                        set dstintf "wan1"
                        set action accept
                        set srcaddr "LAN-IF-SUBNET"
                        set dstaddr "all"
                        set schedule "always"
                        set service "ALL"
                        set comments "Line 1 of comment
                end
                next
                config firewall policy
                Line 5 of comment"
                    next
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
                config firewall policy
                    edit 1
                        set name "BETA-DENY"
                        set srcintf "beta1"
                        set dstintf "beta1"
                        set srcaddr "all"
                        set dstaddr "all"
                        set schedule "always"
                        set service "ALL"
                    next
                end
                """;

        FortiGatePolicyConfig config = FortiGateConfigPolicyParser.parse(multiVdomConfig);
        assertTrue(config.multiVdom());
        assertEquals(List.of("root", "VDOM-ALPHA", "VDOM-BETA"), List.copyOf(config.vdoms().keySet()));

        List<PolicySnapshot> snapshots = FortiGatePolicyMapper.map(
                DEVICE_ID, DEVICE_NAME, COLLECTED_AT, ARTEFACT_REF, config);
        assertEquals(3, snapshots.size());
        assertEquals("root", snapshots.get(0).metadata().containerName());
        assertEquals("VDOM-ALPHA", snapshots.get(1).metadata().containerName());
        assertEquals("VDOM-BETA", snapshots.get(2).metadata().containerName());

        // VDOM-ALPHA resolved LAN-IF-SUBNET from global interface 'lan1' assigned to VDOM-ALPHA
        PolicySnapshot alpha = snapshots.get(1);
        Section alphaMain = alpha.sections().stream()
                .filter(s -> "FGT firewall policy".equals(s.source()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, alphaMain.rules().size());
        PolicyObject lanIfSubnetObj = alpha.objects().get(alphaMain.rules().get(0).source().refs().get(0));
        assertEquals("RESOLVED", lanIfSubnetObj.status());
        assertEquals(List.of("ip-netmask: 192.0.2.1/24"), lanIfSubnetObj.values());

        // VDOM-BETA closed cleanly even though the final VDOM ended with a single 'end'
        PolicySnapshot beta = snapshots.get(2);
        Section betaMain = beta.sections().stream()
                .filter(s -> "FGT firewall policy".equals(s.source()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, betaMain.rules().size());
        assertEquals("BETA-DENY", betaMain.rules().get(0).name());
        assertEquals("deny", betaMain.rules().get(0).action());
    }

    @Test
    void failsClosedWhenFortiOsMinorVersionHasNoCitedDefaultsTable() {
        String config72 = SINGLE_VDOM_7_4_CONFIG.replace(
                "FGVM64-7.4.8-FW-build2795", "FGVM64-7.2.9-FW-build1517");
        FortiGatePolicyConfig parsed = FortiGateConfigPolicyParser.parse(config72);
        assertEquals("7.2", parsed.minorVersion());

        PolicySnapshot snapshot = FortiGatePolicyMapper.map(
                DEVICE_ID, DEVICE_NAME, COLLECTED_AT, ARTEFACT_REF, parsed).get(0);
        assertFalse(snapshot.ruleComplete());
        assertEquals(1, snapshot.failures().size());
        CollectionFailure failure = snapshot.failures().get(0);
        assertEquals("FGT_DEFAULTS_UNPROVEN", failure.reason());
        assertEquals("7.2", failure.layerName());

        // Policy 10 omitted status, srcaddr-negate, dstaddr-negate, service-negate -> records fgt-missing-default on 7.2
        Section mainSection = snapshot.sections().stream()
                .filter(s -> "FGT firewall policy".equals(s.source()))
                .findFirst()
                .orElseThrow();
        Rule r1 = mainSection.rules().get(0);
        assertNull(r1.enabled());
        assertNotNull(r1.extras().get("fgt-missing-default"));
    }

    @Test
    void refusesTransparentModeAndPolicyBasedNgfwModeAtVdomLevel() {
        String transparentConfig = """
                #config-version=FGVM64-7.4.8-FW-build2795-250521:opmode=1:vdom=0:user=admin
                config system settings
                    set opmode transparent
                end
                config firewall policy
                    edit 1
                        set srcintf "port1"
                        set dstintf "port2"
                        set action accept
                        set srcaddr "all"
                        set dstaddr "all"
                        set schedule "always"
                        set service "ALL"
                    next
                end
                """;
        PolicySnapshot transparentSnap = FortiGatePolicyMapper.map(
                DEVICE_ID,
                DEVICE_NAME,
                COLLECTED_AT,
                ARTEFACT_REF,
                FortiGateConfigPolicyParser.parse(transparentConfig)).get(0);
        assertFalse(transparentSnap.ruleComplete());
        assertTrue(transparentSnap.sections().isEmpty());
        assertEquals(1, transparentSnap.failures().size());
        assertEquals("FGT_UNSUPPORTED_MODE", transparentSnap.failures().get(0).reason());
        assertEquals("opmode:transparent", transparentSnap.failures().get(0).layerName());

        String ngfwPolicyModeConfig = """
                #config-version=FGVM64-7.4.8-FW-build2795-250521:opmode=0:vdom=0:user=admin
                config system settings
                    set ngfw-mode policy-based
                end
                """;
        PolicySnapshot ngfwSnap = FortiGatePolicyMapper.map(
                DEVICE_ID,
                DEVICE_NAME,
                COLLECTED_AT,
                ARTEFACT_REF,
                FortiGateConfigPolicyParser.parse(ngfwPolicyModeConfig)).get(0);
        assertFalse(ngfwSnap.ruleComplete());
        assertTrue(ngfwSnap.sections().isEmpty());
        assertEquals(1, ngfwSnap.failures().size());
        assertEquals("FGT_UNSUPPORTED_MODE", ngfwSnap.failures().get(0).reason());
        assertEquals("ngfw-mode:policy-based", ngfwSnap.failures().get(0).layerName());
    }

    @Test
    void handlesBlankRawConfigurationFailClosed() {
        FortiGatePolicyConfig empty = FortiGateConfigPolicyParser.parse("");
        assertEquals("", empty.minorVersion());
        assertEquals(List.of("root"), List.copyOf(empty.vdoms().keySet()));
        assertTrue(empty.vdoms().get("root").policies().isEmpty());

        PolicySnapshot snapshot = FortiGatePolicyMapper.map(
                DEVICE_ID, DEVICE_NAME, COLLECTED_AT, ARTEFACT_REF, empty).get(0);
        assertFalse(snapshot.ruleComplete());
        assertEquals("FGT_DEFAULTS_UNPROVEN", snapshot.failures().get(0).reason());
    }

    @Test
    void parsesAndMapsFiveThousandPoliciesInLinearTime() {
        StringBuilder sb = new StringBuilder(512_000);
        sb.append("#config-version=FGVM64-7.4.8-FW-build2795-250521:opmode=0:vdom=0:user=admin\n");
        sb.append("config system interface\n");
        sb.append("    edit \"port1\"\n        set vdom \"root\"\n        set ip 192.0.2.1 255.255.255.0\n    next\n");
        sb.append("    edit \"port2\"\n        set vdom \"root\"\n        set ip 198.51.100.1 255.255.255.0\n    next\n");
        sb.append("end\n");
        sb.append("config firewall address\n    edit \"all\"\n    next\nend\n");
        sb.append("config firewall service custom\n    edit \"ALL\"\n        set protocol ALL\n    next\nend\n");
        sb.append("config firewall policy\n");
        for (int i = 5000; i >= 1; i--) {
            sb.append("    edit ").append(i).append('\n');
            sb.append("        set name \"RULE-").append(i).append("\"\n");
            sb.append("        set srcintf \"port1\"\n");
            sb.append("        set dstintf \"port2\"\n");
            sb.append("        set action accept\n");
            sb.append("        set srcaddr \"all\"\n");
            sb.append("        set dstaddr \"all\"\n");
            sb.append("        set schedule \"always\"\n");
            sb.append("        set service \"ALL\"\n");
            sb.append("    next\n");
        }
        sb.append("end\n");

        long startNanos = System.nanoTime();
        FortiGatePolicyConfig config = FortiGateConfigPolicyParser.parse(sb.toString());
        List<PolicySnapshot> snapshots = FortiGatePolicyMapper.map(
                DEVICE_ID, DEVICE_NAME, COLLECTED_AT, ARTEFACT_REF, config);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertEquals(1, snapshots.size());
        Section mainSection = snapshots.get(0).sections().get(0);
        assertEquals(5000, mainSection.rules().size());
        // First rule in sequence has policyid 5000, number 1
        assertEquals("RULE-5000", mainSection.rules().get(0).name());
        assertEquals(1, mainSection.rules().get(0).number());
        assertTrue(elapsedMillis < 5_000, "Expected linear-time parse+map < 5000ms, took " + elapsedMillis + "ms");
    }
}
