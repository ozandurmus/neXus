package com.securityexpert.nexus.ui2.service.packetanalysis;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import com.securityexpert.nexus.ui2.packetanalysis.telemetry.TelemetryParser;
import com.securityexpert.nexus.ui2.platform.PacketAnalysisPaths;

/**
 * The service's side of the analysis volume (decision record 2026-10-15 §5): writes uploaded captures, streamed to
 * disk while their SHA-256 is computed -- never buffered whole in memory -- and refuses a file larger than the
 * per-file limit, a file that is neither pcap nor pcapng by its first bytes (the extension is never consulted), and
 * any upload that would take the store past its cap. Captures live only on this volume: never in a browser
 * artefact, a log or a support bundle.
 *
 * <p>Host telemetry text files (sar, vmstat, pidstat, tc/softnet snapshots, syslog) follow the same path with their own
 * per-file limit (200 MB by default) and live in the case's {@code telemetry} directory: they count against the same store
 * cap, are removed with the case and by the same retention pruner, and are accepted only when they are text (no NUL byte
 * in the first 8 KB). What the content is -- or that it is not recognised -- is decided by the telemetry sniffer, not by
 * the file name.</p>
 */
public final class PacketCaptureStore {

    /** A refusal with a closed code; never carries a file name, a path or file content. */
    public static final class Refusal extends Exception {
        private final String code;

        public Refusal(String code) {
            super(code, null, false, false);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static final String TOO_LARGE = "CAPTURE_TOO_LARGE";
    public static final String STORE_FULL = "STORE_FULL";
    public static final String NOT_A_CAPTURE = "NOT_A_CAPTURE";
    public static final String NOT_A_TEXT_FILE = "NOT_A_TEXT_FILE";
    public static final long DEFAULT_MAX_TELEMETRY_BYTES = 200L * 1024L * 1024L;

    public record Stored(String captureId, String storageKey, String format, long sizeBytes, String sha256) {
    }

    /** A telemetry file as stored: identity plus what the sniffer detected ({@code unknown} comes with a closed reason). */
    public record StoredTelemetry(String telemetryId, String storageKey, long sizeBytes, String sha256, String detectedFormat,
            String recognitionNote) {
    }

    private static final int HEADER_BYTES = 24;
    private static final int TEXT_SNIFF_BYTES = 8192;

    private final Path root;
    private final long maxFileBytes;
    private final long maxStoreBytes;
    private final long maxTelemetryBytes;
    private long reservedBytes;

    public PacketCaptureStore(Path root, long maxFileBytes, long maxStoreBytes) {
        this(root, maxFileBytes, maxStoreBytes, Math.min(DEFAULT_MAX_TELEMETRY_BYTES, maxFileBytes));
    }

    public PacketCaptureStore(Path root, long maxFileBytes, long maxStoreBytes, long maxTelemetryBytes) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        if (maxFileBytes <= 0 || maxStoreBytes <= 0 || maxTelemetryBytes <= 0) throw new IllegalArgumentException("PACKET_ANALYSIS_LIMITS_INVALID");
        this.maxFileBytes = maxFileBytes;
        this.maxStoreBytes = maxStoreBytes;
        this.maxTelemetryBytes = maxTelemetryBytes;
    }

    public Path root() {
        return root;
    }

    public long maxFileBytes() {
        return maxFileBytes;
    }

    public long maxStoreBytes() {
        return maxStoreBytes;
    }

    public long maxTelemetryBytes() {
        return maxTelemetryBytes;
    }

