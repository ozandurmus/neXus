package com.securityexpert.nexus.ui2.service.api;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

import jakarta.servlet.http.HttpServletRequest;

@RestController
public final class JobTranscriptController {
    private static final System.Logger LOG = System.getLogger(JobTranscriptController.class.getName());
    private final JobRecordDao jobs;
    private final ArtefactStoreAccess storeAccess;
    private final JobTranscriptReader reader;

    public JobTranscriptController(JobRecordDao jobs, ArtefactStoreAccess storeAccess, ObjectMapper json) {
        this.jobs = jobs;
        this.storeAccess = storeAccess;
        this.reader = new JobTranscriptReader(json);
    }

    public record TranscriptHealth(boolean present, boolean decrypts, Integer entries, String format, String errorCode) {}
    private record Loaded(ArrayNode entries, TranscriptHealth health) {}

    @GetMapping("/jobs/{jobId}/transcript")
    public ResponseEntity<?> transcript(@PathVariable String jobId, HttpServletRequest request) {
        if (Boolean.TRUE.equals(request.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore()).build();
        }
        Loaded loaded = load(jobId);
        String error = loaded.health().errorCode();
        if (error != null) {
            HttpStatus status = switch (error) {
                case "TRANSCRIPT_MISSING" -> HttpStatus.CONFLICT;
                case "TRANSCRIPT_STORE_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
                default -> HttpStatus.INTERNAL_SERVER_ERROR;
            };
            return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("error", error));
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(loaded.entries());
    }

    @GetMapping("/api/v2/jobs/{jobId}/transcript/health")
    public ResponseEntity<TranscriptHealth> health(@PathVariable String jobId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(load(jobId).health());
    }

    private Loaded load(String jobId) {
        var found = jobs.backupTranscript(jobId);
        if (found.isEmpty()) {
            logFailure("TRANSCRIPT_MISSING", jobId, "none", "MissingReference");
            return new Loaded(null, new TranscriptHealth(false, false, null, "UNKNOWN", "TRANSCRIPT_MISSING"));
        }
        var ref = found.get();
        var store = storeAccess.storeOrNull();
        if (store == null) {
            logFailure("TRANSCRIPT_STORE_UNAVAILABLE", jobId, ref.reference(), "StoreUnavailable");
            return new Loaded(null, new TranscriptHealth(true, false, null, "UNKNOWN", "TRANSCRIPT_STORE_UNAVAILABLE"));
        }
        try {
            var transcript = reader.read(store, new ArtefactRef(ref.reference()), ref.wrappedKey());
            return new Loaded(transcript.entries(), new TranscriptHealth(true, true, transcript.entries().size(), transcript.format(), null));
        } catch (JobTranscriptReader.ReadFailure e) {
            logFailure(e.code(), jobId, ref.reference(), e.getCause().getClass().getSimpleName());
            return new Loaded(null, new TranscriptHealth(!e.code().equals("TRANSCRIPT_MISSING"), e.decrypts(), null, "UNKNOWN", e.code()));
        } catch (RuntimeException e) {
            logFailure("TRANSCRIPT_DECRYPT_FAILED", jobId, ref.reference(), e.getClass().getSimpleName());
            return new Loaded(null, new TranscriptHealth(true, false, null, "UNKNOWN", "TRANSCRIPT_DECRYPT_FAILED"));
        }
    }

    private static void logFailure(String code, String jobId, String ref, String exceptionClass) {
        LOG.log(System.Logger.Level.WARNING, "{0} jobId={1} artefactRef={2} exceptionClass={3}", code, jobId, ref, exceptionClass);
    }
}
