package com.securityexpert.nexus.ui2.worker.backup.pan;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiStreamOutcome;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.worker.backup.BackupResult;
import com.securityexpert.nexus.ui2.worker.backup.diff.SemanticDeviationEngine;

/**
 * Backup executor for Palo Alto Networks firewalls (PAN-OS 11.x) and Panorama.
 * 
 * <p>Streams the complete {@code device-state} bundle directly from the XML API
 * into encrypted vault storage with zero local disk footprint on the target firewall.
 * Concurrently extracts the {@code running-config.xml} for AST semantic deviation analysis.</p>
 */
public final class PaloAltoBackupExecutor {

    private static final Duration EXPORT_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration CONFIG_TIMEOUT = Duration.ofMinutes(3);

    private final DeviceTransport transport;
    private final ArtefactStore artefactStore;
    private final SemanticDeviationEngine deviationEngine;

    public PaloAltoBackupExecutor(DeviceTransport transport, ArtefactStore artefactStore) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.artefactStore = Objects.requireNonNull(artefactStore, "artefactStore");
        this.deviationEngine = new SemanticDeviationEngine();
    }

    public record PanBackupResult(
            boolean success,
            String artefactId,
            ArtefactStore.ArtefactMetadata metadata,
            SemanticDeviationEngine.DeviationOutcome deviationOutcome,
            String errorMessage
    ) {}

    public PanBackupResult executeBackup(ApiTarget target, String apiKey, String deviceId, String jobId, String previousConfigXml) {
        // 1. Fetch companion running-config XML for semantic AST deviation diffing
        XmlApiSpec configSpec = new XmlApiSpec(
                "POST",
                "config",
                "show",
                null,
                Map.of(
                        "type", "config",
                        "action", "show"
                ),
                Map.of("X-PAN-KEY", apiKey)
        );

        XmlApiResult configResult = transport.xmlApiCall(target, configSpec, CONFIG_TIMEOUT);
        String currentConfigXml = "";
        if (configResult instanceof XmlApiResult.Completed completed && completed.httpStatus() == 200) {
            String body = completed.body();
            if (body != null && !body.contains("status=\"error\"") && !body.contains("status='error'")) {
                currentConfigXml = body;
            }
        }

        SemanticDeviationEngine.DeviationOutcome deviationOutcome =
                deviationEngine.evaluate("palo_alto", previousConfigXml, currentConfigXml);

        // 2. Stream device-state bundle directly into envelope-encrypted storage
        ArtefactStore.ArtefactHandle handle;
        try {
            handle = artefactStore.open(deviceId, jobId, "palo_alto", false);
        } catch (IOException e) {
            return new PanBackupResult(false, null, null, deviationOutcome, "Failed to open artefact store");
        }

        XmlApiSpec exportSpec = new XmlApiSpec(
                "POST",
                "export",
                "device-state",
                null,
                Map.of(
                        "type", "export",
                        "category", "device-state"
                ),
                Map.of("X-PAN-KEY", apiKey)
        );

        try {
            XmlApiStreamOutcome<Long> streamOutcome = transport.xmlApiCallStreaming(
                    target,
                    exportSpec,
                    EXPORT_TIMEOUT,
                    is -> copyStream(is, handle.sink())
            );

            if (!(streamOutcome instanceof XmlApiStreamOutcome.Completed<Long>)) {
                handle.close();
                return new PanBackupResult(false, null, null, deviationOutcome, "Streaming export failed: transport stream incomplete");
            }

            ArtefactStore.ArtefactMetadata metadata = handle.finish();
            return new PanBackupResult(true, metadata.ref().value(), metadata, deviationOutcome, null);
        } catch (Exception e) {
            try {
                handle.close();
            } catch (IOException ignored) {
            }
            return new PanBackupResult(false, null, null, deviationOutcome, "Device state export failed: stream transfer error");
        }
    }

    private static final long MAX_PAN_DEVICE_STATE_BYTES = 10L * 1024L * 1024L * 1024L; // 10 GB limit

    private static long copyStream(InputStream from, OutputStream to) throws IOException {
        byte[] buffer = new byte[65536];
        long total = 0;
        int read;
        while ((read = from.read(buffer)) != -1) {
            total += read;
            if (total > MAX_PAN_DEVICE_STATE_BYTES) {
                throw new IOException("Stream exceeded maximum allowable size (" + MAX_PAN_DEVICE_STATE_BYTES + " bytes)");
            }
            to.write(buffer, 0, read);
        }
        to.flush();
        return total;
    }
}
