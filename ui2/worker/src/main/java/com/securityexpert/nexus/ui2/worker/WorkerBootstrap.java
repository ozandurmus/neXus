package com.securityexpert.nexus.ui2.worker;

import com.securityexpert.nexus.ui2.capability.CapabilityRegistry;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.worker.transport.StartupTransportCheck;
import com.securityexpert.nexus.ui2.worker.transport.TransportRegistry;
import com.securityexpert.nexus.ui2.worker.transport.ssh.SshExecTransport;

/**
 * Worker composition root (contract §2: "worker wires the concrete adapter
 * per transport.kind; executor logic is transport-agnostic"). {@link
 * #boot} is where {@link StartupTransportCheck} runs -- before this method
 * returns, every execution-eligible capability's transport is proven to
 * have a registered adapter (AC-11); a running worker never discovers this
 * gap lazily on first claim.
 */
public final class WorkerBootstrap {

    private WorkerBootstrap() {
    }

    public static TransportRegistry buildTransportRegistry(SshExecTransport sshExecTransport) {
        TransportRegistry registry = new TransportRegistry();
        registry.register(TransportKind.SSH_EXEC, sshExecTransport);
        // TransportKind.SSH_INTERACTIVE and TransportKind.PAN_XML_API are
        // deliberately never registered here (contract §5: "only the
        // transport the first capability needs is implemented").
        return registry;
    }

    /** @throws com.securityexpert.nexus.ui2.worker.transport.UnimplementedTransportAtStartupException per AC-11. */
    public static void boot(CapabilityRegistry capabilityRegistry, TransportRegistry transportRegistry) {
        StartupTransportCheck.verify(capabilityRegistry, transportRegistry);
    }
}
