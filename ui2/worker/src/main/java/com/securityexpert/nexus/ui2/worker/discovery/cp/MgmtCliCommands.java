package com.securityexpert.nexus.ui2.worker.discovery.cp;

public final class MgmtCliCommands {
    private MgmtCliCommands() {}

    public static String domainList() {
        return "mgmt_cli -r true -f json show-domains limit 500 details-level full";
    }

    public static String showGatewaysAndServers(String domainIdentifier) {
        return "mgmt_cli -r true -d " + quote(domainIdentifier) + " -f json show-gateways-and-servers limit 500 details-level full";
    }

    public static String connectionTable() {
        return "netstat -an";
    }

    public static String showLastPublishedSession(String domain) {
        return "mgmt_cli -r true -d " + quote(domain) + " -f json show-last-published-session";
    }

    public static String showPackages(String domain) {
        return showPackages(domain, 0, 50);
    }

    public static String showPackages(String domain, int offset, int limit) {
        if (offset < 0 || (limit != 20 && limit != 50)) throw new IllegalArgumentException("Invalid package page");
        return "mgmt_cli -r true -d " + quote(domain) + " -f json show-packages limit " + limit
                + " offset " + quote(Integer.toString(offset)) + " details-level full";
    }

    public static String showAccessRulebase(String domain, String layer, int offset) {
        return showAccessRulebase(domain, layer, offset, 100);
    }

    public static String showAccessRulebase(String domain, String layer, int offset, int limit) {
        return accessRulebase(domain, " -f json show-access-rulebase name ", layer, offset, limit);
    }

    public static String showAccessRulebase(String domain, String uid, String name, int offset) {
        return uid == null ? showAccessRulebase(domain, name, offset)
            : accessRulebase(domain, " -f json show-access-rulebase uid ", uid, offset, 100);
    }

    private static String accessRulebase(String domain, String selector, String layer, int offset, int limit) {
        if (offset < 0 || (limit != 100 && limit != 50)) throw new IllegalArgumentException("Invalid policy page");
        return "mgmt_cli -r true -d " + quote(domain) + selector + quote(layer)
                + " limit " + limit + " offset " + quote(Integer.toString(offset)) + " details-level full use-object-dictionary true";
    }

    public static String showNatRulebase(String domain, String policy, int offset) {
        if (offset < 0) throw new IllegalArgumentException("Invalid policy offset");
        return "mgmt_cli -r true -d " + quote(domain) + " -f json show-nat-rulebase package " + quote(policy)
                + " limit 500 offset " + quote(Integer.toString(offset)) + " details-level standard use-object-dictionary true";
    }

    public static String showPolicyObjects(String domain, String type, int offset) {
        int index = com.securityexpert.nexus.ui2.jobs.policy.CpPolicyGates.OBJECT_TYPES.indexOf(type);
        if (index < 0 || offset < 0) throw new IllegalArgumentException("Invalid object page");
        return "mgmt_cli -r true -d " + quote(domain) + " -f json show-" + type
            + " limit 50 offset " + quote(Integer.toString(offset)) + " details-level full"
            + (type.equals("groups") || type.equals("service-groups") ? " dereference-group-members false" : "");
    }

    /**
     * C-1 (policy completeness, PO approval 2026-10-18): {@code from-date}/{@code to-date} are ISO 8601 publish times the
     * vendor printed; the offset is a multiple of the approved {@code limit 10}.
     */
    public static String showChanges(String domain, String from, String to, int offset) {
        if (offset < 0 || offset % com.securityexpert.nexus.ui2.jobs.policy.PolicyCompletenessGates.CP_SHOW_CHANGES_LIMIT != 0
                || offset >= com.securityexpert.nexus.ui2.jobs.policy.PolicyCompletenessGates.CP_SHOW_CHANGES_LIMIT
                    * com.securityexpert.nexus.ui2.jobs.policy.PolicyCompletenessGates.CP_SHOW_CHANGES_MAX_OFFSETS
                || from == null || to == null
                || !com.securityexpert.nexus.ui2.jobs.policy.PolicyCompletenessGates.CP_ISO_TIME.matcher(from).matches()
                || !com.securityexpert.nexus.ui2.jobs.policy.PolicyCompletenessGates.CP_ISO_TIME.matcher(to).matches())
            throw new IllegalArgumentException("Invalid show-changes window");
        return "mgmt_cli -r true -d " + quote(domain) + " -f json show-changes from-date " + quote(from) + " to-date " + quote(to)
                + " limit " + com.securityexpert.nexus.ui2.jobs.policy.PolicyCompletenessGates.CP_SHOW_CHANGES_LIMIT
                + " offset " + quote(Integer.toString(offset));
    }

    /** C-2: {@code <UID>} is the task id the C-1 answer returned. */
    public static String showTask(String domain, String taskId) {
        if (taskId == null || !com.securityexpert.nexus.ui2.jobs.policy.PolicyCompletenessGates.CP_TASK_ID.matcher(taskId).matches())
            throw new IllegalArgumentException("Invalid task id");
        return "mgmt_cli -r true -d " + quote(domain) + " -f json show-task task-id " + quote(taskId) + " details-level full";
    }

    private static String quote(String value) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid management argument");
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
