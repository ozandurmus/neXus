package com.securityexpert.nexus.ui2.worker.inventory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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

/** A scriptable Check Point {@link DeviceTransport} test double, keyed by exact command text (never a real socket). */
final class ScriptedCheckPointInventoryTransport implements DeviceTransport {

    private final Map<String, String> outputByCommand;
    private final Map<String, String> hostBySessionId = new HashMap<>();

    ScriptedCheckPointInventoryTransport(Map<String, String> outputByCommand) {
        this.outputByCommand = outputByCommand;
    }

    @Override
    public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
        String sessionId = UUID.randomUUID().toString();
        hostBySessionId.put(sessionId, target.host());
        return new ConnectResult.Authenticated(() -> sessionId);
    }

    @Override
    public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
        String output = outputByCommand.get(spec.command());
        if (output == null) {
            throw new IllegalStateException("command outside this test's scripted set: " + spec.command());
        }
        return new ExecResult.Completed(output, 0);
    }

    @Override
    public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) {
        throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
        throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public void disconnect(TransportSession session) {
        hostBySessionId.remove(session.sessionId());
    }
}
