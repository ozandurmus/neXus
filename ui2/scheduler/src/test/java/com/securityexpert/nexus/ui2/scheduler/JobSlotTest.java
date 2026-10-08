package com.securityexpert.nexus.ui2.scheduler;

import java.time.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import static org.junit.jupiter.api.Assertions.*;

class JobSlotTest {
    @Test void onlyFourAutomaticBatchStartsPerDay() {
        var policy = new JobWindowPolicy(Clock.fixed(Instant.parse("2026-10-08T09:10:00Z"), JobWindowPolicy.ZONE_ID), 60);
        for (int hour = 0; hour < 24; hour++) for (int minute = 0; minute < 60; minute++) {
            var instant = LocalDate.of(2026, 10, 8).atTime(hour, minute).atZone(JobWindowPolicy.ZONE_ID).toInstant();
            assertEquals(minute == 0 && Set.of(0, 6, 12, 18).contains(hour), policy.isSlotMinute(instant));
        }
    }
}
