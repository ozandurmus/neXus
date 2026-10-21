package com.securityexpert.nexus.ui2.cli;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CLI parity for the failover screens (backlog {@code cli_parity_for_every_ui_action}; Pilot status P1-6; contract
 * {@code CP_FAILOVER_MEMBER_RECOVERY_CONTRACT.md} section 6.3). Every command is the same HTTP call the screen makes
 * to the same route, with the stored session cookie and CSRF token: the server owns every check (roles, windows,
 * incident fence, two-person rule, business hours), and the CLI only prints the server's refusal code in plain
 * words. The actor is the session; no command names an actor or an approver.
 *
 * <p>Business hours: the global {@code --business-hours-ack} flag sends the header on every write, and the two
 * resume routes, which read the acknowledgement from the body, also get {@code "businessHoursAck": true}.</p>
 */
final class FailoverCli {

    private static final String CP = "/api/v2/cp-failover";
    private static final String PAN = "/api/v2/pan-failover";
    private static final Set<String> VENDORS = Set.of("check_point", "palo_alto");

    /** Plain words for the codes the failover routes answer with; an unknown code is printed as it is. */
    private static final Map<String, String> REFUSALS = Map.ofEntries(
            Map.entry("BUSINESS_HOURS_ACK_REQUIRED", "This is inside business hours (08:00-20:00 GMT+3) and needs your acknowledgement; "
                    + "nothing was sent. Repeat the command with --business-hours-ack to confirm."),
            Map.entry("OPEN_INCIDENT", "An open failover incident blocks this; a security_admin must release it first."),
            Map.entry("WRONG_ROLE", "Your role may not do this."),
            Map.entry("SELF_APPROVAL", "The person who asked cannot approve their own request; a second administrator must."),
            Map.entry("RUN_ALREADY_ACTIVE", "Another failover or resume is already running for this unit or fleet."),
            Map.entry("REQUEST_NOT_FOUND", "No such approval request."),
            Map.entry("REQUEST_CHANGED", "The approval request changed after you looked at it; read it again and use the new revision."),
            Map.entry("REQUEST_BINDING_REQUIRED", "The start must name the approved request, its revision and its execution token."),
            Map.entry("REQUEST_ID_REQUIRED", "The approval request needs a request id."),
            Map.entry("WARNING_CONFIRMATION_REQUIRED", "The failover warning must be confirmed; repeat with --confirm-warning."),
            Map.entry("REASON_REQUIRED", "A reason is required."),
            Map.entry("RELEASE_REASON_REQUIRED", "A reason is required to release an incident."),
            Map.entry("INVALID_WINDOW", "The approval window is not valid."),
            Map.entry("INVALID_REQUEST", "The server did not accept the request body."),
            Map.entry("UNIT_NOT_FOUND", "No such failover unit."),
            Map.entry("CLUSTER_NOT_FOUND", "No such cluster."),
            Map.entry("CLUSTER_NOT_ELIGIBLE", "This cluster is not eligible for failover."),
            Map.entry("CLUSTER_STATE_NOT_READY", "The cluster state is not ready for failover."),
            Map.entry("UNSUPPORTED_HA_MODE", "This HA mode is not supported."),
            Map.entry("VSX_CONTEXT_INCOMPLETE", "The VSX context of this unit is incomplete."),
            Map.entry("INVENTORY_REQUIRED", "Inventory evidence is required first."),
            Map.entry("ENDPOINT_MISSING", "A member has no management endpoint recorded."),
            Map.entry("ENDPOINT_INVALID", "A member's management endpoint is not valid."),
            Map.entry("TRUSTED_HOST_KEY_REQUIRED", "A member's host key is not trusted."),
            Map.entry("TRUST_NOT_EVALUABLE", "The trust of a member's host key could not be evaluated."),
            Map.entry("RUN_NOT_FOUND", "No such run."),
            Map.entry("INCIDENT_NOT_FOUND", "No such incident."),
            Map.entry("INCIDENT_NOT_OPEN", "That incident is not open."),
            Map.entry("INCIDENT_RELEASED", "That incident is already released."),
            Map.entry("INCIDENT_MISMATCH", "The incident id does not match the run's current incident."),
            Map.entry("INCIDENT_RUN_NOT_TERMINAL", "The run has not finished; its incident cannot be released yet."),
            Map.entry("INCIDENT_RUN_STILL_OWNED", "A worker still owns the run; its incident cannot be released yet."),
            Map.entry("INCIDENT_RELEASE_FENCE", "The release was refused by the incident fence."),
            Map.entry("INCIDENT_RELEASE_INVALID", "The release request is not valid."),
            Map.entry("INCIDENT_RELEASE_REASON_NOT_RECORDED", "The release reason could not be recorded; nothing was released."),
            Map.entry("RESUME_RUN_NOT_ELIGIBLE", "This run cannot resume the return step (it must be a stopped Check Point failover run)."),
            Map.entry("RESUME_NOT_FOUND", "No such resume request."),
            Map.entry("RESUME_NOT_PENDING", "That resume is not waiting for approval."),
            Map.entry("RESUME_REQUEST_EXPIRED", "The resume request expired; ask again."),
            Map.entry("RESUME_ALREADY_USED", "That resume was already used."),
            Map.entry("RESUME_TERMINAL", "That resume has finished."),
            Map.entry("RESUME_DOWN_NOT_CONFIRMED", "The member's DOWN state was not confirmed, so the return step cannot resume."),
            Map.entry("RESUME_RETURN_ALREADY_ATTEMPTED", "The return step was already attempted for this run; it is never sent twice."),
            Map.entry("RESUME_WRITE_NOT_BOUND", "The return step is not bound to a member; nothing was sent."));

