package com.securityexpert.nexus.ui2.packetanalysis.export;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * CLI entry point for the expert report on a local case project:
 * {@code ExpertReportCli --project <path> --output <file.xlsx> [--window-start <epoch>] [--window-end <epoch>] [--top <n>]
 * [--threads <n>] [--title <case title>] [--telemetry <file> [--telemetry-zone <id>] [--telemetry-offset <sec>] [--telemetry-start <local time>]]...}.
 * Each {@code --telemetry} names a host telemetry text file; the three options after it set its clock alignment (without
 * a zone the file is read as UTC and the report says so). The neXus CLI command {@code pa-expert-report} downloads the
 * same workbook through the service.
 */
public final class ExpertReportCli {
    private ExpertReportCli() {
    }

    public static void main(String[] args) {
        try {
            run(System.out, args);
        } catch (Exception e) {
            System.err.println(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            System.exit(1);
        }
    }

    public static void run(PrintStream out, String... args) throws IOException {
        Path project = null;
        Path output = null;
        Double start = null;
        Double end = null;
        Integer threads = null;
        String title = null;
        int top = ExpertReportOptions.DEFAULT_TOP_CYCLES;
        java.util.List<String[]> telemetry = new java.util.ArrayList<>(); // path, zone, offset, start
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for option " + args[i]);
            }
            switch (args[i]) {
                case "--project" -> project = Path.of(args[i + 1]);
                case "--output", "-o" -> output = Path.of(args[i + 1]);
                case "--window-start" -> start = Double.valueOf(args[i + 1]);
                case "--window-end" -> end = Double.valueOf(args[i + 1]);
                case "--top" -> top = Integer.parseInt(args[i + 1]);
                case "--threads" -> threads = Integer.valueOf(args[i + 1]);
                case "--title" -> title = args[i + 1];
                case "--telemetry" -> telemetry.add(new String[] {args[i + 1], null, "0", null});
                case "--telemetry-zone", "--telemetry-offset", "--telemetry-start" -> {
                    if (telemetry.isEmpty()) {
                        throw new IllegalArgumentException(args[i] + " needs a --telemetry before it");
                    }
                    telemetry.get(telemetry.size() - 1)[args[i].equals("--telemetry-zone") ? 1 : args[i].equals("--telemetry-offset") ? 2 : 3] = args[i + 1];
                }
                default -> throw new IllegalArgumentException("Unknown option " + args[i]);
            }
        }
        if (project == null || output == null) {
            throw new IllegalArgumentException("--project and --output are required");
        }
        java.util.List<ExpertReportOptions.HostTelemetry> files = new java.util.ArrayList<>();
        for (String[] t : telemetry) {
            Path file = Path.of(t[0]);
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("No such telemetry file: " + file);
            }
            String sha = java.util.HexFormat.of().formatHex(sha256(file));
            files.add(new ExpertReportOptions.HostTelemetry(sha.substring(0, 32), file.getFileName().toString(), Files.size(file), sha,
                    t[1], Integer.parseInt(t[2]), t[3], () -> Files.newInputStream(file)));
        }
        ExpertReportOptions options = new ExpertReportOptions(start, end, top, ReportMasker.NONE, null, files, title);
        byte[] workbook = ExpertReport.generate(project, options, threads);
        Files.write(output, workbook, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        out.println("Exported " + output);
    }

    private static byte[] sha256(Path file) throws IOException {
        try (java.io.InputStream in = Files.newInputStream(file)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                digest.update(buffer, 0, n);
            }
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
