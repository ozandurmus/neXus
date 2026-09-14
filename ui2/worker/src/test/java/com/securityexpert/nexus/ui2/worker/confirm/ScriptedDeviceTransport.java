package com.securityexpert.nexus.ui2.worker.confirm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

/**
 * A scriptable {@link DeviceTransport} test double -- never a real socket
 * (T-4's decisive-test pattern, mirrored from {@code
 * worker.discovery.cp.FakeDeviceTransport}). Responses are keyed by the
 * connection target's host, so one instance can play both a first device
 * and the peer PF-1 contacts in the same test.
 */
final class ScriptedDeviceTransport implements DeviceTransport {

    private final Map<String, String> identityOutputByHost;
    private final Map<String, String> haPeerOutputByHost;
    private final Map<String, String> hostBySessionId = new HashMap<>();
    private final List<ConnectionTarget> connectedTargets = new ArrayList<>();
    private boolean credentialUnresolvable;

    ScriptedDeviceTransport(Map<String, String> identityOutputByHost, Map<String, String> haPeerOutputByHost) {
        this.identityOutputByHost = identityOutputByHost;
        this.haPeerOutputByHost = haPeerOutputByHost;
    }

    void makeCredentialUnresolvable() {
        this.credentialUnresolvable = true;
    }

    List<ConnectionTarget> connectedTargets() {
        return connectedTargets;
    }

    @Override
    public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
        if (credentialUnresolvable) {
            throw new IllegalStateException("ssh credential reference not resolvable: " + spec.credentialRef());
        }
        connectedTargets.add(target);
        String sessionId = UUID.randomUUID().toString();
        hostBySessionId.put(sessionId, target.host());
        return new ConnectResult.Authenticated(() -> sessionId);
    }

    @Override
    public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
        String host = hostBySessionId.get(session.sessionId());
        String command = spec.command();
        if (DeviceFirstContactCommandSet.CP_IDENTITY_READ.literalForms().contains(command)) {
            return new ExecResult.Completed(identityOutputByHost.getOrDefault(host, ""), 0);
        }
        if (DeviceFirstContactCommandSet.CP_HA_PEER_READ.literalForms().contains(command)) {
            return new ExecResult.Completed(haPeerOutputByHost.getOrDefault(host, ""), 0);
        }
        throw new IllegalStateException("command outside the closed set: " + command);
    }

    @Override
    public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) {
        throw new UnsupportedOperationException("not used by this movement");
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
