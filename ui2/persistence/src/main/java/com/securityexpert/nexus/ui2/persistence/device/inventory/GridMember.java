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
        Optional<Integer> dbPercent, List<ServiceStatus> services) {

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
    }
}