    private final NexusSessionCli cli;

    FailoverCli(NexusSessionCli cli) {
        this.cli = cli;
    }

    static void printUsage() {
        System.out.println("  failover-summary [--vendor check_point|palo_alto]            every unit: readiness, last run, eligibility");
        System.out.println("  failover-units <clusterRef> | --member <deviceId> [--vendor v]   the unit screen's unit list");
        System.out.println("  failover-incidents [--vendor v]                              open failover incidents (security_admin releases)");
        System.out.println("  failover-runs <clusterId> <unitId> [--vendor v] | failover-run <runId> [--vendor v]");
        System.out.println("  failover-approvals <clusterId> <unitId> [--vendor v]         requests with revision and policy");
        System.out.println("  failover-readiness <clusterId> <unitId> [--vendor v]         HA readiness checks of one unit (read-only)");
        System.out.println("  failover-request-approval <clusterId> <unitId> --reason <text> --until <instant> [--from <instant>]");
        System.out.println("                            [--request-id <id>] [--vendor v]   ask for a failover approval (default window start: now)");
        System.out.println("  failover-approve <requestId> <clusterId> <unitId> <revision> [--vendor v]   the second administrator's approval");
        System.out.println("  failover-start <clusterId> <unitId> <requestId> --confirm-warning [--vendor v]   start the approved failover");
        System.out.println("  failover-incident-release <runId> --reason <text>            security_admin: release a run's incident");
        System.out.println("  failover-resume <runId> --incident <incidentId> --reason <text>   ask to resume the return step (Check Point)");
        System.out.println("  failover-resume-approve <runId> <resumeId>                   second administrator approves a pending resume");
        System.out.println("                                                 every write also accepts --business-hours-ack; refusals print their code in words");
    }

