package com.securityexpert.nexus.ui2.worker.transport.https;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.worker.transcript.JobTranscript;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope;

class HttpsTranscriptCaptureTest {
    @SuppressWarnings("unchecked")
    private static HttpResponse<String> syntheticResponse() {
        HttpHeaders headers = HttpHeaders.of(Map.of("Content-Type", List.of("text/plain"),
                "Set-Cookie", List.of("synthetic-secret")), (name, value) -> true);
        return (HttpResponse<String>) Proxy.newProxyInstance(HttpResponse.class.getClassLoader(),
                new Class<?>[] {HttpResponse.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "statusCode" -> 200;
                    case "headers" -> headers;
                    default -> throw new AssertionError(method.getName());
                });
    }

    @Test void recordsRequestAndResponseOnlyWithScopeWithoutContactingAnEndpoint() throws Exception {
        HttpsDeviceClient client = new HttpsDeviceClient();
        var target = new HttpsDeviceClient.Target("192.0.2.10", 443);
        var credentials = new HttpsDeviceClient.Credentials("synthetic-user", "synthetic-secret".toCharArray());
        JobTranscript transcript = new JobTranscript();
        assertThrows(IllegalArgumentException.class,
                () -> client.get(target, "/bad path", credentials, Duration.ofSeconds(1), 1024));
        HttpsDeviceClient.recordResponse(syntheticResponse(), "outside");
        try (JobTranscriptScope ignored = JobTranscriptScope.open(transcript)) {
            assertThrows(IllegalArgumentException.class,
                    () -> client.get(target, "/bad path", credentials, Duration.ofSeconds(1), 1024));
            HttpsDeviceClient.recordResponse(syntheticResponse(), "synthetic answer");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transcript.writeTo(out);
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("GET /bad path"));
        assertTrue(text.contains("synthetic answer"));
        assertFalse(text.contains("synthetic-secret"));
        assertFalse(text.contains("Set-Cookie"));
        assertFalse(text.contains("outside"));
    }
}
