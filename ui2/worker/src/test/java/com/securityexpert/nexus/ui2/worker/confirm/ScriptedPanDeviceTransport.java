package com.securityexpert.nexus.ui2.worker.confirm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * A scriptable {@link DeviceTransport} test double for the {@code
 * xml_api_call} path -- never a real socket. Responses are keyed by
 * {@link ApiTarget#baseUrl()}, so one instance can play both a first
 * device and the peer PF-1 contacts in the same test.
 */
final class ScriptedPanDeviceTransport implements DeviceTransport {

    private final Map<String, String> identityBodyByBaseUrl;
    private final Map<String, String> haPeerBodyByBaseUrl;
    private final Set<String> connectedBaseUrls = new LinkedHashSet<>();
    private final List<String> commandsIssued = new ArrayList<>();

    ScriptedPanDeviceTransport(Map<String, String> identityBodyByBaseUrl, Map<String, String> haPeerBodyByBaseUrl) {
        this.identityBodyByBaseUrl = identityBodyByBaseUrl;
        this.haPeerBodyByBaseUrl = haPeerBodyByBaseUrl;
    }

    Set<String> connectedBaseUrls() {
        return connectedBaseUrls;
    }

    List<String> commandsIssued() {
        return commandsIssued;
    }

    @Override
    public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
        throw new UnsupportedOperationException("palo alto confirm never calls connect()");
    }

    @Override
    public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
        throw new UnsupportedOperationException("palo alto confirm never calls exec()");
    }

    @Override
    public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) {
        throw new UnsupportedOperationException("not used by this movement");
    }

    @Override
    public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
        if ("keygen".equals(spec.type())) {
            connectedBaseUrls.add(target.baseUrl());
            return new XmlApiResult.Completed(200, "<response><result><key>FAKE-KEY</key></result></response>");
        }
        String command = spec.formParams().get("cmd");
        commandsIssued.add(command);
        if (DeviceFirstContactCommandSet.PAN_IDENTITY_READ.literalForms().contains(command)) {
            return new XmlApiResult.Completed(200, identityBodyByBaseUrl.getOrDefault(target.baseUrl(), ""));
        }
        if (DeviceFirstContactCommandSet.PAN_HA_PEER_READ.literalForms().contains(command)) {
            return new XmlApiResult.Completed(200, haPeerBodyByBaseUrl.getOrDefault(target.baseUrl(), ""));
        }
        return new XmlApiResult.Failed("command outside the closed set: " + command);
    }

    @Override
    public void disconnect(TransportSession session) {
        throw new UnsupportedOperationException("palo alto confirm never calls disconnect()");
    }
}
