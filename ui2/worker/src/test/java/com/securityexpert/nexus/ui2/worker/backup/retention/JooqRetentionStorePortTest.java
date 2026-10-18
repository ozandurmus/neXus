package com.securityexpert.nexus.ui2.worker.backup.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.JooqRetentionQueries;

/** The file side of the pruner: it never deletes outside the artefact store root, for any artefact class. */
class JooqRetentionStorePortTest {

    private static JooqRetentionStorePort port(Path root, List<String> sql) {
        var dsl = DSL.using(new MockConnection(ctx -> {
            sql.add(ctx.sql());
            return new MockResult[] {new MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult())};
        }), SQLDialect.POSTGRES);
        return new JooqRetentionStorePort(new JooqRetentionQueries(new JooqTransactionBoundary(dsl)), root);
    }

    private static RetentionPruningService.ArtefactRecord record(String path) {
        return new RetentionPruningService.ArtefactRecord("cfg-1", "dev-1", path, 10L, "standard", "standard");
    }

    @Test
    void aConfigurationArchiveInsideTheStoreRootIsDeleted(@TempDir Path root) throws IOException {
        Path file = Files.writeString(root.resolve("cfg-1.enc"), "x");

        assertTrue(port(root, new ArrayList<>()).deleteArtefact(record("cfg-1.enc")));
        assertFalse(Files.exists(file));
    }

    @Test
    void aPathOutsideTheStoreRootIsRefusedAndTheFileSurvives(@TempDir Path base) throws IOException {
        Path root = Files.createDirectory(base.resolve("store"));
        Path outside = Files.writeString(base.resolve("outside.enc"), "x");
        JooqRetentionStorePort port = port(root, new ArrayList<>());

        assertFalse(port.deleteArtefact(record("../outside.enc")));
        assertFalse(port.deleteArtefact(record(outside.toString())), "an absolute path elsewhere is refused too");
        assertTrue(Files.exists(outside));
    }

    @Test
    void aSiblingDirectorySharingTheRootsNamePrefixIsNotInsideTheRoot(@TempDir Path base) throws IOException {
        Path root = Files.createDirectory(base.resolve("store"));
        Path sibling = Files.createDirectory(base.resolve("store-old"));
        Path file = Files.writeString(sibling.resolve("cfg.enc"), "x");

        assertFalse(port(root, new ArrayList<>()).deleteArtefact(record("../store-old/cfg.enc")));
        assertTrue(Files.exists(file));
    }

    @Test
    void theConfigurationReadsGoThroughTheQueriesWithTheRequestedDays(@TempDir Path root) {
        List<String> sql = new ArrayList<>();
        JooqRetentionStorePort port = port(root, sql);

        assertTrue(port.findExpiredConfigurationArchives(30).isEmpty());
        assertEquals(1, sql.size());
        assertTrue(sql.get(0).contains("artefact_class = 'configuration'"));
        // an unknown artefact answers "latest": the pruner keeps what it cannot place
        assertTrue(port.isLatestConfigurationArchive("unknown"));
    }
}
