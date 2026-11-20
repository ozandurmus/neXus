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

/** UI2_LOG_FORMAT=json (or legacy NEXUS_LOG_FORMAT=json) turns on structured console logs; text or unset keeps the text format. */
class JsonLogFormatTest {

    @Configuration
    static class Empty {
    }

    private static String run(String format, Runnable logging) throws Exception {
        return run("UI2_LOG_FORMAT", format, logging);
    }

    private static String run(String propertyName, String format, Runnable logging) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        System.clearProperty("UI2_LOG_FORMAT");
        System.clearProperty("NEXUS_LOG_FORMAT");
        if (format != null) {
            System.setProperty(propertyName, format);
        }
        System.setOut(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Empty.class)
                .web(WebApplicationType.NONE).logStartupInfo(false).run()) {
            logging.run();
        } finally {
            System.setOut(original);
            System.clearProperty("UI2_LOG_FORMAT");
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

    private static void emitWithBracketedEvent() {
        Logger log = LoggerFactory.getLogger("nexus.jsonlogtest.event");
        log.warn("[NOTIFY_SYSLOG_FAILED] syslog target 192.0.2.7 unreachable",
                new IllegalStateException("boom 2001:db8::1"));
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
        assertFalse(n.has("event"), line);
        assertFalse(n.get("stack_trace").asText().contains("2001:db8"), line);
        assertTrue("job-123".equals(n.get("job_id").asText()));
        assertTrue("corr-456".equals(n.get("correlation_id").asText()));
        assertTrue(n.get("stack_trace").asText().contains("IllegalStateException: boom <ip>"));
    }

    @Test
    void jsonModeExtractsLeadingBracketedEventCodeAndMasksMessage() throws Exception {
        String out = run("json", JsonLogFormatTest::emitWithBracketedEvent);
        String line = out.lines().filter(l -> l.contains("nexus.jsonlogtest.event")).findFirst().orElseThrow();
        JsonNode n = new ObjectMapper().readTree(line);
        assertEquals("NOTIFY_SYSLOG_FAILED", n.get("event").asText(), line);
        assertEquals("[NOTIFY_SYSLOG_FAILED] syslog target <ip> unreachable", n.get("message").asText(), line);
        assertFalse(line.contains("192.0.2.7"), line);
        assertFalse(line.contains("2001:db8"), line);
    }

    @Test
    void defaultAndExplicitTextStayTextFormat() throws Exception {
        for (String mode : new String[] {null, "text"}) {
            String out = run(mode, JsonLogFormatTest::emit);
            String line = out.lines().filter(l -> l.contains("nexus.jsonlogtest")).findFirst().orElseThrow();
            assertFalse(line.trim().startsWith("{"), line);
            assertTrue(line.contains("device h-4f2a9c01 unreachable <ip>"), line);
            assertEquals(1, out.lines().filter(l -> l.contains("nexus.jsonlogtest")).count(), out);
        }
    }
}