    /** @return exit code, or -1 when {@code args[0]} is not a failover command */
    int run(String[] args) throws IOException, InterruptedException {
        return switch (args[0]) {
            case "failover-summary" -> {
                Parsed p = parse(args, "failover-summary [--vendor check_point|palo_alto]", 0, Set.of("--vendor"), Set.of());
                yield read(plane(p) + "/summary");
            }
            case "failover-units" -> {
                String usage = "failover-units <clusterRef> | --member <deviceId> [--vendor check_point|palo_alto]";
                Parsed p = parse(args, usage, 0, Set.of("--vendor", "--member"), Set.of());
                String member = p.options.get("--member");
                if ((member == null) == (p.positional.size() != 1)) throw usageError(usage);
                String query = member != null ? "memberDeviceId=" + enc(opaque(member)) : "clusterRef=" + enc(opaque(p.positional.get(0)));
                yield read(plane(p) + "/units?" + query);
            }
            case "failover-incidents" -> {
                Parsed p = parse(args, "failover-incidents [--vendor check_point|palo_alto]", 0, Set.of("--vendor"), Set.of());
                yield read(plane(p) + "/incidents");
            }
            case "failover-runs" -> {
                Parsed p = parse(args, "failover-runs <clusterId> <unitId> [--vendor check_point|palo_alto]", 2, Set.of("--vendor"), Set.of());
                yield read(plane(p) + "/runs?" + unitQuery(p));
            }
            case "failover-run" -> {
                Parsed p = parse(args, "failover-run <runId> [--vendor check_point|palo_alto]", 1, Set.of("--vendor"), Set.of());
                yield read(plane(p) + "/runs/" + enc(opaque(p.positional.get(0))));
            }
            case "failover-approvals" -> {
                Parsed p = parse(args, "failover-approvals <clusterId> <unitId> [--vendor check_point|palo_alto]", 2, Set.of("--vendor"), Set.of());
                yield read(plane(p) + "/approvals?" + unitQuery(p));
            }
            case "failover-readiness" -> {
                Parsed p = parse(args, "failover-readiness <clusterId> <unitId> [--vendor check_point|palo_alto]", 2, Set.of("--vendor"), Set.of());
                String unitId = opaque(p.positional.get(1));
                yield write("POST", plane(p) + "/units/" + enc(unitId) + "/readiness",
                        NexusSessionCli.json(unitBody(p)));
            }
            case "failover-request-approval" -> requestApproval(args);
            case "failover-approve" -> {
                Parsed p = parse(args, "failover-approve <requestId> <clusterId> <unitId> <revision> [--vendor check_point|palo_alto]",
                        4, Set.of("--vendor"), Set.of());
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("clusterId", opaque(p.positional.get(1)));
                body.put("unitId", opaque(p.positional.get(2)));
                body.put("revision", revision(p.positional.get(3), args[0]));
                yield write("POST", plane(p) + "/approvals/" + enc(opaque(p.positional.get(0))) + "/approve", NexusSessionCli.json(body));
            }
            case "failover-start" -> start(args);
            case "failover-incident-release" -> {
                String usage = "failover-incident-release <runId> --reason <text>";
                Parsed p = parse(args, usage, 1, Set.of("--reason"), Set.of());
                yield write("POST", CP + "/runs/" + enc(opaque(p.positional.get(0))) + "/incident/release",
                        NexusSessionCli.json(Map.of("reason", requiredText(p, "--reason", usage))));
            }
            case "failover-resume" -> {
                String usage = "failover-resume <runId> --incident <incidentId> --reason <text>";
                Parsed p = parse(args, usage, 1, Set.of("--incident", "--reason"), Set.of());
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("expectedIncidentId", opaque(requiredText(p, "--incident", usage)));
                body.put("reason", requiredText(p, "--reason", usage));
                body.put("businessHoursAck", cli.businessHoursAck());
                yield write("POST", CP + "/runs/" + enc(opaque(p.positional.get(0))) + "/resume-return", NexusSessionCli.json(body));
            }
            case "failover-resume-approve" -> {
                Parsed p = parse(args, "failover-resume-approve <runId> <resumeId>", 2, Set.of(), Set.of());
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("resumeId", opaque(p.positional.get(1)));
                body.put("businessHoursAck", cli.businessHoursAck());
                yield write("POST", CP + "/runs/" + enc(opaque(p.positional.get(0))) + "/resume-return/approve", NexusSessionCli.json(body));
            }
            default -> -1;
        };
    }

    // ---- commands with a body of their own -------------------------------------------------------------------

    private int requestApproval(String[] args) throws IOException, InterruptedException {
        String usage = "failover-request-approval <clusterId> <unitId> --reason <text> --until <instant> [--from <instant>] "
                + "[--request-id <id>] [--vendor check_point|palo_alto]";
        Parsed p = parse(args, usage, 2, Set.of("--vendor", "--reason", "--until", "--from", "--request-id"), Set.of());
        String reason = requiredText(p, "--reason", usage);
        Instant until = instant(requiredText(p, "--until", usage), usage);
        Instant from = p.options.containsKey("--from") ? instant(p.options.get("--from"), usage) : Instant.now();
        String requestId = p.options.containsKey("--request-id") ? opaque(p.options.get("--request-id")) : UUID.randomUUID().toString();
        Map<String, Object> body = unitBody(p);
        body.put("windowFrom", from.toString());
        body.put("windowUntil", until.toString());
        body.put("reason", reason);
        body.put("requestId", requestId);
        System.err.println("request id: " + requestId);
        return write("POST", plane(p) + "/approvals", NexusSessionCli.json(body));
    }

    /**
     * The initiator starts an approved request: like the screen, the CLI reads the approval list for the request's
     * revision and execution token (shown to the initiator only), then posts the run. The token never travels on the
     * command line.
     */
    private int start(String[] args) throws IOException, InterruptedException {
        String usage = "failover-start <clusterId> <unitId> <requestId> --confirm-warning [--vendor check_point|palo_alto]";
        Parsed p = parse(args, usage, 3, Set.of("--vendor"), Set.of("--confirm-warning"));
        String requestId = opaque(p.positional.get(2));
        HttpResponse<byte[]> listing = cli.call("GET", plane(p) + "/approvals?" + unitQuery(p), null);
        if (listing.statusCode() / 100 != 2) return failure(listing);
        String entry = approvalEntry(new String(listing.body(), StandardCharsets.UTF_8), requestId);
        Matcher revision = Pattern.compile("\"revision\"\\s*:\\s*([0-9]{1,18})").matcher(entry == null ? "" : entry);
        Matcher nonce = Pattern.compile("\"executionNonce\"\\s*:\\s*\"([^\"]{1,200})\"").matcher(entry == null ? "" : entry);
        if (entry == null || !revision.find() || !nonce.find()) {
            System.err.println("REQUEST_BINDING_REQUIRED: " + REFUSALS.get("REQUEST_BINDING_REQUIRED")
                    + " That request is not in your approval list, or only its initiator can start it; nothing was sent.");
            return 1;
        }
        Map<String, Object> body = unitBody(p);
        body.put("scheduledFor", null);
        body.put("requestId", requestId);
        body.put("revision", Long.parseLong(revision.group(1)));
        body.put("executionNonce", nonce.group(1));
        body.put("warningConfirmed", p.flags.contains("--confirm-warning"));
        return write("POST", plane(p) + "/runs", NexusSessionCli.json(body));
    }

