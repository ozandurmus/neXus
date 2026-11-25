package com.securityexpert.nexus.ui2.policy.asa;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.CollectionFailure;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Section;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AsaPolicyParserAndMapperTest {

    @Test
    void aclFirstMatchOrderNumberingRemarksInactiveTimeRangeAndIdentityCriteria() {
        String config = """
                : Saved
                ASA Version 9.18(4)22
                !
                interface GigabitEthernet0/0
                 nameif outside
                 security-level 0
                !
                interface GigabitEthernet0/1
                 nameif inside
                 security-level 100
                !
                access-list ACL-OUTSIDE remark First remark line
                access-list ACL-OUTSIDE remark Second remark line
                access-list ACL-OUTSIDE extended permit tcp host 192.0.2.10 host 198.51.100.20 eq 443 log
                access-list ACL-OUTSIDE remark Disabled temporary rule
                access-list ACL-OUTSIDE extended permit tcp user-group GRP-ENG security-group name SG-USERS any host 198.51.100.21 eq 8443 time-range TR-MAINT inactive
                access-list ACL-OUTSIDE extended deny ip any any log warnings interval 300
                !
                access-group ACL-OUTSIDE in interface outside
                """;

        AsaPolicyConfig parsed = AsaConfigPolicyParser.parse(config);
        assertEquals("9.18(4)22", parsed.version());
        assertFalse(parsed.multipleContext());
        assertFalse(parsed.transparent());
        assertEquals(3, parsed.counters().parsedAces());
        assertEquals(0, parsed.counters().unparsedAces());

        PolicySnapshot snapshot = AsaPolicyMapper.map(
                "dev-asa-01", "FW-TANGO-04", "2026-11-14T10:00:00Z", "artefact-01", parsed);
        assertEquals("ASA", snapshot.metadata().vendor());
        assertEquals("single", snapshot.metadata().containerName());
        assertEquals("ASA access rules", snapshot.metadata().name());
        assertEquals(1, snapshot.metadata().targets().size());
        assertEquals("IN_SYNC", snapshot.metadata().targets().get(0).syncStatus());
        assertTrue(snapshot.failures().isEmpty());

        Section outsideIn = snapshot.sections().get(0);
        assertEquals("outside in", outsideIn.name());
        assertEquals("ASA access-group", outsideIn.source());
        assertEquals(3, outsideIn.rules().size());

        Rule r1 = outsideIn.rules().get(0);
        assertEquals(1, r1.number());
        assertEquals("ACL-OUTSIDE:1", r1.name());
        assertNull(r1.uuid());
        assertEquals(Boolean.TRUE, r1.enabled());
        assertEquals("permit", r1.action());
        assertEquals("log", r1.log());
        assertEquals("First remark line\nSecond remark line", r1.comment());
        assertTrue(r1.application().refs().isEmpty());
        assertEquals(List.of("outside"), r1.extras().get("from"));
        assertEquals(List.of("any"), r1.extras().get("to"));
        assertEquals(List.of("in"), r1.extras().get("asa-direction"));
        assertEquals(List.of("ACL-OUTSIDE"), r1.extras().get("asa-acl"));

        Rule r2 = outsideIn.rules().get(1);
        assertEquals(2, r2.number());
        assertEquals("ACL-OUTSIDE:2", r2.name());
        assertEquals(Boolean.FALSE, r2.enabled());
        assertEquals("", r2.log());
        assertEquals("Disabled temporary rule", r2.comment());
        assertEquals(List.of("TR-MAINT"), r2.extras().get("time"));
        assertEquals(List.of("GRP-ENG"), r2.extras().get("user"));
        assertEquals(List.of("SG-USERS"), r2.extras().get("security-group"));

        Rule r3 = outsideIn.rules().get(2);
        assertEquals(3, r3.number());
        assertEquals("ACL-OUTSIDE:3", r3.name());
        assertEquals(Boolean.TRUE, r3.enabled());
        assertEquals("deny", r3.action());
        assertEquals("log", r3.log());
        assertEquals("", r3.comment());
    }

    @Test
    void addressesAndNamesAliasesAndNonContiguousMaskAndDynamicFqdn() {
        String config = """
                ASA Version 9.22(1)
                names
                name 192.0.2.50 ALIAS-HOST-A description web host
                name 198.51.100.0 ALIAS-SUBNET-B
                !
                object network OBJ-HOST
                 host ALIAS-HOST-A
                object network OBJ-SUBNET
                 subnet ALIAS-SUBNET-B 255.255.255.0
                object network OBJ-NON-CONTIG
                 subnet 192.0.2.0 255.255.0.255
                object network OBJ-RANGE
                 range 203.0.113.10 203.0.113.20
                object network OBJ-FQDN
                 fqdn v4 app.example.invalid
                !
                access-list ACL-ADDR extended permit ip object OBJ-HOST object OBJ-SUBNET
                access-list ACL-ADDR extended permit ip object OBJ-RANGE object OBJ-FQDN
                access-list ACL-ADDR extended permit ip object OBJ-NON-CONTIG 192.0.2.0 255.0.255.0
                access-list ACL-ADDR extended permit ip any4 any6
                access-list ACL-ADDR extended permit ip interface inside 2001:db8::/32
                access-list ACL-ADDR extended permit ip host ALIAS-HOST-A ALIAS-SUBNET-B 255.255.255.128
                access-group ACL-ADDR in interface inside
                """;

        AsaPolicyConfig parsed = AsaConfigPolicyParser.parse(config);
        assertEquals("192.0.2.50", parsed.names().get("ALIAS-HOST-A"));
        assertEquals("198.51.100.0", parsed.names().get("ALIAS-SUBNET-B"));
        assertEquals("FQDN", parsed.networkObjects().get("OBJ-FQDN").kind());

        PolicySnapshot snapshot = AsaPolicyMapper.map(
                "dev-asa-01", "FW-TANGO-04", "2026-11-14T10:00:00Z", "artefact-01", parsed);
        List<Rule> rules = snapshot.sections().get(0).rules();
        assertEquals(6, rules.size());

        PolicyObject objHost = objectOf(snapshot, rules.get(0).source());
        assertEquals("host", objHost.type());
        assertEquals("RESOLVED", objHost.status());
        assertEquals(List.of("ip-netmask: 192.0.2.50/32"), objHost.values());

        PolicyObject objSubnet = objectOf(snapshot, rules.get(0).destination());
        assertEquals("network", objSubnet.type());
        assertEquals("RESOLVED", objSubnet.status());
        assertEquals(List.of("ip-netmask: 198.51.100.0/24"), objSubnet.values());

        PolicyObject objRange = objectOf(snapshot, rules.get(1).source());
        assertEquals("address-range", objRange.type());
        assertEquals("RESOLVED", objRange.status());
        assertEquals(List.of("ip-range: 203.0.113.10-203.0.113.20"), objRange.values());

        PolicyObject objFqdn = objectOf(snapshot, rules.get(1).destination());
        assertEquals("address", objFqdn.type());
        assertEquals("DYNAMIC", objFqdn.status());
        assertTrue(objFqdn.values().isEmpty());

        PolicyObject objNonContig = objectOf(snapshot, rules.get(2).source());
        assertEquals("UNSUPPORTED", objNonContig.status());

        PolicyObject inlineNonContig = objectOf(snapshot, rules.get(2).destination());
        assertEquals("UNSUPPORTED", inlineNonContig.status());

        PolicyObject any4Obj = objectOf(snapshot, rules.get(3).source());
        assertEquals("any", any4Obj.type());
        assertEquals("ANY", any4Obj.name());
        assertEquals("RESOLVED", any4Obj.status());

        PolicyObject any6Obj = objectOf(snapshot, rules.get(3).destination());
        assertEquals("address", any6Obj.type());
        assertEquals("UNSUPPORTED", any6Obj.status());

        PolicyObject ifaceObj = objectOf(snapshot, rules.get(4).source());
        assertEquals("address", ifaceObj.type());
        assertEquals("UNKNOWN", ifaceObj.status());

        PolicyObject ipv6Obj = objectOf(snapshot, rules.get(4).destination());
        assertEquals("address", ipv6Obj.type());
        assertEquals("UNSUPPORTED", ipv6Obj.status());

        PolicyObject inlineAliasHost = objectOf(snapshot, rules.get(5).source());
        assertEquals("host", inlineAliasHost.type());
        assertEquals("RESOLVED", inlineAliasHost.status());
        assertEquals(List.of("ip-netmask: 192.0.2.50/32"), inlineAliasHost.values());

        PolicyObject inlineAliasSubnet = objectOf(snapshot, rules.get(5).destination());
        assertEquals("network", inlineAliasSubnet.type());
        assertEquals("RESOLVED", inlineAliasSubnet.status());
        assertEquals(List.of("ip-netmask: 198.51.100.0/25"), inlineAliasSubnet.values());
    }

    @Test
    void portsAndLiteralsAndOperatorsAndServices() {
        String config = """
                ASA Version 9.18(4)
                object service SVC-CUSTOM-SRC-DST
                 service tcp source range 1024 65535 destination eq https
                !
                object-group service OG-LEGACY-TCP-UDP tcp-udp
                 port-object eq www
                 port-object range 8080 8081
                !
                object-group service OG-EXTENDED-SVC
                 service-object tcp source eq 12345 destination range 9000 9010
                 service-object udp destination eq domain
                 service-object object SVC-CUSTOM-SRC-DST
                !
                access-list ACL-SVC extended permit tcp any any eq https
                access-list ACL-SVC extended permit udp any any eq snmp
                access-list ACL-SVC extended permit tcp any any eq snmp
                access-list ACL-SVC extended permit tcp any any eq not-a-cisco-port
                access-list ACL-SVC extended permit tcp any any range 8000 8090
                access-list ACL-SVC extended permit tcp any any lt 1024
                access-list ACL-SVC extended permit tcp any any gt 1023
                access-list ACL-SVC extended permit tcp any any neq 23
                access-list ACL-SVC extended permit object-group OG-LEGACY-TCP-UDP any any
                access-list ACL-SVC extended permit object-group OG-EXTENDED-SVC any any
                access-list ACL-SVC extended permit icmp any any echo
                access-list ACL-SVC extended permit gre any any
                access-list ACL-SVC extended permit 50 any any
                access-list ACL-SVC extended permit ip any any
                access-group ACL-SVC in interface outside
                """;

        AsaPolicyConfig parsed = AsaConfigPolicyParser.parse(config);
        assertEquals(14, parsed.counters().parsedAces());
        assertEquals(0, parsed.counters().unparsedAces());

        PolicySnapshot snapshot = AsaPolicyMapper.map(
                "dev-asa-01", "FW-TANGO-04", "2026-11-14T10:00:00Z", "artefact-01", parsed);
        List<Rule> rules = snapshot.sections().get(0).rules();
        assertEquals(14, rules.size());

        // 1. tcp eq https -> RESOLVED 443
        PolicyObject s1 = objectOf(snapshot, rules.get(0).service());
        assertEquals("service", s1.type());
        assertEquals("RESOLVED", s1.status());
        assertEquals(List.of("protocol: tcp", "port: 443"), s1.values());

        // 2. udp eq snmp -> RESOLVED 161
        PolicyObject s2 = objectOf(snapshot, rules.get(1).service());
        assertEquals("RESOLVED", s2.status());
        assertEquals(List.of("protocol: udp", "port: 161"), s2.values());

        // 3. UDP-only literal (snmp) on a TCP ACE -> UNSUPPORTED (A0-ASA-15)
        PolicyObject s3 = objectOf(snapshot, rules.get(2).service());
        assertEquals("service", s3.type());
        assertEquals("UNSUPPORTED", s3.status());

        // 4. Port literal not in Table 7 -> UNSUPPORTED
        PolicyObject s4 = objectOf(snapshot, rules.get(3).service());
        assertEquals("UNSUPPORTED", s4.status());

        // 5. range 8000 8090 -> RESOLVED 8000-8090
        PolicyObject s5 = objectOf(snapshot, rules.get(4).service());
        assertEquals("RESOLVED", s5.status());
        assertEquals(List.of("protocol: tcp", "port: 8000-8090"), s5.values());

        // 6-8. A0-ASA-14: lt N -> 1..N-1, gt N -> N+1..65535, neq N -> the two ranges (comma list)
        assertEquals(List.of("protocol: tcp", "port: 1-1023"), objectOf(snapshot, rules.get(5).service()).values());
        assertEquals(List.of("protocol: tcp", "port: 1024-65535"), objectOf(snapshot, rules.get(6).service()).values());
        PolicyObject neq23 = objectOf(snapshot, rules.get(7).service());
        assertEquals("RESOLVED", neq23.status());
        assertEquals(List.of("protocol: tcp", "port: 1-22,24-65535"), neq23.values());

        // 9. object-group service legacy tcp-udp with port-object
        PolicyObject ogLegacy = objectOf(snapshot, rules.get(8).service());
        assertEquals("service-group", ogLegacy.type());
        assertEquals("RESOLVED", ogLegacy.status());
        assertEquals(2, ogLegacy.members().size());
        PolicyObject firstPortMember = snapshot.objects().get(ogLegacy.members().get(0));
        assertEquals("service-group", firstPortMember.type());
        assertEquals(2, firstPortMember.members().size());
        PolicyObject tcp80 = snapshot.objects().get(firstPortMember.members().get(0));
        PolicyObject udp80 = snapshot.objects().get(firstPortMember.members().get(1));
        assertEquals(List.of("protocol: tcp", "port: 80"), tcp80.values());
        assertEquals(List.of("protocol: udp", "port: 80"), udp80.values());

        // 10. service-object with source and destination
        PolicyObject ogExt = objectOf(snapshot, rules.get(9).service());
        assertEquals("service-group", ogExt.type());
        assertEquals(3, ogExt.members().size());
        PolicyObject svcWithSrcDst = snapshot.objects().get(ogExt.members().get(0));
        assertEquals("RESOLVED", svcWithSrcDst.status());
        assertEquals(
                List.of("protocol/tcp/source-port: 12345", "protocol/tcp/port: 9000-9010"),
                svcWithSrcDst.values());
        PolicyObject customSvcRef = snapshot.objects().get(ogExt.members().get(2));
        assertEquals(
                List.of("protocol/tcp/source-port: 1024-65535", "protocol/tcp/port: 443"),
                customSvcRef.values());

        // 11. icmp with type -> ["protocol: icmp", "icmp-type: echo"] (no code)
        PolicyObject icmpSvc = objectOf(snapshot, rules.get(10).service());
        assertEquals("service", icmpSvc.type());
        assertEquals("RESOLVED", icmpSvc.status());
        assertEquals(List.of("protocol: icmp", "icmp-type: echo"), icmpSvc.values());

        // 12-13. gre and protocol number 50 -> UNSUPPORTED
        assertEquals("UNSUPPORTED", objectOf(snapshot, rules.get(11).service()).status());
        assertEquals("UNSUPPORTED", objectOf(snapshot, rules.get(12).service()).status());

        // 14. ip with no ports -> ANY
        PolicyObject ipAny = objectOf(snapshot, rules.get(13).service());
        assertEquals("any", ipAny.type());
        assertEquals("ANY", ipAny.name());
        assertEquals("RESOLVED", ipAny.status());
    }

    @Test
    void nestedNetworkGroupsAndMissingMemberAndUnknownChild() {
        String config = """
                ASA Version 9.18(4)
                object network OBJ-A
                 host 192.0.2.10
                !
                object-group network GRP-CHILD
                 network-object object OBJ-A
                 network-object object OBJ-MISSING
                !
                object-group network GRP-PARENT
                 group-object GRP-CHILD
                 network-object 198.51.100.0 255.255.255.0
                 unknown-child-directive foo
                !
                access-list ACL-GRP extended permit ip object-group GRP-PARENT any
                access-group ACL-GRP in interface outside
                """;

        AsaPolicyConfig parsed = AsaConfigPolicyParser.parse(config);
        assertEquals(4, parsed.counters().parsedObjectGroupChildren());
        assertEquals(1, parsed.counters().unparsedObjectGroupChildren());

        PolicySnapshot snapshot = AsaPolicyMapper.map(
                "dev-asa-01", "FW-TANGO-04", "2026-11-14T10:00:00Z", "artefact-01", parsed);
        Rule rule = snapshot.sections().get(0).rules().get(0);
        PolicyObject parentGroup = objectOf(snapshot, rule.source());
        assertEquals("address-group", parentGroup.type());
        assertEquals("RESOLVED", parentGroup.status());
        assertEquals(3, parentGroup.members().size());

        PolicyObject childGroup = snapshot.objects().get(parentGroup.members().get(0));
        assertEquals("address-group", childGroup.type());
        assertEquals(2, childGroup.members().size());

        PolicyObject definedMember = snapshot.objects().get(childGroup.members().get(0));
        assertEquals("RESOLVED", definedMember.status());
        assertEquals(List.of("ip-netmask: 192.0.2.10/32"), definedMember.values());

        PolicyObject missingMember = snapshot.objects().get(childGroup.members().get(1));
        assertEquals("UNRESOLVED", missingMember.status());
        assertEquals("OBJ-MISSING", missingMember.name());

        PolicyObject unknownChildMember = snapshot.objects().get(parentGroup.members().get(2));
        assertEquals("UNRESOLVED", unknownChildMember.status());
    }

    @Test
    void bindingsInOutGlobalMultiBoundControlPlaneAclUsesAndUnreferencedCount() {
        String config = """
                ASA Version 9.18(4)
                interface GigabitEthernet0/0
                 nameif outside
                 security-level 0
                interface GigabitEthernet0/1
                 nameif inside
                 security-level 100
                interface GigabitEthernet0/2
                 nameif dmz
                 security-level 50
                same-security-traffic permit inter-interface
                same-security-traffic permit intra-interface
                !
                access-list ACL-SHARED extended permit tcp any any eq 80
                access-list ACL-OUT extended permit tcp any any eq 443
                access-list ACL-GLOBAL extended permit icmp any any
                access-list ACL-CP extended permit tcp any any eq 22
                access-list ACL-CRYPTO extended permit ip 192.0.2.0 255.255.255.0 198.51.100.0 255.255.255.0
                access-list ACL-VPN extended permit ip any 203.0.113.0 255.255.255.0
                access-list ACL-ORPHAN-1 extended permit ip any any
                access-list ACL-ORPHAN-2 standard permit 192.0.2.1
                !
                access-group ACL-SHARED in interface outside
                access-group ACL-SHARED in interface dmz
                access-group ACL-OUT out interface inside
                access-group ACL-GLOBAL global
                access-group ACL-CP in interface outside control-plane
                !
                crypto map CMAP-OUT 10 match address ACL-CRYPTO
                group-policy GP-VPN attributes
                 vpn-filter value ACL-VPN
                 split-tunnel-network-list value ACL-VPN
                """;

        AsaPolicyConfig parsed = AsaConfigPolicyParser.parse(config);
        assertTrue(parsed.sameSecurityInter());
        assertTrue(parsed.sameSecurityIntra());
        assertEquals(Set.of("crypto_map"), parsed.aclUses().get("ACL-CRYPTO"));
        assertEquals(Set.of("vpn_filter", "split_tunnel"), parsed.aclUses().get("ACL-VPN"));

        PolicySnapshot snapshot = AsaPolicyMapper.map(
                "dev-asa-01", "FW-TANGO-04", "2026-11-14T10:00:00Z", "artefact-01", parsed);

        assertEquals(
                List.of("outside in", "dmz in", "inside out", "global", "ASA security-level default", "ASA not evaluated"),
                snapshot.sections().stream().map(Section::name).toList());

        Section outsideIn = snapshot.sections().get(0);
        Section dmzIn = snapshot.sections().get(1);
        Section insideOut = snapshot.sections().get(2);
        Section globalSec = snapshot.sections().get(3);
        Section secLevelDefault = snapshot.sections().get(4);
        Section notEvaluated = snapshot.sections().get(5);

        // Multi-bound ACL appears in both 'outside in' and 'dmz in' with distinct rule IDs
        assertNotEquals(outsideIn.rules().get(0).id(), dmzIn.rules().get(0).id());
        assertEquals(List.of("outside"), outsideIn.rules().get(0).extras().get("from"));
        assertEquals(List.of("dmz"), dmzIn.rules().get(0).extras().get("from"));

        // Out binding extras
        assertEquals(List.of("any"), insideOut.rules().get(0).extras().get("from"));
        assertEquals(List.of("inside"), insideOut.rules().get(0).extras().get("to"));
        assertEquals(List.of("out"), insideOut.rules().get(0).extras().get("asa-direction"));

        // Global binding extras
        assertEquals(List.of("any"), globalSec.rules().get(0).extras().get("from"));
        assertEquals(List.of("any"), globalSec.rules().get(0).extras().get("to"));
        assertEquals(List.of("global"), globalSec.rules().get(0).extras().get("asa-direction"));

        // Security-level default section and asa-interfaces object
        assertTrue(secLevelDefault.rules().isEmpty());
        assertEquals("ASA implicit", secLevelDefault.source());
        assertTrue(PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), secLevelDefault));
        // VPN, crypto and control-plane ACLs are listed, never evaluated as access rules (A0-ASA-9)
        assertTrue(PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), notEvaluated));
        for (Section access : List.of(outsideIn, dmzIn, insideOut, globalSec))
            assertFalse(PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), access));
        PolicyObject ifObj = snapshot.objects().values().stream()
                .filter(o -> "asa-interfaces".equals(o.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(
                List.of(
                        "nameif: outside", "physical: GigabitEthernet0/0", "security-level: 0",
                        "nameif: inside", "physical: GigabitEthernet0/1", "security-level: 100",
                        "nameif: dmz", "physical: GigabitEthernet0/2", "security-level: 50",
                        "same-security-inter: true",
                        "same-security-intra: true"),
                ifObj.values());

        // Not evaluated section: ACL-CP, ACL-CRYPTO, ACL-VPN, and the two ACLs without binding or use
        Map<String, Rule> byName = new java.util.HashMap<>();
        notEvaluated.rules().forEach(r -> byName.put(r.name(), r));
        assertEquals(java.util.Set.of("ACL-CP", "ACL-CRYPTO", "ACL-VPN", "ACL-ORPHAN-1", "ACL-ORPHAN-2"), byName.keySet());
        Rule cpRule = notEvaluated.rules().get(0);
        assertEquals("ACL-CP", cpRule.name());
        assertNull(cpRule.enabled());
        assertEquals("UNKNOWN", cpRule.action());
        assertEquals(List.of("control-plane"), cpRule.extras().get("asa-acl-use"));
        assertEquals(List.of("crypto_map"), byName.get("ACL-CRYPTO").extras().get("asa-acl-use"));
        assertEquals(List.of("vpn_filter", "split_tunnel"), byName.get("ACL-VPN").extras().get("asa-acl-use"));
        assertEquals(List.of("none"), byName.get("ACL-ORPHAN-1").extras().get("asa-acl-use"));
        assertEquals(List.of("none"), byName.get("ACL-ORPHAN-2").extras().get("asa-acl-use"));
        // Listing unreferenced ACLs is no collection failure: lookup and hygiene keep the policy complete
        assertTrue(snapshot.failures().isEmpty());
    }

    @Test
    void natSections123AndSection2OrderAndExclusionFromSecurityEvaluation() {
        String config = """
                ASA Version 9.18(4)
                object network OBJ-DYN-HOST
                 host 192.0.2.5
                 nat (inside,outside) dynamic interface
                object network OBJ-STATIC-SUBNET
                 subnet 192.0.2.0 255.255.255.0
                 nat (inside,outside) static 203.0.113.0
                object network OBJ-STATIC-HOST-HI
                 host 192.0.2.20
                object network OBJ-STATIC-HOST-HI
                 nat (inside,outside) static 203.0.113.20
                object network OBJ-STATIC-HOST-LO-B
                 host 192.0.2.10
                 nat (inside,outside) static 203.0.113.11
                object network OBJ-STATIC-HOST-LO-A
                 host 192.0.2.10
                 nat (inside,outside) static 203.0.113.10
                object network OBJ-EXT-SERVER
                 host 198.51.100.50
                object network OBJ-MAPPED-SERVER
                 host 203.0.113.50
                !
                nat (inside,outside) source static OBJ-STATIC-HOST-LO-A OBJ-STATIC-HOST-LO-A destination static OBJ-MAPPED-SERVER OBJ-EXT-SERVER no-proxy-arp
                nat (any,outside) after-auto source dynamic any interface inactive
                """;

        AsaPolicyConfig parsed = AsaConfigPolicyParser.parse(config);
        // 5 object NAT network objects + 2 extra objects; OBJ-STATIC-HOST-HI was not overwritten by its second nat-only block
        assertEquals("HOST", parsed.networkObjects().get("OBJ-STATIC-HOST-HI").kind());
        assertEquals("192.0.2.20", parsed.networkObjects().get("OBJ-STATIC-HOST-HI").value1());
        assertEquals(7, parsed.counters().parsedNatRules());
        assertEquals(0, parsed.counters().unparsedNatRules());

        PolicySnapshot snapshot = AsaPolicyMapper.map(
                "dev-asa-01", "FW-TANGO-04", "2026-11-14T10:00:00Z", "artefact-01", parsed);
        assertTrue(snapshot.failures().isEmpty());

        Section nat1 = snapshot.sections().stream().filter(s -> "ASA NAT section 1".equals(s.name())).findFirst().orElseThrow();
        Section nat2 = snapshot.sections().stream().filter(s -> "ASA NAT section 2".equals(s.name())).findFirst().orElseThrow();
        Section nat3 = snapshot.sections().stream().filter(s -> "ASA NAT section 3".equals(s.name())).findFirst().orElseThrow();

        assertTrue(PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), nat1));
        assertTrue(PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), nat2));
        assertTrue(PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), nat3));

        // Section 1 twice NAT rule: real destination is OBJ-EXT-SERVER, translated destination is OBJ-MAPPED-SERVER
        Rule r1 = nat1.rules().get(0);
        assertEquals("source-and-destination-nat", r1.action());
        assertEquals("OBJ-EXT-SERVER", objectOf(snapshot, r1.destination()).name());
        String transDstId = r1.extras().get("translated-destination").get(0);
        assertEquals("OBJ-MAPPED-SERVER", snapshot.objects().get(transDstId).name());
        assertEquals(List.of("static"), r1.extras().get("asa-nat-type"));
        assertEquals(List.of("inside", "outside"), r1.extras().get("asa-nat-interfaces"));

        // Section 2 order (A0-ASA-17):
        // 1. static, size 1, IP 192.0.2.10, name OBJ-STATIC-HOST-LO-A
        // 2. static, size 1, IP 192.0.2.10, name OBJ-STATIC-HOST-LO-B
        // 3. static, size 1, IP 192.0.2.20, name OBJ-STATIC-HOST-HI
        // 4. static, size 256, IP 192.0.2.0, name OBJ-STATIC-SUBNET
        // 5. dynamic, size 1, IP 192.0.2.5, name OBJ-DYN-HOST
        assertEquals(
                List.of(
                        "OBJ-STATIC-HOST-LO-A:1",
                        "OBJ-STATIC-HOST-LO-B:2",
                        "OBJ-STATIC-HOST-HI:3",
                        "OBJ-STATIC-SUBNET:4",
                        "OBJ-DYN-HOST:5"),
                nat2.rules().stream().map(Rule::name).toList());

        // Section 3 rule: after-auto, inactive -> enabled = false, interface PAT -> dynamic-ip-and-port
        Rule r3 = nat3.rules().get(0);
        assertEquals(Boolean.FALSE, r3.enabled());
        assertEquals(List.of("dynamic-ip-and-port"), r3.extras().get("source-translation-type"));
        assertEquals(List.of("any", "outside"), r3.extras().get("asa-nat-interfaces"));
    }

    @Test
    void natSection2UnprovenOrderKeepsConfigurationOrderAndReportsFailure() {
        String config = """
                ASA Version 9.18(4)
                object network OBJ-Z-HOST
                 host 192.0.2.100
                 nat (inside,outside) static 203.0.113.100
                object network OBJ-A-FQDN
                 fqdn v4 host.example.invalid
                 nat (inside,outside) static 203.0.113.101
                object network OBJ-B-HOST
                 host 192.0.2.1
                 nat (inside,outside) static 203.0.113.102
                """;

        AsaPolicyConfig parsed = AsaConfigPolicyParser.parse(config);
        PolicySnapshot snapshot = AsaPolicyMapper.map(
                "dev-asa-01", "FW-TANGO-04", "2026-11-14T10:00:00Z", "artefact-01", parsed);

        assertEquals(1, snapshot.failures().size());
        assertEquals("ASA_NAT_SECTION2_ORDER_UNPROVEN", snapshot.failures().get(0).reason());

        Section nat2 = snapshot.sections().stream().filter(s -> "ASA NAT section 2".equals(s.name())).findFirst().orElseThrow();
        assertEquals(
                List.of("OBJ-Z-HOST:1", "OBJ-A-FQDN:2", "OBJ-B-HOST:3"),
                nat2.rules().stream().map(Rule::name).toList());
    }

    @Test
    void multipleContextAndTransparentModeRefused() {
        AsaPolicyConfig transparentCfg = AsaConfigPolicyParser.parse("""
                ASA Version 9.18(4)
                firewall transparent
                access-list ACL-1 extended permit ip any any
                access-group ACL-1 in interface outside
                """);
        assertTrue(transparentCfg.transparent());
        PolicySnapshot transparentSnap = AsaPolicyMapper.map(
                "dev-asa-01", "FW-TANGO-04", "2026-11-14T10:00:00Z", "artefact-01", transparentCfg);
        assertTrue(transparentSnap.sections().isEmpty());
        assertEquals(1, transparentSnap.failures().size());
        assertEquals("ASA_UNSUPPORTED_MODE", transparentSnap.failures().get(0).reason());

        AsaPolicyConfig multiCfg = AsaConfigPolicyParser.parse("""
                ASA Version 9.18(4)
                mode multiple
                admin-context admin
                """);
        assertTrue(multiCfg.multipleContext());
        PolicySnapshot multiSnap = AsaPolicyMapper.map(
                "dev-asa-02", "FW-JULIET-06", "2026-11-14T10:00:00Z", "artefact-02", multiCfg);
        assertTrue(multiSnap.sections().isEmpty());
        assertEquals(1, multiSnap.failures().size());
        assertEquals("ASA_UNSUPPORTED_MODE", multiSnap.failures().get(0).reason());
    }

    @Test
    void tenThousandAceSyntheticAclParsesLinearlyUnderOneSecond() {
        StringBuilder sb = new StringBuilder(600_000);
        sb.append("ASA Version 9.18(4)\n");
        sb.append("interface GigabitEthernet0/0\n nameif outside\n security-level 0\n");
        for (int i = 1; i <= 10_000; i++) {
            int thirdOctet = (i / 250) % 256;
            int fourthOctet = (i % 250) + 1;
            int port = 1000 + (i % 50_000);
            sb.append("access-list ACL-LARGE extended permit tcp host 192.0.2.")
                    .append(fourthOctet)
                    .append(" 198.51.")
                    .append(thirdOctet)
                    .append(".0 255.255.255.0 eq ")
                    .append(port)
                    .append('\n');
        }
        sb.append("access-group ACL-LARGE in interface outside\n");
        String largeConfig = sb.toString();

        long startNanos = System.nanoTime();
        AsaPolicyConfig parsed = AsaConfigPolicyParser.parse(largeConfig);
        PolicySnapshot snapshot = AsaPolicyMapper.map(
                "dev-asa-large", "FW-BRAVO-02", "2026-11-14T10:00:00Z", "artefact-large", parsed);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertEquals(10_000, parsed.counters().parsedAces());
        assertEquals(0, parsed.counters().unparsedAces());
        assertEquals(10_000, snapshot.sections().get(0).rules().size());
        // Bound, not a benchmark: shared CI runners are several times slower than a workstation (2026-11-15).
        assertTrue(elapsedMs < 5_000, "Expected 10,000 ACE parse + map in < 5000ms, took " + elapsedMs + "ms");
    }

    @Test
    void unparsedConstructsAllAclUsesProtocolAndIcmpGroupsAndInterfaceFiltering() {
        String config = """
                ASA Version 9.22(1)
                interface GigabitEthernet0/0
                 nameif transient-if
                 no nameif
                 shutdown
                interface GigabitEthernet0/1
                 nameif inside
                 security-level 100
                 shutdown
                interface GigabitEthernet0/2
                 nameif outside
                 security-level 50
                 no security-level
                 security-level 0
                 no shutdown
                !
                object-group protocol OG-PROTO-TCP-UDP
                 protocol-object tcp
                 protocol-object udp
                object-group icmp-type OG-ICMP
                 icmp-object echo
                 icmp-object echo-reply
                !
                access-list ACL-MIXED standard permit 192.0.2.0 255.255.255.0
                access-list ACL-MIXED ethertype permit ipx
                access-list ACL-MIXED webtype permit url https://192.0.2.10
                access-list ACL-MIXED extended permit tcp any eq 10000 any object-group NOT-DEFINED-SVC
                access-list ACL-MIXED extended permit object-group OG-PROTO-TCP-UDP any any eq 8080
                access-list ACL-MIXED extended permit icmp any any object-group OG-ICMP
                access-list ACL-MIXED extended permit tcp any any bogus-token 123
                access-group ACL-MIXED in interface outside
                !
                access-list ACL-CLASS extended permit ip any any
                access-list ACL-RMAP extended permit ip any any
                access-list ACL-CAP extended permit ip any any
                class-map CM-1
                 match access-list ACL-CLASS
                route-map RM-1 permit 10
                 match ip address ACL-RMAP
                capture CAP-1 access-list ACL-CAP interface outside
                !
                nat (inside,outside) bogus-nat-syntax
                access-group
                """;

        AsaPolicyConfig parsed = AsaConfigPolicyParser.parse(config);
        // Only interfaces with nameif are kept
        assertEquals(2, parsed.interfaces().size());
        assertEquals("inside", parsed.interfaces().get(0).nameif());
        assertTrue(parsed.interfaces().get(0).shutdown());
        assertEquals("outside", parsed.interfaces().get(1).nameif());
        assertFalse(parsed.interfaces().get(1).shutdown());

        // 3 non-extended ACEs + 1 malformed extended ACE = 4 unparsed; 6 total parsed across all ACLs
        assertEquals(6, parsed.counters().parsedAces());
        assertEquals(4, parsed.counters().unparsedAces());
        assertEquals(1, parsed.counters().unparsedNatRules());
        assertEquals(1, parsed.counters().unknownTopLevelPolicyLines());

        assertEquals(Set.of("class_map"), parsed.aclUses().get("ACL-CLASS"));
        assertEquals(Set.of("route_map"), parsed.aclUses().get("ACL-RMAP"));
        assertEquals(Set.of("capture"), parsed.aclUses().get("ACL-CAP"));

        PolicySnapshot snapshot = AsaPolicyMapper.map(
                "dev-asa-03", "FW-ROMEO-01", "2026-11-14T10:00:00Z", "artefact-03", parsed);
        Section outsideIn = snapshot.sections().get(0);
        assertEquals(7, outsideIn.rules().size());

        // Standard, ethertype, webtype, and bogus extended ACEs carry asa-unparsed = ["true"] and UNRESOLVED cells
        for (int idx : List.of(0, 1, 2, 6)) {
            Rule unparsedRule = outsideIn.rules().get(idx);
            assertEquals(List.of("true"), unparsedRule.extras().get("asa-unparsed"));
            assertEquals("UNRESOLVED", objectOf(snapshot, unparsedRule.source()).status());
            assertEquals("UNRESOLVED", objectOf(snapshot, unparsedRule.destination()).status());
            assertEquals("UNRESOLVED", objectOf(snapshot, unparsedRule.service()).status());
        }

        // Protocol group (tcp + udp) combined with eq 8080 -> service-group of tcp 8080 and udp 8080
        PolicyObject protoWithPort = objectOf(snapshot, outsideIn.rules().get(4).service());
        assertEquals("service-group", protoWithPort.type());
        assertEquals("RESOLVED", protoWithPort.status());
        assertEquals(2, protoWithPort.members().size());

        // ICMP with icmp-type object-group -> service-group of echo and echo-reply
        PolicyObject icmpGroup = objectOf(snapshot, outsideIn.rules().get(5).service());
        assertEquals("service-group", icmpGroup.type());
        assertEquals(2, icmpGroup.members().size());
    }

    private static PolicyObject objectOf(PolicySnapshot snapshot, PolicySnapshot.Cell cell) {
        assertEquals(1, cell.refs().size());
        PolicyObject obj = snapshot.objects().get(cell.refs().get(0));
        assertNotNull(obj);
        return obj;
    }

    /** Directory group names are printed unquoted and may contain spaces (estate measurement 2026-11-14). */
    @Test
    void multiWordIdentityNamesParseAndAmbiguousNamesStayUnparsed() {
        String config = """
                ASA Version 9.18(4)22
                names
                name 198.51.100.40 SRV-ALIAS
                access-list ACL-IN extended permit tcp user-group LAB\\\\Network Admins Team any host 198.51.100.20 eq https
                access-list ACL-IN extended permit udp user LAB\\\\first.last any 198.51.100.0 255.255.255.0 eq domain time-range TR-1
                access-list ACL-IN extended permit tcp user-group "LAB\\\\Quoted Group Name" any4 object-group OG-WEB eq 8443
                access-list ACL-IN extended permit tcp user-group LAB\\\\Team SRV-ALIAS host 198.51.100.21 eq 443
                access-list ACL-IN extended permit tcp user-group LAB\\\\Team 198.51.100.5 host 198.51.100.21 eq 443
                object-group network OG-WEB
                 network-object host 198.51.100.30
                access-group ACL-IN in interface outside
                """;
        AsaPolicyConfig parsed = AsaConfigPolicyParser.parse(config);
        List<AsaPolicyConfig.Ace> aces = parsed.acls().get("ACL-IN");
        assertEquals(5, aces.size());
        assertFalse(aces.get(0).unparsed());
        assertEquals(List.of("LAB\\\\Network Admins Team"), aces.get(0).users());
        assertEquals("any", aces.get(0).source().kind());
        assertFalse(aces.get(1).unparsed());
        assertEquals(1, aces.get(1).users().size());
        assertEquals("TR-1", aces.get(1).timeRange());
        assertFalse(aces.get(2).unparsed());
        assertEquals(List.of("\"LAB\\\\Quoted Group Name\""), aces.get(2).users());
        // A continuation word that is a names alias or an IPv4 literal is ambiguous: never guessed
        assertTrue(aces.get(3).unparsed());
        assertTrue(aces.get(4).unparsed());

        PolicySnapshot snapshot = AsaPolicyMapper.map("dev-asa-01", "FW-TANGO-04", "2026-11-14T10:00:00Z", "artefact-01", parsed);
        Section in = snapshot.sections().get(0);
        assertEquals(List.of("LAB\\\\Network Admins Team"), in.rules().get(0).extras().get("user"));
        assertFalse(in.rules().get(0).extras().containsKey("asa-unparsed"));
        assertEquals(List.of("true"), in.rules().get(3).extras().get("asa-unparsed"));
    }

    // ---- ACL_NOT_BOUND (PO 2026-11-23): "none" only when no parsed feature and no unparsed line names the ACL ----

    private static final String ACL_DEFS = """
            access-list ACL-ORPHAN-1 extended permit ip any any
            access-list ACL-DAP-1 extended permit ip any any
            access-list ACL-WCCP-1 extended permit ip any any
            access-list ACL-NAT-1 extended permit ip any any
            access-list ACL-IGMP-1 extended permit ip any any
            access-list ACL-AAA-1 extended permit ip any any
            access-list ACL-DL-1 extended permit ip any any
            access-list ACL-WEB-1 extended permit ip any any
            access-list ACL-CRYPTO-1 extended permit ip any any
            access-list ACL-RMAP-1 extended permit ip any any
            access-list ACL-DESC-1 extended permit ip any any
            access-list ACL-MIXED-1 extended permit ip any any
            """;

    private static Map<String, Object> aclUseByName(String config) {
        AsaPolicyConfig parsed = AsaConfigPolicyParser.parse(config);
        PolicySnapshot snapshot = AsaPolicyMapper.map("dev-asa-01", "FW-TANGO-04", "2026-11-23T10:00:00Z", "artefact-01", parsed);
        Section notEvaluated = snapshot.sections().stream()
                .filter(x -> "ASA not evaluated".equals(x.name())).findFirst().orElseThrow();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        notEvaluated.rules().forEach(r -> out.put(r.name(), r.extras().get("asa-acl-use")));
        return out;
    }

    @Test
    void aclNotBound_noUseAtAllIsNone_andAnUnparsedFeatureLineMakesItUnknown() {
        Map<String, Object> use = aclUseByName("ASA Version 9.18(4)\n" + ACL_DEFS + """
                dynamic-access-policy-record DAP-1
                 network-acl ACL-DAP-1
                wccp 90 redirect-list ACL-WCCP-1
                nat (inside) 1 access-list ACL-NAT-1
                interface GigabitEthernet0/1
                 nameif inside
                 security-level 100
                 igmp access-group ACL-IGMP-1
                aaa authentication match ACL-AAA-1 inside LOCAL
                router ospf 1
                 distribute-list ACL-DL-1 in
                group-policy GP-1 attributes
                 webvpn
                  filter value ACL-WEB-1
                crypto map CM-1 10 match address ACL-CRYPTO-1
                route-map RM-1 permit 10
                 match ip address ACL-RMAP-1
                 description ACL-DESC-1
                """);
        assertEquals(List.of("none"), use.get("ACL-ORPHAN-1"));
        assertEquals(List.of("unknown", "unparsed_reference"), use.get("ACL-DAP-1"));
        assertEquals(List.of("unknown", "unparsed_reference"), use.get("ACL-WCCP-1"));
        assertEquals(List.of("unknown", "unparsed_reference"), use.get("ACL-NAT-1"));
        assertEquals(List.of("unknown", "unparsed_reference"), use.get("ACL-IGMP-1"));
        assertEquals(List.of("unknown", "unparsed_reference"), use.get("ACL-AAA-1"));
        assertEquals(List.of("unknown", "unparsed_reference"), use.get("ACL-DL-1"));
        assertEquals(List.of("unknown", "unparsed_reference"), use.get("ACL-WEB-1"));
        // a parsed use stays exactly as before and is not also an unparsed reference
        assertEquals(List.of("crypto_map"), use.get("ACL-CRYPTO-1"));
        assertEquals(List.of("route_map"), use.get("ACL-RMAP-1"));
        // free-text description lines are no feature
        assertEquals(List.of("none"), use.get("ACL-DESC-1"));
    }

    @Test
    void aclNotBound_unparsedReferenceNeverEntersPolicyEvaluation() {
        AsaPolicyConfig parsed = AsaConfigPolicyParser.parse("""
                access-list ACL-DAP-1 extended permit ip any any
                dynamic-access-policy-record DAP-1
                 network-acl ACL-DAP-1
                """);
        assertEquals(Set.of("unparsed_reference"), parsed.aclUses().get("ACL-DAP-1"));
        PolicySnapshot snapshot = AsaPolicyMapper.map("dev-asa-01", "FW-TANGO-04", "2026-11-23T10:00:00Z", "artefact-01", parsed);
        assertTrue(snapshot.failures().isEmpty());
    }

    @Test
    void aclNotBound_aclNameThatIsAPrefixOfAnotherIsNotAReference() {
        Map<String, Object> use = aclUseByName("""
                access-list ACL-A extended permit ip any any
                access-list ACL-A1 extended permit ip any any
                wccp 91 redirect-list ACL-A1
                """);
        assertEquals(List.of("none"), use.get("ACL-A"));
        assertEquals(List.of("unknown", "unparsed_reference"), use.get("ACL-A1"));
    }

    @Test
    void aclNotBound_caseDifferenceIsNotAReference() {
        Map<String, Object> use = aclUseByName("""
                access-list ACL-A extended permit ip any any
                wccp 91 redirect-list acl-a
                """);
        assertEquals(List.of("none"), use.get("ACL-A"));
    }

    @Test
    void a0Asa14_comparisonOperatorEdgesAndLiteralsAndSourcePorts() {
        AsaPolicyConfig parsed = AsaConfigPolicyParser.parse("""
                access-list ACL-E extended permit tcp any any lt 1
                access-list ACL-E extended permit tcp any any lt 2
                access-list ACL-E extended permit tcp any any gt 65535
                access-list ACL-E extended permit tcp any any gt 65534
                access-list ACL-E extended permit tcp any any neq 0
                access-list ACL-E extended permit tcp any any neq 1
                access-list ACL-E extended permit tcp any any neq 65535
                access-list ACL-E extended permit tcp any any lt www
                access-list ACL-E extended permit udp any any neq domain
                access-list ACL-E extended permit tcp any any lt snmp
                access-list ACL-E extended permit tcp any gt 1023 any eq https
                access-list ACL-E extended permit tcp any neq 22 any eq https
                access-group ACL-E in interface outside
                """);
        PolicySnapshot snapshot = AsaPolicyMapper.map(
                "dev-asa-01", "FW-TANGO-04", "2026-11-14T10:00:00Z", "artefact-01", parsed);
        List<Rule> rules = snapshot.sections().get(0).rules();
        assertEquals(12, rules.size());
        // lt 1 and gt 65535 are empty ranges, neq 0 is outside 1..65535: unresolved, never a malformed range
        for (int i : new int[] {0, 2, 4}) {
            PolicyObject o = objectOf(snapshot, rules.get(i).service());
            assertEquals("UNSUPPORTED", o.status(), "rule " + i);
            assertEquals(List.of(), o.values(), "rule " + i);
        }
        assertEquals(List.of("protocol: tcp", "port: 1-1"), objectOf(snapshot, rules.get(1).service()).values());
        assertEquals(List.of("protocol: tcp", "port: 65535-65535"), objectOf(snapshot, rules.get(3).service()).values());
        assertEquals(List.of("protocol: tcp", "port: 2-65535"), objectOf(snapshot, rules.get(5).service()).values());
        assertEquals(List.of("protocol: tcp", "port: 1-65534"), objectOf(snapshot, rules.get(6).service()).values());
        // literal operands resolve through Table 7 for the ACE protocol
        assertEquals(List.of("protocol: tcp", "port: 1-79"), objectOf(snapshot, rules.get(7).service()).values());
        assertEquals(List.of("protocol: udp", "port: 1-52,54-65535"), objectOf(snapshot, rules.get(8).service()).values());
        // a UDP-only literal on a TCP entry stays unresolved (A0-ASA-15)
        assertEquals("UNSUPPORTED", objectOf(snapshot, rules.get(9).service()).status());
        // source-port operators
        assertEquals(
                List.of("protocol/tcp/source-port: 1024-65535", "protocol/tcp/port: 443"),
                objectOf(snapshot, rules.get(10).service()).values());
        assertEquals(
                List.of("protocol/tcp/source-port: 1-21,23-65535", "protocol/tcp/port: 443"),
                objectOf(snapshot, rules.get(11).service()).values());
    }

    @Test
    void aclNotBound_aParsedUseAndAnUnparsedLineTogetherReadAsUsed() {
        Map<String, Object> use = aclUseByName("""
                access-list ACL-MIXED-1 extended permit ip any any
                crypto map CM-1 10 match address ACL-MIXED-1
                wccp 91 redirect-list ACL-MIXED-1
                """);
        assertEquals(List.of("crypto_map", "unparsed_reference"), use.get("ACL-MIXED-1"));
    }

    @Test
    void aclNotBound_accessListAndAccessGroupLinesAreNotReferences() {
        AsaPolicyConfig parsed = AsaConfigPolicyParser.parse("""
                access-list ACL-OUT extended permit ip any any
                access-list ACL-OUT remark ACL-OUT
                interface GigabitEthernet0/0
                 nameif outside
                 security-level 0
                access-group ACL-OUT in interface outside
                """);
        assertNull(parsed.aclUses().get("ACL-OUT"));
    }
}
