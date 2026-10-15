package com.securityexpert.nexus.ui2.sanitizer.core;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;

/**
 * Hostname, FQDN and e-mail pseudonyms (DATA_SANITIZER_CONTRACT.md §2). Every label that is not part of the public
 * suffix becomes {@code h-<10 hex>} from the HMAC of the label, so {@code a.b.example.com}-style hierarchy survives
 * and the same label reads the same everywhere. DNS labels are case-insensitive, so the digest is taken over the
 * lower-case punycode form; the token's own case carries the label's case so a revert is exact: an all-lower label
 * gives {@code h-3fa9c01b2e}, an all-upper one {@code H-3FA9C01B2E} (same digest, so still visibly the same name),
 * and a mixed-case label (rare) a case-exact digest of its own.
 *
 * <p>{@link #dnsLabel(String)} is the length-preserving form used inside DNS packets, where the label length is
 * wire structure: the same number of {@code [a-z0-9]} characters, first a letter, letters taking the case of the
 * original character at that position.</p>
 */
public final class NameMapper {

    private final ThreadLocal<Mac> name;
    private final ThreadLocal<Mac> dns;

    public NameMapper(SanitizerKey key) {
        byte[] nameKey = key.subKey("name");
        byte[] dnsKey = key.subKey("dns");
        this.name = ThreadLocal.withInitial(() -> SanitizerKey.mac(nameKey));
        this.dns = ThreadLocal.withInitial(() -> SanitizerKey.mac(dnsKey));
    }

    /** One label's token. */
    public String label(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        String ascii = PublicSuffixes.ascii(label);
        String canonical = ascii == null ? lower : ascii;
        if (label.equals(lower)) {
            return "h-" + hex(name.get(), "label:" + canonical);
        }
        if (label.equals(label.toUpperCase(Locale.ROOT))) {
            return "H-" + hex(name.get(), "label:" + canonical).toUpperCase(Locale.ROOT);
        }
        return "h-" + hex(name.get(), "label-exact:" + label);
    }

    /** True when text has the token form ({@code h-} or {@code H-} and ten hex digits of one case). */
    public static boolean isLabelToken(CharSequence text) {
        if (text.length() != 12 || text.charAt(1) != '-') return false;
        char h = text.charAt(0);
        if (h != 'h' && h != 'H') return false;
        for (int i = 2; i < 12; i++) {
            char c = text.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean letter = h == 'h' ? (c >= 'a' && c <= 'f') : (c >= 'A' && c <= 'F');
            if (!digit && !letter) return false;
        }
        return true;
    }

    /** The same-length DNS label: deterministic per lower-case label, case re-applied position by position. */
    public String dnsLabel(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        Mac m = dns.get();
        byte[] digest = new byte[0];
        StringBuilder out = new StringBuilder(label.length());
        int counter = 0;
        for (int i = 0; i < label.length(); i++) {
            if (i % 32 == 0) {
                m.update(("dns:" + counter++ + ":").getBytes(StandardCharsets.UTF_8));
                digest = m.doFinal(lower.getBytes(StandardCharsets.UTF_8));
            }
            int v = digest[i % 32] & 0xff;
            char c = i == 0 ? (char) ('a' + v % 26) : "abcdefghijklmnopqrstuvwxyz0123456789".charAt(v % 36);
            char original = label.charAt(i);
            if (Character.isUpperCase(original) && Character.isLetter(c)) c = Character.toUpperCase(c);
            out.append(c);
        }
        return out.toString();
    }

    private static String hex(Mac mac, String value) {
        byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest, 0, 5);
    }
}
