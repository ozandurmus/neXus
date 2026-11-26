package com.securityexpert.nexus.ui2.cli;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CLI parity for the support bundle screen (backlog {@code cli_parity_for_every_ui_action}; design section 5.11): every
 * command is the same HTTP call the screen makes, with the stored session cookie and CSRF token. The server owns every
 * check (role, quotas, business hours, reasons); the CLI prints its answer. The download streams to disk and never holds
 * the archive in memory.
 */
final class SupportBundleCli {

    private static final String BASE = "/api/v2/support-bundles";
    private static final Pattern OPAQUE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern UUID_FORM = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final NexusSessionCli cli;

    SupportBundleCli(NexusSessionCli cli) {
        this.cli = cli;
    }

    static void printUsage() {
        System.out.println("  support-bundles [deviceId] [state=<state>] [limit=<n>]        support bundle requests (masked for aiview)");
        System.out.println("  support-bundle <requestId>                                      one request: prechecks, items, sizes, expiry");
        System.out.println("  support-bundle-request <deviceId> <kindsCsv> <reason> [--at <ISO-8601>] [--case <ref>] [--previous <requestId>] [--vs <vsCsv>] [--domain <ref>]");
        System.out.println("                                                 security_admin: collect the items on a device (e.g. cp_gaia_extras,cp_hcp_latest)");
        System.out.println("  support-bundle-cancel <requestId> <reason>                     cancel a queued request");
        System.out.println("  support-bundle-download <requestId> <reason> <dest.tgz> [--sanitized]");
        System.out.println("                                                 security_admin: the bundle is deleted after a confirmed download");
        System.out.println("  support-bundle-delete <requestId> <reason>                     delete a bundle (after a download, or early)");
        System.out.println("  support-bundle-cleanup <requestId> <reason> --yes              security_admin: remove the leftover work directory a run left on the device (recorded name only)");
        System.out.println("  support-bundle-upload-destinations                             the approved upload destinations");
        System.out.println("  support-bundle-uploads <requestId>                             upload history of a bundle (state, bytes, digest, remote answer)");
        System.out.println("  support-bundle-upload <requestId> <destination> <user> <reason> --yes [--token-file <path>] [--items <kindsCsv>]");
        System.out.println("                       [--case <ref>] [--host <host> --port <n>  (generic_sftp only)]");
        System.out.println("                                                 security_admin: send the bundle to a vendor case or an SFTP server. The token or password");
        System.out.println("                                                 is read from --token-file or typed at the prompt, never taken as an argument");
    }

    /** @return exit code, or -1 when {@code args[0]} is not a support bundle command */
    int run(String[] args) throws IOException, InterruptedException {
        return switch (args[0]) {
            case "support-bundles" -> {
                String device = args.length > 1 && !args[1].contains("=") ? opaque(args[1]) : null;
                int from = device == null ? 1 : 2;
                String query = query(args, from);
                yield NexusSessionCli.print(cli.call("GET", device == null ? BASE + query
                        : "/api/v2/devices/" + device + "/support-bundles" + query, null));
            }
            case "support-bundle" -> {
                need(args, 2, "support-bundle <requestId>");
                yield NexusSessionCli.print(cli.call("GET", BASE + "/" + uuid(args[1]), null));
            }
            case "support-bundle-request" -> request(args);
            case "support-bundle-cancel" -> {
                need(args, 3, "support-bundle-cancel <requestId> <reason>");
                yield NexusSessionCli.print(cli.call("POST", BASE + "/" + uuid(args[1]) + "/cancel",
                        NexusSessionCli.json(Map.of("reason", args[2]))));
            }
            case "support-bundle-delete" -> {
                need(args, 3, "support-bundle-delete <requestId> <reason>");
                yield NexusSessionCli.print(cli.call("POST", BASE + "/" + uuid(args[1]) + "/delete",
                        NexusSessionCli.json(Map.of("reason", args[2]))));
            }
            case "support-bundle-download" -> download(args);
            case "support-bundle-cleanup" -> cleanup(args);
            case "support-bundle-upload-destinations" -> NexusSessionCli.print(cli.call("GET", BASE + "/upload-destinations", null));
            case "support-bundle-uploads" -> {
                need(args, 2, "support-bundle-uploads <requestId>");
                yield NexusSessionCli.print(cli.call("GET", BASE + "/" + uuid(args[1]) + "/uploads", null));
            }
            case "support-bundle-upload" -> upload(args);
            default -> -1;
        };
    }

