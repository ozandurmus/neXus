package com.securityexpert.nexus.ui2.worker.transport.https;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Map;

import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Credentials;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.DownloadResult;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.TextResponse;

/** The calls an HTTPS vendor run makes; {@link HttpsDeviceClient} is the real one, tests script their own. */
public interface HttpsDeviceCalls {

    TextResponse get(Target target, String path, Credentials creds, Duration timeout, int maxBytes) throws IOException, InterruptedException;

    TextResponse postJson(Target target, String path, String json, Credentials creds, Duration timeout, int maxBytes)
            throws IOException, InterruptedException;

    /** A session login (Radware Cyber Controller): JSON body, no Authorization header; the session cookie it set, if any. */
    HttpsDeviceClient.SessionLogin login(Target target, String path, String json, Duration timeout) throws IOException, InterruptedException;

    DownloadResult download(Target target, String method, String path, Map<String, String> form, Credentials creds,
            OutputStream sink, long maxBytes, Duration timeout);

    /**
     * The same download with a request {@code Content-Type} the appliance insists on even for a GET (Infoblox file
     * downloads answer 415 without {@code application/force-download}, measured 2026-09-25). Implementations that
     * cannot set it fall back to the plain download.
     */
    default DownloadResult download(Target target, String method, String path, Map<String, String> form, Credentials creds,
            String requestContentType, OutputStream sink, long maxBytes, Duration timeout) {
        return download(target, method, path, form, creds, sink, maxBytes, timeout);
    }
}
