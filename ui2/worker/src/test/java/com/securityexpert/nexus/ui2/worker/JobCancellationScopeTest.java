package com.securityexpert.nexus.ui2.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.jobs.transport.*;
import com.securityexpert.nexus.ui2.worker.transport.ssh.*;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.*;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient;

class JobCancellationScopeTest {
    @Test void cancellationStopsAllTransportsBeforeCredentialsOrNetworkAndScopeDoesNotLeak() {
        var credentials = mock(SshCredentialResolver.class);
        var trust = mock(TrustRuleResolver.class);
        var ssh = new SshExecTransport(credentials, trust);
        var pan = new PanXmlApiTransport("synthetic-trust", mock(PanTrustRuleResolver.class));
        var https = new HttpsDeviceClient();
        try (var scope = new JobCancellationScope(() -> true)) {
            assertThrows(JobCancellationScope.Cancelled.class, () -> ssh.connect(
                new ConnectionTarget("synthetic-target", "192.0.2.8", 22),
                new ConnectSpec("synthetic-ref", "synthetic-trust", Optional.empty()), Duration.ofSeconds(1)));
            assertThrows(JobCancellationScope.Cancelled.class, () -> ssh.exec(() -> "synthetic-session", new ExecSpec("synthetic-step"), Duration.ofSeconds(1)));
            assertThrows(JobCancellationScope.Cancelled.class, () -> pan.xmlApiCall(null, null, Duration.ofSeconds(1)));
            assertThrows(JobCancellationScope.Cancelled.class, () -> https.get(new HttpsDeviceClient.Target("192.0.2.8", 443),
                "/synthetic", new HttpsDeviceClient.Credentials("synthetic-principal", new char[0]), Duration.ofSeconds(1), 100));
        }
        verifyNoInteractions(credentials, trust);
        assertDoesNotThrow(JobCancellationScope::check);
    }
    @Test void nestedScopeRestoresThePreviousJobAndChecksCurrentFlag() {
        var requested = new AtomicBoolean();
        try (var outer = new JobCancellationScope(requested::get)) {
            assertDoesNotThrow(JobCancellationScope::check);
            try (var inner = new JobCancellationScope(() -> true)) {
                assertThrows(JobCancellationScope.Cancelled.class, JobCancellationScope::check);
            }
            assertDoesNotThrow(JobCancellationScope::check);
            requested.set(true);
            assertThrows(JobCancellationScope.Cancelled.class, JobCancellationScope::check);
        }
        assertDoesNotThrow(JobCancellationScope::check);
    }
}
