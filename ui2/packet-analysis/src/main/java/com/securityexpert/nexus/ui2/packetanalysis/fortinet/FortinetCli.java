package com.securityexpert.nexus.ui2.packetanalysis.fortinet;

import com.securityexpert.nexus.ui2.packetanalysis.engine.report.PyJsonWriter;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.Cancellation;
import com.securityexpert.nexus.ui2.packetanalysis.ingest.CaptureIngest;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * CLI entry point for {@code packetbreaker import-fortinet}:
 * {@code FortinetCli --project <dir> --text <path> [--result <json>] [--start-time <str>] [--device <str>]}.
 */
public final class FortinetCli {
    private FortinetCli() {
    }

    public static void main(String[] args) {
        Path projectPath = null;
        Path textPath = null;
        Path resultPath = null;
        String startTime = null;
        String device = "FortiGate";
        for (int i = 0; i < args.length; i += 2) {
            switch (args[i]) {
                case "--project" -> projectPath = Path.of(args[i + 1]);
                case "--text" -> textPath = Path.of(args[i + 1]);
                case "--result" -> resultPath = Path.of(args[i + 1]);
                case "--start-time" -> startTime = args[i + 1];
                case "--device" -> device = args[i + 1];
                default -> throw new IllegalArgumentException("Unknown option " + args[i]);
            }
        }
        if (projectPath == null || textPath == null) {
            throw new IllegalArgumentException("--project and --text are required");
        }
        try {
            ProjectStore project = ProjectStore.open(projectPath, ProjectStore.Settings.defaults(null));
            Map<String, Object> result = Fortinet.importText(project, textPath, startTime, device,
                    Cancellation.NONE, CaptureIngest.Progress.NONE);
            String json = PyJsonWriter.dumps(result, true);
            if (resultPath != null) {
                Files.writeString(resultPath, json, StandardCharsets.UTF_8);
            } else {
                System.out.println(json);
            }
        } catch (Exception e) {
            System.err.println(e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        }
    }
}