    private int request(String[] args) throws IOException, InterruptedException {
        String usage = "support-bundle-request <deviceId> <kindsCsv> <reason> [--at <ISO-8601>] [--case <ref>] [--previous <requestId>] [--vs <vsCsv>] [--domain <ref>]";
        need(args, 4, usage);
        String runAt = null, caseRef = null, previousId = null, domainRef = null;
        List<Integer> vsIds = new ArrayList<>();
        for (int i = 4; i < args.length; i++) {
            if ("--at".equals(args[i]) && i + 1 < args.length) runAt = args[++i];
            else if ("--case".equals(args[i]) && i + 1 < args.length) caseRef = args[++i];
            else if ("--previous".equals(args[i]) && i + 1 < args.length) previousId = uuid(args[++i]);
            else if ("--domain".equals(args[i]) && i + 1 < args.length) domainRef = opaque(args[++i]);
            else if ("--vs".equals(args[i]) && i + 1 < args.length) {
                for (String v : args[++i].split(",")) {
                    if (v.isBlank()) continue;
                    try {
                        int n = Integer.parseInt(v.strip());
                        if (n < 0 || n > 4095) throw new IllegalArgumentException("usage: " + usage);
                        vsIds.add(n);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("usage: " + usage);
                    }
                }
            } else throw new IllegalArgumentException("usage: " + usage);
        }
        List<String> kinds = new ArrayList<>();
        for (String k : args[2].split(",")) if (!k.isBlank()) kinds.add(k.strip());
        if (kinds.isEmpty()) throw new IllegalArgumentException("usage: " + usage);
        StringBuilder body = new StringBuilder("{\"kinds\":[");
        for (int i = 0; i < kinds.size(); i++) body.append(i == 0 ? "" : ",").append('"').append(kinds.get(i).replace("\"", "")).append('"');
        body.append("],\"reason\":").append(quote(args[3]));
        if (runAt != null) body.append(",\"run_at\":").append(quote(runAt));
        if (caseRef != null) body.append(",\"case_ref\":").append(quote(caseRef));
        if (previousId != null) body.append(",\"previous_request_id\":").append(quote(previousId));
        if (!vsIds.isEmpty()) {
            body.append(",\"vs_ids\":[");
            for (int i = 0; i < vsIds.size(); i++) body.append(i == 0 ? "" : ",").append(vsIds.get(i));
            body.append(']');
        }
        if (domainRef != null) body.append(",\"domain_ref\":").append(quote(domainRef));
        body.append('}');
        return NexusSessionCli.print(cli.call("POST", "/api/v2/devices/" + opaque(args[1]) + "/support-bundles", body.toString()));
    }

    private int cleanup(String[] args) throws IOException, InterruptedException {
        String usage = "support-bundle-cleanup <requestId> <reason> --yes";
        need(args, 4, usage);
        if (!"--yes".equals(args[3]) || args.length != 4) throw new IllegalArgumentException("usage: " + usage);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", args[2]);
        body.put("confirm", true);
        return NexusSessionCli.print(cli.call("POST", BASE + "/" + uuid(args[1]) + "/cleanup-workdir", NexusSessionCli.json(body)));
    }

