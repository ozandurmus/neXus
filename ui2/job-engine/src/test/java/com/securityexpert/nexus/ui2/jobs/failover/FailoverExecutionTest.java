package com.securityexpert.nexus.ui2.jobs.failover;

import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;

import com.securityexpert.nexus.ui2.jobs.failover.execution.*;
import com.securityexpert.nexus.ui2.jobs.failover.pilot.FailoverPilotAllowlist;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FailoverExecutionTest {

    @Test void defaultTransportsNeverClaimSuccessForEitherActionOrObservation() {
        for (FailoverDeviceExecutor executor:java.util.List.of(new CheckPointClusterXLExecutor(),new PaloAltoHaExecutor())) {
            for (var action:FailoverActionKind.values()) {
                var result=executor.executeAction("synthetic-member",action);
                assertFalse(result.successful());
                assertEquals(FailoverCommandResult.DeliveryCertainty.DEFINITELY_NOT_SUBMITTED,result.certainty());
            }
            var observed=executor.observePostcondition("synthetic-unit","synthetic-member-a","synthetic-member-b");
            assertFalse(observed.memberAObservation().observationSuccessful());
            assertFalse(observed.memberBObservation().observationSuccessful());
            assertEquals("UNKNOWN",observed.memberAObservation().observedRole());
            assertEquals("UNKNOWN",observed.memberBObservation().observedRole());
        }
    }

    @Test void switchDeniesMissingAndMalformedConfiguration() {
        for (String value:new String[]{null,"","false","1","yes"," true ","invalid"}) {
            assertFalse(FailoverMutationSwitch.fromValue(value).enabled());
        }
        assertTrue(FailoverMutationSwitch.fromValue("true").enabled());
        assertTrue(FailoverMutationSwitch.fromValue("TRUE").enabled());
        assertThrows(SecurityException.class,FailoverMutationSwitch::refuseGenericExecution);
    }

    @Test
    @DisplayName("CheckPointClusterXLExecutor executes exact OP.2.1 commands for failover and reversal")
    void checkPointCommandsTest() {
        AtomicReference<String> executedCmd = new AtomicReference<>();
        CheckPointClusterXLExecutor executor = new CheckPointClusterXLExecutor(
            (memberId, cmd) -> {
                executedCmd.set(cmd);
                return FailoverCommandResult.success(cmd + " OK");
            },
            (clusterId, memberId) -> MemberObservation.of(memberId, "STANDBY", true, true, "OK")
        );

        // 1. Failover
        FailoverCommandResult r1 = executor.executeAction("dev-cp-1", FailoverActionKind.CONTROLLED_FAILOVER);
        assertTrue(r1.successful());
        assertEquals("clusterXL_admin down", executedCmd.get());

        // 2. Reversal
        FailoverCommandResult r2 = executor.executeAction("dev-cp-1", FailoverActionKind.RETURN_TO_SERVICE);
        assertTrue(r2.successful());
        assertEquals("clusterXL_admin up", executedCmd.get());

        // 3. Two-sided observation
        TwoSidedObservation obs = executor.observePostcondition("cls-cp-01", "dev-cp-1", "dev-cp-2");
        assertTrue(obs.bothObservationsSuccessful());
        assertEquals("dev-cp-1", obs.memberAObservation().memberId());
        assertEquals("dev-cp-2", obs.memberBObservation().memberId());
    }

    @Test
    @DisplayName("PaloAltoHaExecutor executes exact commands for failover and reversal")
    void paloAltoCommandsTest() {
        AtomicReference<String> executedCmd = new AtomicReference<>();
        PaloAltoHaExecutor executor = new PaloAltoHaExecutor(
            (memberId, cmd) -> {
                executedCmd.set(cmd);
                return FailoverCommandResult.success(cmd + " OK");
            },
            (clusterId, memberId) -> MemberObservation.of(memberId, "passive", true, true, "OK")
        );

        // 1. Failover
        FailoverCommandResult r1 = executor.executeAction("dev-pa-1", FailoverActionKind.CONTROLLED_FAILOVER);
        assertTrue(r1.successful());
        assertEquals("request high-availability state suspend", executedCmd.get());

        // 2. Reversal
        FailoverCommandResult r2 = executor.executeAction("dev-pa-1", FailoverActionKind.RETURN_TO_SERVICE);
        assertTrue(r2.successful());
        assertEquals("request high-availability state functional", executedCmd.get());

        // 3. Observation
        TwoSidedObservation obs = executor.observePostcondition("cls-pa-01", "dev-pa-1", "dev-pa-2");
        assertTrue(obs.bothObservationsSuccessful());
        assertEquals("dev-pa-1", obs.memberAObservation().memberId());
        assertEquals("dev-pa-2", obs.memberBObservation().memberId());
    }

    @Test
    @DisplayName("FailoverPilotAllowlist strictly restricts execution to enrolled lab clusters and members")
    void pilotAllowlistTest() {
        FailoverPilotAllowlist allowlist = new FailoverPilotAllowlist();

        // Enrolled lab cluster and member
        assertTrue(allowlist.isClusterAllowed("cls-uuid-cp"));
        assertTrue(allowlist.isExecutionAllowed("cls-uuid-cp", "dev-cp-1"));

        // Enrolled cluster, unenrolled member
        assertFalse(allowlist.isExecutionAllowed("cls-uuid-cp", "unauthorized-member"));

        // Unenrolled cluster
        assertFalse(allowlist.isClusterAllowed("prod-perimeter-cluster-01"));
        assertFalse(allowlist.isExecutionAllowed("prod-perimeter-cluster-01", "prod-fw-1"));

        // Production environment cluster must be rejected even if active
        allowlist.enrollCluster(new FailoverPilotAllowlist.PilotEnrollment(
            "prod-cls-01",
            "PRODUCTION",
            Set.of("prod-m1", "prod-m2"),
            "ADMIN",
            true
        ));
        assertFalse(allowlist.isClusterAllowed("prod-cls-01"));
        assertFalse(allowlist.isExecutionAllowed("prod-cls-01", "prod-m1"));
    }
}
