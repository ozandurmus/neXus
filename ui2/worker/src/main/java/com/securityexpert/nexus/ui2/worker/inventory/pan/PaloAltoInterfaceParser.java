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
 * {@code show interface all} (14C §3), {@code result/ifnet/entry} (14C §4
 * baseline: leaves {@code name}, {@code ip}, {@code vsys}, {@code zone},
 * {@code fwd}). {@code UNVERIFIED} -- "payload written, shape unproven"
 * (14C §3). Reads <b>every</b> {@code <ip>} element under an entry, not
 * just the first (14C §4's named correction over the earlier product's
 * "one address per Palo Alto interface"), plus any {@code <ipv6>} block's
 * own {@code <addr>} elements; an interface with no address element at all
 * (an HA link, {@code ip} literally {@code N/A}) is still kept, address-
 * less (14C §3).
 *
 * <p>Grouped by the entry's own {@code <vsys>} leaf -- the single
 * unscoped call already differentiates vsys per entry ({@code
 * tests/fixtures/panorama/interfaces.xml} shows this shape); an entry
 * with no {@code <vsys>} leaf falls under {@link #DEFAULT_VSYS}.</p>
 */
public final class PaloAltoInterfaceParser {

    public static final String DEFAULT_VSYS = "vsys1";

    private static final Pattern NAME = tag("name");
    private static final Pattern VSYS = tag("vsys");
    private static final Pattern STATE = tag("state");
    private static final Pattern IP = tag("ip");
    private static final Pattern IPV6_BLOCK = Pattern.compile("(?is)<ipv6>(.*?)</ipv6>");
    private static final Pattern IPV6_ADDR = tag("addr");

    private PaloAltoInterfaceParser() {
    }

    private static Pattern tag(String name) {
        return Pattern.compile("(?is)<" + name + ">\\s*([^<]*?)\\s*</" + name + ">");
    }

    /** @return every interface, grouped by vsys id (the run's context) -- iteration order preserves first-seen vsys. */
    public static Map<String, List<ParsedInterface>> parse(String responseBody) {
        Map<String, List<ParsedInterface>> byVsys = new LinkedHashMap<>();
        if (responseBody == null || responseBody.isBlank()) {
            return byVsys;
        }
        for (String entryBody : topLevelEntries(responseBody)) {
            String name = firstMatch(NAME, entryBody).orElse(null);
            if (name == null) {
                continue;
            }
            String vsys = firstMatch(VSYS, entryBody).filter(v -> !v.isBlank()).orElse(DEFAULT_VSYS);
            List<ParsedAddress> addresses = new ArrayList<>();
            Matcher ipMatcher = IP.matcher(entryBody);
            while (ipMatcher.find()) {
                String ip = ipMatcher.group(1).trim();
                if (!ip.isEmpty() && !"N/A".equalsIgnoreCase(ip)) {
                    addresses.add(new ParsedAddress(ip, InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER));
                }
            }
            Matcher ipv6Block = IPV6_BLOCK.matcher(entryBody);
            if (ipv6Block.find()) {
                Matcher addrMatcher = IPV6_ADDR.matcher(ipv6Block.group(1));
                while (addrMatcher.find()) {
                    String addr = addrMatcher.group(1).trim();
                    if (!addr.isEmpty()) {
                        addresses.add(new ParsedAddress(addr, InventoryAddress.FAMILY_IPV6, InventoryAddress.ROLE_MEMBER));
                    }
                }
            }

            String state = firstMatch(STATE, entryBody)
                    .map(s -> switch (s.toLowerCase(java.util.Locale.ROOT)) {
                        case "up" -> InventoryInterface.STATE_UP;
                        case "down" -> InventoryInterface.STATE_DOWN;
                        default -> InventoryInterface.STATE_UNKNOWN;
                    })
                    .orElse(InventoryInterface.STATE_UNKNOWN);

            Optional<String> parent = parentOf(name);
            String kind = kindOf(name);

            byVsys.computeIfAbsent(vsys, key -> new ArrayList<>())
                    .add(new ParsedInterface(name, parent, kind, state, addresses));
        }
        return byVsys;
    }

    /**
     * The {@code <ifnet>} block's own top-level {@code <entry>} elements only -- a naive
     * non-greedy {@code <entry>(.*?)</entry>} regex mis-splits on the {@code <ipv6><entry>...
     * </entry></ipv6>} nested entry an address-bearing interface may carry, so this walks the
     * text tracking open/close depth instead.
     */
    private static List<String> topLevelEntries(String responseBody) {
        List<String> result = new ArrayList<>();
        Matcher ifnet = Pattern.compile("(?is)<ifnet>(.*?)</ifnet>").matcher(responseBody);
        if (!ifnet.find()) {
            return result;
        }
        String body = ifnet.group(1);
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
