package com.securityexpert.nexus.ui2.policy.asa;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Cell;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.CollectionFailure;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Metadata;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Section;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Target;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.AccessGroup;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.Ace;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.AddressSpec;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.GroupChild;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.Interface;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.NatRule;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.NetworkObject;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.ObjectGroup;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.PortOp;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.ProtocolSpec;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.ServiceObject;

import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.ref;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps a parsed {@link AsaPolicyConfig} into a normalized {@link PolicySnapshot}.
 * Never logs configuration names, addresses, or rule text (AGENTS.md raw-evidence and masking laws).
 */
public final class AsaPolicyMapper {

    public static PolicySnapshot map(
            String deviceId,
            String deviceName,
            String collectedAt,
            String artefactRef,
            AsaPolicyConfig c) {
        String snapshotId = ref("asa", deviceId, "single");
        Metadata metadata = new Metadata(
                snapshotId,
                deviceId,
                deviceName,
                "ASA",
                ref(deviceId, "single"),
                "single",
                "ASA access rules",
                collectedAt,
                artefactRef,
                List.of(new Target(deviceId, deviceName, "", "IN_SYNC")));

        // A0-ASA-10 (transparent mode) and A0-ASA-19 (multiple-context mode) are refused.
        if (c.multipleContext() || c.transparent()) {
            String modeLayer = c.multipleContext() ? "multiple-context" : "transparent";
            return new PolicySnapshot(
                    metadata,
                    List.of(),
                    Map.of(),
                    List.of(new CollectionFailure(snapshotId, "ASA_UNSUPPORTED_MODE", modeLayer, 0)));
        }

        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        List<CollectionFailure> failures = new ArrayList<>();

        // Pre-populate defined network objects, service objects, and object groups in configuration order.
        for (String name : c.networkObjects().keySet()) {
            ensureNetworkObject(snapshotId, name, c, objects);
        }
        for (String name : c.serviceObjects().keySet()) {
            ensureServiceObject(snapshotId, name, c, objects);
        }
        for (var entry : c.objectGroups().entrySet()) {
            String gt = entry.getValue().groupType();
            if ("network".equals(gt)) {
                ensureNetworkObjectGroup(snapshotId, entry.getKey(), c, objects, new HashSet<>());
            } else if ("service".equals(gt) || "protocol".equals(gt) || "icmp-type".equals(gt)) {
                ensureServiceObjectGroup(snapshotId, entry.getKey(), c, objects, new HashSet<>());
            }
        }

        List<Section> sections = new ArrayList<>();

        // 1. One section per 'access-group ... in interface IF' (excluding control-plane, A0-ASA-2/9)
        for (AccessGroup ag : c.accessGroups()) {
            if (!ag.controlPlane() && "in".equals(ag.direction()) && ag.interfaceName() != null) {
                String iface = ag.interfaceName();
                String sectionId = ref(snapshotId, "in", iface);
                String sectionKey = "in:" + iface;
                List<Rule> rules = mapAclRules(
                        snapshotId, sectionKey, ag.aclName(), List.of(iface), List.of("any"), "in", c, objects);
                sections.add(new Section(sectionId, iface + " in", "ASA access-group", null, rules));
            }
        }

        // 2. One section per 'access-group ... out interface IF' (excluding control-plane, A0-ASA-2/9)
        for (AccessGroup ag : c.accessGroups()) {
            if (!ag.controlPlane() && "out".equals(ag.direction()) && ag.interfaceName() != null) {
                String iface = ag.interfaceName();
                String sectionId = ref(snapshotId, "out", iface);
                String sectionKey = "out:" + iface;
                List<Rule> rules = mapAclRules(
                        snapshotId, sectionKey, ag.aclName(), List.of("any"), List.of(iface), "out", c, objects);
                sections.add(new Section(sectionId, iface + " out", "ASA access-group", null, rules));
            }
        }

        // 3. The 'global' binding (at most one, A0-ASA-2/3)
        for (AccessGroup ag : c.accessGroups()) {
            if (!ag.controlPlane() && "global".equals(ag.direction())) {
                String sectionId = ref(snapshotId, "global");
                List<Rule> rules = mapAclRules(
                        snapshotId, "global", ag.aclName(), List.of("any"), List.of("any"), "global", c, objects);
                sections.add(new Section(sectionId, "global", "ASA access-group", null, rules));
                break;
            }
        }

        // 4. "ASA security-level default" (source "ASA implicit", A0-ASA-4/5; decision record item 4)
        String ifObjId = ref(snapshotId, "asa-interfaces", "interfaces");
        List<String> ifValues = new ArrayList<>();
        boolean allLevelsKnown = true;
        for (Interface iface : c.interfaces()) {
            ifValues.add("nameif: " + iface.nameif());
            // The configured interface name (Trace maps the inventory's interface names to nameif with it).
            if (iface.physical() != null) ifValues.add("physical: " + iface.physical());
            if (iface.securityLevel() == null) {
                allLevelsKnown = false;
                ifValues.add("security-level: UNKNOWN");
            } else {
                ifValues.add("security-level: " + iface.securityLevel());
            }
        }
        ifValues.add("same-security-inter: " + c.sameSecurityInter());
        ifValues.add("same-security-intra: " + c.sameSecurityIntra());
        objects.put(ifObjId, new PolicyObject(
                ifObjId,
                "asa-interfaces",
                "asa-interfaces",
                List.of(),
                ifValues,
                allLevelsKnown ? "RESOLVED" : "UNKNOWN"));
        sections.add(new Section(
                ref(snapshotId, "implicit", "security-level-default"),
                "ASA security-level default",
                "ASA implicit",
                null,
                List.of()));

        // 5. "ASA NAT section 1", "ASA NAT section 2", "ASA NAT section 3" (source "ASA NAT", A0-ASA-17)
        List<NatRule> natSec1 = new ArrayList<>();
        List<NatRule> natSec2 = new ArrayList<>();
        List<NatRule> natSec3 = new ArrayList<>();
        for (NatRule nr : c.natRules()) {
            if (nr.section() == 1) natSec1.add(nr);
            else if (nr.section() == 2) natSec2.add(nr);
            else if (nr.section() == 3) natSec3.add(nr);
        }
        List<NatRule> orderedSec2 = orderNatSection2(snapshotId, natSec2, c, failures);
        addNatSection(snapshotId, 1, natSec1, c, objects, sections);
        addNatSection(snapshotId, 2, orderedSec2, c, objects, sections);
        addNatSection(snapshotId, 3, natSec3, c, objects, sections);

        // 6. "ASA not evaluated" (source "ASA not evaluated", A0-ASA-9): ACLs used elsewhere, and ACLs with no use found ("none")
        Set<String> transitBoundAcls = new HashSet<>();
        Set<String> cpBoundAcls = new LinkedHashSet<>();
        for (AccessGroup ag : c.accessGroups()) {
            if (ag.controlPlane()) {
                cpBoundAcls.add(ag.aclName());
            } else {
                transitBoundAcls.add(ag.aclName());
            }
        }

        List<Rule> notEvaluatedRules = new ArrayList<>();
        Set<String> emittedNotEvaluated = new HashSet<>();
        for (String cpAcl : cpBoundAcls) {
            if (emittedNotEvaluated.add(cpAcl)) {
                Set<String> uses = new LinkedHashSet<>();
                uses.add("control-plane");
                uses.addAll(c.aclUses().getOrDefault(cpAcl, Set.of()));
                int num = notEvaluatedRules.size() + 1;
                Cell emptyCell = new Cell(List.of(), false);
                notEvaluatedRules.add(new Rule(
                        ref(snapshotId, "not-evaluated", cpAcl, String.valueOf(num)),
                        null,
                        num,
                        cpAcl,
                        null,
                        emptyCell,
                        emptyCell,
                        emptyCell,
                        emptyCell,
                        "UNKNOWN",
                        "",
                        "",
                        Map.of("asa-acl-use", List.copyOf(uses))));
            }
        }

        Set<String> candidateUnboundAcls = new LinkedHashSet<>(c.acls().keySet());
        candidateUnboundAcls.addAll(c.aclUses().keySet());
        for (String aclName : candidateUnboundAcls) {
            if (transitBoundAcls.contains(aclName) || emittedNotEvaluated.contains(aclName)) {
                continue;
            }
            // An ACL with no access-group binding and no use found is listed too, with the use "none": it is not
            // enforced as an access rule (A0-ASA-2) and hygiene reports it. It is no collection failure: every rule is
            // in the snapshot, and any failure would make lookup and hygiene treat the whole policy as incomplete.
            // ACL_NOT_BOUND (PO 2026-11-23): "none" only when no parsed feature uses it AND no configuration line the
            // parser does not read names it; an ACL named only by such a line is "unknown" (never "unused", never OK).
            Set<String> found = c.aclUses().getOrDefault(aclName, Set.of());
            Set<String> uses;
            if (found.isEmpty()) {
                uses = Set.of("none");
            } else if (found.size() == 1 && found.contains(AsaConfigPolicyParser.UNPARSED_REFERENCE)) {
                uses = new LinkedHashSet<>();
                uses.add("unknown");
                uses.addAll(found);
            } else {
                uses = found;
            }
            {
                emittedNotEvaluated.add(aclName);
                int num = notEvaluatedRules.size() + 1;
                Cell emptyCell = new Cell(List.of(), false);
                notEvaluatedRules.add(new Rule(
                        ref(snapshotId, "not-evaluated", aclName, String.valueOf(num)),
                        null,
                        num,
                        aclName,
                        null,
                        emptyCell,
                        emptyCell,
                        emptyCell,
                        emptyCell,
                        "UNKNOWN",
                        "",
                        "",
                        Map.of("asa-acl-use", List.copyOf(uses))));
            }
        }

        if (!notEvaluatedRules.isEmpty()) {
            sections.add(new Section(
                    ref(snapshotId, "not-evaluated"),
                    "ASA not evaluated",
                    "ASA not evaluated",
                    null,
                    notEvaluatedRules));
        }

        return new PolicySnapshot(metadata, sections, objects, failures);
    }

