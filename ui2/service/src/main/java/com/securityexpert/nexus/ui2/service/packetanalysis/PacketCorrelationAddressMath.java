package com.securityexpert.nexus.ui2.service.packetanalysis;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject;

/**
 * Stateless IPv4/IPv6 CIDR, host, and range parsing and containment math for
 * {@link PacketAnalysisCorrelationService}.
 */
final class PacketCorrelationAddressMath {

    private PacketCorrelationAddressMath() {
    }

    record ParsedAddress(
            String raw,
            String family,
            int maxBits,
            int prefixLength,
            boolean cidrNotation,
            BigInteger hostAddress,
            BigInteger networkStart,
            BigInteger networkEnd) {
        boolean containsAddress(ParsedAddress target) {
            if (target == null || !family.equals(target.family)) {
                return false;
            }
            return target.networkStart.compareTo(networkStart) >= 0
                    && target.networkEnd.compareTo(networkEnd) <= 0;
        }
    }

    static List<ParsedAddress> parseAddressList(Collection<String> rawStrings) {
        List<ParsedAddress> out = new ArrayList<>();
        for (String raw : rawStrings) {
            ParsedAddress p = parseAddressOrRange(raw);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    static List<ParsedAddress> parsePolicyAddressObject(PolicyObject obj) {
        List<ParsedAddress> out = new ArrayList<>();
        Map<String, String> fields = new LinkedHashMap<>();
        for (String v : obj.values()) {
            int split = v.indexOf(": ");
            if (split >= 0) {
                String k = v.substring(0, split).strip();
                String val = v.substring(split + 2).strip();
                fields.put(k, val);
                if (Set.of("ipv4-address", "ipv6-address", "ip-netmask", "ip-range", "address").contains(k)) {
                    ParsedAddress p = parseAddressOrRange(val);
                    if (p != null) out.add(p);
                }
            } else {
                ParsedAddress p = parseAddressOrRange(v.strip());
                if (p != null) out.add(p);
            }
        }
        if (fields.containsKey("subnet4") && fields.containsKey("mask-length4")) {
            ParsedAddress p = parseAddressOrRange(fields.get("subnet4") + "/" + fields.get("mask-length4"));
            if (p != null) out.add(p);
        }
        if (fields.containsKey("subnet6") && fields.containsKey("mask-length6")) {
            ParsedAddress p = parseAddressOrRange(fields.get("subnet6") + "/" + fields.get("mask-length6"));
            if (p != null) out.add(p);
        }
        if (fields.containsKey("ip-address-first") && fields.containsKey("ip-address-last")) {
            ParsedAddress p = parseAddressOrRange(fields.get("ip-address-first") + "-" + fields.get("ip-address-last"));
            if (p != null) out.add(p);
        }
        return out;
    }

    static ParsedAddress parseSingleAddress(String raw) {
        return parseAddressOrRange(raw);
    }

    static boolean ipEquals(String a, String b) {
        if (Objects.equals(a, b)) return true;
        ParsedAddress pa = parseSingleAddress(a);
        ParsedAddress pb = parseSingleAddress(b);
        return pa != null && pb != null && pa.family.equals(pb.family) && pa.hostAddress.equals(pb.hostAddress);
    }

    static ParsedAddress parseAddressOrRange(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.strip();
        if (text.contains("-")) {
            String[] parts = text.split("-", 2);
            ParsedAddress start = parseCidrOrHost(parts[0].strip());
            ParsedAddress end = parseCidrOrHost(parts[1].strip());
            if (start == null || end == null || !start.family.equals(end.family)
                    || start.hostAddress.compareTo(end.hostAddress) > 0) {
                return null;
            }
            return new ParsedAddress(
                    text, start.family, start.maxBits, start.maxBits, false,
                    start.hostAddress, start.hostAddress, end.hostAddress);
        }
        return parseCidrOrHost(text);
    }

    static ParsedAddress parseCidrOrHost(String text) {
        String[] parts = text.split("/", -1);
        if (parts.length > 2) return null;
        String addrPart = parts[0].strip();
        boolean isIpv6 = addrPart.contains(":");
        int maxBits = isIpv6 ? 128 : 32;
        String family = isIpv6 ? "ipv6" : "ipv4";

        BigInteger host = isIpv6 ? parseIpv6Literal(addrPart) : parseIpv4Literal(addrPart);
        if (host == null) return null;

        boolean hasPrefix = parts.length == 2;
        int prefix = maxBits;
        if (hasPrefix) {
            try {
                prefix = Integer.parseInt(parts[1].strip());
                if (prefix < 0 || prefix > maxBits) return null;
            } catch (NumberFormatException invalid) {
                return null;
            }
        }

        BigInteger allOnes = BigInteger.ONE.shiftLeft(maxBits).subtract(BigInteger.ONE);
        BigInteger hostMask = prefix == maxBits
                ? BigInteger.ZERO
                : BigInteger.ONE.shiftLeft(maxBits - prefix).subtract(BigInteger.ONE);
        BigInteger netMask = allOnes.xor(hostMask);
        BigInteger netStart = host.and(netMask);
        BigInteger netEnd = netStart.or(hostMask);

        return new ParsedAddress(text, family, maxBits, prefix, hasPrefix, host, netStart, netEnd);
    }

    static BigInteger parseIpv4Literal(String ip) {
        String[] octets = ip.split("\\.", -1);
        if (octets.length != 4) return null;
        long result = 0;
        for (String octet : octets) {
            if (!octet.matches("0|[1-9][0-9]{0,2}")) return null;
            int val = Integer.parseInt(octet);
            if (val < 0 || val > 255) return null;
            result = (result << 8) | val;
        }
        return BigInteger.valueOf(result);
    }

    static BigInteger parseIpv6Literal(String ip) {
        if (!ip.matches("[0-9a-fA-F:.]+")) return null;
        try {
            byte[] bytes = InetAddress.getByName(ip).getAddress();
            if (bytes.length != 16) return null;
            return new BigInteger(1, bytes);
        } catch (Exception invalid) {
            return null;
        }
    }
}
