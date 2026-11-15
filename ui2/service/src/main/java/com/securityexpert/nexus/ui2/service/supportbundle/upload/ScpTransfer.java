package com.securityexpert.nexus.ui2.service.supportbundle.upload;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;

/** SCP sink mode ({@code scp -t .}): the exact size goes in the header, so the length must be known before the first byte. */
public final class ScpTransfer implements UploadTransfer {

    @Override
    public TransferResult send(UploadTarget target, char[] password, UploadBody body, HostKeyLog hostKeys) throws UploadException {
        SshSupport.Opened opened = SshSupport.open(target, password, hostKeys);
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) opened.session.openChannel("exec");
            channel.setCommand("scp -t .");
            OutputStream out = channel.getOutputStream();
            InputStream in = channel.getInputStream();
            channel.connect((int) UploadTarget.CONNECT_TIMEOUT.toMillis());
            ack(in);
            out.write(("C0600 " + body.length() + " " + target.remoteName() + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            ack(in);
            body.writeTo(new NonClosing(out));
            out.write(0);
            out.flush();
            ack(in);
            out.close();
            return new TransferResult("SCP_OK acknowledged by the server", opened.fingerprint, opened.changed);
        } catch (JSchException e) {
            throw SshSupport.classify(e, target);
        } catch (IOException e) {
            if (e instanceof SftpTransfer.SourceReadException) throw new UploadException("SOURCE_FAILED", "The stored bundle could not be read.", false);
            if (e instanceof RemoteRefused refused) throw new UploadException("REMOTE_REJECTED", refused.getMessage(), false);
            throw SshSupport.writeFailed(e);
        } finally {
            if (channel != null) channel.disconnect();
            opened.session.disconnect();
        }
    }

    private static final class RemoteRefused extends IOException {
        RemoteRefused(String message) { super(message); }
    }

    /** One status byte: 0 is good; 1 or 2 is followed by a text line, which is kept short and never contains our data. */
    private static void ack(InputStream in) throws IOException {
        int b = in.read();
        if (b == 0) return;
        if (b == -1) throw new IOException("closed");
        StringBuilder text = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n' && text.length() < 120) text.append((char) c);
        throw new RemoteRefused("The SCP server refused the file: " + text.toString().replaceAll("[^\\x20-\\x7E]", "?"));
    }

    private static final class NonClosing extends OutputStream {
        private final OutputStream target;
        NonClosing(OutputStream target) { this.target = target; }
        @Override public void write(int b) throws IOException { target.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException { target.write(b, off, len); }
        @Override public void flush() throws IOException { target.flush(); }
        @Override public void close() { }
    }
}
