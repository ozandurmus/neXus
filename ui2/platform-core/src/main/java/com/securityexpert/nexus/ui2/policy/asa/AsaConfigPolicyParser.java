package com.securityexpert.nexus.ui2.policy.asa;

import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.AccessGroup;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.Ace;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.AddressSpec;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.Counters;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.GroupChild;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.Interface;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.NatRule;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.NetworkObject;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.ObjectGroup;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.PortOp;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.ProtocolSpec;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyConfig.ServiceObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure, linear-time parser for Cisco ASA {@code more system:running-config} policy constructs.
 * Never logs configuration names, addresses, or lines (AGENTS.md raw-evidence and masking laws).
 */
public final class AsaConfigPolicyParser {

    private static final Set<String> OBJECT_GROUP_TYPES = Set.of(
            "network", "service", "protocol", "icmp-type", "user", "security", "network-service");
    private static final Set<String> PORT_OPERATORS = Set.of("eq", "lt", "gt", "neq", "range");
    private static final Set<String> LOG_LEVELS = Set.of(
            "emergencies", "alerts", "critical", "errors", "warnings", "notifications", "informational", "debugging");
    private static final Set<String> NAT_KEYWORDS = Set.of(
            "destination", "service", "inactive", "no-proxy-arp", "route-lookup", "unidirectional",
            "net-to-net", "dns", "flat", "include-reserve", "round-robin", "extended", "block-allocation",
            "interface", "ipv6", "pat-pool", "description");
    private static final Set<String> NON_POLICY_TOP_LEVEL_COMMANDS = Set.of(
            "hostname", "domain-name", "enable", "passwd", "ip", "ipv6", "mac-list", "arp", "dns", "dns-guard",
            "name-server", "mtu", "icmp", "asdm", "ftp", "pager", "logging", "flow-export", "failover",
            "monitor-interface", "no", "route", "router", "timeout", "xlate", "aaa", "aaa-server",
            "user-identity", "http", "snmp-server", "sysopt", "crypto", "telnet", "ssh", "console",
            "management-access", "threat-detection", "ntp", "ssl", "webvpn", "group-policy", "username",
            "tunnel-group", "class-map", "policy-map", "service-policy", "prompt", "call-home",
            "Cryptochecksum:", "cryptochecksum:", "clock", "boot", "banner", "password-policy",
            "dynamic-access-policy-record", "time-range", "route-map", "prefix-list", "capture", "zone",
            "sla", "track", "dhcpd", "dhcprelay", "multicast-routing", "igmp", "pim", "vpdn", "isakmp",
            "ldap", "kerberos", "radius", "tacacs", "certificate", "ca", "tftp-server", "smtp-server",
            "anc", "cts", "ScanSafe", "regex", "url-server", "filter", "wccp", "botnet", "fragment",
            "conns", "clear", "end", "service", "auto-update", " key", "key", "policy-list", "priority-queue");

    /** ACL use recorded when an ACL name appears on a configuration line this parser does not interpret. */
    public static final String UNPARSED_REFERENCE = "unparsed_reference";

    public static AsaPolicyConfig parse(String runningConfig) {
        return parse(runningConfig, false);
    }

