package com.securityexpert.nexus.ui2.cli;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * (Named {@code ZonesMatrixCli}, not {@code ZoneMatrix*}: architecture rule ZM-13 keeps every {@code ZoneMatrix*} class off the cli package.)
 * CLI parity for the zone matrix screen (zone matrix contract, delivery steps 1 to 4b; backlog
 * {@code zone_matrix_delivery}, standing directive {@code cli_parity_for_every_ui_action}): one named command per route of
 * {@code ZoneMatrixController}, same routes, session cookie, CSRF token and role gates as the screen. Body field names are
 * the controller's request records; a command with a larger body takes it as one JSON argument or {@code @file}, and the
 * CLI never adds, renames or corrects a field. The output is the server's answer, so the server's aiview projection is the
 * CLI's output. {@code zone-matrix-finding-witness} reads one stored finding's witness tuples; the server refuses aiview
 * and every caller without {@code zone_matrix_zones_read}, and records each read before it answers.
 */
final class ZonesMatrixCli {

    static final String BASE = "/api/v2/zone-matrix";
    private final NexusSessionCli cli;

    ZonesMatrixCli(NexusSessionCli cli) {
        this.cli = cli;
    }

    static void printUsage() {
        System.out.println("  zone-matrix-versions | zone-matrix-version <versionId> [blocks]   published versions; one version (blocks needs zone_matrix_zones_read)");
        System.out.println("  zone-matrix-draft [blocks] | zone-matrix-template | zone-matrix-policies   the draft with its diff and publish check; the template; policies");
        System.out.println("  zone-matrix-lookup <address> [versionId]       which zone holds an address (not for aiview)");
        System.out.println("  zone-matrix-zone-add <jsonBody|@file>          body: revision, name, description, role, blocks");
        System.out.println("  zone-matrix-zone-update <zoneId> <jsonBody|@file>   same body");
        System.out.println("  zone-matrix-zone-delete <zoneId> <revision> | zone-matrix-discard <revision>");
        System.out.println("  zone-matrix-cell-set <jsonBody|@file>          body: revision, source, destination, statement, services, severity, rationale, regulation_reference");
        System.out.println("  zone-matrix-cell-clear <jsonBody|@file>        body: revision, source, destination");
        System.out.println("  zone-matrix-default-set <jsonBody|@file>       body: revision, statement, severity");
        System.out.println("  zone-matrix-service-set <jsonBody|@file>       body: revision, name, entries");
        System.out.println("  zone-matrix-service-set-delete <jsonBody|@file>   body: revision, name");
        System.out.println("  zone-matrix-template-import <revision> [replace]   import the template into the draft; replace overwrites");
        System.out.println("  zone-matrix-confirm-reference <jsonBody|@file> body: revision, source, destination, cell_sha256");
        System.out.println("  zone-matrix-publish <jsonBody|@file>           body: revision, content_sha256, rationale (four-eyes)");
        System.out.println("  zone-matrix-evaluate [policyId|-] [versionId|-] [witnesses]   evaluate a version against the policies; witnesses adds witness data");
        System.out.println("  zone-matrix-summary [policyId|-] [outcome|-] [limit]   stored summary of the latest complete run of each policy");
        System.out.println("  zone-matrix-run <policyId> [--version <versionId>]   start a stored run of one policy (active version unless named); prints run_id, already_running, state");
        System.out.println("  zone-matrix-finding-witness <findingId>         stored witness tuples of one finding (finding_id from zone-matrix-summary); needs zone_matrix_zones_read, audited, not for aiview");
    }

