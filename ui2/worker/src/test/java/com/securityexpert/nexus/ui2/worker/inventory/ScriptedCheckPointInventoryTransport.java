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
    private boolean execChannelRejectedEntirely;
    private boolean execChannelTimesOutEntirely;
    private boolean allowExecInteractiveAnyway;
    private int execCallCount;

    ScriptedCheckPointInventoryTransport(Map<String, String> outputByCommand) {
        this.outputByCommand = outputByCommand;
    }

    /** Lets a test observe the interactive-shell-first model-hint routing without also
     * simulating an exec-rejecting/timing-out device -- {@link #exec} still succeeds if called,
     * so a count of zero on it is real proof the hint skipped it. */
    void allowExecInteractiveWithoutRejection() {
        this.allowExecInteractiveAnyway = true;
    }

    int execCallCount() {
        return execCallCount;
    }

    /** Simulates a Gaia Embedded/Quantum Spark device that rejects the exec channel outright for
     * every command (measured live, 2026-09-21, for the confirm's identity read; Product Owner
     * 2026-09-22: the same rejection also starves inventory collection of every interface/route
     * read) -- {@link #exec} always fails and only {@link #execInteractive} ever answers. */
    void rejectExecChannelEntirely() {
        this.execChannelRejectedEntirely = true;
    }

    /** Simulates a second exec-channel failure shape measured live, 2026-09-22: some Spark/Gaia
     * Embedded appliances never return a clean {@code ChannelFailed} at all -- every exec attempt
     * instead runs out its full read timeout ({@code ExecResult.TimedOut}). */
    void timeOutExecChannelEntirely() {
        this.execChannelTimesOutEntirely = true;
        this.execChannelRejectedEntirely = true;
    }

    @Override
    public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
        String sessionId = UUID.randomUUID().toString();
        hostBySessionId.put(sessionId, target.host());
        return new ConnectResult.Authenticated(() -> sessionId);
    }

    @Override
    public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
        execCallCount++;
        if (execChannelTimesOutEntirely) {
            return new ExecResult.TimedOut();
        }
        if (execChannelRejectedEntirely) {
            return new ExecResult.ChannelFailed("simulated: exec channel request rejected outright");
        }
        String output = outputByCommand.get(spec.command());
        if (output == null) {
            throw new IllegalStateException("command outside this test's scripted set: " + spec.command());
        }
        return new ExecResult.Completed(output, 0);
    }

    @Override
    public ExecResult execInteractive(TransportSession session, ExecSpec spec, Duration timeout) {
        if (!execChannelRejectedEntirely && !allowExecInteractiveAnyway) {
            throw new IllegalStateException("execInteractive called without simulating an exec-rejecting device");
        }
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
