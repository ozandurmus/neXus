package com.securityexpert.nexus.ui2.persistence.artefact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;

/**
 * The fleet-large and firewall-logs alerts (PO 2026-11-04) over a scripted connection: what is read, what is inserted,
 * that an artefact alerts once, and that neither alert can reach retention. The behaviour over real rows (unique
 * indexes, the digest) is {@code BackupSizeAlertDatabaseTest} in integration-tests, which needs PostgreSQL.
 */
class BackupSizeAlertRepositoryTest {

    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    private final List<String> sql = new ArrayList<>();
    private final List<List<Object>> bindings = new ArrayList<>();

    private static Result<Record> rows(String[] columns, String[]... values) {
        String[][] data = new String[values.length + 1][];
        data[0] = columns;
        System.arraycopy(values, 0, data, 1, values.length);
        return DSL.using(SQLDialect.POSTGRES).fetchFromStringData(data);
    }

    /** A connection answering each statement through {@code script}; a null answer is an empty result. */
    private JooqTransactionBoundary boundary(Function<String, Object> script) {
        var dsl = DSL.using(new MockConnection(ctx -> {
            sql.add(ctx.sql());
            bindings.add(List.of(ctx.bindings()));
            Object answer = script.apply(ctx.sql());
            if (answer instanceof Integer updateCount) {
                return new MockResult[] {new MockResult(updateCount, null)};
            }
            Result<Record> result = answer == null ? DSL.using(SQLDialect.POSTGRES).newResult() : (Result<Record>) answer;
            return new MockResult[] {new MockResult(result.size(), result)};
        }), SQLDialect.POSTGRES);
        return new JooqTransactionBoundary(dsl);
    }

    private String insertOf(String deviationClass) {
        return sql.stream().filter(s -> s.startsWith("insert into backup_deviation_record")
                && s.contains("'" + deviationClass + "'")).findFirst().orElse(null);
    }

    private static Function<String, Object> fleetScript(long newBytes, boolean alreadyAlerted, int insertCount) {
        return statement -> {
            if (statement.contains("select a.device_id, a.vendor, a.plaintext_bytes")) {
                return rows(new String[] {"device_id", "vendor", "plaintext_bytes"},
                        new String[] {"dev-tango-04", "check_point", Long.toString(newBytes)});
            }
            if (statement.startsWith("select 1 from backup_deviation_record")) {
                return alreadyAlerted ? rows(new String[] {"one"}, new String[] {"1"}) : null;
            }
            if (statement.contains("distinct on (a.device_id)")) {
                return rows(new String[] {"plaintext_bytes"}, new String[] {Long.toString(200 * MIB)},
                        new String[] {Long.toString(250 * MIB)}, new String[] {Long.toString(300 * MIB)});
            }
            if (statement.startsWith("insert into backup_deviation_record")) {
                return insertCount;
            }
            return null;
        };
    }

    @Test
    void aLargeBackupRaisesOneFleetLargeRowWithSizesAndRatioOnly() {
        var repository = new JooqBackupSizeAnomalyRepository(boundary(fleetScript(28 * GIB, false, 1)));
        var detected = repository.inspectFleet("art-00000001", "system:worker").orElseThrow();
        assertEquals(4, detected.verdict().fleetDevices(), "three other devices and this one");
        String insert = insertOf("fleet_large");
        assertTrue(insert != null, sql.toString());
        int index = sql.indexOf(insert);
        assertTrue(bindings.get(index).contains(detected.verdict().summary()));
        assertTrue(bindings.get(index).stream().noneMatch(b -> String.valueOf(b).contains("FW-TANGO-04")),
                "no hostname is ever written");
        assertTrue(insert.contains("on conflict do nothing"), "the unique index is the backstop");
        assertTrue(sql.stream().anyMatch(s -> s.toLowerCase().contains("set local app.actor_fingerprint")), "audited");
        String fleet = sql.stream().filter(s -> s.contains("distinct on (a.device_id)")).findFirst().orElseThrow();
        assertTrue(fleet.contains("a.vendor = ?") && fleet.contains("a.device_id <> ?"), fleet);
        assertTrue(fleet.contains(JooqRetentionQueries.andNotRemoved("a")), "live artefacts only, as the screen lists");
    }

    @Test
    void aNormalSizedBackupRaisesNothing() {
        var repository = new JooqBackupSizeAnomalyRepository(boundary(fleetScript(300 * MIB, false, 1)));
        assertTrue(repository.inspectFleet("art-00000001", "system:worker").isEmpty());
        assertTrue(insertOf("fleet_large") == null);
    }

    @Test
    void anArtefactThatAlreadyAlertedOrIsAnOwnHistoryAnomalyIsNotAlertedAgain() {
        var repository = new JooqBackupSizeAnomalyRepository(boundary(fleetScript(28 * GIB, true, 1)));
        assertTrue(repository.inspectFleet("art-00000001", "system:worker").isEmpty());
        assertTrue(insertOf("fleet_large") == null);
        String check = sql.stream().filter(s -> s.startsWith("select 1 from backup_deviation_record")).findFirst().orElseThrow();
        assertTrue(check.contains("deviation_class in ('size_anomaly', 'fleet_large')"), check);
    }

