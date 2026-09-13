package com.securityexpert.nexus.ui2.worker.discovery.cp;

import java.util.List;

import com.securityexpert.nexus.ui2.discovery.cp.ObjectType;

/**
 * T-2/T-3: the closed, class-0-read command set this transport may ever
 * issue, declared once. Every entry is documented, with all ten
 * network-device-command-gate items, in {@code
 * docs/design/CP_DISCOVERY_MANAGEMENT_COMMAND_GATE_ENTRIES.md} (DRAFT,
 * pending Product Owner gate approval). {@link ManagementPlaneEnumerationAdapter}
 * builds every command string it sends through this class and nowhere else
 * (AC-3); {@link ClosedCommandSetTest} asserts every command a fixture run
 * ever produces is a member of {@link #CLOSED_COMMAND_PREFIXES}.
 *
 * <p>Concrete syntax below (the {@code mgmt_cli} tool name, its flags, the
 * per-object-type command names) is an UNVERIFIED candidate, exactly like
 * every {@code ManagementApiFieldBinding} entry: no worker of this movement
 * has run it against the Product Owner's live management server. It is
 * read-only in every branch (class 0): no object write, no policy
 * operation, no publish, and every session/context command a login-shaped
 * read only.</p>
 */
public final class ManagementShellCommands {

    /** T-6/CS-3: kept small and fixed so a page count is a real, observable number, never a port. */
    static final int PAGE_SIZE = 50;

    private ManagementShellCommands() {
    }

    /** Closed set of command-string prefixes: every command this adapter can ever send starts with exactly one of these. */
    public static final List<String> CLOSED_COMMAND_PREFIXES = List.of(
            "mgmt_cli login -r true -f json",
            "mgmt_cli login-to-domain --session-id ",
            "mgmt_cli show-domains --session-id ",
            "mgmt_cli show-gateways-and-servers --session-id ",
            "mgmt_cli show-clusters --session-id ",
            "mgmt_cli show-cluster-members --session-id ",
            "mgmt_cli show-connectivity-state --session-id ");

    public static boolean isMemberOfClosedSet(String command) {
        return CLOSED_COMMAND_PREFIXES.stream().anyMatch(command::startsWith);
    }

    /** One authenticated, read-only session (T-1); no domain context yet. */
    public static String login() {
        return "mgmt_cli login -r true -f json";
    }

    /** T-2 context switch: not a data query, not counted by check 17's formula. */
    public static String loginToDomain(String sessionId, String domainIdentifier) {
        return "mgmt_cli login-to-domain --session-id " + quote(sessionId)
                + " --domain " + quote(domainIdentifier) + " -f json";
    }

    /** T-2 domain enumeration, one page. */
    public static String showDomains(String sessionId, int offset) {
        return "mgmt_cli show-domains --session-id " + quote(sessionId)
                + " --offset " + offset + " --limit " + PAGE_SIZE + " -f json";
    }

    /** T-2 per-domain object query, one page, for the queried object type of §4.1. */
    public static String showObjects(ObjectType objectType, String sessionId, int offset) {
        return objectQueryCommandName(objectType) + " --session-id " + quote(sessionId)
                + " --offset " + offset + " --limit " + PAGE_SIZE + " -f json";
    }

    /** §7.4: reads the management server's own connection table. Not paginated, not counted by check 17's formula. */
    public static String showConnectionTable(String sessionId) {
        return "mgmt_cli show-connectivity-state --session-id " + quote(sessionId) + " -f json";
    }

    private static String objectQueryCommandName(ObjectType objectType) {
        return switch (objectType) {
            case GATEWAY -> "mgmt_cli show-gateways-and-servers";
            case CLUSTER -> "mgmt_cli show-clusters";
            case MEMBER -> "mgmt_cli show-cluster-members";
        };
    }

    /**
     * T-5/AGENTS.md "Diagnostic-path law": {@code sessionId} and {@code
     * domainIdentifier} are values this run parsed out of the management
     * server's own responses, not a literal this class controls -- quoted
     * as a single POSIX shell argument so neither can inject a second shell
     * command into the one session this run is authorized to hold open.
     */
    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
