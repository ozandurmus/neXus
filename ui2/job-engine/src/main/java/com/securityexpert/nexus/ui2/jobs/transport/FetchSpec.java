package com.securityexpert.nexus.ui2.jobs.transport;

/** {@code sftp_get}/{@code scp_get} request shape -- declared, not implemented at this movement. */
public record FetchSpec(String remotePath, long maxBytes) {
}
