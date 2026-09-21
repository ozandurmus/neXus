package com.securityexpert.nexus.ui2.worker.inventory.pan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.worker.inventory.ParsedAddress;
import com.securityexpert.nexus.ui2.worker.inventory.ParsedInterface;

/**
 * {@code show interface all} (14E PF-1), {@code result/ifnet/entry} and
 * {@code result/hw/entry} (14E PP-1). Successor to the pre-measurement
 * parser: reads {@code ip} (primary, an {@code a.b.c.d/len} token or a
 * text token such as {@code N/A}) plus <b>every child</b> of {@code addr}
 * and {@code addr6} (secondary IPv4 and IPv6 addresses, as {@code
 * <entry name="...">} members -- the standard PAN-OS XML API named-list
 * idiom), {@code tag} (VLAN id), {@code vsys}, {@code fwd} (the virtual
 * router name, {@code vr:} prefix stripped). An interface with no address
 * element at all is still kept, address-less (PP-1).
 *
 * <p>{@code hw/entry} rows are the box's physical ports (PM-2): kept
 * separately from the logical {@code ifnet} interfaces, context {@code
 * physical}, kind {@link InventoryInterface#KIND_PHYSICAL}, no address,
 * state from the port's own {@code state} leaf.</p>
 */
public final class PaloAltoInterfaceParser {

    /** Fallback only -- used when an {@code ifnet/entry} carries no {@code vsys} leaf at all (PP-1 does not measure this case). */
    public static final String DEFAULT_VSYS = "1";
    private static final String VR_PREFIX = "vr:";

    private static final Pattern NAME = tag("name");
    private static final Pattern VSYS = tag("vsys");
    private static final Pattern STATE = tag("state");
    private static final Pattern IP = tag("ip");
    private static final Pattern TAG = tag("tag");
    private static final Pattern FWD = tag("fwd");
    private static final Pattern ADDR_BLOCK = Pattern.compile("(?is)<addr>(.*?)</addr>");
    private static final Pattern ADDR6_BLOCK = Pattern.compile("(?is)<addr6>(.*?)</addr6>");
    private static final Pattern ENTRY_NAME_ATTR = Pattern.compile("(?is)<entry\\s+name=\"([^\"]*)\"\\s*/?>");

    private PaloAltoInterfaceParser() {
    }

    private static Pattern tag(String name) {
        return Pattern.compile("(?is)<" + name + ">\\s*([^<]*?)\\s*</" + name + ">");
    }

    public static PaloAltoInterfaceParseResult parse(String responseBody) {
        Map<String, List<ParsedInterface>> byVsys = new LinkedHashMap<>();
        List<ParsedInterface> physicalPorts = new ArrayList<>();
        Map<String, String> interfaceNameToVsys = new LinkedHashMap<>();
        Map<String, String> interfaceNameToVirtualRouter = new LinkedHashMap<>();

        if (responseBody != null && !responseBody.isBlank()) {
            for (String entryBody : topLevelEntries(responseBody, "ifnet")) {
                String name = firstMatch(NAME, entryBody).orElse(null);
                if (name == null) {
                    continue;
                }
                String vsys = firstMatch(VSYS, entryBody).filter(v -> !v.isBlank()).orElse(DEFAULT_VSYS);
                List<ParsedAddress> addresses = primaryAndSecondaryAddresses(entryBody);
                String state = stateOf(entryBody);
                Optional<Integer> vlanId = firstMatch(TAG, entryBody).flatMap(PaloAltoInterfaceParser::parseVlanId);
                Optional<String> parent = parentOf(name);
                String kind = kindOf(name);

                byVsys.computeIfAbsent(vsys, key -> new ArrayList<>())
                        .add(new ParsedInterface(name, parent, kind, state, addresses, vlanId));
                interfaceNameToVsys.put(name, vsys);
                firstMatch(FWD, entryBody)
                        .map(String::trim)
                        .filter(v -> !v.isBlank() && v.startsWith(VR_PREFIX))
                        .map(v -> v.substring(VR_PREFIX.length()).trim())
                        .filter(vr -> !vr.isBlank() && !"N/A".equalsIgnoreCase(vr))
                        .ifPresent(vr -> interfaceNameToVirtualRouter.put(name, vr));
            }

            for (String hwBody : topLevelEntries(responseBody, "hw")) {
                String name = firstMatch(NAME, hwBody).orElse(null);
                if (name == null) {
                    continue;
                }
                String state = stateOf(hwBody);
                physicalPorts.add(new ParsedInterface(name, Optional.empty(), InventoryInterface.KIND_PHYSICAL, state,
                        List.of(), Optional.empty()));
            }

            // Product Owner correction (2026-09-21): a logical/VLAN subinterface's own ifnet entry
            // never carries its own <state> leaf (only the physical port in <hw> does), and a
            // subinterface cannot be up while its physical port is down -- so an ifnet entry whose
            // own state is unknown inherits its parent physical port's state (name before the first
            // "."), never guessed beyond what that port itself reports.
            Map<String, String> physicalStateByName = new LinkedHashMap<>();
            for (ParsedInterface port : physicalPorts) {
                physicalStateByName.put(port.name(), port.state());
            }
            for (Map.Entry<String, List<ParsedInterface>> vsysEntry : byVsys.entrySet()) {
                List<ParsedInterface> inherited = vsysEntry.getValue().stream()
                        .map(iface -> {
                            if (!InventoryInterface.STATE_UNKNOWN.equals(iface.state())) {
                                return iface;
                            }
                            String parentName = iface.parent().orElse(iface.name());
                            String parentState = physicalStateByName.get(parentName);
                            if (parentState == null || InventoryInterface.STATE_UNKNOWN.equals(parentState)) {
                                return iface;
                            }
                            return new ParsedInterface(iface.name(), iface.parent(), iface.kind(), parentState,
                                    iface.addresses(), iface.vlanId());
                        })
                        .toList();
                vsysEntry.setValue(inherited);
            }
        }

        return new PaloAltoInterfaceParseResult(byVsys, physicalPorts, interfaceNameToVsys, interfaceNameToVirtualRouter);
    }

