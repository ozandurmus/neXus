package com.securityexpert.nexus.ui2.service.search;

import java.net.InetAddress;

/** Literal-only validation: never resolve DNS or accept scoped/interface-qualified addresses. */
public record IpQuery(String value, boolean cidr) {
    public static IpQuery parse(String term) {
        String[] parts = term.split("/", -1);
        if (parts.length > 2) return null;
        String address = parts[0];
        try {
            int bits;
            if (address.contains(":")) {
                if (!address.matches("[0-9a-fA-F:.]+")) return null;
                InetAddress.getByName(address);
                bits = 128;
            } else {
                String[] octets = address.split("\\.", -1);
                if (octets.length != 4) return null;
                for (String octet : octets) {
                    if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) return null;
                }
                bits = 32;
            }
            if (parts.length == 2 && (!parts[1].matches("[0-9]{1,3}") || Integer.parseInt(parts[1]) > bits)) return null;
            return new IpQuery(term, parts.length == 2);
        } catch (java.net.UnknownHostException | NumberFormatException invalid) { return null; }
    }
}
