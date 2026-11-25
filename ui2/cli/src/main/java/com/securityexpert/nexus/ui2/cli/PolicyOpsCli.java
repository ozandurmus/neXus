package com.securityexpert.nexus.ui2.cli;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI parity for the policy screens that are not {@code PolicyController} (backlog {@code asa_fortigate_policy_model}, standing
 * directive {@code cli_parity_for_every_ui_action}): one named command per mapping of {@code PolicyChangesController},
 * {@code PolicyCollectionController} (its collect route is the existing {@code policy-collect}), {@code PolicyLookupController}
 * and {@code PolicyInstallMonitoringController}. Same routes, session cookie, CSRF token and role gates as the screens; the
 * server decides, the CLI adds nothing. Arguments are the controller's own parameter names, in the order of the usage text; an
 * optional argument given as {@code -} is "not given" and the server's own default applies. The output is the server's answer,
 * so the server's aiview projection is the CLI's output; {@code policy-changes-csv} prints the CSV body as it came.
 */
final class PolicyOpsCli {

    /**
     * One command. {@code route} is the full path; {@code idLabel} is the label of the single path id ({@code null} when the
     * route has none); the first {@code required} names of {@code fields} are required positional arguments after the id.
     * The fields are query parameters, or the members of the JSON body when {@code body} is set.
     */
    private record Command(String name, String method, String route, String idLabel, int required, String[] fields, boolean body, String description) {
        int maxArgs() {
            return 1 + (idLabel == null ? 0 : 1) + fields.length;
        }

        int minArgs() {
            return 1 + (idLabel == null ? 0 : 1) + required;
        }

        String usage() {
            StringBuilder out = new StringBuilder(name);
            if (idLabel != null) out.append(" <").append(idLabel).append('>');
            for (int i = 0; i < fields.length; i++) out.append(i < required ? " <" : " [").append(fields[i]).append(i < required ? ">" : "]");
            return out.toString();
        }
    }

    private static final String[] NONE = {};
    private static final String[] CHANGE_FILTERS = {"from", "to", "policy", "device", "actor", "changeType", "field", "q", "cursor", "limit", "includeInitial"};

    private static final List<Command> COMMANDS = List.of(
            new Command("policy-changes", "GET", "/api/v2/policy/changes", null, 0, CHANGE_FILTERS, false, "policy changes across the fleet, one page (the server's limit applies)"),
            new Command("policy-change-actors", "GET", "/api/v2/policy/changes/actor-candidates", null, 1, new String[] {"revision"}, false,
                    "candidate administrators of one change whose actor is AMBIGUOUS (withheld from aiview)"),
            new Command("policy-changes-csv", "GET", "/api/v2/policy/changes.csv", null, 0, CHANGE_FILTERS, false, "policy changes as CSV (printed as received)"),
            new Command("policy-sources", "GET", "/api/v2/policy/sources", null, 0, NONE, false, "policy collection sources, their last collection, and whether you may collect or cancel"),
            new Command("policy-collection", "GET", "/api/v2/policy/collections/{id}", "jobId", 0, NONE, false, "status of one policy collection job"),
            new Command("policy-lookup", "POST", "/api/v2/policy/policies/{id}/lookup", "policyId", 3,
                    new String[] {"source", "destination", "service", "sourceZone", "destinationZone", "target", "context"}, true,
                    "which rule of a policy matches a flow (read-only; service is any or tcp/<port> or udp/<port>)"),
            new Command("policy-install-status", "GET", "/api/v2/operations/policy-install/status", null, 0, new String[] {"vendor", "state", "q", "page"}, false,
                    "policy installation status (read-only monitoring)"));

    private final NexusSessionCli cli;

    PolicyOpsCli(NexusSessionCli cli) {
        this.cli = cli;
    }

    /** Command names, for the collision test. */
    static List<String> names() {
        return COMMANDS.stream().map(Command::name).toList();
    }

    static void printUsage() {
        for (Command command : COMMANDS) System.out.println("  " + command.usage() + "   " + command.description());
        System.out.println("  (policy-collect above starts a collection; '-' for an optional argument means not given, the server's default applies)");
    }

    /** @return exit code, or -1 when {@code args[0]} is not one of these commands */
    int run(String[] args) throws IOException, InterruptedException {
        Command command = COMMANDS.stream().filter(c -> c.name().equals(args[0])).findFirst().orElse(null);
        if (command == null) return -1;
        if (args.length < command.minArgs() || args.length > command.maxArgs()) throw new IllegalArgumentException("usage: " + command.usage());
        int next = 1;
        String route = command.route();
        if (command.idLabel() != null) route = route.replace("{id}", SanitizerCli.id(args[next++]));
        Map<String, String> given = new LinkedHashMap<>();
        for (int i = 0; i < command.fields().length && next < args.length; i++, next++) {
            if (!args[next].equals("-")) given.put(command.fields()[i], args[next]);
            else if (i < command.required()) throw new IllegalArgumentException("usage: " + command.usage());
        }
        if (command.name().equals("policy-change-actors") && !given.get("revision").matches("[0-9]{1,18}"))
            throw new IllegalArgumentException("usage: revision is a number");
        if (command.body()) return NexusSessionCli.print(cli.call(command.method(), route, NexusSessionCli.json(given)));
        List<String> query = new ArrayList<>();
        given.forEach((name, value) -> query.add(name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return NexusSessionCli.print(cli.call(command.method(), route + (query.isEmpty() ? "" : "?" + String.join("&", query)), null));
    }
}
