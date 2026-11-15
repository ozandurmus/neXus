package com.securityexpert.nexus.ui2.service.device.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The member-difference view of one cluster: for each stored field, whether the members agree. Pure and deterministic:
 * it compares only what the caller hands it and never fills a gap. A field is
 * <ul>
 *   <li>{@code DIFFERS} as soon as two members both have a value and the values are not equal;</li>
 *   <li>{@code UNKNOWN} when no two members are known to differ but at least one member has no stored value;</li>
 *   <li>{@code SAME} only when every member has a value and all values are equal.</li>
 * </ul>
 * A cluster that does not have at least two members is not comparable and carries no fields.
 */
public final class ClusterMemberDiff {
    public static final String SAME = "SAME";
    public static final String DIFFERS = "DIFFERS";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String PAIR_INCOMPLETE = "PAIR_INCOMPLETE";
    private static final int DETAIL_LIMIT = 20;

    private ClusterMemberDiff() {}

    /**
     * One member's stored value for one field. {@code display} is what the screen shows; {@code identity} is what equality
     * is decided on (the two differ for set-like fields, where the display is a count and the identity is the full set);
     * {@code elements} lists the set members of a set-like field so a difference can name what is not shared.
     */
    public record Cell(String display, String identity, java.util.Set<String> elements) {
        public static Cell of(String value) { return new Cell(value, value, null); }
        public static Cell ofSet(String display, java.util.Set<String> elements) {
            return new Cell(display, String.join("\u0001", new TreeSet<>(elements)), new TreeSet<>(elements));
        }
    }

    public record FieldSpec(String key, String label, String note) {}

    public record Field(String key, String label, String note, String status, Map<String, String> values, List<String> detail) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("label", label);
            m.put("note", note);
            m.put("status", status);
            m.put("values", values);
            m.put("detail", detail);
            return m;
        }
    }

    public record Result(String clusterMemberRef, List<String> memberIds, boolean comparable, String reason, List<Field> fields) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cluster_member_ref", clusterMemberRef);
            m.put("members", memberIds.stream().map(id -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("device_id", id);
                return row;
            }).toList());
            m.put("comparable", comparable);
            m.put("reason", reason);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("same", count(SAME));
            summary.put("differs", count(DIFFERS));
            summary.put("unknown", count(UNKNOWN));
            m.put("summary", summary);
            m.put("fields", fields.stream().map(Field::toMap).toList());
            return m;
        }

        private long count(String status) { return fields.stream().filter(f -> f.status().equals(status)).count(); }
    }

    /** @param cells field key to (member id to the member's stored value, absent when nothing is stored) */
    public static Result compare(String clusterMemberRef, List<String> memberIds, List<FieldSpec> specs,
            Map<String, Map<String, Cell>> cells) {
        List<String> ordered = memberIds.stream().sorted().toList();
        if (ordered.size() < 2) return new Result(clusterMemberRef, ordered, false, PAIR_INCOMPLETE, List.of());
        List<Field> fields = new ArrayList<>();
        for (FieldSpec spec : specs) {
            Map<String, Cell> byMember = cells.getOrDefault(spec.key(), Map.of());
            Map<String, String> values = new LinkedHashMap<>();
            boolean missing = false;
            java.util.Set<String> identities = new java.util.HashSet<>();
            for (String id : ordered) {
                Cell cell = byMember.get(id);
                if (cell == null || cell.display() == null) { values.put(id, null); missing = true; continue; }
                values.put(id, cell.display());
                identities.add(cell.identity());
            }
            String status = identities.size() > 1 ? DIFFERS : missing ? UNKNOWN : SAME;
            fields.add(new Field(spec.key(), spec.label(), spec.note(), status, values, status.equals(DIFFERS) ? detail(ordered, byMember) : List.of()));
        }
        return new Result(clusterMemberRef, ordered, true, null, fields);
    }

    /** Set-like fields: the elements that not every known member has (names only, capped). */
    private static List<String> detail(List<String> ordered, Map<String, Cell> byMember) {
        List<java.util.Set<String>> sets = new ArrayList<>();
        for (String id : ordered) {
            Cell cell = byMember.get(id);
            if (cell != null && cell.elements() != null) sets.add(cell.elements());
        }
        if (sets.size() < 2) return List.of();
        TreeSet<String> union = new TreeSet<>();
        sets.forEach(union::addAll);
        List<String> notShared = union.stream().filter(e -> !sets.stream().allMatch(s -> s.contains(e))).toList();
        if (notShared.size() <= DETAIL_LIMIT) return notShared;
        List<String> capped = new ArrayList<>(notShared.subList(0, DETAIL_LIMIT));
        capped.add("and " + (notShared.size() - DETAIL_LIMIT) + " more");
        return capped;
    }
}
