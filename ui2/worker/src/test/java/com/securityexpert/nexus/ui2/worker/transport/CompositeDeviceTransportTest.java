package com.securityexpert.nexus.ui2.worker.transport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.FetchResult;
import com.securityexpert.nexus.ui2.jobs.transport.FetchSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;

/** AC-4: one worker process routes {@code ssh_exec} to its own adapter and {@code pan_xml_api} to its own, never crossed. */
class CompositeDeviceTransportTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final class RecordingTransport implements DeviceTransport {
        boolean connectCalled;
        boolean xmlApiCallCalled;
        boolean execInteractiveCalled;

        @Override
        public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
            connectCalled = true;
            return new ConnectResult.TimedOut();
        }

        @Override
        public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public ExecResult execInteractive(TransportSession session, ExecSpec spec, Duration timeout) {
            execInteractiveCalled = true;
            return new ExecResult.Completed("interactive-output", 0);
        }

        @Override
        public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
            xmlApiCallCalled = true;
            return new XmlApiResult.Completed(200, "ok");
        }

        @Override
        public void disconnect(TransportSession session) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    @Test
    void connectRoutesToTheRegisteredSshExecAdapter() {
        RecordingTransport ssh = new RecordingTransport();
        TransportRegistry registry = new TransportRegistry();
        registry.register(TransportKind.SSH_EXEC, ssh);
        CompositeDeviceTransport composite = new CompositeDeviceTransport(registry);

        ConnectResult result = composite.connect(new ConnectionTarget("ep-1", "host", 22),
                new ConnectSpec("cred-1", "trust-1", java.util.Optional.empty()), TIMEOUT);

        assertTrue(ssh.connectCalled, "connect must reach the registered ssh_exec adapter");
        assertTrue(result instanceof ConnectResult.TimedOut);
    }

    /** Regression: this method was added to DeviceTransport as a default (throwing) method after
     * CompositeDeviceTransport already existed, and CompositeDeviceTransport was never updated to
     * override and route it -- every execInteractive call silently fell to the interface's own
     * "not implemented" default instead of reaching the registered ssh_exec adapter (measured
     * live, 2026-09-21: the confirm's interactive-shell fallback never actually ran on a real
     * device because of exactly this gap, though its own unit tests -- which construct
     * SshExecTransport directly, never through this composite -- could not catch it). */
    @Test
    void execInteractiveRoutesToTheRegisteredSshExecAdapter() {
        RecordingTransport ssh = new RecordingTransport();
        TransportRegistry registry = new TransportRegistry();
        registry.register(TransportKind.SSH_EXEC, ssh);
        CompositeDeviceTransport composite = new CompositeDeviceTransport(registry);

        ExecResult result = composite.execInteractive(() -> "session-1", new ExecSpec("show version all"), TIMEOUT);

        assertTrue(ssh.execInteractiveCalled, "execInteractive must reach the registered ssh_exec adapter");
        assertTrue(result instanceof ExecResult.Completed);
    }

    @Test
    void xmlApiCallRoutesToTheRegisteredPanXmlApiAdapter() {
        RecordingTransport pan = new RecordingTransport();
        TransportRegistry registry = new TransportRegistry();
        registry.register(TransportKind.PAN_XML_API, pan);
        CompositeDeviceTransport composite = new CompositeDeviceTransport(registry);

        XmlApiResult result = composite.xmlApiCall(new ApiTarget("ep-1", "https://pan.example"),
                new XmlApiSpec("GET", "op", "", "", Map.of(), Map.of()), TIMEOUT);

        assertTrue(pan.xmlApiCallCalled, "xmlApiCall must reach the registered pan_xml_api adapter");
        assertTrue(result instanceof XmlApiResult.Completed);
    }

    @Test
    void connectFailsClosedWhenNoSshExecAdapterIsRegistered_neverFallingBackToPan() {
        RecordingTransport pan = new RecordingTransport();
        TransportRegistry registry = new TransportRegistry();
        registry.register(TransportKind.PAN_XML_API, pan);
        CompositeDeviceTransport composite = new CompositeDeviceTransport(registry);

        ConnectResult result = composite.connect(new ConnectionTarget("ep-1", "host", 22),
                new ConnectSpec("cred-1", "trust-1", java.util.Optional.empty()), TIMEOUT);

        assertTrue(result instanceof ConnectResult.AuthenticationFailed, "an unregistered kind is a definite failure");
        assertFalse(pan.connectCalled, "never falls back to the other kind's adapter");
    }

    @Test
    void xmlApiCallFailsClosedWhenNoPanAdapterIsRegistered_neverFallingBackToSsh() {
        RecordingTransport ssh = new RecordingTransport();
        TransportRegistry registry = new TransportRegistry();
        registry.register(TransportKind.SSH_EXEC, ssh);
        CompositeDeviceTransport composite = new CompositeDeviceTransport(registry);

        XmlApiResult result = composite.xmlApiCall(new ApiTarget("ep-1", "https://pan.example"),
                new XmlApiSpec("GET", "op", "", "", Map.of(), Map.of()), TIMEOUT);

        assertTrue(result instanceof XmlApiResult.Failed, "an unregistered kind is a definite failure");
        assertFalse(ssh.xmlApiCallCalled, "never falls back to the other kind's adapter");
    }

    @Test
    void bothAdaptersRegisteredEachOperationStillReachesOnlyItsOwnKind() {
        RecordingTransport ssh = new RecordingTransport();
        RecordingTransport pan = new RecordingTransport();
        TransportRegistry registry = new TransportRegistry();
        registry.register(TransportKind.SSH_EXEC, ssh);
        registry.register(TransportKind.PAN_XML_API, pan);
        CompositeDeviceTransport composite = new CompositeDeviceTransport(registry);

        composite.connect(new ConnectionTarget("ep-1", "host", 22),
                new ConnectSpec("cred-1", "trust-1", java.util.Optional.empty()), TIMEOUT);

        assertTrue(ssh.connectCalled);
        assertFalse(pan.connectCalled);
        assertFalse(pan.xmlApiCallCalled, "connect() never touches the pan adapter at all");
    }
}
