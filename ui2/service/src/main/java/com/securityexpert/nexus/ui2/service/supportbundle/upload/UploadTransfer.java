package com.securityexpert.nexus.ui2.service.supportbundle.upload;

/** One upload method. Implementations hold the password only for the call and never log or return it. */
public interface UploadTransfer {
    TransferResult send(UploadTarget target, char[] password, UploadBody body, HostKeyLog hostKeys) throws UploadException;
}
