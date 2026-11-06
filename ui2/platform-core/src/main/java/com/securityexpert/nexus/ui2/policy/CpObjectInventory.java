package com.securityexpert.nexus.ui2.policy;

import java.util.List;

/** Parsed management intent for one domain/type, never direct gateway runtime truth. */
public record CpObjectInventory(String sourceId, String containerId, String type, String collectedAt,
        String status, String reason, List<Item> objects, int pages, double seconds) {
    public CpObjectInventory { objects = List.copyOf(objects); }
    /** A reported package name alone does not prove installation. Null means unknown. */
    public record Installation(String policyName, Boolean installed) {}
    /** One Virtual System interface address with its mask length (1..32); nothing else of the interface is kept. */
    public record VsInterface(String address, int maskLength) {}
    /**
     * Management-side interface addresses of a Virtual System object. {@code complete} is false when any interface
     * lacked a valid address or mask, or two interfaces disagree: such an object never binds (fail closed).
     */
    public record VsAddresses(List<VsInterface> interfaces, boolean complete) {
        public VsAddresses { interfaces = interfaces == null ? List.of() : List.copyOf(interfaces); }
    }
    /** {@code vs} is null for every non-Virtual-System object and for payloads written before it existed. */
    public record Item(String id, String uid, String name, String type, List<String> members,
            List<String> values, PolicySchedule schedule, List<Installation> policyInstallations, VsAddresses vs) {
        public Item {
            members = List.copyOf(members); values = List.copyOf(values);
            policyInstallations = List.copyOf(policyInstallations);
        }
        public Item(String id, String uid, String name, String type, List<String> members,
                List<String> values, PolicySchedule schedule, List<Installation> policyInstallations) {
            this(id, uid, name, type, members, values, schedule, policyInstallations, null);
        }
    }
}
