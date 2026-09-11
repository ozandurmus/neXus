package com.securityexpert.nexus.ui2.jobs.device;

import java.util.Optional;

/**
 * The read port job admission and claim-time re-checking consult
 * (adjudication F12: "the read port the engine uses declared in
 * job-engine"; F4/F6: a job whose {@code target_device_id} resolves to
 * {@code DRAFT} is refused before {@code REQUESTED} is ever reached, and
 * re-checked again at claim time -- both are B1-4's own implementation,
 * not this movement's; this port only supplies the read those two checks
 * need).
 */
public interface DeviceEnrollmentReadPort {

    Optional<DeviceEnrollmentSnapshot> findEnrollment(String deviceId);
}
