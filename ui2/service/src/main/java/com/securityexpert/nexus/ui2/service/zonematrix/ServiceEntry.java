package com.securityexpert.nexus.ui2.service.zonematrix;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One entry of a matrix cell's service list (ZONE_MATRIX_CONTRACT.md section 4.2, FROZEN 2026-11-13), in canonical text.
 *
 * <p>Forms: {@code tcp/443}, {@code tcp/8000-8099}, {@code udp/53} (ports 0-65535, the range of
 * {@code PolicyNormalization}); {@code icmp/8}, {@code icmp/8/0}, {@code icmp6/128} (type and optional code 0-255);
 * {@code ipproto/50} (0-255); {@code app:pan:<application-name>} (opaque, compared by exact string, section 4.3); and
 * {@code set:<name>}, a named service set of the same version. A value that is not in canonical form is refused, never
 * corrected (the same rule as zone blocks, section 3.1): no upper case, no leading zero, no {@code tcp/443-443}.</p>
 *
 * <p>TCP and UDP entries are evaluable since the first delivery, Palo Alto applications by name since delivery step 7b
 * (section 4.3: by exact name, for Palo Alto {@code application-default} rules only), and IPv4 ICMP entries
 * ({@code icmp/<type>}, {@code icmp/<type>/<code>}) since delivery step 7d ({@code A0-I1}, Check Point
 * {@code service-icmp} with stored type and code); the others ({@code icmp6/*}, {@code ipproto/*}) are stored as entered
 * and marked with the prerequisite that makes them evaluable (section 4.2 table, section 10.1 step 7).</p>
 */
public record ServiceEntry(Kind kind, String text, int low, int high, String token) {
    public enum Kind { TCP, UDP, ICMP, ICMP6, IPPROTO, APP_PAN, SET }

    public static final int MAX_SET_NAME = 64, MAX_APP_NAME = 128;
    private static final Pattern PORTS = Pattern.compile("(tcp|udp)/(0|[1-9][0-9]{0,4})(?:-(0|[1-9][0-9]{0,4}))?");
    private static final Pattern ICMP = Pattern.compile("(icmp|icmp6)/(0|[1-9][0-9]{0,2})(?:/(0|[1-9][0-9]{0,2}))?");
    private static final Pattern IPPROTO = Pattern.compile("ipproto/(0|[1-9][0-9]{0,2})");
    private static final Pattern SET_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0," + (MAX_SET_NAME - 1) + "}");

    public static ServiceEntry parse(String text) {
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("empty service entry");
        Matcher m = PORTS.matcher(text);
        if (m.matches()) {
            int a = Integer.parseInt(m.group(2)), b = m.group(3) == null ? a : Integer.parseInt(m.group(3));
            if (a > 65535 || b > 65535) throw new IllegalArgumentException("ports are 0-65535");
            if (m.group(3) != null && a >= b) throw new IllegalArgumentException("a port range runs from a lower to a higher port");
            return new ServiceEntry(m.group(1).equals("tcp") ? Kind.TCP : Kind.UDP, text, a, b, null);
        }
        m = ICMP.matcher(text);
        if (m.matches()) {
            int type = Integer.parseInt(m.group(2)), code = m.group(3) == null ? -1 : Integer.parseInt(m.group(3));
            if (type > 255 || code > 255) throw new IllegalArgumentException("ICMP type and code are 0-255");
            return new ServiceEntry(m.group(1).equals("icmp") ? Kind.ICMP : Kind.ICMP6, text, type, code, null);
        }
        m = IPPROTO.matcher(text);
        if (m.matches()) {
            int number = Integer.parseInt(m.group(1));
            if (number > 255) throw new IllegalArgumentException("IP protocol numbers are 0-255");
            return new ServiceEntry(Kind.IPPROTO, text, number, number, null);
        }
        if (text.startsWith("app:pan:")) {
            String name = text.substring("app:pan:".length());
            if (name.isEmpty() || name.length() > MAX_APP_NAME || name.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)))
                throw new IllegalArgumentException("a Palo Alto application name is 1-" + MAX_APP_NAME + " characters without spaces");
            return new ServiceEntry(Kind.APP_PAN, text, 0, 0, name);
        }
        if (text.startsWith("set:")) {
            String name = text.substring("set:".length());
            validateSetName(name);
            return new ServiceEntry(Kind.SET, text, 0, 0, name);
        }
        throw new IllegalArgumentException("not a service entry (tcp/, udp/, icmp/, icmp6/, ipproto/, app:pan:, set:)");
    }

    static void validateSetName(String name) {
        if (name == null || !SET_NAME.matcher(name).matches())
            throw new IllegalArgumentException("a service set name is 1-" + MAX_SET_NAME + " letters, digits, '_', '.' or '-'");
    }

    /** Whether the current engine decides this entry, and if not, which prerequisite is missing (section 4.2, section 10.1 step 7). */
    public String notEvaluableReason() {
        return switch (kind) {
            case TCP, UDP, SET, APP_PAN, ICMP -> null;
            case ICMP6 -> "ICMPv6 is evaluated after IPv6 address evaluation";
            case IPPROTO -> "IP protocol numbers are evaluated after hygiene model R4 and collection fix C4";
        };
    }

    @Override public String toString() { return text; }

    /**
     * What a list of entries permits, for the loosening diff: merged port ranges per protocol plus exact tokens for the
     * other kinds. Sets are expanded by the caller before this is built.
     */
    public record Coverage(Map<Kind, List<int[]>> ports, Set<String> tokens) {
        public static Coverage of(Collection<ServiceEntry> entries) {
            Map<Kind, List<int[]>> ports = new EnumMap<>(Kind.class);
            Set<String> tokens = new TreeSet<>();
            for (ServiceEntry e : entries) {
                switch (e.kind) {
                    case TCP, UDP -> ports.computeIfAbsent(e.kind, k -> new ArrayList<>()).add(new int[]{e.low, e.high});
                    case SET -> throw new IllegalArgumentException("expand sets before building coverage");
                    default -> tokens.add(e.text);
                }
            }
            ports.replaceAll((k, ranges) -> merge(ranges));
            return new Coverage(ports, tokens);
        }

        private static List<int[]> merge(List<int[]> ranges) {
            ranges.sort(Comparator.comparingInt(r -> r[0]));
            List<int[]> out = new ArrayList<>();
            for (int[] r : ranges) {
                if (!out.isEmpty() && r[0] <= out.get(out.size() - 1)[1] + 1)
                    out.get(out.size() - 1)[1] = Math.max(out.get(out.size() - 1)[1], r[1]);
                else out.add(new int[]{r[0], r[1]});
            }
            return out;
        }

        /** True when {@code entry} permits something this coverage does not. */
        public boolean adds(ServiceEntry entry) {
            return switch (entry.kind) {
                case TCP, UDP -> ports.getOrDefault(entry.kind, List.of()).stream().noneMatch(r -> r[0] <= entry.low && entry.high <= r[1]);
                case ICMP, ICMP6 -> !tokens.contains(entry.text)
                        && !(entry.high >= 0 && tokens.contains((entry.kind == Kind.ICMP ? "icmp/" : "icmp6/") + entry.low));
                case SET -> throw new IllegalArgumentException("expand sets before comparing coverage");
                default -> !tokens.contains(entry.text);
            };
        }
    }
}
