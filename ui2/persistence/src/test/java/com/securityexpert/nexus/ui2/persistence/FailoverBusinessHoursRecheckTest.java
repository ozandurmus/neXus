package com.securityexpert.nexus.ui2.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.function.Function;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.BusinessHours;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ManualAdmission;

/**
 * PO decision 2026-10-20 (failover status review): the business-hours acknowledgement is bound to the failover job row
 * and rechecked inside {@code mutationAdmission}, the fence every clusterXL_admin / PAN suspend write passes first.
 */
class FailoverBusinessHoursRecheckTest {

    private static JooqCpFailoverRepository repositoryAt(String local) {
        var policy = new JobWindowPolicy(Clock.fixed(LocalDateTime.parse(local).atZone(JobWindowPolicy.ZONE_ID).toInstant(),
                JobWindowPolicy.ZONE_ID), 60, new BusinessHours(BusinessHours.DEFAULT_START, BusinessHours.DEFAULT_END));
        TransactionBoundary unused = new TransactionBoundary() {
            @Override public <T> T inTransaction(Function<DSLContext, T> work) { throw new AssertionError("unused"); }
        };
        return new JooqCpFailoverRepository(unused, true, policy);
    }

    private static DSLContext jobRow(String bypassRole, String ackAt) {
        var plain = DSL.using(SQLDialect.POSTGRES);
        return DSL.using(new MockConnection(query -> new MockResult[] { new MockResult(1, plain.fetchFromStringData(
                new String[][] {{"window_bypass_role", "business_hours_ack_at"}, {bypassRole, ackAt}})) }), SQLDialect.POSTGRES);
    }

    @Test void anUnacknowledgedAdminJobIsStoppedBeforeAWriteOnceBusinessHoursBegin() {
        // Submitted at 07:50 (outside business hours, no acknowledgement needed); the write would happen at 08:01.
        assertEquals(ManualAdmission.CONFIRM_CODE,
                repositoryAt("2026-10-08T08:01:00").businessHoursRefusal(jobRow("operation_admin", null), "job-1"));
        assertNull(repositoryAt("2026-10-08T07:59:00").businessHoursRefusal(jobRow("operation_admin", null), "job-1"));
        assertNull(repositoryAt("2026-10-08T20:00:00").businessHoursRefusal(jobRow("security_admin", null), "job-1"));
    }

    @Test void anAcknowledgedAdminJobAndAWindowAdmittedJobWriteAtAnyHour() {
        assertNull(repositoryAt("2026-10-08T13:00:00").businessHoursRefusal(
                jobRow("security_admin", "2026-10-08 10:00:00+00"), "job-1"));
        // A scheduled start (startDue) has no bypass role: the windows already govern it.
        assertNull(repositoryAt("2026-10-08T12:10:00").businessHoursRefusal(jobRow(null, null), "job-1"));
    }

    @Test void theRecheckRunsInsideTheMutationFenceBeforeTheDispatchIsAuthorized() throws Exception {
        String source = Files.readString(Path.of(System.getProperty("user.dir"))
                .resolve("src/main/java/com/securityexpert/nexus/ui2/persistence/JooqCpFailoverRepository.java"));
        int fence = source.indexOf("public String mutationAdmission(String id,String cluster");
        int owner = source.indexOf("if (owner==null) return \"OWNERSHIP_LOST\";", fence);
        int recheck = source.indexOf("businessHoursRefusal(dsl,jobId)", fence);
        int authorize = source.indexOf("Linearization point", fence);
        assertTrue(fence > 0 && owner > fence && recheck > owner && authorize > recheck,
                "the business-hours recheck follows the ownership check and precedes the dispatch authorization");
        // Every failover job the admission creates records the grant on its row.
        int admit = source.indexOf("String vendor, String runKind) {");
        assertTrue(source.indexOf("ManualAdmissionRecord.record(dsl, jobId, actor, windows)", admit) > admit);
    }
}
