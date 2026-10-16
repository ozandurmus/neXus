package com.securityexpert.nexus.ui2.packetanalysis.topology;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parity entry point for the topology stage on an ingested {@code project.duckdb}:
 *
 * <pre>
 *   suggest  --project DIR                  topology suggestion
 *   assemble --project DIR                  assembly with the saved topology as the base
 *   case     --project DIR --folder FOLDER  case scaffold + auto assembly; prints case_context and overview
 *   confirm  --project DIR [--group NAME]   confirm the pending suggestion
 *   overview --project DIR                  inventory preflight
 * </pre>
 *
 * The core analysis is not part of this stage: the case command hands assembled paths to an analyzer that returns
 * an empty report, so stored state matches a reference run whose analysis is stubbed the same way.
 * Output is {@code json.dumps(result, indent=2)} text.
 */
public final class TopologyMain {

    private TopologyMain() {
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            usage();
        }
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                usage();
            }
            opts.put(args[i].substring(2), args[i + 1]);
        }
        if (!opts.containsKey("project")) {
            usage();
        }
        ProjectDb project = new ProjectDb(Path.of(opts.get("project")));
        Object result;
        switch (args[0]) {
            case "suggest" -> result = Suggest.suggestTopology(project);
            case "assemble" -> result = Assemble.assembleTopology(project, null);
            case "overview" -> result = CaseSetup.inventoryOverview(project.inventory());
            case "case" -> {
                if (!opts.containsKey("folder")) {
                    usage();
                }
                CaseSetup.createCase(project, Path.of(opts.get("folder")), CaseSetup.IngestOutcome.clean(), true, true,
                        (p, topology) -> new LinkedHashMap<>());
                Object context;
                try (ProjectDb.Db db = project.connect()) {
                    context = db.get("case_context", null);
                }
                result = Py.dict("case_context", context, "overview", CaseSetup.inventoryOverview(project.inventory()));
            }
            case "confirm" -> {
                Map<String, Object> r = new LinkedHashMap<>();
                try {
                    r.put("ok", Assemble.confirmSuggestedTopology(project, opts.get("group")));
                } catch (IllegalArgumentException e) {
                    r.put("error", e.getMessage());
                }
                result = r;
            }
            default -> {
                usage();
                return;
            }
        }
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        out.print(PyJson.dumps(result, 2));
        out.print("\n");
        out.flush();
    }

    private static void usage() {
        System.err.println("usage: TopologyMain suggest|assemble|case|confirm|overview --project DIR "
                + "[--folder FOLDER] [--group NAME]");
        System.exit(2);
    }
}
