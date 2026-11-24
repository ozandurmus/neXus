package com.securityexpert.nexus.ui2.worker.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;

/**
 * Stateless Check Point CLISH interface and route parser plus connected-route enrichment.
 * Moved out of {@link InventoryCapabilityExecutor} without behaviour change.
 */
final class CheckPointClishParser {

    private static final System.Logger LOG = System.getLogger(CheckPointClishParser.class.getName());

    private CheckPointClishParser() {
    }

    private static int maskToPrefix(String mask) {
        if (mask == null || mask.isBlank()) {
            return 24;
        }
        try {
            String[] parts = mask.trim().split("\\.");
            if (parts.length != 4) {
                return 24;
            }
            int bits = 0;
            for (String part : parts) {
                int octet = Integer.parseInt(part);
                bits += Integer.bitCount(octet & 0xFF);
            }
            return bits;
        } catch (Exception e) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(e);
            return 24;
        }
    }

    static List<ParsedInterface> parseClishInterfaces(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        Map<String, ClishInterfaceBuilder> builders = new LinkedHashMap<>();

        boolean isBlockFormat = Pattern.compile("(?im)^interface\\s+[a-zA-Z0-9_.-]+\\s*$").matcher(output).find();
        // Quantum Spark / Gaia Embedded "show interfaces all" (measured live, 2026-09-22, as a
        // structure-only projection): key/value blocks, each opened by "name: <iface>", then
        // "ipv4-address: <addr or blank>", "status: off|disconnected|...", "mac-address:",
        // "description:". No subnet-mask line was observed, so an address here is recorded bare
        // (no prefix) and the caller derives the prefix from the connected routes -- never a
        // default guessed here (Evidence laws: absence of evidence is not evidence of a /24).
        boolean isKeyValueFormat = !isBlockFormat && Pattern.compile("(?im)^name:\\s*\\S+").matcher(output).find();

        if (isKeyValueFormat) {
            ClishInterfaceBuilder current = null;
            java.util.Set<String> statusTokens = new java.util.TreeSet<>();
            for (String rawLine : output.split("\\R")) {
                String line = rawLine.trim();
                int colon = line.indexOf(':');
                if (line.isEmpty() || colon <= 0) {
                    continue;
                }
                String key = line.substring(0, colon).trim().toLowerCase();
                String value = line.substring(colon + 1).trim();
                switch (key) {
                    case "name" -> current = value.isEmpty() ? null : builders.computeIfAbsent(value, ClishInterfaceBuilder::new);
                    case "status" -> {
                        statusTokens.add(value.toLowerCase().replaceAll("\\d+", "#"));
                        if (current != null) {
                            // Measured live, 2026-09-22 (status tokens: "#/full", "disconnected",
                            // "off"): a port with link reports its negotiated speed/duplex, e.g.
                            // "1000/full", as its status -- that is the only "up" shape this shell
                            // was seen to use.
                            String status = value.toLowerCase();
                            current.state = status.matches("^\\d+[a-z]*/(full|half)$")
                                    ? InventoryInterface.STATE_UP
                                    : switch (status) {
                                        case "on", "up", "connected" -> InventoryInterface.STATE_UP;
                                        case "off", "down", "disconnected" -> InventoryInterface.STATE_DOWN;
                                        default -> InventoryInterface.STATE_UNKNOWN;
                                    };
                        }
                    }
                    case "ipv4-address", "ip-address", "ipv4 address" -> {
                        if (current != null && !value.isEmpty() && !value.startsWith("0.0.0.0")) {
                            Matcher m = Pattern.compile("^(\\d{1,3}(?:\\.\\d{1,3}){3})(?:/(\\d{1,2}))?").matcher(value);
                            if (m.find()) {
                                current.ip = m.group(1);
                                if (m.group(2) != null) {
                                    current.prefix = Integer.parseInt(m.group(2));
                                }
                                current.prefixUnknownIsBare = true;
                            }
                        }
                    }
                    case "subnet-mask", "netmask", "mask" -> {
                        if (current != null && value.matches("^\\d{1,3}(?:\\.\\d{1,3}){3}$")) {
                            current.mask = value;
                        }
                    }
                    default -> { }
                }
            }
            // Vendor semantics law: which "status:" words this shell uses (and what each means) is
            // unmeasured beyond off/disconnected -- the distinct tokens (no identity values) are
            // logged so the up/down mapping above is corrected from evidence, not guessed.
            LOG.log(System.Logger.Level.INFO, "[INVENTORY_CLISH_IF_STATUS_TOKENS] interfaces={0} status_tokens={1}",
                    builders.size(), statusTokens);
        } else if (isBlockFormat) {
            ClishInterfaceBuilder current = null;
            for (String rawLine : output.split("\\R")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("---") || line.startsWith("Codes:")) {
                    continue;
                }

                Matcher ifHeaderMatcher = Pattern.compile("(?i)^interface\\s+([a-zA-Z0-9_.-]+)$").matcher(line);
                if (ifHeaderMatcher.find()) {
                    String name = ifHeaderMatcher.group(1);
                    current = builders.computeIfAbsent(name, ClishInterfaceBuilder::new);
                    continue;
                }

                if (current != null) {
                    Matcher stateMatcher = Pattern.compile("(?i)(?:state|status)[:\\s]+(on|up|off|down)").matcher(line);
                    if (stateMatcher.find()) {
                        String st = stateMatcher.group(1).toLowerCase();
                        current.state = ("on".equals(st) || "up".equals(st)) ? InventoryInterface.STATE_UP : InventoryInterface.STATE_DOWN;
                        continue;
                    }
                    Matcher ipMatcher = Pattern.compile("(?i)(?:ipv4-address|ip(?:v4)?\\s*address)[:\\s]+([0-9.]+)(?:/(\\d+))?").matcher(line);
                    if (ipMatcher.find()) {
                        current.ip = ipMatcher.group(1);
                        if (ipMatcher.group(2) != null) {
                            current.prefix = Integer.parseInt(ipMatcher.group(2));
                        }
                        continue;
                    }
                    Matcher maskMatcher = Pattern.compile("(?i)(?:subnet-mask|netmask|mask)[:\\s]+([0-9.]+)").matcher(line);
                    if (maskMatcher.find()) {
                        current.mask = maskMatcher.group(1);
                        continue;
                    }
                }
            }
        } else {
            for (String rawLine : output.split("\\R")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("---") || line.startsWith("Codes:")) {
                    continue;
                }
                if (line.toLowerCase().startsWith("interface") || line.toLowerCase().startsWith("name") || line.toLowerCase().startsWith("port")) {
                    continue;
                }
                String[] tokens = line.split("\\s+");
                if (tokens.length >= 2) {
                    String candidateName = tokens[0];
                    if (!candidateName.matches("^\\d{1,3}\\..*") && candidateName.matches("^[a-zA-Z0-9_.-]+$")) {
                        ClishInterfaceBuilder tb = builders.computeIfAbsent(candidateName, ClishInterfaceBuilder::new);
                        for (int i = 1; i < tokens.length; i++) {
                            String tok = tokens[i].toLowerCase();
                            if ("up".equals(tok) || "on".equals(tok)) {
                                tb.state = InventoryInterface.STATE_UP;
                            } else if ("down".equals(tok) || "off".equals(tok)) {
                                tb.state = InventoryInterface.STATE_DOWN;
                            } else if (tok.matches("^\\d{1,3}(?:\\.\\d{1,3}){3}(?:/\\d{1,2})?$")) {
                                if (tb.ip == null && !tok.startsWith("0.0.0.0")) {
                                    if (tok.contains("/")) {
                                        String[] ipAndPrefix = tok.split("/");
                                        tb.ip = ipAndPrefix[0];
                                        tb.prefix = Integer.parseInt(ipAndPrefix[1]);
                                    } else {
                                        tb.ip = tok;
                                    }
                                } else if (tb.ip != null && tb.mask == null && tok.matches("^255\\..*")) {
                                    tb.mask = tok;
                                }
                            }
                        }
                    }
                }
            }
        }

        List<ParsedInterface> result = new ArrayList<>();
        for (ClishInterfaceBuilder b : builders.values()) {
            if ("lo".equalsIgnoreCase(b.name)) {
                continue;
            }
            List<ParsedAddress> addrs = new ArrayList<>();
            if (b.ip != null && !b.ip.equals("0.0.0.0")) {
                if (b.prefix > 0 || b.mask != null) {
                    int prefix = b.prefix > 0 ? b.prefix : maskToPrefix(b.mask);
                    addrs.add(new ParsedAddress(b.ip + "/" + prefix, InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER));
                } else if (b.prefixUnknownIsBare) {
                    addrs.add(new ParsedAddress(b.ip, InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER));
                } else {
                    addrs.add(new ParsedAddress(b.ip + "/24", InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER));
                }
            }
            String kind = b.name.contains(".") ? InventoryInterface.KIND_VLAN
                    : (b.name.startsWith("bond") ? InventoryInterface.KIND_BOND : InventoryInterface.KIND_PHYSICAL);
            Optional<String> parent = b.name.contains(".") ? Optional.of(b.name.split("\\.")[0]) : Optional.empty();
            Optional<Integer> vlanId = Optional.empty();
            if (b.name.contains(".")) {
                try {
                    vlanId = Optional.of(Integer.parseInt(b.name.split("\\.")[1]));
                } catch (Exception ignored) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(ignored);}
            }
            result.add(new ParsedInterface(b.name, parent, kind, b.state, addrs, vlanId));
        }
        return result;
    }

    /** Structure-only projection of device output for a diagnostic log line: MACs become
     * {@code xx:xx:...}, every digit run becomes {@code #}, whitespace collapses, lines are
     * joined with {@code " | "} and capped at {@code maxLines} -- no address, serial, hostname
     * digit or other identity value survives (Sensitive identity reporting law). */
    static String maskedShape(String output, int maxLines) {
        StringBuilder sb = new StringBuilder();
        int lines = 0;
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (lines++ >= maxLines) {
                sb.append(" | ...");
                break;
            }
            String masked = line
                    .replaceAll("(?i)\\b[0-9a-f]{2}(?::[0-9a-f]{2}){5}\\b", "xx:xx:xx:xx:xx:xx")
                    .replaceAll("\\d+", "#")
                    .replaceAll("\\s+", " ");
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(masked);
        }
        return sb.toString();
    }

    /** For an interface address recorded bare (no prefix in the device's own output), take the
     * prefix from a connected route on that same interface whose network contains the address --
     * real evidence from the same collection pass, never a default. Left bare when no such route
     * exists. */
    /**
     * V184 (G5): as {@link #applyConnectedRoutePrefixes}, but strict -- a bare IPv4 address takes a prefix only when
     * exactly one distinct connected network on the same interface (by exact name) contains it; none, or more than one
     * (an ambiguous shape), leaves the address bare. The address's role is never changed.
     */
    static List<ParsedInterface> applyUniqueConnectedRoutePrefixes(List<ParsedInterface> interfaces, List<ParsedRoute> routes) {
        List<ParsedInterface> result = new ArrayList<>();
        for (ParsedInterface iface : interfaces) {
            List<ParsedAddress> addrs = new ArrayList<>();
            for (ParsedAddress addr : iface.addresses()) {
                if (addr.address().contains("/") || !InventoryAddress.FAMILY_IPV4.equals(addr.family())) {
                    addrs.add(addr);
                    continue;
                }
                List<String> candidates = routes.stream()
                        .filter(r -> InventoryRoute.PROTOCOL_CONNECTED.equals(r.protocol()))
                        .filter(r -> r.interfaceName().map(n -> n.equals(iface.name())).orElse(false))
                        .map(ParsedRoute::destination)
                        .filter(d -> d.matches("\\d{1,3}(?:\\.\\d{1,3}){3}/\\d{1,2}"))
                        .filter(d -> ipv4InNetwork(addr.address(), d))
                        .distinct()
                        .toList();
                addrs.add(candidates.size() == 1
                        ? new ParsedAddress(addr.address() + candidates.get(0).substring(candidates.get(0).indexOf('/')),
                                addr.family(), addr.role())
                        : addr);
            }
            result.add(new ParsedInterface(iface.name(), iface.parent(), iface.kind(), iface.state(), addrs, iface.vlanId()));
        }
        return result;
    }

    static List<ParsedInterface> applyConnectedRoutePrefixes(List<ParsedInterface> interfaces, List<ParsedRoute> routes) {
        List<ParsedInterface> result = new ArrayList<>();
        for (ParsedInterface iface : interfaces) {
            List<ParsedAddress> addrs = new ArrayList<>();
            for (ParsedAddress addr : iface.addresses()) {
                if (addr.address().contains("/") || !InventoryAddress.FAMILY_IPV4.equals(addr.family())) {
                    addrs.add(addr);
                    continue;
                }
                Optional<Integer> prefix = routes.stream()
                        .filter(r -> InventoryRoute.PROTOCOL_CONNECTED.equals(r.protocol()))
                        .filter(r -> r.interfaceName().map(n -> n.equalsIgnoreCase(iface.name())).orElse(false))
                        .filter(r -> r.destination().contains("/"))
                        .filter(r -> ipv4InNetwork(addr.address(), r.destination()))
                        .map(r -> Integer.parseInt(r.destination().substring(r.destination().indexOf('/') + 1)))
                        .findFirst();
                addrs.add(prefix.map(p -> new ParsedAddress(addr.address() + "/" + p, addr.family(), addr.role())).orElse(addr));
            }
            result.add(new ParsedInterface(iface.name(), iface.parent(), iface.kind(), iface.state(), addrs, iface.vlanId()));
        }
        return result;
    }

    private static boolean ipv4InNetwork(String address, String cidr) {
        try {
            String[] net = cidr.split("/");
            int bits = Integer.parseInt(net[1]);
            if (bits < 0 || bits > 32) {
                return false;
            }
            long a = ipv4ToLong(address);
            long n = ipv4ToLong(net[0]);
            long mask = bits == 0 ? 0 : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
            return (a & mask) == (n & mask);
        } catch (Exception e) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(e);
            return false;
        }
    }

    private static long ipv4ToLong(String ip) {
        String[] p = ip.split("\\.");
        if (p.length != 4) {
            throw new IllegalArgumentException(ip);
        }
        long v = 0;
        for (String part : p) {
            v = (v << 8) | (Integer.parseInt(part) & 0xFF);
        }
        return v;
    }

    private static class ClishInterfaceBuilder {
        final String name;
        String state = InventoryInterface.STATE_UP;
        String ip;
        String mask;
        int prefix = 0;
        boolean prefixUnknownIsBare = false;

        ClishInterfaceBuilder(String name) {
            this.name = name;
        }
    }

    static List<ParsedRoute> parseClishRoutes(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        List<ParsedRoute> routes = new ArrayList<>();
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("Codes:") || line.startsWith("---") || line.toLowerCase().startsWith("route table")) {
                continue;
            }
            if (line.startsWith("127.")) {
                continue;
            }

            String proto = InventoryRoute.PROTOCOL_STATIC;
            String remaining = line;
            if (line.matches("^[CSBORKD]\\s+.*")) {
                char code = line.charAt(0);
                proto = switch (code) {
                    case 'C' -> InventoryRoute.PROTOCOL_CONNECTED;
                    case 'B' -> InventoryRoute.PROTOCOL_BGP;
                    case 'O' -> InventoryRoute.PROTOCOL_OSPF;
                    default -> InventoryRoute.PROTOCOL_STATIC;
                };
                remaining = line.substring(1).trim();
            }

            Matcher destMatcher = Pattern.compile("^(default|\\d{1,3}(?:\\.\\d{1,3}){3}(?:/\\d{1,2})?)").matcher(remaining);
            if (!destMatcher.find()) {
                continue;
            }
            String rawDest = destMatcher.group(1);
            String destination = "default".equalsIgnoreCase(rawDest) ? "0.0.0.0/0" : rawDest;
            if (destination.startsWith("127.")) {
                continue;
            }
            if (!destination.contains("/")) {
                destination = destination.equals("0.0.0.0") ? "0.0.0.0/0" : destination + "/32";
            }

            Optional<String> nextHop = Optional.empty();
            Optional<String> ifName = Optional.empty();

            if (remaining.contains("is directly connected")) {
                proto = InventoryRoute.PROTOCOL_CONNECTED;
                Matcher connIf = Pattern.compile("is directly connected(?:,\\s*|\\s+)([a-zA-Z0-9_.-]+)").matcher(remaining);
                if (connIf.find()) {
                    ifName = Optional.of(connIf.group(1));
                }
            } else {
                Matcher viaMatcher = Pattern.compile("via\\s+([0-9.]+)(?:,\\s*([a-zA-Z0-9_.-]+))?").matcher(remaining);
                if (viaMatcher.find()) {
                    nextHop = Optional.of(viaMatcher.group(1));
                    if (viaMatcher.group(2) != null && !viaMatcher.group(2).isBlank()) {
                        ifName = Optional.of(viaMatcher.group(2));
                    }
                }
                if (ifName.isEmpty()) {
                    Matcher devMatcher = Pattern.compile("(?:dev|interface)\\s+([a-zA-Z0-9_.-]+)").matcher(remaining);
                    if (devMatcher.find()) {
                        ifName = Optional.of(devMatcher.group(1));
                    }
                }
            }

            routes.add(new ParsedRoute(destination, nextHop, ifName, proto, Optional.empty()));
        }
        return routes;
    }
}
