package com.securityexpert.nexus.ui2.worker.inventory.cp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;

/**
 * {@code cphaprob -a if} (14D CF-3, PR-3), cluster members only. Only
 * the section after a {@code Virtual cluster interfaces: <n>} line
 * (case-insensitive, the row count tolerated) up to the next section is
 * read; first token interface name, first IPv4 literal the address; every
 * address this class produces is {@link InventoryAddress#ROLE_CLUSTER_VIRTUAL},
 * and this class never reads a member address (that is {@link
 * CheckPointIpAddrParser}'s read alone -- the two never mix).
 *
 * <p>PR-3's other measured tolerances: a leading {@code vsid <N>:} header
 * (present on VSX, both at the physical context and per virtual system)
 * is skipped like any other line before the section is found; a row's
 * trailing {@code VMAC address: <mac>} text never matches the IPv4-literal
 * pattern and is therefore already ignored; both VLAN table shapes
 * ({@code Interface | Low VLAN | High VLAN} on ClusterXL, {@code Interface
 * | VS ID | Monitored VLANs} on VSX) sit after their own header line and
 * are never entered.</p>
 */
public final class CheckPointClusterVirtualInterfaceParser {

    /** One cluster virtual address, named by the interface it belongs to (merged onto that interface by the caller). */
    public record VirtualInterfaceAddress(String interfaceName, String address) {
    }

    private static final Pattern SECTION_HEADER = Pattern.compile("(?i)^\\s*virtual cluster interfaces\\s*:\\s*\\d*\\s*$");
    private static final Pattern OTHER_SECTION_HEADER = Pattern.compile("^\\S.*:\\s*$");
    private static final Pattern IPV4_LITERAL = Pattern.compile("\\b(\\d{1,3}(?:\\.\\d{1,3}){3})\\b");
    private static final Pattern INTERFACE_STATUS_HEADER = Pattern.compile("(?i)^\\s*interface name\\s*:\\s*status\\s*:\\s*$");
    private static final Pattern STATE_TOKEN = Pattern.compile("(?i)\\b(UP|DOWN)\\b");

    private CheckPointClusterVirtualInterfaceParser() {
    }

    public static List<VirtualInterfaceAddress> parse(String output) {
        List<VirtualInterfaceAddress> result = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return result;
        }
        boolean inSection = false;
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.trim();
            if (!inSection) {
                if (SECTION_HEADER.matcher(line).matches() || line.toLowerCase().startsWith("virtual cluster interfaces:")) {
                    inSection = true;
                }
                continue;
            }
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase();
            if (lower.startsWith("no vlans") || lower.startsWith("clusterxl vlan") || lower.startsWith("vlan monitoring")
                    || lower.startsWith("interface name:") || lower.startsWith("security gw:")
                    || (OTHER_SECTION_HEADER.matcher(line).matches() && !lower.startsWith("virtual cluster interfaces"))) {
                break;
            }
            String[] tokens = line.split("\\s+");
            if (tokens.length == 0) {
                continue;
            }
            String interfaceName = tokens[0];
            Matcher ipv4 = IPV4_LITERAL.matcher(line);
            if (ipv4.find()) {
                result.add(new VirtualInterfaceAddress(interfaceName, ipv4.group(1)));
            }
        }
        return result;
    }

    /**
     * The same {@code cphaprob -a if} output's earlier {@code Interface Name:      Status:} table
     * (Product Owner correction, 2026-09-21): each monitored interface's line ends in {@code UP} or
     * {@code DOWN}; the table ends at the next blank line. Only interfaces this table actually lists
     * get a state here -- an interface present only in the "Virtual cluster interfaces" section (not
     * every one is monitored) is not in this map, and the caller records it {@code unknown} rather
     * than guessing.
     */
    public static Map<String, String> parseInterfaceStates(String output) {
        Map<String, String> states = new LinkedHashMap<>();
        if (output == null || output.isBlank()) {
            return states;
        }
        boolean inTable = false;
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.trim();
            if (!inTable) {
                if (INTERFACE_STATUS_HEADER.matcher(line).matches()) {
                    inTable = true;
                }
                continue;
            }
            if (line.isEmpty()) {
                break;
            }
            Matcher state = STATE_TOKEN.matcher(line);
            String lastStatus = null;
            int lastStart = -1;
            while (state.find()) {
                lastStatus = state.group(1).toUpperCase(java.util.Locale.ROOT);
                lastStart = state.start();
            }
            if (lastStatus == null) {
                continue;
            }
            // A row may carry a trailing role marker before the status column, e.g. "Mgmt        (S)  UP"
            // (the legend beneath this table: "S - sync, HA/LS - bond type, LM - link monitor, P -
            // probing") -- stripped so the name matches the same interface's entry in the "Virtual
            // cluster interfaces" section exactly.
            String name = line.substring(0, lastStart).trim().replaceAll("\\s*\\([A-Za-z/]+\\)\\s*$", "").trim();
            if (name.isEmpty()) {
                continue;
            }
            states.put(name, "UP".equals(lastStatus) ? InventoryInterface.STATE_UP : InventoryInterface.STATE_DOWN);
        }
        return states;
    }
}