    private static List<Rule> mapAclRules(
            String snapshotId,
            String sectionKey,
            String aclName,
            List<String> from,
            List<String> to,
            String direction,
            AsaPolicyConfig c,
            Map<String, PolicyObject> objects) {
        List<Ace> aces = c.acls().getOrDefault(aclName, List.of());
        List<Rule> rules = new ArrayList<>(aces.size());
        Cell emptyApp = new Cell(List.of(), false);
        for (int i = 0; i < aces.size(); i++) {
            Ace ace = aces.get(i);
            int number = i + 1;
            String ruleId = ref(snapshotId, sectionKey, aclName, String.valueOf(number));
            String ruleName = aclName + ":" + number;
            // A0-ASA-8: inactive disables the ACE.
            Boolean enabled = !ace.inactive();
            String action = ace.action() == null ? "UNKNOWN" : ace.action();
            String log = ace.log() ? "log" : "";
            String comment = ace.remarks().isEmpty() ? "" : String.join("\n", ace.remarks());

            Map<String, List<String>> extras = new LinkedHashMap<>();
            extras.put("from", from);
            extras.put("to", to);
            extras.put("asa-direction", List.of(direction));
            extras.put("asa-acl", List.of(aclName));
            if (ace.timeRange() != null) {
                // A0-ASA-8: time-ranged ACEs carry 'time' constraint.
                extras.put("time", List.of(ace.timeRange()));
            }
            if (!ace.users().isEmpty()) {
                // A0-ASA-16: identity firewall criteria.
                extras.put("user", ace.users());
            }
            if (!ace.securityGroups().isEmpty()) {
                // A0-ASA-16: TrustSec security group criteria.
                extras.put("security-group", ace.securityGroups());
            }

            Cell srcCell;
            Cell dstCell;
            Cell svcCell;
            if (ace.unparsed()) {
                extras.put("asa-unparsed", List.of("true"));
                String unresolvedId = ensureUnresolvedObject(snapshotId, "unparsed", ruleName, objects);
                Cell unresolvedCell = new Cell(List.of(unresolvedId), false);
                srcCell = unresolvedCell;
                dstCell = unresolvedCell;
                svcCell = unresolvedCell;
            } else {
                String srcId = resolveAddressSpec(snapshotId, ace.source(), c, objects);
                String dstId = resolveAddressSpec(snapshotId, ace.destination(), c, objects);
                String svcId = resolveAceService(snapshotId, ace, c, objects);
                srcCell = new Cell(List.of(srcId), false);
                dstCell = new Cell(List.of(dstId), false);
                svcCell = new Cell(List.of(svcId), false);
            }

            rules.add(new Rule(
                    ruleId,
                    null,
                    number,
                    ruleName,
                    enabled,
                    srcCell,
                    dstCell,
                    svcCell,
                    emptyApp,
                    action,
                    log,
                    comment,
                    extras));
        }
        return rules;
    }

