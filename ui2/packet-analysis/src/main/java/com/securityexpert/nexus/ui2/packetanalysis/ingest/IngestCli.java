package com.securityexpert.nexus.ui2.packetanalysis.ingest;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyJson;
import com.securityexpert.nexus.ui2.packetanalysis.store.MemoryBudget;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Command-line ingest of capture files into a project directory (parity runs and local use).
 *
 * <pre>
 * IngestCli --project DIR [--tshark PATH] [--memory-limit-mb N] [--total-memory-mb N] [--threads N]
 *           [--available-ram-mb N] [--parallel] [--workers N] [--prefilter] [--batch-size N]
 *           [--timeout-seconds N] INPUT...
 * </pre>
 *
 * <p>Each INPUT is a capture file or a folder (every capture below it, in path order). Prints one JSON object with
 * the project path and the capture id per file; exit status 1 when any file failed.
 */
public final class IngestCli {

    private IngestCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Path project = null;
        String tshark = null;
        Integer memoryLimit = null;
        Long totalMemory = null;
        Integer threads = null;
        Long availableRam = null;
        boolean parallel = false;
        Integer workers = null;
        boolean prefilter = false;
        int batchSize = IngestOptions.defaults().batchSize();
        Duration timeout = null;
        List<Path> inputs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--project" -> project = Path.of(args[++i]);
                case "--tshark" -> tshark = args[++i];
                case "--memory-limit-mb" -> memoryLimit = Integer.parseInt(args[++i]);
                case "--total-memory-mb" -> totalMemory = Long.parseLong(args[++i]) * 1024 * 1024;
                case "--threads" -> threads = Integer.parseInt(args[++i]);
                case "--available-ram-mb" -> availableRam = Long.parseLong(args[++i]) * 1024 * 1024;
                case "--parallel" -> parallel = true;
                case "--workers" -> workers = Integer.parseInt(args[++i]);
                case "--prefilter" -> prefilter = true;
                case "--batch-size" -> batchSize = Integer.parseInt(args[++i]);
                case "--timeout-seconds" -> timeout = Duration.ofSeconds(Long.parseLong(args[++i]));
                default -> {
                    if (a.startsWith("--")) {
                        System.err.println("unknown option " + a);
                        return 2;
                    }
                    inputs.add(Path.of(a));
                }
            }
        }
        if (project == null || inputs.isEmpty()) {
            System.err.println("usage: IngestCli --project DIR [options] INPUT...");
            return 2;
        }
        List<Path> files = new ArrayList<>();
        for (Path p : inputs) {
            if (Files.isDirectory(p)) {
                files.addAll(BatchIngest.capturePaths(p));
            } else {
                files.add(p);
            }
        }
        long total = totalMemory != null ? totalMemory : MemoryBudget.systemTotalMemoryBytes();
        int cpus = Runtime.getRuntime().availableProcessors();
        ProjectStore store = ProjectStore.open(project, new ProjectStore.Settings(memoryLimit, total,
                threads != null ? threads : MemoryBudget.defaultThreads(cpus)));
        IngestOptions options = IngestOptions.defaults().withTshark(tshark).withBatchSize(batchSize)
                .withTimeout(timeout);
        BatchIngest.Settings settings = new BatchIngest.Settings(options, parallel, workers, prefilter, cpus,
                availableRam != null ? availableRam : total);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", store.path().toString());
        int status = 0;
        List<String> ids;
        try {
            ids = BatchIngest.ingestMany(store, files, settings, null, null, null);
        } catch (RuntimeException e) {
            out.put("error", e.getMessage());
            ids = null;
            status = 1;
        }
        List<Object> captures = new ArrayList<>();
        List<Path> normalized = BatchIngest.normalize(files);
        for (int i = 0; i < normalized.size(); i++) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("file", normalized.get(i).getFileName().toString());
            c.put("capture_id", ids == null ? null : ids.get(i));
            captures.add(c);
        }
        out.put("captures", captures);
        System.out.println(PyJson.dumps(out));
        return status;
    }
}
