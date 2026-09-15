package com.securityexpert.nexus.ui2.service.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class LocalAuthenticationConfigurationTest {

    @Test
    void configuresFiveMinuteIdleTimeout() {
        assertEquals(Duration.ofMinutes(5), LocalAuthenticationConfiguration.IDLE_TIMEOUT);
    }
}