    public static AsaPolicyConfig parse(String runningConfig, boolean multipleContextHint) {
        String text = runningConfig == null ? "" : runningConfig;
        List<RawLine> rawLines = splitLines(text);

        // Pre-pass for 'name <ip> <alias>' so aliases are recognized even if defined out of order.
        Map<String, String> names = new LinkedHashMap<>();
        for (RawLine rl : rawLines) {
            if (!rl.indented() && rl.tokens().size() >= 3 && "name".equals(rl.tokens().get(0))) {
                String ip = rl.tokens().get(1);
                String alias = rl.tokens().get(2);
                if (isValidIpv4(ip) || isIpv6Literal(ip)) {
                    names.put(alias, ip);
                }
            }
        }

        String version = "";
        boolean multipleContext = multipleContextHint;
        boolean transparent = false;
        List<Interface> interfaces = new ArrayList<>();
        boolean sameSecurityInter = false;
        boolean sameSecurityIntra = false;
        Map<String, NetworkObject> networkObjects = new LinkedHashMap<>();
        Map<String, ServiceObject> serviceObjects = new LinkedHashMap<>();
        Map<String, ObjectGroup> objectGroups = new LinkedHashMap<>();
        Map<String, List<Ace>> acls = new LinkedHashMap<>();
        Map<String, List<String>> pendingRemarks = new LinkedHashMap<>();
        List<AccessGroup> accessGroups = new ArrayList<>();
        List<NatRule> natRules = new ArrayList<>();
        Map<String, Set<String>> aclUses = new LinkedHashMap<>();
        // Lines whose ACL references no parsed feature accounts for (scanned after the loop, once every ACL is known).
        List<RawLine> unparsedLines = new ArrayList<>();

        int parsedAces = 0;
        int unparsedAces = 0;
        int parsedNatRules = 0;
        int unparsedNatRules = 0;
        int parsedOgChildren = 0;
        int unparsedOgChildren = 0;
        int unknownTopLevelPolicyLines = 0;

        String currentBlock = null;
        String currentBlockName = null;
        String currentOgType = null;
        String currentOgProto = null;

        // Interface block state
        String ifPhysical = null;
        String ifNameif = null;
        Integer ifSecurityLevel = null;
        boolean ifShutdown = false;

        // Network object block state
        NetworkObject pendingNetObj = null;
        boolean netObjSawNat = false;
        boolean netObjSawDefinition = false;
        boolean netObjSawUnknownChild = false;

        // Service object block state
        ServiceObject pendingSvcObj = null;
        boolean svcObjSawDefinition = false;
        boolean svcObjSawUnknownChild = false;

        // Object-group block state
        List<GroupChild> ogChildren = null;

        for (int lineIdx = 0; lineIdx <= rawLines.size(); lineIdx++) {
            RawLine rl = lineIdx < rawLines.size() ? rawLines.get(lineIdx) : null;
            boolean recognisedUse = rl != null && scanAclUses(rl.tokens(), rl.indented(), currentBlock, aclUses);

            if (rl != null && rl.indented()) {
                List<String> w = rl.tokens();
                if (currentBlock == null) {
                    continue;
                }
                switch (currentBlock) {
                    case "interface" -> {
                        String kw = w.get(0);
                        if ("nameif".equals(kw) && w.size() >= 2) {
                            ifNameif = w.get(1);
                        } else if ("no".equals(kw) && w.size() >= 2 && "nameif".equals(w.get(1))) {
                            ifNameif = null;
                        } else if ("security-level".equals(kw) && w.size() >= 2 && AsaLiterals.isUnsignedDigits(w.get(1))) {
                            if (w.get(1).length() <= 3) {
                                ifSecurityLevel = Integer.parseInt(w.get(1));
                            }
                        } else if ("no".equals(kw) && w.size() >= 2 && "security-level".equals(w.get(1))) {
                            ifSecurityLevel = null;
                        } else if ("shutdown".equals(kw) && w.size() == 1) {
                            ifShutdown = true;
                        } else if ("no".equals(kw) && w.size() >= 2 && "shutdown".equals(w.get(1))) {
                            ifShutdown = false;
                        } else if (!recognisedUse && !"description".equals(kw)) {
                            // e.g. 'igmp access-group X': an interface sub-command this parser does not read
                            unparsedLines.add(rl);
                        }
                    }
                    case "object network" -> {
                        String kw = w.get(0);
                        if ("description".equals(kw)) {
                            // ignored
                        } else if ("nat".equals(kw)) {
                            netObjSawNat = true;
                            NatRule nat = parseObjectNat(currentBlockName, w);
                            natRules.add(nat);
                            if (nat.unparsed()) unparsedNatRules++;
                            else parsedNatRules++;
                        } else if ("host".equals(kw) && w.size() == 2) {
                            netObjSawDefinition = true;
                            pendingNetObj = new NetworkObject(currentBlockName, "HOST", w.get(1), null, false);
                        } else if ("subnet".equals(kw) && w.size() == 3) {
                            netObjSawDefinition = true;
                            pendingNetObj = new NetworkObject(currentBlockName, "SUBNET", w.get(1), w.get(2), false);
                        } else if ("subnet".equals(kw) && w.size() == 2 && w.get(1).contains("/")) {
                            netObjSawDefinition = true;
                            pendingNetObj = new NetworkObject(currentBlockName, "SUBNET", w.get(1), null, false);
                        } else if ("range".equals(kw) && w.size() == 3) {
                            netObjSawDefinition = true;
                            pendingNetObj = new NetworkObject(currentBlockName, "RANGE", w.get(1), w.get(2), false);
                        } else if ("fqdn".equals(kw) && w.size() >= 2) {
                            netObjSawDefinition = true;
                            if (w.size() == 2) {
                                pendingNetObj = new NetworkObject(currentBlockName, "FQDN", w.get(1), null, false);
                            } else if (w.size() == 3 && ("v4".equals(w.get(1)) || "v6".equals(w.get(1)))) {
                                pendingNetObj = new NetworkObject(currentBlockName, "FQDN", w.get(2), w.get(1), false);
                            } else {
                                netObjSawUnknownChild = true;
                            }
                        } else {
                            netObjSawUnknownChild = true;
                        }
                    }
                    case "object service" -> {
                        String kw = w.get(0);
                        if ("description".equals(kw)) {
                            // ignored
                        } else if ("service".equals(kw)) {
                            svcObjSawDefinition = true;
                            pendingSvcObj = parseServiceObjectChild(currentBlockName, w);
                            if (pendingSvcObj.unparsed()) {
                                svcObjSawUnknownChild = true;
                            }
                        } else {
                            svcObjSawUnknownChild = true;
                        }
                    }
                    case "object-group" -> {
                        if ("description".equals(w.get(0))) {
                            // ignored
                        } else {
                            GroupChild child = parseObjectGroupChild(currentOgType, currentOgProto, w, names);
                            ogChildren.add(child);
                            if (child.unparsed()) unparsedOgChildren++;
                            else parsedOgChildren++;
                        }
                    }
                    default -> {
                        // non-policy block children are not interpreted: an ACL named there may be in use (UNKNOWN)
                        if (!recognisedUse && !"description".equals(w.get(0))) {
                            unparsedLines.add(rl);
                        }
                    }
                }
                continue;
            }

            // Close previous top-level block before starting the next top-level line (or at EOF).
            if (currentBlock != null) {
                switch (currentBlock) {
                    case "interface" -> {
                        if (ifNameif != null && !ifNameif.isEmpty()) {
                            interfaces.add(new Interface(ifPhysical, ifNameif, ifSecurityLevel, ifShutdown));
                        }
                    }
                    case "object network" -> {
                        // A second 'object network N' block whose only child is 'nat (...) ...' is object NAT (section 2),
                        // not a new definition (A0-ASA-17).
                        if (netObjSawDefinition) {
                            if (netObjSawUnknownChild) {
                                networkObjects.put(currentBlockName, new NetworkObject(currentBlockName, "UNPARSED", null, null, true));
                            } else {
                                networkObjects.put(currentBlockName, pendingNetObj);
                            }
                        } else if (netObjSawUnknownChild || (!netObjSawNat && !networkObjects.containsKey(currentBlockName))) {
                            networkObjects.put(currentBlockName, new NetworkObject(currentBlockName, "UNPARSED", null, null, true));
                        }
                    }
                    case "object service" -> {
                        if (svcObjSawDefinition && !svcObjSawUnknownChild && pendingSvcObj != null) {
                            serviceObjects.put(currentBlockName, pendingSvcObj);
                        } else {
                            serviceObjects.put(currentBlockName, new ServiceObject(currentBlockName, null, null, null, null, null, true));
                        }
                    }
                    case "object-group" -> {
                        ObjectGroup existing = objectGroups.get(currentBlockName);
                        if (existing != null && existing.groupType().equals(currentOgType)) {
                            List<GroupChild> combined = new ArrayList<>(existing.children());
                            combined.addAll(ogChildren);
                            objectGroups.put(currentBlockName, new ObjectGroup(currentBlockName, currentOgType, currentOgProto, combined));
                        } else {
                            objectGroups.put(currentBlockName, new ObjectGroup(currentBlockName, currentOgType, currentOgProto, ogChildren));
                        }
                    }
                    default -> {}
                }
                currentBlock = null;
                currentBlockName = null;
                currentOgType = null;
                currentOgProto = null;
                ifPhysical = null;
                ifNameif = null;
                ifSecurityLevel = null;
                ifShutdown = false;
                pendingNetObj = null;
                netObjSawNat = false;
                netObjSawDefinition = false;
                netObjSawUnknownChild = false;
                pendingSvcObj = null;
                svcObjSawDefinition = false;
                svcObjSawUnknownChild = false;
                ogChildren = null;
            }

            if (rl == null) break;

            List<String> w = rl.tokens();
            String first = w.get(0);

            if ("ASA".equals(first) && w.size() >= 3 && "Version".equals(w.get(1))) {
                version = w.size() == 3 ? w.get(2) : String.join(" ", w.subList(2, w.size()));
                continue;
            }
            if ("mode".equals(first)) {
                // A0-ASA-19: multiple-context mode is not supported by single-context policy model.
                if (w.size() >= 2 && "multiple".equals(w.get(1))) {
                    multipleContext = true;
                } else if (w.size() < 2 || !"single".equals(w.get(1))) {
                    unknownTopLevelPolicyLines++;
                }
                continue;
            }
            if ("admin-context".equals(first) || "context".equals(first)) {
                // A0-ASA-19: system execution space in multiple-context mode.
                multipleContext = true;
                currentBlock = "other";
                continue;
            }
            if ("firewall".equals(first)) {
                // A0-ASA-10: transparent firewall mode.
                if (w.size() == 2 && "transparent".equals(w.get(1))) {
                    transparent = true;
                } else {
                    unknownTopLevelPolicyLines++;
                }
                continue;
            }
            if ("interface".equals(first)) {
                if (w.size() >= 2) {
                    currentBlock = "interface";
                    ifPhysical = w.get(1);
                } else {
                    unknownTopLevelPolicyLines++;
                }
                continue;
            }
            if ("same-security-traffic".equals(first)) {
                // A0-ASA-5, A0-ASA-20: absent = false; permit inter-interface | intra-interface.
                if (w.size() == 3 && "permit".equals(w.get(1)) && "inter-interface".equals(w.get(2))) {
                    sameSecurityInter = true;
                } else if (w.size() == 3 && "permit".equals(w.get(1)) && "intra-interface".equals(w.get(2))) {
                    sameSecurityIntra = true;
                } else {
                    unknownTopLevelPolicyLines++;
                }
                continue;
            }
            if ("names".equals(first) && w.size() == 1) {
                continue;
            }
            if ("name".equals(first)) {
                if (w.size() >= 3 && (isValidIpv4(w.get(1)) || isIpv6Literal(w.get(1)))
                        && (w.size() == 3 || "description".equals(w.get(3)))) {
                    names.put(w.get(2), w.get(1));
                } else {
                    unknownTopLevelPolicyLines++;
                }
                continue;
            }
            if ("object".equals(first)) {
                if (w.size() == 3 && "network".equals(w.get(1))) {
                    currentBlock = "object network";
                    currentBlockName = w.get(2);
                } else if (w.size() == 3 && "service".equals(w.get(1))) {
                    currentBlock = "object service";
                    currentBlockName = w.get(2);
                } else {
                    unknownTopLevelPolicyLines++;
                    currentBlock = "other";
                }
                continue;
            }
            if ("object-group".equals(first)) {
                if (w.size() >= 3 && OBJECT_GROUP_TYPES.contains(w.get(1))) {
                    String ogType = w.get(1);
                    String ogName = w.get(2);
                    String ogProto = null;
                    boolean validHeader = w.size() == 3;
                    if ("service".equals(ogType) && w.size() == 4
                            && ("tcp".equals(w.get(3)) || "udp".equals(w.get(3)) || "tcp-udp".equals(w.get(3)))) {
                        ogProto = w.get(3);
                        validHeader = true;
                    }
                    if (validHeader) {
                        currentBlock = "object-group";
                        currentBlockName = ogName;
                        currentOgType = ogType;
                        currentOgProto = ogProto;
                        ogChildren = new ArrayList<>();
                    } else {
                        unknownTopLevelPolicyLines++;
                        currentBlock = "other";
                    }
                } else {
                    unknownTopLevelPolicyLines++;
                    currentBlock = "other";
                }
                continue;
            }
            if ("access-list".equals(first)) {
                if (w.size() < 3) {
                    unknownTopLevelPolicyLines++;
                    continue;
                }
                String aclName = w.get(1);
                String aclSub = w.get(2);
                if ("remark".equals(aclSub)) {
                    String remarkText = extractRemark(rl.trimmed(), aclName);
                    pendingRemarks.computeIfAbsent(aclName, k -> new ArrayList<>()).add(remarkText);
                    continue;
                }
                List<String> remarks = pendingRemarks.remove(aclName);
                List<String> attachedRemarks = remarks == null ? List.of() : List.copyOf(remarks);
                Ace ace = parseAce(aclName, aclSub, w, names, objectGroups, attachedRemarks);
                acls.computeIfAbsent(aclName, k -> new ArrayList<>()).add(ace);
                if (ace.unparsed()) unparsedAces++;
                else parsedAces++;
                continue;
            }
            if ("access-group".equals(first)) {
                AccessGroup ag = parseAccessGroup(w);
                if (ag != null) {
                    accessGroups.add(ag);
                } else {
                    unknownTopLevelPolicyLines++;
                    unparsedLines.add(rl);
                }
                continue;
            }
            if ("nat".equals(first)) {
                // Parsed NAT carries no ACL name; a legacy policy NAT ('nat (if) N access-list X') is not read and
                // therefore stays an unparsed rule whose line may name an ACL.
                NatRule nat = parseManualNat(w);
                natRules.add(nat);
                if (nat.unparsed()) {
                    unparsedNatRules++;
                    if (!recognisedUse) unparsedLines.add(rl);
                } else {
                    parsedNatRules++;
                }
                continue;
            }

            currentBlock = "other";
            if (!NON_POLICY_TOP_LEVEL_COMMANDS.contains(first)) {
                unknownTopLevelPolicyLines++;
            }
            if (!recognisedUse) {
                unparsedLines.add(rl);
            }
        }

        scanUnparsedAclReferences(unparsedLines, acls.keySet(), aclUses);

        Counters counters = new Counters(
                parsedAces,
                unparsedAces,
                parsedNatRules,
                unparsedNatRules,
                parsedOgChildren,
                unparsedOgChildren,
                unknownTopLevelPolicyLines);

        return new AsaPolicyConfig(
                version,
                multipleContext,
                transparent,
                interfaces,
                sameSecurityInter,
                sameSecurityIntra,
                names,
                networkObjects,
                serviceObjects,
                objectGroups,
                acls,
                accessGroups,
                natRules,
                aclUses,
                counters);
    }

