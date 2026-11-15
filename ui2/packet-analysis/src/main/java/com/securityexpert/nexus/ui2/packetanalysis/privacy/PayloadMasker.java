package com.securityexpert.nexus.ui2.packetanalysis.privacy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The masked projection of payload-derived text in Packet Analysis responses (engine report, findings export, case
 * DuckDB views). Every string field of these payloads has a classification in {@link PayloadFields}; a value derived
 * from packet payload (HTTP request lines, hosts, TLS SNI and certificate names, SIP identities, DNS questions, F5 and
 * Check Point trailer text) is replaced by a deterministic keyed token from {@link Tokens}, so equal inputs keep equal
 * tokens and an analyst still sees "same host / same Call-ID" relationships. Structure stays: an HTTP method,
 * version and port stay, each path segment and the host become tokens. Numbers, booleans and enum codes are never
 * touched.
 *
 * <p>Fail closed: a string under a key that {@link PayloadFields} does not classify is replaced by an opaque
 * {@code TXT-} token, never passed through. Free-text fields (engine prose, Wireshark filters) keep their text, but
 * every payload value seen anywhere in the same response is replaced inside them as well.</p>
 *
 * <p>Addresses are not this class's job: address fields keep their value here and the service's subnet-preserving IP
 * masker rewrites them for the replay viewer. Device and point names likewise stay with the service's name
 * pseudonymizer; only names that come from a capture or an import (Fortinet interface and source names, capture
 * interface names, file paths) are masked here, and only in {@code names} mode.</p>
 */
public final class PayloadMasker {

    /** Keyed, deterministic replacements supplied by the product's privacy machinery. */
    public interface Tokens {
        /** A token for one payload value of one kind ({@code HOST}, {@code PATH}, {@code CALL}, ...). */
        String token(String kind, String raw);

        /** Names mode: the pseudonym of a capture, import source or other file name. */
        String captureName(String raw);

        /** Names mode: the pseudonym of an interface name. */
        String interfaceName(String raw);
    }

    /** Token kinds; each is the prefix of its tokens. */
    public static final List<String> KINDS = List.of("HOST", "PATH", "QUERY", "USER", "CALL", "SIP", "CERT", "VTXT",
            "VNAME", "TXT");

    /** One token: the kind, a hyphen and ten upper-case hex digits of a keyed hash. */
    public static final Pattern TOKEN = Pattern.compile("(?:" + String.join("|", KINDS) + ")-[0-9A-F]{10}");

    public static final String REDACTED_PATH = "[REDACTED_PATH]";

    private static final Set<String> CONNECT = Set.of("CONNECT");
    /** A whole HTTP/1.x request line (or the request part of an F5 / proxy record). */
    static final Pattern HTTP_LINE = Pattern.compile("([A-Z][A-Z0-9_-]{1,23}) "
            + "(\\*|/\\S*|[A-Za-z][A-Za-z0-9+.-]*://\\S+|\\S+:\\d{1,5})(?: (HTTP/\\d+(?:\\.\\d+)?))?");
    private static final Pattern ABSOLUTE = Pattern.compile("([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)(.*)",
            Pattern.DOTALL);
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");
    private static final int MIN_REPLACE = 4;

