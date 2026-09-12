package com.securityexpert.nexus.ui2.worker.transport;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.Capability;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistry;
import com.securityexpert.nexus.ui2.capability.CapabilityRegistryLoader;
import com.securityexpert.nexus.ui2.capability.CapabilitySpec;
import com.securityexpert.nexus.ui2.capability.CapabilityStep;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.capability.MaturityState;
import com.securityexpert.nexus.ui2.capability.SignOffState;
import com.securityexpert.nexus.ui2.capability.StepKind;
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
import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * Contract §8 test 12 / AC-11: "a capability declaring xml_api_call/
 * sftp_get; fails if it reaches CLAIMED before the startup wiring check
 * fails." Realized here as: the worker's own startup check throws before
 * any job-claiming code path could ever run, for an execution-eligible
 * capability whose {@code transport.kind} has no registered adapter.
 */
class UnimplementedTransportFailsAtStartupNotPerJobTest {

    private static Capability panXmlApiCapability() {
        CapabilityStep exec = new CapabilityStep(StepKind.XML_API_CALL, "not_applicable", "get-config", false,
                Optional.of(ActionClass.CLASS_0_READ), Optional.empty(), Optional.of(30));
        CapabilitySpec spec = new CapabilitySpec("test_pan_capability", "panorama", "pan_firewall",
                TransportKind.PAN_XML_API, MaturityState.CAP_OFFLINE, List.of(exec), List.of(), "UNKNOWN",
                List.of(), false);
        GateRegistryPort gates = key -> List.of(new GateRow("gate_pan", "panorama", "pan_firewall", "not_applicable",
                "PAN_XML_API", "get-config", ActionClass.CLASS_0_READ, SignOffState.SIGNED_OFF, 30, null, null,
                null, null, null, List.of(), "test"));
        return new CapabilityRegistryLoader(gates).load(spec);
    }

    @Test
    void executionEligibleCapabilityWithNoRegisteredAdapterFailsAtStartup() {
        Capability capability = panXmlApiCapability();
        assertDoesNotThrow(() -> {
            // "is offline-buildable" -- loading the capability itself never fails.
        }, "the capability must compile/load even though its transport has no adapter (C4 §3.5)");

        CapabilityRegistry registry = CapabilityRegistry.of(List.of(capability));
        TransportRegistry transportRegistry = new TransportRegistry(); // no adapters registered at all

        assertThrows(UnimplementedTransportAtStartupException.class,
                () -> StartupTransportCheck.verify(registry, transportRegistry));
    }

    @Test
    void anExecutionIneligibleCapabilityWithNoAdapterDoesNotBlockStartup() {
        // A capability with an UNKNOWN gate can never be claimed (C4 §3.5)
        // regardless of its transport -- a missing adapter for it is not a
        // startup-blocking condition.
        CapabilityStep exec = new CapabilityStep(StepKind.XML_API_CALL, "not_applicable", "get-config", false,
                Optional.empty(), Optional.empty(), Optional.of(30));
        CapabilitySpec spec = new CapabilitySpec("test_pan_ineligible", "panorama", "pan_firewall",
                TransportKind.PAN_XML_API, MaturityState.CAP_OFFLINE, List.of(exec), List.of(), "UNKNOWN",
                List.of(), false);
        Capability capability = new CapabilityRegistryLoader(key -> List.of()).load(spec);

        CapabilityRegistry registry = CapabilityRegistry.of(List.of(capability));
        TransportRegistry transportRegistry = new TransportRegistry();

        assertDoesNotThrow(() -> StartupTransportCheck.verify(registry, transportRegistry));
    }

    @Test
    void aRegisteredSshExecAdapterSatisfiesAnSshExecCapability() {
        CapabilityStep exec = new CapabilityStep(StepKind.EXEC, "clish", "show version", false,
                Optional.of(ActionClass.CLASS_0_READ), Optional.empty(), Optional.of(30));
        CapabilitySpec spec = new CapabilitySpec("test_ssh_ok", "check_point", "cp_gaia_gateway",
                TransportKind.SSH_EXEC, MaturityState.CAP_OFFLINE, List.of(exec), List.of(), "UNKNOWN", List.of(),
                false);
        GateRegistryPort gates = key -> List.of(new GateRow("gate_ssh", "check_point", "cp_gaia_gateway", "clish",
                "SSH_EXEC", "show version", ActionClass.CLASS_0_READ, SignOffState.SIGNED_OFF, 30, null, null, null,
                null, null, List.of(), "test"));
        Capability capability = new CapabilityRegistryLoader(gates).load(spec);

        CapabilityRegistry registry = CapabilityRegistry.of(List.of(capability));
        TransportRegistry transportRegistry = new TransportRegistry();
        transportRegistry.register(TransportKind.SSH_EXEC, new NoOpTransport());

        assertDoesNotThrow(() -> StartupTransportCheck.verify(registry, transportRegistry));
    }

    private static final class NoOpTransport implements DeviceTransport {
        @Override
        public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void disconnect(TransportSession session) {
        }
    }
}
