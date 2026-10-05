package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.SecurityWebMvcConfigTestAccess;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

class JobTranscriptControllerTest {
    private static final String JOB_ID = "00000000-0000-4000-8000-000000000001";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String JSONL = "{\"seq\":1,\"channel\":\"ssh\",\"kind\":\"command\",\"text\":\"show synthetic\"}\n"
            + "{\"seq\":2,\"channel\":\"https\",\"kind\":\"response\",\"text\":\"synthetic answer\"}\n";

    private static JobTranscriptController controller(ArtefactStore store, JobRecordDao.BackupTranscriptRef ref) {
        JobRecordDao jobs = mock(JobRecordDao.class);
        when(jobs.backupTranscript(JOB_ID)).thenReturn(Optional.ofNullable(ref));
        return new JobTranscriptController(jobs, new ArtefactStoreAccess(store), JSON);
    }

    private static JobTranscriptController stored(Path root, byte[] bytes, boolean gzip) throws Exception {
        var store = new FileArtefactStore(root, ArtefactStoreCipher.fromBase64Key(
                Base64.getEncoder().encodeToString(new byte[32])));
        try (var handle = store.open("synthetic-device", JOB_ID, "transcript", gzip)) {
            handle.sink().write(bytes);
            var ref = handle.finish();
            return controller(store, new JobRecordDao.BackupTranscriptRef(ref.ref().value(), ref.wrappedDataKey()));
        }
    }

