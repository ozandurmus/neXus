package com.securityexpert.nexus.ui2.worker.configuration;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;

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
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiStreamHandler;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiStreamOutcome;

/**
 * A scriptable Palo Alto {@link DeviceTransport} test double for {@code
 * configuration_collect}, keyed by {@link XmlApiSpec#type()} and the
 * {@code cmd} form param (never a real socket) -- mirrors {@code
 * worker.inventory.ScriptedPaloAltoInventoryTransport}, extended with
 * {@link #xmlApiCallStreaming} for the {@code effective-running} read
 * (14G CG-5).
 */
final class ScriptedPaloAltoConfigTransport implements DeviceTransport {

    private static final String API_KEY = "test-api-key";

    /** {@code cmd} (op-type) -> scripted response body; system info and merged. */
    private final Map<String, String> outputByOpCmd;
    private final String activeFormOutput;
    private final java.util.function.Supplier<InputStream> effectiveRunningStreamSupplier;

    ScriptedPaloAltoConfigTransport(Map<String, String> outputByOpCmd, String activeFormOutput,
            java.util.function.Supplier<InputStream> effectiveRunningStreamSupplier) {
        this.outputByOpCmd = outputByOpCmd;
        this.activeFormOutput = activeFormOutput;
        this.effectiveRunningStreamSupplier = effectiveRunningStreamSupplier;
    }

    @Override
    public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
        throw new UnsupportedOperationException("palo_alto configuration collect never calls connect");
    }

    @Override
    public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
        throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) {
        throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
        if ("keygen".equals(spec.type())) {
            return new XmlApiResult.Completed(200, "<response><result><key>" + API_KEY + "</key></result></response>");
        }
        if ("config".equals(spec.type())) {
            return new XmlApiResult.Completed(200, activeFormOutput);
        }
        String cmd = spec.formParams().get("cmd");
        String output = outputByOpCmd.get(cmd);
        if (output == null) {
            throw new IllegalStateException("cmd outside this test's scripted set: " + cmd);
        }
        return new XmlApiResult.Completed(200, output);
    }

    @Override
    public <T> XmlApiStreamOutcome<T> xmlApiCallStreaming(ApiTarget target, XmlApiSpec spec, Duration timeout,
            XmlApiStreamHandler<T> handler) {
        String cmd = spec.formParams().get("cmd");
        if (!ConfigurationReadPlan.PAN_EFFECTIVE_RUNNING.equals(cmd)) {
            throw new IllegalStateException("streaming cmd outside this test's scripted set: " + cmd);
        }
        try {
            T handled = handler.handle(effectiveRunningStreamSupplier.get());
            return new XmlApiStreamOutcome.Completed<>(200, handled);
        } catch (IOException e) {
            return new XmlApiStreamOutcome.Failed<>("scripted stream failed: " + e.getMessage());
        }
    }

    @Override
    public void disconnect(TransportSession session) {
        throw new UnsupportedOperationException("not used by this test");
    }
}
