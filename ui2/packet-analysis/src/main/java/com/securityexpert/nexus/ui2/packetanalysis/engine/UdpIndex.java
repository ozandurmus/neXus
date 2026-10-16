package com.securityexpert.nexus.ui2.packetanalysis.engine;

import com.securityexpert.nexus.ui2.packetanalysis.engine.Db.Row;
import com.securityexpert.nexus.ui2.packetanalysis.engine.Topology.Point;
import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.PyCsv;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.TsharkRunner;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyConvert;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFormat;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Reference {@code udp_index.py}: point-scoped tshark header fields for UDP/RTP/SIP ({@code index_udp}), and the
 * protocol identity added to portable evidence filters ({@code protocol_evidence}, {@code frame_evidence}). The
 * tshark argument lists, the CSV load through {@code from_json}, the frame-identity guard and the failure reasons
 * are the reference's.
 */
public final class UdpIndex {
    public static final List<String> FIELDS;
    public static final long VERSION = 4;

    static {
        List<String> f = new ArrayList<>();
        f.addAll(Arrays.asList(("frame.number frame.time_epoch ip.src ipv6.src ip.dst ipv6.dst udp.srcport udp.dstport"
                + " frame.protocols ntp.flags.mode ntp.org ntp.xmt snmp.request_id snmp.msgID snmp.data radius.id"
                + " radius.code radius.authenticator syslog.level").split(" ")));
        f.addAll(Arrays.asList(("tcp.srcport tcp.dstport tcp.seq_raw rtp.ssrc rtp.seq rtp.timestamp rtp.p_type rtp.version"
                + " sip.Call-ID sip.CSeq.seq sip.CSeq.method sip.Method sip.Status-Code sip.Contact sip.Via sip.Via.branch"
                + " sdp.connection_info.address sdp.media.port sdp.media.media sdp.media.format sdp.media_attr").split(" ")));
        f.add("snmp.version");
        f.add("snmp.community");
        FIELDS = List.copyOf(f);
    }

    /** {@code statuses} of the reference: point, reason, cached. */
    public record Status(String point, String reason, boolean cached) {
    }

    /** A reference {@code ValueError}/{@code OSError} inside the per-point decode: becomes the cached reason. */
    static final class DecodeFailure extends RuntimeException {
        DecodeFailure(String message) {
            super(message);
        }
    }

    private UdpIndex() {
    }

    /** Stage body: {@code index_udp(project, db, topology, cancel)} as {@code analysis.matching.prepare} calls it. */
    static void stage(EngineContext ctx) {
        index(ctx.db, ctx.topology, true, null);
    }

    static String schema() {
        List<String> parts = new ArrayList<>();
        for (String f : FIELDS) {
            parts.add("\"" + f + "\" VARCHAR");
        }
        return String.join(",", parts);
    }

    static String fieldColumns() {
        List<String> parts = new ArrayList<>();
        for (String f : FIELDS) {
            parts.add("\"" + f + "\"");
        }
        return String.join(",", parts);
    }

    /**
     * {@code index_udp}. {@code tshark} overrides the binary (the reference reads {@code preferences.tshark} from the
     * project settings; {@code null} reads it the same way).
     */
    public static List<Status> index(Db db, Topology topology, boolean batchHeaders, String tshark) {
        db.exec(Sql.get("udp_index", "udp_decode_cache"));
        String schema = schema();
        String fieldColumns = fieldColumns();
        db.exec(Sql.fmt("udp_index", "udp_decoded", "schema", schema));
        Set<String> existing = new LinkedHashSet<>();
        for (Object[] r : db.tuples(Sql.get("udp_index", "describe_udp_decoded"))) {
            existing.add((String) r[0]);
        }
        for (String f : FIELDS) {
            if (!existing.contains(f)) {
                db.exec(Sql.fmt("udp_index", "add_field", "field", f));
            }
        }
        List<Status> statuses = new ArrayList<>();
        if (db.one(Sql.get("udp_index", "needed")) == null) {
            db.exec(Sql.get("udp_index", "clear_decoded"));
            db.exec(Sql.get("udp_index", "clear_cache"));
            return statuses;
        }
        Map<String, String> fileHeaders = new LinkedHashMap<>();
        String binary = null;
        for (Point point : topology.points) {
            if (db.one(Sql.get("udp_index", "point_needed"), point.id()) == null) {
                db.exec(Sql.get("udp_index", "delete_point"), point.id());
                continue;
            }
            Row capture = db.rows(Sql.get("udp_index", "capture"), point.captureId()).get(0);
            String identity = identity(batchHeaders, capture.str("identity"), topology, point);
            Object[] cached = db.one(Sql.get("udp_index", "cached"), point.id());
            if (cached != null && identity.equals(cached[0])) {
                statuses.add(new Status(point.id(), (String) cached[1], true));
                continue;
            }
            db.exec(Sql.get("udp_index", "delete_point"), point.id());
            String reason = null;
            try {
                binary = decodePoint(db, topology, point, capture, batchHeaders, fileHeaders, binary, tshark, schema,
                        fieldColumns);
            } catch (DecodeFailure e) {
                reason = e.getMessage();
                db.exec(Sql.get("udp_index", "delete_point"), point.id());
            }
            db.exec(Sql.get("udp_index", "cache_insert"), point.id(), identity, reason);
            statuses.add(new Status(point.id(), reason, false));
        }
        return statuses;
    }

