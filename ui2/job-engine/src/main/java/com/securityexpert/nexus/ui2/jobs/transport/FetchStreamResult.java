package com.securityexpert.nexus.ui2.jobs.transport;

/**
 * {@link DeviceTransport#fetchStreaming}'s own result shape -- distinct from
 * {@link FetchResult}'s declared-but-unimplemented {@code stagingArtifactId}
 * shape (contract §5 leaves that undecided): a streaming caller already
 * holds the sink it wants the bytes written to (14H BK-3: straight into the
 * artefact store), so there is no staging artifact id to hand back, only
 * how many bytes were actually written to that sink.
 */
public sealed interface FetchStreamResult {

    record Fetched(long bytesTransferred) implements FetchStreamResult {
    }

    record Failed(String reason) implements FetchStreamResult {
    }
}
