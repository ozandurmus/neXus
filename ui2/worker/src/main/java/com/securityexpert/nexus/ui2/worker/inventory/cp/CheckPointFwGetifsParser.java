package com.securityexpert.nexus.ui2.worker.inventory.cp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.worker.inventory.ParsedAddress;
import com.securityexpert.nexus.ui2.worker.inventory.ParsedInterface;

/**
 * {@code fw getifs} (Product Owner correction, 2026-09-21, replacing {@code
 * ip -4 addr show}/{@code ip -details -4 addr show} for Check Point
 * interface reads on both a physical member and, prefixed with {@code
 * vsenv <VSID> &&}, a virtual system). Each line is {@code localhost <name>
 * <ip> <dotted-netmask>}; an interface with no address does not appear in
 * this output at all (measured live: {@code fw getifs} inside an unrouted
 * VS printed nothing for it, while {@code cpstat -f interfaces fw} still
 * listed it with {@code 0.0.0.0}) -- so this parser only ever produces
 * interfaces that have an address, and never reports {@code up}/{@code
 * down} state, since {@code fw getifs} carries no flag/state column at
 * all: state stays {@link InventoryInterface#STATE_UNKNOWN} rather than
 * guessed (UNKNOWN/fail-closed law).
 */
public final class CheckPointFwGetifsParser {

    private static final Pattern LINE = Pattern.compile(
            "^\\S+\\s+(\\S+)\\s+(\\d{1,3}(?:\\.\\d{1,3}){3})\\s+(\\d{1,3}(?:\\.\\d{1,3}){3})\\s*$");
    private static final Pattern VLAN_NAME = Pattern.compile("^.+\\.\\d+$");

    private CheckPointFwGetifsParser() {
    }

    public static List<ParsedInterface> parse(String output) {
        List<ParsedInterface> interfaces = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return interfaces;
        }
        for (String line : output.split("\\R")) {
            Matcher matcher = LINE.matcher(line.trim());
            if (!matcher.matches()) {
                continue;
            }
            String name = matcher.group(1);
            String ip = matcher.group(2);
            String netmask = matcher.group(3);
            Optional<Integer> prefixLength = prefixLength(netmask);
            if (prefixLength.isEmpty()) {
                continue;
            }
            String cidr = ip + "/" + prefixLength.get();
            ParsedAddress address = new ParsedAddress(cidr, InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER);
            interfaces.add(new ParsedInterface(name, Optional.empty(), kindOf(name), InventoryInterface.STATE_UNKNOWN,
                    List.of(address), Optional.empty()));
        }
        return interfaces;
    }

    private static Optional<Integer> prefixLength(String dottedMask) {
        String[] octets = dottedMask.split("\\.");
        if (octets.length != 4) {
            return Optional.empty();
        }
        int bits = 0;
        for (String octet : octets) {
            int value = Integer.parseInt(octet);
            if (value < 0 || value > 255) {
                return Optional.empty();
            }
            bits += Integer.bitCount(value);
        }
        return Optional.of(bits);
    }

    /** Exposed for {@code CheckPointClusterVirtualInterfaceParser}-sourced interfaces (cluster-member VSX
     * contexts), which classify by the same bare interface name but never call this class's own {@link
     * #parse}. */
    public static String kindOf(String name) {
        if ("lo".equals(name)) {
            return InventoryInterface.KIND_LOOPBACK;
        }
        if (VLAN_NAME.matcher(name).matches()) {
            return InventoryInterface.KIND_VLAN;
        }
        if (name.startsWith("bond")) {
            return InventoryInterface.KIND_BOND;
        }
        if (name.startsWith("tun") || name.startsWith("vti")) {
            return InventoryInterface.KIND_TUNNEL;
        }
        if (name.matches("^[a-zA-Z]+\\d+$")) {
            return InventoryInterface.KIND_PHYSICAL;
        }
        return InventoryInterface.KIND_OTHER;
    }
}