    private int upload(String[] args) throws IOException, InterruptedException {
        String usage = "support-bundle-upload <requestId> <destination> <user> <reason> --yes [--token-file <path>] [--items <kindsCsv>]"
                + " [--case <ref>] [--host <host> --port <n>]";
        need(args, 6, usage);
        boolean yes = false;
        String tokenFile = null, items = null, caseRef = null, host = null;
        Integer port = null;
        for (int i = 5; i < args.length; i++) {
            switch (args[i]) {
                case "--yes" -> yes = true;
                case "--token-file" -> tokenFile = value(args, ++i, usage);
                case "--items" -> items = value(args, ++i, usage);
                case "--case" -> caseRef = value(args, ++i, usage);
                case "--host" -> host = value(args, ++i, usage);
                case "--port" -> {
                    try {
                        port = Integer.parseInt(value(args, ++i, usage));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("usage: " + usage);
                    }
                }
                default -> throw new IllegalArgumentException("usage: " + usage);
            }
        }
        if (!yes) throw new IllegalArgumentException("usage: this sends bank data to an external destination; confirm with --yes. " + usage);
        String secret;
        if (tokenFile != null) {
            secret = com.securityexpert.nexus.ui2.platform.SecretFile.readRequired(Path.of(tokenFile), "support bundle upload token");
        } else {
            java.io.Console console = System.console();
            char[] typed = console == null ? null : console.readPassword("upload token or password: ");
            if (typed == null || typed.length == 0) throw new IllegalArgumentException("usage: no token: use --token-file <path> or run in a terminal");
            secret = new String(typed);
            java.util.Arrays.fill(typed, '\0');
        }
        StringBuilder body = new StringBuilder("{\"destination\":").append(quote(args[2])).append(",\"user\":").append(quote(args[3]))
                .append(",\"password\":").append(quote(secret)).append(",\"reason\":").append(quote(args[4])).append(",\"confirm\":true");
        if (items != null) {
            body.append(",\"item_kinds\":[");
            String[] kinds = items.split(",");
            for (int i = 0; i < kinds.length; i++) body.append(i == 0 ? "" : ",").append(quote(kinds[i].strip()));
            body.append(']');
        }
        if (caseRef != null) body.append(",\"case_ref\":").append(quote(caseRef));
        if (host != null) body.append(",\"host\":").append(quote(host));
        if (port != null) body.append(",\"port\":").append(port);
        body.append('}');
        return NexusSessionCli.print(cli.call("POST", BASE + "/" + uuid(args[1]) + "/uploads", body.toString()));
    }

    private static String value(String[] args, int index, String usage) {
        if (index >= args.length) throw new IllegalArgumentException("usage: " + usage);
        return args[index];
    }

    private int download(String[] args) throws IOException, InterruptedException {
        String usage = "support-bundle-download <requestId> <reason> <dest.tgz> [--sanitized]";
        need(args, 4, usage);
        boolean sanitized = false;
        for (int i = 4; i < args.length; i++) {
            if ("--sanitized".equals(args[i])) sanitized = true; else throw new IllegalArgumentException("usage: " + usage);
        }
        var session = cli.loadSession().orElseThrow(() -> new IllegalArgumentException("usage: not logged in"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", args[2]);
        if (sanitized) body.put("mode", "sanitized");
        HttpRequest request = HttpRequest.newBuilder(URI.create(session.baseUrl() + BASE + "/" + uuid(args[1]) + "/download"))
                .header("Cookie", "ui2_session=" + session.cookie()).header("X-CSRF-Token", session.csrf())
                .header("Origin", NexusSessionCli.originOf(session.baseUrl()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(NexusSessionCli.json(body))).build();
        HttpResponse<InputStream> response = cli.client().send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            try (InputStream in = response.body()) {
                System.err.println("REFUSED: HTTP " + response.statusCode() + " "
                        + new String(in.readNBytes(2000), java.nio.charset.StandardCharsets.UTF_8));
            }
            return response.statusCode() == 401 || response.statusCode() == 403 ? 3 : 1;
        }
        Path dest = Path.of(args[3]);
        try (InputStream in = response.body()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("downloaded " + Files.size(dest) + " bytes to " + dest);
        System.out.println("WARNING: the archive carries secret-bearing files and cannot be masked; the copy is yours to protect.");
        System.out.println("The bundle is still stored in Overwatch. Delete it now with: support-bundle-delete " + args[1] + " <reason>");
        return 0;
    }

    private static String query(String[] args, int from) {
        List<String> parts = new ArrayList<>();
        for (int i = from; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq <= 0 || !List.of("state", "limit").contains(args[i].substring(0, eq))
                    || !OPAQUE.matcher(args[i].substring(eq + 1)).matches()) {
                throw new IllegalArgumentException("usage: support-bundles [deviceId] [state=<state>] [limit=<n>]");
            }
            parts.add(args[i]);
        }
        return parts.isEmpty() ? "" : "?" + String.join("&", parts);
    }

    private static void need(String[] args, int count, String usage) {
        if (args.length < count) throw new IllegalArgumentException("usage: " + usage);
    }

    private static String opaque(String value) {
        if (!OPAQUE.matcher(value).matches()) throw new IllegalArgumentException("usage: identifiers are opaque ids, got: " + value);
        return value;
    }

    private static String uuid(String value) {
        if (!UUID_FORM.matcher(value).matches()) throw new IllegalArgumentException("usage: request identifiers are UUIDs, got: " + value);
        return value;
    }

    private static String quote(String value) {
        return NexusSessionCli.json(Map.of("v", value)).replaceFirst("^\\{\"v\":", "").replaceFirst("}$", "");
    }
}
