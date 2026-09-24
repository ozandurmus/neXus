package com.securityexpert.nexus.ui2.jobs.transport;

import java.io.OutputStream;
import java.time.Duration;

/**
 * The transport port (contract §5, §2 module-placement table: "port lives
 * in job-engine"). {@code job-engine} depends on this interface only; the
 * {@code worker} module's {@code ssh_exec} adapter depends inward on
 * {@code job-engine} to implement it, never the reverse (DIR-3) -- this is
 * exactly what keeps {@code job-engine} free of any transport
 * implementation dependency while the executor can still run any adapter.
 *
 * <p>Every method returns a result value, never throws for an expected
 * device-side outcome (non-match, refusal) -- an exception is reserved for
 * what the transport itself cannot classify (contract §5).</p>
 */
public interface DeviceTransport {

    ConnectResult connect(ConnectionTarget target, ConnectSpec spec, Duration timeout);

    ExecResult exec(TransportSession session, ExecSpec spec, Duration timeout);

    /**
     * A persistent, PTY-backed interactive shell command -- for a Check Point
     * Gaia Embedded/Quantum Spark device that accepts an interactive shell
     * login but rejects a bare {@code exec} channel request outright (measured
     * live, 2026-09-21: every literal form of {@link #exec} ran out its full
     * timeout on such a device, {@code pty} true or not; the pre-Java
     * product's own real-fleet-proven probe used an interactive shell for
     * exactly this reason). A default method, not an abstract one, so every
     * existing implementor and test double keeps compiling unchanged -- only
     * {@code SshExecTransport} overrides it with a real implementation, the
     * same shape {@link #fetchStreaming} already uses for a capability a
     * transport does not support.
     */
    default ExecResult execInteractive(TransportSession session, ExecSpec spec, Duration timeout) {
        throw new TransportNotImplementedException("exec_interactive");
    }

    /**
     * {@link #execInteractive} for a command that asks questions before its prompt returns (Radware Cyber Controller
     * {@code export}: "Password:"; {@code delete}: "(Y/N)?"). Default: not implemented, as for {@link #execInteractive}.
     */
    default ExecResult execInteractiveAnswering(TransportSession session, ExecSpec spec, java.util.List<PromptAnswer> answers,
            Duration timeout) {
        throw new TransportNotImplementedException("exec_interactive_answering");
    }

    /** {@code sftp_get}/{@code scp_get} -- declared, not implemented at this movement. */
    FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout);

    /**
     * The streaming form of {@link #fetch} (14H BK-3): writes the remote
     * file's bytes directly into {@code sink} without buffering the whole
     * file in memory -- the shape backup's own fetch into the artefact
     * store needs, since it already holds an {@code OutputStream} sink
     * rather than wanting a {@code stagingArtifactId} back. A default
     * method, not an abstract one, so every existing implementor and test
     * double keeps compiling unchanged -- only {@code SshExecTransport}
     * overrides it with a real implementation; every other transport falls
     * through to this same "not implemented" shape {@link #fetch} already
     * uses for a capability a transport does not support.
     */
    default FetchStreamResult fetchStreaming(TransportSession session, FetchSpec spec, Duration timeout,
            OutputStream sink) {
        throw new TransportNotImplementedException("sftp_get_streaming");
    }

    /** PAN XML API -- declared, not implemented at this movement. */
    XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout);

    /**
     * The streaming form of {@link #xmlApiCall} (14G CG-5): a default
     * method, not an abstract one, so every existing implementor and test
     * double keeps compiling unchanged -- only {@code PanXmlApiTransport}
     * overrides it with a real implementation; every other transport falls
     * through to this same "not implemented" shape {@link #fetch} already
     * uses for a capability a transport does not support.
     */
    default <T> XmlApiStreamOutcome<T> xmlApiCallStreaming(ApiTarget target, XmlApiSpec spec, Duration timeout,
            XmlApiStreamHandler<T> handler) {
        throw new TransportNotImplementedException("xml_api_call_streaming");
    }

    void disconnect(TransportSession session);
}