    private static AccessGroup parseAccessGroup(List<String> w) {
        // A0-ASA-2/3/9: 'access-group N in|out interface IF [control-plane]' and 'access-group N global'
        if (w.size() == 3 && "global".equals(w.get(2))) {
            return new AccessGroup(w.get(1), "global", null, false);
        }
        if (w.size() >= 5 && ("in".equals(w.get(2)) || "out".equals(w.get(2))) && "interface".equals(w.get(3))) {
            if (w.size() == 5) {
                return new AccessGroup(w.get(1), w.get(2), w.get(4), false);
            }
            if (w.size() == 6 && "control-plane".equals(w.get(5))) {
                return new AccessGroup(w.get(1), w.get(2), w.get(4), true);
            }
        }
        return null;
    }

    private static ServiceObject parseServiceObjectChild(String objName, List<String> w) {
        // service <proto> [source <op>] [destination <op>]
        if (w.size() < 2) {
            return new ServiceObject(objName, null, null, null, null, null, true);
        }
        String proto = w.get(1);
        if ("icmp".equals(proto) || "icmp6".equals(proto)) {
            if (w.size() == 2) {
                return new ServiceObject(objName, proto, null, null, null, null, false);
            }
            if (w.size() == 3) {
                return new ServiceObject(objName, proto, null, null, w.get(2), null, false);
            }
            if (w.size() == 4 && isProtocolOrIcmpNumber(w.get(3))) {
                return new ServiceObject(objName, proto, null, null, w.get(2), w.get(3), false);
            }
            return new ServiceObject(objName, proto, null, null, null, null, true);
        }
        if ("tcp".equals(proto) || "udp".equals(proto) || "tcp-udp".equals(proto) || "sctp".equals(proto)) {
            PortOp srcOp = null;
            PortOp dstOp = null;
            int idx = 2;
            while (idx < w.size()) {
                String tok = w.get(idx);
                if ("source".equals(tok)) {
                    if (srcOp != null) return new ServiceObject(objName, proto, null, null, null, null, true);
                    idx++;
                    ParsedPortOp p = parsePortOperatorOnly(w, idx);
                    if (p == null) return new ServiceObject(objName, proto, null, null, null, null, true);
                    srcOp = p.op();
                    idx = p.nextIndex();
                } else if ("destination".equals(tok)) {
                    if (dstOp != null) return new ServiceObject(objName, proto, null, null, null, null, true);
                    idx++;
                    ParsedPortOp p = parsePortOperatorOnly(w, idx);
                    if (p == null) return new ServiceObject(objName, proto, null, null, null, null, true);
                    dstOp = p.op();
                    idx = p.nextIndex();
                } else if (PORT_OPERATORS.contains(tok) && dstOp == null) {
                    ParsedPortOp p = parsePortOperatorOnly(w, idx);
                    if (p == null) return new ServiceObject(objName, proto, null, null, null, null, true);
                    dstOp = p.op();
                    idx = p.nextIndex();
                } else {
                    return new ServiceObject(objName, proto, null, null, null, null, true);
                }
            }
            return new ServiceObject(objName, proto, srcOp, dstOp, null, null, false);
        }
        if (w.size() == 2 && (AsaLiterals.isKnownProtocolLiteral(proto) || isProtocolOrIcmpNumber(proto))) {
            return new ServiceObject(objName, proto, null, null, null, null, false);
        }
        return new ServiceObject(objName, proto, null, null, null, null, true);
    }

