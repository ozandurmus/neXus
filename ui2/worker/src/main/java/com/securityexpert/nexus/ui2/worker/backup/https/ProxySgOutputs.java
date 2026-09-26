package com.securityexpert.nexus.ui2.worker.backup.https;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;

/**
 * SGOS "show interface all" and "show ip-route-table" read through the Management Center (PO 2026-09-25: "oradaki
 * IP'ler rotalar"). MEASURE FIRST: the output shapes are not yet measured on the estate, so both parsers read only what
 * they can prove -- an interface block headed "Interface N:N" with "Internet address" / "Subnet mask" lines; a route row
 * with a destination (address or "default"), a gateway and, when present, a dotted mask -- and {@link #shape} logs the
 * line shapes (letters a, digits 9) for the first-run record. Anything else is left out, never guessed.
 */
public final class ProxySgOutputs {

    private ProxySgOutputs() {
    }

    // Measured 2026-09-26 (SGOS 7.4 via the MC): "  Ethernet interface 0:0 (...)"; an optional leading word is allowed.
    // "% Ethernet interface N:0 is not installed" (empty slots) starts with '%' and never matches.
    private static final Pattern IFACE = Pattern.compile("^\\s*(?:[A-Za-z]+\\s+)?Interface\\s+(\\d+:\\d+(?:\\.\\d+)?)\\b(.*)$", Pattern.CASE_INSENSITIVE);
    /** Measured: "    Internet address: <ip> netmask <ip>" -- the mask follows the address on the same line. */
    private static final Pattern ADDR_WITH_MASK = Pattern.compile("(?i)internet address:?\\s*(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s+\\S+\\s+(255\\.\\d+\\.\\d+\\.\\d+)");
    /** Measured: "    Status: up" on its own line. */
    private static final Pattern STATUS = Pattern.compile("(?i)^\\s*status:\\s*(up|down)\\b");
    private static final Pattern ADDR = Pattern.compile("(?i)internet address:?\\s*(\\d+\\.\\d+\\.\\d+\\.\\d+)");
    private static final Pattern MASK = Pattern.compile("(?i)subnet mask:?\\s*(\\d+\\.\\d+\\.\\d+\\.\\d+)");
    private static final Pattern IPV4 = Pattern.compile("\\b(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})(?:/(\\d{1,2}))?\\b");

    public static List<InventoryInterface> interfaces(String out) {
        List<InventoryInterface> list = new ArrayList<>();
        if (out == null) {
            return list;
        }
        String name = null;
        String header = "";
        String addr = null;
        String mask = null;
        for (String raw : out.split("\\R")) {
            Matcher h = IFACE.matcher(raw);
            if (h.matches()) {
                if (name != null) {
                    list.add(row(name, header, addr, mask));
                }
                name = h.group(1);
                header = h.group(2).toLowerCase(Locale.ROOT);
                addr = null;
                mask = null;
                continue;
            }
            if (name == null) {
                continue;
            }
            Matcher a = ADDR.matcher(raw);
            if (a.find() && addr == null) {
                addr = a.group(1);
            }
            Matcher m = MASK.matcher(raw);
            if (m.find() && mask == null) {
                mask = m.group(1);
            }
            Matcher am = ADDR_WITH_MASK.matcher(raw);
            if (am.find() && mask == null) {
                mask = am.group(2);
            }
            Matcher st = STATUS.matcher(raw);
            if (st.find()) {
                header = header + " link " + st.group(1).toLowerCase(Locale.ROOT);
            }
            String l = raw.toLowerCase(Locale.ROOT);
            if (l.contains("link") && (l.contains(" up") || l.contains(" down")) && !header.contains("link")) {
                header = header + " " + l;
            }
        }
        if (name != null) {
            list.add(row(name, header, addr, mask));
        }
        return list;
    }

    private static InventoryInterface row(String name, String header, String addr, String mask) {
        List<InventoryAddress> addresses = new ArrayList<>();
        if (addr != null && !addr.equals("0.0.0.0")) {
            addresses.add(new InventoryAddress(UUID.randomUUID().toString(), addr + "/" + (mask == null ? 32 : prefix(mask)),
                    InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER));
        }
        String state = header.contains("link down") || header.contains("not running") ? InventoryInterface.STATE_DOWN
                : header.contains("link up") || header.contains("running at") ? InventoryInterface.STATE_UP : InventoryInterface.STATE_UNKNOWN;
        String kind = name.contains(".") ? InventoryInterface.KIND_VLAN : InventoryInterface.KIND_PHYSICAL;
        return new InventoryInterface(UUID.randomUUID().toString(), name, Optional.empty(), kind, state, addresses);
    }

    /** A route row: first token a destination ("default" or an address, optionally /len), then a gateway address. */
    public static List<InventoryRoute> routes(String out) {
        List<InventoryRoute> list = new ArrayList<>();
        if (out == null) {
            return list;
        }
        for (String raw : out.split("\\R")) {
            String[] t = raw.trim().split("\\s+");
            if (t.length < 2) {
                continue;
            }
            String dest;
            Integer len = null;
            if (t[0].equalsIgnoreCase("default")) {
                dest = "0.0.0.0";
                len = 0;
            } else {
                Matcher d = IPV4.matcher(t[0]);
                if (!d.matches()) {
                    continue;
                }
                dest = d.group(1);
                len = d.group(2) == null ? null : Integer.valueOf(d.group(2));
            }
            Optional<String> gateway = Optional.empty();
            String iface = null;
            for (int i = 1; i < t.length; i++) {
                if (t[i].matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                    if (t[i].startsWith("255.") || (t[i].equals("0.0.0.0") && gateway.isPresent())) {
                        if (len == null) {
                            len = prefix(t[i]);
                        }
                    } else if (gateway.isEmpty()) {
                        gateway = Optional.of(t[i]);
                    }
                } else if (t[i].matches("\\d+:\\d+(\\.\\d+)?")) {
                    iface = t[i];
                }
            }
            if (len == null) {
                len = dest.equals("0.0.0.0") ? 0 : 32;
            }
            Optional<String> hop = gateway.filter(g -> !g.equals("0.0.0.0"));
            String cidr = dest + "/" + len;
            list.add(new InventoryRoute(UUID.randomUUID().toString(), cidr, hop, Optional.ofNullable(iface),
                    cidr.equals("0.0.0.0/0") ? InventoryRoute.PROTOCOL_DEFAULT
                            : hop.isEmpty() ? InventoryRoute.PROTOCOL_CONNECTED : InventoryRoute.PROTOCOL_STATIC, Optional.empty()));
        }
        return list;
    }

    /** First lines with letters as a and digits as 9 -- the shape of the output, no value (measurement log). */
    public static String shape(String out, int lines) {
        if (out == null) {
            return "<none>";
        }
        StringBuilder sb = new StringBuilder();
        String[] rows = out.split("\\R");
        for (int i = 0; i < Math.min(lines, rows.length); i++) {
            sb.append(rows[i].replaceAll("[A-Za-z]", "a").replaceAll("[0-9]", "9").replaceAll("a{2,}", "aa").replaceAll("9{2,}", "99"))
                    .append(" | ");
        }
        return sb.append("(").append(rows.length).append(" lines)").toString();
    }

    static int prefix(String dotted) {
        int len = 0;
        for (String part : dotted.split("\\.")) {
            len += Integer.bitCount(Integer.parseInt(part) & 0xff);
        }
        return len;
    }
}
