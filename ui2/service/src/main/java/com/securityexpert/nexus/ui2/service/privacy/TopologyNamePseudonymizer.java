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
    private static final Pattern IPV4_PATTERN = Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}(?:/\\d{1,2})?$");

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

    /** Uniqueness of cluster and standalone-device pseudonyms (V53); process-local until a database registry is set. */
    private volatile PseudonymRegistry registry = PseudonymRegistry.inMemory();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRegistry(PseudonymRegistry registry) {
        if (registry != null) {
            this.registry = registry;
        }
    }

    /** The computed candidate first, then every other word/digit pair in a fixed walk from it. */
    private static List<String> candidates(String prefix, int dictIndex, int suffixNum) {
        List<String> out = new java.util.ArrayList<>(DICTIONARY.size() * 9 + 1);
        for (int i = 0; i < DICTIONARY.size() * 9; i++) {
            int word = (dictIndex + i / 9) % DICTIONARY.size();
            int digit = ((suffixNum - 1 + i) % 9) + 1;
            out.add(String.format("%s-%s-0%d", prefix, DICTIONARY.get(word), digit));
        }
        return out;
    }

    private String rawKey(String kind, String raw) {
        byte[] h = hmacSha256("REGISTRY:" + kind + ":" + raw.toUpperCase());
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            hex.append(String.format("%02x", h[i]));
        }
        return hex.toString();
    }

    private final Map<String, String> reverseClusterCache = new ConcurrentHashMap<>();
    private final Map<String, String> rawNameToPseudonym = new ConcurrentHashMap<>();

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
        rawNameToPseudonym.put(trimmed, masked);
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
        String trimmed = rawDeviceName.trim();
        if ("unknown".equalsIgnoreCase(trimmed) || IPV4_PATTERN.matcher(trimmed).matches() || trimmed.contains(":")) {
            return "Unknown";
        }
        String key = trimmed + "::" + (clusterMemberRef != null ? clusterMemberRef.trim() : "");
        String masked = deviceCache.computeIfAbsent(key, k -> computeDeviceName(trimmed, clusterMemberRef));
        rawNameToPseudonym.put(trimmed, masked);
        return masked;
    }

    /** A management domain (CMA) name as a stable, unique pseudonym ({@code DOM-TANGO-04}). */
    public String maskDomainName(String rawDomainName) {
        if (rawDomainName == null || rawDomainName.isBlank()) {
            return rawDomainName;
        }
        String trimmed = rawDomainName.trim();
        byte[] hash = hmacSha256("DOMAIN:" + trimmed.toUpperCase());
        int dictIndex = (hash[0] & 0xFF) % DICTIONARY.size();
        int suffixNum = ((hash[1] & 0xFF) % 9) + 1;
        String masked = registry.claim("domain", rawKey("domain", trimmed), candidates("DOM", dictIndex, suffixNum));
        rawNameToPseudonym.put(trimmed, masked);
        return masked;
    }

    public String maskVirtualSystem(String rawVsName, String parentRef) {
        if (rawVsName == null || rawVsName.isBlank()) {
            return rawVsName;
        }
        String key = rawVsName.trim() + "::" + (parentRef != null ? parentRef.trim() : "");
        String masked = vsCache.computeIfAbsent(key, k -> computeVsName(rawVsName.trim(), parentRef));
        rawNameToPseudonym.put(rawVsName.trim(), masked);
        return masked;
    }

    /**
     * Replaces known raw hostnames, cluster names, and VS names in free-form text.
     */
    /** A serial number as a stable, keyed pseudonym ({@code SN-} + 10 hex) -- comparable across screens, never the value. */
    public String maskSerial(String rawSerial) {
        if (rawSerial == null || rawSerial.isBlank()) {
            return rawSerial;
        }
        byte[] hash = hmacSha256("SERIAL:" + rawSerial.strip());
        StringBuilder hex = new StringBuilder("SN-");
        for (int i = 0; i < 5; i++) {
            hex.append(String.format("%02X", hash[i]));
        }
        return hex.toString();
    }

    /**
     * One compiled alternation of every known raw name (longest first, so a VS name wins over its host's
     * prefix), rebuilt only when the dictionary grew. Measured 2026-09-22: copying and sorting the whole
     * dictionary and replacing name by name for every string field made an aiview /devices response
     * ~4x slower than the same response unmasked.
     */
    private volatile java.util.regex.Pattern knownNames;
    private volatile int knownNamesSize = -1;

    private java.util.regex.Pattern knownNamesPattern() {
        int size = rawNameToPseudonym.size();
        java.util.regex.Pattern current = knownNames;
        if (current != null && size == knownNamesSize) {
            return current;
        }
        synchronized (this) {
            if (knownNames != null && rawNameToPseudonym.size() == knownNamesSize) {
                return knownNames;
            }
            java.util.List<String> names = rawNameToPseudonym.keySet().stream().filter(k -> !k.isBlank())
                    .sorted((a, b) -> Integer.compare(b.length(), a.length())).toList();
            knownNamesSize = rawNameToPseudonym.size();
            knownNames = names.isEmpty() ? null
                    : java.util.regex.Pattern.compile(names.stream().map(java.util.regex.Pattern::quote)
                            .collect(java.util.stream.Collectors.joining("|")));
            return knownNames;
        }
    }

    public String maskText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        java.util.regex.Pattern pattern = knownNamesPattern();
        if (pattern == null) {
            return text;
        }
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        do {
            String replacement = rawNameToPseudonym.getOrDefault(matcher.group(), matcher.group());
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
        } while (matcher.find());
        matcher.appendTail(out);
        return out.toString();
    }

    private String computeClusterName(String raw) {
        byte[] hash = hmacSha256("CLUSTER:" + raw.toUpperCase());
        int dictIndex = (hash[0] & 0xFF) % DICTIONARY.size();
        int suffixNum = ((hash[1] & 0xFF) % 9) + 1;
        return registry.claim("cluster", rawKey("cluster", raw), candidates("CLS", dictIndex, suffixNum));
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
        return registry.claim("device", rawKey("device", rawDeviceName), candidates("FW", dictIndex, suffixNum));
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
