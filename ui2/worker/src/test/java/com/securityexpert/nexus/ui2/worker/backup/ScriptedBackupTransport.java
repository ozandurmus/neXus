package com.securityexpert.nexus.ui2.worker.backup;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
import com.securityexpert.nexus.ui2.jobs.transport.FetchStreamResult;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;

/** A scripted {@link DeviceTransport} fake for {@link BackupCapabilityExecutor}/{@link BackupJobExecutor} tests -- no socket, no real device. */
final class ScriptedBackupTransport implements DeviceTransport {

    record FakeSession(String sessionId) implements TransportSession {
    }

    final Map<String, String> execOutputs = new HashMap<>();
    final Map<String, Integer> execExitStatus = new HashMap<>();
    final List<String> statusSequence = new ArrayList<>();
    final List<String> commandsIssued = new ArrayList<>();
    int statusCallCount;
    String fetchedContent = "archive-bytes";
    boolean fetchFails;
    boolean disconnectCalled;
    boolean connectFails;

    @Override
    public ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout) {
        if (connectFails) {
            return new ConnectResult.AuthenticationFailed("scripted failure");
        }
        return new ConnectResult.Authenticated(new FakeSession("scripted-session"));
    }

    @Override
    public ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout) {
        commandsIssued.add(spec.command());
        if (BackupReadPlan.CP_SHOW_BACKUP_STATUS.equals(spec.command())) {
            String output = statusSequence.isEmpty() ? ""
                    : statusSequence.get(Math.min(statusCallCount, statusSequence.size() - 1));
            statusCallCount++;
            return new ExecResult.Completed(output, 0);
        }
        String output = execOutputs.getOrDefault(spec.command(), "");
        int exitStatus = execExitStatus.getOrDefault(spec.command(), 0);
        return new ExecResult.Completed(output, exitStatus);
    }

    @Override
    public FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout) {
        throw new UnsupportedOperationException("not exercised by these tests");
    }

    @Override
    public FetchStreamResult fetchStreaming(TransportSession session, FetchSpec spec, Duration timeout,
            OutputStream sink) {
        if (fetchFails) {
            return new FetchStreamResult.Failed("scripted fetch failure");
        }
        try {
            byte[] bytes = fetchedContent.getBytes(StandardCharsets.UTF_8);
            sink.write(bytes);
            return new FetchStreamResult.Fetched(bytes.length);
        } catch (IOException e) {
            throw new UncheckedIOExceptionForTest(e);
        }
    }

    @Override
    public XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout) {
        throw new UnsupportedOperationException("not exercised by these tests");
    }

    @Override
    public void disconnect(TransportSession session) {
        disconnectCalled = true;
    }

    private static final class UncheckedIOExceptionForTest extends RuntimeException {
        UncheckedIOExceptionForTest(IOException cause) {
            super(cause);
        }
    }
}
