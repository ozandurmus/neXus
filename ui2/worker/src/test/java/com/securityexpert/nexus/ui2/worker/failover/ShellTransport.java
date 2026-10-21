package com.securityexpert.nexus.ui2.worker.failover;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.securityexpert.nexus.ui2.jobs.transport.*;

/** Executor-facing transport: counts exec calls and shell opens; the shell answers from the Script's replies. */
final class ShellTransport implements DeviceTransport {
    final DeviceTransport base;
    final AtomicLong clock;
    final List<String> execLiterals = new ArrayList<>();
    final Map<String, List<ScriptedShell>> shells = new LinkedHashMap<>();
    String hangOn, desyncOn;
    String hangMember = "192.0.2.11";

    ShellTransport(DeviceTransport base, AtomicLong clock) {
        this.base = base;
        this.clock = clock;
    }

    @Override public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
        return base.connect(target, spec, timeout);
    }

    @Override public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
        execLiterals.add(spec.command());
        return base.exec(session, spec, timeout);
    }

    @Override public ShellChannel openShell(TransportSession session, Duration timeout) {
        boolean[] desynced = {false};
        var shell = new ScriptedShell(clock, (inner, begin, end) -> {
            if (session.sessionId().equals(hangMember) && inner.endsWith(String.valueOf(hangOn))) return begin + "\r\n";
            if (session.sessionId().equals(hangMember) && inner.endsWith(String.valueOf(desyncOn)) && !desynced[0]) {
                desynced[0] = true;
                return end + "0__\r\n";
            }
            // The Script answers by the old literal; the shell itself never sees a bash -lc wrapper.
            var reply = base.exec(session, new ExecSpec("bash -lc '" + inner + "'", false), timeout);
            return reply instanceof ExecResult.Completed c ? ScriptedShell.framed(begin, end, c.output(), c.exitStatus()) : null;
        });
        shells.computeIfAbsent(session.sessionId(), k -> new ArrayList<>()).add(shell);
        return shell;
    }

    @Override public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) {
        throw new AssertionError("fetch");
    }

    @Override public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
        throw new AssertionError("xmlApiCall");
    }

    @Override public void disconnect(TransportSession session) {
        base.disconnect(session);
    }

    int sent() {
        return shells.values().stream().flatMap(List::stream).mapToInt(s -> s.inner.size()).sum();
    }
}
