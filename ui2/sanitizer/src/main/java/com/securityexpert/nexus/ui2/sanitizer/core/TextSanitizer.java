package com.securityexpert.nexus.ui2.sanitizer.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;

/**
 * The text rules (DATA_SANITIZER_CONTRACT.md §1/§2): every IPv4/IPv6 address and CIDR, MAC, FQDN/hostname, e-mail
 * and URL host in a piece of text is replaced in place; everything else is copied unchanged. One instance serves one
 * job (it is not thread-safe); every replacement is offered to the {@link TokenSink} and counted.
 *
 * <p>Detection collects candidates from every detector, then keeps a non-overlapping set: earliest start first, the
 * longer candidate on a tie, then the higher-priority kind. A candidate whose mapping declines (a kept range, a
 * version string, a file name) is not replaced.</p>
 */
public final class TextSanitizer {

    /** Count categories reported in a job summary. */
    public static final String IPV4 = "ipv4";
    public static final String IPV6 = "ipv6";
    public static final String CIDR = "cidr";
    public static final String MAC = "mac";
    public static final String HOSTNAME = "hostname";
    public static final String FQDN = "fqdn";
    public static final String EMAIL = "email";
    public static final String URL_HOST = "url_host";
    public static final String DEVICE_NAME = "device_name";

    private enum Type {
        URL(90), EMAIL(80), USER_AT_HOST(75), MAC(70), IPV6(65), IPV4(60), KNOWN(55), FQDN(50), LEARNED(45), CONTEXT_HOST(40),
        SYSLOG_HOST(40), BARE_HOST(10);

        final int priority;

        Type(int priority) {
            this.priority = priority;
        }
    }

    private record Candidate(int start, int end, Type type, String[] parts) {
        int length() {
            return end - start;
        }
    }

    private static final Comparator<Candidate> ORDER = Comparator.comparingInt(Candidate::start)
            .thenComparing(Comparator.comparingInt(Candidate::length).reversed())
            .thenComparing(Comparator.comparingInt((Candidate c) -> c.type().priority).reversed());

    private final Mappers mappers;
    private final TokenSink sink;
    private final Counts counts;
    private final PublicSuffixes suffixes = PublicSuffixes.get();
    /** Host names this job has seen in a host context; their bare mentions elsewhere in the file follow. */
    private final java.util.Set<String> learned = new java.util.HashSet<>();
    static final int MAX_LEARNED = 200_000;

    public TextSanitizer(Mappers mappers, TokenSink sink, Counts counts) {
        this.mappers = mappers;
        this.sink = sink == null ? TokenSink.DISCARD : sink;
        this.counts = counts;
    }

    public Counts counts() {
        return counts;
    }

    /**
     * Remembers a host name seen in a host context (a host column, a host key) so its bare mentions in free text of
     * the same job are replaced too. Bounded; single characters, numbers and stop words are never learned.
     */
    public void learn(String name) {
        if (name == null || name.length() < 3 || learned.size() >= MAX_LEARNED) return;
        if (!Patterns.BARE_HOST.matcher(name).matches() || name.chars().noneMatch(Character::isLetter)) return;
        if (Patterns.HOST_STOPWORDS.contains(name.toLowerCase(Locale.ROOT))) return;
        learned.add(name);
    }

    /** Free text (a log line, a cell, a JSON string). */
    public String sanitize(String text) {
        return sanitize(text, false);
    }

    /**
     * @param hostContext the value sits under a host-like column header or JSON key, so a bare single-word value
     *                    is a host name even without a keyword in front of it
     */
    public String sanitize(String text, boolean hostContext) {
        if (text == null || text.isEmpty()) return text;
        List<Candidate> candidates = new ArrayList<>();
        collect(text, hostContext, candidates);
        if (candidates.isEmpty()) return text;
        candidates.sort(ORDER);
        StringBuilder out = null;
        int copied = 0;
        int lastEnd = 0;
        for (Candidate c : candidates) {
            if (c.start() < lastEnd) continue;
            String replacement = replace(text, c);
            if (replacement == null) continue;
            if (out == null) out = new StringBuilder(text.length() + 32);
            out.append(text, copied, c.start()).append(replacement);
            copied = c.end();
            lastEnd = c.end();
        }
        if (out == null) return text;
        out.append(text, copied, text.length());
        return out.toString();
    }

    // ---- detection -----------------------------------------------------------------------------------------

