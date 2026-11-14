package com.securityexpert.nexus.ui2.service.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/** NEXUS_LOG_FORMAT=json turns on structured console logs; unset keeps the text format. */
class JsonLogFormatTest {

    @Configuration
    static class Empty {
    }

    private static String run(String format, Runnable logging) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (format == null) {
            System.clearProperty("NEXUS_LOG_FORMAT");
        } else {
            System.setProperty("NEXUS_LOG_FORMAT", format);
        }
        System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Empty.class)
                .web(WebApplicationType.NONE).logStartupInfo(false).run()) {
            logging.run();
        } finally {
            System.setOut(original);
            System.clearProperty("NEXUS_LOG_FORMAT");
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static void emit() {
        Logger log = LoggerFactory.getLogger("nexus.jsonlogtest");
        MDC.put("job_id", "job-123");
        MDC.put("correlation_id", "corr-456");
        try {
            log.warn("device h-4f2a9c01 unreachable 192.0.2.7", new IllegalStateException("boom 2001:db8::1"));
        } finally {
            MDC.clear();
        }
    }

    @Test
    void jsonModeWritesOneObjectWithTheRequiredFields() throws Exception {
        String out = run("json", JsonLogFormatTest::emit);
        String line = out.lines().filter(l -> l.contains("nexus.jsonlogtest")).findFirst().orElseThrow();
        JsonNode n = new ObjectMapper().readTree(line);
        assertTrue(n.has("@timestamp"));
        assertTrue("WARN".equals(n.get("level").asText()));
        assertTrue("nexus.jsonlogtest".equals(n.get("logger_name").asText()));
        assertTrue(n.has("thread_name"));
        // The log-time masker applies to the message and the stack trace in JSON mode too.
        assertTrue("device h-4f2a9c01 unreachable <ip>".equals(n.get("message").asText()), line);
        assertFalse(n.get("stack_trace").asText().contains("2001:db8"), line);
        assertTrue("job-123".equals(n.get("job_id").asText()));
        assertTrue("corr-456".equals(n.get("correlation_id").asText()));
        assertTrue(n.get("stack_trace").asText().contains("IllegalStateException: boom <ip>"));
    }

    @Test
    void defaultStaysTextFormat() throws Exception {
        String out = run(null, JsonLogFormatTest::emit);
        String line = out.lines().filter(l -> l.contains("nexus.jsonlogtest")).findFirst().orElseThrow();
        assertFalse(line.trim().startsWith("{"));
        assertTrue(line.contains("device h-4f2a9c01 unreachable <ip>"));
        assertEquals(1, out.lines().filter(l -> l.contains("nexus.jsonlogtest")).count(), out);
    }
}
