package com.securityexpert.nexus.ui2.worker.backup.fortinet;

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
 * FortiGate over SSH, one interactive shell per run (docs/design/FORTINET_CONTRACT.md, gate rows V80). Every command is a
 * read; "config global" / "config vdom" / "edit <vdom>" / "end" only move between contexts. Paging is answered in the
 * session ("--More--"), never switched off on the device -- Backbox's "config system console / set output standard"
 * is a configuration change neXus does not make. Output shapes follow Backbox trail 23488971 (FortiOS 7.0.12, VDOMs).
 */
public final class FortiGatePlan {

    private FortiGatePlan() {
    }

    public static final String GET_SYSTEM_STATUS = "get system status";
    public static final String CONFIG_GLOBAL = "config global";
    public static final String CONFIG_VDOM = "config vdom";
    public static final String END = "end";
    public static final String SHOW_SYSTEM_INTERFACE = "show system interface";
    public static final String GET_ROUTING_TABLE = "get router info routing-table all";
    /** The whole configuration at the top level (all VDOMs), headed "#config-version=" -- the configuration backup. */
    public static final String SHOW = "show";

    private static final Pattern VDOM_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,31}");

    /** "edit <vdom>" only for a name from the device that fits FortiOS's own VDOM name rules. */
    public static String editVdom(String vdom) {
        if (!VDOM_NAME.matcher(vdom).matches()) {
            throw new IllegalArgumentException("not a VDOM name");
        }
        return "edit " + vdom;
    }

    public static boolean validVdom(String vdom) {
        return vdom != null && VDOM_NAME.matcher(vdom).matches();
    }

    public record Status(Optional<String> hostname, Optional<String> model, Optional<String> version, Optional<String> serial,
            boolean multiVdom, Optional<String> haMode) {
    }

    private static final Pattern VERSION = Pattern.compile("(?m)^Version:\\s*(\\S+)\\s+(v[\\d.]+)(?:,build(\\d+))?");
    private static final Pattern HOSTNAME = Pattern.compile("(?m)^Hostname:\\s*(\\S+)");
    private static final Pattern SERIAL = Pattern.compile("(?m)^Serial-Number:\\s*(\\S+)");
    private static final Pattern VDOM_MODE = Pattern.compile("(?m)^Virtual domain configuration:\\s*(\\S+)");
    private static final Pattern HA = Pattern.compile("(?m)^Current HA mode:\\s*(.+)$");

    public static Status parseStatus(String out) {
        if (out == null) {
            return new Status(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, Optional.empty());
        }
        Matcher v = VERSION.matcher(out);
        Optional<String> model = Optional.empty();
        Optional<String> version = Optional.empty();
        if (v.find()) {
            model = Optional.of(v.group(1));
            version = Optional.of(v.group(2) + (v.group(3) != null ? " build" + v.group(3) : ""));
        }
        String mode = group(VDOM_MODE, out).orElse("disable").toLowerCase(Locale.ROOT);
        return new Status(group(HOSTNAME, out), model, version, group(SERIAL, out),
                mode.startsWith("multiple") || mode.startsWith("split"), group(HA, out).map(String::trim));
    }

    /** "a-p, primary" -> "active"; "a-p, secondary" -> "standby"; standalone -> empty. */
    public static Optional<String> haRole(Optional<String> haMode) {
        if (haMode.isEmpty()) {
            return Optional.empty();
        }
        String m = haMode.get().toLowerCase(Locale.ROOT);
        if (m.startsWith("standalone")) {
            return Optional.empty();
        }
        return Optional.of(m.contains("primary") || m.contains("master") ? "active" : m.contains("secondary") || m.contains("slave") ? "standby" : m);
    }

    /** One interface from "show system interface": its VDOM and the inventory row. */
    public record Iface(String vdom, InventoryInterface row) {
    }

    private static final Pattern EDIT = Pattern.compile("^\\s*edit \"([^\"]+)\"\\s*$");
    private static final Pattern SET = Pattern.compile("^\\s*set (\\S+) (.+)$");

    public static List<Iface> parseInterfaces(String out) {
        List<Iface> list = new ArrayList<>();
        if (out == null) {
            return list;
        }
        String name = null;
        Map<String, String> sets = new LinkedHashMap<>();
        for (String raw : out.split("\\R")) {
            Matcher e = EDIT.matcher(raw);
            if (e.matches()) {
                name = e.group(1);
                sets.clear();
                continue;
            }
            if (name != null && raw.strip().equals("next")) {
                list.add(toIface(name, sets));
                name = null;
                continue;
            }
            Matcher s = SET.matcher(raw);
            if (name != null && s.matches()) {
                sets.put(s.group(1), s.group(2).trim());
            }
        }
        return list;
    }

    private static Iface toIface(String name, Map<String, String> sets) {
        String type = unquote(sets.getOrDefault("type", "physical"));
        String kind = switch (type) {
            case "physical" -> InventoryInterface.KIND_PHYSICAL;
            case "vlan" -> InventoryInterface.KIND_VLAN;
            case "aggregate", "redundant" -> InventoryInterface.KIND_BOND;
            case "tunnel" -> InventoryInterface.KIND_TUNNEL;
            case "loopback" -> InventoryInterface.KIND_LOOPBACK;
            default -> InventoryInterface.KIND_OTHER;
        };
        List<InventoryAddress> addresses = new ArrayList<>();
        String ip = sets.get("ip");
        if (ip != null) {
            String[] p = ip.split("\\s+");
            if (p.length == 2 && !p[0].equals("0.0.0.0")) {
                addresses.add(new InventoryAddress(UUID.randomUUID().toString(), p[0] + "/" + prefix(p[1]),
                        InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER));
            }
        }
        Optional<Integer> vlan = Optional.ofNullable(sets.get("vlanid")).map(String::trim).filter(x -> x.matches("\\d+")).map(Integer::valueOf);
        Optional<String> parent = Optional.ofNullable(sets.get("interface")).map(FortiGatePlan::unquote);
        // configured state: "set status down" -> down, else up (FortiOS shows only non-default values)
        String state = "down".equals(sets.get("status")) ? InventoryInterface.STATE_DOWN : InventoryInterface.STATE_UP;
        return new Iface(unquote(sets.getOrDefault("vdom", "\"root\"")),
                new InventoryInterface(UUID.randomUUID().toString(), name, parent, kind, state, addresses, vlan));
    }

    private static final Pattern ROUTE = Pattern.compile(
            "^([A-Z*]+(?:\\s+(?:E1|E2|N1|N2|IA|L1|L2|ia))?)\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+/\\d+)\\s+(.*)$");
    /** "via hop, port1" or "via hop (recursive is directly connected, port2), 2d01h" -- the interface in brackets then. */
    private static final Pattern VIA = Pattern.compile("via (\\d+\\.\\d+\\.\\d+\\.\\d+)(?: \\(([^)]*)\\))?,\\s*([^,\\s]+)");
    private static final Pattern CONNECTED = Pattern.compile("is directly connected,\\s*([^,\\s]+)");

    /** "get router info routing-table all": code, destination/prefix, then "via next hop, interface" or "directly connected". */
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
            Optional<String> hop = Optional.empty();
            Optional<String> iface = Optional.empty();
            Matcher via = VIA.matcher(m.group(3));
            Matcher conn = CONNECTED.matcher(m.group(3));
            if (via.find()) {
                hop = Optional.of(via.group(1));
                Matcher inner = via.group(2) == null ? null : CONNECTED.matcher(via.group(2));
                iface = Optional.of(inner != null && inner.find() ? inner.group(1) : via.group(3));
            } else if (conn.find()) {
                iface = Optional.of(conn.group(1));
            }
            String protocol = switch (code) {
                case "C" -> InventoryRoute.PROTOCOL_CONNECTED;
                case "S" -> m.group(2).equals("0.0.0.0/0") ? InventoryRoute.PROTOCOL_DEFAULT : InventoryRoute.PROTOCOL_STATIC;
                case "O" -> InventoryRoute.PROTOCOL_OSPF;
                case "B" -> InventoryRoute.PROTOCOL_BGP;
                case "R" -> InventoryRoute.PROTOCOL_RIP;
                default -> InventoryRoute.PROTOCOL_UNKNOWN;
            };
            routes.add(new InventoryRoute(UUID.randomUUID().toString(), m.group(2), hop, iface, protocol, Optional.empty()));
        }
        return routes;
    }

    /** A configuration backup starts with FortiOS's own header line. */
    public static boolean isConfiguration(String out) {
        return out != null && out.stripLeading().startsWith("#config-version=");
    }

    public static boolean isCliError(String out) {
        String l = out == null ? "" : out.toLowerCase(Locale.ROOT);
        return l.contains("command fail. return code") || l.contains("command parse error") || l.contains("unknown action");
    }

    static int prefix(String dotted) {
        int len = 0;
        for (String part : dotted.split("\\.")) {
            len += Integer.bitCount(Integer.parseInt(part) & 0xff);
        }
        return len;
    }

    private static String unquote(String s) {
        String t = s.trim();
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1) : t;
    }

    private static Optional<String> group(Pattern p, String out) {
        Matcher m = p.matcher(out);
        return m.find() ? Optional.of(m.group(1).trim()) : Optional.empty();
    }
}