    private static void addNatSection(
            String snapshotId,
            int sectionNum,
            List<NatRule> natRules,
            AsaPolicyConfig c,
            Map<String, PolicyObject> objects,
            List<Section> sections) {
        if (natRules.isEmpty()) return;
        String sectionId = ref(snapshotId, "nat", String.valueOf(sectionNum));
        String sectionName = "ASA NAT section " + sectionNum;
        List<Rule> rules = new ArrayList<>(natRules.size());
        Cell emptyApp = new Cell(List.of(), false);

        for (int i = 0; i < natRules.size(); i++) {
            NatRule nr = natRules.get(i);
            int number = i + 1;
            String ruleId = ref(snapshotId, "nat-" + sectionNum, String.valueOf(number));
            String ruleName = nr.objectName() != null ? nr.objectName() + ":" + number : "nat-" + sectionNum + ":" + number;

            Map<String, List<String>> extras = new LinkedHashMap<>();
            if (nr.realInterface() != null && nr.mappedInterface() != null) {
                extras.put("asa-nat-interfaces", List.of(nr.realInterface(), nr.mappedInterface()));
            }
            if (nr.unparsed()) {
                extras.put("asa-unparsed", List.of("true"));
                String unresolvedId = ensureUnresolvedObject(snapshotId, "unparsed-nat", ruleName, objects);
                Cell unresolvedCell = new Cell(List.of(unresolvedId), false);
                rules.add(new Rule(
                        ruleId,
                        null,
                        number,
                        ruleName,
                        null,
                        unresolvedCell,
                        unresolvedCell,
                        unresolvedCell,
                        emptyApp,
                        "UNKNOWN",
                        "",
                        "",
                        extras));
                continue;
            }

            extras.put("asa-nat-type", List.of(nr.natType()));
            String sourceTransType = "static".equals(nr.natType())
                    ? "static-ip"
                    : nr.interfacePat() ? "dynamic-ip-and-port" : "dynamic-ip";
            extras.put("source-translation-type", List.of(sourceTransType));

            String realSrcId = resolveNatAddressOperand(snapshotId, nr.realSource(), nr.realInterface(), c, objects);
            String mappedSrcId = resolveNatAddressOperand(snapshotId, nr.mappedSource(), nr.mappedInterface(), c, objects);
            extras.put("translated-source", List.of(mappedSrcId));

            String realDstId;
            if (nr.realDestination() != null) {
                realDstId = resolveNatAddressOperand(snapshotId, nr.realDestination(), nr.mappedInterface(), c, objects);
                String mappedDstId = resolveNatAddressOperand(snapshotId, nr.mappedDestination(), nr.realInterface(), c, objects);
                extras.put("translated-destination", List.of(mappedDstId));
            } else {
                realDstId = ensureAnyObject(snapshotId, objects);
            }

            String realSvcId;
            if (nr.realService() != null) {
                realSvcId = resolveNatServiceOperand(snapshotId, nr.serviceProtocol(), nr.realService(), c, objects);
                String mappedSvcId = resolveNatServiceOperand(snapshotId, nr.serviceProtocol(), nr.mappedService(), c, objects);
                extras.put("translated-service", List.of(mappedSvcId));
            } else {
                realSvcId = ensureAnyObject(snapshotId, objects);
            }

            String action = nr.realDestination() != null ? "source-and-destination-nat" : "source-nat";
            rules.add(new Rule(
                    ruleId,
                    null,
                    number,
                    ruleName,
                    !nr.inactive(),
                    new Cell(List.of(realSrcId), false),
                    new Cell(List.of(realDstId), false),
                    new Cell(List.of(realSvcId), false),
                    emptyApp,
                    action,
                    "",
                    "",
                    extras));
        }
        sections.add(new Section(sectionId, sectionName, "ASA NAT", null, rules));
    }

    private record Section2SortKey(
            NatRule rule,
            int configIndex,
            int typeOrder,
            boolean addressProven,
            long quantity,
            long lowestIp,
            String objectName) {}

    /**
     * Orders section 2 object NAT rules per A0-ASA-17:
     * static before dynamic, then quantity of real addresses (smallest first), then lowest IP, then object name.
     * If two rules cannot be ordered by these keys, keeps configuration order and adds
     * {@code ASA_NAT_SECTION2_ORDER_UNPROVEN}.
     */
    private static List<NatRule> orderNatSection2(
            String snapshotId,
            List<NatRule> rules,
            AsaPolicyConfig c,
            List<CollectionFailure> failures) {
        if (rules.size() <= 1) return rules;
        List<Section2SortKey> keys = new ArrayList<>(rules.size());
        for (int i = 0; i < rules.size(); i++) {
            NatRule r = rules.get(i);
            keys.add(computeSection2Key(r, i, c));
        }
        boolean unproven = false;
        for (int i = 0; i < keys.size() && !unproven; i++) {
            Section2SortKey a = keys.get(i);
            if (a.typeOrder() < 0) {
                unproven = true;
                break;
            }
            for (int j = i + 1; j < keys.size(); j++) {
                Section2SortKey b = keys.get(j);
                if (b.typeOrder() < 0) {
                    unproven = true;
                    break;
                }
                if (a.typeOrder() == b.typeOrder() && (!a.addressProven() || !b.addressProven())) {
                    unproven = true;
                    break;
                }
            }
        }
        if (unproven) {
            failures.add(new CollectionFailure(
                    ref(snapshotId, "nat", "2"),
                    "ASA_NAT_SECTION2_ORDER_UNPROVEN",
                    "ASA NAT section 2",
                    0));
            return rules;
        }
        keys.sort((a, b) -> {
            int cmp = Integer.compare(a.typeOrder(), b.typeOrder());
            if (cmp != 0) return cmp;
            cmp = Long.compare(a.quantity(), b.quantity());
            if (cmp != 0) return cmp;
            cmp = Long.compare(a.lowestIp(), b.lowestIp());
            if (cmp != 0) return cmp;
            cmp = a.objectName().compareTo(b.objectName());
            if (cmp != 0) return cmp;
            return Integer.compare(a.configIndex(), b.configIndex());
        });
        List<NatRule> sorted = new ArrayList<>(keys.size());
        for (Section2SortKey k : keys) sorted.add(k.rule());
        return sorted;
    }