    /** Bytes on the analysis volume under this store's root (captures, run output, engine files). */
    public long usedBytes() {
        Path cases = root.resolve("cases");
        if (!Files.isDirectory(cases)) return 0L;
        try (Stream<Path> files = Files.walk(cases)) {
            // An upload in progress is covered by its reservation, not counted twice.
            return files.filter(Files::isRegularFile).filter(path -> !path.getFileName().toString().startsWith(".partial-"))
                    .mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException gone) {
                    return 0L;
                }
            }).sum();
        } catch (IOException | java.io.UncheckedIOException unreadable) {
            // Fail closed: an unreadable store counts as full rather than as empty.
            return maxStoreBytes;
        }
    }

    /**
     * Stream one capture into the case directory. {@code declaredBytes} (the request's Content-Length, or -1) sizes
     * the reservation that keeps concurrent uploads from passing the cap together.
     */
    public Stored write(String caseId, InputStream in, long declaredBytes) throws Refusal, IOException {
        if (declaredBytes > maxFileBytes + 1024L * 1024L) throw new Refusal(TOO_LARGE);
        long reservation = declaredBytes > 0 ? Math.min(declaredBytes, maxFileBytes) : maxFileBytes;
        reserve(reservation);
        Path partial = null;
        try {
            byte[] header = in.readNBytes(HEADER_BYTES);
            String format = formatOf(header);
            if (format == null) throw new Refusal(NOT_A_CAPTURE);
            String captureId = UUID.randomUUID().toString();
            String storageKey = captureId + "." + format;
            Path target = PacketAnalysisPaths.captureFile(root, caseId, storageKey);
            Files.createDirectories(target.getParent());
            partial = target.resolveSibling(".partial-" + captureId);
            MessageDigest digest = sha256();
            long written = header.length;
            byte[] buffer = new byte[1 << 16];
            try (OutputStream out = Files.newOutputStream(partial)) {
                out.write(header);
                digest.update(header);
                int read;
                while ((read = in.read(buffer)) != -1) {
                    written += read;
                    if (written > maxFileBytes) throw new Refusal(TOO_LARGE);
                    if (written > reservation) {
                        // The declared length understated the file: grow the reservation or refuse.
                        long grown = Math.min(maxFileBytes, written + (1L << 26));
                        reserve(grown - reservation);
                        reservation = grown;
                    }
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                }
            }
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            partial = null;
            return new Stored(captureId, storageKey, format, written, HexFormat.of().formatHex(digest.digest()));
        } finally {
            release(reservation);
            if (partial != null) Files.deleteIfExists(partial);
        }
    }

    /**
     * Stream one host telemetry text file into the case's telemetry directory (same reservation and cap as a capture,
     * the telemetry file limit per file), then sniff its head so the attachment carries what it was recognised as.
     */
    public StoredTelemetry writeTelemetry(String caseId, InputStream in, long declaredBytes) throws Refusal, IOException {
        if (declaredBytes > maxTelemetryBytes + 1024L * 1024L) throw new Refusal(TOO_LARGE);
        long reservation = declaredBytes > 0 ? Math.min(declaredBytes, maxTelemetryBytes) : maxTelemetryBytes;
        reserve(reservation);
        Path partial = null;
        try {
            byte[] head = in.readNBytes(TEXT_SNIFF_BYTES);
            if (head.length == 0) throw new Refusal(NOT_A_TEXT_FILE);
            for (byte b : head) {
                if (b == 0) throw new Refusal(NOT_A_TEXT_FILE);
            }
            String telemetryId = UUID.randomUUID().toString();
            String storageKey = telemetryId + ".txt";
            Path target = PacketAnalysisPaths.telemetryFile(root, caseId, storageKey);
            Files.createDirectories(target.getParent());
            partial = target.resolveSibling(".partial-" + telemetryId);
            MessageDigest digest = sha256();
            long written = head.length;
            if (written > maxTelemetryBytes) throw new Refusal(TOO_LARGE);
            byte[] buffer = new byte[1 << 16];
            try (OutputStream out = Files.newOutputStream(partial)) {
                out.write(head);
                digest.update(head);
                int read;
                while ((read = in.read(buffer)) != -1) {
                    written += read;
                    if (written > maxTelemetryBytes) throw new Refusal(TOO_LARGE);
                    if (written > reservation) {
                        long grown = Math.min(maxTelemetryBytes, written + (1L << 24));
                        reserve(grown - reservation);
                        reservation = grown;
                    }
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                }
            }
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            partial = null;
            TelemetryParser.Detection found;
            try (InputStream stored = Files.newInputStream(target)) {
                found = TelemetryParser.detect(stored);
            }
            return new StoredTelemetry(telemetryId, storageKey, written, HexFormat.of().formatHex(digest.digest()),
                    found.format().id(), found.reason());
        } finally {
            release(reservation);
            if (partial != null) Files.deleteIfExists(partial);
        }
    }

    public InputStream openTelemetry(String caseId, String storageKey) throws IOException {
        return Files.newInputStream(PacketAnalysisPaths.telemetryFile(root, caseId, storageKey));
    }

    public void deleteTelemetry(String caseId, String storageKey) throws IOException {
        Files.deleteIfExists(PacketAnalysisPaths.telemetryFile(root, caseId, storageKey));
    }

    public InputStream open(String caseId, String storageKey) throws IOException {
        return Files.newInputStream(PacketAnalysisPaths.captureFile(root, caseId, storageKey));
    }

    public void deleteCapture(String caseId, String storageKey) throws IOException {
        Files.deleteIfExists(PacketAnalysisPaths.captureFile(root, caseId, storageKey));
    }

    public Path reportFile(String caseId, String jobId) {
        return PacketAnalysisPaths.reportFile(root, caseId, jobId);
    }

    /** Removes the case directory and everything in it; an already-absent directory is a success. */
    public void deleteCase(String caseId) throws IOException {
        deleteTree(PacketAnalysisPaths.caseDir(root, caseId));
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    /** {@code pcap} or {@code pcapng} by the file's own magic numbers, or {@code null}. */
    static String formatOf(byte[] header) {
        if (header.length < HEADER_BYTES) return null;
        int magic = ((header[0] & 0xff) << 24) | ((header[1] & 0xff) << 16) | ((header[2] & 0xff) << 8) | (header[3] & 0xff);
        switch (magic) {
            case 0xa1b2c3d4, 0xd4c3b2a1, 0xa1b23c4d, 0x4d3cb2a1 -> {
                return "pcap";
            }
            case 0x0a0d0d0a -> {
                int byteOrder = ((header[8] & 0xff) << 24) | ((header[9] & 0xff) << 16) | ((header[10] & 0xff) << 8)
                        | (header[11] & 0xff);
                return byteOrder == 0x1a2b3c4d || byteOrder == 0x4d3c2b1a ? "pcapng" : null;
            }
            default -> {
                return null;
            }
        }
    }

    private synchronized void reserve(long bytes) throws Refusal {
        if (usedBytes() + reservedBytes + bytes > maxStoreBytes) throw new Refusal(STORE_FULL);
        reservedBytes += bytes;
    }

    private synchronized void release(long bytes) {
        reservedBytes = Math.max(0, reservedBytes - bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
