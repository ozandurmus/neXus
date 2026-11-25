package com.securityexpert.nexus.ui2.cli;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CLI parity for {@code OfflineAnalysisController}, {@code PathAnalysisController}, {@code PathValidationController} and
 * {@code TopologyController} (standing directive {@code cli_parity_for_every_ui_action}): one named command per controller
 * route, using the same routes, session cookie, CSRF token and role gates as the screens. Query parameter names and request
 * bodies match the controllers verbatim; {@code -} for an optional query parameter means "not given" so the server's default
 * applies. The CLI never adds, renames or corrects a field and prints the server's answer verbatim, so the server's aiview
 * projection is the CLI's output.
 */
final class PathTopologyCli {

    private static final Pattern OPAQUE_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final String[] NONE = {};

    private enum BodyKind {
        NONE,
        JSON,
        MULTIPART_OFFLINE_CSV
    }

    private record Command(
            String name,
            String method,
            String route,
            String idLabel,
            String[] query,
            BodyKind bodyKind,
            String description) {

        int minArgs() {
            int base = 1 + (idLabel == null ? 0 : 1);
            return switch (bodyKind) {
                case NONE -> base;
                case JSON -> base + 1;
                case MULTIPART_OFFLINE_CSV -> base + 2;
            };
        }

        int maxArgs() {
            return bodyKind == BodyKind.NONE ? minArgs() + query.length : minArgs();
        }

        String usage() {
            StringBuilder out = new StringBuilder(name);
            if (idLabel != null) out.append(" <").append(idLabel).append('>');
            for (String q : query) out.append(" [").append(q).append(']');
            if (bodyKind == BodyKind.JSON) out.append(" <json|@file>");
            if (bodyKind == BodyKind.MULTIPART_OFFLINE_CSV) out.append(" <mappingJson|@file> <file.csv>");
            return out.toString();
        }
    }

    private static final List<Command> COMMANDS = List.of(
            new Command("policy-offline-analyses", "GET", "/api/v2/policy/policies/{id}/offline-analyses", "policyId",
                    new String[] {"page"}, BodyKind.NONE,
                    "saved offline CSV rule-usage analyses of a policy ('-' leaves page out)"),
            new Command("policy-offline-analysis", "GET", "/api/v2/policy/offline-analyses/{id}", "analysisId",
                    NONE, BodyKind.NONE,
                    "one saved offline CSV rule-usage analysis"),
            new Command("policy-offline-analysis-upload", "POST", "/api/v2/policy/policies/{id}/offline-analyses", "policyId",
                    NONE, BodyKind.MULTIPART_OFFLINE_CSV,
                    "upload a CSV sample and column mapping for offline policy analysis"),
            new Command("policy-offline-analysis-delete", "DELETE", "/api/v2/policy/offline-analyses/{id}", "analysisId",
                    NONE, BodyKind.NONE,
                    "delete a saved offline policy analysis"),
            new Command("path-query", "POST", "/api/v2/path-analysis/query", null,
                    NONE, BodyKind.JSON,
                    "Trace path query (body: src, dst, protocol, port[, ingress_node_id, ingress_interface_name, edge_mode])"),
            new Command("path-validate", "POST", "/api/v2/path-analysis/validate", null,
                    NONE, BodyKind.JSON,
                    "submit CLASS_0 hop validation for a Trace path query (body: query fields[, branch_id])"),
            new Command("path-validation", "GET", "/api/v2/path-analysis/validate/{id}", "validationId",
                    NONE, BodyKind.NONE,
                    "recorded hop outcomes of one Trace path validation"),
            new Command("topology", "GET", "/api/v2/topology", null,
                    NONE, BodyKind.NONE,
                    "held L3 path-analysis topology and latest snapshot header"),
            new Command("topology-overrides", "GET", "/api/v2/topology/overrides", null,
                    NONE, BodyKind.NONE,
                    "topology link overrides with their status against the held graph"),
            new Command("topology-override-add", "POST", "/api/v2/topology/overrides", null,
                    NONE, BodyKind.JSON,
                    "create a topology link override (security_admin; body: action, link_kind, a_*, b_*, reason_code[, assumed_subnet, note])"),
            new Command("topology-override-revoke", "POST", "/api/v2/topology/overrides/{id}/revoke", "overrideId",
                    NONE, BodyKind.NONE,
                    "revoke a topology link override (security_admin)"),
            new Command("topology-rebuild", "POST", "/api/v2/topology/rebuild", null,
                    NONE, BodyKind.NONE,
                    "rebuild the L3 topology from stored inventory (security_admin)"));

