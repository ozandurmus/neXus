package com.securityexpert.nexus.ui2.worker.backup.asa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;

/**
 * Cisco ASA (9.x) over SSH, one interactive shell per run (docs/design/CISCO_ASA_CONTRACT.md, gate rows V78). Every
 * command below is a read; none changes the device. Output shapes follow the Backbox trail of the estate's ASA
 * (Firepower 4100, 9.22) and Cisco's command reference; each parser returns empty rather than guess.
 */
public final class CiscoAsaPlan {

    private CiscoAsaPlan() {
    }

    /** Session setting only (this login's pager), never saved to the configuration. */
    public static final String TERMINAL_PAGER_0 = "terminal pager 0";
    public static final String SHOW_CURPRIV = "show curpriv";
    public static final String SHOW_VERSION = "show version";
    public static final String SHOW_FAILOVER_THIS_HOST = "show failover | include This host";
    public static final String SHOW_MODE = "show mode";
    public static final String SHOW_IP_ADDRESS = "show ip address";
    public static final String SHOW_INTERFACE_IP_BRIEF = "show interface ip brief";
    public static final String SHOW_ROUTE = "show route";
    /** Backup: the running configuration with its keys (Backbox's own read), and the saved one. */
    public static final String MORE_SYSTEM_RUNNING_CONFIG = "more system:running-config";
    public static final String SHOW_STARTUP_CONFIG = "show startup-config";

    public static final int DEFAULT_PORT = 22;

    public record Version(Optional<String> hostname, Optional<String> model, Optional<String> softwareVersion,
            Optional<String> serial, Optional<String> uptime) {
    }

    private static final Pattern SOFTWARE_VERSION = Pattern.compile("Adaptive Security Appliance Software Version (\\S+)");
    private static final Pattern HARDWARE = Pattern.compile("(?m)^Hardware:\\s+([^,\\s]+)");
    private static final Pattern SERIAL = Pattern.compile("(?m)^Serial Number:\\s*(\\S+)");
    /** "{hostname} up 358 days 20 hours" -- not "failover cluster up ...". */
    private static final Pattern UP = Pattern.compile("(?m)^(\\S+) up (.+)$");
    private static final Pattern PRIVILEGE = Pattern.compile("Current privilege level\\s*:\\s*(\\d+)");
    private static final Pattern THIS_HOST = Pattern.compile("This host\\s*:\\s*(\\w+)\\s*-\\s*(\\w+(?:\\s\\w+)?)");

    /** A CLI refusal ("ERROR: % Invalid input detected at '^' marker.") -- the read failed, never output to parse. */
    public static boolean isCliError(String output) {
        return output != null && (output.contains("% Invalid input detected") || output.contains("% Incomplete command")
                || output.contains("ERROR: Command authorization failed"));
    }

    public static Version parseVersion(String out) {
        if (out == null) {
            return new Version(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
        Optional<String> host = Optional.empty();
        Optional<String> uptime = Optional.empty();
        Matcher up = UP.matcher(out);
        while (up.find()) {
            if (!"cluster".equals(up.group(1)) && !out.substring(Math.max(0, up.start() - 9), up.start()).endsWith("failover ")) {
                host = Optional.of(up.group(1));
                uptime = Optional.of(up.group(2).trim());
                break;
            }
        }
        return new Version(host, group(HARDWARE, out), group(SOFTWARE_VERSION, out), group(SERIAL, out), uptime);
    }

    public static Optional<Integer> parsePrivilege(String out) {
        return group(PRIVILEGE, out).map(Integer::parseInt);
    }

    /** "This host: Primary - Active" -> "active"; "Failover Off" or nothing -> empty (standalone). */
    public static Optional<String> parseHaRole(String out) {
        Matcher m = out == null ? null : THIS_HOST.matcher(out);
        if (m == null || !m.find()) {
            return Optional.empty();
        }
        String state = m.group(2).toLowerCase(Locale.ROOT);
        return Optional.of(state.startsWith("active") ? "active" : state.startsWith("standby") ? "standby" : state);
    }

    /** "Security context mode: multiple" -> true. */
    public static boolean isMultipleContext(String out) {
        return out != null && out.toLowerCase(Locale.ROOT).contains("mode: multiple");
    }

    /**
     * {@code show ip address}: the "Current IP Addresses" block (else the "System" one) -- interface, nameif, address,
     * dotted mask. Keyed by interface.
     */
    public static Map<String, String[]> parseIpAddresses(String out) {
        Map<String, String[]> system = new LinkedHashMap<>();
        Map<String, String[]> current = new LinkedHashMap<>();
        Map<String, String[]> into = system;
        if (out == null) {
            return system;
        }
        for (String raw : out.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("Current IP Addresses")) {
                into = current;
                continue;
            }
            String[] f = line.split("\\s+");
            if (f.length >= 4 && isIpv4(f[2]) && isIpv4(f[3])) {
                into.put(f[0], new String[] {f[1], f[2], f[3]});
            }
        }
        return current.isEmpty() ? system : current;
    }

    /** {@code show interface ip brief}: interface -> up/down from its Status and Protocol columns. */
    public static Map<String, String> parseInterfaceStates(String out) {
        Map<String, String> states = new LinkedHashMap<>();
        if (out == null) {
            return states;
        }
        for (String raw : out.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("Interface")) {
                continue;
            }
            String[] f = line.split("\\s+");
            // Interface IP-Address OK? Method Status Protocol ("administratively down" is two words)
            if (f.length >= 6 && (f[3].equals("YES") || f[3].equals("NO") || f[2].equals("YES") || f[2].equals("NO"))) {
                String protocol = f[f.length - 1].toLowerCase(Locale.ROOT);
                String status = f[f.length - 2].toLowerCase(Locale.ROOT);
                states.put(f[0], "up".equals(protocol) && "up".equals(status) ? InventoryInterface.STATE_UP
                        : "down".equals(protocol) || "down".equals(status) ? InventoryInterface.STATE_DOWN
                                : InventoryInterface.STATE_UNKNOWN);
            }
        }
        return states;
    }

