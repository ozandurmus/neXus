package com.securityexpert.nexus.ui2.cli;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CLI parity for the Data Sanitizer screen (docs/design/DATA_SANITIZER_CONTRACT.md §3.1; backlog
 * {@code cli_parity_for_every_ui_action}): the same routes, session cookie and CSRF token as the screen. Uploads are
 * streamed from disk as one multipart part; downloads are streamed to disk. A result can be downloaded once.
 */
final class SanitizerCli {

    static final String BASE = "/api/v2/sanitize";
    private static final Pattern OPAQUE_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    static long pollMillis = 1000;

    private final NexusSessionCli cli;

    SanitizerCli(NexusSessionCli cli) {
        this.cli = cli;
    }

    static void printUsage() {
        System.out.println("  sanitize <file> [dest]                         pcap/pcapng/log/txt/csv/xlsx/json; 2 GB; waits, then downloads to dest");
        System.out.println("  sanitize-job <jobId> | sanitize-download <jobId> <dest>   counts per kind; one download, then the file is gone");
        System.out.println("  sanitize-revert <file> [dest] [--job <jobId>|--all]       originals back for the vault's tokens");
        System.out.println("  sanitize-revert-job <revertId> | sanitize-revert-download <revertId> <dest>");
    }

    /** @return exit code, or -1 when {@code args[0]} is not a sanitizer command */
    int run(String[] args) throws IOException, InterruptedException {
        return switch (args[0]) {
            case "sanitize" -> {
                require(args, 2, "sanitize <file> [dest]");
                yield submit(BASE, Path.of(args[1]), Map.of(), "job_id", BASE + "/jobs/", args.length > 2 ? Path.of(args[2]) : null);
            }
            case "sanitize-job" -> {
                require(args, 2, "sanitize-job <jobId>");
                yield print(cli.call("GET", BASE + "/jobs/" + id(args[1]), null));
            }
            case "sanitize-download" -> {
                require(args, 3, "sanitize-download <jobId> <dest>");
                yield download(BASE + "/jobs/" + id(args[1]) + "/download", Path.of(args[2]));
            }
            case "sanitize-revert" -> {
                require(args, 2, "sanitize-revert <file> [dest] [--job <jobId>|--all]");
                Map<String, String> fields = new LinkedHashMap<>();
                Path dest = null;
                for (int i = 2; i < args.length; i++) {
                    if (args[i].equals("--job") && i + 1 < args.length) fields.put("source_job_id", id(args[++i]));
                    else if (args[i].equals("--all")) fields.put("scope", "all");
                    else if (dest == null && !args[i].startsWith("--")) dest = Path.of(args[i]);
                    else throw new IllegalArgumentException("usage: sanitize-revert <file> [dest] [--job <jobId>|--all]");
                }
                yield submit(BASE + "/revert", Path.of(args[1]), fields, "revert_id", BASE + "/reverts/", dest);
            }
            case "sanitize-revert-job" -> {
                require(args, 2, "sanitize-revert-job <revertId>");
                yield print(cli.call("GET", BASE + "/reverts/" + id(args[1]), null));
            }
            case "sanitize-revert-download" -> {
                require(args, 3, "sanitize-revert-download <revertId> <dest>");
                yield download(BASE + "/reverts/" + id(args[1]) + "/download", Path.of(args[2]));
            }
            default -> -1;
        };
    }

    static String id(String value) {
        if (!OPAQUE_ID.matcher(value).matches()) throw new IllegalArgumentException("identifiers are opaque UUIDs");
        return value;
    }

    private static void require(String[] args, int count, String usage) {
        if (args.length < count) throw new IllegalArgumentException("usage: " + usage);
    }

    /** Uploads, waits for the job to end, prints its view, and downloads the result when {@code dest} is given. */
    private int submit(String route, Path file, Map<String, String> fields, String idField, String statusRoute, Path dest)
            throws IOException, InterruptedException {
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("no such file: " + file);
        NexusSessionCli.Session session = cli.loadSession().orElseThrow(() -> new IllegalArgumentException("not logged in"));
        String boundary = "nexus-cli-" + java.util.UUID.randomUUID();
        HttpRequest request = HttpRequest.newBuilder(URI.create(session.baseUrl() + route))
                .header("Cookie", NexusSessionCli.SESSION_COOKIE + "=" + session.cookie())
                .header("X-CSRF-Token", session.csrf())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(multipart(file, fields, boundary)).build();
        System.err.println("uploading " + Files.size(file) + " bytes");
        HttpResponse<byte[]> accepted = cli.client().send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (accepted.statusCode() / 100 != 2) return print(accepted);
        String id = NexusSessionCli.jsonString(new String(accepted.body(), StandardCharsets.UTF_8), idField).orElse("");
        HttpResponse<byte[]> status;
        while (true) {
            status = cli.call("GET", statusRoute + id(id), null);
            String state = NexusSessionCli.jsonString(new String(status.body(), StandardCharsets.UTF_8), "state").orElse("");
            if (status.statusCode() / 100 != 2 || !state.equals("RUNNING")) break;
            Thread.sleep(pollMillis);
        }
        int code = print(status);
        if (code != 0 || dest == null) return code;
        if (!NexusSessionCli.jsonString(new String(status.body(), StandardCharsets.UTF_8), "state").orElse("").equals("SUCCEEDED")) return 1;
        return download(statusRoute + id + "/download", dest);
    }

    /** Form fields first, then the file part, streamed from disk. */
    static HttpRequest.BodyPublisher multipart(Path file, Map<String, String> fields, String boundary) throws IOException {
        StringBuilder head = new StringBuilder();
        for (Map.Entry<String, String> f : fields.entrySet()) {
            head.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(f.getKey())
                    .append("\"\r\n\r\n").append(f.getValue()).append("\r\n");
        }
        String name = file.getFileName().toString().replace("\"", "_").replace("\r", "_").replace("\n", "_");
        head.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"file\"; filename=\"").append(name)
                .append("\"\r\nContent-Type: application/octet-stream\r\n\r\n");
        List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
        parts.add(HttpRequest.BodyPublishers.ofString(head.toString(), StandardCharsets.UTF_8));
        parts.add(HttpRequest.BodyPublishers.ofFile(file));
        parts.add(HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n", StandardCharsets.UTF_8));
        return HttpRequest.BodyPublishers.concat(parts.toArray(new HttpRequest.BodyPublisher[0]));
    }

    private int download(String route, Path dest) throws IOException, InterruptedException {
        NexusSessionCli.Session session = cli.loadSession().orElseThrow(() -> new IllegalArgumentException("not logged in"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(session.baseUrl() + route))
                .header("Cookie", NexusSessionCli.SESSION_COOKIE + "=" + session.cookie()).GET().build();
        HttpResponse<InputStream> response = cli.client().send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try (InputStream in = response.body()) {
                byte[] body = in.readNBytes(2000);
                System.err.println("REFUSED: HTTP " + response.statusCode() + " " + new String(body, StandardCharsets.UTF_8).strip());
            }
            return response.statusCode() == 401 || response.statusCode() == 403 ? 3 : 1;
        }
        try (InputStream in = response.body()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("downloaded " + Files.size(dest) + " bytes to " + dest + " (the server kept no copy)");
        return 0;
    }

    private static int print(HttpResponse<byte[]> response) {
        String body = new String(response.body(), StandardCharsets.UTF_8);
        if (response.statusCode() / 100 == 2) {
            System.out.println(body);
            return 0;
        }
        System.err.println("HTTP " + response.statusCode() + ": " + body);
        return response.statusCode() == 401 || response.statusCode() == 403 ? 3 : 1;
    }
}
