package com.securityexpert.nexus.ui2.worker.backup.fortinet;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
    public static final String GET_SYSTEM_HA_STATUS = "get system ha status";
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

    /** This unit's exact serial in an A-P/A-A member row determines its FortiOS role. */
    public static Optional<String> haRole(String output, Optional<String> ownSerial) {
        if (output == null || ownSerial.isEmpty() || ownSerial.get().isBlank()) {
            return Optional.empty();
        }
        if (Pattern.compile("(?im)^\\s*(?:Mode|HA Health Status):\\s*standalone\\s*$").matcher(output).find()) {
            return Optional.empty();
        }
        if (!Pattern.compile("(?m)^HA Health Status:\\s*.+$").matcher(output).find()
                || !Pattern.compile("(?m)^Mode:\\s*HA A-[PA]\\s*$").matcher(output).find()) {
            return Optional.empty();
        }
        // Measured 2026-09-26 (FortiOS HA A-P pair): "Primary: <serial>, HA operating index = 0" -- no host name field
        // and "operating" (confirmed by condition measurement: mode matched, 0 "cluster index" lines); other releases
        // print "<name>, <serial>, HA cluster index = N". Both are accepted.
        Matcher member = Pattern.compile("(?m)^\\s*(Primary|Secondary)\\s*:\\s*(?:[^,]+,\\s*)?([^,\\s]+),\\s*HA (?:cluster|operating) index\\s*=").matcher(output);
        String role = null;
        while (member.find()) {
            if (ownSerial.get().equals(member.group(2))) {
                if (role != null) {
                    return Optional.empty();
                }
                role = member.group(1).toLowerCase(Locale.ROOT);
            }
        }
        return Optional.ofNullable(role);
    }

    private static final Pattern HA_MEMBER_LINE = Pattern.compile(
            "(?m)^\\s*(Primary|Secondary)\\s*:\\s*(?:[^,]+,\\s*)?([^,\\s]+),\\s*HA (?:cluster|operating) index\\s*=");

    /** A pair identity from exactly one Primary and one Secondary member, including this unit. */
    public static Optional<String> haPairClaim(String haStatus, Optional<String> ownSerial) {
        if (haStatus == null || ownSerial.isEmpty() || ownSerial.get().isBlank()
                || Pattern.compile("(?im)^\\s*(?:Mode|HA Health Status):\\s*standalone\\s*$").matcher(haStatus).find()) {
            return Optional.empty();
        }
        Matcher m = HA_MEMBER_LINE.matcher(haStatus);
        String primary = null;
        String secondary = null;
        int lines = 0;
        while (m.find()) {
            lines++;
            if ("Primary".equals(m.group(1))) primary = m.group(2);
            else secondary = m.group(2);
        }
        if (lines != 2 || primary == null || secondary == null || primary.equals(secondary)
                || (!ownSerial.get().equals(primary) && !ownSerial.get().equals(secondary))) return Optional.empty();
        String pair = primary.compareTo(secondary) < 0 ? primary + "|" + secondary : secondary + "|" + primary;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(pair.getBytes(StandardCharsets.UTF_8));
            // The claim carries this unit's own token (hash of its serial): two records of the SAME unit (e.g. added once
            // by the cluster address and once by its own address) must never corroborate each other (review 2026-09-27).
            byte[] unit = MessageDigest.getInstance("SHA-256").digest(ownSerial.get().getBytes(StandardCharsets.UTF_8));
            return Optional.of("fgt-ha|" + HexFormat.of().formatHex(digest, 0, 8) + "|" + HexFormat.of().formatHex(unit, 0, 8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The other unit's serial in a two-unit HA status, when exactly one member line is not this unit. */
    public static Optional<String> haPeerSerial(String haStatus, String ownSerial) {
        if (haStatus == null || ownSerial == null) return Optional.empty();
        java.util.Set<String> peers = new java.util.LinkedHashSet<>();
        Matcher m = HA_MEMBER_LINE.matcher(haStatus);
        while (m.find()) {
            if (!ownSerial.equals(m.group(2))) peers.add(m.group(2));
        }
        return peers.size() == 1 ? Optional.of(peers.iterator().next()) : Optional.empty();
    }

    private static final Pattern HA_MEMBER_INDEX = Pattern.compile(
            "(?m)^\\s*(?:Primary|Secondary)\\s*:\\s*(?:[^,]+,\\s*)?([^,\\s]+),\\s*HA (?:cluster|operating) index\\s*=\\s*(\\d{1,3})");

    /** The peer's index from the HA status member line "Secondary: <serial>, HA operating index = N" (exact serial). */
    public static Optional<String> haIndexOf(String haStatus, String serial) {
        if (haStatus == null || serial == null) return Optional.empty();
        Optional<String> found = Optional.empty();
        Matcher m = HA_MEMBER_INDEX.matcher(haStatus);
        while (m.find()) {
            if (serial.equals(m.group(1))) {
                if (found.isPresent() && !found.get().equals(m.group(2))) return Optional.empty();
                found = Optional.of(m.group(2));
            }
        }
        return found;
    }

    /** "execute ha manage ?" lists "<index> ... <serial>" per other unit: the index whose line names the peer serial exactly. */
    public static Optional<String> haManageIndexFor(String listOutput, String peerSerial) {
        if (listOutput == null || peerSerial == null) return Optional.empty();
        Optional<String> found = Optional.empty();
        for (String line : listOutput.split("\\R")) {
            Matcher idx = Pattern.compile("<(\\d{1,3})>").matcher(line);
            if (idx.find() && java.util.Arrays.asList(line.split("[\\s,()]+")).contains(peerSerial)) {
                if (found.isPresent()) return Optional.empty();
                found = Optional.of(idx.group(1));
            }
        }
        return found;
    }

    /** A user name safe to put on the command line (it is not secret; the password never is). */
    public static boolean safeUsername(String username) {
        return username != null && username.matches("[A-Za-z0-9._@-]{1,64}");
    }

    /** MEASURE: which of haRole's conditions hold on a real output (booleans and counts only). */
    public static String haRoleChecks(String output, Optional<String> ownSerial) {
        if (output == null) return "null";
        boolean health = Pattern.compile("(?m)^HA Health Status:\\s*.+$").matcher(output).find();
        boolean mode = Pattern.compile("(?m)^Mode:\\s*HA A-[PA]\\s*$").matcher(output).find();
        boolean modeLoose = Pattern.compile("(?im)^\\s*Mode\\s*:\\s*HA\\s+A-[PA]").matcher(output).find();
        Matcher member = Pattern.compile("(?m)^\\s*(Primary|Secondary)\\s*:\\s*(?:[^,]+,\\s*)?([^,\\s]+),\\s*HA cluster index\\s*=").matcher(output);
        int members = 0;
        int own = 0;
        while (member.find()) {
            members++;
            if (ownSerial.isPresent() && ownSerial.get().equals(member.group(2))) own++;
        }
        boolean cr = output.indexOf('\r') >= 0;
        return "health=" + health + " mode=" + mode + " modeLoose=" + modeLoose + " memberLines=" + members + " ownMatches=" + own
                + " cr=" + cr + " ownSerialLen=" + ownSerial.map(String::length).orElse(-1);
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
