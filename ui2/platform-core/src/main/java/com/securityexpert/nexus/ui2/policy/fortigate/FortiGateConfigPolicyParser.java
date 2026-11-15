package com.securityexpert.nexus.ui2.policy.fortigate;

import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.Address6Object;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.AddressGroup;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.AddressGroup6;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.AddressObject;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.CentralSnatRule;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.Counters;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.FirewallPolicy;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.Interface;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.IpPool;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.NonEvaluatedPolicy;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.PortRange;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.SdwanZone;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.ServiceGroup;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.ServiceObject;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.VdomConfig;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.VdomCounters;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.VdomSettings;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.VipGroup;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.VipObject;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyConfig.Zone;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, linear-time parser for FortiOS top-level {@code show} configuration output (and spliced
 * {@code show full-configuration} sections).
 *
 * <p>Preserves the exact printed (sequence) order of {@code config firewall policy} entries (never sorting by
 * {@code policyid}, A0-FG-1). Never logs configuration names, addresses, or lines (AGENTS.md raw-evidence
 * and masking laws).
 */
public final class FortiGateConfigPolicyParser {

    private static final Pattern CONFIG_VERSION_PATTERN =
            Pattern.compile("^#config-version=[^-:]+-(\\d+\\.\\d+(?:\\.\\d+)?)-");
    private static final Pattern GENERIC_VERSION_PATTERN =
            Pattern.compile("v?(\\d+\\.\\d+(?:\\.\\d+)?)");

    private static final Set<String> NON_EVALUATED_POLICY_TABLES = Set.of(
            "firewall local-in-policy",
            "firewall local-in-policy6",
            "firewall proxy-policy",
            "firewall security-policy",
            "firewall multicast-policy",
            "firewall multicast-policy6",
            "firewall dos-policy",
            "firewall dos-policy6",
            "firewall policy6",
            "firewall policy46",
            "firewall policy64",
            "firewall interface-policy",
            "firewall interface-policy6",
            "firewall shaping-policy");

    private FortiGateConfigPolicyParser() {}

    public static FortiGatePolicyConfig parse(String showConfig) {
        return parse(showConfig, null);
    }

