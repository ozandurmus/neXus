package com.securityexpert.nexus.ui2.cli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * CLI parity for the pre-cutover impact analysis (ACI segmentation design section 4.7, acceptance criterion ACI-16):
 * the same routes, session cookie and CSRF token as the screen. Uploads are streamed from disk, spec first, then the
 * files in the order of the spec's mappings.
 */
final class CutoverCli {

    static final String BASE = "/api/v2/cutover";
    private final NexusSessionCli cli;

    CutoverCli(NexusSessionCli cli) {
        this.cli = cli;
    }

    static void printUsage() {
        System.out.println("  cutover-segments | cutover-segment <segmentId>              segments; one segment with its assessments");
        System.out.println("  cutover-segment-create <name> <blocks> <peerBlocks>         blocks are comma-separated IPv4 CIDR");
        System.out.println("  cutover-segment-update <segmentId> <revision> <name> <blocks> <peerBlocks>");
        System.out.println("  cutover-segment-delete <segmentId>");
        System.out.println("  cutover-assess <segmentId> <spec.json> <file.csv> [file.csv ...]   one mapping per file in the spec");
        System.out.println("  cutover-assessment <assessmentId> | cutover-assessment-delete <assessmentId>");
        System.out.println("  cutover-flows <assessmentId> [FLOW|MISSING_RULE] [decision|-] [page]");
    }

    /** @return exit code, or -1 when {@code args[0]} is not a cutover command */
    int run(String[] args) throws IOException, InterruptedException {
        return switch (args[0]) {
            case "cutover-segments" -> NexusSessionCli.print(cli.call("GET", BASE + "/segments", null));
            case "cutover-segment" -> {
                require(args, 2, "cutover-segment <segmentId>");
                yield NexusSessionCli.print(cli.call("GET", BASE + "/segments/" + SanitizerCli.id(args[1]), null));
            }
            case "cutover-segment-create" -> {
                require(args, 4, "cutover-segment-create <name> <blocks> <peerBlocks>");
                yield NexusSessionCli.print(cli.call("POST", BASE + "/segments", segment(args[1], args[2], args[3], null)));
            }
            case "cutover-segment-update" -> {
                require(args, 6, "cutover-segment-update <segmentId> <revision> <name> <blocks> <peerBlocks>");
                if (!args[2].matches("[1-9][0-9]{0,8}")) throw new IllegalArgumentException("usage: revision is a positive number");
                yield NexusSessionCli.print(cli.call("PUT", BASE + "/segments/" + SanitizerCli.id(args[1]), segment(args[3], args[4], args[5], args[2])));
            }
            case "cutover-segment-delete" -> {
                require(args, 2, "cutover-segment-delete <segmentId>");
                yield NexusSessionCli.print(cli.call("DELETE", BASE + "/segments/" + SanitizerCli.id(args[1]), null));
            }
            case "cutover-assess" -> assess(args);
            case "cutover-assessment" -> {
                require(args, 2, "cutover-assessment <assessmentId>");
                yield NexusSessionCli.print(cli.call("GET", BASE + "/assessments/" + SanitizerCli.id(args[1]), null));
            }
            case "cutover-assessment-delete" -> {
                require(args, 2, "cutover-assessment-delete <assessmentId>");
                yield NexusSessionCli.print(cli.call("DELETE", BASE + "/assessments/" + SanitizerCli.id(args[1]), null));
            }
            case "cutover-flows" -> {
                require(args, 2, "cutover-flows <assessmentId> [FLOW|MISSING_RULE] [decision|-] [page]");
                String kind = args.length > 2 ? args[2] : "FLOW";
                if (!Set.of("FLOW", "MISSING_RULE").contains(kind)) throw new IllegalArgumentException("usage: kind is FLOW or MISSING_RULE");
                String decision = args.length > 3 && !args[3].equals("-") ? args[3] : null;
                if (decision != null && !Set.of("WOULD_ALLOW", "WOULD_DROP", "NOT_DECIDED", "NOT_EVALUABLE").contains(decision))
                    throw new IllegalArgumentException("usage: decision is WOULD_ALLOW, WOULD_DROP, NOT_DECIDED, NOT_EVALUABLE or -");
                String page = args.length > 4 ? args[4] : "0";
                if (!page.matches("[0-9]{1,5}")) throw new IllegalArgumentException("usage: page is a number");
                yield NexusSessionCli.print(cli.call("GET", BASE + "/assessments/" + SanitizerCli.id(args[1]) + "/flows?kind=" + kind
                        + (decision == null ? "" : "&decision=" + decision) + "&page=" + page, null));
            }
            default -> -1;
        };
    }

