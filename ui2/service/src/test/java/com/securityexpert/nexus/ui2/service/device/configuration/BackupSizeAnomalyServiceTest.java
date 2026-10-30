package com.securityexpert.nexus.ui2.service.device.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.artefact.JooqBackupSizeAnomalyRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.api.BackupDeviationController;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/** V180 / PO decision 2026-10-30: "accept new size" is security_admin only, needs a reason, never auto-releases. */
class BackupSizeAnomalyServiceTest {

    private static final RbacEvaluator.Decision PERMITTED =
            new RbacEvaluator.Decision(AuthzOutcome.PERMITTED, Optional.empty(), Optional.empty(), Optional.empty());
    private static final RbacEvaluator.Decision DENIED =
            new RbacEvaluator.Decision(AuthzOutcome.DENIED, Optional.empty(), Optional.empty(), Optional.empty());

    private final JooqBackupSizeAnomalyRepository repository = mock(JooqBackupSizeAnomalyRepository.class);
    private final RbacEvaluator rbac = mock(RbacEvaluator.class);
    private final BackupSizeAnomalyService service = new BackupSizeAnomalyService(repository, List::of, rbac);

    private void roles(String actor, boolean securityAdmin, boolean replayViewer) {
        when(rbac.evaluate(eq(actor), eq(Optional.of(RoleToken.SECURITY_ADMIN)), any())).thenReturn(securityAdmin ? PERMITTED : DENIED);
        when(rbac.evaluate(eq(actor), eq(Optional.of(RoleToken.REPLAY_VIEWER)), any())).thenReturn(replayViewer ? PERMITTED : DENIED);
    }

    @Test
    void aSecurityAdminAcceptsWithAReasonAndTheActionIsAudited() {
        roles("admin", true, false);
        when(repository.accept("dev-00000001", "admin", ActionRegistry.BACKUP_SIZE_ANOMALY_ACCEPT, "logs now kept locally"))
                .thenReturn(2);
        assertEquals(2, service.accept("dev-00000001", "  logs now kept locally ", "admin"));
    }

    @Test
    void anyOtherRoleOrTheReplayViewerIsRefusedBeforeAnyWrite() {
        roles("operator", false, false);
        roles("aiview", true, true);
        assertEquals("WRONG_ROLE", assertThrows(BackupSizeAnomalyService.Refusal.class,
                () -> service.accept("dev-00000001", "reason", "operator")).code());
        assertEquals("WRONG_ROLE", assertThrows(BackupSizeAnomalyService.Refusal.class,
                () -> service.accept("dev-00000001", "reason", "aiview")).code());
        assertFalse(service.mayAccept(null));
        verify(repository, never()).accept(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aReasonIsRequiredAndNothingOpenIsReported() {
        roles("admin", true, false);
        assertEquals("REASON_REQUIRED", assertThrows(BackupSizeAnomalyService.Refusal.class,
                () -> service.accept("dev-00000001", "  ", "admin")).code());
        assertEquals("REASON_REQUIRED", assertThrows(BackupSizeAnomalyService.Refusal.class,
                () -> service.accept("dev-00000001", "x".repeat(501), "admin")).code());
        when(repository.accept(anyString(), anyString(), anyString(), anyString())).thenReturn(0);
        assertEquals("NO_OPEN_SIZE_ANOMALY", assertThrows(BackupSizeAnomalyService.Refusal.class,
                () -> service.accept("dev-00000001", "reason", "admin")).code());
    }

    @Test
    void theCardBodyCarriesSizesRatioAndOpaqueIdsWithTheRoleNote() {
        var anomaly = new JooqBackupSizeAnomalyRepository.OpenAnomaly("dev-00000001", "dev-row-1", "art-bad", "art-good",
                "growth", 29L << 30, 1L << 30, 29.0, "Backup size anomaly: grew 29.0x (1.0 GB -> 29.0 GB, median of the last 3 backups)",
                Instant.parse("2026-10-30T02:00:00Z"), 7);
        when(repository.openAnomalies()).thenReturn(List.of(anomaly));
        Map<String, Object> body = BackupDeviationController.sizeAnomalyBody(service.open(), false);
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) body.get("size_anomalies");
        assertEquals(1, rows.size());
        assertEquals("art-good", rows.get(0).get("last_good_artefact_id"));
        assertEquals(7, rows.get(0).get("open_copies"));
        assertEquals("2026-10-30T02:00:00Z", rows.get(0).get("detected_at"));
        assertNull(rows.get(0).get("hostname"), "no inventory row, no name");
        assertEquals(false, body.get("can_accept_size"));
        assertTrue(((String) body.get("size_role_note")).contains("security_admin"));
    }
}