    public static FortiGatePolicyConfig parse(String showConfig, String softwareVersionHint) {
        String text = showConfig == null ? "" : showConfig;
        List<String> rawLines = splitLines(text);
        int totalLines = rawLines.size();

        String headerVersion = "";
        for (int i = 0; i < Math.min(rawLines.size(), 20); i++) {
            String t = rawLines.get(i).strip();
            if (t.startsWith("#config-version=")) {
                Matcher m = CONFIG_VERSION_PATTERN.matcher(t);
                if (m.find()) {
                    headerVersion = m.group(1);
                } else {
                    Matcher gm = GENERIC_VERSION_PATTERN.matcher(t);
                    if (gm.find()) {
                        headerVersion = gm.group(1);
                    }
                }
                break;
            }
        }

        String effectiveVersion = !headerVersion.isEmpty()
                ? headerVersion
                : extractVersionFromHint(softwareVersionHint);
        String minorVersion = toMinorVersion(effectiveVersion);

        boolean multiVdom = false;
        Map<String, Interface> globalInterfaces = new LinkedHashMap<>();
        Map<String, VdomBuilder> vdoms = new LinkedHashMap<>();

        Deque<String> stack = new ArrayDeque<>(); // innermost first
        String currentScope = "root";
        String activeTable = null;
        String activeSubTable = null;
        EntryBuffer activeEntry = null;
        EntryBuffer activeSubEntry = null;
        int unbalancedBlocks = 0;

        StringBuilder pendingQuotedLine = null;

        for (int idx = 0; idx < rawLines.size(); idx++) {
            String rawLine = rawLines.get(idx);

            if (pendingQuotedLine != null) {
                pendingQuotedLine.append('\n').append(rawLine);
                if ((unescapedQuotes(rawLine) & 1) != 0) {
                    String fullLine = pendingQuotedLine.toString();
                    pendingQuotedLine = null;
                    processStatement(
                            fullLine.strip(),
                            stack,
                            currentScope,
                            activeTable,
                            activeSubTable,
                            activeEntry,
                            activeSubEntry,
                            vdoms);
                }
                continue;
            }

            String t = rawLine.strip();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }

            if ((t.startsWith("set ") || t.startsWith("unset ")) && (unescapedQuotes(rawLine) & 1) != 0) {
                pendingQuotedLine = new StringBuilder(rawLine);
                continue;
            }

            List<String> w = tokenize(t);
            if (w.isEmpty()) {
                continue;
            }
            String kw = w.get(0);

            if ("config".equals(kw)) {
                String configName = w.size() > 1 ? String.join(" ", w.subList(1, w.size())) : "";
                if (stack.isEmpty() && "vdom".equals(configName)) {
                    multiVdom = true;
                } else if (stack.isEmpty() && "global".equals(configName)) {
                    currentScope = "global";
                }
                stack.push("config " + configName);

                int sectionDepth = sectionConfigDepth(stack);
                if (stack.size() == sectionDepth && !"vdom".equals(configName) && !"global".equals(configName)) {
                    activeTable = normalizeTableName(configName);
                    activeSubTable = null;
                    if (!"global".equals(currentScope)) {
                        vdoms.computeIfAbsent(currentScope, VdomBuilder::new);
                    }
                } else if ("system sdwan".equals(activeTable) && stack.size() == sectionDepth + 1) {
                    activeSubTable = configName;
                }
                continue;
            }

            if ("edit".equals(kw)) {
                String editName = w.size() > 1 ? String.join(" ", w.subList(1, w.size())) : "";
                if (stack.size() == 1 && "config vdom".equals(stack.peek())) {
                    currentScope = editName;
                    vdoms.computeIfAbsent(currentScope, VdomBuilder::new);
                }
                stack.push("edit " + editName);

                int entryDepth = sectionConfigDepth(stack) + 1;
                if (activeTable != null && stack.size() == entryDepth) {
                    activeEntry = new EntryBuffer(editName);
                } else if ("system sdwan".equals(activeTable)
                        && activeSubTable != null
                        && stack.size() == entryDepth + 1) {
                    activeSubEntry = new EntryBuffer(editName);
                }
                continue;
            }

            if ("next".equals(kw) || "end".equals(kw)) {
                if (stack.isEmpty()) {
                    unbalancedBlocks++;
                    continue;
                }
                int entryDepth = sectionConfigDepth(stack) + 1;

                if (activeSubEntry != null && stack.size() == entryDepth + 1 && stack.peek().startsWith("edit ")) {
                    VdomBuilder vb = vdoms.computeIfAbsent(currentScope, VdomBuilder::new);
                    finishSdwanSubEntry(vb, activeSubTable, activeSubEntry);
                    activeSubEntry = null;
                }

                if (activeEntry != null && stack.size() == entryDepth && stack.peek().startsWith("edit ")) {
                    finishTableEntry(
                            currentScope,
                            activeTable,
                            activeEntry,
                            globalInterfaces,
                            vdoms);
                    activeEntry = null;
                }

                boolean closesVdom = "end".equals(kw)
                        && stack.size() == 2
                        && "config vdom".equals(stack.peekLast())
                        && stack.peek().startsWith("edit ");
                String popped = stack.pop();
                if (!closesVdom && ("end".equals(kw) != popped.startsWith("config "))) {
                    unbalancedBlocks++;
                }
                if (closesVdom) {
                    stack.pop();
                }

                int newSectionDepth = sectionConfigDepth(stack);
                if (activeSubTable != null && stack.size() < newSectionDepth + 1) {
                    activeSubTable = null;
                    activeSubEntry = null;
                }
                if (activeTable != null && stack.size() < newSectionDepth) {
                    activeTable = null;
                    activeEntry = null;
                    activeSubTable = null;
                    activeSubEntry = null;
                }
                if (closesVdom || ("end".equals(kw) && stack.isEmpty())) {
                    activeTable = null;
                    activeEntry = null;
                    activeSubTable = null;
                    activeSubEntry = null;
                    currentScope = multiVdom ? "global" : "root";
                }
                continue;
            }

            processStatement(
                    t,
                    stack,
                    currentScope,
                    activeTable,
                    activeSubTable,
                    activeEntry,
                    activeSubEntry,
                    vdoms);
        }

        if (pendingQuotedLine != null || !stack.isEmpty()) {
            unbalancedBlocks++;
        }

