package com.securityexpert.nexus.ui2.service.supportbundle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.ItemRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.StoredItem;
import com.securityexpert.nexus.ui2.platform.TarStream;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.systemlog.LogMasking;

/**
 * The one service path that decrypts support bundle items (design sections 5.3, 5.4 and 5.10.2). Order of operations:
 * the request and its stored items found, masking readiness checked for the sanitized mode, <b>audit written</b>, and
 * only then any ciphertext opened. An audit write that fails sends zero bytes. The archive is assembled on the fly (gzip
 * over tar) from the request's stored item artefacts and a freshly rendered {@code manifest.json}; nothing is buffered
 * whole except the small inner archive of {@code cp_gaia_extras}.
 */
public final class SupportBundleDownloadService {

    private static final Logger LOG = Logger.getLogger(SupportBundleDownloadService.class.getName());
    public static final int MIN_REASON_LENGTH = 8;
    static final long INNER_ARCHIVE_MAX_BYTES = 4L * 1024 * 1024;
    /** Binary archives are omitted in sanitized mode: rewriting bytes corrupts them (design 5.4 part 2). */
    private static final java.util.Set<String> TEXT_KINDS = java.util.Set.of("cp_gaia_extras", "asa_crashinfo",
            "asa_show_tech", "fgt_crashlog", "fgt_tac_report");

    /** Writes the whole archive; the caller owns the target and closes it. */
    public interface Body {
        void writeTo(OutputStream out) throws IOException;
    }

    public sealed interface Outcome {
        record Ready(String fileName, Body body) implements Outcome {
        }

        record ReasonTooShort() implements Outcome {
        }

        record NotFound() implements Outcome {
        }

        /** The request is deleted, expired, failed or holds no stored item. */
        record NothingToDownload(String state) implements Outcome {
        }

        record AuditRefused(String reason) implements Outcome {
        }

        record StoreUnavailable(String reason) implements Outcome {
        }

        record MaskingUnavailable() implements Outcome {
        }
    }

    private final ArtefactStore artefactStore;
    private final JooqSupportBundleStore store;
    private final DeviceRepository devices;
    private final TopologyNamePseudonymizer names;
    private final LogMasking logMasking;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    public SupportBundleDownloadService(ArtefactStore artefactStore, JooqSupportBundleStore store, DeviceRepository devices,
            TopologyNamePseudonymizer names, LogMasking logMasking) {
        this.artefactStore = artefactStore; this.store = store; this.devices = devices; this.names = names;
        this.logMasking = logMasking;
    }

