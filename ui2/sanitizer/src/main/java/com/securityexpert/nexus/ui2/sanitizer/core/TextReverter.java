package com.securityexpert.nexus.ui2.sanitizer.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * The revert rules (DATA_SANITIZER_CONTRACT.md §2 "Reversible on request"): every token shape a sanitize run emits
 * -- {@code h-}/{@code H-} label tokens, device pseudonyms, addresses and networks, MACs, DNS names -- is looked up in
 * the vault, in batches, and replaced by its original when (and only when) the vault holds it. Text around the
 * tokens, including new text an external tool wrote, is copied unchanged.
 *
 * <p>Addresses and MACs are written back in the token's own style (letter case, separators, IPv6 compression) when
 * that differs from how the recorded original was written, so a value that occurred in two spellings comes back in
 * the spelling each occurrence had.</p>
 */
public final class TextReverter {

    public static final String REVERTED = "reverted";

    private record Candidate(int start, int end, VaultKey key, VaultKey fallback, String suffix, String token) {
    }

    private final TokenResolver resolver;
    private final Counts counts;

    public TextReverter(TokenResolver resolver, Counts counts) {
        this.resolver = resolver;
        this.counts = counts;
    }

    public Counts counts() {
        return counts;
    }

    public String revert(String text) {
        return revert(List.of(text)).get(0);
    }