    private static Section2SortKey computeSection2Key(NatRule r, int index, AsaPolicyConfig c) {
        String objName = r.objectName() == null ? "" : r.objectName();
        if (r.unparsed() || r.natType() == null || r.objectName() == null) {
            return new Section2SortKey(r, index, -1, false, 0, 0, objName);
        }
        int typeOrder = "static".equals(r.natType()) ? 0 : "dynamic".equals(r.natType()) ? 1 : -1;
        NetworkObject netObj = c.networkObjects().get(objName);
        if (netObj == null || netObj.unparsed()) {
            return new Section2SortKey(r, index, typeOrder, false, 0, 0, objName);
        }
        switch (netObj.kind()) {
            case "HOST" -> {
                String ip = resolveAlias(netObj.value1(), c.names());
                Long v = ipv4ToLong(ip);
                if (v == null) return new Section2SortKey(r, index, typeOrder, false, 0, 0, objName);
                return new Section2SortKey(r, index, typeOrder, true, 1L, v, objName);
            }
            case "SUBNET" -> {
                String ip = resolveAlias(netObj.value1(), c.names());
                String mask = netObj.value2();
                Long ipVal = ipv4ToLong(ip);
                Integer prefix = mask == null ? null : contiguousMaskPrefix(mask);
                if (ipVal == null || prefix == null) {
                    return new Section2SortKey(r, index, typeOrder, false, 0, 0, objName);
                }
                long maskBits = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
                long lowestIp = ipVal & maskBits;
                long quantity = 1L << (32 - prefix);
                return new Section2SortKey(r, index, typeOrder, true, quantity, lowestIp, objName);
            }
            case "RANGE" -> {
                String ip1 = resolveAlias(netObj.value1(), c.names());
                String ip2 = resolveAlias(netObj.value2(), c.names());
                Long v1 = ipv4ToLong(ip1);
                Long v2 = ipv4ToLong(ip2);
                if (v1 == null || v2 == null || v1 > v2) {
                    return new Section2SortKey(r, index, typeOrder, false, 0, 0, objName);
                }
                return new Section2SortKey(r, index, typeOrder, true, (v2 - v1) + 1L, v1, objName);
            }
            default -> {
                return new Section2SortKey(r, index, typeOrder, false, 0, 0, objName);
            }
        }
    }

    private static String resolveNatAddressOperand(
            String snapshotId,
            String operand,
            String fallbackIf,
            AsaPolicyConfig c,
            Map<String, PolicyObject> objects) {
        if (operand == null || "any".equals(operand) || "any4".equals(operand)) {
            return ensureAnyObject(snapshotId, objects);
        }
        if ("any6".equals(operand)) {
            return ensureLiteralAddress(snapshotId, new AddressSpec("any6", "any6", null), c, objects);
        }
        if ("interface".equals(operand)) {
            String ifName = fallbackIf == null ? "interface" : fallbackIf;
            return ensureLiteralAddress(snapshotId, new AddressSpec("interface", ifName, null), c, objects);
        }
        if (c.networkObjects().containsKey(operand)) {
            return ensureNetworkObject(snapshotId, operand, c, objects);
        }
        ObjectGroup og = c.objectGroups().get(operand);
        if (og != null && "network".equals(og.groupType())) {
            return ensureNetworkObjectGroup(snapshotId, operand, c, objects, new HashSet<>());
        }
        String resolved = resolveAlias(operand, c.names());
        if (AsaConfigPolicyParser.isValidIpv4(resolved)) {
            return ensureLiteralAddress(snapshotId, new AddressSpec("host", operand, null), c, objects);
        }
        if (AsaConfigPolicyParser.isIpv6Literal(resolved) || AsaConfigPolicyParser.isIpv6Prefix(resolved)) {
            return ensureLiteralAddress(snapshotId, new AddressSpec("ipv6", operand, null), c, objects);
        }
        return ensureUnresolvedObject(snapshotId, "network-object", operand, objects);
    }

    private static String resolveNatServiceOperand(
            String snapshotId,
            String serviceProtocol,
            String operand,
            AsaPolicyConfig c,
            Map<String, PolicyObject> objects) {
        if (operand == null) {
            return ensureAnyObject(snapshotId, objects);
        }
        if (serviceProtocol != null) {
            return ensureInlineService(snapshotId, serviceProtocol, null, PortOp.single("eq", operand), null, objects);
        }
        if (c.serviceObjects().containsKey(operand)) {
            return ensureServiceObject(snapshotId, operand, c, objects);
        }
        ObjectGroup og = c.objectGroups().get(operand);
        if (og != null && ("service".equals(og.groupType()) || "protocol".equals(og.groupType()) || "icmp-type".equals(og.groupType()))) {
            return ensureServiceObjectGroup(snapshotId, operand, c, objects, new HashSet<>());
        }
        return ensureUnresolvedObject(snapshotId, "service-object", operand, objects);
    }

    private static String resolveAddressSpec(
            String snapshotId,
            AddressSpec spec,
            AsaPolicyConfig c,
            Map<String, PolicyObject> objects) {
        if (spec == null) {
            return ensureUnresolvedObject(snapshotId, "literal", "missing-address", objects);
        }
        return switch (spec.kind()) {
            case "any", "any4" -> ensureAnyObject(snapshotId, objects);
            case "object" -> {
                if (c.networkObjects().containsKey(spec.value1())) {
                    yield ensureNetworkObject(snapshotId, spec.value1(), c, objects);
                }
                yield ensureUnresolvedObject(snapshotId, "network-object", spec.value1(), objects);
            }
            case "object-group" -> {
                ObjectGroup og = c.objectGroups().get(spec.value1());
                if (og != null && "network".equals(og.groupType())) {
                    yield ensureNetworkObjectGroup(snapshotId, spec.value1(), c, objects, new HashSet<>());
                }
                yield ensureUnresolvedObject(snapshotId, "network-group", spec.value1(), objects);
            }
            default -> ensureLiteralAddress(snapshotId, spec, c, objects);
        };
    }

