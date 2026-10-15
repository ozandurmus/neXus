package com.securityexpert.nexus.ui2.sanitizer.pcap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.securityexpert.nexus.ui2.sanitizer.core.Addresses;
import com.securityexpert.nexus.ui2.sanitizer.core.Counts;
import com.securityexpert.nexus.ui2.sanitizer.core.Mappers;
import com.securityexpert.nexus.ui2.sanitizer.core.PublicSuffixes;
import com.securityexpert.nexus.ui2.sanitizer.core.TokenSink;
import com.securityexpert.nexus.ui2.sanitizer.core.VaultKey;
import com.securityexpert.nexus.ui2.sanitizer.core.VaultKind;

/**
 * DNS messages inside a sanitized capture (DATA_SANITIZER_CONTRACT.md §1 "DNS names in DNS packets replaced by their
 * pseudonyms"). The message is rewritten in place so every length, count, offset and compression pointer stays
 * valid: each label outside the public suffix becomes its same-length pseudonym ({@code NameMapper#dnsLabel}), A and
 * AAAA data go through the address permutation, name-bearing RDATA (NS, CNAME, PTR, DNAME, MX, SRV, SOA) is
 * rewritten the same way, and every other RDATA (TXT, OPT/EDNS options such as a client subnet, ...) is zeroed.
 * A message that does not parse is left to the caller, which zeroes it.
 */
final class DnsRewriter {

    private static final int MAX_JUMPS = 64;

    private final Mappers mappers;
    private final TokenSink sink;
    private final Counts counts;

    DnsRewriter(Mappers mappers, TokenSink sink, Counts counts) {
        this.mappers = mappers;
        this.sink = sink == null ? TokenSink.DISCARD : sink;
        this.counts = counts;
    }

    private interface Visitor {
        /** A name: its labels (decompressed) and, per label, where it is stored in this occurrence (-1: elsewhere). */
        void name(List<String> labels, List<Integer> positions);

        void address(int pos, int length);

        void opaque(int pos, int length);
    }

    /** Rewrites {@code message} in place; false when it is not a parseable DNS message (nothing is trusted then). */
    boolean sanitize(byte[] message) {
        byte[] original = message.clone();
        PublicSuffixes suffixes = PublicSuffixes.get();
        return walk(original, 0, original.length, new Visitor() {
            @Override
            public void name(List<String> labels, List<Integer> positions) {
                if (labels.isEmpty()) return;
                int suffix = suffixes.suffixLabels(labels.toArray(new String[0]));
                List<String> pseudonym = new ArrayList<>(labels.size());
                boolean changed = false;
                for (int i = 0; i < labels.size(); i++) {
                    String label = labels.get(i);
                    String out = i >= labels.size() - suffix ? label : mappers.names().dnsLabel(label);
                    pseudonym.add(out);
                    changed |= !out.equals(label);
                    int at = positions.get(i);
                    if (at >= 0) System.arraycopy(out.getBytes(StandardCharsets.ISO_8859_1), 0, message, at + 1, out.length());
                }
                if (changed) {
                    sink.record(new VaultKey(VaultKind.DNS_NAME, String.join(".", pseudonym).toLowerCase(Locale.ROOT)),
                            String.join(".", labels));
                    counts.add(FrameRewriter.DNS_NAMES);
                }
            }

            @Override
            public void address(int pos, int length) {
                byte[] value = Arrays.copyOfRange(original, pos, pos + length);
                byte[] mapped = mappers.ip().map(value);
                System.arraycopy(mapped, 0, message, pos, length);
                if (!Arrays.equals(value, mapped)) {
                    sink.record(new VaultKey(VaultKind.ADDRESS, Addresses.format(mapped)), Addresses.format(value));
                    counts.add(length == 4 ? "ipv4" : "ipv6");
                }
            }

            @Override
            public void opaque(int pos, int length) {
                Arrays.fill(message, pos, pos + length, (byte) 0);
            }
        });
    }

    void collectKeys(byte[] frame, int start, int end, Set<VaultKey> keys) {
        byte[] message = Arrays.copyOfRange(frame, start, end);
        walk(message, 0, message.length, new Visitor() {
            @Override
            public void name(List<String> labels, List<Integer> positions) {
                if (!labels.isEmpty()) keys.add(new VaultKey(VaultKind.DNS_NAME, String.join(".", labels).toLowerCase(Locale.ROOT)));
            }

            @Override
            public void address(int pos, int length) {
                keys.add(new VaultKey(VaultKind.ADDRESS, Addresses.format(Arrays.copyOfRange(message, pos, pos + length))));
            }

            @Override
            public void opaque(int pos, int length) {
            }
        });
    }