    private final NexusSessionCli cli;

    PathTopologyCli(NexusSessionCli cli) {
        this.cli = cli;
    }

    /** Command names, for the parity and collision tests. */
    static List<String> names() {
        return COMMANDS.stream().map(Command::name).toList();
    }

    static void printUsage() {
        for (Command command : COMMANDS) {
            System.out.println("  " + command.usage() + "   " + command.description());
        }
    }

    /** @return exit code, or -1 when {@code args[0]} is not one of this class's commands */
    int run(String[] args) throws IOException, InterruptedException {
        Command command = COMMANDS.stream().filter(c -> c.name().equals(args[0])).findFirst().orElse(null);
        if (command == null) return -1;
        if (args.length < command.minArgs() || args.length > command.maxArgs()) {
            throw new IllegalArgumentException("usage: " + command.usage());
        }
        int next = 1;
        String route = command.route();
        if (command.idLabel() != null) {
            route = route.replace("{id}", opaqueId(args[next++], command.usage()));
        }
        return switch (command.bodyKind()) {
            case NONE -> {
                List<String> query = new ArrayList<>();
                for (int i = 0; i < command.query().length && next < args.length; i++, next++) {
                    if (!args[next].equals("-")) {
                        query.add(command.query()[i] + "=" + URLEncoder.encode(args[next], StandardCharsets.UTF_8));
                    }
                }
                String fullPath = route + (query.isEmpty() ? "" : "?" + String.join("&", query));
                yield NexusSessionCli.print(cli.call(command.method(), fullPath, null));
            }
            case JSON -> NexusSessionCli.print(cli.call(command.method(), route, jsonBody(args[next], command.usage())));
            case MULTIPART_OFFLINE_CSV -> uploadOfflineAnalysis(route, args[next], args[next + 1], command.usage());
        };
    }

    private int uploadOfflineAnalysis(String route, String mappingArg, String csvArg, String usage)
            throws IOException, InterruptedException {
        String mapping = jsonBody(mappingArg, usage);
        Path csv = Path.of(csvArg);
        if (!Files.isRegularFile(csv)) {
            throw new IllegalArgumentException("usage: no such file: " + csv);
        }
        NexusSessionCli.Session session = cli.loadSession().orElseThrow(
                () -> new IllegalArgumentException("usage: not logged in: run 'login <baseUrl> <username>' first"));
        String boundary = "nexus-cli-" + UUID.randomUUID();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(session.baseUrl() + route))
                .header("Cookie", NexusSessionCli.SESSION_COOKIE + "=" + session.cookie())
                .header("X-CSRF-Token", session.csrf())
                .header("Origin", NexusSessionCli.originOf(session.baseUrl()))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (cli.businessHoursAck()) {
            request.header(NexusSessionCli.BUSINESS_HOURS_ACK_HEADER, "true");
        }
        HttpRequest built = request.POST(multipartOfflineCsv(mapping, csv, boundary)).build();
        return NexusSessionCli.print(cli.client().send(built, HttpResponse.BodyHandlers.ofByteArray()));
    }

    static HttpRequest.BodyPublisher multipartOfflineCsv(String mapping, Path csv, String boundary) throws IOException {
        String filename = csv.getFileName().toString().replace("\"", "_").replace("\r", "_").replace("\n", "_");
        return HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString("--" + boundary
                        + "\r\nContent-Disposition: form-data; name=\"mapping\"\r\n\r\n" + mapping + "\r\n--" + boundary
                        + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                        + "\"\r\nContent-Type: text/csv\r\n\r\n", StandardCharsets.UTF_8),
                HttpRequest.BodyPublishers.ofFile(csv),
                HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n", StandardCharsets.UTF_8));
    }

    private static String jsonBody(String argument, String usage) throws IOException {
        if (argument == null || argument.isBlank() || argument.equals("@")) {
            throw new IllegalArgumentException("usage: " + usage);
        }
        if (argument.startsWith("@")) {
            Path file = Path.of(argument.substring(1));
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("usage: no such file: " + file);
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        return argument;
    }

    private static String opaqueId(String value, String usage) {
        if (value == null || value.equals("-") || value.equals(".") || value.equals("..") || !OPAQUE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("usage: " + usage);
        }
        return value;
    }
}
