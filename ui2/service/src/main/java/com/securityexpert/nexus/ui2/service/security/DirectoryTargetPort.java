package com.securityexpert.nexus.ui2.service.security;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import com.securityexpert.nexus.ui2.platform.DirectoryBindingKind;
import com.securityexpert.nexus.ui2.platform.DirectoryObservation;

/** Approved server-side target resolution. Corporate composition remains disabled. */
public interface DirectoryTargetPort {
    record Target(String profileId, DirectoryBindingKind kind, String reference, Instant validUntil,
            DirectoryObservation.Publication publication) {
        @Override public String toString() { return "Target[redacted]"; }
    }
    List<Target> list(String profileId, DirectoryBindingKind kind, Instant now);
    Optional<Target> resolve(Target selected, Instant now);
}
