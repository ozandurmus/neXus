package com.securityexpert.nexus.ui2.service.privacy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Deterministically pseudonymizes Cluster, Firewall, and Virtual System names
 * while preserving parent-child topology and cross-referencing (PRIVATE_REPLAY_ARCHITECTURE §150).
 */
@Component
public class TopologyNamePseudonymizer {

    private static final List<String> DICTIONARY = List.of(
            "ALPHA", "BRAVO", "CHARLIE", "DELTA", "ECHO", "FOXTROT", "GOLF", "HOTEL",
            "INDIA", "JULIET", "KILO", "LIMA", "MIKE", "NOVEMBER", "OSCAR", "PAPA",
            "QUEBEC", "ROMEO", "SIERRA", "TANGO", "UNIFORM", "VICTOR", "WHISKEY", "XRAY",
            "YANKEE", "ZULU", "APOLLO", "BEACON", "CYGNUS", "DARIUS", "HELIOS", "ORION",
            "PHOENIX", "TITAN", "VORTEX"
    );

    private static final Pattern MEMBER_ORDINAL_PATTERN = Pattern.compile("[-_.]?(?:0?([1-9]|10)|m([1-9]|10)|member([1-9]|10)|aa|bb)$", Pattern.CASE_INSENSITIVE);

    private final byte[] secretKey;
    private final Map<String, String> clusterCache = new ConcurrentHashMap<>();
    private final Map<String, String> deviceCache = new ConcurrentHashMap<>();
    private final Map<String, String> vsCache = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public TopologyNamePseudonymizer(HmacKeyProvider keyProvider) {
        this(keyProvider.getSecretKey());
    }

    public TopologyNamePseudonymizer(byte[] secretKey) {
        this.secretKey = Objects.requireNonNull(secretKey, "secretKey").clone();
    }

    private final Map<String, String> reverseClusterCache = new ConcurrentHashMap<>();

    public String maskClusterName(String rawClusterName) {
        if (rawClusterName == null || rawClusterName.isBlank()) {
            return rawClusterName;
        }
        String trimmed = rawClusterName.trim();
        if (reverseClusterCache.containsKey(trimmed)) {
            return trimmed;
        }
        String masked = clusterCache.computeIfAbsent(trimmed, this::computeClusterName);
        reverseClusterCache.put(masked, trimmed);
        return masked;
    }

    public String resolveClusterName(String pseudonym, java.util.function.Supplier<List<String>> candidateSupplier) {
        if (pseudonym == null || pseudonym.isBlank()) {
            return null;
        }
        String resolved = reverseClusterCache.get(pseudonym.trim());
        if (resolved != null) {
            return resolved;
        }
        if (candidateSupplier != null) {
            for (String candidate : candidateSupplier.get()) {
                maskClusterName(candidate);
            }
            return reverseClusterCache.get(pseudonym.trim());
        }
        return null;
    }

    public String maskDeviceName(String rawDeviceName, String clusterMemberRef) {
        if (rawDeviceName == null || rawDeviceName.isBlank()) {
            return rawDeviceName;
        }
        String key = rawDeviceName.trim() + "::" + (clusterMemberRef != null ? clusterMemberRef.trim() : "");
        return deviceCache.computeIfAbsent(key, k -> computeDeviceName(rawDeviceName.trim(), clusterMemberRef));
    }

    public String maskVirtualSystem(String rawVsName, String parentRef) {
        if (rawVsName == null || rawVsName.isBlank()) {
            return rawVsName;
        }
        String key = rawVsName.trim() + "::" + (parentRef != null ? parentRef.trim() : "");
        return vsCache.computeIfAbsent(key, k -> computeVsName(rawVsName.trim(), parentRef));
    }

    private String computeClusterName(String raw) {
        byte[] hash = hmacSha256("CLUSTER:" + raw.toUpperCase());
        int dictIndex = (hash[0] & 0xFF) % DICTIONARY.size();
        int suffixNum = ((hash[1] & 0xFF) % 9) + 1;
        return String.format("CLS-%s-0%d", DICTIONARY.get(dictIndex), suffixNum);
    }

    private String computeDeviceName(String rawDeviceName, String clusterMemberRef) {
        if (clusterMemberRef != null && !clusterMemberRef.isBlank()) {
            String maskedCluster = maskClusterName(clusterMemberRef);
            String base = maskedCluster.startsWith("CLS-") ? maskedCluster.substring(4) : maskedCluster;
            String ordinal = extractMemberOrdinal(rawDeviceName);
            return String.format("FW-%s-M%s", base, ordinal);
        }
        byte[] hash = hmacSha256("DEVICE:" + rawDeviceName.toUpperCase());
        int dictIndex = (hash[0] & 0xFF) % DICTIONARY.size();
        int suffixNum = ((hash[1] & 0xFF) % 9) + 1;
        return String.format("FW-%s-0%d", DICTIONARY.get(dictIndex), suffixNum);
    }

    private String computeVsName(String rawVsName, String parentRef) {
        String baseParent;
        if (parentRef != null && !parentRef.isBlank()) {
            if (parentRef.contains("CLS") || parentRef.contains("cls") || clusterCache.containsKey(parentRef.trim())) {
                String masked = maskClusterName(parentRef);
                baseParent = masked.startsWith("CLS-") ? masked.substring(4) : masked;
            } else {
                String masked = maskDeviceName(parentRef, null);
                baseParent = masked.startsWith("FW-") ? masked.substring(3) : masked;
            }
        } else {
            baseParent = "SEC";
        }
        byte[] hash = hmacSha256("VS:" + rawVsName.toUpperCase());
        int suffixNum = ((hash[0] & 0xFF) % 99) + 1;
        return String.format("VS-%s-%02d", baseParent, suffixNum);
    }

    private String extractMemberOrdinal(String deviceName) {
        Matcher matcher = MEMBER_ORDINAL_PATTERN.matcher(deviceName.trim());
        if (matcher.find()) {
            String group1 = matcher.group(1);
            if (group1 != null) return group1;
            String group2 = matcher.group(2);
            if (group2 != null) return group2;
            String group3 = matcher.group(3);
            if (group3 != null) return group3;
            String full = matcher.group(0).toLowerCase();
            if (full.contains("aa")) return "1";
            if (full.contains("bb")) return "2";
        }
        byte[] hash = hmacSha256("ORDINAL:" + deviceName);
        return String.valueOf(((hash[0] & 0xFF) % 2) + 1);
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