    private static void require(String[] args, int count, String usage) {
        if (args.length < count) throw new IllegalArgumentException("usage: " + usage);
    }

    /** A segment body; block lists are validated by the server, never corrected here. */
    static String segment(String name, String blocks, String peers, String revision) {
        return "{" + (revision == null ? "" : "\"revision\":" + revision + ",") + "\"name\":" + quote(name)
                + ",\"blocks\":" + list(blocks) + ",\"peerBlocks\":" + list(peers) + "}";
    }

    private static String list(String commaSeparated) {
        List<String> out = new ArrayList<>();
        for (String value : commaSeparated.split(",")) if (!value.isBlank()) out.add(quote(value.strip()));
        return "[" + String.join(",", out) + "]";
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    private int assess(String[] args) throws IOException, InterruptedException {
        require(args, 4, "cutover-assess <segmentId> <spec.json> <file.csv> [file.csv ...]");
        String segment = SanitizerCli.id(args[1]);
        Path spec = Path.of(args[2]);
        if (!Files.isRegularFile(spec)) throw new IllegalArgumentException("usage: no such spec file: " + spec);
        List<Path> files = new ArrayList<>();
        for (int i = 3; i < args.length; i++) {
            Path file = Path.of(args[i]);
            if (!Files.isRegularFile(file)) throw new IllegalArgumentException("usage: no such file: " + file);
            files.add(file);
        }
        NexusSessionCli.Session session = cli.loadSession().orElseThrow(() -> new IllegalArgumentException("usage: not logged in"));
        String boundary = "nexus-cli-" + java.util.UUID.randomUUID();
        HttpRequest request = HttpRequest.newBuilder(URI.create(session.baseUrl() + BASE + "/segments/" + segment + "/assessments"))
                .header("Cookie", NexusSessionCli.SESSION_COOKIE + "=" + session.cookie())
                .header("X-CSRF-Token", session.csrf())
                .header("Origin", NexusSessionCli.originOf(session.baseUrl()))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(multipart(Files.readString(spec), files, boundary)).build();
        System.err.println("uploading " + files.size() + " file(s)");
        return NexusSessionCli.print(cli.client().send(request, HttpResponse.BodyHandlers.ofByteArray()));
    }

    /** The spec field first, then one file part per file, each streamed from disk. */
    static HttpRequest.BodyPublisher multipart(String spec, List<Path> files, String boundary) throws IOException {
        List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
        parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\nContent-Disposition: form-data; name=\"spec\"\r\n\r\n" + spec + "\r\n",
                StandardCharsets.UTF_8));
        for (Path file : files) {
            String name = file.getFileName().toString().replace("\"", "_").replace("\r", "_").replace("\n", "_");
            parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
                    + "\"\r\nContent-Type: text/csv\r\n\r\n", StandardCharsets.UTF_8));
            parts.add(HttpRequest.BodyPublishers.ofFile(file));
            parts.add(HttpRequest.BodyPublishers.ofString("\r\n", StandardCharsets.UTF_8));
        }
        parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "--\r\n", StandardCharsets.UTF_8));
        return HttpRequest.BodyPublishers.concat(parts.toArray(new HttpRequest.BodyPublisher[0]));
    }
}
