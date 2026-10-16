package com.securityexpert.nexus.ui2.packetanalysis.topology;

import static com.securityexpert.nexus.ui2.packetanalysis.topology.Py.dict;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyArith;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyNum;
import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyTimSort;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Case scaffold after ingest and the inventory-only preflight ({@code case_setup.py}): capture discovery in a
 * folder, the default topology points, the automatic assembly hand-off and the stored {@code case_context}.
 * Ingest itself is a separate stage; its outcome (the optional warning and per-file issues) is passed in.
 */
public final class CaseSetup {

    private static final List<byte[]> MAGICS = List.of(
            new byte[] {(byte) 0xd4, (byte) 0xc3, (byte) 0xb2, (byte) 0xa1},
            new byte[] {(byte) 0xa1, (byte) 0xb2, (byte) 0xc3, (byte) 0xd4},
            new byte[] {(byte) 0x4d, (byte) 0x3c, (byte) 0xb2, (byte) 0xa1},
            new byte[] {(byte) 0xa1, (byte) 0xb2, (byte) 0x3c, (byte) 0x4d},
            new byte[] {(byte) 0x0a, (byte) 0x0d, (byte) 0x0d, (byte) 0x0a},
            new byte[] {'s', 'n', 'o', 'o'});
    private static final Set<String> SUFFIXES = Set.of(".pcap", ".pcapng", ".cap", ".snoop");
    static final String NOTE = "File names do not establish path order, device identity or client direction. Draw "
            + "the path and assign ingress/egress explicitly.";

    private CaseSetup() {
    }

