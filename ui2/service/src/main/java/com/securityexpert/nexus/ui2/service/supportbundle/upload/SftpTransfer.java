package com.securityexpert.nexus.ui2.service.supportbundle.upload;

import java.io.IOException;
import java.io.OutputStream;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpException;

/** SFTP put into the account's start directory; the remote size is compared afterwards when the server lets the account look. */
public final class SftpTransfer implements UploadTransfer {

    @Override
    public TransferResult send(UploadTarget target, char[] password, UploadBody body, HostKeyLog hostKeys) throws UploadException {
        SshSupport.Opened opened = SshSupport.open(target, password, hostKeys);
        ChannelSftp channel = null;
        try {
            channel = (ChannelSftp) opened.session.openChannel("sftp");
            channel.connect((int) UploadTarget.CONNECT_TIMEOUT.toMillis());
            try (OutputStream out = channel.put(target.remoteName(), ChannelSftp.OVERWRITE)) {
                body.writeTo(out);
            }
            String status;
            try {
                long remote = channel.lstat(target.remoteName()).getSize();
                status = remote == body.length() ? "SFTP_OK size " + remote + " confirmed"
                        : "SFTP_SIZE_MISMATCH remote " + remote + " local " + body.length();
            } catch (SftpException e) {
                status = "SFTP_OK size not verifiable (the account cannot read the file back)";
            }
            return new TransferResult(status, opened.fingerprint, opened.changed);
        } catch (SftpException e) {
            throw new UploadException("REMOTE_REJECTED", "The SFTP server refused the file (status " + e.id + ").", false);
        } catch (JSchException e) {
            throw SshSupport.classify(e, target);
        } catch (IOException e) {
            if (e instanceof SourceReadException) throw new UploadException("SOURCE_FAILED", "The stored bundle could not be read.", false);
            throw SshSupport.writeFailed(e);
        } finally {
            if (channel != null) channel.disconnect();
            opened.session.disconnect();
        }
    }

    /** Thrown by the service's body wrapper when the vault side failed, so a transfer does not call it a remote fault. */
    public static class SourceReadException extends IOException {
        public SourceReadException(Throwable cause) { super("source", cause); }
    }
}
