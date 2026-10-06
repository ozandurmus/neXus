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
    public FailoverPilotAllowlist failoverPilotAllowlist() {
        return new FailoverPilotAllowlist();
    }
}
