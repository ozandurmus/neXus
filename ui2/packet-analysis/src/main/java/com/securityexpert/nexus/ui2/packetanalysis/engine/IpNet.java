package com.securityexpert.nexus.ui2.packetanalysis.engine;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * The reference {@code ipaddress} uses of the vendor/DNS lane: {@code ip_network(c)}, {@code ip_address(a) in net}
 * (False across address families) and {@code ip_address(a).is_multicast}. Only IP literals are parsed (no name
 * resolution); an IPv4-mapped IPv6 literal stays IPv6, as in the reference.
 */
final class IpNet {
    private static final Pattern V4 = Pattern.compile("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern V6 = Pattern.compile("[0-9A-Fa-f:.]+");

    private IpNet() {
    }

    record Network(byte[] address, int prefix) {
        boolean contains(String ip) {
            byte[] a = parse(ip);
            if (a.length != address.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (a[i] != address[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xff << (8 - rest)) & 0xff;
            return (a[full] & mask) == (address[full] & mask);
        }
    }

    static byte[] parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("None does not appear to be an IPv4 or IPv6 address");
        }
        boolean v6 = text.contains(":");
        if (!v6 && !V4.matcher(text).matches() || v6 && !V6.matcher(text).matches()) {
            throw new IllegalArgumentException("'" + text + "' does not appear to be an IPv4 or IPv6 address");
        }
        try {
            InetAddress a = InetAddress.getByName(text);
            byte[] b = a.getAddress();
            if (v6 && a instanceof Inet4Address) {
                byte[] mapped = new byte[16];
                mapped[10] = (byte) 0xff;
                mapped[11] = (byte) 0xff;
                System.arraycopy(b, 0, mapped, 12, 4);
                return mapped;
            }
            return b;
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("'" + text + "' does not appear to be an IPv4 or IPv6 address", e);
        }
    }

    /** {@code ipaddress.ip_network(text)} (strict: host bits must be zero). */
    static Network network(String text) {
        int slash = text.indexOf('/');
        byte[] address = parse(slash < 0 ? text : text.substring(0, slash));
        int prefix = slash < 0 ? address.length * 8 : Integer.parseInt(text.substring(slash + 1));
        if (prefix < 0 || prefix > address.length * 8) {
            throw new IllegalArgumentException("'" + text + "' does not appear to be an IPv4 or IPv6 network");
        }
        Network n = new Network(address, prefix);
        for (int bit = prefix; bit < address.length * 8; bit++) {
            if ((address[bit / 8] >> (7 - bit % 8) & 1) != 0) {
                throw new IllegalArgumentException(text + " has host bits set");
            }
        }
        return n;
    }

    /** {@code ipaddress.ip_address(text).is_multicast}. */
    static boolean isMulticast(String text) {
        byte[] a = parse(text);
        if (a.length == 4) {
            return (a[0] & 0xf0) == 0xe0;
        }
        return (a[0] & 0xff) == 0xff;
    }
}
