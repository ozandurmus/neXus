package com.securityexpert.nexus.ui2.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.platform.OpaqueId;

class JobTest {

    @Test
    void transitionKeepsIdentityAndCapability() {
        Job job = Job.requested(OpaqueId.random(), "cp_gaia_inventory_show_version_ha_state", "device-1",
                ActionClass.CLASS_0_READ, "idem-1");

        Job claimed = job.transitionTo(JobState.CLAIMED);

        assertEquals(job.id(), claimed.id());
        assertEquals(job.capabilityId(), claimed.capabilityId());
        assertEquals(JobState.CLAIMED, claimed.state());
    }

    @Test
    void illegalTransitionThrows() {
        Job job = Job.requested(OpaqueId.random(), "cp_gaia_inventory_show_version_ha_state", "device-1",
                ActionClass.CLASS_0_READ, "idem-2");

        // REQUESTED -> EXECUTING is not in C2 §3.2's graph (a job must pass
        // through CLAIMED first) -- the in-memory value object refuses it
        // exactly as the persistence layer's WHERE state = <expected> guard
        // would (contract §4).
        assertThrows(IllegalStateException.class, () -> job.transitionTo(JobState.EXECUTING));
    }

    @Test
    void terminalStatesHaveNoOutgoingEdgeExceptOutcomeUnknownToReconciled() {
        for (JobState state : JobState.values()) {
            if (state.isTerminal() && state != JobState.OUTCOME_UNKNOWN) {
                for (JobState target : JobState.values()) {
                    assertEquals(false, state.canTransitionTo(target),
                            state + " must have no outgoing edge (C2 §3.2), but claims one to " + target);
                }
            }
        }
        assertEquals(true, JobState.OUTCOME_UNKNOWN.canTransitionTo(JobState.RECONCILED));
        assertEquals(false, JobState.RECONCILED.canTransitionTo(JobState.REQUESTED));
    }
}