    /** The flat approval object whose {@code requestId} is {@code requestId}, or null. Braces inside strings are skipped. */
    static String approvalEntry(String listing, String requestId) {
        int depth = 0, start = -1;
        boolean inString = false;
        for (int i = 0; i < listing.length(); i++) {
            char c = listing.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth++ == 0) start = i;
            } else if (c == '}' && --depth == 0 && start >= 0) {
                String object = listing.substring(start, i + 1);
                if (NexusSessionCli.jsonString(object, "requestId").filter(requestId::equals).isPresent()) return object;
                start = -1;
            }
        }
        return null;
    }

    // ---- calls and output ---------------------------------------------------------------------------------------

    private int read(String path) throws IOException, InterruptedException {
        return finish(cli.call("GET", path, null));
    }

    private int write(String method, String path, String body) throws IOException, InterruptedException {
        return finish(cli.call(method, path, body));
    }

    private static int finish(HttpResponse<byte[]> response) {
        return response.statusCode() / 100 == 2 ? NexusSessionCli.print(response) : failure(response);
    }

    /** A refusal prints {@code CODE: plain words}; the business-hours confirm and anything without a code use the shared printer. */
    private static int failure(HttpResponse<byte[]> response) {
        String body = new String(response.body(), StandardCharsets.UTF_8);
        String code = NexusSessionCli.jsonString(body, "code").orElse("");
        if (!code.matches("[A-Z][A-Z0-9_]{2,80}") || NexusSessionCli.BUSINESS_HOURS_CONFIRM_CODE.equals(code)) {
            return NexusSessionCli.print(response);
        }
        System.err.println(code + ": " + REFUSALS.getOrDefault(code, "The server refused this request.")
                + (response.statusCode() == 409 ? "" : " (HTTP " + response.statusCode() + ")"));
        return response.statusCode() == 401 || response.statusCode() == 403 ? 3 : 1;
    }

    // ---- argument handling --------------------------------------------------------------------------------------

    private record Parsed(List<String> positional, Map<String, String> options, Set<String> flags) {
    }

    /** Positionals are exactly {@code positionals} (or, for failover-units, checked by the caller); options and flags are closed lists. */
    private static Parsed parse(String[] args, String usage, int positionals, Set<String> valueOptions, Set<String> flagOptions) {
        List<String> positional = new ArrayList<>();
        Map<String, String> options = new LinkedHashMap<>();
        Set<String> flags = new java.util.HashSet<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (valueOptions.contains(arg)) {
                if (i + 1 >= args.length || args[i + 1].startsWith("--") || options.put(arg, args[++i]) != null) throw usageError(usage);
            } else if (flagOptions.contains(arg)) {
                flags.add(arg);
            } else if (arg.startsWith("--")) {
                throw usageError(usage);
            } else {
                positional.add(arg);
            }
        }
        boolean lenient = "failover-units".equals(args[0]);
        if (!lenient && positional.size() != positionals) throw usageError(usage);
        String vendor = options.get("--vendor");
        if (vendor != null && !VENDORS.contains(vendor)) throw usageError(usage);
        return new Parsed(positional, options, flags);
    }

    private static IllegalArgumentException usageError(String usage) {
        return new IllegalArgumentException("usage: " + usage);
    }

    private static String plane(Parsed p) {
        return "palo_alto".equals(p.options.get("--vendor")) ? PAN : CP;
    }

    private static String requiredText(Parsed p, String option, String usage) {
        String value = p.options.get(option);
        if (value == null || value.isBlank()) throw usageError(usage);
        return value;
    }

    private static Instant instant(String value, String usage) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw usageError(usage + "  (instants look like 2026-10-22T09:00:00Z)");
        }
    }

    private static long revision(String value, String command) {
        if (!value.matches("[0-9]{1,18}")) throw usageError(command + ": the revision is a number, got: " + value);
        return Long.parseLong(value);
    }

    private static Map<String, Object> unitBody(Parsed p) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clusterId", opaque(p.positional.get(0)));
        body.put("unitId", opaque(p.positional.get(1)));
        return body;
    }

    private static String unitQuery(Parsed p) {
        return "clusterId=" + enc(opaque(p.positional.get(0))) + "&unitId=" + enc(opaque(p.positional.get(1)));
    }

    private static String opaque(String value) {
        return NexusSessionCli.opaque(value);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