    private static String ensureLiteralAddress(
            String snapshotId,
            AddressSpec spec,
            AsaPolicyConfig c,
            Map<String, PolicyObject> objects) {
        String text = switch (spec.kind()) {
            case "any6" -> "any6";
            case "host" -> "host " + spec.value1();
            case "subnet" -> spec.value1() + " " + spec.value2();
            case "interface" -> "interface " + spec.value1();
            case "ipv6" -> spec.value1();
            default -> spec.kind() + " " + spec.value1();
        };
        String id = ref(snapshotId, "literal", text);
        if (objects.containsKey(id)) return id;

        PolicyObject obj = switch (spec.kind()) {
            // A0-ASA-11: any6 is IPv6-only -> UNSUPPORTED in IPv4 engine.
            case "any6" -> new PolicyObject(id, text, "address", List.of(), List.of(), "UNSUPPORTED");
            case "interface" -> new PolicyObject(id, text, "address", List.of(), List.of(), "UNKNOWN");
            case "ipv6" -> new PolicyObject(id, text, "address", List.of(), List.of(), "UNSUPPORTED");
            case "host" -> buildHostObject(id, text, spec.value1(), c.names());
            case "subnet" -> buildSubnetObject(id, text, spec.value1(), spec.value2(), c.names());
            default -> new PolicyObject(id, text, "address", List.of(), List.of(), "UNSUPPORTED");
        };
        objects.put(id, obj);
        return id;
    }

    private static String ensureNetworkObject(
            String snapshotId,
            String name,
            AsaPolicyConfig c,
            Map<String, PolicyObject> objects) {
        String id = ref(snapshotId, "network-object", name);
        if (objects.containsKey(id)) return id;
        NetworkObject net = c.networkObjects().get(name);
        if (net == null) {
            return ensureUnresolvedObject(snapshotId, "network-object", name, objects);
        }
        if (net.unparsed()) {
            objects.put(id, new PolicyObject(id, name, "address", List.of(), List.of(), "UNSUPPORTED"));
            return id;
        }
        PolicyObject obj = switch (net.kind()) {
            case "HOST" -> buildHostObject(id, name, net.value1(), c.names());
            case "SUBNET" -> buildSubnetObject(id, name, net.value1(), net.value2(), c.names());
            case "RANGE" -> buildRangeObject(id, name, net.value1(), net.value2(), c.names());
            // A0-ASA-13: FQDN objects are resolved at run time -> DYNAMIC.
            case "FQDN" -> new PolicyObject(id, name, "address", List.of(), List.of(), "DYNAMIC");
            default -> new PolicyObject(id, name, "address", List.of(), List.of(), "UNSUPPORTED");
        };
        objects.put(id, obj);
        return id;
    }

    private static PolicyObject buildHostObject(String id, String name, String rawAddr, Map<String, String> names) {
        String resolved = resolveAlias(rawAddr, names);
        if (AsaConfigPolicyParser.isValidIpv4(resolved)) {
            return new PolicyObject(id, name, "host", List.of(), List.of("ip-netmask: " + resolved + "/32"), "RESOLVED");
        }
        if (AsaConfigPolicyParser.isIpv6Literal(resolved) || AsaConfigPolicyParser.isIpv6Prefix(resolved)) {
            return new PolicyObject(id, name, "host", List.of(), List.of(), "UNSUPPORTED");
        }
        return new PolicyObject(id, name, "host", List.of(), List.of(), "UNRESOLVED");
    }

    private static PolicyObject buildSubnetObject(
            String id, String name, String rawAddr, String rawMask, Map<String, String> names) {
        String resolved = resolveAlias(rawAddr, names);
        if (rawMask == null || AsaConfigPolicyParser.isIpv6Literal(resolved) || AsaConfigPolicyParser.isIpv6Prefix(resolved)) {
            return new PolicyObject(id, name, "network", List.of(), List.of(), "UNSUPPORTED");
        }
        if (!AsaConfigPolicyParser.isValidIpv4(resolved) || !AsaConfigPolicyParser.isValidIpv4(rawMask)) {
            return new PolicyObject(id, name, "network", List.of(), List.of(), "UNSUPPORTED");
        }
        // A0-ASA-12: dotted mask converted only when contiguous; non-contiguous mask -> UNSUPPORTED.
        Integer prefix = contiguousMaskPrefix(rawMask);
        if (prefix == null) {
            return new PolicyObject(id, name, "network", List.of(), List.of(), "UNSUPPORTED");
        }
        return new PolicyObject(id, name, "network", List.of(), List.of("ip-netmask: " + resolved + "/" + prefix), "RESOLVED");
    }

    private static PolicyObject buildRangeObject(
            String id, String name, String rawFirst, String rawLast, Map<String, String> names) {
        String ip1 = resolveAlias(rawFirst, names);
        String ip2 = resolveAlias(rawLast, names);
        Long v1 = ipv4ToLong(ip1);
        Long v2 = ipv4ToLong(ip2);
        if (v1 == null || v2 == null || v1 > v2) {
            return new PolicyObject(id, name, "address-range", List.of(), List.of(), "UNSUPPORTED");
        }
        return new PolicyObject(id, name, "address-range", List.of(), List.of("ip-range: " + ip1 + "-" + ip2), "RESOLVED");
    }

