package com.securityexpert.nexus.ui2.worker.packetanalysis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository;

class PacketAnalysisRetentionPrunerTest {
    private static final String EXPIRED = "11111111-2222-4333-8444-555555555555";
    private static final String FAILING = "22222222-2222-4333-8444-555555555555";
    @TempDir Path root;

    @Test void filesGoFirstThenTheTombstoneAndAFailureKeepsTheRecord() throws Exception {
        var repository = mock(PacketAnalysisRepository.class);
        Path expired = root.resolve("cases").resolve(EXPIRED);
        Files.createDirectories(expired.resolve("runs").resolve("x"));
        Files.write(expired.resolve("captures.pcap"), new byte[10]);
        Files.write(expired.resolve("runs").resolve("x").resolve("report.json"), new byte[3]);
        when(repository.expiredCases(anyInt())).thenReturn(List.of(EXPIRED, FAILING));
        when(repository.prune(eq(EXPIRED), eq("system:worker"), any())).thenAnswer(call -> {
            assertTrue(Files.exists(expired), "the case is still on disk when the transaction starts");
            ((Runnable) call.getArgument(2)).run();
            assertFalse(Files.exists(expired), "the tombstone is written only after the files are gone");
            return true;
        });
        when(repository.prune(eq(FAILING), anyString(), any())).thenThrow(new IllegalStateException("synthetic"));
        var summary = new PacketAnalysisRetentionPruner(repository, root).runOnce();
        assertEquals(1, summary.casesPruned());
        assertEquals(1, summary.failures());
        verify(repository).expiredCases(200);
    }

    @Test void hostTelemetryFilesAreRemovedWithTheCaseByTheSamePass() throws Exception {
        var repository = mock(PacketAnalysisRepository.class);
        Path expired = root.resolve("cases").resolve(EXPIRED);
        Path telemetry = expired.resolve("telemetry").resolve("99999999-8888-4777-8666-555555555555.txt");
        Files.createDirectories(telemetry.getParent());
        Files.writeString(telemetry, "synthetic vmstat text");
        Files.write(expired.resolve("telemetry").resolve(".partial-aaaaaaaa-8888-4777-8666-555555555555"), new byte[5]);
        when(repository.expiredCases(anyInt())).thenReturn(List.of(EXPIRED));
        when(repository.prune(eq(EXPIRED), anyString(), any())).thenAnswer(call -> {
            assertTrue(Files.exists(telemetry), "the telemetry file is still there when the transaction starts");
            ((Runnable) call.getArgument(2)).run();
            return true;
        });
        assertEquals(1, new PacketAnalysisRetentionPruner(repository, root).runOnce().casesPruned());
        assertFalse(Files.exists(telemetry));
        assertFalse(Files.exists(expired), "nothing of the case, telemetry included, is left on the volume");
    }

    @Test void anAlreadyMissingDirectoryStillLetsTheTombstoneLand() {
        var repository = mock(PacketAnalysisRepository.class);
        when(repository.expiredCases(anyInt())).thenReturn(List.of(EXPIRED));
        when(repository.prune(eq(EXPIRED), anyString(), any())).thenAnswer(call -> {
            ((Runnable) call.getArgument(2)).run();
            return true;
        });
        assertEquals(1, new PacketAnalysisRetentionPruner(repository, root).runOnce().casesPruned());
    }

    @Test void aCaseThatCannotBeTouchedIsNotCountedAndNothingOutsideTheRootIsResolved() {
        var repository = mock(PacketAnalysisRepository.class);
        when(repository.expiredCases(anyInt())).thenReturn(List.of("../escape"));
        when(repository.prune(anyString(), anyString(), any())).thenAnswer(call -> {
            ((Runnable) call.getArgument(2)).run();
            return true;
        });
        var summary = new PacketAnalysisRetentionPruner(repository, root).runOnce();
        assertEquals(0, summary.casesPruned());
        assertEquals(1, summary.failures());
    }
}
