package com.securityexpert.nexus.ui2.service.zonematrix;

import com.securityexpert.nexus.ui2.service.report.ReportDocument.Cell;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.CellKind;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Column;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Section;
import com.securityexpert.nexus.ui2.service.report.ReportDocument.Table;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixContent.Zone;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Content of the opt-in zone-blocks appendix for the zone matrix evidence report
 * (docs/design/ZONE_MATRIX_CONTRACT.md sections 3.4, 7.3, 8.2; {@code ZM-38}, {@code ZM-33}).
 *
 * <p>Pure and deterministic: no Spring, no database, no file store, no clock. Kept separate from
 * {@link ZoneMatrixEvidenceReport} and {@link ZoneMatrixEvidenceReportInputs} so the later Reports R1 wiring composes
 * it only when requested and permitted.</p>
 *
 * <h2>Rights and fail-closed rules</h2>
 * <ul>
 *   <li>When {@link Rights#appendixRequested()} is {@code false}, no appendix section is produced and nothing mentions
 *       blocks.</li>
 *   <li>When requested without {@link Rights#maySeeBlocks()} ({@code zone_matrix_zones_read}), without
 *       {@link Rights#rawReportRight()} ({@code report_raw_view}), or by a replay viewer
 *       ({@link Rights#replayViewer()}), the call refuses with {@link #BLOCKS_NOT_PERMITTED} and an empty section —
 *       never a partial or masked block list, even if the two right flags were both set alongside
 *       {@code replayViewer = true}.</li>
 *   <li>Zone names are taken as already projected for the caller's screen view and passed through unchanged (unless
 *       null or blank, which prints {@code UNKNOWN}); the Reports R1 CSV renderer owns formula-injection escaping.</li>
 *   <li>Blocks print in {@link ZoneBlock}'s canonical CIDR form (RFC 5952 for IPv6), with exact {@link BigInteger}
 *       address counts in decimal text. Zones with no blocks print one {@code "no blocks"} row; nothing is capped.
 *       Hostnames, zone descriptions, zone ids and witness addresses are never read or emitted.</li>
 * </ul>
 */
public final class ZoneMatrixEvidenceBlocksAppendix {
    private ZoneMatrixEvidenceBlocksAppendix() {}

    /** Section identifier when the appendix is included. */
    public static final String SECTION_ID = "appendix-zone-blocks";

    /** Exact title of the appendix section (contract 7.3 / ZM-38). */
    public static final String SECTION_TITLE = "Appendix: zone blocks";

    /** Table identifier inside the appendix section. */
    public static final String TABLE_ID = "zone-blocks";

    /** Table title inside the appendix section. */
    public static final String TABLE_TITLE = "Zone blocks (configured CIDR blocks per zone)";

    /** Fixed refusal code when the blocks appendix is requested without the required raw and block rights. */
    public static final String BLOCKS_NOT_PERMITTED = "BLOCKS_NOT_PERMITTED";

    /** Marker printed in the block column when a zone defines zero blocks. */
    public static final String NO_BLOCKS = "no blocks";

    /** Fixed explanatory sentence carried by the appendix section. */
    public static final String FIXED_SENTENCE =
            "Blocks describe the bank's network and are listed because the requester asked for them and holds the raw-view right.";

    private static final String UNKNOWN = "UNKNOWN";

    /**
     * Explicit caller request and authorization facts for the blocks appendix.
     *
     * @param appendixRequested {@code true} only when the caller explicitly opted in to the blocks appendix
     * @param maySeeBlocks      holds {@code zone_matrix_zones_read} (and is not a replay viewer)
     * @param rawReportRight    holds {@code report_raw_view} (and is not a replay viewer)
     * @param replayViewer      {@code true} for the {@code replay_viewer} / {@code aiview} persona; always refuses a
     *                          requested appendix even if {@code maySeeBlocks} and {@code rawReportRight} are {@code true}
     */
    public record Rights(
            boolean appendixRequested,
            boolean maySeeBlocks,
            boolean rawReportRight,
            boolean replayViewer) {

        /** Convenience constructor when {@code replayViewer} is {@code false}. */
        public Rights(boolean appendixRequested, boolean maySeeBlocks, boolean rawReportRight) {
            this(appendixRequested, maySeeBlocks, rawReportRight, false);
        }

        /** Explicit constructor for the {@code replay_viewer} persona. */
        public static Rights forReplayViewer(boolean appendixRequested, boolean maySeeBlocks, boolean rawReportRight) {
            return new Rights(appendixRequested, maySeeBlocks, rawReportRight, true);
        }

        /** True only when the caller holds both rights and is not a replay viewer. */
        public boolean permitted() {
            return !replayViewer && maySeeBlocks && rawReportRight;
        }
    }

    /** Bundled input record matching {@link ZoneMatrixEvidenceReport.Input} style. */
    public record Input(List<Zone> zones, Rights rights) {
        public Input {
            zones = zones == null ? List.of() : List.copyOf(zones);
            Objects.requireNonNull(rights, "rights");
        }
    }

    /** Outcome of evaluating the opt-in blocks appendix. */
    public enum Status {
        /** {@code appendixRequested} was {@code false}: no appendix section, no refusal. */
        NOT_REQUESTED,
        /** Requested and permitted: {@link Result#section()} holds the appendix section. */
        INCLUDED,
        /** Requested without permission: refused with {@link #BLOCKS_NOT_PERMITTED} and an empty section. */
        REFUSED
    }

    /**
     * Result of building the blocks appendix: either {@link Status#NOT_REQUESTED} (empty section, no refusal code),
     * {@link Status#INCLUDED} (present section, no refusal code), or {@link Status#REFUSED} (empty section and
     * {@link #BLOCKS_NOT_PERMITTED}).
     */
    public record Result(Status status, Optional<Section> section, String refusalCode) {
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(section, "section");
            switch (status) {
                case NOT_REQUESTED -> {
                    if (section.isPresent() || refusalCode != null) {
                        throw new IllegalArgumentException("NOT_REQUESTED carries neither section nor refusalCode");
                    }
                }
                case INCLUDED -> {
                    if (section.isEmpty() || refusalCode != null) {
                        throw new IllegalArgumentException("INCLUDED carries a section and no refusalCode");
                    }
                }
                case REFUSED -> {
                    if (section.isPresent() || refusalCode == null || refusalCode.isBlank()) {
                        throw new IllegalArgumentException("REFUSED carries a refusalCode and an empty section");
                    }
                }
            }
        }

        public static Result notRequested() {
            return new Result(Status.NOT_REQUESTED, Optional.empty(), null);
        }

        public static Result included(Section section) {
            return new Result(Status.INCLUDED, Optional.of(Objects.requireNonNull(section, "section")), null);
        }

        public static Result refused(String refusalCode) {
            return new Result(Status.REFUSED, Optional.empty(), refusalCode);
        }

        public boolean isIncluded() {
            return status == Status.INCLUDED;
        }

        public boolean isRefused() {
            return status == Status.REFUSED;
        }

        /** Zero sections when omitted or refused; one section when included. */
        public List<Section> sections() {
            return section.map(List::of).orElseGet(List::of);
        }
    }

    /** Builds the opt-in blocks appendix from a bundled {@link Input}. */
    public static Result build(Input in) {
        Objects.requireNonNull(in, "in");
        return build(in.zones(), in.rights());
    }

    /**
     * Builds the opt-in blocks appendix for {@code zones} (already projected for the caller's view, in the version's
     * zone order) and {@code rights}.
     */
    public static Result build(List<Zone> zones, Rights rights) {
        Objects.requireNonNull(zones, "zones");
        Objects.requireNonNull(rights, "rights");
        for (Zone z : zones) {
            Objects.requireNonNull(z, "zone");
        }
        if (!rights.appendixRequested()) {
            return Result.notRequested();
        }
        if (!rights.permitted()) {
            return Result.refused(BLOCKS_NOT_PERMITTED);
        }

        List<List<Cell>> rows = new ArrayList<>();
        for (Zone z : zones) {
            String zoneName = zoneName(z.name());
            List<ZoneBlock> ordered = z.blocks().stream().sorted(ZoneBlock.ORDER).toList();
            if (ordered.isEmpty()) {
                rows.add(List.of(
                        Cell.text(zoneName),
                        Cell.text(""),
                        Cell.text(NO_BLOCKS),
                        decimalCount(BigInteger.ZERO)));
            } else {
                for (ZoneBlock block : ordered) {
                    rows.add(List.of(
                            Cell.text(zoneName),
                            Cell.status(block.family().name()),
                            Cell.text(block.toString()),
                            decimalCount(block.size())));
                }
            }
        }

        Table table = new Table(
                TABLE_ID,
                TABLE_TITLE,
                List.of(
                        new Column("zone", "Zone"),
                        new Column("family", "Family"),
                        new Column("block", "Block (canonical CIDR)"),
                        new Column("addressCount", "Address count (addresses in block)")),
                rows,
                "The version defines no zones.");
        Section section = new Section(
                SECTION_ID,
                SECTION_TITLE,
                List.of(),
                List.of(),
                List.of(table),
                List.of(FIXED_SENTENCE));
        return Result.included(section);
    }

    /** Projected zone name as entered (unchanged for the R1 CSV guard), or UNKNOWN when missing/blank. */
    private static String zoneName(String projectedName) {
        return projectedName == null || projectedName.isBlank() ? UNKNOWN : projectedName;
    }

    private static Cell decimalCount(BigInteger exactCount) {
        return new Cell(exactCount.toString(), CellKind.NUMBER, null);
    }
}
