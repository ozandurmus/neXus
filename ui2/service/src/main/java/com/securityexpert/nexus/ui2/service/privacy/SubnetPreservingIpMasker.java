package com.securityexpert.nexus.ui2.service.privacy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Deterministically pseudonymizes IPv4 addresses while preserving subnet membership,
 * prefix containment, and relative host offsets (PRIVATE_REPLAY_ARCHITECTURE §156).
 */
@Component
public class SubnetPreservingIpMasker {

    private static final Pattern IPV4_PATTERN = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?:/(\\d{1,2}))?$");
    private static final Pattern EMBEDDED_IPV4_PATTERN = Pattern.compile("\\b(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(?:/\\d{1,2})?)\\b");

    private final byte[] secretKey;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public SubnetPreservingIpMasker(HmacKeyProvider keyProvider) {
        this(keyProvider.getSecretKey());
    }

    public SubnetPreservingIpMasker(byte[] secretKey) {
        this.secretKey = Objects.requireNonNull(secretKey, "secretKey").clone();
    }

    /**
     * Masks an IPv4 address (e.g. "192.168.230.1" or "192.168.230.2/24").
     * Preserves loopback, default route, and subnet relationships.
     */
    public String mask(String ipOrCidr) {
        if (ipOrCidr == null || ipOrCidr.isBlank()) {
            return ipOrCidr;
        }
        String trimmed = ipOrCidr.trim();
        return cache.computeIfAbsent(trimmed, this::computeMaskedIp);
    }

    /**
     * Finds any IPv4 addresses in free-form text (e.g. logs, terminal reasons)
     * and replaces them with their relationship-preserving masked counterpart.
     */
    public String maskText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        Matcher matcher = EMBEDDED_IPV4_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String ipMatch = matcher.group(1);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(mask(ipMatch)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String computeMaskedIp(String trimmed) {
        Matcher matcher = IPV4_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            return "[REDACTED_IP]";
        }

        int o1 = Integer.parseInt(matcher.group(1));
        int o2 = Integer.parseInt(matcher.group(2));
        int o3 = Integer.parseInt(matcher.group(3));
        int o4 = Integer.parseInt(matcher.group(4));

        if (o1 > 255 || o2 > 255 || o3 > 255 || o4 > 255) {
            return "[REDACTED_IP]";
        }

        String cidrGroup = matcher.group(5);
        int prefixLen = cidrGroup != null ? Integer.parseInt(cidrGroup) : 24;
        if (prefixLen < 0 || prefixLen > 32) {
            return "[REDACTED_IP]";
        }

        // Special network invariant preservation
        if (o1 == 0 && o2 == 0 && o3 == 0 && o4 == 0) {
            return trimmed; // 0.0.0.0 or 0.0.0.0/0
        }
        if (o1 == 127) {
            return trimmed; // 127.x.x.x loopback
        }
        if (o1 == 255 && o2 == 255 && o3 == 255 && o4 == 255) {
            return trimmed; // 255.255.255.255 broadcast
        }

        long ipLong = (((long) o1) << 24) | (((long) o2) << 16) | (((long) o3) << 8) | o4;
        long mask = prefixLen == 0 ? 0 : (-1L << (32 - prefixLen)) & 0xFFFFFFFFL;
        long netLong = ipLong & mask;
        long hostOffset = ipLong & ~mask;

        String netAddressStr = String.format("%d.%d.%d.%d",
                (netLong >> 24) & 0xFF, (netLong >> 16) & 0xFF, (netLong >> 8) & 0xFF, netLong & 0xFF);

        byte[] hmacBytes = hmacSha256("SUBNET:" + netAddressStr + "/" + prefixLen);
        int x = (hmacBytes[0] & 0xFF) % 240 + 10; // 10..249
        int y = (hmacBytes[1] & 0xFF) % 254 + 1;  // 1..254

        long synthNetBase = (((10L << 24) | (((long) x) << 16) | (((long) y) << 8)) & mask);
        long synthIpLong = synthNetBase | hostOffset;

        int so1 = (int) ((synthIpLong >> 24) & 0xFF);
        int so2 = (int) ((synthIpLong >> 16) & 0xFF);
        int so3 = (int) ((synthIpLong >> 8) & 0xFF);
        int so4 = (int) (synthIpLong & 0xFF);

        String syntheticIp = String.format("%d.%d.%d.%d", so1, so2, so3, so4);
        return cidrGroup != null ? syntheticIp + "/" + cidrGroup : syntheticIp;
    }

    private byte[] hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
