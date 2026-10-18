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

    private void registerTextName(String raw, String masked) {
        if (raw.length() >= 3 && !raw.matches("\\p{N}+")) rawNameToPseudonym.put(raw, masked);
    }

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
        registerTextName(trimmed, masked);
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
        registerTextName(trimmed, masked);
        return masked;
    }

    /**
     * The device's own pseudonym, unique per device. {@link #maskDeviceName(String, String)} derives a name from the
     * hostname and cluster reference alone, so two devices with the same inputs share it: a Cisco ASA failover pair
     * replicates one hostname onto both units, and a member's {@code -M1}/{@code -M2} ordinal is read from the
     * hostname suffix (or a keyed hash of it). With the device ID and a {@link MaskingDeviceDirectory}, the oldest
     * device of such a group keeps the shared name and every later one gets a deterministic disambiguator: the
     * lowest member ordinal free in its cluster, or the next free standalone name from the keyed registry walk. A
     * device whose name was already unique keeps it unchanged; so does any call without a device ID.
     */
    public String maskDeviceName(String rawDeviceName, String clusterMemberRef, String deviceId) {
        String base = maskDeviceName(rawDeviceName, clusterMemberRef);
        if (deviceId == null || deviceId.isBlank() || deviceDirectory == null || base == null || "Unknown".equals(base)) {
            return base;
        }
        DeviceNameOverride override = disambiguation().get(deviceId.trim());
        // The override belongs to the device's own hostname only: a certificate CN or any other name beside the
        // same device ID keeps its own pseudonym.
        return override != null && override.hostname().equals(rawDeviceName.trim()) ? override.pseudonym() : base;
    }

    /**
     * A device a manager lists but neXus does not hold (a FortiManager's FortiGates, a Management Center's ProxySGs,
     * an Infoblox grid's members) as {@code MD-WORD-0n}: its own prefix family, so it never reads as a virtual
     * system ({@code VS-...}). Keyed, deterministic and unique like device names (UI review 2026-10-18, P2-3B).
     */
    public String maskManagedDeviceName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return rawName;
        }
        String trimmed = rawName.trim();
        String masked = managedCache.computeIfAbsent(trimmed.toUpperCase(java.util.Locale.ROOT), upper -> {
            byte[] hash = hmacSha256("MANAGED:" + upper);
            int dictIndex = (hash[0] & 0xFF) % DICTIONARY.size();
            int suffixNum = ((hash[1] & 0xFF) % 9) + 1;
            // The registry's 'domain' kind already holds arbitrary presentation labels in keyed namespaces
            // (policy labels do the same); the MD- prefix keeps these apart from DOM- names.
            return registry.claim("domain", "managed:" + rawKey("managed", trimmed), candidates("MD", dictIndex, suffixNum));
        });
        registerTextName(trimmed, masked);
        return masked;
    }

    private final Map<String, String> managedCache = new ConcurrentHashMap<>();

    /** Device IDs and the hostnames they show, oldest registration first; none in tests or without a database. */
    private volatile MaskingDeviceDirectory deviceDirectory;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDeviceDirectory(MaskingDeviceDirectory deviceDirectory) {
        this.deviceDirectory = deviceDirectory;
        this.disambiguationAt = null;
    }

    private record DeviceNameOverride(String hostname, String pseudonym) {
    }

    /** A new or renamed device is seen within this long; one directory read serves every response meanwhile. */
    private static final java.time.Duration DIRECTORY_TTL = java.time.Duration.ofSeconds(60);
    private volatile Map<String, DeviceNameOverride> disambiguation = Map.of();
    private volatile java.time.Instant disambiguationAt;

    private Map<String, DeviceNameOverride> disambiguation() {
        java.time.Instant at = disambiguationAt;
        java.time.Instant now = java.time.Instant.now();
        if (at != null && at.plus(DIRECTORY_TTL).isAfter(now)) {
            return disambiguation;
        }
        synchronized (this) {
            if (disambiguationAt != null && disambiguationAt.plus(DIRECTORY_TTL).isAfter(now)) {
                return disambiguation;
            }
            MaskingDeviceDirectory directory = deviceDirectory;
            List<MaskingDeviceDirectory.Entry> entries;
            try {
                entries = directory == null ? List.of() : directory.devices();
            } catch (RuntimeException unavailable) {
                // Without the directory a shared name stays shared (the state before this table existed); retry later.
                disambiguationAt = now.minus(DIRECTORY_TTL).plusSeconds(5);
                return disambiguation;
            }
            disambiguation = computeDisambiguation(entries);
            disambiguationAt = now;
            return disambiguation;
        }
    }

    private static final Pattern MEMBER_PSEUDONYM = Pattern.compile("^(FW-.+-M)(\\d+)$");

    private Map<String, DeviceNameOverride> computeDisambiguation(List<MaskingDeviceDirectory.Entry> entries) {
        Map<String, List<MaskingDeviceDirectory.Entry>> byBase = new java.util.LinkedHashMap<>();
        for (MaskingDeviceDirectory.Entry entry : entries) {
            if (entry == null || entry.deviceId() == null || entry.hostname() == null || entry.hostname().isBlank()) {
                continue;
            }
            String base = maskDeviceName(entry.hostname(), entry.clusterRef());
            if (base == null || "Unknown".equals(base)) {
                continue;
            }
            byBase.computeIfAbsent(base, ignored -> new java.util.ArrayList<>()).add(entry);
        }
        java.util.Set<String> used = new java.util.HashSet<>(byBase.keySet());
        Map<String, DeviceNameOverride> overrides = new java.util.HashMap<>();
        byBase.forEach((base, group) -> {
            if (group.size() < 2) {
                return;
            }
            for (int rank = 0; rank < group.size(); rank++) {
                MaskingDeviceDirectory.Entry entry = group.get(rank);
                String pseudonym = rank == 0 ? base : disambiguate(base, entry.hostname().trim(), rank, used);
                used.add(pseudonym);
                // Every device of the group is listed, the oldest too: a response that masks its hostname without
                // the cluster reference would otherwise give both the same standalone name.
                overrides.put(entry.deviceId().trim(), new DeviceNameOverride(entry.hostname().trim(), pseudonym));
            }
        });
        return Map.copyOf(overrides);
    }

    private String disambiguate(String base, String hostname, int rank, java.util.Set<String> used) {
        Matcher member = MEMBER_PSEUDONYM.matcher(base);
        if (member.matches()) {
            for (int ordinal = 1; ordinal < 100; ordinal++) {
                String candidate = member.group(1) + ordinal;
                if (!used.contains(candidate)) {
                    return candidate;
                }
            }
        }
        String key = hostname.toUpperCase(java.util.Locale.ROOT) + "#" + rank;
        byte[] hash = hmacSha256("DEVICE:" + key);
        int dictIndex = (hash[0] & 0xFF) % DICTIONARY.size();
        int suffixNum = ((hash[1] & 0xFF) % 9) + 1;
        List<String> walk = candidates("FW", dictIndex, suffixNum).stream().filter(c -> !used.contains(c)).toList();
        return registry.claim("device", rawKey("device", key), walk.isEmpty() ? List.of(base + "-" + (rank + 1)) : walk);
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
        registerTextName(trimmed, masked);
        return masked;
    }

    public String maskVirtualSystem(String rawVsName, String parentRef) {
        if (rawVsName == null || rawVsName.isBlank()) {
            return rawVsName;
        }
        String key = rawVsName.trim() + "::" + (parentRef != null ? parentRef.trim() : "");
        String masked = vsCache.computeIfAbsent(key, k -> computeVsName(rawVsName.trim(), parentRef));
        registerTextName(rawVsName.trim(), masked);
        return masked;
    }

    /**
     * Replaces known raw hostnames, cluster names, and VS names in free-form text.
     */
    /** A serial number as a stable, keyed pseudonym ({@code SN-} + 10 hex) -- comparable across screens, never the value. */
    /** Exact, case-sensitive policy labels; native identity formatting is never normalized. */
    public String maskPolicyName(String namespace, String value) {
        if (value == null || value.isEmpty()) return value;
        byte[] hash = hmacSha256("POLICY:" + namespace.length() + ":" + namespace + value);
        String rawKey = "policy:" + java.util.HexFormat.of().formatHex(hash, 0, 16);
        return policyCache.computeIfAbsent(rawKey, ignored -> {
            String prefix = switch (namespace) {
                case "policy" -> "POL";
                case "rule", "uuid" -> "RULE";
                case "address" -> "ADDR";
                case "group", "address-group", "service-group", "application-group", "time-group" -> "GRP";
                case "service" -> "SVC";
                case "application" -> "APP";
                case "tag" -> "TAG";
                case "zone" -> "ZONE";
                default -> namespace.startsWith("profile-setting") ? "PROF" : "OBJ";
            };
            int word = (hash[0] & 0xff) % DICTIONARY.size();
            int number = (((hash[1] & 0xff) << 8) | (hash[2] & 0xff)) % 9999 + 1;
            List<String> choices = new java.util.ArrayList<>();
            for (int i = 0; i < 64; i++) choices.add(String.format("%s-%s-%02d", prefix,
                    DICTIONARY.get(word), (number + i - 1) % 9999 + 1));
            // Keyed fallback retains the original identity space if all short candidates are occupied.
            choices.add(prefix + "-" + java.util.HexFormat.of().formatHex(hash, 0, 8)
                    + "-" + java.util.HexFormat.of().formatHex(hash, 8, 16));
            // Existing domain registry permits arbitrary presentation labels; the keyed namespace separates claims.
            return registry.claim("domain", rawKey, choices);
        });
    }

    private final Map<String, String> policyCache = new ConcurrentHashMap<>();

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
     * A value carried in packet payload (HTTP host or path segment, TLS SNI, SIP Call-ID, DNS question, vendor trailer
     * text) as a stable keyed token: {@code KIND-} + 10 upper-case hex of the HMAC, like {@link #maskSerial}. No
     * registry claim: captures can hold many thousands of distinct values, and a 40-bit keyed hash keeps them
     * distinct without a database round trip per value. The same value always gives the same token.
     */
    public String maskPayloadToken(String kind, String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        byte[] hash = hmacSha256("PAYLOAD:" + kind + ":" + raw);
        StringBuilder token = new StringBuilder(kind).append('-');
        for (int i = 0; i < 5; i++) {
            token.append(String.format("%02X", hash[i]));
        }
        return token.toString();
    }

    /**
     * One compiled alternation of whole name tokens (never short or numeric names), longest first,
     * rebuilt only when the dictionary grew. Measured 2026-09-22: copying and sorting the whole dictionary and replacing name by name for every string field made an aiview /devices response
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
            java.util.List<String> names = rawNameToPseudonym.keySet().stream()
                    .sorted((a, b) -> Integer.compare(b.length(), a.length())).toList();
            knownNamesSize = rawNameToPseudonym.size();
            knownNames = names.isEmpty() ? null
                    : java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}_.-])(?:"
                            + names.stream().map(java.util.regex.Pattern::quote)
                                    .collect(java.util.stream.Collectors.joining("|"))
                            + ")(?![\\p{L}\\p{N}_.-])");
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
