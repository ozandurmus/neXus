package com.securityexpert.nexus.ui2.packetanalysis.ingest;

import com.securityexpert.nexus.ui2.packetanalysis.store.AnalysisFailure;
import com.securityexpert.nexus.ui2.packetanalysis.store.AnalysisLimitException;
import com.securityexpert.nexus.ui2.packetanalysis.store.Db;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import com.securityexpert.nexus.ui2.packetanalysis.store.Sql;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Ingest several capture files into one project (the reference {@code ingest_many}): one tshark per file, bounded
 * parallelism, commits serialised by the project lock, and the optional pre-filter that ingests captures with more
 * than five times the frames of the others only for IP pairs seen elsewhere.
 */
public final class BatchIngest {

    /** RAM budgeted per parallel worker: 1.6 GiB. */
    public static final double RAM_PER_WORKER = 1.6 * 1024 * 1024 * 1024;

    private static final Set<String> MAGICS = Set.of("d4c3b2a1", "a1b2c3d4", "4d3cb2a1", "a1b23c4d", "0a0d0d0a",
            "736e6f6f");
    private static final Set<String> SUFFIXES = Set.of(".pcap", ".pcapng", ".cap", ".snoop");

    /** Batch settings. */
    public record Settings(IngestOptions options, boolean parallel, Integer workers, boolean prefilter,
            int cpuCores, long availableRamBytes) {
    }

    private BatchIngest() {
    }

    /** {@code worker_budget}. */
    public static int workerBudget(int files, int cores, long availableRam) {
        int workers = (int) Math.min(files, Math.min(Math.max(0, cores - 1),
                Math.max(0, (long) Math.floor(availableRam / RAM_PER_WORKER))));
        if (files > 0 && workers < 1) {
            throw new IllegalArgumentException(
                    "No ingest worker budget: need a spare CPU core and 1.6 GiB available RAM");
        }
        return workers;
    }

    /** {@code normalize_paths}: resolved, duplicates dropped, order kept. */
    static List<Path> normalize(List<Path> paths) {
        return List.copyOf(new LinkedHashSet<>(paths.stream().map(CaptureIngest::resolve).toList()));
    }

    /** The reference {@code PurePath} ordering: component by component. */
    static final Comparator<Path> PATH_ORDER = (a, b) -> {
        int n = Math.min(a.getNameCount(), b.getNameCount());
        for (int i = 0; i < n; i++) {
            int c = a.getName(i).toString().compareTo(b.getName(i).toString());
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.getNameCount(), b.getNameCount());
    };

