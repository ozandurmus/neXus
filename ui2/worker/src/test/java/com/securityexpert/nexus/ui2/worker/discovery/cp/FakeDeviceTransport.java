package com.securityexpert.nexus.ui2.worker.discovery.cp;

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
 * never a real device or management server (T-4's decisive test needs
 * exactly this: something that records every {@link ConnectionTarget} it
 * was ever asked to dial).
 */
final class FakeDeviceTransport implements DeviceTransport {

    private final List<ConnectionTarget> connectTargets = new ArrayList<>();
    private final List<String> commandsIssued = new ArrayList<>();
    private final List<TransportSession> disconnectCalls = new ArrayList<>();
    private final Function<String, ExecResult> commandHandler;
    private boolean disconnectThrows;
    private int connectCount;

    FakeDeviceTransport(Function<String, ExecResult> commandHandler) {
        this.commandHandler = commandHandler;
    }

    void disconnectThrows(boolean value) {
        this.disconnectThrows = value;
    }

    @Override
    public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
        connectTargets.add(target);
        connectCount++;
        return new ConnectResult.Authenticated(new FakeSession());
    }

    @Override
    public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
        commandsIssued.add(spec.command());
        return commandHandler.apply(spec.command());
    }

    @Override
    public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) {
        throw new UnsupportedOperationException("not used by this movement");
    }

    @Override
    public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
        throw new UnsupportedOperationException("not used by this movement");
    }

    @Override
    public void disconnect(TransportSession session) {
        disconnectCalls.add(session);
        if (disconnectThrows) {
            throw new IllegalStateException("fixture-forced disconnect failure");
        }
    }

    List<ConnectionTarget> connectTargets() {
        return connectTargets;
    }

    List<String> commandsIssued() {
        return commandsIssued;
    }

    int connectCount() {
        return connectCount;
    }

    int disconnectCount() {
        return disconnectCalls.size();
    }

    private static final class FakeSession implements TransportSession {
        @Override
        public String sessionId() {
            return "fake-session";
        }
    }
}
