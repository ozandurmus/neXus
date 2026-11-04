package com.securityexpert.nexus.ui2.service.pathanalysis.topology;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An IPv4 network in canonical {@code a.b.c.d/len} form (path analysis is IPv4 only, PATH_ANALYSIS_DESIGN.md §5).
 * Canonicalisation is representation-only (dotted quad to 32 bits and back, host bits cleared for the network): the
 * identity law's "proven representation-only transformation". Anything that is not a well-formed dotted quad with an
 * optional {@code /0..32} is not parsed -- never guessed.
 */
public record Ipv4Prefix(long network, int length) implements Comparable<Ipv4Prefix> {

    private static final Pattern FORM = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?:/(\\d{1,2}))?$");

    /**
     * IANA IPv4 Special-Purpose Address Registry entries marked "Globally Reachable: False" (RFC 6890 and updates):
     * private-use, shared address space, link local, IETF protocol assignments, the three documentation ranges,
     * benchmarking and reserved. The same prefix on two estate units may be two different networks; such prefixes are
     * never joined on equality alone (design §4).
     */
    private static final List<Ipv4Prefix> NOT_GLOBALLY_REACHABLE = List.of(
            parse("10.0.0.0/8").orElseThrow(), parse("100.64.0.0/10").orElseThrow(),
            parse("169.254.0.0/16").orElseThrow(), parse("172.16.0.0/12").orElseThrow(),
            parse("192.0.0.0/24").orElseThrow(), parse("192.0.2.0/24").orElseThrow(),
            parse("192.168.0.0/16").orElseThrow(), parse("198.18.0.0/15").orElseThrow(),
            parse("198.51.100.0/24").orElseThrow(), parse("203.0.113.0/24").orElseThrow(),
            parse("240.0.0.0/4").orElseThrow());

    public Ipv4Prefix {
        if (length < 0 || length > 32) {
            throw new IllegalArgumentException("prefix length");
        }
        network = network & mask(length);
    }

    /** A host address with its prefix, as an interface address is written ({@code 192.0.2.1/24}). */
    public record HostAddress(long host, Optional<Integer> length) {
        public Optional<Ipv4Prefix> prefix() {
            return length.map(len -> new Ipv4Prefix(host, len));
        }

        public String hostText() {
            return text(host);
        }
    }

    /** Parses {@code a.b.c.d} or {@code a.b.c.d/len} as a network (host bits cleared); empty when not well formed. */
    public static Optional<Ipv4Prefix> parse(String text) {
        return parseHost(text).map(h -> new Ipv4Prefix(h.host(), h.length().orElse(32)));
    }

    /** Parses an address keeping its host bits; the length is empty when the text carries none (UNKNOWN). */
    public static Optional<HostAddress> parseHost(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = FORM.matcher(text.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        long value = 0;
        for (int i = 1; i <= 4; i++) {
            int octet = Integer.parseInt(m.group(i));
            if (octet > 255 || (m.group(i).length() > 1 && m.group(i).startsWith("0"))) {
                // A leading zero is ambiguous (octal in some parsers): not a proven representation.
                return Optional.empty();
            }
            value = (value << 8) | octet;
        }
        Optional<Integer> length = Optional.empty();
        if (m.group(5) != null) {
            int len = Integer.parseInt(m.group(5));
            if (len > 32) {
                return Optional.empty();
            }
            length = Optional.of(len);
        }
        return Optional.of(new HostAddress(value, length));
    }

    public boolean contains(long address) {
        return (address & mask(length)) == network;
    }

    public boolean contains(Ipv4Prefix other) {
        return other.length >= length && contains(other.network);
    }

    public boolean overlaps(Ipv4Prefix other) {
        return contains(other) || other.contains(this);
    }

    /** True unless the whole prefix lies in an IANA "not globally reachable" block. */
    public boolean globallyReachable() {
        for (Ipv4Prefix block : NOT_GLOBALLY_REACHABLE) {
            if (block.contains(this)) {
                return false;
            }
        }
        return true;
    }

    /** {@code /30} and {@code /31}: drawn as a direct edge when exactly two units attach (design §4). */
    public boolean pointToPoint() {
        return length == 30 || length == 31;
    }

    @Override
    public String toString() {
        return text(network) + "/" + length;
    }

    @Override
    public int compareTo(Ipv4Prefix o) {
        int byNetwork = Long.compare(network, o.network);
        return byNetwork != 0 ? byNetwork : Integer.compare(length, o.length);
    }

    static long mask(int length) {
        return length == 0 ? 0L : (0xFFFF_FFFFL << (32 - length)) & 0xFFFF_FFFFL;
    }

    static String text(long value) {
        return ((value >> 24) & 0xFF) + "." + ((value >> 16) & 0xFF) + "." + ((value >> 8) & 0xFF) + "." + (value & 0xFF);
    }
}