    private static String ensureNetworkObjectGroup(
            String snapshotId,
            String name,
            AsaPolicyConfig c,
            Map<String, PolicyObject> objects,
            Set<String> path) {
        String id = ref(snapshotId, "network-group", name);
        if (objects.containsKey(id)) return id;
        ObjectGroup og = c.objectGroups().get(name);
        if (og == null || !"network".equals(og.groupType())) {
            return ensureUnresolvedObject(snapshotId, "network-group", name, objects);
        }
        if (!path.add(name) || path.size() > 32) {
            objects.put(id, new PolicyObject(id, name, "address-group", List.of(), List.of(), "UNRESOLVED"));
            return id;
        }
        List<String> memberIds = new ArrayList<>(og.children().size());
        int unparsedIdx = 0;
        for (GroupChild child : og.children()) {
            if (child.unparsed()) {
                memberIds.add(ensureUnresolvedObject(snapshotId, "og-child", name + ":unparsed:" + (++unparsedIdx), objects));
                continue;
            }
            switch (child.kind()) {
                case "host" -> memberIds.add(ensureLiteralAddress(
                        snapshotId, new AddressSpec("host", child.value1(), null), c, objects));
                case "subnet" -> memberIds.add(ensureLiteralAddress(
                        snapshotId, new AddressSpec("subnet", child.value1(), child.value2()), c, objects));
                case "ipv6-prefix" -> memberIds.add(ensureLiteralAddress(
                        snapshotId, new AddressSpec("ipv6", child.value1(), null), c, objects));
                case "network-object-ref" -> {
                    if (c.networkObjects().containsKey(child.value1())) {
                        memberIds.add(ensureNetworkObject(snapshotId, child.value1(), c, objects));
                    } else {
                        memberIds.add(ensureUnresolvedObject(snapshotId, "network-object", child.value1(), objects));
                    }
                }
                case "group-object" -> {
                    ObjectGroup nested = c.objectGroups().get(child.value1());
                    if (nested != null && "network".equals(nested.groupType())) {
                        memberIds.add(ensureNetworkObjectGroup(snapshotId, child.value1(), c, objects, path));
                    } else {
                        memberIds.add(ensureUnresolvedObject(snapshotId, "network-group", child.value1(), objects));
                    }
                }
                default -> memberIds.add(ensureUnresolvedObject(
                        snapshotId, "og-child", name + ":other:" + (++unparsedIdx), objects));
            }
        }
        path.remove(name);
        objects.put(id, new PolicyObject(id, name, "address-group", memberIds, List.of(), "RESOLVED"));
        return id;
    }

    private static String ensureServiceObject(
            String snapshotId,
            String name,
            AsaPolicyConfig c,
            Map<String, PolicyObject> objects) {
        String id = ref(snapshotId, "service-object", name);
        if (objects.containsKey(id)) return id;
        ServiceObject svc = c.serviceObjects().get(name);
        if (svc == null) {
            return ensureUnresolvedObject(snapshotId, "service-object", name, objects);
        }
        if (svc.unparsed() || svc.protocol() == null) {
            objects.put(id, new PolicyObject(id, name, "service", List.of(), List.of(), "UNSUPPORTED"));
            return id;
        }
        putServiceDefinition(snapshotId, id, name, svc.protocol(), svc.sourcePort(), svc.destinationPort(), svc.icmpType(), objects);
        return id;
    }

    private static String ensureServiceObjectGroup(
            String snapshotId,
            String name,
            AsaPolicyConfig c,
            Map<String, PolicyObject> objects,
            Set<String> path) {
        String id = ref(snapshotId, "service-group", name);
        if (objects.containsKey(id)) return id;
        ObjectGroup og = c.objectGroups().get(name);
        if (og == null || !("service".equals(og.groupType()) || "protocol".equals(og.groupType()) || "icmp-type".equals(og.groupType()))) {
            return ensureUnresolvedObject(snapshotId, "service-group", name, objects);
        }
        if (!path.add(name) || path.size() > 32) {
            objects.put(id, new PolicyObject(id, name, "service-group", List.of(), List.of(), "UNRESOLVED"));
            return id;
        }

        List<String> memberIds = new ArrayList<>(og.children().size());
        int unparsedIdx = 0;
        for (GroupChild child : og.children()) {
            if (child.unparsed()) {
                memberIds.add(ensureUnresolvedObject(snapshotId, "og-svc-child", name + ":unparsed:" + (++unparsedIdx), objects));
                continue;
            }
            switch (child.kind()) {
                case "port-object" -> {
                    // Legacy port groups combine the header protocol with each port-object (A0-ASA-14).
                    String headerProto = og.protocol();
                    memberIds.add(ensureInlineService(snapshotId, headerProto, null, child.destinationPort(), null, objects));
                }
                case "service-object" -> {
                    memberIds.add(ensureInlineService(
                            snapshotId, child.value1(), child.sourcePort(), child.destinationPort(), child.value2(), objects));
                }
                case "service-object-ref" -> {
                    if (c.serviceObjects().containsKey(child.value1())) {
                        memberIds.add(ensureServiceObject(snapshotId, child.value1(), c, objects));
                    } else {
                        memberIds.add(ensureUnresolvedObject(snapshotId, "service-object", child.value1(), objects));
                    }
                }
                case "protocol-object" -> {
                    memberIds.add(ensureInlineService(snapshotId, child.value1(), null, null, null, objects));
                }
                case "icmp-object" -> {
                    memberIds.add(ensureInlineService(snapshotId, "icmp", null, null, child.value1(), objects));
                }
                case "group-object" -> {
                    ObjectGroup nested = c.objectGroups().get(child.value1());
                    if (nested != null && ("service".equals(nested.groupType())
                            || "protocol".equals(nested.groupType())
                            || "icmp-type".equals(nested.groupType()))) {
                        memberIds.add(ensureServiceObjectGroup(snapshotId, child.value1(), c, objects, path));
                    } else {
                        memberIds.add(ensureUnresolvedObject(snapshotId, "service-group", child.value1(), objects));
                    }
                }
                default -> memberIds.add(ensureUnresolvedObject(
                        snapshotId, "og-svc-child", name + ":other:" + (++unparsedIdx), objects));
            }
        }
        path.remove(name);
        objects.put(id, new PolicyObject(id, name, "service-group", memberIds, List.of(), "RESOLVED"));
        return id;
    }

