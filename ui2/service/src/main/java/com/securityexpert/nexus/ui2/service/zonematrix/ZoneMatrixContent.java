package com.securityexpert.nexus.ui2.service.zonematrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * The content of one zone-matrix version (ZONE_MATRIX_CONTRACT.md sections 3.1 and 3.5, FROZEN 2026-11-13), delivery
 * step 1: zones and their blocks. Statements and cells arrive in step 2 under a new schema number.
 *
 * <p>The canonical JSON is written by hand so its bytes do not depend on a serializer's configuration: keys in a fixed
 * order, zones sorted by id, blocks canonical and sorted, no whitespace. The version hash is the SHA-256 of these
 * bytes.</p>
 */
public record ZoneMatrixContent(List<Zone> zones) {
    public static final int SCHEMA = 1;
    /** Bounds (section 3.1). The total-block cap is provisional until the section 9 measurement sets it. */
    public static final int MAX_ZONES = 500, MAX_BLOCKS_PER_ZONE = 10_000, MAX_BLOCKS_TOTAL = 200_000,
            MAX_NAME = 64, MAX_DESCRIPTION = 500;
    /** The template roles of section 6.1; a zone may carry none. */
    public static final Set<String> ROLES = Set.of("INTERNET_UNTRUSTED", "DMZ", "USER", "APPLICATION", "DATABASE",
            "MANAGEMENT", "PARTNER", "NON_PRODUCTION");

    public record Zone(String id, String name, String description, String role, List<ZoneBlock> blocks) {
        public Zone {
            blocks = blocks.stream().sorted(ZoneBlock.ORDER).toList();
            description = description == null ? "" : description;
        }
    }

    public ZoneMatrixContent {
        zones = zones.stream().sorted(Comparator.comparing(Zone::id)).toList();
    }

    public static ZoneMatrixContent empty() { return new ZoneMatrixContent(List.of()); }

    public Optional<Zone> zone(String id) { return zones.stream().filter(z -> z.id().equals(id)).findFirst(); }

    public int blockCount() { return zones.stream().mapToInt(z -> z.blocks().size()).sum(); }

    public ZonePartition partition() {
        List<ZonePartition.Placed> placed = new ArrayList<>();
        for (Zone zone : zones) for (ZoneBlock block : zone.blocks()) placed.add(new ZonePartition.Placed(zone.id(), block));
        return ZonePartition.of(placed);
    }

    /**
     * Validates the whole content: bounds, names (unique ignoring case so two zones never read alike), roles, and the
     * partition (a block in two zones fails with {@link ZonePartition.DuplicateBlock}). Returns the partition.
     */
    public ZonePartition validate() {
        if (zones.size() > MAX_ZONES) throw new Invalid("TOO_MANY_ZONES", "at most " + MAX_ZONES + " zones");
        if (blockCount() > MAX_BLOCKS_TOTAL) throw new Invalid("TOO_MANY_BLOCKS", "at most " + MAX_BLOCKS_TOTAL + " blocks in a version");
        Set<String> names = new HashSet<>(), ids = new HashSet<>();
        for (Zone zone : zones) {
            if (zone.id() == null || !zone.id().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") || !ids.add(zone.id()))
                throw new Invalid("INVALID_ZONE_ID", "zone id is not a unique UUID");
            validateName(zone.name());
            if (!names.add(zone.name().toLowerCase(Locale.ROOT))) throw new Invalid("ZONE_NAME_DUPLICATE", "another zone has this name");
            if (zone.description().length() > MAX_DESCRIPTION || zone.description().chars().anyMatch(c -> Character.isISOControl(c) && c != '\n'))
                throw new Invalid("INVALID_DESCRIPTION", "description is too long or holds control characters");
            if (zone.role() != null && !ROLES.contains(zone.role())) throw new Invalid("INVALID_ROLE", "unknown zone role");
            if (zone.blocks().size() > MAX_BLOCKS_PER_ZONE) throw new Invalid("TOO_MANY_BLOCKS", "at most " + MAX_BLOCKS_PER_ZONE + " blocks in a zone");
        }
        return partition();
    }

    static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME || !name.equals(name.strip())
                || name.chars().anyMatch(Character::isISOControl))
            throw new Invalid("INVALID_ZONE_NAME", "zone name must be 1-" + MAX_NAME + " characters without control characters or outer spaces");
        if (name.equalsIgnoreCase("UNASSIGNED")) throw new Invalid("INVALID_ZONE_NAME", "UNASSIGNED is the built-in pseudo-zone");
    }

    /** A refused edit, with a stable code for the API and a reason that never echoes the refused value. */
    public static final class Invalid extends IllegalArgumentException {
        public final String code;
        public Invalid(String code, String reason) { super(reason); this.code = code; }
    }

    // ---------- canonical JSON ----------

    public String canonicalJson() {
        StringBuilder out = new StringBuilder(64 + zones.size() * 96);
        out.append("{\"schema\":").append(SCHEMA).append(",\"zones\":[");
        for (int i = 0; i < zones.size(); i++) {
            Zone z = zones.get(i);
            if (i > 0) out.append(',');
            out.append("{\"id\":"); string(out, z.id());
            out.append(",\"name\":"); string(out, z.name());
            out.append(",\"description\":"); string(out, z.description());
            out.append(",\"role\":"); if (z.role() == null) out.append("null"); else string(out, z.role());
            out.append(",\"blocks\":[");
            for (int j = 0; j < z.blocks().size(); j++) { if (j > 0) out.append(','); string(out, z.blocks().get(j).toString()); }
            out.append("]}");
        }
        return out.append("]}").toString();
    }

    public String sha256() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void string(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        out.append('"');
    }

    /** Reads content stored by {@link #canonicalJson()}. */
    public static ZoneMatrixContent parse(String json, ObjectMapper mapper) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root.path("schema").asInt() != SCHEMA) throw new IllegalStateException("unknown zone-matrix content schema");
            List<Zone> zones = new ArrayList<>();
            for (JsonNode z : root.path("zones")) {
                List<ZoneBlock> blocks = new ArrayList<>();
                for (JsonNode b : z.path("blocks")) blocks.add(ZoneBlock.parse(b.asText()));
                zones.add(new Zone(z.path("id").asText(), z.path("name").asText(), z.path("description").asText(""),
                        z.path("role").isNull() ? null : z.path("role").asText(), blocks));
            }
            return new ZoneMatrixContent(zones);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("stored zone-matrix content is not readable", e);
        }
    }
}