    private static GroupChild parseObjectGroupChild(String ogType, String ogProto, List<String> w, Map<String, String> names) {
        String kw = w.get(0);
        if ("group-object".equals(kw) && w.size() == 2) {
            return new GroupChild("group-object", w.get(1), null, null, null, false);
        }
        switch (ogType) {
            case "network" -> {
                if ("network-object".equals(kw)) {
                    if (w.size() == 3 && "host".equals(w.get(1)) && isAddressOrAlias(w.get(2), names)) {
                        return new GroupChild("host", w.get(2), null, null, null, false);
                    }
                    if (w.size() == 3 && "object".equals(w.get(1))) {
                        return new GroupChild("network-object-ref", w.get(2), null, null, null, false);
                    }
                    if (w.size() == 3 && isIpv4OrAlias(w.get(1), names) && isValidIpv4(w.get(2))) {
                        return new GroupChild("subnet", w.get(1), w.get(2), null, null, false);
                    }
                    if (w.size() == 2 && isIpv6Prefix(w.get(1))) {
                        return new GroupChild("ipv6-prefix", w.get(1), null, null, null, false);
                    }
                }
                return new GroupChild("unparsed", null, null, null, null, true);
            }
            case "service" -> {
                if ("port-object".equals(kw)) {
                    if (ogProto == null) {
                        return new GroupChild("unparsed", null, null, null, null, true);
                    }
                    ParsedPortOp p = parsePortOperatorOnly(w, 1);
                    if (p != null && p.nextIndex() == w.size()) {
                        return new GroupChild("port-object", ogProto, null, null, p.op(), false);
                    }
                    return new GroupChild("unparsed", null, null, null, null, true);
                }
                if ("service-object".equals(kw)) {
                    if (w.size() == 3 && "object".equals(w.get(1))) {
                        return new GroupChild("service-object-ref", w.get(2), null, null, null, false);
                    }
                    ServiceObject so = parseServiceObjectChild("", w);
                    if (!so.unparsed()) {
                        return new GroupChild("service-object", so.protocol(), so.icmpType(), so.sourcePort(), so.destinationPort(), false);
                    }
                }
                return new GroupChild("unparsed", null, null, null, null, true);
            }
            case "protocol" -> {
                if ("protocol-object".equals(kw) && w.size() == 2) {
                    String p = w.get(1);
                    if (AsaLiterals.isKnownProtocolLiteral(p) || "tcp-udp".equals(p) || isProtocolOrIcmpNumber(p)) {
                        return new GroupChild("protocol-object", p, null, null, null, false);
                    }
                }
                return new GroupChild("unparsed", null, null, null, null, true);
            }
            case "icmp-type" -> {
                if ("icmp-object".equals(kw) && w.size() == 2) {
                    return new GroupChild("icmp-object", w.get(1), null, null, null, false);
                }
                return new GroupChild("unparsed", null, null, null, null, true);
            }
            case "user" -> {
                if (("user".equals(kw) || "user-group".equals(kw)) && w.size() == 2) {
                    return new GroupChild(kw, w.get(1), null, null, null, false);
                }
                return new GroupChild("unparsed", null, null, null, null, true);
            }
            case "security" -> {
                if ("security-group".equals(kw) && w.size() == 3 && ("name".equals(w.get(1)) || "tag".equals(w.get(1)))) {
                    return new GroupChild("security-group", w.get(2), w.get(1), null, null, false);
                }
                return new GroupChild("unparsed", null, null, null, null, true);
            }
            default -> {
                return new GroupChild("unparsed", null, null, null, null, true);
            }
        }
    }

    private static Ace parseAce(
            String aclName,
            String aclType,
            List<String> w,
            Map<String, String> names,
            Map<String, ObjectGroup> objectGroups,
            List<String> remarks) {
        // Other ACL types (standard, ethertype, webtype, advanced) are kept as Ace with type set and unparsed = true.
        if (!"extended".equals(aclType)) {
            String action = w.size() >= 4 && ("permit".equals(w.get(3)) || "deny".equals(w.get(3))) ? w.get(3) : "UNKNOWN";
            return new Ace(aclName, aclType, action, null, null, null, null, null, null,
                    List.of(), List.of(), false, null, w.contains("inactive"), remarks, true);
        }
        if (w.size() < 6) {
            return unparsedExtendedAce(aclName, w, remarks);
        }
        String action = w.get(3);
        if (!"permit".equals(action) && !"deny".equals(action)) {
            return unparsedExtendedAce(aclName, w, remarks);
        }

        int idx = 4;
        // 1. Protocol: ip, a protocol literal from Table 6, a number, object-group X, object X (A0-ASA-15)
        ProtocolSpec protocol;
        String pTok = w.get(idx);
        if ("object-group".equals(pTok)) {
            if (idx + 1 >= w.size()) return unparsedExtendedAce(aclName, w, remarks);
            protocol = new ProtocolSpec("object-group", w.get(idx + 1));
            idx += 2;
        } else if ("object".equals(pTok)) {
            if (idx + 1 >= w.size()) return unparsedExtendedAce(aclName, w, remarks);
            protocol = new ProtocolSpec("object", w.get(idx + 1));
            idx += 2;
        } else if (AsaLiterals.isKnownProtocolLiteral(pTok)) {
            protocol = new ProtocolSpec("literal", pTok);
            idx++;
        } else if (isProtocolOrIcmpNumber(pTok)) {
            protocol = new ProtocolSpec("number", pTok);
            idx++;
        } else {
            return unparsedExtendedAce(aclName, w, remarks);
        }

        // 2. Identity criteria preceding the source address (A0-ASA-16):
        // user U, user-group G, object-group-user G, security-group name|tag X, object-group-security X
        List<String> users = new ArrayList<>();
        List<String> securityGroups = new ArrayList<>();
        idx = consumeIdentityAndSecurityCriteria(w, idx, users, securityGroups, names);
        if (idx < 0 || idx >= w.size()) {
            return unparsedExtendedAce(aclName, w, remarks);
        }

        // 3. Source address (A0-ASA-11/12)
        ParsedAddress srcAddr = parseAddress(w, idx, names);
        if (srcAddr == null) {
            return unparsedExtendedAce(aclName, w, remarks);
        }
        AddressSpec source = srcAddr.address();
        idx = srcAddr.nextIndex();

        // 4. Optional source port (when protocol allows ports)
        boolean protoAllowsPorts = allowsPortOperators(protocol);
        PortOp sourcePort = null;
        if (protoAllowsPorts && idx < w.size()) {
            String next = w.get(idx);
            if (PORT_OPERATORS.contains(next)) {
                ParsedPortOp sp = parsePortOperatorOnly(w, idx);
                if (sp == null) return unparsedExtendedAce(aclName, w, remarks);
                sourcePort = sp.op();
                idx = sp.nextIndex();
            } else if ("object-group".equals(next)) {
                if (idx + 1 >= w.size()) return unparsedExtendedAce(aclName, w, remarks);
                if (isSourcePortObjectGroup(w, idx, names, objectGroups)) {
                    sourcePort = PortOp.group(w.get(idx + 1));
                    idx += 2;
                }
            }
        }

        // Optional security-group criteria before destination address
        idx = consumeSecurityCriteriaOnly(w, idx, securityGroups);
        if (idx < 0 || idx >= w.size()) {
            return unparsedExtendedAce(aclName, w, remarks);
        }

        // 5. Destination address
        ParsedAddress dstAddr = parseAddress(w, idx, names);
        if (dstAddr == null) {
            return unparsedExtendedAce(aclName, w, remarks);
        }
        AddressSpec destination = dstAddr.address();
        idx = dstAddr.nextIndex();

        // 6. Optional destination port or ICMP type
        PortOp destinationPort = null;
        String icmpType = null;
        boolean protoIsIcmp = isIcmpProtocol(protocol);
        if (protoIsIcmp && idx < w.size() && !isAceTailOption(w.get(idx))) {
            if ("object-group".equals(w.get(idx))) {
                if (idx + 1 >= w.size()) return unparsedExtendedAce(aclName, w, remarks);
                destinationPort = PortOp.group(w.get(idx + 1));
                idx += 2;
            } else {
                icmpType = w.get(idx);
                idx++;
                // Optional numeric ICMP code
                if (idx < w.size() && !isAceTailOption(w.get(idx)) && isProtocolOrIcmpNumber(w.get(idx))) {
                    idx++;
                }
            }
        } else if (protoAllowsPorts && idx < w.size() && !isAceTailOption(w.get(idx))) {
            String next = w.get(idx);
            if (PORT_OPERATORS.contains(next)) {
                ParsedPortOp dp = parsePortOperatorOnly(w, idx);
                if (dp == null) return unparsedExtendedAce(aclName, w, remarks);
                destinationPort = dp.op();
                idx = dp.nextIndex();
            } else if ("object-group".equals(next)) {
                if (idx + 1 >= w.size()) return unparsedExtendedAce(aclName, w, remarks);
                destinationPort = PortOp.group(w.get(idx + 1));
                idx += 2;
            } else {
                return unparsedExtendedAce(aclName, w, remarks);
            }
        }

        // 7. Tail options: [log ...] [time-range X] [inactive] (A0-ASA-8)
        boolean log = false;
        String timeRange = null;
        boolean inactive = false;
        while (idx < w.size()) {
            String tok = w.get(idx);
            if ("log".equals(tok)) {
                log = true;
                idx++;
                if (idx < w.size() && ("disable".equals(w.get(idx)) || "default".equals(w.get(idx)))) {
                    idx++;
                } else {
                    if (idx < w.size() && (isLogLevelNumber(w.get(idx)) || LOG_LEVELS.contains(w.get(idx)))) {
                        idx++;
                    }
                    if (idx < w.size() && "interval".equals(w.get(idx))) {
                        if (idx + 1 >= w.size() || !AsaLiterals.isUnsignedDigits(w.get(idx + 1))) {
                            return unparsedExtendedAce(aclName, w, remarks);
                        }
                        idx += 2;
                    }
                }
            } else if ("time-range".equals(tok)) {
                if (idx + 1 >= w.size()) return unparsedExtendedAce(aclName, w, remarks);
                timeRange = w.get(idx + 1);
                idx += 2;
            } else if ("inactive".equals(tok)) {
                inactive = true;
                idx++;
            } else {
                return unparsedExtendedAce(aclName, w, remarks);
            }
        }

        return new Ace(
                aclName,
                "extended",
                action,
                protocol,
                source,
                sourcePort,
                destination,
                destinationPort,
                icmpType,
                users,
                securityGroups,
                log,
                timeRange,
                inactive,
                remarks,
                false);
    }

