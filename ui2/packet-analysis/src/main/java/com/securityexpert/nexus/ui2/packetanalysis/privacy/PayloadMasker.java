package com.securityexpert.nexus.ui2.packetanalysis.privacy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
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

    private final Tokens tokens;
    private final boolean names;
    /**
     * Raw payload value -> its masked form, for replacement inside free text; keyed case-folded without a root dot
     * (host names are case-insensitive, RFC 4343). The first value seen wins a case-only collision.
     */
    private final Map<String, String> seenFolded = new LinkedHashMap<>();
    /** Folded raw values by their first two characters, longest first: a linear scan instead of a huge regex. */
    private final Map<String, List<String>> byPrefix = new HashMap<>();

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
                case ADDRESS, TEXT -> {
                    String line = requestLine(s);
                    yield line != null ? line : replaceSeen(s);
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
}