    @Test
    void aLostRaceOnTheUniqueIndexIsNotAnAlert() {
        var repository = new JooqBackupSizeAnomalyRepository(boundary(fleetScript(28 * GIB, false, 0)));
        assertTrue(repository.inspectFleet("art-00000001", "system:worker").isEmpty());
    }

    @Test
    void aSnapshotOrUnknownArtefactIsNeverChecked() {
        var repository = new JooqBackupSizeAnomalyRepository(boundary(statement -> null));
        assertTrue(repository.inspectFleet("art-00000001", "system:worker").isEmpty());
        String read = sql.stream().filter(s -> s.contains("a.vendor, a.plaintext_bytes")).findFirst().orElseThrow();
        assertTrue(read.contains("coalesce(a.backup_type, 'standard') = 'standard'"));
        assertTrue(insertOf("fleet_large") == null);
    }

    private static Function<String, Object> listingScript(long logBytes, long totalBytes, boolean alreadyAlerted,
            boolean checkPoint) {
        return statement -> {
            if (statement.contains("select a.device_id from backup_artefact a")) {
                return checkPoint ? rows(new String[] {"device_id"}, new String[] {"dev-tango-04"}) : null;
            }
            if (statement.startsWith("select 1 from backup_deviation_record")) {
                return alreadyAlerted ? rows(new String[] {"one"}, new String[] {"1"}) : null;
            }
            if (statement.contains("as log_bytes")) {
                return rows(new String[] {"log_bytes", "total_bytes"},
                        new String[] {Long.toString(logBytes), Long.toString(totalBytes)});
            }
            if (statement.startsWith("insert into backup_deviation_record")) {
                return 1;
            }
            return null;
        };
    }

    private void store(Function<String, Object> script) {
        new JooqBackupArtefactEntryRepository(boundary(script)).recordListed("art-00000001", List.of());
    }

    @Test
    void storingAListingWithEnoughFirewallLogsRaisesOneRowAndNeverNamesAFile() {
        store(listingScript(270 * GIB, 605 * GIB, false, true));
        String insert = insertOf("firewall_logs");
        assertTrue(insert != null, sql.toString());
        assertTrue(bindings.get(sql.indexOf(insert)).contains(
                "Backup contains 270.0 GiB of firewall logs (44.6% of the archive contents)"));
        String sums = sql.stream().filter(s -> s.contains("as log_bytes")).findFirst().orElseThrow();
        assertTrue(sums.contains("e.entry_path ~ ?") && sums.contains("e.entry_type = 'file'"), sums);
        assertTrue(bindings.get(sql.indexOf(sums)).contains(FirewallLogsInBackupRule.LOG_PATH_REGEX));
        assertFalse(sums.contains("select e.entry_path"), "paths are matched in the database, never selected");
        assertTrue(sql.stream().anyMatch(s -> s.toLowerCase().contains("set local app.actor_fingerprint")), "audited");
    }

    @Test
    void aListingBelowTheThresholdAlreadyAlertedOrNotCheckPointRaisesNothing() {
        store(listingScript(99 * MIB, GIB, false, true));
        assertTrue(insertOf("firewall_logs") == null);
        sql.clear();
        store(listingScript(270 * GIB, 605 * GIB, true, true));
        assertTrue(insertOf("firewall_logs") == null, "a re-list of an alerted artefact does not alert again");
        sql.clear();
        store(listingScript(270 * GIB, 605 * GIB, false, false));
        assertTrue(insertOf("firewall_logs") == null, "only a live Check Point standard backup");
        String read = sql.stream().filter(s -> s.contains("select a.device_id from backup_artefact a")).findFirst().orElseThrow();
        assertTrue(read.contains("a.vendor = 'check_point'") && read.contains("coalesce(a.backup_type, 'standard') = 'standard'"));
    }

    @Test
    void aFailingAlertCheckNeverCostsTheListing() {
        store(statement -> {
            if (statement.contains("as log_bytes")) {
                throw new IllegalStateException("scripted failure");
            }
            return statement.contains("select a.device_id from backup_artefact a")
                    ? rows(new String[] {"device_id"}, new String[] {"dev-tango-04"}) : null;
        });
        assertTrue(sql.stream().anyMatch(s -> s.contains("insert into backup_artefact_content_listing")), sql.toString());
    }

    @Test
    void retentionNeverReadsTheNewAlertClasses() {
        var queries = new JooqRetentionQueries(boundary(statement -> null));
        queries.expiredStandardBackups(7);
        queries.supersededSizeAnomalies();
        for (String statement : sql) {
            assertFalse(statement.contains("fleet_large") || statement.contains("firewall_logs"), statement);
        }
        assertTrue(JooqBackupSizeAnomalyRepository.isOpenAnomaly("a").contains("deviation_class = 'size_anomaly'"));
        assertTrue(JooqBackupSizeAnomalyRepository.isPinnedLastGood("a").contains("deviation_class = 'size_anomaly'"));
    }
}
