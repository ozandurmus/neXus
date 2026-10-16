package com.securityexpert.nexus.ui2.packetanalysis.fortinet;

import com.securityexpert.nexus.ui2.packetanalysis.ingest.Cancellation;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.CaptureIngest;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.CaptureIngest.Progress;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.IngestCancelledException;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.IngestOptions;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.TsharkRunner;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyInt;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyJson;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyMath;
import com.securityexpert.nexus.ui2.packetanalysis.store.Db;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import com.securityexpert.nexus.ui2.packetanalysis.topology.Topology;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reference {@code fortinet.py}: FortiOS verbose-6 text-to-pcap converter ({@code absolute_ns},
 * {@code cooked_link_type}, {@code convert_text}, {@code import_text}). Only explicitly formatted hex bytes are
 * copied; tshark dissects the resulting capture.
 */
public final class Fortinet {
    private static final String STAMP = "(?:\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})?|\\d+(?:\\.\\d{1,9})?)";
    private static final Pattern HEADER = Pattern.compile("^\\s*(" + STAMP + ")\\s+(\\S+)\\s+(?:in|out|--)(?:\\s+|$)");
    private static final Pattern HEX = Pattern.compile("^\\s*0x([0-9a-fA-F]{4,8})\\s+(.*)$");
    private static final Pattern ANSI = Pattern.compile("\\x1b\\[[0-9;]*[A-Za-z]");
    private static final Pattern FRACTION = Pattern.compile("\\.(\\d+)");
    private static final Pattern YEAR_PREFIX = Pattern.compile("^\\d{4}-");
    private static final Pattern PROTO_TYPE = Pattern.compile("protocol type:\\s*(0x[0-9a-fA-F]+|[0-9]+)");
    private static final Pattern PACKET_TYPE = Pattern.compile("packet type:\\s*(0x[0-9a-fA-F]+|[0-9]+)");
    private static final Pattern HARDWARE_TYPE = Pattern.compile("address type:\\s*(0x[0-9a-fA-F]+|[0-9]+)");
    private static final Pattern SAFE_NAME = Pattern.compile("[^A-Za-z0-9_.-]");
    private static final Pattern TWO_SPACES = Pattern.compile("\\s{2,}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern HEX_WORD = Pattern.compile("(?:[0-9a-fA-F]{2}){1,2}");
    private static final Pattern ZONE_SUFFIX = Pattern.compile("(?:[Zz]|[+-]\\d{2}:?\\d{2})$");
    private static final MathContext DECIMAL_CTX = new MathContext(28, RoundingMode.HALF_EVEN);
    private static final BigDecimal NANO_SCALE = BigDecimal.valueOf(1_000_000_000L);

    private Fortinet() {
    }

    /** {@code absolute_ns(value, require_zone=False)}. */
    public static long absoluteNs(String value, boolean requireZone) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Invalid ISO-8601 timestamp");
        }
        Matcher fraction = FRACTION.matcher(value);
        long nano = 0;
        String whole = value;
        if (fraction.find()) {
            String digits = (fraction.group(1) + "000000000").substring(0, 9);
            nano = Long.parseLong(digits);
            whole = value.substring(0, fraction.start()) + value.substring(fraction.end());
        }
        String iso = whole.replace("Z", "+00:00").replace("z", "+00:00");
        if (iso.length() > 10 && iso.charAt(10) == ' ') {
            iso = iso.substring(0, 10) + "T" + iso.substring(11);
        }
        boolean hasZone = ZONE_SUFFIX.matcher(iso).find();
        if (requireZone && !hasZone) {
            throw new IllegalArgumentException("Relative start time must include Z or a numeric timezone offset");
        }
        try {
            long epochSec = hasZone
                    ? OffsetDateTime.parse(iso).toEpochSecond()
                    : LocalDateTime.parse(iso).toEpochSecond(ZoneOffset.UTC);
            return Math.addExact(Math.multiplyExact(epochSec, 1_000_000_000L), nano);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid ISO-8601 timestamp: " + value, e);
        }
    }

    public static long absoluteNs(String value) {
        return absoluteNs(value, false);
    }

    private static long parseAutoInt(String s) {
        String trimmed = s.strip();
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return Long.parseLong(trimmed.substring(2), 16);
        }
        return Long.parseLong(trimmed, 10);
    }

    /** {@code cooked_link_type(frame, description, tshark=None)}. */
    public static Integer cookedLinkType(byte[] frame, String description, String tshark) {
        Matcher proto = PROTO_TYPE.matcher(description);
        if (!proto.find()) {
            return null;
        }
        Matcher packetType = PACKET_TYPE.matcher(description);
        Long expectedPacketType = packetType.find() ? parseAutoInt(packetType.group(1)) : null;
        Matcher hardware = HARDWARE_TYPE.matcher(description);
        Long expectedHardware = hardware.find() ? parseAutoInt(hardware.group(1)) : null;
        long expected = parseAutoInt(proto.group(1));
        String binary = TsharkRunner.find(tshark);
        List<Integer> candidates = new ArrayList<>();
        Path directory;
        try {
            directory = Files.createTempDirectory("fortinet-link-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            Path probe = directory.resolve("probe.pcap");
            for (int link : new int[] {1, 113, 276}) {
                ByteBuffer buf = ByteBuffer.allocate(24 + 16 + frame.length).order(ByteOrder.LITTLE_ENDIAN);
                buf.putInt(0xA1B2C3D4);
                buf.putShort((short) 2);
                buf.putShort((short) 4);
                buf.putInt(0);
                buf.putInt(0);
                buf.putInt(16777216);
                buf.putInt(link);
                buf.putInt(1700000000);
                buf.putInt(0);
                buf.putInt(frame.length);
                buf.putInt(frame.length);
                buf.put(frame);
                Files.write(probe, buf.array());
                List<String> cmd = List.of(
                        binary, "-n", "-r", probe.toString(),
                        "-T", "fields", "-E", "occurrence=f",
                        "-e", "eth.type", "-e", "sll.etype", "-e", "sll.pkttype", "-e", "sll.hatype");
                String stdout;
                try (TsharkRunner runner = TsharkRunner.start(cmd, Cancellation.NONE, Duration.ofSeconds(30))) {
                    stdout = readAll(runner.stdout());
                    int code = runner.waitFor();
                    if (runner.timedOut() || code != 0) {
                        throw new IllegalStateException("tshark probe failed with exit code " + code);
                    }
                }
                String stripped = stripTrailingCrLf(stdout);
                String[] parts = stripped.split("\t", -1);
                String[] decoded = new String[4];
                for (int i = 0; i < 4; i++) {
                    decoded[i] = i < parts.length ? parts[i] : "";
                }
                String field = link == 1 ? decoded[0] : decoded[1];
                if (field.isEmpty() || parseAutoInt(field) != expected) {
                    continue;
                }
                if (link != 1) {
                    if (expectedPacketType != null
                            && (decoded[2].isEmpty() || parseAutoInt(decoded[2]) != expectedPacketType)) {
                        continue;
                    }
                    if (expectedHardware != null
                            && (decoded[3].isEmpty() || parseAutoInt(decoded[3]) != expectedHardware)) {
                        continue;
                    }
                }
                candidates.add(link);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteTree(directory);
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static String readAll(Reader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) >= 0) {
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private static String stripTrailingCrLf(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\r' || s.charAt(end - 1) == '\n')) {
            end--;
        }
        return s.substring(0, end);
    }

    private static final class PacketAccum {
        final String iface;
        final long timeNs;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final boolean relative;
        boolean bad;
        int rowBytes;
        String cooked;

        PacketAccum(String iface, long timeNs, boolean relative) {
            this.iface = iface;
            this.timeNs = timeNs;
            this.relative = relative;
        }
    }

    private static final class FileOut {
        final String path;
        final OutputStream handle;
        long packets;
        boolean relative;
        final int linkType;
        boolean cookedChecked;

        FileOut(String path, OutputStream handle, int linkType) {
            this.path = path;
            this.handle = handle;
            this.linkType = linkType;
        }
    }

    private static byte[] readHex(String text) {
        String stripped = text.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        String column = TWO_SPACES.split(stripped, 2)[0].strip();
        if (column.isEmpty()) {
            return null;
        }
        String[] tokens = WHITESPACE.split(column);
        if (tokens.length == 0) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (String word : tokens) {
            if (!HEX_WORD.matcher(word).matches()) {
                return null;
            }
            joined.append(word);
        }
        return HexFormat.of().parseHex(joined.toString());
    }

    private static int leadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return i;
    }

    private static void inc(Map<String, Long> map, String key) {
        map.merge(key, 1L, Long::sum);
    }

    /** {@code convert_text(source, output_dir, start_time=None, cancel=None, progress=None, tshark=None)}. */
    public static Map<String, Object> convertText(Path source, Path outputDir, String startTime, Cancellation cancel,
            Progress progress, String tshark) {
        Cancellation c = cancel == null ? Cancellation.NONE : cancel;
        Progress p = progress == null ? Progress.NONE : progress;
        String sourceName = source.getFileName().toString();
        p.update(Map.of("state", "converting Fortinet text", "file", sourceName, "frames", 0L));
        Long anchor = startTime != null && !startTime.isEmpty() ? absoluteNs(startTime, true) : null;
        if (Files.exists(outputDir)) {
            throw new UncheckedIOException(new FileAlreadyExistsException(outputDir.toString()));
        }
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, FileOut> files = new LinkedHashMap<>();
        Map<String, Long> skipped = new LinkedHashMap<>();
        Map<String, Long> counters = new LinkedHashMap<>();
        PacketAccum[] currentHolder = new PacketAccum[1];
        long totalLines = 0;

        Runnable finish = () -> {
            PacketAccum current = currentHolder[0];
            if (current == null) {
                return;
            }
            byte[] frame = current.bytes.toByteArray();
            if (current.bad || frame.length < 14) {
                inc(counters, "skipped_packets");
            } else {
                String name = current.iface;
                FileOut previous = files.get(name);
                Integer link = previous != null ? previous.linkType : 1;
                if (current.cooked != null && !(previous != null && previous.cookedChecked)) {
                    link = cookedLinkType(frame, current.cooked, tshark);
                    if (link == null || (previous != null && previous.linkType != link)) {
                        inc(counters, "skipped_packets");
                        inc(skipped, "unsupported_cooked_link_header");
                        currentHolder[0] = null;
                        return;
                    }
                }
                if (!files.containsKey(name)) {
                    if (files.size() >= 64) {
                        throw new IllegalArgumentException("More than 64 interfaces; split the source text");
                    }
                    String safe = SAFE_NAME.matcher(name).replaceAll("_");
                    if (safe.length() > 64) {
                        safe = safe.substring(0, 64);
                    }
                    Path path = outputDir.resolve(String.format(Locale.ROOT, "interface-%02d-%s.pcap", files.size(), safe));
                    try {
                        OutputStream handle = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE);
                        ByteBuffer hdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
                        hdr.putInt(0xA1B23C4D);
                        hdr.putShort((short) 2);
                        hdr.putShort((short) 4);
                        hdr.putInt(0);
                        hdr.putInt(0);
                        hdr.putInt(16777216);
                        hdr.putInt(link);
                        handle.write(hdr.array());
                        files.put(name, new FileOut(path.toRealPath().toString(), handle, link));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                FileOut item = files.get(name);
                item.cookedChecked |= current.cooked != null;
                long sec = PyMath.floorDiv(current.timeNs, 1_000_000_000L);
                long nano = PyMath.mod(current.timeNs, 1_000_000_000L);
                if (sec < 0 || sec > 0xFFFFFFFFL) {
                    inc(counters, "skipped_packets");
                    inc(skipped, "timestamp_outside_pcap_range");
                } else {
                    ByteBuffer pkt = ByteBuffer.allocate(16 + frame.length).order(ByteOrder.LITTLE_ENDIAN);
                    pkt.putInt((int) sec);
                    pkt.putInt((int) nano);
                    pkt.putInt(frame.length);
                    pkt.putInt(frame.length);
                    pkt.put(frame);
                    try {
                        item.handle.write(pkt.array());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    item.packets += 1;
                    item.relative |= current.relative;
                    inc(counters, "packets");
                }
            }
            currentHolder[0] = null;
        };

        try {
            try (BufferedReader src = new BufferedReader(new InputStreamReader(Files.newInputStream(source),
                    StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPLACE)
                            .onUnmappableCharacter(CodingErrorAction.REPLACE)))) {
                String raw;
                while ((raw = src.readLine()) != null) {
                    totalLines++;
                    if (totalLines == 1 && !raw.isEmpty() && raw.charAt(0) == '\uFEFF') {
                        raw = raw.substring(1);
                    }
                    if (totalLines % 1000 == 0) {
                        p.update(Map.of("state", "converting Fortinet text", "file", sourceName,
                                "frames", counters.getOrDefault("packets", 0L)));
                    }
                    if (c.isSet()) {
                        throw new IngestCancelledException("Fortinet conversion cancelled");
                    }
                    String line = ANSI.matcher(stripTrailingCrLf(raw)).replaceAll("").replace('\u00a0', ' ');
                    if (line.strip().isEmpty()) {
                        continue;
                    }
                    Matcher header = HEADER.matcher(line);
                    if (header.find()) {
                        finish.run();
                        String stamp = header.group(1);
                        String iface = header.group(2);
                        boolean relative = !YEAR_PREFIX.matcher(stamp).find();
                        if (relative && anchor == null) {
                            throw new IllegalArgumentException(
                                    "Relative Fortinet timestamps require a user-supplied start time");
                        }
                        long ns;
                        try {
                            if (relative) {
                                long delta = new BigDecimal(stamp).multiply(NANO_SCALE, DECIMAL_CTX)
                                        .toBigInteger().longValueExact();
                                ns = Math.addExact(anchor, delta);
                            } else {
                                ns = absoluteNs(stamp, false);
                            }
                        } catch (IllegalArgumentException | ArithmeticException e) {
                            inc(skipped, "invalid_timestamp");
                            inc(counters, "skipped_packets");
                            continue;
                        }
                        currentHolder[0] = new PacketAccum(iface, ns, relative);
                        continue;
                    }
                    if (line.toLowerCase(Locale.ROOT).contains("linux cooked capture")) {
                        if (currentHolder[0] != null) {
                            currentHolder[0].cooked = line;
                        } else {
                            inc(skipped, "cooked_metadata_without_header");
                        }
                        continue;
                    }
                    Matcher match = HEX.matcher(line);
                    boolean matched = match.matches();
                    PacketAccum current = currentHolder[0];
                    boolean continuation = current != null && 0 < current.rowBytes && current.rowBytes < 16
                            && leadingSpaces(line) >= 4;
                    if (current != null && (matched || continuation)) {
                        byte[] chunk = readHex(matched ? match.group(2) : line);
                        if (chunk == null) {
                            if (matched) {
                                current.bad = true;
                                inc(skipped, "invalid_or_truncated_hex");
                            } else {
                                inc(skipped, "console_noise");
                            }
                            continue;
                        }
                        long offset = matched ? Long.parseLong(match.group(1), 16) : current.bytes.size();
                        if (offset != current.bytes.size() || current.bytes.size() + chunk.length > 16777216) {
                            current.bad = true;
                            inc(skipped, "hex_gap_or_overlap");
                            continue;
                        }
                        current.bytes.writeBytes(chunk);
                        current.rowBytes = matched ? chunk.length : current.rowBytes + chunk.length;
                    } else {
                        inc(skipped, matched ? "hex_without_packet_header" : "console_noise");
                    }
                }
            }
            finish.run();
            long packets = counters.getOrDefault("packets", 0L);
            long skippedPackets = counters.getOrDefault("skipped_packets", 0L);
            long skippedLines = skipped.values().stream().mapToLong(Long::longValue).sum();
            if (packets == 0) {
                throw new IllegalArgumentException("No complete packets found in Fortinet text; " + skippedLines
                        + " skipped lines, " + skippedPackets + " skipped packets: " + formatDict(skipped));
            }
            List<Map<String, Object>> fileList = new ArrayList<>();
            for (Map.Entry<String, FileOut> e : files.entrySet()) {
                FileOut item = e.getValue();
                item.handle.close();
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("path", item.path);
                f.put("interface", e.getKey());
                f.put("packets", item.packets);
                f.put("adapter", "fortinet");
                f.put("link_type", (long) item.linkType);
                f.put("cooked_metadata", item.cookedChecked);
                f.put("clock_group", outputDir.getFileName().toString());
                f.put("clock_confidence", item.relative ? "low" : "source");
                f.put("relative_timestamps", item.relative);
                f.put("start_time", item.relative ? startTime : null);
                fileList.add(f);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("source", sourceName);
            result.put("packets", packets);
            result.put("skipped_packets", skippedPackets);
            result.put("skipped_lines", skippedLines);
            result.put("skipped_reasons", new LinkedHashMap<>(skipped));
            result.put("lines", totalLines);
            result.put("files", fileList);
            return result;
        } catch (Throwable t) {
            for (FileOut item : files.values()) {
                try {
                    item.handle.close();
                } catch (IOException ignored) {
                }
            }
            deleteTree(outputDir);
            if (t instanceof RuntimeException re) {
                throw re;
            }
            if (t instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(t);
        }
    }

    private static String formatDict(Map<String, Long> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Long> e : map.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append('\'').append(e.getKey()).append("': ").append(e.getValue());
        }
        return sb.append('}').toString();
    }

    /** {@code import_text(project, path, start_time=None, device="FortiGate", cancel=None, progress=None)}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> importText(ProjectStore project, Path path, String startTime, String device,
            Cancellation cancel, Progress progress) {
        String dev = device == null ? "FortiGate" : device;
        Path destination = project.path().resolve("captures")
                .resolve("fortinet-" + UUID.randomUUID().toString().replace("-", ""));
        Map<String, Object> preferences;
        try (Db db = project.connect()) {
            Object p = ProjectStore.get(db, "preferences", Map.of());
            preferences = p instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        }
        String tshark = (String) preferences.get("tshark");
        int prefixBytes = preferences.get("prefix_bytes") instanceof Number n ? n.intValue() : 64;
        Map<String, Object> result = convertText(path, destination, startTime, cancel, progress, tshark);
        Map<String, Object> topology;
        try (Db db = project.connect()) {
            Object stored = ProjectStore.get(db, "topology", null);
            topology = stored != null
                    ? (Map<String, Object>) com.securityexpert.nexus.ui2.packetanalysis.topology.PyJson.loads(
                            PyJson.dumps(stored))
                    : new Topology().dump();
        }
        List<Object> points = (List<Object>) topology.get("points");
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> files = (List<Map<String, Object>>) result.get("files");
        for (Map<String, Object> item : files) {
            Map<String, Object> provenance = new LinkedHashMap<>(item);
            provenance.put("source", result.get("source"));
            provenance.put("skipped_lines", result.get("skipped_lines"));
            provenance.put("skipped_packets", result.get("skipped_packets"));
            provenance.put("skipped_reasons", result.get("skipped_reasons"));
            provenance.remove("path");
            IngestOptions options = new IngestOptions(tshark, prefixBytes, 50000, false, provenance, null, null, null,
                    null, null);
            String cid = CaptureIngest.ingest(project, Path.of((String) item.get("path")), options, cancel, progress);
            Map<String, Object> pt = new LinkedHashMap<>();
            pt.put("id", "forti-" + cid.substring(0, 12));
            pt.put("label", item.get("interface"));
            pt.put("device", dev);
            pt.put("capture_id", cid);
            pt.put("vendor", "fortinet");
            pt.put("vendor_stage", item.get("interface"));
            pt.put("kind", "Firewall");
            pt.put("x", new PyInt(120L + 240L * (points.size() + added.size())));
            pt.put("y", new PyInt(160L));
            added.add(pt);
            item.put("capture_id", cid);
        }
        if (points.size() + added.size() > 64) {
            throw new IllegalArgumentException(
                    "Import exceeds 64 topology points; converted captures are retained for a separate project");
        }
        points.addAll(added);
        Map<String, Object> validated = Topology.validate(topology).dump();
        Object dumped = PyJson.loads(com.securityexpert.nexus.ui2.packetanalysis.topology.PyJson.dumps(validated));
        try (Db db = project.connect()) {
            ProjectStore.set(db, "topology", dumped);
            ProjectStore.set(db, "report", null);
            ProjectStore.set(db, "fortinet_conversion", result);
        }
        return result;
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