    private void collect(String text, boolean hostContext, List<Candidate> out) {
        boolean dot = text.indexOf('.') >= 0;
        boolean colon = text.indexOf(':') >= 0;
        boolean at = text.indexOf('@') >= 0;
        if (colon && text.contains("://")) {
            Matcher m = Patterns.URL.matcher(text);
            while (m.find()) urlCandidate(text, m.end(), out);
        }
        if (at) {
            Matcher m = Patterns.EMAIL.matcher(text);
            while (m.find()) out.add(new Candidate(m.start(), m.end(), Type.EMAIL, new String[] {m.group(1), m.group(2)}));
            m = Patterns.USER_AT_HOST.matcher(text);
            while (m.find()) out.add(new Candidate(m.start(), m.end(), Type.USER_AT_HOST, new String[] {m.group(1), m.group(2)}));
        }
        if (colon || text.indexOf('-') >= 0 || dot) {
            Matcher m = Patterns.MAC.matcher(text);
            while (m.find()) out.add(new Candidate(m.start(), m.end(), Type.MAC, new String[] {m.group()}));
        }
        if (colon) ipv6Candidates(text, out);
        if (dot) {
            Matcher m = Patterns.IPV4.matcher(text);
            while (m.find()) {
                out.add(new Candidate(m.start(), m.end(), Type.IPV4, new String[] {m.group(1), m.group(2)}));
            }
            m = Patterns.FQDN.matcher(text);
            while (m.find()) out.add(new Candidate(m.start(), m.end(), Type.FQDN, new String[] {m.group()}));
        }
        Matcher m = Patterns.CONTEXT_HOST.matcher(text);
        while (m.find()) {
            out.add(new Candidate(m.start(1), m.end(1), Type.CONTEXT_HOST, new String[] {m.group(1)}));
        }
        for (var pattern : List.of(Patterns.SYSLOG_3164, Patterns.SYSLOG_5424)) {
            Matcher s = pattern.matcher(text);
            if (s.find()) out.add(new Candidate(s.start(1), s.end(1), Type.SYSLOG_HOST, new String[] {s.group(1)}));
        }
        if (mappers.known() != KnownNames.NONE || !learned.isEmpty()) {
            Matcher w = Patterns.WORD.matcher(text);
            while (w.find()) {
                String word = w.group();
                int end = w.end();
                while (!word.isEmpty() && (word.endsWith(".") || word.endsWith("-"))) {
                    word = word.substring(0, word.length() - 1);
                    end--;
                }
                if (!word.isEmpty() && mappers.known().pseudonymFor(word).isPresent()) {
                    out.add(new Candidate(w.start(), end, Type.KNOWN, new String[] {word}));
                } else if (!word.isEmpty() && learned.contains(word)) {
                    out.add(new Candidate(w.start(), end, Type.LEARNED, new String[] {word}));
                }
            }
        }
        if (hostContext) {
            String trimmed = text.strip();
            if (Patterns.BARE_HOST.matcher(trimmed).matches() && !Patterns.HOST_STOPWORDS.contains(trimmed.toLowerCase(Locale.ROOT))
                    && trimmed.chars().anyMatch(Character::isLetter)) {
                int start = text.indexOf(trimmed);
                out.add(new Candidate(start, start + trimmed.length(), Type.BARE_HOST, new String[] {trimmed}));
            }
        }
    }

    private void ipv6Candidates(String text, List<Candidate> out) {
        Matcher m = Patterns.IPV6_RUN.matcher(text);
        while (m.find()) {
            int start = m.start();
            int end = m.end();
            String run = text.substring(start, end);
            while (!run.isEmpty() && Addresses.parseV6(run) == null && (run.endsWith(".") || run.endsWith(":"))) {
                run = run.substring(0, run.length() - 1);
                end--;
            }
            if (run.isEmpty() || Addresses.parseV6(run) == null) continue;
            if (end < text.length() && Character.isLetterOrDigit(text.charAt(end)) && end == m.end()) continue;
            String zone = null;
            String prefix = null;
            int stop = end;
            Matcher z = Patterns.ZONE.matcher(text).region(stop, text.length());
            if (stop < text.length() && text.charAt(stop) == '%' && z.lookingAt()) {
                zone = z.group();
                stop = z.end();
            }
            Matcher p = Patterns.PREFIX.matcher(text).region(stop, text.length());
            if (stop < text.length() && text.charAt(stop) == '/' && p.lookingAt() && Integer.parseInt(p.group(1)) <= 128) {
                prefix = p.group(1);
                stop = p.end();
            }
            out.add(new Candidate(start, stop, Type.IPV6, new String[] {run, zone, prefix}));
        }
    }

