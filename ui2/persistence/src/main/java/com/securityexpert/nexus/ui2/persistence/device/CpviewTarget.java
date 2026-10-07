package com.securityexpert.nexus.ui2.persistence.device;

import java.util.Optional;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/** Same stored context boundary as failover selection, restricted to an enrolled gateway member. */
public final class CpviewTarget {
    public static boolean eligible(DeviceRecord device, Optional<DeviceSummaryRecord> summary, Optional<InventoryRun> run) {
        return device.permitsReadCollection() && device.enrollmentState() == DeviceEnrollmentState.ENROLLED
            && "check_point".equals(device.vendorHint()) && "gateway".equals(device.role())
            && summary.filter(s -> s.clusterMemberRef().filter(v -> !v.isBlank()).isPresent()
                && s.observedModel().filter(v -> !v.isBlank()).isPresent()
                && s.virtualSystems().filter(v -> !v.isBlank()).isEmpty()).isPresent()
            && run.filter(r -> !r.contexts().isEmpty() && r.virtualSystems().filter(v -> !v.isBlank()).isEmpty()
                && r.contexts().stream().allMatch(c -> "physical".equals(c.context()))).isPresent();
    }
    private CpviewTarget() {}
}
