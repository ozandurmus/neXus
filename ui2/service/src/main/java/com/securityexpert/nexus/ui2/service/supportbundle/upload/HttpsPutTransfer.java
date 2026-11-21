package com.securityexpert.nexus.ui2.service.supportbundle.upload;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Cisco CXD: {@code PUT https://<host>/home/<file>} with Basic auth (case number and upload token), raw body, success 2xx
 * (CXD answers 201). TLS is the JVM's normal verification; there is no way to switch it off from here. The scheme is a
 * constructor argument only so a test can talk to a local plain server; the API never supplies it.
 */
public final class HttpsPutTransfer implements UploadTransfer {
    private final String scheme;

    public HttpsPutTransfer() { this("https"); }

    HttpsPutTransfer(String scheme) { this.scheme = scheme; }

    @Override
    public TransferResult send(UploadTarget target, char[] password, UploadBody body, HostKeyLog hostKeys) throws UploadException {
        HttpURLConnection http = null;
        byte[] secret = SshSupport.utf8(password);
        try {
            URL url = URI.create(scheme + "://" + (target.host().indexOf(':') >= 0 ? "[" + target.host() + "]" : target.host()) + ":"
                    + target.port() + "/home/" + target.remoteName()).toURL();
            http = (HttpURLConnection) url.openConnection();
            http.setRequestMethod("PUT");
            http.setInstanceFollowRedirects(false);
            http.setConnectTimeout((int) UploadTarget.CONNECT_TIMEOUT.toMillis());
            http.setReadTimeout((int) UploadTarget.STALL_TIMEOUT.toMillis());
            byte[] userPart = (target.user() + ":").getBytes(StandardCharsets.UTF_8);
            byte[] joined = new byte[userPart.length + secret.length];
            System.arraycopy(userPart, 0, joined, 0, userPart.length);
            System.arraycopy(secret, 0, joined, userPart.length, secret.length);
            http.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(joined));
            java.util.Arrays.fill(joined, (byte) 0);
            http.setRequestProperty("Content-Type", "application/gzip");
            http.setRequestProperty("Expect", "100-continue");
            http.setFixedLengthStreamingMode(body.length());
            http.setDoOutput(true);
            try (OutputStream out = http.getOutputStream()) {
                body.writeTo(out);
            }
            int status = http.getResponseCode();
            if (status == 401 || status == 403) {
                throw new UploadException("AUTH_FAILED", "The destination answered HTTP " + status + " to the credential.", false);
            }
            if (status < 200 || status > 299) {
                throw new UploadException("REMOTE_REJECTED", "The destination answered HTTP " + status + ".", false);
            }
            return new TransferResult("HTTP " + status, null, false);
        } catch (UploadException e) {
            throw e;
        } catch (IOException e) {
            if (e instanceof SftpTransfer.SourceReadException) throw new UploadException("SOURCE_FAILED", "The stored bundle could not be read.", false);
            String where = target.host() + ":" + target.port();
            boolean connect = e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException
                    || e instanceof java.net.SocketTimeoutException && String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("connect");
            if (connect) {
                throw new UploadException("CONNECTION_FAILED", "Could not reach " + where + " (" + e.getClass().getSimpleName()
                        + "). The bank firewall may need an opening for this destination from the Overwatch host.", true);
            }
            throw new UploadException("WRITE_FAILED", "The HTTPS transfer to " + where + " failed (" + e.getClass().getSimpleName() + ").", false);
        } finally {
            java.util.Arrays.fill(secret, (byte) 0);
            if (http != null) http.disconnect();
        }
    }
}
