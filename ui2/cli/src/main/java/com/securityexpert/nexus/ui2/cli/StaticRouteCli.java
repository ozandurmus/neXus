package com.securityexpert.nexus.ui2.cli;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * CLI parity for Operations > Static routes, Phase R0 (approved CLASS_0 reads, Stage A/B pre-checks, before-path
 * lookup, deterministic command-plan + inverse-plan preview; ZERO writes):
 * <ul>
 *   <li>{@code static-route-preview <target> <prefix> <nexthop> [...]}</li>
 *   <li>{@code static-route-preview-show <id>}</li>
 *   <li>{@code static-route-previews}</li>
 * </ul>
 */
final class StaticRouteCli {

    static final String BASE_PATH = "/api/v2/operations/static-route/preview";
    private static final Pattern OPAQUE_TARGET = Pattern.compile("\\A[A-Za-z0-9_.:-]{1,128}\\z");
    private static final Pattern PREVIEW_ID_FORM = Pattern.compile(
            "\\Asrp-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\z");
    private static final Pattern CIDR_FORM = Pattern.compile(
            "\\A\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}/\\d{1,2}\\z");
    private static final Pattern IPV4_FORM = Pattern.compile(
            "\\A\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\z");

    private final NexusSessionCli cli;

    StaticRouteCli(NexusSessionCli cli) {
        this.cli = cli;
    }

    static void printUsage() {
        System.out.println("  static-route-preview <target> <prefix> <nexthop> [--op ADD|DELETE] [--vendor <v>] [--context <ctx>]");
        System.out.println("                       [--interface <if>] [--distance <1..255>] [--metric <1..65535>] [--priority <1..8>]");
        System.out.println("                       [--route-name <name>] [--comment <text>] [--host <ip>] [--ticket <ref>]");
        System.out.println("                       [--reason <text>] [--idempotency-key <key>]");
        System.out.println("                                                 Operations > Static routes (R0): CLASS_0 read-only preview & pre-checks; nothing is written");
        System.out.println("  static-route-preview-show <id>                 show one static-route preview result (pre-checks, before path, forward & inverse plan)");
        System.out.println("  static-route-previews                          list recent static-route previews and eligible targets");
    }

    /** @return exit code, or -1 when {@code args[0]} is not a static-route preview command */
    int run(String[] args) throws IOException, InterruptedException {
        return switch (args[0]) {
            case "static-route-preview" -> preview(args);
            case "static-route-preview-show" -> show(args);
            case "static-route-previews" -> {
                if (args.length != 1) {
                    throw new IllegalArgumentException("usage: static-route-previews");
                }
                yield NexusSessionCli.print(cli.call("GET", BASE_PATH, null));
            }
            default -> -1;
        };
    }