    /** {@code capture_paths(folder)}: capture files under the folder, recursively, in path order. */
    public static List<Path> capturePaths(Path folder) {
        Path root;
        try {
            root = folder.toAbsolutePath().normalize().toRealPath();
        } catch (IOException e) {
            root = folder.toAbsolutePath().normalize();
        }
        if (!Files.isDirectory(root) || root.getParent() == null) {
            throw new IllegalArgumentException("Choose an existing capture folder, not the filesystem root");
        }
        List<Path> all;
        try (Stream<Path> walk = Files.walk(root)) {
            all = new ArrayList<>(walk.toList());
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        all.remove(root);
        all.sort((a, b) -> PyPaths.PATH_ORDER.compare(a.toString(), b.toString()));
        List<Path> result = new ArrayList<>();
        for (Path p : all) {
            if (!Files.isRegularFile(p)) {
                continue;
            }
            byte[] magic;
            try (InputStream in = Files.newInputStream(p)) {
                magic = in.readNBytes(4);
            } catch (IOException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
            String name = p.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String suffix = (0 < dot && dot < name.length() - 1) ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
            byte[] m = magic;
            if (MAGICS.stream().anyMatch(x -> Arrays.equals(x, m)) || SUFFIXES.contains(suffix)) {
                result.add(p);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("No supported capture files found in this folder");
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> info(Map<String, Object> capture) {
        return (Map<String, Object>) capture.get("inventory");
    }

    /** {@code inventory_overview(captures)}: per-file warnings, the common time window and pre-filter summary. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> inventoryOverview(List<Map<String, Object>> captures) {
        List<Map<String, Object>> files = new ArrayList<>();
        List<PyNum[]> intervals = new ArrayList<>();
        for (Map<String, Object> capture : captures) {
            Map<String, Object> info = info(capture);
            List<Object> warnings = new ArrayList<>((List<Object>) info.getOrDefault("warnings", List.of()));
            if (Py.truthy(capture.get("error"))) {
                warnings.add(capture.get("error"));
            }
            if (Py.truthy(info.get("truncated"))) {
                warnings.add(Py.str(info.get("truncated")) + " capture-truncated packets; only retained bytes can be "
                        + "compared");
            }
            if (Py.gt(Py.or(info.get("ifdrop"), Py.i(0)), Py.i(0)) || Py.gt(Py.or(info.get("osdrop"), Py.i(0)), Py.i(0))) {
                warnings.add("Capture-reported drops: interface " + Py.str(info.get("ifdrop")) + ", OS "
                        + Py.str(info.get("osdrop")));
            }
            Object tunnels = info.containsKey("tunnel_counts") ? info.get("tunnel_counts") : new LinkedHashMap<>();
            List<PyNum> snaplenSet = new ArrayList<>();
            for (Object iface : (List<Object>) info.getOrDefault("interfaces", List.of())) {
                Object s = ((Map<String, Object>) iface).get("snaplen");
                if (s != null && !Py.contains(snaplenSet, s)) {
                    snaplenSet.add(Py.num(s));
                }
            }
            List<PyNum> snaplens = PyTimSort.sorted(snaplenSet, false);
            if (Py.truthy(tunnels)) {
                warnings.add("Tunnel observations: " + Py.str(tunnels)
                        + "; choose a matching layer in Path (ERSPAN defaults inner)");
            }
            if (Py.truthy(info.get("unsupported")) && !Py.truthy(tunnels)) {
                warnings.add(Py.str(info.get("unsupported"))
                        + " observations need protocol/layer/reassembly validation during analysis");
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("id", capture.get("id"));
            f.put("name", capture.get("name"));
            f.put("state", capture.get("state"));
            f.put("warnings", warnings);
            f.put("start", info.get("start"));
            f.put("end", info.get("end"));
            f.put("packet_count", info.get("packet_count"));
            f.put("prefilter_total", info.get("prefilter_total"));
            f.put("prefilter_retained", info.get("prefilter_retained"));
            f.put("prefilter_excluded", info.containsKey("prefilter_excluded") ? info.get("prefilter_excluded") : Py.i(0));
            f.put("prefilter_skipped", Py.truthy(info.get("prefilter_skipped")));
            f.put("header_snaplen", snaplens.size() == 1 ? snaplens.get(0) : info.get("snaplen"));
            f.put("header_snaplens", new ArrayList<Object>(snaplens));
            f.put("observed_max_caplen", info.get("observed_max_caplen"));
            f.put("ifdrop", info.get("ifdrop"));
            f.put("osdrop", info.get("osdrop"));
            f.put("tunnel_counts", tunnels);
            files.add(f);
            if ("ready".equals(capture.get("state")) && info.get("start") != null && info.get("end") != null) {
                intervals.add(new PyNum[] {Py.num(info.get("start")), Py.num(info.get("end"))});
            }
        }
        Map<String, Object> overlap;
        if (intervals.size() < 2) {
            overlap = dict("status", "unknown", "reason", "At least two ready captures with valid time ranges are required");
        } else {
            PyNum start = intervals.get(0)[0];
            PyNum end = intervals.get(0)[1];
            for (PyNum[] iv : intervals.subList(1, intervals.size())) {
                if (PyArith.gt(iv[0], start)) {
                    start = iv[0];
                }
                if (PyArith.lt(iv[1], end)) {
                    end = iv[1];
                }
            }
            boolean positive = PyArith.lt(start, end);
            overlap = dict("status", positive ? "overlap" : "none", "start", start, "end", end,
                    "reason", positive ? "Observed timestamp ranges overlap; clock alignment is still required"
                            : "Capture time ranges have no positive-duration overlap. Clock offsets may explain this; "
                                    + "verify capture windows and clock settings before analysis.");
        }
        Map<String, Object> prefilterByFile = new LinkedHashMap<>();
        Map<String, Object> prefilterExcluded = new LinkedHashMap<>();
        List<Object> summaryFiles = new ArrayList<>();
        for (Map<String, Object> f : files) {
            prefilterByFile.put((String) f.get("name"), f.get("prefilter_excluded"));
            if (Py.truthy(f.get("prefilter_excluded"))) {
                prefilterExcluded.put((String) f.get("name"), f.get("prefilter_excluded"));
            }
            summaryFiles.add(dict("file", f.get("name"), "packet_count", f.get("packet_count"),
                    "prefilter_total", f.get("prefilter_total"), "prefilter_retained", f.get("prefilter_retained"),
                    "prefilter_excluded", f.get("prefilter_excluded"), "prefilter_skipped", f.get("prefilter_skipped")));
        }
        return dict("files", new ArrayList<Object>(files), "overlap", overlap, "prefilter_excluded", prefilterExcluded,
                "prefilter_excluded_by_file", prefilterByFile,
                "summary", dict("file_count", Py.i(files.size()), "prefilter_excluded", prefilterByFile,
                        "files", summaryFiles));
    }

    /** The ingest stage's outcome that {@code create_case} records. */
    public record IngestOutcome(String warning, List<Map<String, Object>> fileStates) {
        public static IngestOutcome clean() {
            return new IngestOutcome(null, List.of());
        }
    }

    /**
     * {@code create_case(folder, ...)} after ingest: the default topology points for new captures, automatic
     * assembly (unless a saved topology already has a path) handed to {@code analyzer}, and {@code case_context}.
     */
    public static Map<String, Object> createCase(ProjectDb project, Path folder, IngestOutcome ingest, boolean prefilter,
            boolean autoAssemble, Assemble.Analyzer analyzer) {
        List<Path> paths = capturePaths(folder);
        Path root;
        try {
            root = folder.toAbsolutePath().normalize().toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        List<Map<String, Object>> inventory = project.inventory();
        Map<String, String> labels = new LinkedHashMap<>();
        for (Path p : paths) {
            String resolved;
            try {
                resolved = p.toRealPath().toString();
            } catch (IOException e) {
                resolved = p.toString();
            }
            labels.put(resolved, root.relativize(p).toString().replace(java.io.File.separatorChar, '/'));
        }
        Map<String, Object> prefilterByFile = new LinkedHashMap<>();
        List<Object> summaryFiles = new ArrayList<>();
        for (Map<String, Object> c : inventory) {
            Map<String, Object> inv = info(c);
            prefilterByFile.put((String) c.get("name"), inv.containsKey("prefilter_excluded")
                    ? inv.get("prefilter_excluded") : Py.i(0));
            summaryFiles.add(dict("file", c.get("name"),
                    "packet_count", inv.containsKey("packet_count") ? inv.get("packet_count") : Py.i(0),
                    "prefilter_total", inv.get("prefilter_total"), "prefilter_retained", inv.get("prefilter_retained"),
                    "prefilter_excluded", inv.containsKey("prefilter_excluded") ? inv.get("prefilter_excluded") : Py.i(0),
                    "prefilter_skipped", Py.truthy(inv.get("prefilter_skipped"))));
        }
        Map<String, Object> summary = dict("file_count", Py.i(paths.size()), "prefilter_excluded", prefilterByFile,
                "files", summaryFiles);
        Object saved;
        Topology topology;
        try (ProjectDb.Db db = project.connect()) {
            saved = db.get("topology", null);
            topology = Py.truthy(saved) ? Topology.validate(saved) : new Topology();
            Set<String> assigned = new HashSet<>();
            for (Point p : topology.points) {
                assigned.add(p.captureId);
            }
            for (Map<String, Object> capture : inventory) {
                String path = (String) capture.get("path");
                if (labels.containsKey(path) && !assigned.contains(capture.get("id")) && topology.points.size() < 64) {
                    int n = topology.points.size();
                    String label = Py.prefix(labels.get(path), 100);
                    topology.points.add(Point.of("p_" + capture.get("id"), label, label, (String) capture.get("id"),
                            Py.i((n % 4) * 240L), Py.i((n / 4) * 180L)));
                }
            }
            db.set("topology", topology.dump());
        }
        Object suggested = null;
        boolean savedHasPath = Py.truthy(saved) && Py.truthy(((Map<?, ?>) saved).get("forward"));
        if (autoAssemble && !savedHasPath) {
            suggested = Assemble.assembleAndAnalyze(project, topology, analyzer).get("suggested_topology");
        }
        try (ProjectDb.Db db = project.connect()) {
            if (suggested == null) {
                suggested = db.get("suggested_topology", null);
            }
            List<Object> issues = new ArrayList<>();
            for (Map<String, Object> f : ingest.fileStates()) {
                if (Py.truthy(f.get("error"))) {
                    issues.add(dict("file", f.get("file"), "error", f.get("error")));
                }
            }
            Map<String, Object> context = dict("folder", root.toString(), "files", Py.i(paths.size()),
                    "warning", ingest.warning(), "prefilter", prefilter, "prefilter_excluded", prefilterByFile,
                    "summary", summary, "suggested_topology", suggested, "issues", issues, "note", NOTE);
            db.set("case_context", context);
            return context;
        }
    }
}
