package com.securityexpert.nexus.ui2.jobs.transport;

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

    /** {@code sftp_get}/{@code scp_get} -- declared, not implemented at this movement. */
    FetchResult fetch(TransportSession session, FetchSpec spec, Duration timeout);

    /** PAN XML API -- declared, not implemented at this movement. */
    XmlApiResult xmlApiCall(ApiTarget target, XmlApiSpec spec, Duration timeout);

    void disconnect(TransportSession session);
}