    public static List<InventoryInterface> interfaces(String ipAddressOut, String briefOut) {
        Map<String, String[]> addresses = parseIpAddresses(ipAddressOut);
        Map<String, String> states = parseInterfaceStates(briefOut);
        List<String> names = new ArrayList<>(states.keySet());
        addresses.keySet().stream().filter(n -> !states.containsKey(n)).forEach(names::add);
        List<InventoryInterface> out = new ArrayList<>();
        for (String name : names) {
            List<InventoryAddress> addrs = new ArrayList<>();
            String[] a = addresses.get(name);
            if (a != null) {
                addrs.add(new InventoryAddress(UUID.randomUUID().toString(), a[1] + "/" + prefixLength(a[2]),
                        InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER));
            }
            String lower = name.toLowerCase(Locale.ROOT);
            String kind = name.contains(".") ? InventoryInterface.KIND_SUBINTERFACE
                    : lower.startsWith("port-channel") ? InventoryInterface.KIND_BOND
                    : lower.startsWith("tunnel") ? InventoryInterface.KIND_TUNNEL
                    : lower.startsWith("vlan") ? InventoryInterface.KIND_VLAN : InventoryInterface.KIND_PHYSICAL;
            Optional<String> parent = name.contains(".") ? Optional.of(name.substring(0, name.indexOf('.'))) : Optional.empty();
            out.add(new InventoryInterface(UUID.randomUUID().toString(), name, parent, kind,
                    states.getOrDefault(name, InventoryInterface.STATE_UNKNOWN), addrs));
        }
        return out;
    }

    private static final Pattern ROUTE = Pattern.compile(
            "^([A-Za-z*]+(?:\\s+(?:E1|E2|N1|N2|IA|L1|L2|ia|\\*))*)\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s+(.*)$");
    private static final Pattern VIA = Pattern.compile("via (\\d+\\.\\d+\\.\\d+\\.\\d+),\\s*(\\S+)");
    private static final Pattern CONNECTED = Pattern.compile("is directly connected,\\s*(\\S+)");

    /** {@code show route}: code, destination, dotted mask, then "via {next hop}, {nameif}" or "is directly connected, {nameif}". */
    public static List<InventoryRoute> parseRoutes(String out) {
        List<InventoryRoute> routes = new ArrayList<>();
        if (out == null) {
            return routes;
        }
        for (String raw : out.split("\\R")) {
            Matcher m = ROUTE.matcher(raw.strip());
            if (!m.matches()) {
                continue;
            }
            String code = m.group(1).replace("*", "").trim().split("\\s+")[0];
            String rest = m.group(4);
            Optional<String> nextHop = Optional.empty();
            Optional<String> iface = Optional.empty();
            Matcher via = VIA.matcher(rest);
            Matcher conn = CONNECTED.matcher(rest);
            if (via.find()) {
                nextHop = Optional.of(via.group(1));
                iface = Optional.of(via.group(2));
            } else if (conn.find()) {
                iface = Optional.of(conn.group(1));
            }
            String protocol = switch (code) {
                case "C" -> InventoryRoute.PROTOCOL_CONNECTED;
                case "L" -> InventoryRoute.PROTOCOL_HOST;
                case "S" -> m.group(2).equals("0.0.0.0") && m.group(3).equals("0.0.0.0") ? InventoryRoute.PROTOCOL_DEFAULT
                        : InventoryRoute.PROTOCOL_STATIC;
                case "O" -> InventoryRoute.PROTOCOL_OSPF;
                case "B" -> InventoryRoute.PROTOCOL_BGP;
                case "R" -> InventoryRoute.PROTOCOL_RIP;
                default -> InventoryRoute.PROTOCOL_UNKNOWN;
            };
            routes.add(new InventoryRoute(UUID.randomUUID().toString(), m.group(2) + "/" + prefixLength(m.group(3)), nextHop,
                    iface, protocol, Optional.empty()));
        }
        return routes;
    }

    static int prefixLength(String dotted) {
        int len = 0;
        for (String part : dotted.split("\\.")) {
            len += Integer.bitCount(Integer.parseInt(part) & 0xff);
        }
        return len;
    }

    private static boolean isIpv4(String s) {
        return s.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    }

    private static Optional<String> group(Pattern p, String out) {
        if (out == null) {
            return Optional.empty();
        }
        Matcher m = p.matcher(out);
        return m.find() ? Optional.of(m.group(1).trim()) : Optional.empty();
    }
}