    /** Puts originals back into {@code frame[start, end)} for every name and address the vault resolved. */
    void revert(byte[] frame, int start, int end, Map<VaultKey, String> resolved) {
        byte[] message = Arrays.copyOfRange(frame, start, end);
        walk(message, 0, message.length, new Visitor() {
            @Override
            public void name(List<String> labels, List<Integer> positions) {
                if (labels.isEmpty()) return;
                String token = String.join(".", labels);
                String original = resolved.get(new VaultKey(VaultKind.DNS_NAME, token.toLowerCase(Locale.ROOT)));
                if (original == null) return;
                String[] back = original.split("\\.", -1);
                if (back.length != labels.size()) return;
                for (int i = 0; i < back.length; i++) {
                    if (back[i].length() != labels.get(i).length()) return;
                }
                for (int i = 0; i < back.length; i++) {
                    int at = positions.get(i);
                    if (at < 0) continue;
                    byte[] bytes = recase(back[i], labels.get(i)).getBytes(StandardCharsets.ISO_8859_1);
                    System.arraycopy(bytes, 0, frame, start + at + 1, bytes.length);
                }
                counts.add("reverted");
            }

            @Override
            public void address(int pos, int length) {
                String original = resolved.get(new VaultKey(VaultKind.ADDRESS,
                        Addresses.format(Arrays.copyOfRange(message, pos, pos + length))));
                if (original == null) return;
                try {
                    byte[] bytes = Addresses.parse(original);
                    if (bytes.length == length) {
                        System.arraycopy(bytes, 0, frame, start + pos, length);
                        counts.add("reverted");
                    }
                } catch (IllegalArgumentException ignored) {
                    // not an address of this family
                }
            }

            @Override
            public void opaque(int pos, int length) {
            }
        });
    }

    private static String recase(String original, String token) {
        StringBuilder out = new StringBuilder(original.length());
        for (int i = 0; i < original.length(); i++) {
            char o = original.charAt(i);
            char t = token.charAt(i);
            out.append(Character.isLetter(o) && Character.isLetter(t) && t < 0x80 && o < 0x80
                    ? (Character.isUpperCase(t) ? Character.toUpperCase(o) : Character.toLowerCase(o)) : o);
        }
        return out.toString();
    }

    // ---- parsing -------------------------------------------------------------------------------------------

    private static int u16(byte[] m, int at) {
        return ((m[at] & 0xff) << 8) | (m[at + 1] & 0xff);
    }

    private static boolean walk(byte[] m, int start, int end, Visitor v) {
        try {
            if (end - start < 12) return false;
            int qd = u16(m, start + 4);
            int an = u16(m, start + 6);
            int ns = u16(m, start + 8);
            int ar = u16(m, start + 10);
            int at = start + 12;
            for (int i = 0; i < qd; i++) {
                at = name(m, start, end, at, v);
                if (at < 0 || at + 4 > end) return false;
                at += 4;
            }
            for (int i = 0; i < an + ns + ar; i++) {
                at = name(m, start, end, at, v);
                if (at < 0 || at + 10 > end) return false;
                int type = u16(m, at);
                int rdlength = u16(m, at + 8);
                int rdata = at + 10;
                if (rdata + rdlength > end) return false;
                switch (type) {
                    case 1 -> {
                        if (rdlength == 4) v.address(rdata, 4);
                        else v.opaque(rdata, rdlength);
                    }
                    case 28 -> {
                        if (rdlength == 16) v.address(rdata, 16);
                        else v.opaque(rdata, rdlength);
                    }
                    case 2, 5, 12, 39 -> {
                        if (name(m, start, rdata + rdlength, rdata, v) != rdata + rdlength) return false;
                    }
                    case 15 -> {
                        if (rdlength < 3 || name(m, start, rdata + rdlength, rdata + 2, v) != rdata + rdlength) return false;
                    }
                    case 33 -> {
                        if (rdlength < 7 || name(m, start, rdata + rdlength, rdata + 6, v) != rdata + rdlength) return false;
                    }
                    case 6 -> {
                        int next = name(m, start, rdata + rdlength, rdata, v);
                        if (next < 0) return false;
                        next = name(m, start, rdata + rdlength, next, v);
                        if (next < 0 || next + 20 != rdata + rdlength) return false;
                    }
                    default -> v.opaque(rdata, rdlength);
                }
                at = rdata + rdlength;
            }
            return true;
        } catch (IndexOutOfBoundsException malformed) {
            return false;
        }
    }

    /** Visits the name at {@code at}; returns the offset after it in this occurrence, or -1 when malformed. */
    private static int name(byte[] m, int start, int end, int at, Visitor v) {
        List<String> labels = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        int cursor = at;
        int next = -1;
        int jumps = 0;
        int total = 0;
        boolean direct = true;
        while (true) {
            if (cursor >= end && direct) return -1;
            if (cursor >= m.length) return -1;
            int length = m[cursor] & 0xff;
            if ((length & 0xc0) == 0xc0) {
                if (cursor + 1 >= m.length) return -1;
                int target = start + (((length & 0x3f) << 8) | (m[cursor + 1] & 0xff));
                if (direct) next = cursor + 2;
                if (target >= cursor || ++jumps > MAX_JUMPS) return -1;
                direct = false;
                cursor = target;
                continue;
            }
            if ((length & 0xc0) != 0) return -1;
            if (length == 0) {
                if (direct) next = cursor + 1;
                break;
            }
            if (cursor + 1 + length > m.length || (direct && cursor + 1 + length > end)) return -1;
            total += length + 1;
            if (total > 255) return -1;
            labels.add(new String(m, cursor + 1, length, StandardCharsets.ISO_8859_1));
            positions.add(direct ? cursor : -1);
            cursor += 1 + length;
        }
        v.name(labels, positions);
        return next;
    }
}
