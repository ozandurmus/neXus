package com.securityexpert.nexus.ui2.service.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

import jakarta.servlet.http.HttpServletRequest;

@RestController
public final class JobTranscriptController {
    private final JobRecordDao jobs;
    private final ArtefactStoreAccess storeAccess;
    private final ObjectMapper json;

    public JobTranscriptController(JobRecordDao jobs, ArtefactStoreAccess storeAccess, ObjectMapper json) {
        this.jobs = jobs;
        this.storeAccess = storeAccess;
        this.json = json;
    }

    @GetMapping("/jobs/{jobId}/transcript")
    public ResponseEntity<StreamingResponseBody> transcript(@PathVariable String jobId, HttpServletRequest request) {
        if (Boolean.TRUE.equals(request.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (storeAccess.storeOrNull() == null) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        Optional<JobRecordDao.BackupTranscriptRef> found = jobs.backupTranscript(jobId);
        if (found.isEmpty()) return ResponseEntity.notFound().build();
        var ref = found.get();
        StreamingResponseBody body = output -> {
            try (var input = storeAccess.storeOrNull().retrieve(new ArtefactRef(ref.reference()), ref.wrappedKey(), true);
                    var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                output.write('[');
                String line;
                boolean first = true;
                while ((line = reader.readLine()) != null) {
                    if (!first) output.write(',');
                    JsonNode entry = json.readTree(line);
                    json.writeValue(output, entry);
                    first = false;
                }
                output.write(']');
            }
        };
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(body);
    }
}
