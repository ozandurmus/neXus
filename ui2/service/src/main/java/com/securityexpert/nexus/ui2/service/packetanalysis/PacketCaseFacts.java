package com.securityexpert.nexus.ui2.service.packetanalysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import com.securityexpert.nexus.ui2.packetanalysis.engine.CaseQueries;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Db;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Engine;
import com.securityexpert.nexus.ui2.platform.PacketAnalysisPaths;

/**
 * The three stored facts of an analysed case that Device events reads, as the case's {@code project.duckdb} settings
 * hold them: the engine's {@code report} (findings, segments, window), the {@code topology} (capture points, paths)
 * and the {@code nexus_correlation} the service stored after the analysis (hop status and, for a MATCH, the neXus
 * device id). Nothing else of the case database is opened: no packet, no flow, no payload-derived text.
 *
 * @param report      the report map, or null when the engine stored none
 * @param topology    the topology map, or null
 * @param correlation the correlation map, or null when no correlation was ever stored
 */
public record PacketCaseFacts(Map<String, Object> report, Map<String, Object> topology, Map<String, Object> correlation) {

    /** Reads the facts of one case; empty when the case has no completed analysis database. */
    public interface Reader {
        Optional<PacketCaseFacts> read(String caseId) throws IOException;
    }

    /** The production reader: a read-only connection to the case database on the analysis volume. */
    public static Reader onVolume(Path root) {
        return caseId -> {
            Path dbPath = PacketAnalysisPaths.projectDuckDb(root, caseId);
            if (!Files.isRegularFile(dbPath)) return Optional.empty();
            try (var conn = Engine.openReadOnly(dbPath); var db = new Db(conn)) {
                return Optional.of(new PacketCaseFacts(map(CaseQueries.settingGet(db, "report")),
                        map(CaseQueries.settingGet(db, "topology")), map(CaseQueries.settingGet(db, "nexus_correlation"))));
            } catch (Exception e) {
                throw new IOException("case database could not be read", e);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }
}