    private static Ace unparsedExtendedAce(String aclName, List<String> w, List<String> remarks) {
        String action = w.size() >= 4 && ("permit".equals(w.get(3)) || "deny".equals(w.get(3))) ? w.get(3) : "UNKNOWN";
        boolean inactive = w.contains("inactive");
        return new Ace(aclName, "extended", action, null, null, null, null, null, null,
                List.of(), List.of(), false, null, inactive, remarks, true);
    }

    /** Tokens that start a source address or another criterion: they end a multi-word user or group name. */
    private static final Set<String> IDENTITY_NAME_STOP = Set.of("any", "any4", "any6", "host", "object", "object-group",
            "interface", "user", "user-group", "object-group-user", "security-group", "object-group-security");

    /**
     * A {@code user} / {@code user-group} name: the running configuration prints directory names as they are, so a name
     * can contain spaces (measured 2026-11-14 on the estate: {@code DOMAIN\\group name ...}). A quoted name runs to
     * its closing quote; an unquoted one runs until the next token that starts an address or another criterion. A
     * continuation word that is an IPv4/IPv6 literal or a {@code names} alias is ambiguous: the ACE stays unparsed.
     */
    private static int consumeIdentityName(List<String> w, int idx, List<String> users, Map<String, String> names) {
        if (idx >= w.size()) return -1;
        StringBuilder name = new StringBuilder(w.get(idx));
        int i = idx + 1;
        if (w.get(idx).startsWith("\"")) {
            while (!(name.length() > 1 && name.charAt(name.length() - 1) == '"')) {
                if (i >= w.size()) return -1;
                name.append(' ').append(w.get(i++));
            }
        } else {
            while (i < w.size() && !IDENTITY_NAME_STOP.contains(w.get(i))) {
                String t = w.get(i);
                if (isValidIpv4(t) || isIpv6Literal(t) || names.containsKey(t)) return -1;
                name.append(' ').append(t);
                i++;
            }
        }
        users.add(name.toString());
        return i;
    }

    private static int consumeIdentityAndSecurityCriteria(
            List<String> w, int idx, List<String> users, List<String> securityGroups, Map<String, String> names) {
        while (idx < w.size()) {
            String tok = w.get(idx);
            if ("user".equals(tok) || "user-group".equals(tok)) {
                idx = consumeIdentityName(w, idx + 1, users, names);
                if (idx < 0) return -1;
            } else if ("object-group-user".equals(tok)) {
                if (idx + 1 >= w.size()) return -1;
                users.add(w.get(idx + 1));
                idx += 2;
            } else if ("object-group-security".equals(tok)) {
                if (idx + 1 >= w.size()) return -1;
                securityGroups.add(w.get(idx + 1));
                idx += 2;
            } else if ("security-group".equals(tok)) {
                if (idx + 2 >= w.size() || (!"name".equals(w.get(idx + 1)) && !"tag".equals(w.get(idx + 1)))) {
                    return -1;
                }
                securityGroups.add(w.get(idx + 2));
                idx += 3;
            } else {
                break;
            }
        }
        return idx;
    }

    private static int consumeSecurityCriteriaOnly(List<String> w, int idx, List<String> securityGroups) {
        while (idx < w.size()) {
            String tok = w.get(idx);
            if ("object-group-security".equals(tok)) {
                if (idx + 1 >= w.size()) return -1;
                securityGroups.add(w.get(idx + 1));
                idx += 2;
            } else if ("security-group".equals(tok)) {
                if (idx + 2 >= w.size() || (!"name".equals(w.get(idx + 1)) && !"tag".equals(w.get(idx + 1)))) {
                    return -1;
                }
                securityGroups.add(w.get(idx + 2));
                idx += 3;
            } else {
                break;
            }
        }
        return idx;
    }

    /**
     * Determines whether {@code object-group A} at {@code idx} (immediately after source address) is a source-port
     * service group rather than the destination address group.
     */
    private static boolean isSourcePortObjectGroup(
            List<String> w, int idx, Map<String, String> names, Map<String, ObjectGroup> objectGroups) {
        int afterFirstGroup = idx + 2;
        if (afterFirstGroup >= w.size()) {
            return false; // No tokens left for destination address, so this must be the destination address.
        }
        String nextTok = w.get(afterFirstGroup);
        if (isAceTailOption(nextTok) || PORT_OPERATORS.contains(nextTok)) {
            return false; // Followed by dst port operator or tail option -> this is the destination address.
        }
        if ("object-group".equals(nextTok)) {
            int afterSecondGroup = afterFirstGroup + 2;
            if (afterSecondGroup < w.size() && !isAceTailOption(w.get(afterSecondGroup))) {
                // Another clause follows the second group (e.g. dst port), so the first group is src port.
                return true;
            }
            // Exactly two object-group clauses after source: check if first is a service group and second is network.
            ObjectGroup firstOg = objectGroups.get(w.get(idx + 1));
            ObjectGroup secondOg = afterFirstGroup + 1 < w.size() ? objectGroups.get(w.get(afterFirstGroup + 1)) : null;
            return firstOg != null && "service".equals(firstOg.groupType())
                    && (secondOg == null || "network".equals(secondOg.groupType()));
        }
        // Any other valid address starter after 'object-group A' means that starter is destination, so A is src port.
        return parseAddress(w, afterFirstGroup, names) != null
                || "security-group".equals(nextTok)
                || "object-group-security".equals(nextTok);
    }

