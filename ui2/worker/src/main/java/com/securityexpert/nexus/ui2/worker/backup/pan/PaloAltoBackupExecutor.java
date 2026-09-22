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
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.persistence.artefact.content.TarWriter;
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
    private final com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver credentialResolver;
    private static final java.util.regex.Pattern API_KEY = java.util.regex.Pattern.compile("<key>([^<]+)</key>");
    private static final java.util.regex.Pattern XML_MSG = java.util.regex.Pattern.compile("<msg>([^<]{0,160})");
    private static final java.util.regex.Pattern XML_CODE = java.util.regex.Pattern.compile("code\\s*=\\s*['\"]([0-9]{1,4})['\"]");

    /** Fail-closed: without a credential resolver no key can be generated and every backup is refused. */
    public PaloAltoBackupExecutor(DeviceTransport transport, ArtefactStore artefactStore) {
        this(transport, artefactStore, null);
    }

    public PaloAltoBackupExecutor(DeviceTransport transport, ArtefactStore artefactStore,
            com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver credentialResolver) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.artefactStore = Objects.requireNonNull(artefactStore, "artefactStore");
        this.deviationEngine = new SemanticDeviationEngine();
        this.credentialResolver = credentialResolver;
    }

    public record PanBackupResult(
            boolean success,
            String artefactId,
            ArtefactStore.ArtefactMetadata metadata,
            SemanticDeviationEngine.DeviationOutcome deviationOutcome,
            String errorMessage
    ) {}

    /**
     * @param credentialRef the device's credential reference (Admin > Credentials), resolved here
     *     and exchanged for a session API key through {@code type=keygen} -- measured live
     *     (2026-09-22): the reference id itself had been sent as X-PAN-KEY, so the device answered
     *     a 108-byte XML error which was then stored as a "V1 backup".
     */
    public PanBackupResult executeBackup(ApiTarget target, String credentialRef, String deviceId, String jobId, String previousConfigXml) {
        SemanticDeviationEngine.DeviationOutcome noDeviation = deviationEngine.evaluate("palo_alto", previousConfigXml, "");
        if (credentialResolver == null) {
            return new PanBackupResult(false, null, null, noDeviation, "PAN credential resolver not configured; refusing (fail-closed)");
        }
        com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial credential;
        try {
            credential = credentialResolver.resolve(credentialRef);
        } catch (RuntimeException unresolvable) {
            return new PanBackupResult(false, null, null, noDeviation, "credential_unresolvable: " + unresolvable.getMessage());
        }
        XmlApiResult keyResult = transport.xmlApiCall(target,
                new XmlApiSpec("POST", "keygen", "", "",
                        Map.of("user", credential.username(), "password", new String(credential.password())),
                        Map.of()),
                CONFIG_TIMEOUT);
        String apiKey = null;
        if (keyResult instanceof XmlApiResult.Completed keyCompleted && keyCompleted.httpStatus() == 200 && keyCompleted.body() != null) {
            java.util.regex.Matcher m = API_KEY.matcher(keyCompleted.body());
            if (m.find()) {
                apiKey = m.group(1);
            }
        }
        if (apiKey == null) {
            String why = keyResult instanceof XmlApiResult.Failed failed ? failed.reason()
                    : keyResult instanceof XmlApiResult.Completed c ? describeXmlError(c.body(), "HTTP " + c.httpStatus()) : "no key";
            return new PanBackupResult(false, null, null, noDeviation, "palo alto key generation failed: " + why);
        }

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

        // 2. Device-state export, validated, held in a bounded buffer: the bundle written below needs
        //    each member's size up front (tar), and a firewall's device-state is a few MB (measured
        //    0.6-2.9 MB live) -- MAX_BUFFERED_DEVICE_STATE_BYTES refuses anything absurd.
        java.io.ByteArrayOutputStream deviceState = new java.io.ByteArrayOutputStream(4 << 20);
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
        XmlApiStreamOutcome<StreamedExport> streamOutcome;
        try {
            streamOutcome = transport.xmlApiCallStreaming(target, exportSpec, EXPORT_TIMEOUT,
                    is -> copyValidatedExport(is, new BoundedOutputStream(deviceState, MAX_BUFFERED_DEVICE_STATE_BYTES)));
        } catch (Exception e) {
            return new PanBackupResult(false, null, null, deviationOutcome, "Device state export failed: stream transfer error");
        }
        if (!(streamOutcome instanceof XmlApiStreamOutcome.Completed<StreamedExport> completed)) {
            String why = streamOutcome instanceof XmlApiStreamOutcome.Failed<StreamedExport> failed ? failed.reason() : "transport stream incomplete";
            return new PanBackupResult(false, null, null, deviationOutcome, "Streaming export failed: " + why);
        }
        StreamedExport export = completed.handled();
        // A device-state export is a gzip archive; anything else (an XML <response status="error">
        // for a wrong key, a role without export rights, an unknown category...) is a refusal and
        // is never stored as a backup (measured live, 2026-09-22: 108-byte XML errors stored as V1).
        if (completed.httpStatus() != 200 || export.xmlErrorSnippet() != null || !export.gzip() || export.bytes() <= 0) {
            String why = export.xmlErrorSnippet() != null
                    ? describeXmlError(export.xmlErrorSnippet(), "HTTP " + completed.httpStatus())
                    : "HTTP " + completed.httpStatus() + ", " + export.bytes() + " bytes, gzip=" + export.gzip();
            return new PanBackupResult(false, null, null, deviationOutcome, "device-state export refused by the device: " + why);
        }

        // 3. The set-format running configuration over the CLI (Product Owner P0, 2026-09-22: "PAN'daki
        //    show kısmını da bu backupun içine ekleyelim"). Part of the bundle, so its absence fails the
        //    run with its reason -- a backup that silently lacks half its content is not a backup.
        String sshHost = target.baseUrl().replaceFirst("^https?://", "").replaceFirst("/.*$", "").replaceFirst(":\\d+$", "");
        ConnectionTarget sshTarget = new ConnectionTarget(target.endpointId(), sshHost, SSH_PORT);
        String trustRuleRef = com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver
                .scopeRef(sshHost, SSH_PORT);
        PanSetConfigReader.Outcome setConfig = new PanSetConfigReader(transport).read(sshTarget, credentialRef, trustRuleRef);
        if (!(setConfig instanceof PanSetConfigReader.Outcome.Read read)) {
            String why = ((PanSetConfigReader.Outcome.Unavailable) setConfig).reason();
            return new PanBackupResult(false, null, null, deviationOutcome, "pan_set_config_unavailable: " + why);
        }

        // 4. One bundle artefact: a gzip tar holding both members, so the download is one file, the
        //    listing shows both and a compare tells which of the two changed.
        ArtefactStore.ArtefactHandle handle;
        try {
            handle = artefactStore.open(deviceId, jobId, "palo_alto", false);
        } catch (IOException e) {
            return new PanBackupResult(false, null, null, deviationOutcome, "Failed to open artefact store");
        }
        try {
            try (TarWriter tar = new TarWriter(new java.util.zip.GZIPOutputStream(handle.sink()))) {
                tar.file(BUNDLE_DEVICE_STATE, deviceState.toByteArray());
                tar.file(BUNDLE_RUNNING_CONFIG_SET, read.setFormatText().getBytes(StandardCharsets.UTF_8));
            }
            ArtefactStore.ArtefactMetadata metadata = handle.finish();
            return new PanBackupResult(true, metadata.ref().value(), metadata, deviationOutcome, null);
        } catch (Exception e) {
            try {
                handle.close();
            } catch (IOException ignored) {
            }
            return new PanBackupResult(false, null, null, deviationOutcome, "bundle could not be stored: " + e.getClass().getSimpleName());
        }
    }

    public static final String BUNDLE_DEVICE_STATE = "device-state.tgz";
    public static final String BUNDLE_RUNNING_CONFIG_SET = "running-config.set";
    private static final int SSH_PORT = 22;
    private static final long MAX_BUFFERED_DEVICE_STATE_BYTES = 256L * 1024 * 1024;

    /** Refuses, mid-stream, a body larger than the bundle is willing to hold in memory. */
    private static final class BoundedOutputStream extends java.io.FilterOutputStream {
        private final long limit;
        private long written;

        BoundedOutputStream(OutputStream out, long limit) {
            super(out);
            this.limit = limit;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            written += len;
            if (written > limit) {
                throw new IOException("device-state export exceeds " + limit + " bytes");
            }
            out.write(b, off, len);
        }
    }

    private static final long MAX_PAN_DEVICE_STATE_BYTES = 10L * 1024L * 1024L * 1024L; // 10 GB limit

    /** What the export stream turned out to be: a gzip archive of {@code bytes}, or an XML body
     * (kept only as a short, message-only snippet for the failure reason -- never stored). */
    record StreamedExport(long bytes, boolean gzip, String xmlErrorSnippet) {
    }

    static StreamedExport copyValidatedExport(InputStream from, OutputStream to) throws IOException {
        java.io.PushbackInputStream in = new java.io.PushbackInputStream(from, 2);
        int b1 = in.read();
        int b2 = b1 == -1 ? -1 : in.read();
        if (b1 == -1) {
            return new StreamedExport(0, false, null);
        }
        if (b1 == 0x1f && b2 == 0x8b) {
            in.unread(b2);
            in.unread(b1);
            return new StreamedExport(copyStream(in, to), true, null);
        }
        if (b2 != -1) {
            in.unread(b2);
        }
        in.unread(b1);
        byte[] head = in.readNBytes(4096);
        String text = new String(head, java.nio.charset.StandardCharsets.UTF_8);
        // Drain the rest so the connection closes cleanly; nothing of it is kept.
        long rest = head.length;
        byte[] buffer = new byte[65536];
        int read;
        while ((read = in.read(buffer)) != -1) {
            rest += read;
        }
        return new StreamedExport(rest, false, text.trim().startsWith("<") ? text : "non-gzip, non-xml body");
    }

    /** Status code and {@code <msg>} text only -- never the whole body. */
    static String describeXmlError(String body, String fallback) {
        if (body == null) {
            return fallback;
        }
        java.util.regex.Matcher code = XML_CODE.matcher(body);
        java.util.regex.Matcher msg = XML_MSG.matcher(body);
        String c = code.find() ? "code " + code.group(1) : fallback;
        return msg.find() ? c + ": " + msg.group(1).trim() : c;
    }

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