        // Single-VDOM configurations without any VDOM wrapper still produce the default "root" VDOM.
        if (!multiVdom && vdoms.isEmpty()) {
            vdoms.put("root", new VdomBuilder("root"));
        }

        // Merge global interfaces into their assigned VDOM when not already overridden there.
        for (Interface gif : globalInterfaces.values()) {
            String targetVdom = (gif.vdom() != null && !gif.vdom().isEmpty()) ? gif.vdom() : (multiVdom ? null : "root");
            if (targetVdom != null && (vdoms.containsKey(targetVdom) || !multiVdom)) {
                VdomBuilder vb = vdoms.computeIfAbsent(targetVdom, VdomBuilder::new);
                vb.mergeInterface(gif);
            }
        }

        Map<String, VdomConfig> builtVdoms = new LinkedHashMap<>();
        int totalParsedPolicies = 0;
        int totalUnparsedPolicies = 0;
        int totalParsedAddresses = 0;
        int totalUnparsedAddresses = 0;
        int totalParsedServices = 0;
        int totalUnparsedServices = 0;
        int totalNonEvaluatedPolicies = 0;

        for (var e : vdoms.entrySet()) {
            VdomConfig vc = e.getValue().build();
            builtVdoms.put(e.getKey(), vc);
            VdomCounters c = vc.counters();
            totalParsedPolicies += c.parsedPolicies();
            totalUnparsedPolicies += c.unparsedPolicies();
            totalParsedAddresses += c.parsedAddresses();
            totalUnparsedAddresses += c.unparsedAddresses();
            totalParsedServices += c.parsedServices();
            totalUnparsedServices += c.unparsedServices();
            totalNonEvaluatedPolicies += c.nonEvaluatedPolicies();
        }

        Counters counters = new Counters(
                totalLines,
                unbalancedBlocks,
                totalParsedPolicies,
                totalUnparsedPolicies,
                totalParsedAddresses,
                totalUnparsedAddresses,
                totalParsedServices,
                totalUnparsedServices,
                totalNonEvaluatedPolicies);

