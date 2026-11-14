package com.securityexpert.nexus.ui2.service.failover;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;

/**
 * Read-only cluster view of a Cisco ASA failover pair or a FortiGate HA cluster, built only from the HA role each member's
 * latest inventory read already stored (ASA "active" / "standby", FortiGate "primary" / "secondary"). No device is asked
 * anything. A role that is missing or not recognised is reported as such, never inferred from the other member or from a
 * hostname. Sync state is not collected for either vendor, so it is always {@code UNKNOWN}.
 */
public final class HaPairView {
    public static final String HEALTHY = "HEALTHY";
    public static final String DEGRADED = "DEGRADED";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String ROLE_UNKNOWN = "unknown";
    /** Stored role words that say the unit itself is not taking part in the pair. */
    private static final Set<String> FAILED_ROLES = Set.of("failed", "not detected", "disabled");

    private HaPairView() {}

    public record Member(String deviceId, String role) {}
    public record View(String vendor, String state, String reason, String sync, String syncReason, List<Member> members) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("vendor", vendor);
            m.put("state", state);
            m.put("reason", reason);
            m.put("sync", sync);
            m.put("syncReason", syncReason);
            m.put("members", members.stream().map(member -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("device_id", member.deviceId());
                row.put("role", member.role());
                return row;
            }).toList());
            return m;
        }
    }

    /** The two roles a healthy pair of this vendor reports, one each; empty for a vendor with no pair view. */
    private static Set<String> pairRoles(FailoverVendor vendor) {
        return switch (vendor) {
            case CISCO_ASA -> Set.of("active", "standby");
            case FORTINET -> Set.of("primary", "secondary");
            default -> Set.of();
        };
    }

    public static boolean covers(FailoverVendor vendor) {
        return !pairRoles(vendor).isEmpty();
    }

    public static View of(FailoverVendor vendor, List<DeviceSummaryRecord> members) {
        List<Member> rows = new ArrayList<>();
        for (DeviceSummaryRecord m : members.stream().sorted(java.util.Comparator.comparing(DeviceSummaryRecord::deviceId)).toList()) {
            String role = m.observedHaRole().map(r -> r.trim().toLowerCase(Locale.ROOT)).filter(r -> !r.isEmpty()).orElse(ROLE_UNKNOWN);
            rows.add(new Member(m.deviceId(), role));
        }
        String[] verdict = verdict(vendor, rows);
        return new View(vendor.dbValue(), verdict[0], verdict[1], UNKNOWN, "NOT_COLLECTED", List.copyOf(rows));
    }

    private static String[] verdict(FailoverVendor vendor, List<Member> rows) {
        Set<String> expected = pairRoles(vendor);
        if (expected.isEmpty()) return new String[] {UNKNOWN, "VENDOR_NOT_COVERED"};
        if (rows.size() != 2) return new String[] {UNKNOWN, "PAIR_INCOMPLETE"};
        // A reported failed unit is a fact even when the other member's role is missing.
        if (rows.stream().anyMatch(r -> FAILED_ROLES.contains(r.role()))) return new String[] {DEGRADED, "MEMBER_FAILED"};
        if (rows.stream().anyMatch(r -> ROLE_UNKNOWN.equals(r.role()))) return new String[] {UNKNOWN, "ROLE_NOT_OBSERVED"};
        if (rows.stream().anyMatch(r -> !expected.contains(r.role()))) return new String[] {UNKNOWN, "ROLE_NOT_RECOGNISED"};
        if (rows.get(0).role().equals(rows.get(1).role())) return new String[] {DEGRADED, "ROLES_NOT_COMPLEMENTARY"};
        return new String[] {HEALTHY, null};
    }
}
