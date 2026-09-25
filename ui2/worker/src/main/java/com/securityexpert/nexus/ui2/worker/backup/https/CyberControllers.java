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
        return managers(devices, "radware");
    }

    /** Enrolled, enabled management servers of a vendor (a Blue Coat one is a Management Center, reached on 8082). */
    public static List<Ref> managers(DeviceRepository devices, String vendorHint) {
        List<Ref> out = new ArrayList<>();
        for (DeviceSummaryRecord s : devices.listAll()) {
            if (!vendorHint.equals(s.vendorHint()) || !"management_server".equals(s.role())
                    || s.enrollmentState() != DeviceEnrollmentState.ENROLLED) {
                continue;
            }
            Optional<DeviceRecord> record = devices.find(s.deviceId());
            Optional<EndpointRecord> endpoint = devices.findEndpointByDeviceId(s.deviceId());
            if (record.isEmpty() || record.get().disabled() || endpoint.isEmpty()) {
                continue;
            }
            Target t = target(endpoint.get().addressRef());
            if ("bluecoat".equals(vendorHint) && endpoint.get().addressRef().lastIndexOf(':') < 0) {
                t = new Target(t.host(), HttpsVendorPlan.MC_DEFAULT_PORT);
            }
            out.add(new Ref(s.deviceId(), t, record.get().credentialReferenceId()));
        }
        return out;
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> LOCKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Runs {@code work} while holding this management server's lock: a Cyber Controller answered HTTP 500 to logins
     * when the bulk Collect opened five sessions on it at once (measured 2026-09-25); one at a time per host, it answers.
     */
    public static <T> T oneAtATime(Target target, java.util.function.Supplier<T> work) {
        java.util.concurrent.locks.ReentrantLock lock = LOCKS.computeIfAbsent(target.host() + ":" + target.port(),
                k -> new java.util.concurrent.locks.ReentrantLock(true));
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
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
