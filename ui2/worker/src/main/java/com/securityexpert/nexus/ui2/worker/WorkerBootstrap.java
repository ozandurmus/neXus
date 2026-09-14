package com.securityexpert.nexus.ui2.worker;

import com.securityexpert.nexus.ui2.capability.CapabilityRegistry;
import com.securityexpert.nexus.ui2.capability.TransportKind;
import com.securityexpert.nexus.ui2.worker.transport.StartupTransportCheck;
import com.securityexpert.nexus.ui2.worker.transport.TransportRegistry;
import com.securityexpert.nexus.ui2.worker.transport.ssh.SshExecTransport;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanXmlApiTransport;

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

    /**
     * WORKER.md "Both vendors in one worker": both adapters are registered
     * so a single {@link com.securityexpert.nexus.ui2.worker.transport.CompositeDeviceTransport}
     * over this registry serves Check Point ({@code ssh_exec}) and Palo
     * Alto ({@code pan_xml_api}) from the one worker process.
     * {@link TransportKind#SSH_INTERACTIVE} is deliberately never
     * registered here (contract §5: no capability declares it yet).
     */
    public static TransportRegistry buildTransportRegistry(SshExecTransport sshExecTransport,
            PanXmlApiTransport panXmlApiTransport) {
        TransportRegistry registry = new TransportRegistry();
        registry.register(TransportKind.SSH_EXEC, sshExecTransport);
        registry.register(TransportKind.PAN_XML_API, panXmlApiTransport);
        return registry;
    }

    /** @throws com.securityexpert.nexus.ui2.worker.transport.UnimplementedTransportAtStartupException per AC-11. */
    public static void boot(CapabilityRegistry capabilityRegistry, TransportRegistry transportRegistry) {
        StartupTransportCheck.verify(capabilityRegistry, transportRegistry);
    }
}