    @Test
    void returnsTheCompleteArrayAndNoStoreBeforeHttpSuccess(@TempDir Path root) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(stored(root, JSONL.getBytes(StandardCharsets.UTF_8), true)).build();
        mvc.perform(get("/jobs/{id}/transcript", JOB_ID)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("[" + JSONL.strip().replace("\n", ",") + "]"));
    }

    @Test
    void reproducesTheOldPerEntrySerializationClosingTheResponse() throws Exception {
        var output = new OutputStream() {
            boolean closed;
            @Override public void write(int value) throws IOException {
                if (closed) throw new IOException("synthetic response already closed");
            }
            @Override public void close() { closed = true; }
        };
        output.write('[');
        JSON.writeValue(output, JSON.readTree("{\"seq\":1}"));
        assertThrows(IOException.class, () -> output.write(','));
    }

    @Test
    void sharedWriterRoundTripsForEveryTranscriptJobType(@TempDir Path root) throws Exception {
        // The service already includes worker at runtime; keep its production compile boundary intact.
        Class<?> writer = Class.forName("com.securityexpert.nexus.ui2.worker.transcript.JobTranscript");
        for (String jobType : List.of("cp_gateway_backup", "cp_gaia_snapshot", "cp_mds_export", "cp_spark_sftp_backup",
                "pan_device_state_backup", "asa_config_backup", "fgt_config_backup", "https_vendor_backup",
                "rdw_cc_config_backup", "cp_policy_collect", "pan_policy_collect")) {
            Object transcript = writer.getConstructor().newInstance();
            writer.getMethod("add", String.class, String.class, String.class).invoke(transcript, "job", "note", jobType);
            writer.getMethod("add", String.class, String.class, String.class).invoke(transcript, "job", "verdict", "COMPLETED");
            var bytes = new ByteArrayOutputStream();
            writer.getMethod("writeTo", OutputStream.class).invoke(transcript, bytes);
            var controller = stored(root.resolve(jobType), bytes.toByteArray(), true);
            var response = controller.transcript(JOB_ID, new MockHttpServletRequest());
            assertEquals(HttpStatus.OK, response.getStatusCode(), jobType);
            var entries = JSON.valueToTree(response.getBody());
            assertEquals(2, entries.size(), jobType);
            assertEquals(jobType, entries.get(0).get("text").asText());
            assertEquals("COMPLETED", entries.get(1).get("text").asText());
            assertEquals(new JobTranscriptController.TranscriptHealth(true, true, 2, "JSON_LINES", null),
                    controller.health(JOB_ID).getBody());
        }
    }

    @Test
    void missingAndCorruptArtefactsReturnStableCodesBefore200(@TempDir Path root) throws Exception {
        var missing = controller(null, null);
        assertEquals(HttpStatus.CONFLICT, missing.transcript(JOB_ID, new MockHttpServletRequest()).getStatusCode());
        assertEquals(Map.of("error", "TRANSCRIPT_MISSING"), missing.transcript(JOB_ID, new MockHttpServletRequest()).getBody());
        assertFalse(missing.health(JOB_ID).getBody().present());

        for (String bytes : List.of("{broken", JSONL + "{broken", "[{}] {}", "[null]")) {
            var bad = stored(root, bytes.getBytes(StandardCharsets.UTF_8), true);
            var response = bad.transcript(JOB_ID, new MockHttpServletRequest());
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertEquals(Map.of("error", "TRANSCRIPT_PARSE_FAILED"), response.getBody());
            assertTrue(bad.health(JOB_ID).getBody().decrypts());
            assertNull(bad.health(JOB_ID).getBody().entries());
        }
        var invalidRef = controller(mock(ArtefactStore.class), new JobRecordDao.BackupTranscriptRef("", new byte[] {1}));
        assertEquals(Map.of("error", "TRANSCRIPT_DECRYPT_FAILED"), invalidRef.transcript(JOB_ID, new MockHttpServletRequest()).getBody());
        var store = mock(ArtefactStore.class);
        when(store.retrieve(any(), any(), eq(false))).thenThrow(new IllegalStateException("synthetic private detail"));
        var badKey = controller(store, new JobRecordDao.BackupTranscriptRef("synthetic/ref", new byte[] {1}));
        assertEquals(Map.of("error", "TRANSCRIPT_DECRYPT_FAILED"), badKey.transcript(JOB_ID, new MockHttpServletRequest()).getBody());
        assertEquals(new JobTranscriptController.TranscriptHealth(true, false, null, "UNKNOWN", "TRANSCRIPT_DECRYPT_FAILED"),
                badKey.health(JOB_ID).getBody());
        var mvc = MockMvcBuilders.standaloneSetup(badKey).build();
        mvc.perform(get("/jobs/{id}/transcript", JOB_ID)).andExpect(status().isInternalServerError())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"error\":\"TRANSCRIPT_DECRYPT_FAILED\"}", true));
        // Restubbing with when(...) would invoke the previous throwing stub during test setup.
        doThrow(new java.nio.file.NoSuchFileException("synthetic/ref")).when(store).retrieve(any(), any(), eq(false));
        assertEquals("TRANSCRIPT_MISSING", badKey.health(JOB_ID).getBody().errorCode());
        assertFalse(badKey.health(JOB_ID).getBody().present());
    }

    @Test
    void corruptCiphertextAndLateReadFailuresAreNotCommitted(@TempDir Path root) throws Exception {
        var controller = stored(root, JSONL.getBytes(StandardCharsets.UTF_8), true);
        Path artefact;
        try (var paths = Files.walk(root)) {
            artefact = paths.filter(p -> p.toString().endsWith(".enc")).findFirst().orElseThrow();
        }
        byte[] encrypted = Files.readAllBytes(artefact);
        encrypted[encrypted.length - 1] ^= 1;
        Files.write(artefact, encrypted);
        assertEquals(Map.of("error", "TRANSCRIPT_DECRYPT_FAILED"), controller.transcript(JOB_ID, new MockHttpServletRequest()).getBody());

        var store = mock(ArtefactStore.class);
        when(store.retrieve(any(), any(), eq(false))).thenAnswer(call -> new java.io.InputStream() {
            @Override public int read() throws IOException { throw new IOException("synthetic late failure"); }
        });
        var late = controller(store, new JobRecordDao.BackupTranscriptRef("synthetic/ref", new byte[] {1}));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, late.transcript(JOB_ID, new MockHttpServletRequest()).getStatusCode());
    }

    @Test
    void replayViewerGets403OnContentAndOnlySafeFieldsOnHealth(@TempDir Path root) throws Exception {
        var controller = stored(root, JSONL.getBytes(StandardCharsets.UTF_8), true);
        var request = new MockHttpServletRequest();
        request.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true);
        assertEquals(HttpStatus.FORBIDDEN, controller.transcript(JOB_ID, request).getStatusCode());
        var gate = mock(com.securityexpert.nexus.ui2.service.security.GateChain.class);
        when(gate.evaluate(any(), any())).thenReturn(new com.securityexpert.nexus.ui2.service.security.GateOutcome.Proceed("synthetic-session", "synthetic-aiview"));
        when(gate.isReplayViewer("synthetic-aiview")).thenReturn(true);
        var routes = Map.of("GET /jobs/*/transcript", ActionRegistry.JOB_TRANSCRIPT_READ,
                "GET /api/v2/jobs/*/transcript/health", ActionRegistry.JOB_LOG_READ);
        var mvc = MockMvcBuilders.standaloneSetup(controller).addInterceptors(new GateChainInterceptor(gate, routes)).build();
        mvc.perform(get("/jobs/{id}/transcript", JOB_ID).servletPath("/jobs/" + JOB_ID + "/transcript"))
                .andExpect(status().isForbidden()).andExpect(content().string(""));
        mvc.perform(get("/api/v2/jobs/{id}/transcript/health", JOB_ID)
                .servletPath("/api/v2/jobs/" + JOB_ID + "/transcript/health"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"present\":true,\"decrypts\":true,\"entries\":2,\"format\":\"JSON_LINES\",\"errorCode\":null}", true));
        assertEquals(ActionRegistry.JOB_LOG_READ, SecurityWebMvcConfigTestAccess.actionIdFor("GET /api/v2/jobs/*/transcript/health"));
        assertTrue(new ActionRegistry().find(ActionRegistry.JOB_LOG_READ).orElseThrow().requiredRoleTokens().isEmpty());
        assertEquals(java.util.Set.of("role:security_admin", "role:backup_admin"),
                new ActionRegistry().find(ActionRegistry.JOB_TRANSCRIPT_READ).orElseThrow().requiredRoleTokens());
    }
}
