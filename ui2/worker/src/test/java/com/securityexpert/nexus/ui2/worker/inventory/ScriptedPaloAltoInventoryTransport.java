package com.securityexpert.nexus.ui2.worker.inventory;

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

/** A scriptable Palo Alto {@link DeviceTransport} test double, keyed by the {@code cmd} form param (never a real socket). */
final class ScriptedPaloAltoInventoryTransport implements DeviceTransport {

    private static final String API_KEY = "test-api-key";

    private final Map<String, String> outputByCmd;

    ScriptedPaloAltoInventoryTransport(Map<String, String> outputByCmd) {
        this.outputByCmd = outputByCmd;
    }

    @Override
    public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
        throw new UnsupportedOperationException("palo_alto inventory never calls connect");
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
        String cmd = spec.formParams().get("cmd");
        String output = outputByCmd.get(cmd);
        if (output == null) {
            throw new IllegalStateException("cmd outside this test's scripted set: " + cmd);
        }
        return new XmlApiResult.Completed(200, output);
    }

    @Override
    public void disconnect(TransportSession session) {
        throw new UnsupportedOperationException("not used by this test");
    }
}
