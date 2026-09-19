package com.securityexpert.nexus.ui2.worker.transport;

import java.time.Duration;

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

/**
 * Routes {@link DeviceTransport} operations to the one {@link
 * #registry}-registered adapter for that operation's own fixed transport
 * kind (WORKER.md "Both vendors in one worker"): {@code connect}/{@code
 * exec}/{@code fetch}/{@code disconnect} are {@code ssh_exec}-shaped
 * (Check Point); {@code xmlApiCall} is {@code pan_xml_api}-shaped (Palo
 * Alto) -- {@link com.securityexpert.nexus.ui2.worker.confirm.ConfirmCapabilityExecutor}
 * already calls exactly one family per vendor (EC-11/EC-12), so this class
 * never has to infer intent from anything beyond which method was called.
 *
 * <p>A kind with no adapter registered in {@link #registry} fails closed
 * with a definite failure result of that operation's own result type --
 * never a silent fallback to the other adapter's kind. This is what makes
 * {@code Ui2WorkerMain} wiring both adapters (rather than this class
 * guessing) the only way either vendor's confirm ever reaches a real
 * device.</p>
 */
public final class CompositeDeviceTransport implements DeviceTransport {

    private final TransportRegistry registry;

    public CompositeDeviceTransport(TransportRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
        return registry.find(TransportKind.SSH_EXEC)
                .map(transport -> transport.connect(target, spec, timeout))
                .orElseGet(() -> new ConnectResult.AuthenticationFailed(noAdapter(TransportKind.SSH_EXEC)));
    }

    @Override
    public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
        return registry.find(TransportKind.SSH_EXEC)
                .map(transport -> transport.exec(session, spec, timeout))
                .orElseGet(() -> new ExecResult.ChannelFailed(noAdapter(TransportKind.SSH_EXEC)));
    }

    @Override
    public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) {
        return registry.find(TransportKind.SSH_EXEC)
                .map(transport -> transport.fetch(session, spec, timeout))
                .orElseGet(() -> new FetchResult.Failed(noAdapter(TransportKind.SSH_EXEC)));
    }

    @Override
    public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
        return registry.find(TransportKind.PAN_XML_API)
                .map(transport -> transport.xmlApiCall(target, spec, timeout))
                .orElseGet(() -> new XmlApiResult.Failed(noAdapter(TransportKind.PAN_XML_API)));
    }

    @Override
    public <T> com.securityexpert.nexus.ui2.jobs.transport.XmlApiStreamOutcome<T> xmlApiCallStreaming(
            ApiTarget target, XmlApiSpec spec, Duration timeout,
            com.securityexpert.nexus.ui2.jobs.transport.XmlApiStreamHandler<T> handler) {
        return registry.find(TransportKind.PAN_XML_API)
                .map(transport -> transport.xmlApiCallStreaming(target, spec, timeout, handler))
                .orElseGet(() -> new com.securityexpert.nexus.ui2.jobs.transport.XmlApiStreamOutcome.Failed<>(
                        noAdapter(TransportKind.PAN_XML_API)));
    }

    @Override
    public void disconnect(TransportSession session) {
        registry.find(TransportKind.SSH_EXEC).ifPresent(transport -> transport.disconnect(session));
    }

    private static String noAdapter(TransportKind kind) {
        return "no " + kind.name() + " transport adapter registered -- refusing rather than falling back "
                + "to another transport kind";
    }
}
