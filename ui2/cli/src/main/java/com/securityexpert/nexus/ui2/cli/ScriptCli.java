package com.securityexpert.nexus.ui2.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CLI parity for Operations › Script Execution ({@code SCRIPT_EXECUTION_CONTRACT.md} sections 3.5, 3.6, 3.7
 * and Amendment 2026-11-18 in {@code PO_DECISION_RECORD_2026_11_18_SCRIPT_EXECUTION_NO_REFUSALS.md}):
 * {@code script-list}, {@code script-get}, {@code script-add <file>}, {@code script-version-add <id> <file>},
 * {@code script-set <id>}, {@code script-enable} / {@code script-disable}, {@code script-run <id>},
 * {@code script-runs <id>}, {@code script-run-show <runId>} and {@code script-output <runId>}.
 */
final class ScriptCli {

    static final String BASE = "/api/v2/scripts";
    private static final Pattern OPAQUE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final List<String> NAMES = List.of(
            "script-list",
            "script-get",
            "script-add",
            "script-version-add",
            "script-set",
            "script-enable",
            "script-disable",
            "script-run",
            "script-runs",
            "script-run-show",
            "script-output");

    private final NexusSessionCli cli;

    ScriptCli(NexusSessionCli cli) {
        this.cli = cli;
    }

    static List<String> names() {
        return NAMES;
    }

    static void printUsage() {
        System.out.println("  script-list [scriptId]                         list scripts (metadata only) or one script with versions");
        System.out.println("  script-get <scriptId>                          show one script (metadata and versions, never body)");
        System.out.println("  script-add <file> [--name <name>] [--kind <kind>] [--schedule <cron>] [--enabled <true|false|on|off>]");
        System.out.println("             [--timeout <seconds>] [--egress <host:port,...|none>] [--notify <syslog|smtp|both|none>]");
        System.out.println("             [--mail-on-failure-only <true|false|on|off>] [--credential-refs <ref1,ref2,...|none>]");
        System.out.println("             [--run-as <actor>] [--script-id <id>]");
        System.out.println("  script-version-add <scriptId> <file> [--kind <kind>] [--credential-refs <ref1,ref2,...|none>]");
        System.out.println("  script-set <scriptId> [--schedule <cron|clear>] [--enabled <true|false|on|off>] [--timeout <seconds>]");
        System.out.println("             [--egress <host:port,...|none>] [--notify <syslog|smtp|both|none>]");
        System.out.println("             [--mail-on-failure-only <true|false|on|off>] [--credential-refs <ref1,ref2,...|none>]");
        System.out.println("             [--run-as <actor>]");
        System.out.println("  script-enable <scriptId>                       enable a script's schedule");
        System.out.println("  script-disable <scriptId>                      disable a script's schedule");
        System.out.println("  script-run <scriptId>                          trigger an immediate script_run job");
        System.out.println("  script-runs <scriptId>                         list recent runs for a script");
        System.out.println("  script-run-show <runId>                        show one script run (metadata, masked output tail, notification)");
        System.out.println("  script-output <runId>                          download audited captured output for a run");
    }

