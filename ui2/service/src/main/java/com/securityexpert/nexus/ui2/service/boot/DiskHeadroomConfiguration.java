package com.securityexpert.nexus.ui2.service.boot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.service.system.DiskHeadroomPolicy;

/**
 * The disk headroom thresholds are configuration: warning 80 %, critical 90 %, hysteresis 2 points by default
 * ({@code ui2.disk-headroom.*}). Invalid values fall back to the defaults with a warning rather than stopping startup.
 */
@Configuration
public class DiskHeadroomConfiguration {

    private static final System.Logger LOG = System.getLogger(DiskHeadroomConfiguration.class.getName());

    @Bean
    public DiskHeadroomPolicy diskHeadroomPolicy(
            @Value("${ui2.disk-headroom.warning-percent:80}") int warningPercent,
            @Value("${ui2.disk-headroom.critical-percent:90}") int criticalPercent,
            @Value("${ui2.disk-headroom.hysteresis-percent:2}") int hysteresisPercent) {
        return DiskHeadroomPolicy.orDefault(warningPercent, criticalPercent, hysteresisPercent,
                message -> LOG.log(System.Logger.Level.WARNING, "[DISK_HEADROOM_CONFIG_INVALID] {0}", message));
    }
}
