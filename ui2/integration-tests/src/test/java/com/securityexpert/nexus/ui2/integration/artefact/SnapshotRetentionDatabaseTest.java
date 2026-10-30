package com.securityexpert.nexus.ui2.integration.artefact;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactClass;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactValidation;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupArtefactManifestRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqRetentionQueries;

/**
 * The Backups screen's "Gaia snapshots: newest N kept per gateway" rule, end to end over the real insert: a snapshot
 * written through {@link JooqBackupArtefactManifestRepository} lands with {@code backup_type = 'snapshot'}, the depth
 * read offers every snapshot of a device but its newest N, and the age read never offers a snapshot. A Gaia backup
 * ({@code add backup local}) stays {@code standard}: the age rule prunes it, the depth rule never reaches it. Before
 * the fix the insert never wrote the column, every snapshot defaulted to {@code standard}, and the depth read could
 * not match a single row. Synthetic values only. Needs PostgreSQL (excluded from the default build like the other
 * integration tests).
 */
class SnapshotRetentionDatabaseTest {

    private static BackupArtefactManifestRecord manifest(String id, String device, String backupType) {
        return new BackupArtefactManifestRecord(id, device, Optional.empty(), ArtefactClass.BACKUP, "check_point",
                Optional.of("R81.20"), "a".repeat(64), "plain-sha", 10L, "cipher-sha", 26L, "v1", new byte[] { 0 },
                ArtefactValidation.reachedWithoutRestore(ArtefactValidation.V1), "standard", Optional.empty(), id + ".enc",
                Optional.of(BackupArtefactManifestRecord.DEVIATION_FIRST), backupType);
    }

    private static void age(Connection db, String id, int days) throws Exception {
        db.setAutoCommit(false);
        try {
            Ui2Rows.setAuditContext(db, Ui2Rows.ACTOR, Ui2Rows.ACTION);
            try (var statement = db.createStatement()) {
                statement.execute("UPDATE backup_artefact SET created_at = now() - interval '" + days + " days' "
                        + "WHERE artefact_id = '" + id + "'");
            }
            db.commit();
        } finally {
            db.setAutoCommit(true);
        }
    }

    private static List<String> ids(List<JooqRetentionQueries.Candidate> candidates) {
        return candidates.stream().map(JooqRetentionQueries.Candidate::artefactId).toList();
    }

    @Test
    void theDepthRuleReachesRecordedSnapshotsAndTheAgeRuleOnlyStandardBackups() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("snapshot_retention")) {
            fixture.runFlyway();
            String gw;
            try (Connection db = fixture.migrateConnection()) {
                gw = Ui2Rows.insertDevice(db, Ui2Rows.insertCredentialReference(db), "ENROLLED");
            }
            var boundary = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
            var repository = new JooqBackupArtefactManifestRepository(boundary);
            repository.record(manifest("snap-21", gw, BackupArtefactManifestRecord.BACKUP_TYPE_SNAPSHOT), "system:worker", "t");
            repository.record(manifest("snap-14", gw, BackupArtefactManifestRecord.BACKUP_TYPE_SNAPSHOT), "system:worker", "t");
            repository.record(manifest("snap-01", gw, BackupArtefactManifestRecord.BACKUP_TYPE_SNAPSHOT), "system:worker", "t");
            repository.record(manifest("gaia-09", gw, BackupArtefactManifestRecord.BACKUP_TYPE_STANDARD), "system:worker", "t");
            repository.record(manifest("gaia-02", gw, BackupArtefactManifestRecord.BACKUP_TYPE_STANDARD), "system:worker", "t");
            try (Connection db = fixture.migrateConnection()) {
                age(db, "snap-21", 21);
                age(db, "snap-14", 14);
                age(db, "snap-01", 1);
                age(db, "gaia-09", 9);
                age(db, "gaia-02", 2);
                assertEquals(3L, Ui2Rows.count(db,
                        "SELECT count(*) FROM backup_artefact WHERE backup_type = 'snapshot' AND device_id = '" + gw + "'"),
                        "the insert writes the snapshot type, not the column default");
            }
            var queries = new JooqRetentionQueries(boundary);

            assertEquals(List.of("snap-21", "snap-14"), ids(queries.snapshotsBeyondDepth(1)),
                    "depth 1: every snapshot but the newest, oldest first");
            assertEquals(List.of("gaia-09"), ids(queries.expiredStandardBackups(7)),
                    "the age rule offers the old Gaia backup and never a snapshot, however old");

            // A pruned snapshot leaves the depth read; the newest one is still never offered.
            queries.recordRemoved("ledger-snap-21", "snap-21", "standard", "system:worker", "backup_artefact_pruned");
            assertEquals(List.of("snap-14"), ids(queries.snapshotsBeyondDepth(1)));
        }
    }
}