        return new FortiGatePolicyConfig(
                effectiveVersion,
                minorVersion,
                multiVdom,
                globalInterfaces,
                builtVdoms,
                counters);
    }

    private static void processStatement(
            String trimmedLine,
            Deque<String> stack,
            String currentScope,
            String activeTable,
            String activeSubTable,
            EntryBuffer activeEntry,
            EntryBuffer activeSubEntry,
            Map<String, VdomBuilder> vdoms) {
        List<String> w = tokenize(trimmedLine);
        if (w.size() < 2) {
            return;
        }
        String cmd = w.get(0);
        String key = w.get(1);
        int sectionDepth = sectionConfigDepth(stack);
        int entryDepth = sectionDepth + 1;

        if ("set".equals(cmd)) {
            List<String> values = w.size() > 2 ? w.subList(2, w.size()) : List.of("");
            if (activeSubEntry != null && stack.size() == entryDepth + 1) {
                activeSubEntry.set(key, values);
            } else if (activeEntry != null && stack.size() == entryDepth) {
                activeEntry.set(key, values);
            } else if ("system settings".equals(activeTable) && stack.size() == sectionDepth && !"global".equals(currentScope)) {
                VdomBuilder vb = vdoms.computeIfAbsent(currentScope, VdomBuilder::new);
                String val = values.isEmpty() ? "" : values.get(0);
                switch (key) {
                    case "opmode" -> vb.opmode = val;
                    case "ngfw-mode" -> vb.ngfwMode = val;
                    case "central-nat" -> vb.centralNat = val;
                    case "inspection-mode" -> vb.inspectionMode = val;
                    default -> {}
                }
            }
        } else if ("unset".equals(cmd)) {
            if (activeSubEntry != null && stack.size() == entryDepth + 1) {
                activeSubEntry.unset(key);
            } else if (activeEntry != null && stack.size() == entryDepth) {
                activeEntry.unset(key);
            }
        }
    }

    private static void finishSdwanSubEntry(VdomBuilder vb, String subTable, EntryBuffer sub) {
        if ("zone".equals(subTable)) {
            vb.sdwanZoneMembers.computeIfAbsent(sub.name, k -> new LinkedHashSet<>());
        } else if ("members".equals(subTable)) {
            String iface = sub.scalar("interface");
            String zone = sub.scalar("zone");
            if (zone == null || zone.isEmpty()) {
                zone = "virtual-wan-link";
            }
            if (iface != null && !iface.isEmpty()) {
                vb.sdwanZoneMembers.computeIfAbsent(zone, k -> new LinkedHashSet<>()).add(iface);
            }
        }
    }

    private static void finishTableEntry(
            String currentScope,
            String table,
            EntryBuffer entry,
            Map<String, Interface> globalInterfaces,
            Map<String, VdomBuilder> vdoms) {
        if (table == null) return;

        if ("system interface".equals(table)) {
            Interface parsedIf = parseInterface(currentScope, entry);
            if ("global".equals(currentScope)) {
                globalInterfaces.put(parsedIf.name(), parsedIf);
                if (parsedIf.vdom() != null && !parsedIf.vdom().isEmpty()) {
                    vdoms.computeIfAbsent(parsedIf.vdom(), VdomBuilder::new).mergeInterface(parsedIf);
                }
            } else {
                String targetVdom = (parsedIf.vdom() != null && !parsedIf.vdom().isEmpty()) ? parsedIf.vdom() : currentScope;
                vdoms.computeIfAbsent(targetVdom, VdomBuilder::new).mergeInterface(parsedIf);
            }
            return;
        }

        if ("global".equals(currentScope)) {
            return;
        }

        VdomBuilder vb = vdoms.computeIfAbsent(currentScope, VdomBuilder::new);
        switch (table) {
            case "system zone" -> {
                Zone z = new Zone(
                        entry.name,
                        entry.list("interface"),
                        entry.scalar("intrazone"),
                        entry.scalar("description"));
                vb.zones.put(z.name(), z);
            }
            case "firewall address" -> {
                AddressObject addr = parseAddressObject(entry);
                vb.addresses.put(addr.name(), addr);
                if (addr.unparsed()) vb.unparsedAddresses++;
                else vb.parsedAddresses++;
            }
            case "firewall addrgrp" -> {
                AddressGroup grp = parseAddressGroup(entry);
                vb.addressGroups.put(grp.name(), grp);
            }
            case "firewall address6" -> {
                Address6Object a6 = new Address6Object(
                        entry.name,
                        entry.scalar("uuid"),
                        entry.scalar("type"),
                        entry.scalar("ip6"),
                        entry.scalar("start-ip"),
                        entry.scalar("end-ip"),
                        entry.comment());
                vb.addresses6.put(a6.name(), a6);
            }
            case "firewall addrgrp6" -> {
                AddressGroup6 g6 = new AddressGroup6(
                        entry.name,
                        entry.scalar("uuid"),
                        entry.list("member"),
                        entry.comment());
                vb.addressGroups6.put(g6.name(), g6);
            }
            case "firewall service custom" -> {
                ServiceObject svc = parseServiceObject(entry);
                vb.services.put(svc.name(), svc);
                if (svc.unparsed()) vb.unparsedServices++;
                else vb.parsedServices++;
            }
            case "firewall service group" -> {
                List<String> members = entry.list("member");
                ServiceGroup sg = new ServiceGroup(
                        entry.name,
                        entry.scalar("uuid"),
                        members,
                        entry.comment(),
                        members.isEmpty());
                vb.serviceGroups.put(sg.name(), sg);
            }
            case "firewall vip" -> {
                VipObject vip = parseVipObject(entry);
                vb.vips.put(vip.name(), vip);
            }
            case "firewall vipgrp" -> {
                VipGroup vg = new VipGroup(
                        entry.name,
                        entry.scalar("uuid"),
                        entry.scalar("interface"),
                        entry.list("member"),
                        entry.comment());
                vb.vipGroups.put(vg.name(), vg);
            }
            case "firewall ippool" -> {
                String startip = entry.scalar("startip");
                String endip = entry.scalar("endip");
                boolean unparsed = startip == null || endip == null || !isValidIpv4(startip) || !isValidIpv4(endip);
                IpPool pool = new IpPool(
                        entry.name,
                        entry.scalar("uuid"),
                        entry.scalar("type"),
                        startip,
                        endip,
                        entry.comment(),
                        unparsed);
                vb.ipPools.put(pool.name(), pool);
            }
            case "firewall central-snat-map" -> {
                CentralSnatRule snat = new CentralSnatRule(
                        entry.name,
                        entry.scalar("uuid"),
                        entry.scalar("status"),
                        entry.list("srcintf"),
                        entry.list("dstintf"),
                        entry.list("orig-addr"),
                        entry.list("dst-addr"),
                        entry.scalar("protocol"),
                        entry.scalar("orig-port"),
                        entry.scalar("nat"),
                        entry.list("nat-ippool"),
                        entry.scalar("nat-port"),
                        entry.comment());
                vb.centralSnatRules.add(snat);
            }
            case "firewall policy" -> {
                FirewallPolicy pol = parseFirewallPolicy(entry);
                vb.policies.add(pol);
                if (pol.unparsed()) vb.unparsedPolicies++;
                else vb.parsedPolicies++;
            }
            default -> {
                if (NON_EVALUATED_POLICY_TABLES.contains(table)) {
                    vb.nonEvaluatedPolicies.add(new NonEvaluatedPolicy(
                            table,
                            entry.name,
                            entry.scalar("uuid"),
                            entry.scalar("name"),
                            entry.comment()));
                    vb.nonEvaluatedCount++;
                }
            }
        }
    }

    private static Interface parseInterface(String currentScope, EntryBuffer entry) {
        String vdom = entry.scalar("vdom");
        if ((vdom == null || vdom.isEmpty()) && !"global".equals(currentScope)) {
            vdom = currentScope;
        }
        String ip = null;
        String mask = null;
        List<String> ipTokens = entry.list("ip");
        if (ipTokens.size() == 2) {
            ip = ipTokens.get(0);
            mask = ipTokens.get(1);
        } else if (ipTokens.size() == 1 && ipTokens.get(0).contains("/")) {
            String[] parts = ipTokens.get(0).split("/", 2);
            ip = parts[0];
            mask = prefixToDottedMask(parts[1]);
        }
        return new Interface(
                entry.name,
                vdom,
                ip,
                mask,
                entry.scalar("status"),
                entry.scalar("type"),
                entry.scalar("role"));
    }

    private static AddressObject parseAddressObject(EntryBuffer entry) {
        String type = entry.scalar("type");
        String subnetIp = null;
        String subnetMask = null;
        List<String> subnetTokens = entry.list("subnet");
        boolean unparsed = false;
        if (!subnetTokens.isEmpty()) {
            if (subnetTokens.size() == 2) {
                subnetIp = subnetTokens.get(0);
                subnetMask = subnetTokens.get(1);
            } else if (subnetTokens.size() == 1 && subnetTokens.get(0).contains("/")) {
                String[] parts = subnetTokens.get(0).split("/", 2);
                subnetIp = parts[0];
                subnetMask = prefixToDottedMask(parts[1]);
                if (subnetMask == null) unparsed = true;
            } else {
                unparsed = true;
            }
        }
        String wildcardIp = null;
        String wildcardMask = null;
        List<String> wcTokens = entry.list("wildcard");
        if (wcTokens.size() == 2) {
            wildcardIp = wcTokens.get(0);
            wildcardMask = wcTokens.get(1);
        }
        String iface = entry.scalar("interface");
        if (iface == null) {
            iface = entry.scalar("associated-interface");
        }
        return new AddressObject(
                entry.name,
                entry.scalar("uuid"),
                type,
                subnetIp,
                subnetMask,
                entry.scalar("start-ip"),
                entry.scalar("end-ip"),
                entry.scalar("fqdn"),
                entry.scalar("country"),
                wildcardIp,
                wildcardMask,
                iface,
                entry.scalar("route-tag"),
                entry.comment(),
                unparsed);
    }

    private static AddressGroup parseAddressGroup(EntryBuffer entry) {
        List<String> members = entry.list("member");
        return new AddressGroup(
                entry.name,
                entry.scalar("uuid"),
                members,
                entry.scalar("exclude"),
                entry.list("exclude-member"),
                entry.comment(),
                false);
    }

    private static ServiceObject parseServiceObject(EntryBuffer entry) {
        String protocol = entry.scalar("protocol");
        List<String> rawTcp = entry.list("tcp-portrange");
        List<String> rawUdp = entry.list("udp-portrange");
        List<String> rawSctp = entry.list("sctp-portrange");
        List<PortRange> tcpRanges = new ArrayList<>(rawTcp.size());
        List<PortRange> udpRanges = new ArrayList<>(rawUdp.size());
        boolean unparsed = false;

        for (String tok : rawTcp) {
            PortRange pr = parsePortRangeToken(tok);
            if (pr == null) {
                unparsed = true;
            } else {
                tcpRanges.add(pr);
            }
        }
        for (String tok : rawUdp) {
            PortRange pr = parsePortRangeToken(tok);
            if (pr == null) {
                unparsed = true;
            } else {
                udpRanges.add(pr);
            }
        }

        boolean hasAddressRestriction = entry.has("iprange") || entry.has("fqdn");
        return new ServiceObject(
                entry.name,
                entry.scalar("uuid"),
                protocol,
                tcpRanges,
                udpRanges,
                rawSctp,
                entry.scalar("icmptype"),
                entry.scalar("icmpcode"),
                entry.scalar("protocol-number"),
                hasAddressRestriction,
                entry.comment(),
                unparsed);
    }

    /**
     * Parses one FortiOS port-range token (A0-FG-26; measurement section 3):
     * {@code dst}, {@code dstStart-dstEnd}, or {@code dst[-dstEnd]:src[-srcEnd]}.
     */
    static PortRange parsePortRangeToken(String token) {
        if (token == null || token.isEmpty()) return null;
        String dstPart = token;
        String srcPart = null;
        int colon = token.indexOf(':');
        if (colon >= 0) {
            if (token.indexOf(':', colon + 1) >= 0) return null;
            dstPart = token.substring(0, colon);
            srcPart = token.substring(colon + 1);
            if (srcPart.isEmpty()) return null;
        }
        int[] dst = parseSingleOrRangePort(dstPart);
        if (dst == null) return null;
        if (srcPart == null) {
            return new PortRange(dst[0], dst[1], null, null);
        }
        int[] src = parseSingleOrRangePort(srcPart);
        if (src == null) return null;
        return new PortRange(dst[0], dst[1], src[0], src[1]);
    }

    private static int[] parseSingleOrRangePort(String s) {
        if (s == null || s.isEmpty()) return null;
        int dash = s.indexOf('-');
        if (dash < 0) {
            Integer p = parsePortNumber(s);
            return p == null ? null : new int[] {p, p};
        }
        if (s.indexOf('-', dash + 1) >= 0) return null;
        Integer a = parsePortNumber(s.substring(0, dash));
        Integer b = parsePortNumber(s.substring(dash + 1));
        if (a == null || b == null || a > b) return null;
        return new int[] {a, b};
    }

    static Integer parsePortNumber(String s) {
        if (s == null || s.isEmpty() || s.length() > 5) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        int v = Integer.parseInt(s);
        return (v >= 0 && v <= 65535) ? v : null;
    }

    private static VipObject parseVipObject(EntryBuffer entry) {
        String extip = entry.scalar("extip");
        List<String> mappedip = entry.list("mappedip");
        boolean unparsed = extip == null || extip.isEmpty() || mappedip.isEmpty();
        return new VipObject(
                entry.name,
                entry.scalar("uuid"),
                entry.scalar("type"),
                extip,
                mappedip,
                entry.scalar("extintf"),
                entry.scalar("portforward"),
                entry.scalar("protocol"),
                entry.scalar("extport"),
                entry.scalar("mappedport"),
                entry.comment(),
                unparsed);
    }

    private static FirewallPolicy parseFirewallPolicy(EntryBuffer entry) {
        boolean unparsed = entry.name == null || entry.name.isEmpty();
        List<String> isdbNames = new ArrayList<>();
        for (String k : List.of(
                "internet-service-name",
                "internet-service-id",
                "internet-service-group",
                "internet-service-custom",
                "internet-service-custom-group")) {
            isdbNames.addAll(entry.list(k));
        }
        List<String> isdbSrcNames = new ArrayList<>();
        for (String k : List.of(
                "internet-service-src-name",
                "internet-service-src-id",
                "internet-service-src-group",
                "internet-service-src-custom",
                "internet-service-src-custom-group")) {
            isdbSrcNames.addAll(entry.list(k));
        }

        return new FirewallPolicy(
                entry.name,
                entry.scalar("uuid"),
                entry.scalar("name"),
                entry.scalar("status"),
                entry.scalar("action"),
                entry.list("srcintf"),
                entry.list("dstintf"),
                entry.list("srcaddr"),
                entry.list("dstaddr"),
                entry.list("srcaddr6"),
                entry.list("dstaddr6"),
                entry.scalar("srcaddr-negate"),
                entry.scalar("dstaddr-negate"),
                entry.list("service"),
                entry.scalar("service-negate"),
                entry.scalar("schedule"),
                entry.scalar("logtraffic"),
                entry.comment(),
                entry.list("users"),
                entry.list("groups"),
                entry.list("fsso-groups"),
                entry.scalar("internet-service"),
                isdbNames,
                entry.scalar("internet-service-src"),
                isdbSrcNames,
                entry.list("application"),
                entry.scalar("application-list"),
                entry.list("app-category"),
                entry.list("app-group"),
                entry.list("url-category"),
                entry.scalar("nat"),
                entry.scalar("ippool"),
                entry.list("poolname"),
                entry.scalar("match-vip"),
                entry.scalar("match-vip-only"),
                entry.scalar("vpntunnel"),
                unparsed);
    }

    private static int sectionConfigDepth(Deque<String> stack) {
        if (stack.isEmpty()) return 1;
        String outermost = stack.peekLast();
        if ("config vdom".equals(outermost)) return 3;
        if ("config global".equals(outermost)) return 2;
        return 1;
    }

    private static String normalizeTableName(String raw) {
        if ("firewall DoS-policy".equals(raw)) return "firewall dos-policy";
        if ("firewall DoS-policy6".equals(raw)) return "firewall dos-policy6";
        if ("system virtual-wan-link".equals(raw)) return "system sdwan";
        return raw;
    }

    private static String extractVersionFromHint(String hint) {
        if (hint == null || hint.isBlank()) return "";
        Matcher m = GENERIC_VERSION_PATTERN.matcher(hint.strip());
        return m.find() ? m.group(1) : "";
    }

    static String toMinorVersion(String version) {
        if (version == null || version.isBlank()) return "";
        String[] parts = version.strip().split("\\.");
        if (parts.length >= 2 && isDigits(parts[0]) && isDigits(parts[1])) {
            return parts[0] + "." + parts[1];
        }
        return "";
    }

    private static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    static String prefixToDottedMask(String prefixStr) {
        if (!isDigits(prefixStr) || prefixStr.length() > 2) return null;
        int prefix = Integer.parseInt(prefixStr);
        if (prefix < 0 || prefix > 32) return null;
        long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return ((mask >>> 24) & 0xFF) + "."
                + ((mask >>> 16) & 0xFF) + "."
                + ((mask >>> 8) & 0xFF) + "."
                + (mask & 0xFF);
    }

    public static boolean isValidIpv4(String s) {
        if (s == null || s.length() < 7 || s.length() > 15) return false;
        int dots = 0;
        int octetLen = 0;
        int octetVal = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') {
                if (octetLen == 0 || octetVal > 255) return false;
                dots++;
                if (dots > 3) return false;
                octetLen = 0;
                octetVal = 0;
            } else if (c >= '0' && c <= '9') {
                if (octetLen > 0 && octetVal == 0) return false;
                octetVal = octetVal * 10 + (c - '0');
                octetLen++;
                if (octetLen > 3) return false;
            } else {
                return false;
            }
        }
        return dots == 3 && octetLen > 0 && octetVal <= 255;
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                lines.add(text.substring(start, i));
                if (c == '\r' && i + 1 < len && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < len) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    static int unescapedQuotes(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                count++;
            }
        }
        return count;
    }

    static List<String> tokenize(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        boolean hasToken = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '\\' && i + 1 < line.length()) {
                cur.append(line.charAt(++i));
                hasToken = true;
                continue;
            }
            if (ch == '"') {
                inQuotes = !inQuotes;
                hasToken = true;
                continue;
            }
            if (!inQuotes && Character.isWhitespace(ch)) {
                if (hasToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    hasToken = false;
                }
                continue;
            }
            cur.append(ch);
            hasToken = true;
        }
        if (hasToken) {
            out.add(cur.toString());
        }
        return out;
    }

    private static final class EntryBuffer {
        final String name;
        final Map<String, List<String>> fields = new LinkedHashMap<>();

        EntryBuffer(String name) {
            this.name = name;
        }

        void set(String key, List<String> values) {
            fields.put(key, List.copyOf(values));
        }

        void unset(String key) {
            fields.remove(key);
        }

        boolean has(String key) {
            return fields.containsKey(key);
        }

        String scalar(String key) {
            List<String> v = fields.get(key);
            return (v == null || v.isEmpty()) ? null : v.get(0);
        }

        List<String> list(String key) {
            return fields.getOrDefault(key, List.of());
        }

        String comment() {
            String c = scalar("comments");
            if (c == null) c = scalar("comment");
            return c;
        }
    }

    private static final class VdomBuilder {
        final String name;
        String opmode;
        String ngfwMode;
        String centralNat;
        String inspectionMode;
        final Map<String, Interface> interfaces = new LinkedHashMap<>();
        final Map<String, Zone> zones = new LinkedHashMap<>();
        final Map<String, Set<String>> sdwanZoneMembers = new LinkedHashMap<>();
        final Map<String, AddressObject> addresses = new LinkedHashMap<>();
        final Map<String, AddressGroup> addressGroups = new LinkedHashMap<>();
        final Map<String, Address6Object> addresses6 = new LinkedHashMap<>();
        final Map<String, AddressGroup6> addressGroups6 = new LinkedHashMap<>();
        final Map<String, ServiceObject> services = new LinkedHashMap<>();
        final Map<String, ServiceGroup> serviceGroups = new LinkedHashMap<>();
        final Map<String, VipObject> vips = new LinkedHashMap<>();
        final Map<String, VipGroup> vipGroups = new LinkedHashMap<>();
        final Map<String, IpPool> ipPools = new LinkedHashMap<>();
        final List<CentralSnatRule> centralSnatRules = new ArrayList<>();
        final List<FirewallPolicy> policies = new ArrayList<>();
        final List<NonEvaluatedPolicy> nonEvaluatedPolicies = new ArrayList<>();

        int parsedPolicies;
        int unparsedPolicies;
        int parsedAddresses;
        int unparsedAddresses;
        int parsedServices;
        int unparsedServices;
        int nonEvaluatedCount;

        VdomBuilder(String name) {
            this.name = name;
        }

        void mergeInterface(Interface incoming) {
            Interface existing = interfaces.get(incoming.name());
            if (existing == null) {
                interfaces.put(incoming.name(), incoming);
                return;
            }
            interfaces.put(
                    incoming.name(),
                    new Interface(
                            incoming.name(),
                            incoming.vdom() != null ? incoming.vdom() : existing.vdom(),
                            incoming.ip() != null ? incoming.ip() : existing.ip(),
                            incoming.mask() != null ? incoming.mask() : existing.mask(),
                            incoming.status() != null ? incoming.status() : existing.status(),
                            incoming.type() != null ? incoming.type() : existing.type(),
                            incoming.role() != null ? incoming.role() : existing.role()));
        }

        VdomConfig build() {
            Map<String, SdwanZone> builtSdwan = new LinkedHashMap<>();
            for (var e : sdwanZoneMembers.entrySet()) {
                builtSdwan.put(e.getKey(), new SdwanZone(e.getKey(), List.copyOf(e.getValue())));
            }
            return new VdomConfig(
                    name,
                    new VdomSettings(opmode, ngfwMode, centralNat, inspectionMode),
                    interfaces,
                    zones,
                    builtSdwan,
                    addresses,
                    addressGroups,
                    addresses6,
                    addressGroups6,
                    services,
                    serviceGroups,
                    vips,
                    vipGroups,
                    ipPools,
                    centralSnatRules,
                    policies,
                    nonEvaluatedPolicies,
                    new VdomCounters(
                            parsedPolicies,
                            unparsedPolicies,
                            parsedAddresses,
                            unparsedAddresses,
                            parsedServices,
                            unparsedServices,
                            nonEvaluatedCount));
        }
    }
}