    /** {@code capture_paths(folder)}: every file below the folder with a capture magic or capture suffix. */
    public static List<Path> capturePaths(Path folder) {
        Path root = CaptureIngest.resolve(folder);
        if (!Files.isDirectory(root) || root.getParent() == null) {
            throw new IllegalArgumentException("Choose an existing capture folder, not the filesystem root");
        }
        List<Path> result = new ArrayList<>();
        List<Path> all;
        try (Stream<Path> s = Files.walk(root)) {
            all = s.filter(p -> !p.equals(root)).sorted(PATH_ORDER).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Path p : all) {
            if (!Files.isRegularFile(p)) {
                continue;
            }
            byte[] magic;
            try (InputStream in = Files.newInputStream(p)) {
                magic = in.readNBytes(4);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (MAGICS.contains(java.util.HexFormat.of().formatHex(magic)) || SUFFIXES.contains(suffix(p))) {
                result.add(p);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("No supported capture files found in this folder");
        }
        return result;
    }

    /** {@code PurePath.suffix.lower()}. */
    static String suffix(Path p) {
        String name = p.getFileName().toString();
        int i = name.lastIndexOf('.');
        return i > 0 && i < name.length() - 1 ? name.substring(i).toLowerCase(java.util.Locale.ROOT) : "";
    }

    /** {@code _ip_pair_filter}. */
    static String ipPairFilter(List<String[]> pairs) {
        List<String> clauses = new ArrayList<>();
        for (String[] p : pairs) {
            String a = p[0];
            String b = p[1];
            String field = a.contains(":") || b.contains(":") ? "ipv6" : "ip";
            if (a.equals(b)) {
                clauses.add("(" + field + ".src == " + a + " && " + field + ".dst == " + b + ")");
            } else {
                clauses.add("(" + field + ".addr == " + a + " && " + field + ".addr == " + b + ")");
            }
        }
        return String.join(" || ", clauses);
    }

    private static final Comparator<String[]> PAIR_ORDER = Comparator.<String[], String>comparing(p -> p[0])
            .thenComparing(p -> p[1]);

    private static List<String> key(String[] p) {
        return List.of(p[0], p[1]);
    }

    /** {@code _prefilter_candidates}: frame counts per index and the indices of the large captures. */
    static Map.Entry<Map<Integer, Long>, Set<Integer>> prefilterCandidates(List<Path> paths, Cancellation cancel) {
        Map<Integer, Long> counts = new LinkedHashMap<>();
        for (int i = 0; i < paths.size(); i++) {
            try {
                Map<String, Object> info = CaptureMetadata.read(paths.get(i), cancel);
                Object total = info.get("frame_limit") != null ? info.get("frame_limit")
                        : info.getOrDefault("complete_records", 0L);
                if (total instanceof Long t && t > 0) {
                    counts.put(i, t);
                }
            } catch (Exception e) {
                continue;
            }
        }
        List<long[]> ordered = counts.entrySet().stream().map(e -> new long[] {e.getValue(), e.getKey()})
                .sorted(Comparator.<long[]>comparingLong(x -> x[0]).thenComparingLong(x -> x[1])).toList();
        if (ordered.size() < 2) {
            return Map.entry(counts, Set.of());
        }
        long baselineMax = ordered.get(0)[0];
        for (long[] c : ordered) {
            if (c[0] <= 5 * baselineMax) {
                baselineMax = c[0];
            }
        }
        Set<Integer> large = new HashSet<>();
        for (long[] c : ordered) {
            if (c[0] > 5 * baselineMax) {
                large.add((int) c[1]);
            }
        }
        return Map.entry(counts, large);
    }

    /** {@code _scan_ip_pairs}: total frames and frames per unordered IP pair. */
    static Map.Entry<Long, Map<List<String>, Long>> scanIpPairs(Path path, String tshark, Cancellation cancel) {
        String binary = TsharkRunner.find(tshark);
        List<String> cmd = List.of(binary, "-n", "-r", path.toString(), "-o", "ip.defragment:FALSE",
                "-o", "ipv6.defragment:FALSE", "-T", "fields", "-E", "separator=/t", "-e", "ip.src", "-e", "ip.dst",
                "-e", "ipv6.src", "-e", "ipv6.dst");
        Map<List<String>, Long> pairCounts = new HashMap<>();
        long totalFrames = 0;
        try (TsharkRunner proc = TsharkRunner.start(cmd, null, null)) {
            BufferedReader lines = new BufferedReader(proc.stdout());
            String line;
            try {
                while ((line = lines.readLine()) != null) {
                    if (cancel != null && cancel.isSet()) {
                        throw new IngestCancelledException("File ingest cancelled");
                    }
                    totalFrames++;
                    String[] parts = line.split("\t", -1);
                    String ipS = firstOccurrence(parts, 0);
                    String ipD = firstOccurrence(parts, 1);
                    String ip6S = firstOccurrence(parts, 2);
                    String ip6D = firstOccurrence(parts, 3);
                    String src = ipS.isEmpty() ? ip6S : ipS;
                    String dst = ipD.isEmpty() ? ip6D : ipD;
                    if (!src.isEmpty() && !dst.isEmpty()) {
                        List<String> pair = src.compareTo(dst) <= 0 ? List.of(src, dst) : List.of(dst, src);
                        pairCounts.merge(pair, 1L, Long::sum);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            proc.waitFor();
        }
        return Map.entry(totalFrames, pairCounts);
    }

    private static String firstOccurrence(String[] parts, int i) {
        String v = i < parts.length ? parts[i] : "";
        int comma = v.indexOf(',');
        return comma < 0 ? v : v.substring(0, comma);
    }

    /** Ingest all files; returns capture ids by input position (normalised, de-duplicated). */
    public static List<String> ingestMany(ProjectStore project, List<Path> input, Settings settings,
            Cancellation cancel, Map<String, CancelFlag> fileCancels, CaptureIngest.Progress progress) {
        List<Path> paths = normalize(input);
        if (paths.isEmpty()) {
            return List.of();
        }
        int count = workerBudget(paths.size(), settings.cpuCores(), settings.availableRamBytes());
        if (!settings.parallel() && settings.workers() == null) {
            count = Math.min(count, 1);
        }
        if (settings.workers() != null) {
            if (settings.workers() < 1) {
                throw new IllegalArgumentException("Worker override must be positive");
            }
            count = Math.min(count, settings.workers());
        }
        Cancellation global = cancel == null ? Cancellation.NONE : cancel;
        CaptureIngest.Progress sink = progress == null ? CaptureIngest.Progress.NONE : progress;
        Map<String, CancelFlag> perFile = fileCancels == null ? new HashMap<>() : fileCancels;
        for (int i = 0; i < paths.size(); i++) {
            perFile.putIfAbsent(Integer.toString(i), new CancelFlag());
        }
        List<Map<String, Object>> states = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("file_id", Integer.toString(i));
            s.put("file", paths.get(i).getFileName().toString());
            s.put("path", paths.get(i).toString());
            s.put("state", "queued");
            s.put("frames", 0L);
            states.add(s);
        }
        final int workers = count;
        Object lock = new Object();
        Updater update = (i, values) -> {
            synchronized (lock) {
                states.get(i).putAll(values);
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("state", "ingesting");
                event.put("workers", (long) workers);
                event.put("files", states.stream().map(LinkedHashMap::new).collect(Collectors.toList()));
                event.put("frames", states.stream().mapToLong(s -> s.get("frames") instanceof Number n
                        ? n.longValue() : 0L).sum());
                event.put("file_count", (long) paths.size());
                sink.update(event);
            }
        };
        String[] result = new String[paths.size()];
        List<Integer> errors = new ArrayList<>();
        List<Integer> cancelled = new ArrayList<>();
        Map.Entry<Map<Integer, Long>, Set<Integer>> candidates = settings.prefilter() && paths.size() >= 2
                ? prefilterCandidates(paths, global) : Map.entry(Map.of(), Set.of());
        Map<Integer, Long> counts = candidates.getKey();
        Set<Integer> large = candidates.getValue();
        List<Integer> first = new ArrayList<>();
        List<Integer> second = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            (large.contains(i) ? second : first).add(i);
        }
        Batch batch = new Batch(project, paths, settings.options(), global, perFile, update, result, errors,
                cancelled, workers, new java.util.concurrent.atomic.AtomicReference<>());
        batch.execute(first, i -> settings.options());
        if (!second.isEmpty() && !global.isSet()) {
            Map<Integer, Set<List<String>>> pairsByIndex = new HashMap<>();
            try (Db db = project.connect()) {
                for (int i : first) {
                    if (result[i] != null) {
                        Set<List<String>> pairs = new HashSet<>();
                        for (Object[] row : db.query(Sql.load("ingest/ip_pairs"), result[i])) {
                            pairs.add(List.of((String) row[0], (String) row[1]));
                        }
                        pairsByIndex.put(i, pairs);
                    }
                }
            }
            Map<Integer, Map.Entry<Long, Map<List<String>, Long>>> scanned = new HashMap<>();
            if (second.size() >= 2) {
                for (int i : second) {
                    if (global.isSet()) {
                        break;
                    }
                    Cancellation token = Cancellation.either(global, perFile.get(Integer.toString(i)));
                    Map.Entry<Long, Map<List<String>, Long>> scan = scanIpPairs(paths.get(i),
                            settings.options().tshark(), token);
                    scanned.put(i, scan);
                    pairsByIndex.put(i, new HashSet<>(scan.getValue().keySet()));
                }
            }
            Map<Integer, IngestOptions> extraByIndex = new HashMap<>();
            for (int i : second) {
                Set<List<String>> refPairs = new HashSet<>();
                for (int j = 0; j < paths.size(); j++) {
                    if (j != i) {
                        refPairs.addAll(pairsByIndex.getOrDefault(j, Set.of()));
                    }
                }
                Long totalI = counts.get(i);
                IngestOptions base = settings.options();
                if (scanned.containsKey(i)) {
                    Map.Entry<Long, Map<List<String>, Long>> scan = scanned.get(i);
                    if (totalI == null || totalI == 0) {
                        totalI = scan.getKey();
                    }
                    Set<List<String>> matched = new TreeSet<>(Comparator.<List<String>, String>comparing(p -> p.get(0))
                            .thenComparing(p -> p.get(1)));
                    for (List<String> p : scan.getValue().keySet()) {
                        if (refPairs.contains(p)) {
                            matched.add(p);
                        }
                    }
                    long matchedFrames = matched.stream().mapToLong(p -> scan.getValue().get(p)).sum();
                    if (totalI != 0 && matchedFrames * 10 < totalI) {
                        extraByIndex.put(i, base.withPrefilter(null, totalI,
                                "Ingest pre-filter skipped: reference IP pairs matched " + matchedFrames + " of "
                                        + totalI + " frames (<10%); kept all frames"));
                    } else if (totalI != 0 && matchedFrames >= totalI) {
                        extraByIndex.put(i, base);
                    } else {
                        extraByIndex.put(i, base.withPrefilter(ipPairFilter(matched.stream()
                                .map(p -> new String[] {p.get(0), p.get(1)}).toList()), totalI, null));
                    }
                } else if (refPairs.isEmpty()) {
                    extraByIndex.put(i, base.withPrefilter(null, totalI,
                            "Ingest pre-filter skipped: reference IP pairs matched 0 of " + (totalI == null ? 0
                                    : totalI) + " frames (<10%); kept all frames"));
                } else {
                    List<String[]> sorted = refPairs.stream().map(p -> new String[] {p.get(0), p.get(1)})
                            .sorted(PAIR_ORDER).toList();
                    extraByIndex.put(i, base.withPrefilter(ipPairFilter(sorted), totalI, null));
                }
            }
            batch.execute(second, i -> extraByIndex.getOrDefault(i, settings.options()));
        }
        if (batch.memoryFailure().get() != null) {
            // A capture that failed at the memory limit is not "a file that failed": the run stops with its reason.
            throw new AnalysisLimitException(AnalysisFailure.MEMORY_LIMIT, AnalysisFailure.MEMORY_LIMIT_MESSAGE,
                    batch.memoryFailure().get());
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(errors.size()
                    + " file(s) failed; see per-file status. Completed captures are retained.");
        }
        if (!cancelled.isEmpty()) {
            throw new IngestCancelledException(cancelled.size()
                    + " file(s) cancelled; completed captures are retained.");
        }
        return Arrays.asList(result);
    }

    @FunctionalInterface
    private interface Updater {
        void update(int index, Map<String, Object> values);
    }

    private record Batch(ProjectStore project, List<Path> paths, IngestOptions options, Cancellation global,
            Map<String, CancelFlag> perFile, Updater update, String[] result, List<Integer> errors,
            List<Integer> cancelled, int workers, java.util.concurrent.atomic.AtomicReference<Throwable> memoryFailure) {

        String run(int i, IngestOptions o, CaptureIngest.Turn turn) {
            Cancellation token = Cancellation.either(global, perFile.get(Integer.toString(i)));
            try {
                if (token.isSet()) {
                    throw new IngestCancelledException("File ingest cancelled");
                }
                String cid = CaptureIngest.ingest(project, paths.get(i), o, token, v -> update.update(i, v), turn);
                update.update(i, Map.of("capture_id", cid));
                return cid;
            } catch (RuntimeException e) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("state", e instanceof IngestCancelledException ? "cancelled" : "error");
                v.put("error", e.getMessage() == null ? "" : e.getMessage());
                update.update(i, v);
                throw e;
            }
        }

        void execute(List<Integer> indices, IntFunction<IngestOptions> optionsFor) {
            if (indices.isEmpty()) {
                return;
            }
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(workers, indices.size()), r -> {
                Thread t = new Thread(r, "capture-ingest");
                t.setDaemon(true);
                return t;
            });
            try {
                Map<Integer, Future<String>> futures = new LinkedHashMap<>();
                // Registration turns in input order (CaptureIngest.Turn): the pool is FIFO, so every file's
                // predecessor has started (or finished) when the file waits for it.
                CountDownLatch previous = null;
                for (int i : indices) {
                    IngestOptions o = optionsFor.apply(i);
                    CountDownLatch before = previous;
                    CountDownLatch mine = new CountDownLatch(1);
                    previous = mine;
                    CaptureIngest.Turn turn = new CaptureIngest.Turn() {
                        @Override
                        public void await() {
                            if (before == null) {
                                return;
                            }
                            try {
                                before.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IngestCancelledException("Ingest cancelled");
                            }
                        }

                        @Override
                        public void done() {
                            mine.countDown();
                        }
                    };
                    futures.put(i, pool.submit(() -> {
                        try {
                            return run(i, o, turn);
                        } finally {
                            turn.done();
                        }
                    }));
                }
                for (Map.Entry<Integer, Future<String>> f : futures.entrySet()) {
                    try {
                        result[f.getKey()] = f.getValue().get();
                    } catch (ExecutionException e) {
                        synchronized (errors) {
                            if (e.getCause() instanceof IngestCancelledException) {
                                cancelled.add(f.getKey());
                            } else {
                                errors.add(f.getKey());
                                if (AnalysisFailure.isMemoryLimit(e.getCause())) {
                                    memoryFailure.compareAndSet(null, e.getCause());
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IngestCancelledException("Ingest cancelled");
                    }
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
