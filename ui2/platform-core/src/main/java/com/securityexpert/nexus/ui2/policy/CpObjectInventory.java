package com.securityexpert.nexus.ui2.policy;

import java.util.List;

/** Parsed management intent for one domain/type, never direct gateway runtime truth. */
public record CpObjectInventory(String sourceId, String containerId, String type, String collectedAt,
        String status, String reason, List<Item> objects, int pages, double seconds) {
    public CpObjectInventory { objects = List.copyOf(objects); }
    /** A reported package name alone does not prove installation. Null means unknown. */
    public record Installation(String policyName, Boolean installed) {}
    public record Item(String id, String uid, String name, String type, List<String> members,
            List<String> values, PolicySchedule schedule, List<Installation> policyInstallations) {
        public Item {
            members = List.copyOf(members); values = List.copyOf(values);
            policyInstallations = List.copyOf(policyInstallations);
        }
    }
}
