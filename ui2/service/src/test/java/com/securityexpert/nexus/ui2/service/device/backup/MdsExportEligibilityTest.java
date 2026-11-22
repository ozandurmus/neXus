package com.securityexpert.nexus.ui2.service.device.backup;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;

/**
 * Due-target selection for the scheduled Check Point MDS export (PO 2026-11-18).
 *
 * The eligibility rule itself lives in SQL (non-terminal job exclusion, one cp_mds_export per local day). These tests
 * run without a database, so they prove three things: the SQL carries the exclusions and binds the day start exactly
 * as the argument, the rows the database returns are passed through as opaque target ids, and an empty result stays
 * empty. They do NOT prove the SQL's row-level semantics; that needs a real-database integration test.
 *
 * All target ids are synthetic (FW-TANGO-04 style).
 */
class MdsExportEligibilityTest {

    private static final Field<String> ID = DSL.field(DSL.name("id"), String.class);

    private static Instant istanbulMidnight(String localDate) {
        return LocalDateTime.parse(localDate + "T00:00:00").atZone(JobWindowPolicy.ZONE_ID).toInstant();
    }

    /** Captures the last statement and answers it with the given target ids as the "id" column. */
    private static final class Capture {
        String sql;
        Object[] bindings;
        int calls;

        TransactionBoundary boundary(List<String> ids) {
            DSLContext dsl = DSL.using(new MockConnection(query -> {
                calls++;
                sql = query.sql();
                bindings = query.bindings();
                DSLContext pg = DSL.using(SQLDialect.POSTGRES);
                var rows = pg.newResult(ID);
                for (String id : ids) {
                    var rec = pg.newRecord(ID);
                    rec.set(ID, id);
                    rows.add(rec);
                }
                return new MockResult[]{new MockResult(rows.size(), rows)};
            }), SQLDialect.POSTGRES);
            return new TransactionBoundary() {
                @Override public <T> T inTransaction(Function<DSLContext, T> work) {
                    return work.apply(dsl);
                }
            };
        }
    }

    @Test
    void emptyResultWhenNoTargetIsEligible() {
        var capture = new Capture();
        var eligibility = new MdsExportEligibility(capture.boundary(List.of()));

        Set<String> due = eligibility.dueTargets(istanbulMidnight("2026-11-22"));

        assertTrue(due.isEmpty());
        assertEquals(1, capture.calls);
    }

    @Test
    void eligibleTargetsAreReturnedAsOpaqueIds() {
        var capture = new Capture();
        var eligibility = new MdsExportEligibility(capture.boundary(List.of("FW-TANGO-04", "FW-JULIET-06")));

        Set<String> due = eligibility.dueTargets(istanbulMidnight("2026-11-22"));

        assertEquals(Set.of("FW-TANGO-04", "FW-JULIET-06"), due);
    }

    @Test
    void sqlExcludesTargetsWithNonTerminalJobsAndTodaysExport() {
        var capture = new Capture();
        var eligibility = new MdsExportEligibility(capture.boundary(List.of()));

        eligibility.dueTargets(istanbulMidnight("2026-11-22"));

        // Busy: any job not in a terminal state blocks the device.
        assertTrue(capture.sql.contains("j.state not in ('COMPLETED','FAILED','REJECTED','CANCELLED',"
                + "'OUTCOME_UNKNOWN','RECONCILED')"), capture.sql);
        // Already exported since the day start: any cp_mds_export job, whoever started it, blocks the device.
        assertTrue(capture.sql.contains("j.job_type = 'cp_mds_export' and j.submitted_at >= "), capture.sql);
        // Only enrolled, enabled Check Point management servers with backup_target set are candidates.
        assertTrue(capture.sql.contains("d.vendor_hint = 'check_point'"), capture.sql);
        assertTrue(capture.sql.contains("d.enrollment_state = 'ENROLLED' and not d.disabled"), capture.sql);
    }

    @Test
    void dayStartArgumentIsBoundAsTheExportCutoff() {
        var capture = new Capture();
        var eligibility = new MdsExportEligibility(capture.boundary(List.of()));
        Instant dayStart = istanbulMidnight("2026-11-22");

        eligibility.dueTargets(dayStart);

        assertEquals(1, capture.bindings.length);
        assertEquals(Timestamp.from(dayStart), capture.bindings[0]);
    }

    @Test
    void nextLocalDayMovesTheCutoffSoYesterdaysExportNoLongerBlocks() {
        var capture = new Capture();
        var eligibility = new MdsExportEligibility(capture.boundary(List.of()));

        eligibility.dueTargets(istanbulMidnight("2026-11-21"));
        Object previousCutoff = capture.bindings[0];
        eligibility.dueTargets(istanbulMidnight("2026-11-22"));
        Object nextCutoff = capture.bindings[0];

        assertNotEquals(previousCutoff, nextCutoff);
        assertEquals(Timestamp.from(istanbulMidnight("2026-11-22")), nextCutoff);
        assertTrue(((Timestamp) nextCutoff).after((Timestamp) previousCutoff));
    }
}
