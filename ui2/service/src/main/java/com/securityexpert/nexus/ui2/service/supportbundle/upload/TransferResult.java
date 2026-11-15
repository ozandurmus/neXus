package com.securityexpert.nexus.ui2.service.supportbundle.upload;

/** What the remote side answered; the byte count and digest are measured by the service, not trusted from here. */
public record TransferResult(String remoteStatus, String hostKeyFingerprint, boolean hostKeyChanged) {
}
