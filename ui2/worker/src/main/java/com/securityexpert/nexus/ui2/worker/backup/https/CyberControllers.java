package com.securityexpert.nexus.ui2.worker.backup.https;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target;

/** The enrolled, enabled Radware Cyber Controllers (vendor radware, role management_server; V67). */
public final class CyberControllers {

    public record Ref(String deviceId, Target target, String credentialRef) {
    }

    private CyberControllers() {
    }

    public static List<Ref> enrolled(DeviceRepository devices) {
        List<Ref> out = new ArrayList<>();
        for (DeviceSummaryRecord s : devices.listAll()) {
            if (!"radware".equals(s.vendorHint()) || !"management_server".equals(s.role())
                    || s.enrollmentState() != DeviceEnrollmentState.ENROLLED) {
                continue;
            }
            Optional<DeviceRecord> record = devices.find(s.deviceId());
            Optional<EndpointRecord> endpoint = devices.findEndpointByDeviceId(s.deviceId());
            if (record.isEmpty() || record.get().disabled() || endpoint.isEmpty()) {
                continue;
            }
            out.add(new Ref(s.deviceId(), target(endpoint.get().addressRef()), record.get().credentialReferenceId()));
        }
        return out;
    }

    static Target target(String addressRef) {
        int colon = addressRef.lastIndexOf(':');
        if (colon >= 0) {
            try {
                return new Target(addressRef.substring(0, colon), Integer.parseInt(addressRef.substring(colon + 1)));
            } catch (NumberFormatException notAPort) {
                // a bare host
            }
        }
        return new Target(addressRef, 443);
    }
}