    /** @return exit code, or -1 when {@code args[0]} is not a zone-matrix command */
    int run(String[] args) throws IOException, InterruptedException {
        return switch (args[0]) {
            case "zone-matrix-versions" -> {
                requireExactly(args, 1, "zone-matrix-versions");
                yield NexusSessionCli.print(cli.call("GET", BASE + "/versions", null));
            }
            case "zone-matrix-version" -> {
                require(args, 2, "zone-matrix-version <versionId> [blocks]");
                yield NexusSessionCli.print(cli.call("GET", BASE + "/versions/" + SanitizerCli.id(args[1]) + blocks(args, 2), null));
            }
            case "zone-matrix-draft" -> NexusSessionCli.print(cli.call("GET", BASE + "/draft" + blocks(args, 1), null));
            case "zone-matrix-template" -> {
                requireExactly(args, 1, "zone-matrix-template");
                yield NexusSessionCli.print(cli.call("GET", BASE + "/template", null));
            }
            case "zone-matrix-policies" -> {
                requireExactly(args, 1, "zone-matrix-policies");
                yield NexusSessionCli.print(cli.call("GET", BASE + "/policies", null));
            }
            case "zone-matrix-lookup" -> {
                require(args, 2, "zone-matrix-lookup <address> [versionId]");
                String body = "{\"address\":" + quote(args[1])
                        + (args.length > 2 ? ",\"version_id\":" + quote(SanitizerCli.id(args[2])) : "") + "}";
                yield NexusSessionCli.print(cli.call("POST", BASE + "/lookup", body));
            }
            case "zone-matrix-zone-add" -> post(args, "zone-matrix-zone-add <jsonBody|@file>", "/draft/zones");
            case "zone-matrix-zone-update" -> {
                require(args, 3, "zone-matrix-zone-update <zoneId> <jsonBody|@file>");
                yield NexusSessionCli.print(cli.call("PUT", BASE + "/draft/zones/" + SanitizerCli.id(args[1]), body(args[2])));
            }
            case "zone-matrix-zone-delete" -> {
                require(args, 3, "zone-matrix-zone-delete <zoneId> <revision>");
                yield NexusSessionCli.print(cli.call("POST", BASE + "/draft/zones/" + SanitizerCli.id(args[1]) + "/delete", revision(args[2])));
            }
            case "zone-matrix-discard" -> {
                require(args, 2, "zone-matrix-discard <revision>");
                yield NexusSessionCli.print(cli.call("POST", BASE + "/draft/discard", revision(args[1])));
            }
            case "zone-matrix-cell-set" -> post(args, "zone-matrix-cell-set <jsonBody|@file>", "/draft/cells");
            case "zone-matrix-cell-clear" -> post(args, "zone-matrix-cell-clear <jsonBody|@file>", "/draft/cells/clear");
            case "zone-matrix-default-set" -> post(args, "zone-matrix-default-set <jsonBody|@file>", "/draft/default");
            case "zone-matrix-service-set" -> post(args, "zone-matrix-service-set <jsonBody|@file>", "/draft/service-sets");
            case "zone-matrix-service-set-delete" -> post(args, "zone-matrix-service-set-delete <jsonBody|@file>", "/draft/service-sets/delete");
            case "zone-matrix-template-import" -> {
                require(args, 2, "zone-matrix-template-import <revision> [replace]");
                String replace = args.length > 2 ? args[2] : "";
                if (!replace.isEmpty() && !replace.equals("replace"))
                    throw new IllegalArgumentException("usage: the optional second argument is the word replace");
                String body = "{\"revision\":" + number(args[1]) + (replace.isEmpty() ? "" : ",\"replace\":true") + "}";
                yield NexusSessionCli.print(cli.call("POST", BASE + "/draft/template-import", body));
            }
            case "zone-matrix-confirm-reference" -> post(args, "zone-matrix-confirm-reference <jsonBody|@file>", "/draft/cells/confirm-reference");
            case "zone-matrix-publish" -> post(args, "zone-matrix-publish <jsonBody|@file>", "/draft/publish");
            case "zone-matrix-evaluate" -> {
                List<String> fields = new ArrayList<>();
                if (present(args, 1)) fields.add("\"policy_id\":" + quote(args[1]));
                if (present(args, 2)) fields.add("\"version_id\":" + quote(SanitizerCli.id(args[2])));
                if (args.length > 3) {
                    if (!args[3].equals("witnesses")) throw new IllegalArgumentException("usage: the optional third argument is the word witnesses");
                    fields.add("\"include_witnesses\":true");
                }
                yield NexusSessionCli.print(cli.call("POST", BASE + "/evaluate", "{" + String.join(",", fields) + "}"));
            }
            case "zone-matrix-run" -> {
                String usage = "zone-matrix-run <policyId> [--version <versionId>]";
                require(args, 2, usage);
                if (args.length != 2 && !(args.length == 4 && args[2].equals("--version"))) throw new IllegalArgumentException("usage: " + usage);
                String body = "{\"policy_id\":" + quote(args[1])
                        + (args.length == 4 ? ",\"version_id\":" + quote(SanitizerCli.id(args[3])) : "") + "}";
                yield NexusSessionCli.print(cli.call("POST", BASE + "/runs", body));
            }
            case "zone-matrix-finding-witness" -> {
                requireExactly(args, 2, "zone-matrix-finding-witness <findingId>");
                yield NexusSessionCli.print(cli.call("GET", BASE + "/findings/" + findingId(args[1]) + "/witness", null));
            }
            case "zone-matrix-summary" -> {
                List<String> query = new ArrayList<>();
                if (present(args, 1)) query.add("policy_id=" + encode(args[1]));
                if (present(args, 2)) query.add("outcome=" + encode(args[2]));
                if (present(args, 3)) query.add("limit=" + encode(args[3]));
                yield NexusSessionCli.print(cli.call("GET", BASE + "/summary" + (query.isEmpty() ? "" : "?" + String.join("&", query)), null));
            }
            default -> -1;
        };
    }

    private int post(String[] args, String usage, String route) throws IOException, InterruptedException {
        require(args, 2, usage);
        return NexusSessionCli.print(cli.call("POST", BASE + route, body(args[1])));
    }

    /** A JSON argument, or {@code @file}; sent as given, the server validates it. */
    private static String body(String argument) throws IOException {
        return argument.startsWith("@") ? Files.readString(Path.of(argument.substring(1))) : argument;
    }

    private static String blocks(String[] args, int index) {
        if (args.length <= index) return "";
        if (!args[index].equals("blocks")) throw new IllegalArgumentException("usage: the optional argument is the word blocks");
        return "?blocks=1";
    }

    private static boolean present(String[] args, int index) {
        return args.length > index && !args[index].equals("-");
    }

    private static String revision(String value) {
        return "{\"revision\":" + number(value) + "}";
    }

    private static String number(String value) {
        if (!value.matches("[1-9][0-9]{0,17}")) throw new IllegalArgumentException("usage: revision is a positive number");
        return value;
    }

    /** The opaque finding id exactly as the summary prints it (digits, no leading zero); anything else is not sent. */
    private static String findingId(String value) {
        if (!value.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("usage: findingId is the finding_id the summary prints");
        return value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void require(String[] args, int count, String usage) {
        if (args.length < count) throw new IllegalArgumentException("usage: " + usage);
    }

    private static void requireExactly(String[] args, int count, String usage) {
        if (args.length != count) throw new IllegalArgumentException("usage: " + usage);
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
}