    private int preview(String[] args) throws IOException, InterruptedException {
        String usage = "static-route-preview <target> <prefix> <nexthop> [--op ADD|DELETE] [--vendor <v>] "
                + "[--context <ctx>] [--interface <if>] [--distance <n>] [--metric <n>] [--priority <n>] "
                + "[--route-name <name>] [--comment <text>] [--host <ip>] [--ticket <ref>] [--reason <text>] "
                + "[--idempotency-key <key>]";
        if (args.length < 4) {
            throw new IllegalArgumentException("usage: " + usage);
        }
        String target = args[1];
        String prefix = args[2];
        String nextHop = args[3];
        if (!OPAQUE_TARGET.matcher(target).matches()) {
            throw new IllegalArgumentException("usage: invalid target identifier: " + target);
        }
        if ("0.0.0.0/0".equals(prefix) || "default".equalsIgnoreCase(prefix)) {
            throw new IllegalArgumentException("usage: DEFAULT_ROUTE_FORBIDDEN (0.0.0.0/0 is not permitted)");
        }
        if (!CIDR_FORM.matcher(prefix).matches()) {
            throw new IllegalArgumentException("usage: prefix must be IPv4 CIDR A.B.C.D/M: " + prefix);
        }
        if (!IPV4_FORM.matcher(nextHop).matches()) {
            throw new IllegalArgumentException("usage: nexthop must be IPv4 address A.B.C.D: " + nextHop);
        }

        String operation = "ADD";
        String vendor = null;
        String routingContext = null;
        String interfaceName = null;
        Integer distance = null;
        Integer metric = null;
        Integer priority = null;
        String routeName = null;
        String deviceComment = null;
        String representativeHost = null;
        String changeTicketRef = null;
        String reason = null;
        String idempotencyKey = null;

        for (int i = 4; i < args.length; i++) {
            String flag = args[i];
            switch (flag) {
                case "--op", "--operation" -> {
                    operation = nextValue(args, ++i, usage).toUpperCase(Locale.ROOT);
                    if (!"ADD".equals(operation) && !"DELETE".equals(operation)) {
                        throw new IllegalArgumentException("usage: --op must be ADD or DELETE");
                    }
                }
                case "--vendor" -> vendor = nextValue(args, ++i, usage);
                case "--context", "--routing-context" -> routingContext = nextValue(args, ++i, usage);
                case "--interface", "--if" -> interfaceName = nextValue(args, ++i, usage);
                case "--distance" -> distance = parseIntOption(nextValue(args, ++i, usage), "--distance", usage);
                case "--metric" -> metric = parseIntOption(nextValue(args, ++i, usage), "--metric", usage);
                case "--priority" -> priority = parseIntOption(nextValue(args, ++i, usage), "--priority", usage);
                case "--route-name" -> routeName = nextValue(args, ++i, usage);
                case "--comment" -> deviceComment = nextValue(args, ++i, usage);
                case "--host", "--representative-host" -> representativeHost = nextValue(args, ++i, usage);
                case "--ticket", "--change-ticket-ref" -> changeTicketRef = nextValue(args, ++i, usage);
                case "--reason" -> reason = nextValue(args, ++i, usage);
                case "--idempotency-key" -> idempotencyKey = nextValue(args, ++i, usage);
                default -> throw new IllegalArgumentException("usage: unknown option '" + flag + "'. " + usage);
            }
        }

        String resolvedIdemKey = idempotencyKey != null ? idempotencyKey : "cli-srp-" + UUID.randomUUID();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotency_key", resolvedIdemKey);
        body.put("unit_id", target);
        if (vendor != null) body.put("vendor", vendor);
        if (routingContext != null) body.put("routing_context", routingContext);
        body.put("operation", operation);
        body.put("destination_prefix", prefix);
        body.put("next_hop_ip", nextHop);
        if (interfaceName != null) body.put("interface_name", interfaceName);
        if (distance != null) body.put("distance", distance);
        if (metric != null) body.put("metric", metric);
        if (priority != null) body.put("priority", priority);
        if (routeName != null) body.put("route_name", routeName);
        if (deviceComment != null) body.put("device_comment", deviceComment);
        if (representativeHost != null) body.put("representative_host", representativeHost);
        if (changeTicketRef != null) body.put("change_ticket_ref", changeTicketRef);
        if (reason != null) body.put("reason", reason);

        return NexusSessionCli.print(cli.call(
                "POST",
                BASE_PATH,
                NexusSessionCli.json(body),
                Map.of("Idempotency-Key", resolvedIdemKey)));
    }

    private int show(String[] args) throws IOException, InterruptedException {
        String usage = "static-route-preview-show <id>";
        if (args.length != 2 || !PREVIEW_ID_FORM.matcher(args[1]).matches()) {
            throw new IllegalArgumentException("usage: " + usage);
        }
        return NexusSessionCli.print(cli.call("GET", BASE_PATH + "/" + args[1], null));
    }

    private static String nextValue(String[] args, int index, String usage) {
        if (index >= args.length || args[index].isBlank()) {
            throw new IllegalArgumentException("usage: " + usage);
        }
        return args[index];
    }

    private static Integer parseIntOption(String val, String flag, String usage) {
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("usage: " + flag + " requires an integer. " + usage);
        }
    }
}
