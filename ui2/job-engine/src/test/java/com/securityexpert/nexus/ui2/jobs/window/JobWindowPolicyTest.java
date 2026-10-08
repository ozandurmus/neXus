package com.securityexpert.nexus.ui2.jobs.window;

import java.time.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import static org.junit.jupiter.api.Assertions.*;

class JobWindowPolicyTest {
    private final JobWindowPolicy policy = new JobWindowPolicy(Clock.systemUTC(), 60);
    private Instant at(String local) { return LocalDateTime.parse(local).atZone(JobWindowPolicy.ZONE_ID).toInstant(); }

    @Test void boundariesAndNextWindow() {
        assertFalse(policy.isOpen(at("2026-10-08T11:59:59.999")));
        assertTrue(policy.isOpen(at("2026-10-08T12:00:00")));
        assertTrue(policy.isOpen(at("2026-10-08T12:59:59.999")));
        var refused = assertThrows(JobWindowPolicy.OutsideWindow.class,
                () -> policy.requireOpen(at("2026-10-08T13:00:00")));
        assertEquals("2026-10-08T18:00+03:00[Europe/Istanbul]", refused.nextWindowStart().toString());
        assertEquals(at("2026-10-09T00:00:00"), policy.nextStart(at("2026-10-08T23:59:00")).toInstant());
    }
    @Test void zoneRulesIncludeHistoricalDstAndModernFixedIstanbulTime() {
        var winter = at("2015-01-15T12:00:00");
        var summer = at("2015-07-15T12:00:00");
        assertEquals(Instant.parse("2015-01-15T10:00:00Z"), winter);
        assertEquals(Instant.parse("2015-07-15T09:00:00Z"), summer);
        assertTrue(policy.isOpen(winter)); assertTrue(policy.isOpen(summer));
        assertEquals(Instant.parse("2026-01-15T09:00:00Z"), at("2026-01-15T12:00:00"));
    }
    @Test void configurableDurationAndInvalidConfiguration() {
        var shortWindow = new JobWindowPolicy(Clock.systemUTC(), 15);
        assertTrue(shortWindow.isOpen(at("2026-10-08T12:14:59")));
        assertFalse(shortWindow.isOpen(at("2026-10-08T12:15:00")));
        assertThrows(IllegalArgumentException.class, () -> new JobWindowPolicy(Clock.systemUTC(), 0));
        assertThrows(IllegalArgumentException.class, () -> new JobWindowPolicy(Clock.systemUTC(), 361));
    }
    @Test void onlyNamedInternalTasksAreExempt() {
        var outside = at("2026-10-08T13:00:00");
        assertEquals(Set.of("db_maintenance", "lease_reconciliation", "owner_heartbeat", "compliance_evaluation",
                "cluster_diff", "notification_forwarding"), JobWindowPolicy.INTERNAL_TASKS);
        for (var task : JobWindowPolicy.INTERNAL_TASKS) assertTrue(policy.permitsInternalTask(task, outside));
        for (var task : Set.of("diagnostic_read", "cp_failover_readiness", "pan_cluster_failover", "unknown"))
            assertFalse(policy.permitsInternalTask(task, outside));
    }
}