    private static String resolveAceService(
            String snapshotId,
            Ace ace,
            AsaPolicyConfig c,
            Map<String, PolicyObject> objects) {
        ProtocolSpec protoSpec = ace.protocol();
        PortOp srcPort = ace.sourcePort();
        PortOp dstPort = ace.destinationPort();
        if (protoSpec == null) {
            return ensureUnresolvedObject(snapshotId, "literal", "missing-protocol", objects);
        }

        if ("object".equals(protoSpec.kind())) {
            if (c.serviceObjects().containsKey(protoSpec.value())) {
                return ensureServiceObject(snapshotId, protoSpec.value(), c, objects);
            }
            return ensureUnresolvedObject(snapshotId, "service-object", protoSpec.value(), objects);
        }

        if ("object-group".equals(protoSpec.kind())) {
            String ogName = protoSpec.value();
            if (srcPort == null && dstPort == null) {
                ObjectGroup og = c.objectGroups().get(ogName);
                if (og != null && ("service".equals(og.groupType())
                        || "protocol".equals(og.groupType())
                        || "icmp-type".equals(og.groupType()))) {
                    return ensureServiceObjectGroup(snapshotId, ogName, c, objects, new HashSet<>());
                }
                return ensureUnresolvedObject(snapshotId, "service-group", ogName, objects);
            }
            // Protocol object-group combined with port operators on the ACE
            ObjectGroup og = c.objectGroups().get(ogName);
            if (og != null && "protocol".equals(og.groupType())) {
                Set<String> protos = collectProtocolGroupLiterals(og, c.objectGroups(), new HashSet<>());
                if (protos != null && !protos.isEmpty()) {
                    if (protos.equals(Set.of("tcp"))) {
                        return ensureInlineService(snapshotId, "tcp", srcPort, dstPort, null, objects);
                    }
                    if (protos.equals(Set.of("udp"))) {
                        return ensureInlineService(snapshotId, "udp", srcPort, dstPort, null, objects);
                    }
                    if (protos.equals(Set.of("tcp", "udp"))) {
                        return ensureInlineService(snapshotId, "tcp-udp", srcPort, dstPort, null, objects);
                    }
                }
            }
            String text = "object-group " + ogName + " " + formatPortOp("src", srcPort) + " " + formatPortOp("dst", dstPort);
            String id = ref(snapshotId, "literal", text);
            objects.putIfAbsent(id, new PolicyObject(id, text, "service", List.of(), List.of(), "UNSUPPORTED"));
            return id;
        }

        if ("number".equals(protoSpec.kind())) {
            String text = "proto " + protoSpec.value();
            String id = ref(snapshotId, "literal", text);
            objects.putIfAbsent(id, new PolicyObject(id, text, "service", List.of(), List.of(), "UNSUPPORTED"));
            return id;
        }

        // Literal protocol
        String proto = protoSpec.value();
        if ("ip".equals(proto) && srcPort == null && dstPort == null) {
            return ensureAnyObject(snapshotId, objects);
        }
        if ("icmp".equals(proto)) {
            if (dstPort != null && "object-group".equals(dstPort.operator())) {
                String ogName = dstPort.objectGroup();
                ObjectGroup og = c.objectGroups().get(ogName);
                if (og != null && ("icmp-type".equals(og.groupType()) || "service".equals(og.groupType()))) {
                    return ensureServiceObjectGroup(snapshotId, ogName, c, objects, new HashSet<>());
                }
                return ensureUnresolvedObject(snapshotId, "service-group", ogName, objects);
            }
            return ensureInlineService(snapshotId, "icmp", null, null, ace.icmpType(), objects);
        }
        if ("tcp".equals(proto) || "udp".equals(proto)) {
            if (srcPort == null && dstPort != null && "object-group".equals(dstPort.operator())) {
                String ogName = dstPort.objectGroup();
                ObjectGroup og = c.objectGroups().get(ogName);
                if (og != null && "service".equals(og.groupType())) {
                    return ensureServiceObjectGroup(snapshotId, ogName, c, objects, new HashSet<>());
                }
                return ensureUnresolvedObject(snapshotId, "service-group", ogName, objects);
            }
            return ensureInlineService(snapshotId, proto, srcPort, dstPort, null, objects);
        }

        // Any other protocol literal (gre, esp, icmp6, ospf, etc.) -> UNSUPPORTED
        String text = "proto " + proto;
        String id = ref(snapshotId, "literal", text);
        objects.putIfAbsent(id, new PolicyObject(id, text, "service", List.of(), List.of(), "UNSUPPORTED"));
        return id;
    }

    private static Set<String> collectProtocolGroupLiterals(
            ObjectGroup og, Map<String, ObjectGroup> allGroups, Set<String> visited) {
        if (!visited.add(og.name()) || visited.size() > 32) return null;
        Set<String> out = new LinkedHashSet<>();
        for (GroupChild ch : og.children()) {
            if (ch.unparsed()) return null;
            if ("protocol-object".equals(ch.kind())) {
                if ("tcp-udp".equals(ch.value1())) {
                    out.add("tcp");
                    out.add("udp");
                } else if ("tcp".equals(ch.value1()) || "udp".equals(ch.value1())) {
                    out.add(ch.value1());
                } else {
                    return null;
                }
            } else if ("group-object".equals(ch.kind())) {
                ObjectGroup nested = allGroups.get(ch.value1());
                if (nested == null || !"protocol".equals(nested.groupType())) return null;
                Set<String> sub = collectProtocolGroupLiterals(nested, allGroups, visited);
                if (sub == null) return null;
                out.addAll(sub);
            } else {
                return null;
            }
        }
        visited.remove(og.name());
        return out;
    }

    private static String ensureInlineService(
            String snapshotId,
            String proto,
            PortOp srcPort,
            PortOp dstPort,
            String icmpType,
            Map<String, PolicyObject> objects) {
        if ("ip".equals(proto) && srcPort == null && dstPort == null && icmpType == null) {
            return ensureAnyObject(snapshotId, objects);
        }
        String text = buildServiceText(proto, srcPort, dstPort, icmpType);
        String id = ref(snapshotId, "literal", text);
        if (objects.containsKey(id)) return id;
        putServiceDefinition(snapshotId, id, text, proto, srcPort, dstPort, icmpType, objects);
        return id;
    }