    private static final Pattern EMBEDDED_URL = Pattern.compile("(?i)\\bhttps?://[^\\s\"'<>()\\[\\],;]+");
    private static final Pattern KEYED_DN = Pattern.compile(
            "(?i)\\b(user(?:name)?|admin|account|principal|login|subject|issuer|dn)\\s*([=:])\\s*"
            + "(\"(?:CN|UID)=[^\"\\r\\n]+\"|(?:CN|UID)=[^\\s,;)\"']+(?:\\s*,\\s*[A-Za-z]{1,10}=[^\\s,;)\"']+)*"
            + "|(?:/[A-Za-z]{1,10}=[^\\s/;)\"']+)+)");
    private static final Pattern STANDALONE_DN = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_./=-])(?:\"((?:CN|UID)=[^\"\\r\\n]+)\""
            + "|((?:CN|UID)=(?:[^,\\s;)\"']+|(?:\\s+(?=[A-Za-z0-9-]+,|[A-Za-z]{1,10}=)))+"
            + "(?:\\s*,\\s*[A-Za-z]{1,10}=[^\\s,;)\"']+)*))");
    private static final Pattern SLASH_DN = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_./-])((?:/[A-Za-z]{1,10}=[^\\s/;,\"')]+){2,})");
    private static final Pattern DOMAIN_USER = Pattern.compile(
            "(?<![A-Za-z0-9_./:-])([A-Za-z][A-Za-z0-9._-]{1,30})\\\\\\\\?([A-Za-z_][A-Za-z0-9._$-]{1,63})(?![A-Za-z0-9_./\\\\-])");
    private static final Pattern USER_AT_REALM = Pattern.compile(
            "(?<![A-Za-z0-9_./:-])((?:[A-Za-z][A-Za-z0-9_-]{1,15}/)?[A-Za-z0-9._%+$-]{2,64})"
            + "@([A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*)"
            + "(?![A-Za-z0-9_.-])");
    private static final Pattern KEYED_PRINCIPAL = Pattern.compile(
            "(?i)\\b(user(?:name)?|admin|account|principal|login)\\s*([=:])\\s*\"?([A-Za-z0-9._\\\\@-]{2,64})\"?");
    private static final Pattern KEYED_SERIAL = Pattern.compile(
            "(?i)\\b(serial(?:_number)?|sn)\\s*([=:])\\s*\"?([A-Za-z0-9-]{6,32})\"?");
    private static final Pattern FREE_TEXT_FQDN = Pattern.compile(
            "(?<![A-Za-z0-9._/@:-])([A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
            + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+)(?![A-Za-z0-9_/-])");
    private static final Pattern HOST_BEFORE_STATE = Pattern.compile(
            "(?i)\\b([A-Za-z][A-Za-z0-9_-]{2,62})"
            + "(\\s+(?:is\\s+(?:down|up|unreachable|reachable|active|standby|alive|dead|offline|online|degraded|healthy|stalled|reset)"
            + "|went\\s+(?:down|up)|(?:entered|in)\\s+(?:active|standby|down|up|fault|init))\\b)");
    private static final Pattern HOST_AFTER_CONTEXT = Pattern.compile(
            "(?i)(\\b(?:host(?:name)?|peer|gateway|firewall|router|switch|proxy|node|member|appliance|from|toward|towards|via)\\s+)"
            + "([A-Za-z][A-Za-z0-9_-]{2,62})\\b");
    private static final Pattern STANDALONE_HOST_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9._/@:-])([A-Za-z][A-Za-z0-9_-]{5,62})(?![A-Za-z0-9._/-])");
    private static final Pattern SIZE_OR_UNIT = Pattern.compile(
            "(?i)^\\d+(?:\\.\\d+)?(?:b|kb|mb|gb|tb|pb|kib|mib|gib|tib|bps|kbps|mbps|gbps|ms|us|ns|s|m|h|d|hz|khz|mhz|ghz|pct)?$");
    private static final Pattern ORDINAL_TOKEN = Pattern.compile(
            "(?i)^(?:\\d+(?:st|nd|rd|th)|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)$");
    private static final Pattern DEVICE_AFFIX = Pattern.compile(
            "(?i)^(?:(?:fw|gw|rtr|sw|vpn|vsx|mds|clm|cma|sxl|pxy|srv|firewall|gateway|router|switch|proxy)[a-z0-9]{2,}"
            + "|[a-z0-9]{2,}(?:fw|gw|rtr|sw|vpn|vsx|mds|clm|cma|sxl|pxy|srv|firewall|gateway|router|switch|proxy)\\d*)$");

    private static final Set<String> NON_PROSE_TEXT_KEYS = Set.of(
            "content_filter", "display_filter", "flow_filter", "id",
            "device", "file", "name", "project", "capture_name", "display_name", "point_label", "hostname", "zone",
            "rule_name", "policy_name", "section_name", "interface_name", "vendor_interface",
            "cluster_member_ref", "source_zone", "destination_zone", "ingress_zone", "egress_zone",
            "ingress_interface_name", "egress_interface_name", "matched_source_objects",
            "matched_destination_objects", "matched_service_objects", "virtual_system",
            "virtual_systems", "member", "label", "display_label", "segment_label",
            "forward_labels", "reverse_labels", "forward", "reverse", "evidence_rank", "observation_point",
            "scope", "badge", "baseline_method", "evidence_time_source", "time_source", "per_hop_added");

    private static final Set<String> FILE_EXTENSIONS = Set.of(
            "pcap", "pcapng", "cap", "txt", "log", "json", "gz", "tgz", "tar", "zip", "csv", "elg", "xml", "html", "pdf", "xlsx");

    private static final Set<String> FILTER_PREFIXES = Set.of(
            "ip", "ipv6", "tcp", "udp", "icmp", "icmpv6", "eth", "vlan", "arp", "dns", "http", "tls", "ssl",
            "sip", "sdp", "rtp", "rtcp", "frame", "f5ethtrailer", "fw1", "data");

    private static final Set<String> VOCABULARY_STOPWORDS = Set.of(
            "jan", "january", "feb", "february", "mar", "march", "apr", "april", "may", "jun", "june",
            "jul", "july", "aug", "august", "sep", "sept", "september", "oct", "october", "nov", "november", "dec", "december",
            "mon", "monday", "tue", "tues", "tuesday", "wed", "wednesday", "thu", "thur", "thurs", "thursday",
            "fri", "friday", "sat", "saturday", "sun", "sunday",
            "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth",
            "client", "server", "source", "target", "destination", "upstream", "downstream", "forward", "reverse",
            "packet", "packets", "frame", "frames", "segment", "segments", "stream", "streams", "window", "buffer",
            "session", "sessions", "connection", "connections", "transaction", "transactions", "request", "requests",
            "response", "responses", "capture", "captures", "interface", "interfaces", "traffic", "network", "networks",
            "device", "devices", "point", "points", "cluster", "member", "policy", "section", "object", "objects",
            "unknown", "normal", "healthy", "degraded", "stalled", "failed", "dropped", "blocked", "rejected", "reset",
            "active", "standby", "online", "offline", "reachable", "unreachable", "selected", "observed", "expected",
            "one-way", "tie-break", "in-flight", "pre-nat", "post-nat", "non-zero", "half-closed", "client-side",
            "server-side", "same-capture", "request-bearing", "selected-window", "tshark-decoded",
            "sha256", "sha384", "sha512", "base64", "utf-8", "sha-256", "tls12", "tls13", "http2", "http3",
            "ipv4", "ipv6", "icmpv6", "dhcpv6", "ikev2", "ospfv2", "ospfv3", "snmpv3");

    private final Tokens tokens;
    private final boolean names;
    /**
     * Raw payload value -> its masked form, for replacement inside free text; keyed case-folded without a root dot
     * (host names are case-insensitive, RFC 4343). The first value seen wins a case-only collision.
     */
    private final Map<String, String> seenFolded = new LinkedHashMap<>();
    /** Folded raw values by their first two characters, longest first: a linear scan instead of a huge regex. */
    private final Map<String, List<String>> byPrefix = new HashMap<>();
    /** Device, point and capture names present in keyed fields of the same response. */
    private final Set<String> responseDeviceNames = new HashSet<>();

    private PayloadMasker(Tokens tokens, boolean names) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.names = names;
    }

    /**
     * Masks one response body without changing the input.
     *
     * @param body  a JSON-like tree of maps, lists, strings, numbers, booleans and nulls
     * @param names also mask capture-derived names (the replay viewer); payload values are masked either way
     */
    public static Object mask(Object body, Tokens tokens, boolean names) {
        PayloadMasker masker = new PayloadMasker(tokens, names);
        masker.collect(body, null, null, null);
        for (String raw : masker.seenFolded.keySet()) {
            masker.byPrefix.computeIfAbsent(raw.substring(0, 2), k -> new ArrayList<>()).add(raw);
        }
        masker.byPrefix.values().forEach(l -> l.sort(Comparator.comparingInt(String::length).reversed()));
        return masker.rewrite(body, null, null, null);
    }

    /** True when the value is a token, or a request line / URI whose every non-structural part is a token. */
    public static boolean isMasked(String value) {
        if (TOKEN.matcher(value).matches() || REDACTED_PATH.equals(value)) return true;
        Matcher m = HTTP_LINE.matcher(value);
        String target = m.matches() ? m.group(2) : value;
        if (!TOKEN.matcher(target).find() && !"*".equals(target)) return false;
        // What is left of the target once its tokens are gone: structure, a scheme, a port, an address literal.
        String rest = TOKEN.matcher(target).replaceAll("").replaceFirst("^[a-z][a-z0-9+.-]*://", "");
        return rest.matches("[/?#*:@.\\[\\]0-9]*");
    }

    // ---- passes --------------------------------------------------------------------------------------------

    private void collect(Object node, String key, String context, Map<?, ?> owner) {
        if (node instanceof Map<?, ?> map) {
            String inner = PayloadFields.context(key, context);
            for (Map.Entry<?, ?> e : map.entrySet()) {
                collect(e.getValue(), String.valueOf(e.getKey()), inner, map);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) collect(item, key, context, owner);
        } else if (node instanceof String s && !s.isEmpty()) {
            if (isResponseDeviceKey(key)) {
                responseDeviceNames.add(fold(s));
            }
            PayloadFields.Field field = PayloadFields.field(key, context, owner);
            String masked = switch (field) {
                case KEEP -> null;
                case ADDRESS, TEXT -> requestLine(s);
                default -> maskValue(field, s);
            };
            if (masked != null && !masked.equals(s) && s.length() >= MIN_REPLACE) {
                seenFolded.putIfAbsent(fold(s), masked);
            }
        }
    }

    private Object rewrite(Object node, String key, String context, Map<?, ?> owner) {
        if (node instanceof Map<?, ?> map) {
            String inner = PayloadFields.context(key, context);
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                out.put(replaceSeen(k), rewrite(e.getValue(), k, inner, map));
            }
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(rewrite(item, key, context, owner));
            return out;
        }
        if (node instanceof String s && !s.isEmpty()) {
            PayloadFields.Field field = PayloadFields.field(key, context, owner);
            return switch (field) {
                case KEEP -> s;
                case ADDRESS -> {
                    String line = requestLine(s);
                    yield line != null ? line : replaceSeen(s);
                }
                case TEXT -> {
                    String line = requestLine(s);
                    if (line != null) yield line;
                    String seenReplaced = replaceSeen(s);
                    yield isProseTextKey(key) ? maskFreeText(seenReplaced) : seenReplaced;
                }
                default -> maskValue(field, s);
            };
        }
        return node;
    }

    /** Replaces every whole occurrence (not inside a longer name or token) of a seen payload value. */
    private String replaceSeen(String text) {
        if (byPrefix.isEmpty() || text.length() < MIN_REPLACE) return text;
        StringBuilder out = null;
        int last = 0;
        int i = 0;
        while (i <= text.length() - MIN_REPLACE) {
            if (i > 0 && nameChar(text.charAt(i - 1), true)) {
                i++;
                continue;
            }
            List<String> candidates = byPrefix.get(text.substring(i, i + 2).toLowerCase(Locale.ROOT));
            String hit = null;
            if (candidates != null) {
                for (String raw : candidates) {
                    int end = i + raw.length();
                    if (text.regionMatches(true, i, raw, 0, raw.length())
                            && (end == text.length() || !nameChar(text.charAt(end), false))) {
                        hit = raw;
                        break;
                    }
                }
            }
            if (hit == null) {
                i++;
                continue;
            }
            if (out == null) out = new StringBuilder(text.length());
            out.append(text, last, i).append(seenFolded.get(hit));
            i += hit.length();
            last = i;
        }
        if (out == null) return text;
        return out.append(text, last, text.length()).toString();
    }

    /** Characters that continue a name: a value is replaced only where it is not part of a longer name. */
    private static boolean nameChar(char c, boolean before) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || before && c == '.';
    }

    private static String fold(String s) {
        String f = s.toLowerCase(Locale.ROOT);
        return f.length() > 1 && f.endsWith(".") ? f.substring(0, f.length() - 1) : f;
    }

    // ---- value treatments ----------------------------------------------------------------------------------

    private String maskValue(PayloadFields.Field field, String s) {
        if (TOKEN.matcher(s).matches()) return s;
        return switch (field) {
            case HOST -> host(s);
            case HTTP_REQUEST -> {
                String line = requestLine(s);
                yield line != null ? line : host(s);
            }
            case URI -> target(null, s);
            case CALL_ID -> tokens.token("CALL", s);
            case SIP_IDENTITY -> tokens.token("SIP", s);
            case CERT_NAME -> tokens.token("CERT", s);
            case VENDOR_TEXT -> tokens.token("VTXT", s);
            case VENDOR_NAME -> tokens.token("VNAME", s);
            case CAPTURE_NAME -> names ? tokens.captureName(s) : s;
            case INTERFACE_NAME -> names ? tokens.interfaceName(s) : s;
            case FILE_PATH -> names ? REDACTED_PATH : s;
            case UNCLASSIFIED -> tokens.token("TXT", s);
            default -> s;
        };
    }

    /** The masked form of a whole HTTP request line, or {@code null} when the text is not one. */
    String requestLine(String s) {
        Matcher m = HTTP_LINE.matcher(s);
        if (!m.matches()) return null;
        String method = m.group(1);
        String target = m.group(2);
        boolean authority = !target.startsWith("/") && !target.equals("*") && !target.contains("://");
        if (authority && !CONNECT.contains(method)) return null;
        String version = m.group(3);
        return method + " " + target(method, target) + (version == null ? "" : " " + version);
    }

    /** A request target or URI: path segments, query and host become tokens; scheme, port and slashes stay. */
    private String target(String method, String target) {
        if (target.equals("*")) return target;
        if (target.startsWith("/")) return path(target);
        Matcher abs = ABSOLUTE.matcher(target);
        if (abs.matches()) {
            return abs.group(1).toLowerCase(Locale.ROOT) + "://" + authority(abs.group(2)) + path(abs.group(3));
        }
        if (method != null && CONNECT.contains(method)) return authority(target);
        return tokens.token("PATH", target);
    }

    private String path(String p) {
        if (p.isEmpty()) return p;
        String fragment = null;
        int hash = p.indexOf('#');
        if (hash >= 0) {
            fragment = p.substring(hash + 1);
            p = p.substring(0, hash);
        }
        String query = null;
        int q = p.indexOf('?');
        if (q >= 0) {
            query = p.substring(q + 1);
            p = p.substring(0, q);
        }
        StringBuilder out = new StringBuilder();
        String[] segments = p.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) out.append('/');
            if (!segments[i].isEmpty()) out.append(tokens.token("PATH", segments[i]));
        }
        if (query != null) out.append('?').append(query.isEmpty() ? "" : tokens.token("QUERY", query));
        if (fragment != null) out.append('#').append(fragment.isEmpty() ? "" : tokens.token("QUERY", fragment));
        return out.toString();
    }

    private String authority(String a) {
        String user = "";
        int at = a.lastIndexOf('@');
        if (at >= 0) {
            user = tokens.token("USER", a.substring(0, at)) + "@";
            a = a.substring(at + 1);
        }
        return user + host(a);
    }

    /** A host name, with an optional port; address literals stay for the IP masker. */
    private String host(String h) {
        String name = h.strip();
        String port = "";
        if (name.startsWith("[")) return h;
        int colon = name.lastIndexOf(':');
        if (colon > 0 && name.indexOf(':') == colon && name.substring(colon + 1).matches("\\d{1,5}")) {
            port = name.substring(colon);
            name = name.substring(0, colon);
        } else if (colon >= 0) {
            return h; // an IPv6 literal
        }
        if (IPV4.matcher(name).matches() || name.isEmpty()) return h;
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
        return tokens.token("HOST", normalized) + port;
    }

    // ---- free-text summary / prose identity masking -------------------------------------------------------

    private static boolean isResponseDeviceKey(String key) {
        return "hostname".equals(key) || "device".equals(key) || "file".equals(key)
                || "point_label".equals(key) || "capture_name".equals(key) || "display_name".equals(key)
                || "name".equals(key);
    }

    private static boolean isProseTextKey(String key) {
        return key == null || !NON_PROSE_TEXT_KEYS.contains(key);
    }

    private String maskFreeText(String text) {
        if (text == null || text.length() < 4) return text;
        String out = replaceEmbeddedUrls(text);
        out = replaceDistinguishedNames(out);
        out = replaceDomainUsers(out);
        out = replaceUserAtRealm(out);
        out = replaceKeyedPrincipalsAndSerials(out);
        out = replaceFreeTextFqdns(out);
        out = replaceFreeTextHostnames(out);
        return out;
    }

    private String replaceEmbeddedUrls(String s) {
        if (!s.contains("://")) return s;
        Matcher m = EMBEDDED_URL.matcher(s);
        StringBuilder out = null;
        int last = 0;
        while (m.find()) {
            if (out == null) out = new StringBuilder(s.length() + 16);
            out.append(s, last, m.start()).append(target(null, m.group()));
            last = m.end();
        }
        return out == null ? s : out.append(s, last, s.length()).toString();
    }

    private String replaceDistinguishedNames(String s) {
        String out = s;
        if (containsIgnoreCase(out, "cn=") || containsIgnoreCase(out, "uid=")) {
            Matcher keyed = KEYED_DN.matcher(out);
            StringBuilder sb = null;
            int last = 0;
            while (keyed.find()) {
                String dn = stripOuterQuotes(keyed.group(3));
                if (sb == null) sb = new StringBuilder(out.length() + 16);
                sb.append(out, last, keyed.start()).append(keyed.group(1)).append(keyed.group(2)).append(tokens.token("USER", dn));
                last = keyed.end();
            }
            if (sb != null) out = sb.append(out, last, out.length()).toString();

            Matcher standalone = STANDALONE_DN.matcher(out);
            sb = null;
            last = 0;
            while (standalone.find()) {
                String dn = standalone.group(1) != null ? standalone.group(1) : standalone.group(2);
                if (dn == null || dn.isBlank()) continue;
                if (sb == null) sb = new StringBuilder(out.length() + 16);
                sb.append(out, last, standalone.start()).append(tokens.token("USER", dn.strip()));
                last = standalone.end();
            }
            if (sb != null) out = sb.append(out, last, out.length()).toString();

            Matcher slash = SLASH_DN.matcher(out);
            sb = null;
            last = 0;
            while (slash.find()) {
                String dn = slash.group(1);
                String lower = dn.toLowerCase(Locale.ROOT);
                if (!lower.contains("/cn=") && !lower.contains("/uid=")) continue;
                if (sb == null) sb = new StringBuilder(out.length() + 16);
                sb.append(out, last, slash.start()).append(tokens.token("USER", dn));
                last = slash.end();
            }
            if (sb != null) out = sb.append(out, last, out.length()).toString();
        }
        return out;
    }

    private String replaceDomainUsers(String s) {
        if (s.indexOf('\\') < 0) return s;
        Matcher m = DOMAIN_USER.matcher(s);
        StringBuilder out = null;
        int last = 0;
        while (m.find()) {
            String domain = m.group(1);
            String user = m.group(2);
            if (!isValidDomainPart(domain) || isAlreadyToken(user)) continue;
            if (out == null) out = new StringBuilder(s.length() + 16);
            out.append(s, last, m.start()).append(tokens.token("USER", domain + "\\" + user));
            last = m.end();
        }
        return out == null ? s : out.append(s, last, s.length()).toString();
    }

    private String replaceUserAtRealm(String s) {
        if (s.indexOf('@') < 0) return s;
        Matcher m = USER_AT_REALM.matcher(s);
        StringBuilder out = null;
        int last = 0;
        while (m.find()) {
            String user = m.group(1);
            String realm = m.group(2);
            if (isAlreadyToken(user) || isAlreadyToken(realm) || !hasAtLeastTwoLetters(realm)) continue;
            if (out == null) out = new StringBuilder(s.length() + 16);
            out.append(s, last, m.start()).append(tokens.token("USER", user + "@" + realm));
            last = m.end();
        }
        return out == null ? s : out.append(s, last, s.length()).toString();
    }

    private String replaceKeyedPrincipalsAndSerials(String s) {
        if (s.indexOf('=') < 0 && s.indexOf(':') < 0) return s;
        String out = s;
        Matcher pm = KEYED_PRINCIPAL.matcher(out);
        StringBuilder sb = null;
        int last = 0;
        while (pm.find()) {
            String val = pm.group(3);
            if (isAlreadyToken(val)) continue;
            if (sb == null) sb = new StringBuilder(out.length() + 16);
            sb.append(out, last, pm.start()).append(pm.group(1)).append(pm.group(2)).append(tokens.token("USER", val));
            last = pm.end();
        }
        if (sb != null) out = sb.append(out, last, out.length()).toString();

        Matcher sm = KEYED_SERIAL.matcher(out);
        sb = null;
        last = 0;
        while (sm.find()) {
            String val = sm.group(3);
            if (isAlreadyToken(val)) continue;
            if (sb == null) sb = new StringBuilder(out.length() + 16);
            sb.append(out, last, sm.start()).append(sm.group(1)).append(sm.group(2)).append(tokens.token("TXT", val));
            last = sm.end();
        }
        if (sb != null) out = sb.append(out, last, out.length()).toString();
        return out;
    }

    private String replaceFreeTextFqdns(String s) {
        if (s.indexOf('.') < 0) return s;
        Matcher m = FREE_TEXT_FQDN.matcher(s);
        StringBuilder out = null;
        int last = 0;
        while (m.find()) {
            String candidate = m.group(1);
            if (!isFreeTextFqdn(candidate)) continue;
            if (out == null) out = new StringBuilder(s.length() + 16);
            out.append(s, last, m.start()).append(host(candidate));
            last = m.end();
        }
        return out == null ? s : out.append(s, last, s.length()).toString();
    }

    private String replaceFreeTextHostnames(String s) {
        String out = replaceHostBeforeState(s);
        out = replaceHostAfterContext(out);
        out = replaceStandaloneHostTokens(out);
        return out;
    }

    private String replaceHostBeforeState(String s) {
        Matcher m = HOST_BEFORE_STATE.matcher(s);
        StringBuilder out = null;
        int last = 0;
        while (m.find()) {
            String candidate = m.group(1);
            if (!isContextualHostCandidate(candidate)) continue;
            if (out == null) out = new StringBuilder(s.length() + 16);
            out.append(s, last, m.start(1)).append(host(candidate)).append(m.group(2));
            last = m.end();
        }
        return out == null ? s : out.append(s, last, s.length()).toString();
    }

    private String replaceHostAfterContext(String s) {
        Matcher m = HOST_AFTER_CONTEXT.matcher(s);
        StringBuilder out = null;
        int last = 0;
        while (m.find()) {
            String candidate = m.group(2);
            if (!isContextualHostCandidate(candidate)) continue;
            if (out == null) out = new StringBuilder(s.length() + 16);
            out.append(s, last, m.start()).append(m.group(1)).append(host(candidate));
            last = m.end();
        }
        return out == null ? s : out.append(s, last, s.length()).toString();
    }

    private String replaceStandaloneHostTokens(String s) {
        Matcher m = STANDALONE_HOST_TOKEN.matcher(s);
        StringBuilder out = null;
        int last = 0;
        while (m.find()) {
            String candidate = m.group(1);
            if (!isStandaloneHostCandidate(candidate)) continue;
            if (out == null) out = new StringBuilder(s.length() + 16);
            out.append(s, last, m.start()).append(host(candidate));
            last = m.end();
        }
        return out == null ? s : out.append(s, last, s.length()).toString();
    }

    private boolean isFreeTextFqdn(String candidate) {
        if (IPV4.matcher(candidate).matches() || isAlreadyToken(candidate)) return false;
        String folded = fold(candidate);
        if (responseDeviceNames.contains(folded)) return false;
        String[] labels = folded.split("\\.");
        if (labels.length < 2) return false;
        String first = labels[0];
        String last = labels[labels.length - 1];
        if (FILTER_PREFIXES.contains(first) || FILE_EXTENSIONS.contains(last)) return false;
        if (last.length() < 2 || !last.chars().allMatch(Character::isLetter)) return false;
        int letterLabels = 0;
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63) return false;
            if (label.chars().anyMatch(Character::isLetter)) letterLabels++;
        }
        return letterLabels >= 2;
    }

    private boolean isContextualHostCandidate(String candidate) {
        if (candidate == null || candidate.length() < 3 || isAlreadyToken(candidate)) return false;
        String lower = candidate.toLowerCase(Locale.ROOT);
        if (responseDeviceNames.contains(lower) || VOCABULARY_STOPWORDS.contains(lower)) return false;
        if (SIZE_OR_UNIT.matcher(lower).matches() || ORDINAL_TOKEN.matcher(lower).matches()) return false;
        return hasAtLeastTwoLetters(lower);
    }

    private boolean isStandaloneHostCandidate(String candidate) {
        if (!isContextualHostCandidate(candidate)) return false;
        String lower = candidate.toLowerCase(Locale.ROOT);
        if (lower.indexOf('-') >= 0) {
            boolean hasDigit = lower.chars().anyMatch(Character::isDigit);
            for (String part : lower.split("-")) {
                if (part.equals("fw") || part.equals("gw") || part.equals("rtr") || part.equals("sw")
                        || part.equals("vpn") || part.equals("vsx") || part.equals("mds") || part.equals("pxy")
                        || part.equals("srv") || part.equals("prd") || part.equals("stg") || part.equals("dmz")) {
                    return true;
                }
            }
            return hasDigit && countLetters(lower) >= 3;
        }
        if (lower.indexOf('_') >= 0 || lower.startsWith("0x")) return false;
        if (DEVICE_AFFIX.matcher(lower).matches()) return true;
        int letters = countLetters(lower);
        int digits = (int) lower.chars().filter(Character::isDigit).count();
        return lower.length() >= 6 && letters >= 4 && digits >= 1;
    }

    private static boolean isValidDomainPart(String domain) {
        String lower = domain.toLowerCase(Locale.ROOT);
        return !Set.of("http", "https", "tcp", "udp", "sip", "tls", "ip", "icmp", "and", "or").contains(lower);
    }

    private static boolean isAlreadyToken(String value) {
        return TOKEN.matcher(value).find() || value.matches("(?i)(?:FW|CLS|DOM|VS|OBJ|ZONE|RULE|USR)-[A-Z0-9-]+");
    }

    private static boolean hasAtLeastTwoLetters(String s) {
        return countLetters(s) >= 2;
    }

    private static int countLetters(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) count++;
        }
        return count;
    }

    private static boolean containsIgnoreCase(String s, String needleLower) {
        return s.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    private static String stripOuterQuotes(String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }
}
