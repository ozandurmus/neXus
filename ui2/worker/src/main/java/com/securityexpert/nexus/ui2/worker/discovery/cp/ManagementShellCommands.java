package com.securityexpert.nexus.ui2.worker.discovery.cp;

import java.util.List;

import com.securityexpert.nexus.ui2.discovery.cp.ObjectType;

/**
 * T-2/T-3: the closed, class-0-read command set this transport may ever
 * issue, aligned to the method the Product Owner measured against a live
 * multi-domain management server ({@code docs/design/
 * CP_DISCOVERY_MEASURED_METHOD_AND_COMMAND_HANDOVER_2026_09_13.md} §§3-4,
 * §10). Every entry is documented, with all ten network-device-command-gate
 * items, in {@code docs/design/CP_DISCOVERY_MANAGEMENT_COMMAND_GATE_ENTRIES.md}
 * (DRAFT, pending Product Owner gate approval). {@link
 * ManagementPlaneEnumerationAdapter} builds every command string it sends
 * through this class and nowhere else; {@code
 * ManagementShellCommandsClosedSetTest} asserts every command a fixture run
 * ever produces is a member of {@link #CLOSED_COMMAND_PREFIXES}.
 *
 * <p>No {@code mgmt_cli}, anywhere (record §0). No script upload, no device
 * contact -- everything below runs on the management server's own shell,
 * reached through the existing {@code ssh_exec} transport, and reads the
 * management database or the management server's own kernel state.</p>
 */
public final class ManagementShellCommands {

    private ManagementShellCommands() {
    }

    /** Closed set of command-string prefixes: every command this adapter can ever send starts with exactly one of these. */
    public static final List<String> CLOSED_COMMAND_PREFIXES = List.of(
            "bash -l -c 'source /etc/profile.d/CP.sh; $MDSVERUTIL AllCMAs'",
            "bash -l -c 'source /etc/profile.d/CP.sh; mdsenv ",
            "netstat -an");

    public static boolean isMemberOfClosedSet(String command) {
        return CLOSED_COMMAND_PREFIXES.stream().anyMatch(command::startsWith);
    }

    /**
     * record §4 item 1: the management-server utility that lists the domain
     * management servers. Not paginated -- the response is read as one line
     * per domain (record §10's "domain enumeration's per-line value").
     */
    public static String domainList() {
        return loginShell("source /etc/profile.d/CP.sh; $MDSVERUTIL AllCMAs");
    }

    /**
     * record §3 row 2 / §10: the domain context switch ({@code mdsenv
     * <DOMAIN>}) and the per-domain, per-object-type query MUST be issued on
     * ONE shell command line. {@code mdsenv} alters the calling shell and
     * cannot survive a subprocess boundary -- wrapping it in a separate exec
     * discards the context silently and every later query then runs against
     * the wrong domain, producing confident wrong answers, not errors (this
     * was measured by making it happen). record §4 item 5 / §10 row 4: the
     * {@code object} result type with a type filter dumps every object of
     * one type, in one call, with every field named -- no pagination, no
     * column that can shift.
     */
    public static String contextSwitchAndObjectQuery(String domainIdentifier, ObjectType objectType) {
        // The filter argument is one of exactly three closed, this-class-controlled constants (never a
        // parsed response value), embedded literally in the record's own double-quoted syntax -- quote()
        // (POSIX single-quote escaping) is reserved for domainIdentifier, the one value this run did not choose.
        // "&&", never ";": if the context switch fails, the query must NOT run in the wrong (top-level)
        // scope -- the measurement record (section 3 row 2) says a lost context yields confident wrong answers.
        return loginShell("source /etc/profile.d/CP.sh; mdsenv " + quote(domainIdentifier)
                + " && cpmiquerybin object \"\" network_objects \"type='" + objectTypeFilterValue(objectType) + "'\"");
    }

    /**
     * §7.4 / record §4 item 6: the management server's own operating-system
     * connection table -- no packet sent to any device, kernel state read
     * only. Read twice, separated by the caller's CS-3 interval; the raw
     * text is filtered and reduced in Java, never by an awk pipeline on the
     * server (T-7: the reduction logic stays out of the shell string).
     */
    public static String connectionTable() {
        return "netstat -an";
    }

    /** record §10 addendum's type-filter values, mapped from the contract's three object types. */
    private static String objectTypeFilterValue(ObjectType objectType) {
        return switch (objectType) {
            case GATEWAY -> "gateway";
            case CLUSTER -> "gateway_cluster";
            case MEMBER -> "cluster_member";
        };
    }

    /**
     * T-5/AGENTS.md "Diagnostic-path law": the domain identifier is a value
     * this run parsed out of the management server's own domain-list
     * response, not a literal this class controls -- quoted as a single
     * POSIX shell argument so it cannot inject a second shell command into
     * the one session this run is authorized to hold open.
     */
    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String loginShell(String command) {
        return "bash -l -c " + quote(command);
    }
}
