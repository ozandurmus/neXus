package com.securityexpert.nexus.ui2.cli;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI parity for the policy screens (backlog {@code asa_fortigate_policy_model}, standing directive
 * {@code cli_parity_for_every_ui_action}): one named, read-only command per GET mapping of {@code PolicyController}, same
 * routes, session cookie and role gates as the screens. Arguments are the controller's own parameter names, in the order of
 * the usage text; an optional argument given as {@code -} is "not given" and the server's own default applies. The CLI never
 * adds, renames or corrects a parameter. The output is the server's answer, so the server's aiview projection is the CLI's
 * output; {@code policy-hygiene-csv} prints the CSV body as it came.
 *
 * <p>Out of scope here (they have their own controllers): policy changes, policy collection, install monitoring and policy
 * lookup. The existing {@code policy}, {@code policy-set} and {@code policy-collect} (backups, collection) keep their names.</p>
 */
final class PolicyCli {

    static final String BASE = "/api/v2/policy";

    /**
     * One command. {@code route} is relative to {@link #BASE}; {@code idLabel} is the label of the single path id ({@code null}
     * when the route has none); the first {@code required} query names are required positional arguments after the id.
     */
    private record Command(String name, String route, String idLabel, int required, String[] query, String description) {
        int maxArgs() {
            return 1 + (idLabel == null ? 0 : 1) + query.length;
        }

        int minArgs() {
            return 1 + (idLabel == null ? 0 : 1) + required;
        }

        String usage() {
            StringBuilder out = new StringBuilder(name);
            if (idLabel != null) out.append(" <").append(idLabel).append('>');
            for (int i = 0; i < query.length; i++) out.append(i < required ? " <" : " [").append(query[i]).append(i < required ? ">" : "]");
            return out.toString();
        }
    }

    private static final String[] NONE = {};

    private static final List<Command> COMMANDS = List.of(
            new Command("policy-ha-pairs", "/ha-pairs", null, 0, NONE, "HA pair policy comparison: SAME, HA_POLICY_DIVERGENT or UNKNOWN per pair and context"),
            new Command("policy-hygiene-summary", "/hygiene-summary", null, 0, NONE, "the cached fleet hygiene summary"),
            new Command("policy-hygiene", "/policies/{id}/hygiene", "policyId", 0, new String[] {"page", "findingClass", "severity", "days"}, "one page of hygiene findings of a policy"),
            new Command("policy-insights", "/policies/{id}/insights", "policyId", 0, new String[] {"page", "type", "grade", "days"}, "package overview and one page of the insight list"),
            new Command("policy-insight", "/policies/{id}/insight", "policyId", 1, new String[] {"insightId", "days"}, "one insight"),
            new Command("policy-proposal", "/policies/{id}/proposal", "policyId", 0, new String[] {"days"}, "reorder-only proposal of a policy"),
            new Command("policy-rule", "/policies/{id}/rules", "policyId", 1, new String[] {"ruleId", "days"}, "one rule with its findings and hits"),
            new Command("policy-hygiene-csv", "/policies/{id}/hygiene.csv", "policyId", 0, new String[] {"findingClass", "severity", "days"}, "hygiene findings as CSV (printed as received)"),
            new Command("policy-tree", "/tree", null, 0, new String[] {"source", "container", "device"}, "the policy tree"),
            new Command("policy-devices", "/devices", null, 0, NONE, "policies and the devices they are installed on"),
            new Command("policy-device", "/devices/{id}", "deviceId", 0, new String[] {"policy", "page", "q"}, "the policy of one device, one page of rules"),
            new Command("policy-show", "/policies/{id}", "policyId", 0, new String[] {"page", "q", "hitFilter", "days", "sort", "order"}, "one page of rules of a policy"),
            new Command("policy-history", "/policies/{id}/history", "policyId", 0, new String[] {"rule", "page"}, "change history of a policy or one rule"),
            new Command("policy-object", "/objects/{id}", "objectId", 0, new String[] {"device", "policy"}, "one object (device or policy is required by the server)"),
            new Command("policy-domain-objects", "/domains/{id}/objects", "domainId", 1, new String[] {"source", "page", "q", "type", "hygiene", "sort", "order"}, "objects of a domain"),
            new Command("policy-domain-object-usage", "/domains/{id}/object-usage", "domainId", 2, new String[] {"source", "uid", "page", "sort", "order"}, "where one object is used"),
            new Command("policy-domain-duplicates", "/domains/{id}/objects/duplicates", "domainId", 1, new String[] {"source", "page", "q"}, "duplicate objects of a domain"),
            new Command("policy-domain-installation", "/domains/{id}/installation", "domainId", 1, new String[] {"source", "page", "q", "policy", "sort", "order"}, "installation targets of a domain"),
            new Command("policy-domain-unused", "/domains/{id}/unused", "domainId", 1, new String[] {"source", "page"}, "unused objects of a domain"),
            new Command("policy-domain-gateways", "/domains/{id}/gateways", "domainId", 1, new String[] {"source", "page"}, "gateways of a domain"),
            new Command("policy-domain-hits", "/domains/{id}/hits", "domainId", 1, new String[] {"source", "page"}, "rule hits of a domain"));

    private final NexusSessionCli cli;

    PolicyCli(NexusSessionCli cli) {
        this.cli = cli;
    }

    /** Command names, for the collision test. */
    static List<String> names() {
        return COMMANDS.stream().map(Command::name).toList();
    }

    static void printUsage() {
        for (Command command : COMMANDS) System.out.println("  " + command.usage() + "   " + command.description());
        System.out.println("  (policy-*: read-only; '-' for an optional argument means not given, the server's default applies)");
    }

    /** @return exit code, or -1 when {@code args[0]} is not a policy-screen command */
    int run(String[] args) throws IOException, InterruptedException {
        Command command = COMMANDS.stream().filter(c -> c.name().equals(args[0])).findFirst().orElse(null);
        if (command == null) return -1;
        if (args.length < command.minArgs() || args.length > command.maxArgs()) throw new IllegalArgumentException("usage: " + command.usage());
        int next = 1;
        String route = command.route();
        if (command.idLabel() != null) route = route.replace("{id}", SanitizerCli.id(args[next++]));
        List<String> query = new ArrayList<>();
        for (int i = 0; i < command.query().length && next < args.length; i++, next++) {
            boolean given = !args[next].equals("-");
            if (!given && i < command.required()) throw new IllegalArgumentException("usage: " + command.usage());
            if (given) query.add(command.query()[i] + "=" + URLEncoder.encode(args[next], StandardCharsets.UTF_8));
        }
        return NexusSessionCli.print(cli.call("GET", BASE + route + (query.isEmpty() ? "" : "?" + String.join("&", query)), null));
    }
}