    /**
     * {@code json.dumps([VERSION, batch_headers, capture identity, rtp_port_ranges, point.model_dump(exclude=...)],
     * sort_keys=True)}.
     */
    static String identity(boolean batchHeaders, String captureIdentity, Topology topology, Point p) {
        Map<String, Object> dump = new TreeMap<>();
        dump.put("id", p.id());
        dump.put("side", p.side());
        dump.put("capture_id", p.captureId());
        dump.put("clock_domain", p.clockDomain());
        dump.put("host_capture", p.hostCapture());
        dump.put("stage_capture", p.stageCapture());
        dump.put("interface", p.iface());
        dump.put("vendor", p.vendor());
        dump.put("vendor_stage", p.vendorStage());
        dump.put("vendor_interface", p.vendorInterface());
        dump.put("inspection_complete", p.inspectionComplete());
        dump.put("matching_layer", p.matchingLayer());
        dump.put("source_cidr", p.sourceCidr());
        List<Object> ranges = new ArrayList<>();
        for (long[] r : topology.rtpPortRanges) {
            ranges.add(List.of(r[0], r[1]));
        }
        return PyJsonWriter.dumps(Arrays.asList(VERSION, batchHeaders, captureIdentity, ranges, dump), false);
    }

    /** One point's decode; returns the (possibly resolved) tshark binary so later points reuse it. */
    @SuppressWarnings("unchecked")
    private static String decodePoint(Db db, Topology topology, Point point, Row capture, boolean batchHeaders,
            Map<String, String> fileHeaders, String binary, String tshark, String schema, String fieldColumns) {
        Path source = Path.of(capture.str("path"));
        List<Object> original = (List<Object>) Evidence.loads(capture.str("identity"));
        long size;
        long mtimeNs;
        try {
            BasicFileAttributes a = Files.readAttributes(source, BasicFileAttributes.class);
            size = a.size();
            mtimeNs = a.lastModifiedTime().to(TimeUnit.NANOSECONDS);
        } catch (NoSuchFileException e) {
            throw new DecodeFailure("[Errno 2] No such file or directory: '" + source + "'");
        } catch (IOException e) {
            throw new DecodeFailure(String.valueOf(e.getMessage()));
        }
        if (!Arrays.asList(source.toString(), size, mtimeNs).equals(new ArrayList<>(original.subList(0, 3)))) {
            throw new DecodeFailure("Capture changed; reattach before UDP analysis");
        }
        if (point.vendor().equals("f5") || db.one(Sql.get("udp_index", "tunnel_or_fragment"), point.id()) != null) {
            throw new DecodeFailure("UDP protocol index unknown for this vendor/tunnel/fragment view");
        }
        if (binary == null) {
            try {
                binary = TsharkRunner.find(tshark != null ? tshark : preferenceTshark(db));
            } catch (IllegalArgumentException e) {
                throw new DecodeFailure(e.getMessage());
            }
        }
        Map<String, Object> inv = (Map<String, Object>) Evidence.loads(capture.str("inventory"));
        List<String> options = new ArrayList<>();
        if ("snoop".equals(inv.get("format"))) {
            Object uuid = inv.containsKey("checkpoint_uuid") ? inv.get("checkpoint_uuid") : Boolean.FALSE;
            options.addAll(List.of("-o", "eth.interpret_as_fw1_monitor:TRUE", "-o", "fw1.iflist_with_chain:TRUE", "-o",
                    "fw1.with_uuid:" + Evidence.pyStr(uuid).toUpperCase(java.util.Locale.ROOT)));
        }
        long count = db.count(Sql.get("udp_index", "point_count"), point.id());
        long sharing = topology.points.stream().filter(p -> p.captureId().equals(point.captureId())).count();
        boolean headerOnly = batchHeaders && !topology.rtpPortRanges.isEmpty() && point.vendor().equals("none")
                && !"snoop".equals(inv.get("format")) && sharing > 1
                && db.one(Sql.get("udp_index", "capture_tcp_sip"), point.captureId()) == null;
        String headerTable = headerOnly ? fileHeaders.get(point.captureId()) : null;
        Path directory;
        Path errors;
        try {
            directory = Files.createTempDirectory("udp-point-");
            errors = Files.createTempFile("udp-errors-", ".txt");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            Path selected = source;
            boolean direct = headerOnly || canDecodeSource(point, inv, count);
            if (!direct) {
                selected = directory.resolve("point.pcapng");
                List<String> cmd = new ArrayList<>(List.of(binary, "-n", "-r", source.toString(), "-Y", readFilter(point, inv),
                        "-w", selected.toString(), "-F", "pcapng"));
                cmd.addAll(options);
                if (inv.get("frame_limit") != null) {
                    cmd.addAll(List.of("-c", Evidence.pyStr(inv.get("frame_limit"))));
                }
                if (run(cmd, null, errors) != 0) {
                    throw new DecodeFailure("Point isolation failed");
                }
            }
            db.exec(Sql.get("udp_index", "frame_map"), point.id());
            if (headerTable == null) {
                Path output = directory.resolve("fields.csv");
                List<String> cmd = new ArrayList<>(List.of(binary, "-n", "-2", "-r", selected.toString(), "-Y",
                        (headerOnly ? "udp" : "(udp || sip)") + " && !dns && !(gre || vxlan || geneve || icmp || icmpv6)",
                        "-T", "fields", "-E", "header=y", "-E", "separator=,", "-E", "quote=d", "-E", "occurrence=a"));
                cmd.addAll(options);
                if (direct && inv.get("frame_limit") != null) {
                    cmd.addAll(List.of("-c", Evidence.pyStr(inv.get("frame_limit"))));
                }
                for (long[] r : topology.rtpPortRanges) {
                    cmd.addAll(List.of("-d", "udp.port==" + r[0] + "-" + r[1] + ",rtp"));
                }
                for (String f : FIELDS) {
                    cmd.addAll(List.of("-e", f));
                }
                int code = run(cmd, output, errors);
                if (code != 0) {
                    throw new DecodeFailure("UDP decode failed: " + head(errors, 1000));
                }
                db.exec(Sql.fmt("udp_index", "udp_raw", "schema", schema));
                loadFields(db, output);
                // SDP is authoritative port negotiation; never infer RTP from arbitrary UDP bytes.
                List<Object[]> learned = db.tuples(Sql.get("udp_index", "learned_ports"));
                if (learned.size() > 256) {
                    throw new DecodeFailure("Too many SDP media ports; narrow the selected window/capture");
                }
                if (!learned.isEmpty() && topology.rtpPortRanges.isEmpty()) {
                    for (Object[] port : learned) {
                        cmd.addAll(List.of("-d", "udp.port==" + port[0] + ",rtp"));
                    }
                    if (run(cmd, output, errors) != 0) {
                        throw new DecodeFailure("SDP-directed media decode failed");
                    }
                    loadFields(db, output);
                }
                if (headerOnly) {
                    headerTable = "udp_headers_" + fileHeaders.size();
                    db.exec(Sql.fmt("udp_index", "header_table", "header_table", headerTable));
                    fileHeaders.put(point.captureId(), headerTable);
                }
            }
            if (headerOnly) {
                db.exec(Sql.fmt("udp_index", "header_select", "header_table", headerTable));
            }
            String join = Sql.get("udp_index", direct ? "join_direct" : "join_ordinal");
            String valid = Sql.get("udp_index", "valid");
            long wrong = db.count(Sql.fmt("udp_index", "wrong", "join", join, "valid", valid));
            if (wrong != 0) {
                throw new DecodeFailure("Decoded UDP fields do not uniquely map to original frames");
            }
            db.exec(Sql.fmt("udp_index", "insert_decoded", "field_columns", fieldColumns, "join", join));
        } finally {
            deleteTree(directory);
            try {
                Files.deleteIfExists(errors);
            } catch (IOException e) {
                // temporary file
            }
        }
        return binary;
    }

