package com.securityexpert.nexus.ui2.worker.transport.ssh;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.jcraft.jsch.HostKeyRepository;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.persistence.discovery.SshHostKeyPolicyStore;
import com.securityexpert.nexus.ui2.persistence.runtime.EndpointAdmissionRepository.Owner;
import com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime;

class SshHostKeyWarnModeTest {
    private static byte[] key() {
        byte[] algorithm = "ssh-rsa".getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(4 + algorithm.length + 4 + 32).putInt(algorithm.length).put(algorithm).putInt(32).put(new byte[32]).array();
    }
    @Test void mismatchHookAllowsOnlyAfterDurableObservationAndNeverEnrolls() {
        var policy = mock(SshHostKeyPolicyStore.class);
        var resolver = mock(TrustRuleResolver.class);
        when(resolver.resolveExpectedFingerprint(anyString(), anyString(), anyInt(), anyString())).thenReturn(Optional.of("1".repeat(64)));
        when(policy.observe(anyString(), anyLong(), anyBoolean(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyString())).thenReturn(true);
        var transport = new SshExecTransport(ref -> null, resolver, false, policy);
        try (var scope = EndpointRuntime.open(new Owner("job-fixture", 1, null, 0, "inventory", "worker-fixture", 1, "INVENTORY", false))) {
            assertEquals(HostKeyRepository.OK, transport.trustedOnlyRepository("ref", new ConnectionTarget("endpoint-fixture", "192.0.2.10", 22), new String[1], new boolean[1]).check("192.0.2.10", key()));
            verify(policy).observe(eq("job-fixture"), eq(1L), eq(false), eq("endpoint-fixture"), eq("192.0.2.10"), eq(22), eq("ssh-rsa"), anyString(), eq("1".repeat(64)));
            verify(resolver, never()).recordTrustOnFirstUse(anyString(), anyInt(), anyString(), anyString());
        }
    }
    @Test void refusedOrUnavailablePolicyAndMissingContextFailClosed() {
        var policy = mock(SshHostKeyPolicyStore.class);
        var transport = new SshExecTransport(ref -> null, ref -> Optional.of("1".repeat(64)), false, policy);
        var target = new ConnectionTarget("endpoint-fixture", "192.0.2.10", 22);
        assertEquals(HostKeyRepository.NOT_INCLUDED, transport.trustedOnlyRepository("ref", target, new String[1], new boolean[1]).check("192.0.2.10", key()));
        try (var scope = EndpointRuntime.open(new Owner("job-fixture", 1, null, 0, "inventory", "worker-fixture", 1, "INVENTORY", false))) {
            assertEquals(HostKeyRepository.NOT_INCLUDED, transport.trustedOnlyRepository("ref", target, new String[1], new boolean[1]).check("192.0.2.10", key()));
            when(policy.observe(anyString(), anyLong(), anyBoolean(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException());
            assertEquals(HostKeyRepository.NOT_INCLUDED, transport.trustedOnlyRepository("ref", target, new String[1], new boolean[1]).check("192.0.2.10", key()));
        }
    }
    @Test void failoverContextIsAlwaysPassedToPolicyRegardlessOfOutputMasking() {
        var policy = mock(SshHostKeyPolicyStore.class);
        var transport = new SshExecTransport(ref -> null, ref -> Optional.of("1".repeat(64)), true, policy);
        var target = new ConnectionTarget("endpoint-fixture", "192.0.2.10", 22);
        try (var scope = EndpointRuntime.open(new Owner("job-fixture", 1, null, 0, "failover", "worker-fixture", 1, "FAILOVER_EXECUTION", false))) {
            assertEquals(HostKeyRepository.NOT_INCLUDED, transport.trustedOnlyRepository("ref", target, new String[1], new boolean[1]).check("192.0.2.10", key()));
            verify(policy).observe(eq("job-fixture"), eq(1L), eq(true), anyString(), anyString(), anyInt(), anyString(), anyString(), anyString());
        }
        when(policy.observe(anyString(), anyLong(), eq(false), anyString(), anyString(), anyInt(), anyString(), anyString(), anyString())).thenReturn(true);
        try (var scope = EndpointRuntime.open(new Owner("job-fixture", 1, null, 0, "inventory", "worker-fixture", 1, "INVENTORY", false))) {
            assertEquals(HostKeyRepository.OK, transport.trustedOnlyRepository("ref", target, new String[1], new boolean[1]).check("192.0.2.10", key()));
        }
    }

    @Test void missingPinNeverInvokesMismatchException() {
        var policy = mock(SshHostKeyPolicyStore.class);
        var transport = new SshExecTransport(ref -> null, ref -> Optional.empty(), false, policy);
        String[] failure = new String[1];
        assertEquals(HostKeyRepository.NOT_INCLUDED, transport.trustedOnlyRepository("ref", new ConnectionTarget("endpoint-fixture", "192.0.2.10", 22), failure, new boolean[1]).check("192.0.2.10", key()));
        assertEquals("TRUST_ENTRY_MISSING", failure[0]);
        verifyNoInteractions(policy);
    }
}
