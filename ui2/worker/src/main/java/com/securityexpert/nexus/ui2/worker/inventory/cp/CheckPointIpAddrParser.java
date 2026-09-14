package com.securityexpert.nexus.ui2.worker.inventory.cp;

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
 * {@code ip -details -4 addr show} / {@code ip -6 addr show} (14C §3).
 * {@code UNVERIFIED} against a real gateway (channel differs -- 14C §3:
 * "proven payload, channel differs"). A pure function from the two reads'
 * output text to {@link ParsedInterface} data; every address on this read
 * is {@link InventoryAddress#ROLE_MEMBER} -- a cluster virtual address is
 * only ever read from {@link CheckPointClusterVirtualInterfaceParser}, and
 * the two are never merged in this class (14C §3: "the two never mix").
 *
 * <p>14C §4's named correction: "UP must be read from the flag list inside
 * {@code <...>}, not by substring" -- {@link #isUp} checks the flag list
 * for the exact token {@code "UP"}, never a substring match against the
 * whole header line (which would also match {@code LOWER_UP}).</p>
 */
public final class CheckPointIpAddrParser {

    // "1: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP" -- group 1 is the raw name
    // (possibly "name@parent"), group 2 the flag list inside <...>.
    private static final Pattern HEADER = Pattern.compile("^\\d+:\\s*([^:]+):\\s*<([^>]*)>");
    private static final Pattern INET4_LINE = Pattern.compile("^\\s*inet\\s+(\\S+)");
    private static final Pattern INET6_LINE = Pattern.compile("^\\s*inet6\\s+(\\S+)");
    private static final Pattern VLAN_NAME = Pattern.compile("^.+\\.\\d+$");

    private CheckPointIpAddrParser() {
    }

    public static List<ParsedInterface> parse(String ipv4Output, String ipv6Output) {
        Map<String, Builder> byName = new LinkedHashMap<>();
        parseInto(byName, ipv4Output, INET4_LINE, InventoryAddress.FAMILY_IPV4, true);
        parseInto(byName, ipv6Output, INET6_LINE, InventoryAddress.FAMILY_IPV6, false);
        return byName.values().stream().map(Builder::build).toList();
    }

    private static void parseInto(Map<String, Builder> byName, String output, Pattern addressLine, String family,
            boolean recordMetadata) {
        if (output == null || output.isBlank()) {
            return;
        }
        Builder current = null;
        for (String line : output.split("\\R")) {
            Matcher header = HEADER.matcher(line);
            if (header.find()) {
                String rawName = header.group(1).trim();
                String[] nameAndParent = rawName.split("@", 2);
                String name = nameAndParent[0];
                Optional<String> parent = nameAndParent.length > 1 ? Optional.of(nameAndParent[1]) : Optional.empty();
                current = byName.computeIfAbsent(name, key -> new Builder(name));
                if (recordMetadata) {
                    List<String> flags = List.of(header.group(2).split(","));
                    current.parent = parent;
                    current.kind = kindOf(name, parent);
                    current.state = isUp(flags) ? InventoryInterface.STATE_UP : InventoryInterface.STATE_DOWN;
                }
                continue;
            }
            if (current == null) {
                continue;
            }
            Matcher address = addressLine.matcher(line);
            if (address.find()) {
                current.addresses.add(new ParsedAddress(address.group(1), family, InventoryAddress.ROLE_MEMBER));
            }
        }
    }

    private static boolean isUp(List<String> flags) {
        return flags.stream().map(String::trim).anyMatch("UP"::equals);
    }

    private static String kindOf(String name, Optional<String> parent) {
        if ("lo".equals(name)) {
            return InventoryInterface.KIND_LOOPBACK;
        }
        if (name.startsWith("bond")) {
            return InventoryInterface.KIND_BOND;
        }
        if (name.startsWith("tun") || name.startsWith("vti")) {
            return InventoryInterface.KIND_TUNNEL;
        }
        if (VLAN_NAME.matcher(name).matches()) {
            return InventoryInterface.KIND_VLAN;
        }
        if (parent.isPresent()) {
            return InventoryInterface.KIND_SUBINTERFACE;
        }
        if (name.matches("^[a-zA-Z]+\\d+$") || name.matches("^eth\\d+$")) {
            return InventoryInterface.KIND_PHYSICAL;
        }
        return InventoryInterface.KIND_OTHER;
    }

    private static final class Builder {
        final String name;
        Optional<String> parent = Optional.empty();
        String kind = InventoryInterface.KIND_OTHER;
        String state = InventoryInterface.STATE_UNKNOWN;
        final List<ParsedAddress> addresses = new java.util.ArrayList<>();

        Builder(String name) {
            this.name = name;
        }

        ParsedInterface build() {
            return new ParsedInterface(name, parent, kind, state, List.copyOf(addresses));
        }
    }
}
