package com.securityexpert.nexus.ui2.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.GateRequirement;
import com.securityexpert.nexus.ui2.platform.OpaqueId;

class JobTest {

    @Test
    void transitionKeepsIdentityAndCapability() {
        Capability capability = new Capability(OpaqueId.random(), "example", GateRequirement.NONE);
        Job job = new Job(OpaqueId.random(), capability, JobState.REQUESTED);

        Job admitted = job.transitionTo(JobState.ADMITTED);

        assertEquals(job.id(), admitted.id());
        assertEquals(job.capability(), admitted.capability());
        assertEquals(JobState.ADMITTED, admitted.state());
    }
}