    private record ParsedAddress(AddressSpec address, int nextIndex) {}

    private static ParsedAddress parseAddress(List<String> w, int idx, Map<String, String> names) {
        if (idx >= w.size()) return null;
        String tok = w.get(idx);
        switch (tok) {
            case "any" -> {
                return new ParsedAddress(new AddressSpec("any", "any", null), idx + 1);
            }
            case "any4" -> {
                return new ParsedAddress(new AddressSpec("any4", "any4", null), idx + 1);
            }
            case "any6" -> {
                return new ParsedAddress(new AddressSpec("any6", "any6", null), idx + 1);
            }
            case "host" -> {
                if (idx + 1 >= w.size() || !isAddressOrAlias(w.get(idx + 1), names)) return null;
                return new ParsedAddress(new AddressSpec("host", w.get(idx + 1), null), idx + 2);
            }
            case "object" -> {
                if (idx + 1 >= w.size()) return null;
                return new ParsedAddress(new AddressSpec("object", w.get(idx + 1), null), idx + 2);
            }
            case "object-group" -> {
                if (idx + 1 >= w.size()) return null;
                return new ParsedAddress(new AddressSpec("object-group", w.get(idx + 1), null), idx + 2);
            }
            case "interface" -> {
                if (idx + 1 >= w.size()) return null;
                return new ParsedAddress(new AddressSpec("interface", w.get(idx + 1), null), idx + 2);
            }
            default -> {
                if (isIpv6Prefix(tok, names)) {
                    return new ParsedAddress(new AddressSpec("ipv6", tok, null), idx + 1);
                }
                if (idx + 1 < w.size() && isIpv4OrAlias(tok, names) && isValidIpv4(w.get(idx + 1))) {
                    return new ParsedAddress(new AddressSpec("subnet", tok, w.get(idx + 1)), idx + 2);
                }
                return null;
            }
        }
    }

    private record ParsedPortOp(PortOp op, int nextIndex) {}

    private static ParsedPortOp parsePortOperatorOnly(List<String> w, int idx) {
        if (idx >= w.size()) return null;
        String op = w.get(idx);
        if ("eq".equals(op) || "lt".equals(op) || "gt".equals(op) || "neq".equals(op)) {
            if (idx + 1 >= w.size()) return null;
            String p = w.get(idx + 1);
            if (isAceTailOption(p) || PORT_OPERATORS.contains(p)) return null;
            return new ParsedPortOp(PortOp.single(op, p), idx + 2);
        }
        if ("range".equals(op)) {
            if (idx + 2 >= w.size()) return null;
            String p1 = w.get(idx + 1);
            String p2 = w.get(idx + 2);
            if (isAceTailOption(p1) || isAceTailOption(p2)) return null;
            return new ParsedPortOp(PortOp.range(p1, p2), idx + 3);
        }
        return null;
    }

    private static boolean allowsPortOperators(ProtocolSpec proto) {
        if ("object-group".equals(proto.kind())) return true;
        if ("literal".equals(proto.kind())) {
            return "tcp".equals(proto.value()) || "udp".equals(proto.value());
        }
        if ("number".equals(proto.kind())) {
            return "6".equals(proto.value()) || "17".equals(proto.value());
        }
        return false;
    }

    private static boolean isIcmpProtocol(ProtocolSpec proto) {
        if ("literal".equals(proto.kind())) {
            return "icmp".equals(proto.value()) || "icmp6".equals(proto.value());
        }
        if ("number".equals(proto.kind())) {
            return "1".equals(proto.value()) || "58".equals(proto.value());
        }
        return false;
    }

    private static boolean isAceTailOption(String tok) {
        return "log".equals(tok) || "time-range".equals(tok) || "inactive".equals(tok);
    }

    private static NatRule parseManualNat(List<String> w) {
        // nat (real_if,mapped_if) [after-auto] source static|dynamic <real> <mapped>
        // [destination static <mapped> <real>] [service <real> <mapped>] [options] (A0-ASA-17)
        ParsedIfPair ifPair = parseNatInterfaces(w, 1);
        int sectionGuess = w.contains("after-auto") ? 3 : 1;
        if (ifPair == null) {
            return new NatRule(sectionGuess, null, null, null, null, null, null, null, null, null, null, null,
                    w.contains("inactive"), w.contains("no-proxy-arp"), false, false, true);
        }
        String realIf = ifPair.realIf();
        String mappedIf = ifPair.mappedIf();
        boolean anyIf = "any".equals(realIf) || "any".equals(mappedIf);
        int idx = ifPair.nextIndex();
        int section = 1;
        if (idx < w.size() && "after-auto".equals(w.get(idx))) {
            section = 3;
            idx++;
        }
        if (idx + 3 >= w.size() || !"source".equals(w.get(idx))) {
            return unparsedNat(section, null, realIf, mappedIf, anyIf, w);
        }
        idx++;
        String natType = w.get(idx);
        if (!"static".equals(natType) && !"dynamic".equals(natType)) {
            return unparsedNat(section, null, realIf, mappedIf, anyIf, w);
        }
        idx++;
        String realSource = w.get(idx++);
        String mappedSource;
        boolean interfacePat = false;
        if ("pat-pool".equals(w.get(idx))) {
            idx++;
            if (idx >= w.size()) return unparsedNat(section, null, realIf, mappedIf, anyIf, w);
            mappedSource = w.get(idx++);
        } else {
            mappedSource = w.get(idx++);
        }
        if ("interface".equals(mappedSource)) {
            interfacePat = true;
            if (idx < w.size() && "ipv6".equals(w.get(idx))) idx++;
        } else if ("dynamic".equals(natType) && idx < w.size() && "interface".equals(w.get(idx))) {
            interfacePat = true;
            idx++;
            if (idx < w.size() && "ipv6".equals(w.get(idx))) idx++;
        }

        String realDestination = null;
        String mappedDestination = null;
        String serviceProtocol = null;
        String realService = null;
        String mappedService = null;
        boolean inactive = false;
        boolean noProxyArp = false;

        while (idx < w.size()) {
            String tok = w.get(idx);
            if ("destination".equals(tok)) {
                if (idx + 3 >= w.size() || !"static".equals(w.get(idx + 1))) {
                    return unparsedNat(section, null, realIf, mappedIf, anyIf, w);
                }
                // destination static <mapped> <real>
                mappedDestination = w.get(idx + 2);
                realDestination = w.get(idx + 3);
                idx += 4;
                if ("interface".equals(mappedDestination) && idx < w.size() && "ipv6".equals(w.get(idx))) {
                    idx++;
                }
            } else if ("service".equals(tok)) {
                idx++;
                if (idx + 1 >= w.size()) return unparsedNat(section, null, realIf, mappedIf, anyIf, w);
                if (idx + 2 < w.size()
                        && ("tcp".equals(w.get(idx)) || "udp".equals(w.get(idx)) || "sctp".equals(w.get(idx)))
                        && !NAT_KEYWORDS.contains(w.get(idx + 2))) {
                    serviceProtocol = w.get(idx);
                    realService = w.get(idx + 1);
                    mappedService = w.get(idx + 2);
                    idx += 3;
                } else {
                    serviceProtocol = null;
                    realService = w.get(idx);
                    mappedService = w.get(idx + 1);
                    idx += 2;
                }
            } else if ("inactive".equals(tok)) {
                inactive = true;
                idx++;
            } else if ("no-proxy-arp".equals(tok)) {
                noProxyArp = true;
                idx++;
            } else if ("interface".equals(tok)) {
                interfacePat = true;
                idx++;
                if (idx < w.size() && "ipv6".equals(w.get(idx))) idx++;
            } else if ("pat-pool".equals(tok)) {
                if (idx + 1 >= w.size()) return unparsedNat(section, null, realIf, mappedIf, anyIf, w);
                idx += 2;
            } else if ("route-lookup".equals(tok) || "unidirectional".equals(tok) || "net-to-net".equals(tok)
                    || "dns".equals(tok) || "flat".equals(tok) || "include-reserve".equals(tok)
                    || "round-robin".equals(tok) || "extended".equals(tok) || "block-allocation".equals(tok)) {
                idx++;
            } else if ("description".equals(tok)) {
                break;
            } else {
                return unparsedNat(section, null, realIf, mappedIf, anyIf, w);
            }
        }

        return new NatRule(
                section,
                null,
                realIf,
                mappedIf,
                natType,
                realSource,
                mappedSource,
                realDestination,
                mappedDestination,
                serviceProtocol,
                realService,
                mappedService,
                inactive,
                noProxyArp,
                interfacePat,
                anyIf,
                false);
    }