    public Outcome prepare(String actor, UUID requestId, String reason, boolean sanitized) {
        if (reason == null || reason.strip().length() < MIN_REASON_LENGTH) return new Outcome.ReasonTooShort();
        if (artefactStore == null) {
            return new Outcome.StoreUnavailable("the service has no artefact store mounted (UI2_ARTEFACT_STORE_KEY_FILE / UI2_ARTEFACT_STORE_ROOT)");
        }
        Optional<RequestRow> request = store.find(requestId);
        if (request.isEmpty()) return new Outcome.NotFound();
        List<StoredItem> stored = store.storedItems(requestId);
        if (!List.of("COMPLETED", "PARTIAL").contains(request.get().state()) || stored.isEmpty()) {
            return new Outcome.NothingToDownload(request.get().state());
        }
        LogMasking.Session masking = null;
        if (sanitized) {
            try {
                masking = logMasking.session();
            } catch (LogMasking.MaskingUnavailableException unavailable) {
                return new Outcome.MaskingUnavailable();
            }
        }
        // Audit first: no byte is read before the retrieval is on record.
        try {
            store.recordDownload(requestId, actor, reason.strip(), sanitized ? "sanitized" : "raw");
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "support bundle retrieval audit could not be written; download refused", e);
            return new Outcome.AuditRefused(e.getClass().getSimpleName());
        }
        RequestRow req = request.get();
        List<ItemRow> items = store.items(requestId);
        String rootDir = "nexus-support-bundle-" + requestId.toString().substring(0, 8);
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(java.time.ZoneOffset.UTC)
                .format(req.finishedAt() == null ? req.requestedAt() : req.finishedAt());
        String fileName = "nexus-support-bundle-" + req.vendor() + "-" + requestId.toString().substring(0, 8) + "-" + stamp + ".tgz";
        byte[] manifest;
        try {
            manifest = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest(req, items, sanitized));
        } catch (IOException e) {
            return new Outcome.AuditRefused("MANIFEST_FAILED");
        }
        LogMasking.Session session = masking;
        long mtime = Instant.now().getEpochSecond();
        return new Outcome.Ready(fileName, out -> write(out, rootDir, manifest, stored, session, mtime));
    }

    /**
     * The archive of a vendor upload (raw mode, never sanitized: the vendor's tools need the bytes intact). No audit happens
     * here; the upload row and its retrieval rows are written by the caller before the body is first read. The body is
     * deterministic (fixed entry time, fixed manifest), so a first pass can measure its length and SHA-256 and a second pass
     * produces the same bytes. Empty when the request holds nothing to send for the chosen item kinds.
     */
    public Optional<UploadPlan> uploadPlan(UUID requestId, java.util.Collection<String> itemKinds) {
        if (artefactStore == null) return Optional.empty();
        Optional<RequestRow> request = store.find(requestId);
        if (request.isEmpty() || !List.of("COMPLETED", "PARTIAL").contains(request.get().state())) return Optional.empty();
        List<StoredItem> stored = store.storedItems(requestId).stream()
                .filter(i -> itemKinds == null || itemKinds.isEmpty() || itemKinds.contains(i.kind())).toList();
        if (stored.isEmpty()) return Optional.empty();
        RequestRow req = request.get();
        java.util.Set<UUID> selected = new java.util.HashSet<>();
        stored.forEach(i -> selected.add(i.itemId()));
        String rootDir = "nexus-support-bundle-" + requestId.toString().substring(0, 8);
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(java.time.ZoneOffset.UTC)
                .format(req.finishedAt() == null ? req.requestedAt() : req.finishedAt());
        String fileName = "nexus-support-bundle-" + req.vendor() + "-" + requestId.toString().substring(0, 8) + "-" + stamp + ".tgz";
        byte[] manifest;
        try {
            Map<String, Object> m = manifest(req, store.items(requestId), false);
            m.put("upload_selection", stored.stream().map(StoredItem::kind).distinct().toList());
            manifest = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(m);
        } catch (IOException e) {
            return Optional.empty();
        }
        long mtime = (req.finishedAt() == null ? req.requestedAt() : req.finishedAt()).getEpochSecond();
        return Optional.of(new UploadPlan(fileName, stored, selected, out -> write(out, rootDir, manifest, stored, null, mtime)));
    }

    /** What an upload sends: the file name, the stored items it carries and the deterministic archive writer. */
    public record UploadPlan(String fileName, List<StoredItem> items, java.util.Set<UUID> itemIds, Body body) {
    }

    private void write(OutputStream target, String rootDir, byte[] manifest, List<StoredItem> stored, LogMasking.Session masking,
            long mtime) throws IOException {
        GZIPOutputStream gz = new GZIPOutputStream(target, 64 * 1024);
        TarStream.Writer tar = new TarStream.Writer(gz);
        tar.entry(rootDir + "/manifest.json", manifest, mtime);
        for (StoredItem item : stored) {
            boolean text = TEXT_KINDS.contains(item.kind());
            if (masking != null && !text) continue; // binary item omitted in sanitized mode; the manifest says so
            try (InputStream in = artefactStore.retrieve(new ArtefactRef(item.recoveryVolumePath()), item.wrappedDataKey(), false)) {
                if ("cp_gaia_extras".equals(item.kind())) {
                    List<TarStream.Entry> inner;
                    try (GZIPInputStream unzip = new GZIPInputStream(in)) {
                        inner = TarStream.readAll(unzip, INNER_ARCHIVE_MAX_BYTES);
                    }
                    for (TarStream.Entry entry : inner) {
                        byte[] bytes = masking == null ? entry.bytes() : maskText(masking, entry.bytes());
                        tar.entry(rootDir + "/" + entry.name(), bytes, mtime);
                    }
                } else {
                    String entryName = item.archiveEntryName() == null ? "items/" + item.kind() + "/" + item.kind() + ".bin"
                            : item.archiveEntryName();
                    if (masking != null) {
                        // Sanitized mode: a session capture is masked line by line into a temporary file (never held whole in
                        // memory), because the tar entry needs its masked size first.
                        java.nio.file.Path spool = java.nio.file.Files.createTempFile("nexus-sb-masked-", ".txt");
                        try {
                            long maskedBytes;
                            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                                    var writer = java.nio.file.Files.newBufferedWriter(spool, StandardCharsets.UTF_8)) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    writer.write(masking.maskLine(line));
                                    writer.write('\n');
                                }
                            }
                            maskedBytes = java.nio.file.Files.size(spool);
                            tar.beginEntry(rootDir + "/" + entryName, maskedBytes, mtime);
                            try (InputStream masked = java.nio.file.Files.newInputStream(spool)) {
                                byte[] buffer = new byte[64 * 1024];
                                int n;
                                while ((n = masked.read(buffer)) > 0) tar.write(buffer, 0, n);
                            }
                            tar.endEntry();
                        } finally {
                            java.nio.file.Files.deleteIfExists(spool);
                        }
                        continue;
                    }
                    tar.beginEntry(rootDir + "/" + entryName, item.plaintextBytes(), mtime);
                    byte[] buffer = new byte[64 * 1024];
                    long copied = 0;
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        tar.write(buffer, 0, n);
                        copied += n;
                    }
                    if (copied != item.plaintextBytes()) throw new IOException("stored item size differs from its manifest");
                    tar.endEntry();
                }
            }
        }
        tar.finish();
        gz.finish();
    }

    private static byte[] maskText(LogMasking.Session session, byte[] raw) {
        String text = new String(raw, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            if (out.length() > 0 || !line.isEmpty()) {
                if (out.length() > 0) out.append('\n');
                out.append(session.maskLine(line));
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** design 5.3.3: opaque identifiers, the deterministic pseudonym, timestamps and per-item outcomes only. */
    Map<String, Object> manifest(RequestRow req, List<ItemRow> items, boolean sanitized) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema_version", 1);
        m.put("download_mode", sanitized ? "sanitized" : "raw");
        m.put("request_id", req.id().toString());
        m.put("job_id", req.jobId());
        m.put("device_id", req.deviceId());
        m.put("device_pseudonym", pseudonym(req.deviceId()));
        m.put("vendor", req.vendor());
        m.put("platform_role", req.platformRole());
        m.put("requested_at", req.requestedAt());
        m.put("started_at", req.startedAt());
        m.put("finished_at", req.finishedAt());
        m.put("expires_at", req.expiresAt());
        m.put("state", req.state());
        m.put("outcome", req.outcome());
        m.put("prechecks", precheck(req.precheckJson()));
        List<Map<String, Object>> list = new ArrayList<>();
        for (ItemRow i : items) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("ordinal", i.ordinal());
            e.put("kind", i.kind());
            e.put("vs_id", i.vsId());
            e.put("state", i.state());
            e.put("outcome_code", i.outcomeCode());
            e.put("archive_entry_name", i.archiveEntryName());
            e.put("artefact_id", i.artefactId());
            e.put("size_bytes", i.sizeBytes());
            e.put("sha256", i.sha256());
            e.put("redaction_ruleset", i.redactionRuleset());
            e.put("gate_ids_used", i.gateIdsUsed());
            e.put("started_at", i.startedAt());
            e.put("finished_at", i.finishedAt());
            if (sanitized && i.artefactId() != null && !TEXT_KINDS.contains(i.kind())) e.put("omitted_in_sanitized_mode", true);
            list.add(e);
        }
        m.put("items", list);
        return m;
    }

    private Object precheck(String precheckJson) {
        try {
            return json.readTree(precheckJson == null ? "{}" : precheckJson);
        } catch (IOException e) {
            return Map.of();
        }
    }

    /** A pseudonym, or UNKNOWN: never a raw host name, never a fallback to one. */
    public String pseudonym(String deviceId) {
        try {
            String raw = devices.findSummary(deviceId).flatMap(DeviceSummaryRecord::observedHostname).orElse(null);
            if (raw == null || raw.isBlank()) return "UNKNOWN";
            String masked = names.maskDeviceName(raw, null, deviceId);
            return masked == null || masked.isBlank() ? "UNKNOWN" : masked;
        } catch (RuntimeException e) {
            return "UNKNOWN";
        }
    }
}
