package com.securityexpert.nexus.ui2.worker.transport.ssh;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.jcraft.jsch.Session;
import com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime;
import org.junit.jupiter.api.Test;

class SshDisconnectTest {
    @Test void shellCleanupFailureStillDisconnectsBeforeReleasingAdmission() {
        var jsch = mock(Session.class);
        var ssh = spy(new SshTransportSession("synthetic-session", jsch));
        ssh.admission = mock(EndpointRuntime.Lease.class);
        doThrow(new IllegalStateException("synthetic-cleanup-failure")).when(ssh).closeInteractiveShell();
        var transport = new SshExecTransport(ref -> { throw new AssertionError("no credentials"); },
            ref -> java.util.Optional.empty());
        assertThrows(IllegalStateException.class, () -> transport.disconnect(ssh));
        var order = inOrder(jsch, ssh.admission);
        order.verify(jsch).disconnect();
        order.verify(jsch).isConnected();
        order.verify(ssh.admission).closed();
    }

    @Test void failedForceDisconnectRetainsAdmission() {
        var jsch = mock(Session.class);
        var ssh = new SshTransportSession("synthetic-session", jsch);
        ssh.admission = mock(EndpointRuntime.Lease.class);
        doThrow(new IllegalStateException("synthetic-disconnect-failure")).when(jsch).disconnect();
        var transport = new SshExecTransport(ref -> { throw new AssertionError("no credentials"); },
            ref -> java.util.Optional.empty());
        assertThrows(IllegalStateException.class, () -> transport.disconnect(ssh));
        verify(ssh.admission, never()).closed();
    }

    @Test void stillConnectedSessionRetainsAdmission() {
        var jsch = mock(Session.class);
        when(jsch.isConnected()).thenReturn(true);
        var ssh = new SshTransportSession("synthetic-session", jsch);
        ssh.admission = mock(EndpointRuntime.Lease.class);
        var transport = new SshExecTransport(ref -> { throw new AssertionError("no credentials"); },
            ref -> java.util.Optional.empty());
        assertThrows(IllegalStateException.class, () -> transport.disconnect(ssh));
        verify(jsch).disconnect();
        verify(ssh.admission, never()).closed();
    }
}