    private static void putServiceDefinition(
            String snapshotId,
            String id,
            String name,
            String proto,
            PortOp srcPort,
            PortOp dstPort,
            String icmpType,
            Map<String, PolicyObject> objects) {
        if ("ip".equals(proto) && srcPort == null && dstPort == null && icmpType == null) {
            objects.put(id, new PolicyObject(id, "ANY", "any", List.of(), List.of(), "RESOLVED"));
            return;
        }
        if ("tcp-udp".equals(proto)) {
            // A0-ASA-14: tcp-udp -> a service-group of two services (tcp and udp).
            String tcpId = ensureInlineService(snapshotId, "tcp", srcPort, dstPort, null, objects);
            String udpId = ensureInlineService(snapshotId, "udp", srcPort, dstPort, null, objects);
            PolicyObject tcpObj = objects.get(tcpId);
            PolicyObject udpObj = objects.get(udpId);
            String status = (tcpObj != null && "RESOLVED".equals(tcpObj.status())
                    && udpObj != null && "RESOLVED".equals(udpObj.status())) ? "RESOLVED" : "UNSUPPORTED";
            objects.put(id, new PolicyObject(id, name, "service-group", List.of(tcpId, udpId), List.of(), status));
            return;
        }
        if ("icmp".equals(proto)) {
            if (srcPort != null || dstPort != null) {
                objects.put(id, new PolicyObject(id, name, "service", List.of(), List.of(), "UNSUPPORTED"));
                return;
            }
            // icmp with a type -> values ["protocol: icmp", "icmp-type: T"] with no code.
            List<String> values = icmpType != null
                    ? List.of("protocol: icmp", "icmp-type: " + icmpType)
                    : List.of("protocol: icmp");
            objects.put(id, new PolicyObject(id, name, "service", List.of(), values, "RESOLVED"));
            return;
        }
        if ("tcp".equals(proto) || "udp".equals(proto)) {
            String srcRange = null;
            if (srcPort != null) {
                srcRange = evalPortOp(srcPort, proto);
                if (srcRange == null) {
                    objects.put(id, new PolicyObject(id, name, "service", List.of(), List.of(), "UNSUPPORTED"));
                    return;
                }
            }
            String dstRange = "0-65535";
            if (dstPort != null) {
                dstRange = evalPortOp(dstPort, proto);
                if (dstRange == null) {
                    objects.put(id, new PolicyObject(id, name, "service", List.of(), List.of(), "UNSUPPORTED"));
                    return;
                }
            }
            List<String> values;
            if (srcRange != null) {
                values = List.of(
                        "protocol/" + proto + "/source-port: " + srcRange,
                        "protocol/" + proto + "/port: " + dstRange);
            } else {
                values = List.of("protocol: " + proto, "port: " + dstRange);
            }
            objects.put(id, new PolicyObject(id, name, "service", List.of(), values, "RESOLVED"));
            return;
        }
        // Any other protocol (gre, esp, numbers, etc.) -> type service, status UNSUPPORTED.
        objects.put(id, new PolicyObject(id, name, "service", List.of(), List.of(), "UNSUPPORTED"));
    }

    /**
     * Converts a {@link PortOp} to a port range expression ({@code N} or {@code N-M}) for {@code protocol}
     * ({@code tcp} or {@code udp}).
     * Returns null when the operator is {@code lt}/{@code gt}/{@code neq} (port bounds not established by A0)
     * or when a port literal is not in Table 7 for that protocol (A0-ASA-15).
     */
    private static String evalPortOp(PortOp op, String protocol) {
        if (op == null || op.operator() == null) return null;
        return switch (op.operator()) {
            case "eq" -> {
                Integer p = AsaLiterals.resolvePort(op.port1(), protocol);
                yield p == null ? null : String.valueOf(p);
            }
            case "range" -> {
                Integer a = AsaLiterals.resolvePort(op.port1(), protocol);
                Integer b = AsaLiterals.resolvePort(op.port2(), protocol);
                yield (a == null || b == null || a > b) ? null : a + "-" + b;
            }
            // lt, gt, neq, and object-group in port op position -> UNSUPPORTED
            default -> null;
        };
    }

    private static String buildServiceText(String proto, PortOp srcPort, PortOp dstPort, String icmpType) {
        StringBuilder sb = new StringBuilder(proto == null ? "unknown" : proto);
        if (srcPort != null) sb.append(" src ").append(formatPortOp("", srcPort));
        if (dstPort != null) sb.append(" dst ").append(formatPortOp("", dstPort));
        if (icmpType != null) sb.append(" ").append(icmpType);
        return sb.toString();
    }

    private static String formatPortOp(String prefix, PortOp op) {
        if (op == null) return "";
        String p = prefix.isEmpty() ? "" : prefix + ":";
        if ("range".equals(op.operator())) return p + "range " + op.port1() + " " + op.port2();
        if ("object-group".equals(op.operator())) return p + "object-group " + op.objectGroup();
        return p + op.operator() + " " + op.port1();
    }

    private static String ensureAnyObject(String snapshotId, Map<String, PolicyObject> objects) {
        String id = ref(snapshotId, "any", "any");
        objects.putIfAbsent(id, new PolicyObject(id, "ANY", "any", List.of(), List.of(), "RESOLVED"));
        return id;
    }

    private static String ensureUnresolvedObject(
            String snapshotId, String kind, String name, Map<String, PolicyObject> objects) {
        String id = ref(snapshotId, kind, name);
        objects.putIfAbsent(id, new PolicyObject(id, name, "unresolved", List.of(), List.of(), "UNRESOLVED"));
        return id;
    }

    private static String resolveAlias(String token, Map<String, String> names) {
        if (token == null) return "";
        return names.getOrDefault(token, token);
    }

    static Long ipv4ToLong(String ip) {
        if (!AsaConfigPolicyParser.isValidIpv4(ip)) return null;
        long result = 0;
        int val = 0;
        for (int i = 0; i < ip.length(); i++) {
            char c = ip.charAt(i);
            if (c == '.') {
                result = (result << 8) | (val & 0xFFL);
                val = 0;
            } else {
                val = val * 10 + (c - '0');
            }
        }
        return (result << 8) | (val & 0xFFL);
    }

    static Integer contiguousMaskPrefix(String mask) {
        Long v = ipv4ToLong(mask);
        if (v == null) return null;
        long inv = (~v) & 0xFFFFFFFFL;
        if (((inv + 1) & inv) != 0) {
            return null;
        }
        return Long.bitCount(v);
    }
}