    private static NatRule parseObjectNat(String objectName, List<String> w) {
        // nat (real_if,mapped_if) static|dynamic <mapped> [service ...] [options] (A0-ASA-17)
        ParsedIfPair ifPair = parseNatInterfaces(w, 1);
        if (ifPair == null) {
            return new NatRule(2, objectName, null, null, null, objectName, null, null, null, null, null, null,
                    w.contains("inactive"), w.contains("no-proxy-arp"), false, false, true);
        }
        String realIf = ifPair.realIf();
        String mappedIf = ifPair.mappedIf();
        boolean anyIf = "any".equals(realIf) || "any".equals(mappedIf);
        int idx = ifPair.nextIndex();
        if (idx + 1 >= w.size()) {
            return unparsedNat(2, objectName, realIf, mappedIf, anyIf, w);
        }
        String natType = w.get(idx++);
        if (!"static".equals(natType) && !"dynamic".equals(natType)) {
            return unparsedNat(2, objectName, realIf, mappedIf, anyIf, w);
        }
        String mappedSource;
        boolean interfacePat = false;
        if ("pat-pool".equals(w.get(idx))) {
            idx++;
            if (idx >= w.size()) return unparsedNat(2, objectName, realIf, mappedIf, anyIf, w);
            mappedSource = w.get(idx++);
        } else {
            mappedSource = w.get(idx++);
        }
        if ("interface".equals(mappedSource)) {
            interfacePat = true;
            if (idx < w.size() && "ipv6".equals(w.get(idx))) idx++;
        } else if ("dynamic".equals(natType) && idx < w.size() && "interface".equals(w.get(idx))) {
            interfacePat = true;
            idx++;
            if (idx < w.size() && "ipv6".equals(w.get(idx))) idx++;
        }

        String serviceProtocol = null;
        String realService = null;
        String mappedService = null;
        boolean inactive = false;
        boolean noProxyArp = false;

        while (idx < w.size()) {
            String tok = w.get(idx);
            if ("service".equals(tok)) {
                idx++;
                if (idx + 1 >= w.size()) return unparsedNat(2, objectName, realIf, mappedIf, anyIf, w);
                if (idx + 2 < w.size()
                        && ("tcp".equals(w.get(idx)) || "udp".equals(w.get(idx)) || "sctp".equals(w.get(idx)))
                        && !NAT_KEYWORDS.contains(w.get(idx + 2))) {
                    serviceProtocol = w.get(idx);
                    realService = w.get(idx + 1);
                    mappedService = w.get(idx + 2);
                    idx += 3;
                } else {
                    serviceProtocol = null;
                    realService = w.get(idx);
                    mappedService = w.get(idx + 1);
                    idx += 2;
                }
            } else if ("inactive".equals(tok)) {
                inactive = true;
                idx++;
            } else if ("no-proxy-arp".equals(tok)) {
                noProxyArp = true;
                idx++;
            } else if ("interface".equals(tok)) {
                interfacePat = true;
                idx++;
                if (idx < w.size() && "ipv6".equals(w.get(idx))) idx++;
            } else if ("pat-pool".equals(tok)) {
                if (idx + 1 >= w.size()) return unparsedNat(2, objectName, realIf, mappedIf, anyIf, w);
                idx += 2;
            } else if ("route-lookup".equals(tok) || "unidirectional".equals(tok) || "net-to-net".equals(tok)
                    || "dns".equals(tok) || "flat".equals(tok) || "include-reserve".equals(tok)
                    || "round-robin".equals(tok) || "extended".equals(tok) || "block-allocation".equals(tok)) {
                idx++;
            } else if ("description".equals(tok)) {
                break;
            } else {
                return unparsedNat(2, objectName, realIf, mappedIf, anyIf, w);
            }
        }

        return new NatRule(
                2,
                objectName,
                realIf,
                mappedIf,
                natType,
                objectName,
                mappedSource,
                null,
                null,
                serviceProtocol,
                realService,
                mappedService,
                inactive,
                noProxyArp,
                interfacePat,
                anyIf,
                false);
    }

    private static NatRule unparsedNat(int section, String objectName, String realIf, String mappedIf, boolean anyIf, List<String> w) {
        return new NatRule(section, objectName, realIf, mappedIf, null, objectName, null, null, null, null, null, null,
                w.contains("inactive"), w.contains("no-proxy-arp"), false, anyIf, true);
    }

    private record ParsedIfPair(String realIf, String mappedIf, int nextIndex) {}

    private static ParsedIfPair parseNatInterfaces(List<String> w, int startIdx) {
        if (startIdx >= w.size()) return null;
        String first = w.get(startIdx);
        if (!first.startsWith("(")) return null;
        StringBuilder sb = new StringBuilder();
        int idx = startIdx;
        while (idx < w.size()) {
            String tok = w.get(idx++);
            sb.append(tok);
            if (tok.endsWith(")")) {
                String combined = sb.toString();
                if (combined.length() < 3 || combined.charAt(0) != '(' || combined.charAt(combined.length() - 1) != ')') {
                    return null;
                }
                String inner = combined.substring(1, combined.length() - 1);
                int comma = inner.indexOf(',');
                if (comma <= 0 || comma >= inner.length() - 1 || inner.indexOf(',', comma + 1) >= 0) {
                    return null;
                }
                String r = inner.substring(0, comma).trim();
                String m = inner.substring(comma + 1).trim();
                if (r.isEmpty() || m.isEmpty()) return null;
                return new ParsedIfPair(r, m, idx);
            }
        }
        return null;
    }

