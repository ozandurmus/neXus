package com.securityexpert.nexus.ui2.persistence.device.inventory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One member of an Infoblox grid as the Grid Manager lists it (V72, PO 2026-09-25): identity, role in the grid,
 * hardware, HA, node health and the member services -- facts observed on a read, never inferred. {@code services} are
 * {@code service=status} pairs (for example {@code DNS=WORKING}); percentages are parsed from the node's own status
 * descriptions and absent when they do not parse.
 */
public record GridMember(String memberId, String hostName, Optional<String> vipAddress, Optional<String> platform,
        Optional<String> hardwareType, Optional<String> hypervisor, boolean gridMaster, boolean masterCandidate,
        boolean haEnabled, Optional<String> haStatus, Optional<String> nodeStatus, Optional<String> replication,
        Optional<Integer> diskPercent, Optional<Integer> memoryPercent, Optional<Integer> cpuPercent,
        Optional<Integer> dbPercent, List<ServiceStatus> services, Optional<Health> health) {

    /** The closed health states a Management Center's managed device can carry (V271); anything else is {@code UNKNOWN}. */
    public static final java.util.Set<String> HEALTH_STATES = java.util.Set.of("ERROR", "WARNING", "OK", "INACTIVE", "UNKNOWN");

    /**
     * A managed device's health as its Management Center reports it (V271, PO 2026-11-24): the closed state, the error and
     * warning counts, the MC's check time as sent (never guessed) and at most three short, already-masked texts -- or, when
     * the state is {@code UNKNOWN}, the closed reason token ({@code GATE_NOT_APPROVED}, {@code HEALTH_READ_FAILED},
     * {@code NOT_IN_STATUS_ANSWER}) as the only message. Manager-reported, never a direct device read.
     */
    public record Health(String state, int errors, int warnings, Optional<String> checkedAt, List<String> messages) {
        public Health {
            Objects.requireNonNull(state, "state");
            if (!HEALTH_STATES.contains(state)) {
                throw new IllegalArgumentException("not a health state: " + state);
            }
            errors = Math.max(0, errors);
            warnings = Math.max(0, warnings);
            checkedAt = checkedAt == null ? Optional.empty() : checkedAt;
            messages = messages == null ? List.of() : List.copyOf(messages);
        }

        /** An unreadable health: {@code UNKNOWN} with the closed reason token, never {@code OK}. */
        public static Health unknown(String reason) {
            return new Health("UNKNOWN", 0, 0, Optional.empty(), List.of(reason));
        }
    }

    /** A member without health (Infoblox, FortiManager, a Management Center run recorded with the switch off). */
    public GridMember(String memberId, String hostName, Optional<String> vipAddress, Optional<String> platform,
            Optional<String> hardwareType, Optional<String> hypervisor, boolean gridMaster, boolean masterCandidate,
            boolean haEnabled, Optional<String> haStatus, Optional<String> nodeStatus, Optional<String> replication,
            Optional<Integer> diskPercent, Optional<Integer> memoryPercent, Optional<Integer> cpuPercent,
            Optional<Integer> dbPercent, List<ServiceStatus> services) {
        this(memberId, hostName, vipAddress, platform, hardwareType, hypervisor, gridMaster, masterCandidate, haEnabled, haStatus,
                nodeStatus, replication, diskPercent, memoryPercent, cpuPercent, dbPercent, services, Optional.empty());
    }

    /** The same member carrying {@code health}. */
    public GridMember withHealth(Health health) {
        return new GridMember(memberId, hostName, vipAddress, platform, hardwareType, hypervisor, gridMaster, masterCandidate,
                haEnabled, haStatus, nodeStatus, replication, diskPercent, memoryPercent, cpuPercent, dbPercent, services,
                Optional.ofNullable(health));
    }

    /** A member-level service and its status as reported ({@code WORKING}, {@code INACTIVE}, {@code WARNING}, {@code UNKNOWN}, ...). */
    public record ServiceStatus(String service, String status) {
        public ServiceStatus {
            Objects.requireNonNull(service, "service");
            Objects.requireNonNull(status, "status");
        }
    }

    public GridMember {
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(hostName, "hostName");
        vipAddress = vipAddress == null ? Optional.empty() : vipAddress;
        platform = platform == null ? Optional.empty() : platform;
        hardwareType = hardwareType == null ? Optional.empty() : hardwareType;
        hypervisor = hypervisor == null ? Optional.empty() : hypervisor;
        haStatus = haStatus == null ? Optional.empty() : haStatus;
        nodeStatus = nodeStatus == null ? Optional.empty() : nodeStatus;
        replication = replication == null ? Optional.empty() : replication;
        diskPercent = diskPercent == null ? Optional.empty() : diskPercent;
        memoryPercent = memoryPercent == null ? Optional.empty() : memoryPercent;
        cpuPercent = cpuPercent == null ? Optional.empty() : cpuPercent;
        dbPercent = dbPercent == null ? Optional.empty() : dbPercent;
        services = services == null ? List.of() : List.copyOf(services);
        health = health == null ? Optional.empty() : health;
    }
}
