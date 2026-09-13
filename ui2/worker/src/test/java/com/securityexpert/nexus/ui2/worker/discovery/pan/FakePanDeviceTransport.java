package com.securityexpert.nexus.ui2.worker.discovery.pan;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

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
 * A scriptable {@link DeviceTransport} test double -- never a real socket,
 * never a real Panorama (T-4's decisive test needs exactly this: something
 * that records every {@link ApiTarget} it was ever asked to call).
 */
final class FakePanDeviceTransport implements DeviceTransport {

    private final List<ApiTarget> targets = new ArrayList<>();
    private final List<XmlApiSpec> specsIssued = new ArrayList<>();
    private final Function<XmlApiSpec, XmlApiResult> handler;

    FakePanDeviceTransport(Function<XmlApiSpec, XmlApiResult> handler) {
        this.handler = handler;
    }

    @Override
    public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
        throw new UnsupportedOperationException("not used by this movement");
    }

    @Override
    public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
        throw new UnsupportedOperationException("not used by this movement");
    }

    @Override
    public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) {
        throw new UnsupportedOperationException("not used by this movement");
    }

    @Override
    public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
        targets.add(target);
        specsIssued.add(spec);
        return handler.apply(spec);
    }

    @Override
    public void disconnect(TransportSession session) {
        throw new UnsupportedOperationException("not used by this movement");
    }

    List<ApiTarget> targets() {
        return targets;
    }

    List<XmlApiSpec> specsIssued() {
        return specsIssued;
    }

    int requestCount() {
        return specsIssued.size();
    }
}
