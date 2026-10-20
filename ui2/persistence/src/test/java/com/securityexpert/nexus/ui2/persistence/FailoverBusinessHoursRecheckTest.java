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

    /** A policy whose clock the test moves across 08:00 Europe/Istanbul between the run's two writes. */
    private static JobWindowPolicy movingPolicy(java.util.concurrent.atomic.AtomicReference<java.time.Instant> now) {
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return JobWindowPolicy.ZONE_ID; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public java.time.Instant instant() { return now.get(); }
        };
        return new JobWindowPolicy(clock, 60, new BusinessHours(BusinessHours.DEFAULT_START, BusinessHours.DEFAULT_END));
    }

    private static java.time.Instant local(String time) {
        return LocalDateTime.parse(time).atZone(JobWindowPolicy.ZONE_ID).toInstant();
    }

    private static JooqFailoverApprovalTest.Database unacknowledgedAdminRun(JobWindowPolicy policy, String vendor) {
        var db = new JooqFailoverApprovalTest.Database(policy);
        db.bypassRole = ManualAdmission.OPERATION_ADMIN; // submitted before 08:00: no acknowledgement on the job row
        db.ackAt = null;
        db.run.put("vendor", vendor); db.approval.put("vendor", vendor);
        return db;
    }

    private static String admission(JooqFailoverApprovalTest.Database db, String vendor) {
        return db.repository.mutationAdmission("run-a", "unit-a", null, vendor, java.util.Set.of("member-a", "member-b"),
                false, "job-a", 1);
    }

    /**
     * Argon review 2026-10-20 P1-1, Check Point (clusterXL_admin down at 07:59:30, up at 08:01) and Palo Alto (suspend,
     * then functional): business hours beginning between the run's two writes never block the return write.
     */
    @Test void theReturnWriteIsNeverRefusedForBusinessHoursThatBeganAfterTheFirstWrite() {
        for (String vendor : java.util.List.of("check_point", "palo_alto")) {
            var now = new java.util.concurrent.atomic.AtomicReference<>(local("2026-10-08T07:59:30"));
            var db = unacknowledgedAdminRun(movingPolicy(now), vendor);
            assertEquals("ADMITTED", admission(db, vendor), vendor + ": the first write, before business hours");
            db.run.put("mutation_possible", true); // prepareDispatch committed the first write's intent
            now.set(local("2026-10-08T08:01:00"));
            assertEquals("ADMITTED", admission(db, vendor), vendor + ": the return write restores the cluster");
        }
    }

    /** The other crossing: the run was admitted before 08:00 but its first write would be sent after 08:00. */
    @Test void aFirstWriteAfterBusinessHoursBeganIsStillRefusedWithoutTheAcknowledgement() {
        for (String vendor : java.util.List.of("check_point", "palo_alto")) {
            var now = new java.util.concurrent.atomic.AtomicReference<>(local("2026-10-08T07:59:30"));
            var db = unacknowledgedAdminRun(movingPolicy(now), vendor);
            assertEquals("ADMITTED", admission(db, vendor), vendor + ": the start-of-run check before 08:00");
            now.set(local("2026-10-08T08:00:00"));
            assertEquals(ManualAdmission.CONFIRM_CODE, admission(db, vendor), vendor + ": no write was prepared yet");
            // An acknowledged job writes at any hour.
            db.ackAt = "2026-10-08 05:00:00+00";
            assertEquals("ADMITTED", admission(db, vendor));
        }
    }

    /** Only business hours are lifted for the return write; every other safeguard of the fence still refuses it. */
    @Test void theReturnWriteKeepsEveryOtherSafeguard() {
        var now = new java.util.concurrent.atomic.AtomicReference<>(local("2026-10-08T08:01:00"));
        var db = unacknowledgedAdminRun(movingPolicy(now), "check_point");
        db.run.put("mutation_possible", true);
        assertEquals("ADMITTED", admission(db, "check_point"));
        db.approval.put("revoked_at", java.time.OffsetDateTime.now());
        assertEquals("APPROVAL_REVOKED", admission(db, "check_point"));
        db.approval.put("revoked_at", null);
        db.owner = false;
        assertEquals("OWNERSHIP_LOST", admission(db, "check_point"));
        db.owner = true;
        db.incident = "incident-a";
        assertEquals("OPEN_INCIDENT", admission(db, "check_point"));
        db.incident = null;
        db.members = java.util.Set.of("member-a", "member-new");
        assertEquals("MEMBER_SET_CHANGED", admission(db, "check_point"));
        db.members = java.util.Set.of("member-a", "member-b");
        db.run.put("state", "STOPPED");
        assertEquals("RUN_TERMINAL", admission(db, "check_point"));
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
        int firstWriteOnly = source.indexOf("if (!Boolean.TRUE.equals(r.get(\"mutation_possible\", Boolean.class))) {", owner);
        assertTrue(firstWriteOnly > owner && firstWriteOnly < recheck,
                "the recheck is guarded by 'no write of this run prepared yet' (P1-1)");
        // Every failover job the admission creates records the grant on its row.
        int admit = source.indexOf("String vendor, String runKind) {");
        assertTrue(source.indexOf("ManualAdmissionRecord.record(dsl, jobId, actor, windows)", admit) > admit);
    }
}