    /** Records the recognised uses on one line; true when at least one use was recorded for this line. */
    private static boolean scanAclUses(List<String> w, boolean indented, String currentBlock, Map<String, Set<String>> aclUses) {
        if (w.isEmpty()) return false;
        String first = w.get(0);
        boolean recorded = false;
        // crypto_map: crypto map M n match address X (or crypto dynamic-map M n match address X)
        if (!indented && "crypto".equals(first) && w.size() >= 7
                && ("map".equals(w.get(1)) || "dynamic-map".equals(w.get(1)))) {
            for (int i = 2; i + 2 < w.size(); i++) {
                if ("match".equals(w.get(i)) && "address".equals(w.get(i + 1))) {
                    addAclUse(aclUses, w.get(i + 2), "crypto_map");
                    recorded = true;
                }
            }
            return recorded;
        }
        // capture: capture ... access-list X
        if (!indented && "capture".equals(first)) {
            for (int i = 1; i + 1 < w.size(); i++) {
                if ("access-list".equals(w.get(i))) {
                    addAclUse(aclUses, w.get(i + 1), "capture");
                    recorded = true;
                }
            }
            return recorded;
        }
        // vpn_filter: vpn-filter value X | ipv6-vpn-filter value X
        if (("vpn-filter".equals(first) || "ipv6-vpn-filter".equals(first)) && w.size() >= 3 && "value".equals(w.get(1))) {
            addAclUse(aclUses, w.get(2), "vpn_filter");
            return true;
        }
        // split_tunnel: split-tunnel-network-list value X
        if ("split-tunnel-network-list".equals(first) && w.size() >= 3 && "value".equals(w.get(1))) {
            addAclUse(aclUses, w.get(2), "split_tunnel");
            return true;
        }
        // class_map: match access-list X
        if ("match".equals(first) && w.size() >= 3 && "access-list".equals(w.get(1))) {
            addAclUse(aclUses, w.get(2), "class_map");
            return true;
        }
        // route_map: match ip address X ...
        if ("match".equals(first) && w.size() >= 4 && "ip".equals(w.get(1)) && "address".equals(w.get(2))) {
            if (!"prefix-list".equals(w.get(3))) {
                for (int i = 3; i < w.size(); i++) {
                    addAclUse(aclUses, w.get(i), "route_map");
                }
                recorded = true;
            }
        }
        return recorded;
    }

    /**
     * ACL_NOT_BOUND (PO 2026-11-23): an ACL named as a whole token (exact, case-sensitive, no normalisation) on a
     * configuration line the parser does not interpret is possibly in use by a feature this model does not read; it is
     * recorded as use {@value #UNPARSED_REFERENCE}, so the mapper reports it UNKNOWN, never "unused".
     */
    private static void scanUnparsedAclReferences(
            List<RawLine> unparsedLines, Set<String> definedAcls, Map<String, Set<String>> aclUses) {
        if (definedAcls.isEmpty()) return;
        for (RawLine rl : unparsedLines) {
            for (String tok : rl.tokens()) {
                if (definedAcls.contains(tok)) {
                    addAclUse(aclUses, tok, UNPARSED_REFERENCE);
                }
            }
        }
    }

    private static void addAclUse(Map<String, Set<String>> aclUses, String aclName, String use) {
        aclUses.computeIfAbsent(aclName, k -> new LinkedHashSet<>()).add(use);
    }

    private static String extractRemark(String trimmedLine, String aclName) {
        // access-list <aclName> remark <text>
        int pos = "access-list".length();
        while (pos < trimmedLine.length() && Character.isWhitespace(trimmedLine.charAt(pos))) pos++;
        pos += aclName.length();
        while (pos < trimmedLine.length() && Character.isWhitespace(trimmedLine.charAt(pos))) pos++;
        if (trimmedLine.regionMatches(pos, "remark", 0, 6)) {
            pos += 6;
            if (pos < trimmedLine.length() && Character.isWhitespace(trimmedLine.charAt(pos))) {
                pos++;
            }
            return trimmedLine.substring(pos);
        }
        return "";
    }

    static boolean isValidIpv4(String s) {
        if (s == null || s.length() < 7 || s.length() > 15) return false;
        int dots = 0;
        int val = 0;
        int digits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') {
                if (digits == 0 || val > 255) return false;
                dots++;
                val = 0;
                digits = 0;
            } else if (c >= '0' && c <= '9') {
                if (digits > 0 && val == 0) return false;
                val = val * 10 + (c - '0');
                digits++;
                if (digits > 3) return false;
            } else {
                return false;
            }
        }
        return dots == 3 && digits > 0 && val <= 255;
    }

    static boolean isIpv6Literal(String s) {
        if (s == null || s.indexOf(':') < 0) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex && c != ':' && c != '.') return false;
        }
        return true;
    }

    static boolean isIpv6Prefix(String s) {
        if (s == null) return false;
        int slash = s.indexOf('/');
        if (slash <= 0 || slash >= s.length() - 1) return false;
        String addr = s.substring(0, slash);
        String len = s.substring(slash + 1);
        if (!isIpv6Literal(addr) || !AsaLiterals.isUnsignedDigits(len) || len.length() > 3) return false;
        int prefix = Integer.parseInt(len);
        return prefix >= 0 && prefix <= 128;
    }

    private static boolean isIpv6Prefix(String s, Map<String, String> names) {
        if (isIpv6Prefix(s)) return true;
        if (s == null) return false;
        int slash = s.indexOf('/');
        if (slash <= 0 || slash >= s.length() - 1) return false;
        String resolved = names.get(s.substring(0, slash));
        return resolved != null && isIpv6Prefix(resolved + s.substring(slash));
    }

    private static boolean isIpv4OrAlias(String s, Map<String, String> names) {
        if (isValidIpv4(s)) return true;
        String resolved = names.get(s);
        return resolved != null && isValidIpv4(resolved);
    }

    private static boolean isAddressOrAlias(String s, Map<String, String> names) {
        if (isValidIpv4(s) || isIpv6Literal(s)) return true;
        String resolved = names.get(s);
        return resolved != null && (isValidIpv4(resolved) || isIpv6Literal(resolved));
    }

    private static boolean isProtocolOrIcmpNumber(String s) {
        if (!AsaLiterals.isUnsignedDigits(s) || s.length() > 3) return false;
        int n = Integer.parseInt(s);
        return n >= 0 && n <= 255;
    }

    private static boolean isLogLevelNumber(String s) {
        return s != null && s.length() == 1 && s.charAt(0) >= '0' && s.charAt(0) <= '7';
    }

    private record RawLine(boolean indented, String trimmed, List<String> tokens) {}

    /** Linear-time line splitter and whitespace tokenizer with no regular expressions. */
    private static List<RawLine> splitLines(String text) {
        List<RawLine> out = new ArrayList<>();
        int len = text.length();
        int lineStart = 0;
        for (int i = 0; i <= len; i++) {
            char ch = i < len ? text.charAt(i) : '\n';
            if (ch == '\n' || ch == '\r') {
                if (i > lineStart) {
                    parseRawLine(text, lineStart, i, out);
                }
                if (ch == '\r' && i + 1 < len && text.charAt(i + 1) == '\n') {
                    i++;
                }
                lineStart = i + 1;
            }
        }
        return out;
    }

    private static void parseRawLine(String text, int start, int end, List<RawLine> out) {
        boolean indented = Character.isWhitespace(text.charAt(start));
        int firstNonWs = start;
        while (firstNonWs < end && Character.isWhitespace(text.charAt(firstNonWs))) firstNonWs++;
        int lastNonWs = end;
        while (lastNonWs > firstNonWs && Character.isWhitespace(text.charAt(lastNonWs - 1))) lastNonWs--;
        if (firstNonWs >= lastNonWs) return;
        char firstChar = text.charAt(firstNonWs);
        if (firstChar == '!' || firstChar == ':') return;

        List<String> tokens = new ArrayList<>(8);
        int i = firstNonWs;
        while (i < lastNonWs) {
            while (i < lastNonWs && Character.isWhitespace(text.charAt(i))) i++;
            int tokStart = i;
            while (i < lastNonWs && !Character.isWhitespace(text.charAt(i))) i++;
            if (i > tokStart) {
                tokens.add(text.substring(tokStart, i));
            }
        }
        if (!tokens.isEmpty()) {
            out.add(new RawLine(indented, text.substring(firstNonWs, lastNonWs), tokens));
        }
    }
}