    /** Reverts a batch with one vault lookup for every candidate in it. */
    public List<String> revert(List<String> texts) {
        List<List<Candidate>> perText = new ArrayList<>(texts.size());
        Set<VaultKey> keys = new LinkedHashSet<>();
        for (String text : texts) {
            List<Candidate> found = text == null || text.isEmpty() ? List.of() : candidates(text);
            perText.add(found);
            for (Candidate c : found) {
                keys.add(c.key());
                if (c.fallback() != null) keys.add(c.fallback());
            }
        }
        Map<VaultKey, String> originals = keys.isEmpty() ? Map.of() : resolver.resolve(keys);
        List<String> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            out.add(apply(texts.get(i), perText.get(i), originals));
        }
        return out;
    }

    private String apply(String text, List<Candidate> candidates, Map<VaultKey, String> originals) {
        if (candidates.isEmpty()) return text;
        List<Candidate> unresolvedLabels = new ArrayList<>();
        for (Candidate c : candidates) {
            if ((c.key().kind() == VaultKind.LABEL || c.key().kind() == VaultKind.DEVICE) && render(c, originals) == null) {
                unresolvedLabels.add(c);
            }
        }
        List<Candidate> found = new ArrayList<>();
        Map<Candidate, String> replacement = new HashMap<>();
        for (Candidate c : candidates) {
            if ((c.key().kind() == VaultKind.LABEL || c.key().kind() == VaultKind.DEVICE)
                    && !unresolvedLabels.isEmpty() && taintedByUnresolvedLabel(text, c, unresolvedLabels)) {
                continue;
            }
            String value = render(c, originals);
            if (value != null) {
                found.add(c);
                replacement.put(c, value);
            }
        }
        if (found.isEmpty()) return text;
        found.sort(Comparator.comparingInt(Candidate::start).thenComparing(Comparator.comparingInt((Candidate c) -> c.end() - c.start()).reversed()));
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        for (Candidate c : found) {
            if (c.start() < copied) continue;
            out.append(text, copied, c.start()).append(replacement.get(c));
            copied = c.end();
            counts.add(REVERTED);
        }
        return out.append(text, copied, text.length()).toString();
    }

    private static boolean taintedByUnresolvedLabel(String text, Candidate c, List<Candidate> unresolved) {
        for (Candidate u : unresolved) {
            int lo = Math.min(c.end(), u.end());
            int hi = Math.max(c.start(), u.start());
            if (lo >= hi) continue;
            boolean sameDotted = true;
            for (int i = lo; i < hi; i++) {
                char ch = text.charAt(i);
                if (ch != '.' && ch != '-' && !Character.isLetterOrDigit(ch)) {
                    sameDotted = false;
                    break;
                }
            }
            if (sameDotted) return true;
        }
        return false;
    }

    private static String render(Candidate c, Map<VaultKey, String> originals) {
        String original = originals.get(c.key());
        String suffix = "";
        if (original == null && c.fallback() != null) {
            original = originals.get(c.fallback());
            suffix = c.suffix() == null ? "" : c.suffix();
        }
        if (original == null) return null;
        return switch (c.key().kind()) {
            case MAC -> {
                try {
                    if (original.length() < 17 && MacText.Style.of(original).equals(MacText.Style.of(c.token()))) {
                        yield original;
                    }
                    yield MacText.format(MacText.parse(original), MacText.Style.of(c.token()));
                } catch (IllegalArgumentException unparsable) {
                    yield original;
                }
            }
            case ADDRESS, NETWORK -> restyle(original, c.token()) + suffix;
            case DNS_NAME -> recase(original, c.token());
            default -> original;
        };
    }

    /** An IPv6 original rendered in the token's style when the styles differ; IPv4 text is never restyled. */
    static String restyle(String original, String token) {
        String address = original;
        String tail = "";
        int slash = original.indexOf('/');
        if (slash >= 0) {
            address = original.substring(0, slash);
            tail = original.substring(slash);
        }
        int tokenSlash = token.indexOf('/');
        String tokenBeforeSlash = tokenSlash >= 0 ? token.substring(0, tokenSlash) : token;
        int tokenPercent = tokenBeforeSlash.indexOf('%');
        String tokenAddress = tokenPercent >= 0 ? tokenBeforeSlash.substring(0, tokenPercent) : tokenBeforeSlash;
        String tokenZone = tokenPercent >= 0 ? tokenBeforeSlash.substring(tokenPercent) : "";
        if (address.indexOf(':') < 0 || tokenAddress.indexOf(':') < 0) return original;
        Addresses.V6Style have = Addresses.V6Style.of(address);
        Addresses.V6Style read = Addresses.V6Style.of(tokenAddress);
        // A token is a keyed random address rendered in the original's style, so two of the style flags are only
        // readable from it when it shows them: "expanded" looks the same as a plain address whose eight groups all
        // happen to have four digits (about 60 % of random tokens), and a token without a letter says nothing about
        // case. Where the token is silent the stored original's own style stands; reading it as "expanded" or
        // "lower-case" anyway rewrote most compressed IPv6 originals on revert.
        boolean letters = tokenAddress.chars().anyMatch(c -> (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'));
        Addresses.V6Style want = new Addresses.V6Style(letters ? read.upper() : have.upper(),
                read.expanded() ? have.expanded() : false, read.dottedTail());
        if (want.equals(have)) return address + tokenZone + tail;
        byte[] bytes = Addresses.parseV6(address);
        return bytes == null ? original : Addresses.formatV6(bytes, want) + tokenZone + tail;
    }

    /** A DNS name: letter case follows the token position by position (DNS 0x20 case randomisation). */
    static String recase(String original, String token) {
        if (original.length() != token.length()) return original;
        StringBuilder out = new StringBuilder(original.length());
        for (int i = 0; i < original.length(); i++) {
            char o = original.charAt(i);
            char t = token.charAt(i);
            if (Character.isLetter(o) && Character.isLetter(t)) {
                out.append(Character.isUpperCase(t) ? Character.toUpperCase(o) : Character.toLowerCase(o));
            } else {
                out.append(o);
            }
        }
        return out.toString();
    }

    private List<Candidate> candidates(String text) {
        List<Candidate> out = new ArrayList<>();
        if (text.indexOf('-') >= 0) {
            Matcher m = Patterns.LABEL_TOKEN.matcher(text);
            while (m.find()) out.add(new Candidate(m.start(), m.end(), new VaultKey(VaultKind.LABEL, m.group()), null, null, m.group()));
            m = Patterns.DEVICE_TOKEN.matcher(text);
            while (m.find()) out.add(new Candidate(m.start(), m.end(), new VaultKey(VaultKind.DEVICE, m.group()), null, null, m.group()));
        }
        boolean dot = text.indexOf('.') >= 0;
        boolean colon = text.indexOf(':') >= 0;
        if (colon || dot || text.indexOf('-') >= 0) {
            Matcher m = Patterns.MAC.matcher(text);
            while (m.find()) {
                try {
                    String canonical = MacText.canonical(MacText.parse(m.group()));
                    out.add(new Candidate(m.start(), m.end(), new VaultKey(VaultKind.MAC, canonical), null, null, m.group()));
                } catch (IllegalArgumentException ignored) {
                    // not a MAC after all
                }
            }
        }
        List<int[]> v6Spans = new ArrayList<>();
        if (colon) {
            Matcher m = Patterns.IPV6_RUN.matcher(text);
            while (m.find()) {
                int end = m.end();
                String run = m.group();
                while (!run.isEmpty() && Addresses.parseV6(run) == null && (run.endsWith(".") || run.endsWith(":"))) {
                    run = run.substring(0, run.length() - 1);
                    end--;
                }
                byte[] bytes = run.isEmpty() ? null : Addresses.parseV6(run);
                if (bytes == null) continue;
                String canonical = Addresses.formatV6(bytes, Addresses.V6Style.CANONICAL);
                VaultKey addressKey = new VaultKey(VaultKind.ADDRESS, canonical);
                String zone = "";
                int stop = end;
                Matcher z = Patterns.ZONE.matcher(text).region(stop, text.length());
                if (stop < text.length() && text.charAt(stop) == '%' && z.lookingAt()) {
                    zone = z.group();
                    stop = z.end();
                }
                Matcher p = Patterns.PREFIX.matcher(text).region(stop, text.length());
                if (stop < text.length() && text.charAt(stop) == '/' && p.lookingAt() && Integer.parseInt(p.group(1)) <= 128) {
                    stop = p.end();
                    out.add(new Candidate(m.start(), stop, new VaultKey(VaultKind.NETWORK, canonical + "/" + p.group(1)),
                            addressKey, "/" + p.group(1), run + zone + "/" + p.group(1)));
                } else {
                    out.add(new Candidate(m.start(), end, addressKey, null, null, run));
                }
                v6Spans.add(new int[] {m.start(), stop});
            }
        }
        if (dot) {
            Matcher m = Patterns.IPV4.matcher(text);
            while (m.find()) {
                if (overlapsAny(v6Spans, m.start(), m.end())) continue;
                byte[] bytes = Addresses.parseV4(m.group(1));
                if (bytes == null) continue;
                String address = Addresses.formatV4(bytes);
                VaultKey addressKey = new VaultKey(VaultKind.ADDRESS, address);
                if (m.group(2) != null && Integer.parseInt(m.group(2)) <= 32) {
                    out.add(new Candidate(m.start(), m.end(), new VaultKey(VaultKind.NETWORK, address + "/" + m.group(2)),
                            addressKey, "/" + m.group(2), m.group()));
                } else {
                    out.add(new Candidate(m.start(), m.start() + m.group(1).length(), addressKey, null, null, m.group(1)));
                }
            }
            m = Patterns.FQDN.matcher(text);
            while (m.find()) {
                out.add(new Candidate(m.start(), m.end(), new VaultKey(VaultKind.DNS_NAME, m.group().toLowerCase(Locale.ROOT)),
                        null, null, m.group()));
            }
        }
        return out;
    }

    private static boolean overlapsAny(List<int[]> spans, int start, int end) {
        for (int[] span : spans) {
            if (start < span[1] && end > span[0]) return true;
        }
        return false;
    }
}
