package com.securityexpert.nexus.ui2.service.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class LocalAuthenticationConfigurationTest {

    @Test
    void configuresThirtyMinuteIdleTimeout() {
        assertEquals(Duration.ofMinutes(30), LocalAuthenticationConfiguration.IDLE_TIMEOUT);
    }
}