    /** A URL's authority: optional {@code user[:password]@}, then a host ({@code [v6]}, IPv4 or name). */
    private void urlCandidate(String text, int from, List<Candidate> out) {
        int end = from;
        while (end < text.length() && "/?#\"'<>`\\ \t\r\n,;)]}".indexOf(text.charAt(end)) < 0) end++;
        if (end < text.length() && text.charAt(end) == ']') end++;
        String authority = text.substring(from, end);
        int hostStart = from;
        int atIndex = authority.lastIndexOf('@');
        if (atIndex >= 0) {
            String user = authority.substring(0, atIndex);
            if (!user.isEmpty()) out.add(new Candidate(from, from + atIndex, Type.URL, new String[] {"userinfo", user}));
            hostStart = from + atIndex + 1;
        }
        String hostPort = text.substring(hostStart, end);
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            if (close > 1) out.add(new Candidate(hostStart, hostStart + close + 1, Type.URL, new String[] {"v6", hostPort.substring(1, close)}));
            return;
        }
        int colon = hostPort.indexOf(':');
        String host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (!host.isEmpty()) out.add(new Candidate(hostStart, hostStart + host.length(), Type.URL, new String[] {"host", host}));
    }

    // ---- mapping -------------------------------------------------------------------------------------------

    private String replace(String text, Candidate c) {
        String[] p = c.parts();
        return switch (c.type()) {
            case IPV4 -> ipv4(text, c.start(), p[0], p[1]);
            case IPV6 -> ipv6(p[0], p[1], p[2], IPV6);
            case MAC -> mac(p[0]);
            case FQDN -> fqdn(p[0], FQDN, false);
            case EMAIL -> email(p[0], p[1]);
            case USER_AT_HOST -> {
                String user = label(p[0], EMAIL);
                yield user + "@" + host(p[1], EMAIL);
            }
            case CONTEXT_HOST, SYSLOG_HOST, LEARNED -> host(p[0], HOSTNAME);
            case BARE_HOST -> {
                learn(p[0]);
                yield host(p[0], HOSTNAME);
            }
            case KNOWN -> device(p[0]);
            case URL -> url(p[0], p[1]);
        };
    }

    private String ipv4(String text, int start, String address, String prefix) {
        byte[] bytes = Addresses.parseV4(address);
        if (bytes == null) return null;
        int lookback = Math.max(0, start - 16);
        if (Patterns.VERSION_BEFORE.matcher(text.substring(lookback, start)).find()) return null;
        if (mappers.ip().isKept(bytes) || isContiguousMask(bytes)) return null;
        byte[] mapped = mappers.ip().map(bytes);
        if (prefix != null) {
            int bits = Integer.parseInt(prefix);
            if (bits <= 32) {
                counts.add(CIDR);
                return network(bytes, mapped, bits, address, prefix);
            }
            prefix = null;
        }
        String token = Addresses.formatV4(mapped);
        sink.record(new VaultKey(VaultKind.ADDRESS, token), address);
        counts.add(IPV4);
        return prefix == null ? token : token + "/" + prefix;
    }

    /** A prefix: a network (host bits zero) is recorded as one network token, an interface address as an address. */
    private String network(byte[] original, byte[] mapped, int bits, String originalText, String prefix) {
        if (bits < original.length * 8 && hostBitsZero(original, bits)) {
            byte[] net = zeroHostBits(mapped, bits);
            String token = Addresses.format(net) + "/" + prefix;
            sink.record(new VaultKey(VaultKind.NETWORK, token), originalText + "/" + prefix);
            return original.length == 4 ? token : null;
        }
        String token = Addresses.format(mapped);
        sink.record(new VaultKey(VaultKind.ADDRESS, token), originalText);
        return original.length == 4 ? token + "/" + prefix : null;
    }

    private String ipv6(String address, String zone, String prefix, String category) {
        byte[] bytes = Addresses.parseV6(address);
        if (bytes == null || mappers.ip().isKept(bytes)) return null;
        Addresses.V6Style style = Addresses.V6Style.of(address);
        byte[] mapped = mappers.ip().map(bytes);
        String rendered;
        String suffix = (zone == null ? "" : zone);
        if (prefix != null) {
            int bits = Integer.parseInt(prefix);
            if (bits < 128 && hostBitsZero(bytes, bits)) {
                byte[] net = zeroHostBits(mapped, bits);
                rendered = Addresses.formatV6(net, style);
                sink.record(new VaultKey(VaultKind.NETWORK, Addresses.formatV6(net, Addresses.V6Style.CANONICAL) + "/" + prefix),
                        address + "/" + prefix);
                counts.add(CIDR);
                return rendered + suffix + "/" + prefix;
            }
            counts.add(CIDR);
        } else {
            counts.add(category);
        }
        rendered = Addresses.formatV6(mapped, style);
        sink.record(new VaultKey(VaultKind.ADDRESS, Addresses.formatV6(mapped, Addresses.V6Style.CANONICAL)), address);
        if (IpPermutation.isV4Mapped(bytes)) {
            sink.record(new VaultKey(VaultKind.ADDRESS, Addresses.formatV4(java.util.Arrays.copyOfRange(mapped, 12, 16))),
                    Addresses.formatV4(java.util.Arrays.copyOfRange(bytes, 12, 16)));
        }
        return rendered + suffix + (prefix == null ? "" : "/" + prefix);
    }

    private String mac(String text) {
        byte[] bytes = MacText.parse(text);
        if (mappers.mac().isKept(bytes)) return null;
        byte[] mapped = mappers.mac().map(bytes);
        String token = MacText.format(mapped, MacText.Style.of(text));
        sink.record(new VaultKey(VaultKind.MAC, MacText.canonical(mapped)), text);
        counts.add(MAC);
        return token;
    }

    /** An FQDN found in free text: its last label must be a public suffix (checked here). */
    private String fqdn(String name, String category, boolean anySuffix) {
        String[] labels = name.split("\\.", -1);
        int suffix = suffixes.suffixLabels(labels);
        if (suffix == 0 && !anySuffix) return null;
        if (labels.length == 2 && suffix == 1 && Patterns.FILE_LIKE_TLDS.contains(labels[1].toLowerCase(Locale.ROOT))) {
            return null;
        }
        if (labels[labels.length - 1].chars().allMatch(Character::isDigit)) return null;
        Optional<String> whole = mappers.known().pseudonymFor(name);
        if (whole.isPresent()) {
            sink.record(new VaultKey(VaultKind.DEVICE, whole.get()), name);
            counts.add(DEVICE_NAME);
            return whole.get();
        }
        String reverse = reverseDns(labels, suffix);
        if (reverse != null) {
            counts.add(category);
            return reverse;
        }
        if (suffix >= labels.length) return null; // the name is itself a public suffix ("co.uk")
        StringBuilder out = new StringBuilder(name.length() + 16);
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) out.append('.');
            if (i >= labels.length - suffix) {
                out.append(labels[i]);
            } else if (i == 0 && mappers.known().pseudonymFor(labels[0]).isPresent()) {
                out.append(device(labels[0]));
                counts.add(DEVICE_NAME, -1); // counted once, as the name below
            } else {
                out.append(label(labels[i], null));
            }
        }
        counts.add(category);
        return out.toString();
    }

    /** {@code d.c.b.a.in-addr.arpa} / nibble {@code ip6.arpa} names: the address prefix they spell is mapped. */
    private String reverseDns(String[] labels, int suffix) {
        int n = labels.length;
        if (n < 3 || suffix < 2) return null;
        String zone = (labels[n - 2] + "." + labels[n - 1]).toLowerCase(Locale.ROOT);
        int count = n - 2;
        if (zone.equals("in-addr.arpa") && count <= 4) {
            byte[] address = new byte[4];
            for (int i = 0; i < count; i++) {
                byte[] octet = Addresses.parseV4(labels[count - 1 - i] + ".0.0.0");
                if (octet == null) return null;
                address[i] = octet[0];
            }
            if (mappers.ip().isKept(address)) return null;
            byte[] mapped = mappers.ip().map(address);
            StringBuilder out = new StringBuilder();
            for (int i = count - 1; i >= 0; i--) out.append(mapped[i] & 0xff).append('.');
            String token = out.append(labels[n - 2]).append('.').append(labels[n - 1]).toString();
            sink.record(new VaultKey(VaultKind.DNS_NAME, token.toLowerCase(Locale.ROOT)), String.join(".", labels));
            return token;
        }
        if (zone.equals("ip6.arpa") && count <= 32) {
            byte[] address = new byte[16];
            for (int i = 0; i < count; i++) {
                String nibble = labels[count - 1 - i];
                if (nibble.length() != 1 || Character.digit(nibble.charAt(0), 16) < 0) return null;
                int value = Character.digit(nibble.charAt(0), 16);
                address[i / 2] |= (byte) (i % 2 == 0 ? value << 4 : value);
            }
            if (mappers.ip().isKept(address)) return null;
            byte[] mapped = mappers.ip().map(address);
            boolean upper = String.join("", java.util.Arrays.copyOf(labels, count)).chars().anyMatch(ch -> ch >= 'A' && ch <= 'F');
            StringBuilder out = new StringBuilder();
            for (int i = count - 1; i >= 0; i--) {
                int value = i % 2 == 0 ? (mapped[i / 2] >> 4) & 0xf : mapped[i / 2] & 0xf;
                char digit = Character.forDigit(value, 16);
                out.append(upper ? Character.toUpperCase(digit) : digit).append('.');
            }
            String token = out.append(labels[n - 2]).append('.').append(labels[n - 1]).toString();
            sink.record(new VaultKey(VaultKind.DNS_NAME, token.toLowerCase(Locale.ROOT)), String.join(".", labels));
            return token;
        }
        return null;
    }

    private String email(String local, String domain) {
        String[] labels = domain.split("\\.", -1);
        int suffix = suffixes.suffixLabels(labels);
        StringBuilder out = new StringBuilder(label(local, null)).append('@');
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) out.append('.');
            out.append(i >= labels.length - suffix ? labels[i] : label(labels[i], null));
        }
        counts.add(EMAIL);
        return out.toString();
    }

    /** A single-label host name (keyword, syslog header, host column): the device pseudonym or a label token. */
    private String host(String name, String category) {
        if (mappers.known().pseudonymFor(name).isPresent()) return device(name);
        if (Patterns.HOST_STOPWORDS.contains(name.toLowerCase(Locale.ROOT))) return null;
        if (category != null) counts.add(category);
        return label(name, null);
    }

    private String device(String name) {
        String pseudonym = mappers.known().pseudonymFor(name).orElseThrow();
        sink.record(new VaultKey(VaultKind.DEVICE, pseudonym), name);
        counts.add(DEVICE_NAME);
        return pseudonym;
    }

    private String label(String label, String category) {
        String token = mappers.names().label(label);
        sink.record(new VaultKey(VaultKind.LABEL, token), label);
        if (category != null) counts.add(category);
        return token;
    }

    private String url(String part, String value) {
        switch (part) {
            case "userinfo" -> {
                int colon = value.indexOf(':');
                if (colon < 0) return label(value, null);
                String user = value.substring(0, colon);
                String secret = value.substring(colon + 1);
                return (user.isEmpty() ? "" : label(user, null)) + ":" + (secret.isEmpty() ? "" : label(secret, null));
            }
            case "v6" -> {
                int percent = value.indexOf('%');
                String address = percent >= 0 ? value.substring(0, percent) : value;
                String zone = percent >= 0 ? value.substring(percent) : null;
                String mapped = ipv6(address, zone, null, URL_HOST);
                return mapped == null ? null : "[" + mapped + "]";
            }
            default -> {
                byte[] v4 = Addresses.parseV4(value);
                if (v4 != null) {
                    if (mappers.ip().isKept(v4)) return null;
                    String token = Addresses.formatV4(mappers.ip().map(v4));
                    sink.record(new VaultKey(VaultKind.ADDRESS, token), value);
                    counts.add(URL_HOST);
                    return token;
                }
                if (!value.matches("(?:" + Patterns.LABEL + "\\.)*" + Patterns.LABEL)) return null;
                if (!value.contains(".")) return host(value, URL_HOST);
                String mapped = fqdn(value, URL_HOST, true);
                return mapped;
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /** Contiguous IPv4 netmasks below /4 (128.0.0.0, 192.0.0.0); larger ones lie in kept ranges already. */
    private static boolean isContiguousMask(byte[] a) {
        int value = ((a[0] & 0xff) << 24) | ((a[1] & 0xff) << 16) | ((a[2] & 0xff) << 8) | (a[3] & 0xff);
        return value == 0x80000000 || value == 0xC0000000;
    }

    static boolean hostBitsZero(byte[] a, int bits) {
        for (int i = bits; i < a.length * 8; i++) {
            if (((a[i >> 3] >> (7 - (i & 7))) & 1) != 0) return false;
        }
        return true;
    }

    static byte[] zeroHostBits(byte[] a, int bits) {
        byte[] out = a.clone();
        for (int i = bits; i < a.length * 8; i++) out[i >> 3] &= (byte) ~(0x80 >>> (i & 7));
        return out;
    }
}
