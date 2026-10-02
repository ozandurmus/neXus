package com.securityexpert.nexus.ui2.service.policy;

import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Bounded recursive descent, with three-valued matching for unresolved and negated address cells. */
final class PolicyRuleQuery {
    enum Truth {
        YES, NO, UNKNOWN;
        Truth not() { return this == YES ? NO : this == NO ? YES : UNKNOWN; }
        Truth and(Truth b) { return this == NO || b == NO ? NO : this == YES && b == YES ? YES : UNKNOWN; }
        Truth or(Truth b) { return this == YES || b == YES ? YES : this == NO && b == NO ? NO : UNKNOWN; }
    }
    interface Match { Truth test(Rule rule); }
    private static final Set<String> FIELDS = Set.of("source.ip", "destination.ip", "service", "application", "action", "name", "comment", "user", "zone.from", "zone.to", "enabled", "expired", "time", "hits", "lasthit.days");
    private static final Pattern TOKEN = Pattern.compile("\\G\\s*(AND\\b|OR\\b|NOT\\b|[()=<>]|[0-9]+|[a-zA-Z][a-zA-Z.]*|true\\b|false\\b|'(?:[^'\\\\]|\\\\.)*')", Pattern.CASE_INSENSITIVE);
    private final List<String> tokens = new ArrayList<>();
    private final Map<String, PolicyObject> objects;
    private final java.time.Instant now;
    private final String vendor;
    private int pos;
    private PolicyRuleQuery(String query, Map<String, PolicyObject> objects, java.time.Instant now, String vendor) {
        this.objects = objects; this.now = now; this.vendor = vendor;
        var matcher = TOKEN.matcher(query);
        int offset = 0;
        while (matcher.find()) { tokens.add(matcher.group(1)); offset = matcher.end(); }
        if (!query.substring(offset).isBlank() || tokens.size() > 100) throw invalid();
    }
    static Predicate<Rule> compile(String query, Map<String, PolicyObject> objects, java.time.Instant now) { return compile(query, objects, now, ""); }
    static Predicate<Rule> compile(String query, Map<String, PolicyObject> objects, java.time.Instant now, String vendor) {
        if (!query.contains("=") && !query.contains(">") && !query.contains("<") && !query.matches("(?is).*\\b(AND|OR|NOT)\\b.*") && !query.contains("(") && !query.contains(")")) {
            String term = query.toLowerCase(Locale.ROOT);
            return r -> (r.name() + " " + r.comment()).toLowerCase(Locale.ROOT).contains(term);
        }
        var parser = new PolicyRuleQuery(query, objects, now, vendor);
        Match match = parser.or(0);
        if (parser.pos != parser.tokens.size()) throw invalid();
        return rule -> match.test(rule) == Truth.YES;
    }
    private Match or(int depth) {
        Match result = and(depth);
        while (take("OR")) { Match a = result, b = and(depth); result = r -> a.test(r).or(b.test(r)); }
        return result;
    }
    private Match and(int depth) {
        Match result = atom(depth);
        while (take("AND")) { Match a = result, b = atom(depth); result = r -> a.test(r).and(b.test(r)); }
        return result;
    }
    private Match atom(int depth) {
        if (depth > 16) throw invalid();
        if (take("NOT")) { Match child = atom(depth + 1); return r -> child.test(r).not(); }
        if (take("(")) { Match child = or(depth + 1); require(")"); return child; }
        String field = next().toLowerCase(Locale.ROOT);
        if (!FIELDS.contains(field)) throw invalid();
        if (field.equals("hits") || field.equals("lasthit.days")) {
            String operator = next();
            if (!Set.of("=", ">", "<").contains(operator)) throw invalid();
            String number = next();
            if (!number.matches("[0-9]{1,19}")) throw invalid();
            final long expected;
            try { expected = Long.parseLong(number); } catch (NumberFormatException invalid) { throw invalid(); }
            return r -> {
                if (r.hitCounts() == null) return Truth.UNKNOWN;
                Long actual = r.hitCounts().hits();
                if (field.equals("lasthit.days")) {
                    if (r.hitCounts().lastHit() == null) return Truth.UNKNOWN;
                    try {
                        var last = java.time.Instant.parse(r.hitCounts().lastHit());
                        if (last.isAfter(now)) return Truth.UNKNOWN;
                        actual = java.time.Duration.between(last, now).toDays();
                    } catch (RuntimeException invalid) { return Truth.UNKNOWN; }
                }
                if (actual == null) return Truth.UNKNOWN;
                return truth(operator.equals("=") ? actual == expected : operator.equals(">") ? actual > expected : actual < expected);
            };
        }
        require("="); String value = next();
        if (value.startsWith("'")) value = value.substring(1, value.length() - 1).replace("\\'", "'").replace("\\\\", "\\");
        else if (!Set.of("true", "false").contains(value.toLowerCase(Locale.ROOT))) throw invalid();
        if (Set.of("enabled", "expired").contains(field) && !Set.of("true", "false").contains(value.toLowerCase(Locale.ROOT))) throw invalid();
        if (field.equals("time") && !Set.of("any", "bound").contains(value)) throw invalid();
        final String expected = value;
        final long[] ip = field.endsWith(".ip") ? interval(value) : null;
        final Map<Cell, Truth> cells = new HashMap<>();
        return r -> switch (field) {
            case "source.ip", "destination.ip" -> cells.computeIfAbsent(field.equals("source.ip") ? r.source() : r.destination(), c -> addresses(c, ip));
            case "enabled" -> r.enabled() == null ? Truth.UNKNOWN : truth(r.enabled() == Boolean.parseBoolean(expected));
            case "expired" -> { String status = PolicyRuleMetrics.time(r, objects, now, vendor).status(); yield status.equals("unknown") ? Truth.UNKNOWN : truth(status.equals("expired") == Boolean.parseBoolean(expected)); }
            case "time" -> { String status = PolicyRuleMetrics.time(r, objects, now, vendor).status(); yield status.equals("unknown") ? Truth.UNKNOWN : truth(expected.equals("any") == status.equals("always")); }
            case "service", "application" -> cells.computeIfAbsent(field.equals("service") ? r.service() : r.application(), c -> refs(c, expected));
            default -> {
                List<String> values = switch (field) {
                    case "name" -> List.of(r.name()); case "comment" -> List.of(r.comment()); case "action" -> List.of(r.action());
                    case "zone.from" -> r.extras().getOrDefault("from", List.of());
                    case "zone.to" -> r.extras().getOrDefault("to", List.of());
                    case "user" -> r.extras().getOrDefault("source-user", List.of()); default -> List.of();
                };
                yield values.isEmpty() ? Truth.UNKNOWN : truth(values.stream().anyMatch(v -> v.equalsIgnoreCase(expected)));
            }
        };
    }
    private Truth refs(Cell cell, String expected) {
        Truth match = Truth.NO;
        for (String id : cell.refs()) match = match.or(ref(id, expected, new HashSet<>()));
        return cell.refs().isEmpty() ? Truth.UNKNOWN : cell.negated() ? match.not() : match;
    }
    private Truth ref(String id, String expected, Set<String> path) {
        PolicyObject o = objects.get(id);
        if (o == null || !path.add(id) || path.size() > 32) return Truth.UNKNOWN;
        if (o.name().equalsIgnoreCase(expected)) { path.remove(id); return Truth.YES; }
        if (!o.status().equals("RESOLVED")) { path.remove(id); return Truth.UNKNOWN; }
        Truth result = Truth.NO;
        if (o.type().equals("any")) result = Truth.YES;
        for (String value : o.values()) {
            if (value.equalsIgnoreCase(expected)) result = Truth.YES;
            String protocol = o.values().stream().filter(v -> v.startsWith("protocol: ")).map(v -> v.substring(10)).findFirst().orElse("");
            if (value.startsWith("port: ")) result = result.or(service(protocol, value.substring(6), expected));
            if (value.matches("protocol/(tcp|udp)/port: .*")) {
                String service = value.replace("protocol/", "").replace("/port: ", "/");
                String[] parts = service.split("/", 2);
                result = result.or(service(parts[0], parts[1], expected));
            }
        }
        for (String child : o.members()) result = result.or(ref(child, expected, path));
        path.remove(id); return result;
    }
    private static Truth service(String protocol, String ports, String expected) {
        if ((protocol + "/" + ports).equalsIgnoreCase(expected)) return Truth.YES;
        String[] query = expected.split("/", 2);
        if (query.length != 2 || !protocol.equalsIgnoreCase(query[0]) || !query[1].matches("[0-9]{1,5}")) return Truth.NO;
        int port = Integer.parseInt(query[1]);
        if (port > 65535) return Truth.NO;
        if (!ports.matches("[0-9, -]+")) return Truth.UNKNOWN;
        for (String range : ports.split(",")) {
            String[] parts = range.trim().split("-", 2);
            try {
                int from = Integer.parseInt(parts[0]), to = parts.length == 1 ? from : Integer.parseInt(parts[1]);
                if (from > to || from < 0 || to > 65535) return Truth.UNKNOWN;
                if (port >= from && port <= to) return Truth.YES;
            } catch (NumberFormatException invalid) { return Truth.UNKNOWN; }
        }
        return Truth.NO;
    }
    private Truth addresses(Cell cell, long[] query) {
        List<long[]> ranges = new ArrayList<>();
        boolean complete = !cell.refs().isEmpty();
        for (String id : cell.refs()) complete &= ranges(id, ranges, new HashSet<>());
        ranges.sort(Comparator.comparingLong(r -> r[0]));
        long cursor = query[0]; boolean overlap = false;
        for (long[] range : ranges) {
            if (range[1] < query[0] || range[0] > query[1]) continue;
            overlap = true;
            if (range[0] <= cursor) cursor = Math.max(cursor, range[1] + 1);
        }
        if (cell.negated()) return overlap ? Truth.NO : complete ? Truth.YES : Truth.UNKNOWN;
        return cursor > query[1] ? Truth.YES : complete ? Truth.NO : Truth.UNKNOWN;
    }
    private boolean ranges(String id, List<long[]> out, Set<String> path) {
        PolicyObject o = objects.get(id);
        if (o == null || !o.status().equals("RESOLVED") || !path.add(id) || path.size() > 32) return false;
        if (o.type().equals("any")) { out.add(new long[]{0, 0xffffffffL}); path.remove(id); return true; }
        boolean complete = true, found = false;
        String first = null, last = null, subnet = null, prefix = null;
        for (String value : o.values()) {
            if (value.startsWith("ip-address-first: ")) first = value.substring(18);
            else if (value.startsWith("ip-address-last: ")) last = value.substring(17);
            else if (value.startsWith("subnet4: ")) subnet = value.substring(9);
            else if (value.startsWith("mask-length4: ")) prefix = value.substring(14);
            else if (value.matches("(?:(?:ipv4-address|ip-netmask|ip-range): )?[0-9./ -]+")) {
                try { out.add(interval(value.replaceFirst("^[a-z0-9-]+: ", ""))); found = true; }
                catch (IllegalArgumentException invalid) { complete = false; }
            }
        }
        try {
            if (first != null && last != null) { out.add(interval(first + "-" + last)); found = true; }
            if (subnet != null && prefix != null) { out.add(interval(subnet + "/" + prefix)); found = true; }
        } catch (IllegalArgumentException invalid) { complete = false; }
        for (String member : o.members()) { complete &= ranges(member, out, path); found = true; }
        path.remove(id); return complete && found;
    }
    static long[] interval(String value) {
        try {
            String[] range = value.trim().split("-", -1);
            if (range.length == 2) { long a = ipv4(range[0].trim()), b = ipv4(range[1].trim()); if (a > b) throw invalid(); return new long[]{a,b}; }
            String[] cidr = value.split("/", -1);
            if (cidr.length > 2) throw invalid();
            long ip = ipv4(cidr[0]); int prefix = cidr.length == 1 ? 32 : Integer.parseInt(cidr[1]);
            if (prefix < 0 || prefix > 32) throw invalid();
            long size = 1L << (32 - prefix), start = (ip / size) * size;
            return new long[]{start, start + size - 1};
        } catch (RuntimeException invalid) { throw invalid(); }
    }
    private static long ipv4(String value) {
        String[] parts = value.split("\\.", -1); if (parts.length != 4) throw invalid(); long result = 0;
        for (String p : parts) { if (!p.matches("0|[1-9][0-9]{0,2}")) throw invalid(); int n = Integer.parseInt(p); if (n > 255) throw invalid(); result = (result << 8) | n; }
        return result;
    }
    private boolean take(String value) { if (pos < tokens.size() && tokens.get(pos).equalsIgnoreCase(value)) { pos++; return true; } return false; }
    private void require(String value) { if (!take(value)) throw invalid(); }
    private String next() { if (pos >= tokens.size()) throw invalid(); return tokens.get(pos++); }
    private static Truth truth(boolean value) { return value ? Truth.YES : Truth.NO; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid rule query"); }
}