    /** @return exit code, or -1 when {@code args[0]} is not a script command */
    int run(String[] args) throws IOException, InterruptedException {
        return switch (args[0]) {
            case "script-list" -> {
                if (args.length > 2) {
                    throw new IllegalArgumentException("usage: script-list [scriptId]");
                }
                yield args.length == 2
                        ? NexusSessionCli.print(cli.call("GET", BASE + "/" + opaque(args[1]), null))
                        : NexusSessionCli.print(cli.call("GET", BASE, null));
            }
            case "script-get" -> {
                need(args, 2, "script-get <scriptId>");
                if (args.length > 2) {
                    throw new IllegalArgumentException("usage: script-get <scriptId>");
                }
                yield NexusSessionCli.print(cli.call("GET", BASE + "/" + opaque(args[1]), null));
            }
            case "script-add" -> add(args);
            case "script-version-add" -> addVersion(args);
            case "script-set" -> set(args);
            case "script-enable" -> {
                need(args, 2, "script-enable <scriptId>");
                if (args.length > 2) {
                    throw new IllegalArgumentException("usage: script-enable <scriptId>");
                }
                yield NexusSessionCli.print(cli.call("POST", BASE + "/" + opaque(args[1]) + "/enable", "{}"));
            }
            case "script-disable" -> {
                need(args, 2, "script-disable <scriptId>");
                if (args.length > 2) {
                    throw new IllegalArgumentException("usage: script-disable <scriptId>");
                }
                yield NexusSessionCli.print(cli.call("POST", BASE + "/" + opaque(args[1]) + "/disable", "{}"));
            }
            case "script-run" -> {
                need(args, 2, "script-run <scriptId>");
                if (args.length > 2) {
                    throw new IllegalArgumentException("usage: script-run <scriptId>");
                }
                yield NexusSessionCli.print(cli.call("POST", BASE + "/" + opaque(args[1]) + "/run", "{}"));
            }
            case "script-runs" -> {
                need(args, 2, "script-runs <scriptId>");
                if (args.length > 2) {
                    throw new IllegalArgumentException("usage: script-runs <scriptId>");
                }
                yield NexusSessionCli.print(cli.call("GET", BASE + "/" + opaque(args[1]) + "/runs", null));
            }
            case "script-run-show" -> {
                need(args, 2, "script-run-show <runId>");
                if (args.length > 2) {
                    throw new IllegalArgumentException("usage: script-run-show <runId>");
                }
                String runId = opaque(args[1]);
                yield NexusSessionCli.print(cli.call("GET", BASE + "/runs/" + runId, null));
            }
            case "script-output" -> {
                need(args, 2, "script-output <runId>");
                if (args.length > 2) {
                    throw new IllegalArgumentException("usage: script-output <runId>");
                }
                yield NexusSessionCli.print(cli.call("GET", BASE + "/runs/" + opaque(args[1]) + "/output", null));
            }
            default -> -1;
        };
    }

