package com.securityexpert.nexus.ui2.service.policy;

import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.*;
import static com.securityexpert.nexus.ui2.service.policy.ZoneMatrixPolicyBridge.*;
import java.math.BigInteger;
import java.util.*;

final class ZoneMatrixUlaRanges {
    private ZoneMatrixUlaRanges() {}

    private static final BigInteger ULA_START = new BigInteger("fc000000000000000000000000000000", 16);
    private static final BigInteger ULA_END = new BigInteger("fdffffffffffffffffffffffffffffff", 16);

    static void collectUlaRanges(
            Map<String, PolicyObject> objects,
            List<String> refs,
            Set<String> visited,
            List<UlaRange> out,
            Budget budget) {
        for (String id : refs) {
            budget.spend();
            if (!visited.add(id) || visited.size() > 512) continue;
            PolicyObject o = objects.get(id);
            if (o == null) continue;
            extractObjectUla(o, out);
            if (!o.members().isEmpty()) {
                collectUlaRanges(objects, o.members(), visited, out, budget);
            }
        }
    }

    private static void extractObjectUla(PolicyObject o, List<UlaRange> out) {
        String subnet6 = null, mask6 = null;
        for (String v : o.values()) {
            if (v.startsWith("subnet6: ")) subnet6 = v.substring("subnet6: ".length()).trim();
            else if (v.startsWith("mask-length6: ")) mask6 = v.substring("mask-length6: ".length()).trim();
            else if (v.startsWith("ipv6-address: ")) addUlaCidrOrAddr(v.substring("ipv6-address: ".length()).trim(), out);
            else if (v.startsWith("ip-netmask: ")) {
                String val = v.substring("ip-netmask: ".length()).trim();
                if (val.indexOf(':') >= 0) addUlaCidrOrAddr(val, out);
            } else if (v.indexOf(": ") < 0 && v.indexOf(':') >= 0) {
                addUlaCidrOrAddr(v.trim(), out);
            }
        }
        if (subnet6 != null && mask6 != null) {
            addUlaCidrOrAddr(subnet6 + "/" + mask6, out);
        }
    }

    private static void addUlaCidrOrAddr(String text, List<UlaRange> out) {
        try {
            int slash = text.indexOf('/');
            String addr = slash < 0 ? text : text.substring(0, slash);
            int prefix = slash < 0 ? 128 : Integer.parseInt(text.substring(slash + 1));
            if (prefix < 0 || prefix > 128) return;
            BigInteger ip = parseIpv6BigInt(addr);
            if (ip == null) return;
            BigInteger mask = prefix == 128 ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(128 - prefix).subtract(BigInteger.ONE);
            BigInteger start = ip.andNot(mask);
            BigInteger end = start.or(mask);
            BigInteger iStart = start.max(ULA_START);
            BigInteger iEnd = end.min(ULA_END);
            if (iStart.compareTo(iEnd) <= 0) {
                out.add(new UlaRange(iStart, iEnd));
            }
        } catch (RuntimeException ignored) {
            // Non-canonical IPv6 representations stay governed by normal cell resolution.
        }
    }

    private static BigInteger parseIpv6BigInt(String text) {
        try {
            java.net.InetAddress inet = java.net.InetAddress.getByName(text);
            byte[] bytes = inet.getAddress();
            if (bytes.length != 16) return null;
            return new BigInteger(1, bytes);
        } catch (Exception e) {
            return null;
        }
    }
}
