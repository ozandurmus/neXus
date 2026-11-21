package com.securityexpert.nexus.ui2.service.zonematrix;

import java.util.*;
import java.util.function.Predicate;

/**
 * "neXus segmentation template (proposal)" (ZONE_MATRIX_CONTRACT.md section 6.1, FROZEN 2026-11-13): proposed default
 * statements by zone role, based on general segmentation practice and not on a BDDK clause. Importing it seeds the
 * draft's cells from the zones' roles; every reference field stays empty (neXus never fills them).
 *
 * <p>How the rows of the 6.1 table are read (the table states rows, not their precedence; this is the reading this
 * class implements and the import response and the Matrix tab show):</p>
 * <ul>
 *   <li>{@code UNASSIGNED} plays the role {@code INTERNET_UNTRUSTED} ("by default the UNASSIGNED space"), except that
 *       {@code UNASSIGNED -> UNASSIGNED} is its own row (DENY_ALL) and never takes the diagonal ALLOW_ANY (3.3).</li>
 *   <li>"Internal role" is every role except {@code INTERNET_UNTRUSTED}. "Production roles" are {@code DMZ},
 *       {@code USER}, {@code APPLICATION}, {@code DATABASE} and {@code MANAGEMENT}. "Any role" is any endpoint that has
 *       a role. A zone without a role matches no row and stays on the version default.</li>
 *   <li>A row naming two exact roles wins. Otherwise, of the matching general rows, the strictest statement wins, and
 *       between equal statements the higher severity (fail-closed: two rows never combine into something looser than
 *       either). The diagonal is one of the general rows, so for example {@code PARTNER -> PARTNER} is DENY_ALL.</li>
 *   <li>A pair no row matches gets no cell and follows the version default (DENY_ALL, MEDIUM).</li>
 * </ul>
 */
public final class ZoneMatrixTemplate {
    public static final String NAME = "Overwatch segmentation template (proposal)";
    /** Bumped whenever the rows change, so a version's template origin names the rows it was seeded from. */
    public static final String REVISION = "2026-11-13.2";

    private static final Set<String> PRODUCTION = Set.of("DMZ", "USER", "APPLICATION", "DATABASE", "MANAGEMENT");
    private static final String INTERNET = "INTERNET_UNTRUSTED";

    /** One row of the 6.1 table. {@code exact} rows name two roles; the others are general. */
    public record Row(String text, boolean exact, Predicate<String> source, Predicate<String> destination,
            boolean diagonal, String statement, String severity) {}

    private static Predicate<String> role(String role) { return role::equals; }
    private static Predicate<String> internal() { return r -> r != null && !r.equals(INTERNET); }
    private static Predicate<String> any() { return Objects::nonNull; }

    public static final List<Row> ROWS = List.of(
            new Row("INTERNET_UNTRUSTED -> DMZ", true, role(INTERNET), role("DMZ"), false, "ALLOW_LISTED", "HIGH"),
            new Row("INTERNET_UNTRUSTED -> any internal role except DMZ", false, role(INTERNET),
                    internal().and(r -> !r.equals("DMZ")), false, "DENY_ALL", "CRITICAL"),
            new Row("DMZ -> APPLICATION", true, role("DMZ"), role("APPLICATION"), false, "ALLOW_LISTED", "HIGH"),
            new Row("DMZ -> DATABASE", true, role("DMZ"), role("DATABASE"), false, "DENY_ALL", "CRITICAL"),
            new Row("USER -> DATABASE", true, role("USER"), role("DATABASE"), false, "DENY_ALL", "CRITICAL"),
            new Row("USER -> MANAGEMENT", true, role("USER"), role("MANAGEMENT"), false, "DENY_ALL", "HIGH"),
            new Row("USER -> APPLICATION", true, role("USER"), role("APPLICATION"), false, "ALLOW_LISTED", "MEDIUM"),
            new Row("APPLICATION -> DATABASE", true, role("APPLICATION"), role("DATABASE"), false, "ALLOW_LISTED", "HIGH"),
            new Row("MANAGEMENT -> any role", false, role("MANAGEMENT"), any(), false, "ALLOW_LISTED", "MEDIUM"),
            new Row("any role -> MANAGEMENT except MANAGEMENT", false, any().and(r -> !r.equals("MANAGEMENT")),
                    role("MANAGEMENT"), false, "DENY_ALL", "HIGH"),
            new Row("PARTNER -> any role except DMZ", false, role("PARTNER"), any().and(r -> !r.equals("DMZ")), false,
                    "DENY_ALL", "CRITICAL"),
            new Row("NON_PRODUCTION -> production roles", false, role("NON_PRODUCTION"), PRODUCTION::contains, false,
                    "DENY_ALL", "HIGH"),
            new Row("production roles -> NON_PRODUCTION", false, PRODUCTION::contains, role("NON_PRODUCTION"), false,
                    "DENY_ALL", "HIGH"),
            new Row("same role -> same role (assigned zones only)", false, any(), any(), true, "ALLOW_ANY", "LOW"),
            new Row("internal role -> INTERNET_UNTRUSTED", false, internal(), role(INTERNET), false, "ALLOW_LISTED", "MEDIUM"));

    /** {@code UNASSIGNED -> UNASSIGNED}: its own row, never the diagonal (section 3.3). */
    public static final Row UNASSIGNED_ROW = new Row("UNASSIGNED -> UNASSIGNED", true, r -> false, r -> false, false,
            "DENY_ALL", "MEDIUM");

    private ZoneMatrixTemplate() {}

    /** The row that decides one ordered pair of endpoints, or empty for the version default. */
    public static Optional<Row> decide(ZoneMatrixContent content, String source, String destination) {
        boolean su = source.equals(ZoneMatrixContent.UNASSIGNED), du = destination.equals(ZoneMatrixContent.UNASSIGNED);
        if (su && du) return Optional.of(UNASSIGNED_ROW);
        String s = su ? INTERNET : content.zone(source).map(ZoneMatrixContent.Zone::role).orElse(null);
        String d = du ? INTERNET : content.zone(destination).map(ZoneMatrixContent.Zone::role).orElse(null);
        if (s == null || d == null) return Optional.empty();
        List<Row> general = new ArrayList<>();
        for (Row row : ROWS) {
            boolean matches = row.diagonal() ? s.equals(d) && !su && !du : row.source().test(s) && row.destination().test(d);
            if (!matches) continue;
            if (row.exact()) return Optional.of(row);
            general.add(row);
        }
        return general.stream().min(Comparator
                .comparingInt((Row r) -> ZoneMatrixContent.STATEMENTS.indexOf(r.statement()))
                .thenComparing(Comparator.comparingInt((Row r) -> ZoneMatrixContent.SEVERITIES.indexOf(r.severity())).reversed()));
    }

    /** The matrix the template proposes for this content's zones: default DENY_ALL / MEDIUM, references empty. */
    public static ZoneMatrixContent.Matrix seed(ZoneMatrixContent content) {
        List<ZoneMatrixContent.Cell> cells = new ArrayList<>();
        for (String source : content.endpoints())
            for (String destination : content.endpoints())
                decide(content, source, destination).ifPresent(row -> cells.add(new ZoneMatrixContent.Cell(source, destination,
                        row.statement(), List.of(), row.severity(), null, "", "EMPTY", null, null)));
        return new ZoneMatrixContent.Matrix("DENY_ALL", "MEDIUM", new ZoneMatrixContent.TemplateOrigin(NAME, REVISION),
                content.matrix().serviceSets(), cells);
    }
}
