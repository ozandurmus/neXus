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

    DownloadResult download(Target target, String method, String path, Map<String, String> form, Credentials creds,
            OutputStream sink, long maxBytes, Duration timeout);
}