    private static String preferenceTshark(Db db) {
        Object[] row = db.one("SELECT value FROM settings WHERE key=?", "preferences");
        if (row == null || row[0] == null) {
            return null;
        }
        Object prefs = Evidence.loads((String) row[0]);
        if (prefs instanceof Map<?, ?> m && m.get("tshark") instanceof String s) {
            return s;
        }
        return null;
    }

    /** {@code application_index.can_decode_source}. */
    static boolean canDecodeSource(Point point, Map<String, Object> inventory, long count) {
        Object records = inventory.get("records_read");
        Object packets = inventory.get("packet_count");
        Object interfaces = inventory.getOrDefault("interfaces", List.of());
        return point.iface() == null && point.sourceCidr() == null && point.vendor().equals("none")
                && Evidence.truthy(inventory.getOrDefault("timestamps_validated", Boolean.FALSE))
                && records != null && packets != null && pyEq(records, packets) && pyEq(packets, count)
                && interfaces instanceof List<?> l && l.size() == 1 && !Evidence.truthy(inventory.get("damaged_tail"))
                && inventory.get("frame_limit") == null;
    }

    private static boolean pyEq(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return x.doubleValue() == y.doubleValue();
        }
        return a.equals(b);
    }

    /** {@code application_index.read_filter}. */
    static String readFilter(Point point, Map<String, Object> inventory) {
        List<String> terms = new ArrayList<>();
        terms.add("frame.time_epoch >= " + PyFormat.format(((Number) inventory.get("start")).doubleValue() - 1e-6, ".9f"));
        terms.add("frame.time_epoch <= " + PyFormat.format(((Number) inventory.get("end")).doubleValue() + 1e-6, ".9f"));
        if (point.iface() != null) {
            terms.add("frame.interface_id == " + point.iface());
        }
        if (point.sourceCidr() != null && !point.sourceCidr().isEmpty()) {
            String family = point.sourceCidr().contains(":") ? "ipv6" : "ip";
            terms.add(family + ".src == " + point.sourceCidr());
        }
        if (point.vendor().equals("checkpoint")) {
            String stage = point.vendorStage();
            terms.add("fw1.direction == " + PyJsonWriter.string(stage.substring(0, 1)));
            if (stage.length() > 1) {
                terms.add("fw1.chain == " + PyJsonWriter.string(stage.substring(1)));
            } else if (stage.equals("o") || stage.equals("O")) {
                terms.add("fw1.chain != " + PyJsonWriter.string(stage.equals("o") ? "e" : "E"));
            }
            if (point.vendorInterface() != null && !point.vendorInterface().isEmpty()) {
                terms.add("fw1.interface == " + PyJsonWriter.string(point.vendorInterface()));
            }
        }
        return String.join(" && ", terms);
    }

    @FunctionalInterface
    interface Runner {
        int run(List<String> cmd, Path stdout, Path errors);
    }

    static volatile Runner runner = UdpIndex::runProcess;

    /** {@code running(...)}: stdout to a file (or discarded), stderr appended to the shared error file. */
    private static int run(List<String> cmd, Path stdout, Path errors) {
        return runner.run(cmd, stdout, errors);
    }

    static int runProcess(List<String> cmd, Path stdout, Path errors) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().remove("SSLKEYLOGFILE");
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        pb.redirectOutput(stdout == null ? ProcessBuilder.Redirect.DISCARD : ProcessBuilder.Redirect.to(stdout.toFile()));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(errors.toFile()));
        try {
            Process p = pb.start();
            p.getOutputStream().close();
            return p.waitFor();
        } catch (IOException e) {
            throw new DecodeFailure(String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("UDP indexing cancelled", e);
        }
    }

    private static String head(Path errors, int chars) {
        try {
            String text = new String(Files.readAllBytes(errors), StandardCharsets.UTF_8);
            return text.codePointCount(0, text.length()) <= chars ? text : text.substring(0, text.offsetByCodePoints(0, chars));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code load_fields}: csv.DictReader over tshark's CSV, 4096-row {@code from_json} batches into {@code udp_raw}. */
    static void loadFields(Db db, Path output) {
        db.exec(Sql.get("udp_index", "raw_clear"));
        Map<String, Object> shapeRow = new LinkedHashMap<>();
        for (String f : FIELDS) {
            shapeRow.put(f, "VARCHAR");
        }
        String shape = PyJsonWriter.dumps(List.of(shapeRow), false);
        List<Object> buffer = new ArrayList<>();
        try (Reader in = new InputStreamReader(Files.newInputStream(output), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))) {
            PyCsv.CsvReader reader = new PyCsv.CsvReader(in, ',');
            List<String> header = reader.next();
            while (header != null && header.isEmpty()) {
                header = reader.next();
            }
            if (!FIELDS.equals(header)) {
                throw new DecodeFailure("UDP decoder field schema differs");
            }
            List<String> record;
            while ((record = reader.next()) != null) {
                if (record.isEmpty()) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < FIELDS.size(); i++) {
                    String v = i < record.size() ? record.get(i) : null;
                    row.put(FIELDS.get(i), v == null || v.isEmpty() ? null : v);
                }
                buffer.add(row);
                if (buffer.size() >= 4096) {
                    flush(db, buffer, shape);
                }
            }
            flush(db, buffer, shape);
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new DecodeFailure("'utf-8' codec can't decode bytes: invalid UTF-8 in tshark output");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void flush(Db db, List<Object> buffer, String shape) {
        if (!buffer.isEmpty()) {
            db.exec(Sql.get("udp_index", "raw_insert"), PyJsonWriter.dumps(buffer, false), shape);
            buffer.clear();
        }
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            // temporary directory
        }
    }

    // ------------------------------------------------------------------ protocol_evidence / frame_evidence

    private static final List<String> NUMERIC = List.of("rtp.ssrc", "rtp.seq", "rtp.timestamp", "snmp.request_id",
            "radius.id", "radius.code", "ntp.flags.mode", "sip.CSeq.seq");

    /** {@code protocol_evidence}: add tshark header identity to portable filters, one bounded lookup. */
    public static List<Map<String, Object>> protocolEvidence(Db db, String predicate, List<Object> params, int limit) {
        List<Map<String, Object>> refs = Evidence.evidence(db, predicate, params, limit);
        if (refs.isEmpty()) {
            return refs;
        }
        List<Object> selected = new ArrayList<>();
        for (Map<String, Object> r : refs) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("point", r.get("point"));
            s.put("frame", r.get("frame"));
            selected.add(s);
        }
        List<Row> decoded = db.rows(Sql.get("udp_index", "decoded_lookup"), PyJsonWriter.dumps(selected, false));
        Map<List<Object>, Row> lookup = new LinkedHashMap<>();
        for (Row d : decoded) {
            lookup.put(Arrays.asList(d.get("point"), d.get("frame")), d);
        }
        for (Map<String, Object> ref : refs) {
            Row data = lookup.getOrDefault(Arrays.asList(ref.get("point"), ref.get("frame")), new Row());
            if (!Evidence.truthy(ref.get("content_filter"))) {
                continue;
            }
            Map<String, Object> identity = new LinkedHashMap<>();
            for (String field : NUMERIC) {
                String value = (String) data.get(field);
                if (value == null) {
                    continue;
                }
                long parsed;
                try {
                    parsed = value.startsWith("0x") ? PyConvert.parseInt(value, 16) : PyConvert.parseInt(value);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                ref.put("content_filter", ref.get("content_filter") + " && " + field + " == " + parsed);
                identity.put(field, parsed);
            }
            String callId = (String) data.get("sip.Call-ID");
            if (callId != null && !callId.isEmpty() && !callId.contains(",")) {
                ref.put("content_filter", ref.get("content_filter") + " && sip.Call-ID == " + PyJsonWriter.string(callId));
                identity.put("sip.Call-ID", callId);
            }
            if (!identity.isEmpty()) {
                ref.put("protocol_identity", identity);
            }
        }
        return refs;
    }

    /** {@code frame_evidence}: bounded button references for (point, frame) pairs, 512 per query. */
    public static Map<List<Object>, Map<String, Object>> frameEvidence(Db db, List<List<Object>> pairs) {
        LinkedHashSet<List<Object>> unique = new LinkedHashSet<>();
        for (List<Object> p : pairs) {
            if (p.get(1) != null) {
                unique.add(Arrays.asList(p.get(0), p.get(1)));
            }
        }
        List<List<Object>> ordered = new ArrayList<>(unique);
        Map<List<Object>, Map<String, Object>> result = new LinkedHashMap<>();
        for (int start = 0; start < ordered.size(); start += 512) {
            List<Object> wanted = new ArrayList<>();
            for (List<Object> p : ordered.subList(start, Math.min(ordered.size(), start + 512))) {
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("point", p.get(0));
                w.put("frame", p.get(1));
                wanted.add(w);
            }
            List<Map<String, Object>> refs = protocolEvidence(db, Sql.get("udp_index", "frame_predicate"),
                    List.of(PyJsonWriter.dumps(wanted, false)), 512);
            for (Map<String, Object> r : refs) {
                result.put(Arrays.asList(r.get("point"), r.get("frame")), r);
            }
        }
        return result;
    }
}
