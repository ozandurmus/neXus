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
        List<Candidate> found = new ArrayList<>();
        Map<Candidate, String> replacement = new HashMap<>();
        for (Candidate c : candidates) {
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
        String tokenAddress = token.contains("/") ? token.substring(0, token.indexOf('/')) : token;
        if (address.indexOf(':') < 0 || tokenAddress.indexOf(':') < 0) return original;
        Addresses.V6Style want = Addresses.V6Style.of(tokenAddress);
        if (want.equals(Addresses.V6Style.of(address))) return original;
        byte[] bytes = Addresses.parseV6(address);
        return bytes == null ? original : Addresses.formatV6(bytes, want) + tail;
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
        if (dot) {
            Matcher m = Patterns.IPV4.matcher(text);
            while (m.find()) {
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
                Matcher p = Patterns.PREFIX.matcher(text).region(end, text.length());
                if (end < text.length() && text.charAt(end) == '/' && p.lookingAt() && Integer.parseInt(p.group(1)) <= 128) {
                    out.add(new Candidate(m.start(), p.end(), new VaultKey(VaultKind.NETWORK, canonical + "/" + p.group(1)),
                            addressKey, "/" + p.group(1), run + "/" + p.group(1)));
                } else {
                    out.add(new Candidate(m.start(), end, addressKey, null, null, run));
                }
                if (IpPermutation.isV4Mapped(bytes) && run.indexOf('.') >= 0) {
                    int tail = m.start() + run.lastIndexOf(':') + 1;
                    String v4 = text.substring(tail, end);
                    out.add(new Candidate(tail, end, new VaultKey(VaultKind.ADDRESS, v4), null, null, v4));
                }
            }
        }
        return out;
    }
}