    private static List<ParsedAddress> primaryAndSecondaryAddresses(String entryBody) {
        List<ParsedAddress> addresses = new ArrayList<>();
        firstMatch(IP, entryBody).filter(ip -> !ip.isBlank() && !"N/A".equalsIgnoreCase(ip))
                .ifPresent(ip -> addresses.add(new ParsedAddress(ip, InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER)));

        Matcher addrBlock = ADDR_BLOCK.matcher(entryBody);
        if (addrBlock.find()) {
            for (String member : entryNameMembers(addrBlock.group(1))) {
                addresses.add(new ParsedAddress(member, InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER));
            }
        }
        Matcher addr6Block = ADDR6_BLOCK.matcher(entryBody);
        if (addr6Block.find()) {
            for (String member : entryNameMembers(addr6Block.group(1))) {
                addresses.add(new ParsedAddress(member, InventoryAddress.FAMILY_IPV6, InventoryAddress.ROLE_MEMBER));
            }
        }
        return addresses;
    }

    /** Every {@code <entry name="...">} child of an {@code addr}/{@code addr6} block (PP-1: "every child"). */
    private static List<String> entryNameMembers(String blockBody) {
        List<String> result = new ArrayList<>();
        Matcher matcher = ENTRY_NAME_ATTR.matcher(blockBody);
        while (matcher.find()) {
            String value = matcher.group(1).trim();
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    private static String stateOf(String entryBody) {
        return firstMatch(STATE, entryBody)
                .map(s -> switch (s.toLowerCase(java.util.Locale.ROOT)) {
                    case "up" -> InventoryInterface.STATE_UP;
                    case "down" -> InventoryInterface.STATE_DOWN;
                    default -> InventoryInterface.STATE_UNKNOWN;
                })
                .orElse(InventoryInterface.STATE_UNKNOWN);
    }

    private static Optional<Integer> parseVlanId(String tagValue) {
        try {
            return Optional.of(Integer.parseInt(tagValue.trim()));
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    private static String stripVrPrefix(String fwd) {
        return fwd.startsWith(VR_PREFIX) ? fwd.substring(VR_PREFIX.length()) : fwd;
    }

    /**
     * The named top-level container's ({@code <ifnet>} or {@code <hw>})
     * own top-level {@code <entry>} elements only -- a naive non-greedy
     * {@code <entry>(.*?)</entry>} regex mis-splits on a nested {@code
     * <addr>}/{@code <addr6>} block's own {@code <entry name="...">}
     * members, so this walks the text tracking open/close depth instead.
     */
    private static List<String> topLevelEntries(String responseBody, String containerTag) {
        List<String> result = new ArrayList<>();
        Matcher container = Pattern.compile("(?is)<" + containerTag + ">(.*?)</" + containerTag + ">").matcher(responseBody);
        if (!container.find()) {
            return result;
        }
        String body = container.group(1);
        int i = 0;
        while (true) {
            int start = body.indexOf("<entry>", i);
            if (start < 0) {
                break;
            }
            int depth = 1;
            int pos = start + "<entry>".length();
            int contentStart = pos;
            while (depth > 0) {
                int nextOpen = body.indexOf("<entry>", pos);
                int nextClose = body.indexOf("</entry>", pos);
                if (nextClose < 0) {
                    pos = body.length();
                    depth = 0;
                    break;
                }
                if (nextOpen >= 0 && nextOpen < nextClose) {
                    depth++;
                    pos = nextOpen + "<entry>".length();
                } else {
                    depth--;
                    pos = nextClose + "</entry>".length();
                }
            }
            int contentEnd = Math.max(contentStart, pos - "</entry>".length());
            result.add(body.substring(contentStart, contentEnd));
            i = pos;
        }
        return result;
    }

    private static Optional<String> parentOf(String name) {
        int dot = name.indexOf('.');
        return dot < 0 ? Optional.empty() : Optional.of(name.substring(0, dot));
    }

    private static String kindOf(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("loopback")) {
            return InventoryInterface.KIND_LOOPBACK;
        }
        if (lower.startsWith("tunnel")) {
            return InventoryInterface.KIND_TUNNEL;
        }
        if (lower.startsWith("ae")) {
            return InventoryInterface.KIND_BOND;
        }
        if (lower.startsWith("ha")) {
            return InventoryInterface.KIND_OTHER;
        }
        if (name.contains(".")) {
            return InventoryInterface.KIND_SUBINTERFACE;
        }
        if (lower.startsWith("ethernet")) {
            return InventoryInterface.KIND_PHYSICAL;
        }
        return InventoryInterface.KIND_OTHER;
    }

    private static Optional<String> firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1).trim()) : Optional.empty();
    }
}
