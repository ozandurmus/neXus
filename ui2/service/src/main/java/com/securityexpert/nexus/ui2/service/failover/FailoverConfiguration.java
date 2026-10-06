package com.securityexpert.nexus.ui2.service.failover;

import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;

import com.securityexpert.nexus.ui2.jobs.failover.pilot.FailoverPilotAllowlist;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration declaring platform beans for the failover engine subsystem.
 */
@Configuration
public class FailoverConfiguration {

    @Bean
    public FailoverMutationSwitch failoverMutationSwitch() {
        return FailoverMutationSwitch.fromEnvironment();
    }

    @Bean
    public FailoverPilotAllowlist failoverPilotAllowlist(
            com.securityexpert.nexus.ui2.persistence.device.DeviceRepository devices) {
        return new FailoverPilotAllowlist(cluster -> {
            var members=devices.findMembersByClusterRef(cluster);
            if (members.size()!=2 || members.stream().anyMatch(m ->
                    devices.find(m.deviceId()).filter(d -> d.permitsReadCollection()).isEmpty())) return null;
            return new FailoverPilotAllowlist.PilotEnrollment(cluster,"ENROLLED",
                members.stream().map(com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord::deviceId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()),"SERVER_RESOLVED",true);
        });
    }
}
