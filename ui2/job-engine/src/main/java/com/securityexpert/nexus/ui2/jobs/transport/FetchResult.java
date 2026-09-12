package com.securityexpert.nexus.ui2.jobs.transport;

/** {@code sftp_get}/{@code scp_get} result shape -- declared, not implemented at this movement. */
public sealed interface FetchResult {

    record Fetched(String stagingArtifactId, long bytesTransferred) implements FetchResult {
    }

    record Failed(String reason) implements FetchResult {
    }
}
