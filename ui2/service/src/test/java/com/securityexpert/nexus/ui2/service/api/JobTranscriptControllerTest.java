package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

class JobTranscriptControllerTest {
    private static final String JOB_ID = "00000000-0000-4000-8000-000000000001";
    private static final String JSONL = "{\"seq\":1,\"channel\":\"ssh\",\"kind\":\"command\",\"text\":\"show synthetic\"}\n"
            + "{\"seq\":2,\"channel\":\"https\",\"kind\":\"response\",\"text\":\"ok\"}\n";

    @Test
    void streamsJsonArrayAndReturnsNotFoundWithoutTranscript() throws Exception {
        JobRecordDao jobs = mock(JobRecordDao.class);
        ArtefactStore store = mock(ArtefactStore.class);
        when(jobs.backupTranscript(JOB_ID)).thenReturn(Optional.of(new JobRecordDao.BackupTranscriptRef("synthetic/ref", new byte[] { 1 })));
        when(store.retrieve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(new ByteArrayInputStream(JSONL.getBytes(StandardCharsets.UTF_8)));
        var controller = new JobTranscriptController(jobs, new ArtefactStoreAccess(store), new ObjectMapper());
        var request = new MockHttpServletRequest();
        var response = controller.transcript(JOB_ID, request);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        response.getBody().writeTo(bytes);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(bytes.toString(StandardCharsets.UTF_8).startsWith("[{"));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("\"channel\":\"https\""));

        when(jobs.backupTranscript("missing-job")).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, controller.transcript("missing-job", request).getStatusCode());
    }

    @Test
    void replayViewerIsRefusedEvenWhenTheActionRoleGateAllowsAnotherRole() {
        JobRecordDao jobs = mock(JobRecordDao.class);
        var controller = new JobTranscriptController(jobs, new ArtefactStoreAccess(null), new ObjectMapper());
        var request = new MockHttpServletRequest();
        request.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);
        assertEquals(HttpStatus.FORBIDDEN, controller.transcript(JOB_ID, request).getStatusCode());
        assertFalse(ActionRegistry.JOB_TRANSCRIPT_READ.isBlank());
    }

    @Test
    void actionAcceptsOnlyTheTwoApprovedRoleTokens() {
        var descriptor = new ActionRegistry().find(ActionRegistry.JOB_TRANSCRIPT_READ).orElseThrow();
        assertEquals(java.util.Set.of("role:security_admin", "role:backup_admin"), descriptor.requiredRoleTokens());
    }
}