    private int add(String[] args) throws IOException, InterruptedException {
        String usage = "script-add <file> [--name <name>] [--kind <kind>] [--schedule <cron>] "
                + "[--enabled <true|false|on|off>] [--timeout <seconds>] [--egress <host:port,...|none>] "
                + "[--notify <syslog|smtp|both|none>] [--mail-on-failure-only <true|false|on|off>] "
                + "[--credential-refs <ref1,ref2,...|none>] [--run-as <actor>] [--script-id <id>]";
        need(args, 2, usage);
        Path file = Path.of(args[1]);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("usage: no such script file: " + file);
        }
        String name = null;
        String kind = null;
        String schedule = null;
        Boolean enabled = null;
        Integer timeout = null;
        List<String> egress = null;
        List<String> credentialRefs = null;
        String notify = null;
        Boolean mailOnFailureOnly = null;
        String runAs = null;
        String scriptId = null;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--name" -> name = value(args, ++i, usage);
                case "--kind" -> kind = value(args, ++i, usage);
                case "--schedule" -> schedule = value(args, ++i, usage);
                case "--enabled" -> enabled = parseBooleanFlag(value(args, ++i, usage), usage);
                case "--timeout" -> timeout = parsePositiveInt(value(args, ++i, usage), usage);
                case "--egress" -> egress = parseCsvOrNone(value(args, ++i, usage));
                case "--credential-refs" -> credentialRefs = parseCsvOrNone(value(args, ++i, usage));
                case "--notify" -> notify = value(args, ++i, usage);
                case "--mail-on-failure-only" -> mailOnFailureOnly = parseBooleanFlag(value(args, ++i, usage), usage);
                case "--run-as" -> runAs = value(args, ++i, usage);
                case "--script-id" -> scriptId = opaque(value(args, ++i, usage));
                default -> throw new IllegalArgumentException("usage: " + usage);
            }
        }
        if (scriptId != null && mailOnFailureOnly != null) {
            throw new IllegalArgumentException("usage: " + usage);
        }

        byte[] bytes = Files.readAllBytes(file);
        String base64 = Base64.getEncoder().encodeToString(bytes);
        String fileName = file.getFileName().toString();

        if (scriptId != null) {
            StringBuilder body = new StringBuilder("{\"file_name\":").append(quote(fileName))
                    .append(",\"content_base64\":").append(quote(base64));
            if (kind != null) body.append(",\"kind\":").append(quote(kind));
            if (credentialRefs != null) body.append(",\"credential_refs\":").append(jsonArray(credentialRefs));
            body.append('}');
            return NexusSessionCli.print(cli.call("POST", BASE + "/" + scriptId + "/versions", body.toString()));
        }

        StringBuilder body = new StringBuilder("{\"file_name\":").append(quote(fileName))
                .append(",\"content_base64\":").append(quote(base64));
        if (name != null) body.append(",\"name\":").append(quote(name));
        if (kind != null) body.append(",\"kind\":").append(quote(kind));
        if (schedule != null) body.append(",\"schedule\":").append(quote(schedule));
        if (enabled != null) body.append(",\"enabled\":").append(enabled);
        if (timeout != null) body.append(",\"timeout_s\":").append(timeout);
        if (egress != null) body.append(",\"egress\":").append(jsonArray(egress));
        if (credentialRefs != null) body.append(",\"credential_refs\":").append(jsonArray(credentialRefs));
        if (notify != null) body.append(",\"notify\":").append(quote(notify));
        if (mailOnFailureOnly != null) body.append(",\"notify_mail_on_failure_only\":").append(mailOnFailureOnly);
        if (runAs != null) body.append(",\"run_as\":").append(quote(runAs));
        body.append('}');
        return NexusSessionCli.print(cli.call("POST", BASE, body.toString()));
    }

    private int addVersion(String[] args) throws IOException, InterruptedException {
        String usage = "script-version-add <scriptId> <file> [--kind <kind>] [--credential-refs <ref1,ref2,...|none>]";
        need(args, 3, usage);
        String scriptId = opaque(args[1]);
        Path file = Path.of(args[2]);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("usage: no such script file: " + file);
        }
        String kind = null;
        List<String> credentialRefs = null;
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "--kind" -> kind = value(args, ++i, usage);
                case "--credential-refs" -> credentialRefs = parseCsvOrNone(value(args, ++i, usage));
                default -> throw new IllegalArgumentException("usage: " + usage);
            }
        }
        String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        StringBuilder body = new StringBuilder("{\"file_name\":").append(quote(file.getFileName().toString()))
                .append(",\"content_base64\":").append(quote(base64));
        if (kind != null) body.append(",\"kind\":").append(quote(kind));
        if (credentialRefs != null) body.append(",\"credential_refs\":").append(jsonArray(credentialRefs));
        body.append('}');
        return NexusSessionCli.print(cli.call("POST", BASE + "/" + scriptId + "/versions", body.toString()));
    }

    private int set(String[] args) throws IOException, InterruptedException {
        String usage = "script-set <scriptId> [--schedule <cron|clear>] [--enabled <true|false|on|off>] "
                + "[--timeout <seconds>] [--egress <host:port,...|none>] [--notify <syslog|smtp|both|none>] "
                + "[--mail-on-failure-only <true|false|on|off>] [--credential-refs <ref1,ref2,...|none>] "
                + "[--run-as <actor>]";
        need(args, 3, usage);
        String scriptId = opaque(args[1]);
        String schedule = null;
        boolean clearSchedule = false;
        Boolean enabled = null;
        Integer timeout = null;
        List<String> egress = null;
        List<String> credentialRefs = null;
        String notify = null;
        Boolean mailOnFailureOnly = null;
        String runAs = null;
        int fieldsSet = 0;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--schedule" -> {
                    String raw = value(args, ++i, usage);
                    if ("clear".equalsIgnoreCase(raw) || "none".equalsIgnoreCase(raw) || raw.isBlank()) {
                        clearSchedule = true;
                    } else {
                        schedule = raw;
                    }
                    fieldsSet++;
                }
                case "--enabled" -> {
                    enabled = parseBooleanFlag(value(args, ++i, usage), usage);
                    fieldsSet++;
                }
                case "--timeout" -> {
                    timeout = parsePositiveInt(value(args, ++i, usage), usage);
                    fieldsSet++;
                }
                case "--egress" -> {
                    egress = parseCsvOrNone(value(args, ++i, usage));
                    fieldsSet++;
                }
                case "--credential-refs" -> {
                    credentialRefs = parseCsvOrNone(value(args, ++i, usage));
                    fieldsSet++;
                }
                case "--notify" -> {
                    notify = value(args, ++i, usage);
                    fieldsSet++;
                }
                case "--mail-on-failure-only" -> {
                    mailOnFailureOnly = parseBooleanFlag(value(args, ++i, usage), usage);
                    fieldsSet++;
                }
                case "--run-as" -> {
                    runAs = value(args, ++i, usage);
                    fieldsSet++;
                }
                default -> throw new IllegalArgumentException("usage: " + usage);
            }
        }
        if (fieldsSet == 0) {
            throw new IllegalArgumentException("usage: " + usage);
        }

        List<String> entries = new ArrayList<>();
        if (clearSchedule) {
            entries.add("\"clear_schedule\":true");
        } else if (schedule != null) {
            entries.add("\"schedule\":" + quote(schedule));
        }
        if (enabled != null) {
            entries.add("\"enabled\":" + enabled);
        }
        if (timeout != null) {
            entries.add("\"timeout_s\":" + timeout);
        }
        if (egress != null) {
            entries.add("\"egress\":" + jsonArray(egress));
        }
        if (credentialRefs != null) {
            entries.add("\"credential_refs\":" + jsonArray(credentialRefs));
        }
        if (notify != null) {
            entries.add("\"notify\":" + quote(notify));
        }
        if (mailOnFailureOnly != null) {
            entries.add("\"notify_mail_on_failure_only\":" + mailOnFailureOnly);
        }
        if (runAs != null) {
            entries.add("\"run_as\":" + quote(runAs));
        }

        String body = "{" + String.join(",", entries) + "}";
        return NexusSessionCli.print(cli.call("PATCH", BASE + "/" + scriptId, body));
    }

    private static void need(String[] args, int count, String usage) {
        if (args.length < count) {
            throw new IllegalArgumentException("usage: " + usage);
        }
    }

    private static String value(String[] args, int index, String usage) {
        if (index >= args.length) {
            throw new IllegalArgumentException("usage: " + usage);
        }
        return args[index];
    }

    private static String opaque(String value) {
        if (value == null || !OPAQUE.matcher(value).matches()) {
            throw new IllegalArgumentException("usage: identifiers are opaque ids, got: " + value);
        }
        return value;
    }

    private static boolean parseBooleanFlag(String raw, String usage) {
        String lower = raw.strip().toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "true", "on", "1", "yes" -> true;
            case "false", "off", "0", "no" -> false;
            default -> throw new IllegalArgumentException("usage: " + usage);
        };
    }

    private static int parsePositiveInt(String raw, String usage) {
        try {
            int parsed = Integer.parseInt(raw.strip());
            if (parsed <= 0) {
                throw new IllegalArgumentException("usage: " + usage);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("usage: " + usage);
        }
    }

    private static List<String> parseCsvOrNone(String raw) {
        String trimmed = raw.strip();
        if (trimmed.isEmpty() || "none".equalsIgnoreCase(trimmed) || "clear".equalsIgnoreCase(trimmed) || "-".equals(trimmed)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String item : trimmed.split(",")) {
            if (!item.isBlank()) {
                out.add(item.strip());
            }
        }
        return List.copyOf(out);
    }

    private static String jsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(quote(values.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String quote(String value) {
        return NexusSessionCli.json(Map.of("v", value)).replaceFirst("^\\{\"v\":", "").replaceFirst("}$", "");
    }
}
