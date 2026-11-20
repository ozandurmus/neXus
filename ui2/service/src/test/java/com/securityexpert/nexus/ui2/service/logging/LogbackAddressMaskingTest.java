package com.securityexpert.nexus.ui2.service.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

/** Drives the shipped logback-spring.xml (console appender, masking converters) and reads what it writes. */
class LogbackAddressMaskingTest {

    private static final String V4 = String.join(".", "192", "0", "2", "42");
    private static final String V6 = String.join(":", "2001", "db8", "", "a1", "7");

    private static String run(Runnable logging) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        // Spring Boot registers its Environment in the logger context; the JSON appender's encoder needs it.
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("logging.structured.json.customizer", MaskingJsonMembersCustomizer.class.getName());
        context.putObject(org.springframework.core.env.Environment.class.getName(), env);
        try {
            System.setOut(new PrintStream(captured, true));
            URL config = LogbackAddressMaskingTest.class.getClassLoader().getResource("logback-spring.xml");
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(config);
            Logger logger = context.getLogger("test.masking");
            logging.run();
            logger.info("peer {} version 10.1.2", V4);
            logger.warn("failed", new java.io.IOException("connect " + V6));
        } finally {
            context.stop();
            System.setOut(original);
        }
        return captured.toString();
    }

    @Test
    void messageAndStackTraceAreMaskedAndVersionSurvives() throws Exception {
        String out = run(() -> { });
        assertFalse(out.contains(V4), out);
        assertFalse(out.contains(V6), out);
        assertTrue(out.contains("peer <ip> version 10.1.2"), out);
        assertTrue(out.contains("IOException: connect <ip>"), out);
    }

    @Test
    void jsonEncoderWritesCorrelationIdAndJobMdcAsTopLevelFields() throws Exception {
        String prev = System.getProperty("NEXUS_LOG_FORMAT");
        System.setProperty("NEXUS_LOG_FORMAT", "json");
        try (var reqScope = com.securityexpert.nexus.ui2.platform.logging.JobLogContext.open(
                "job-99", "cp_inventory_collect", "req-abc12345")) {
            String out = run(() -> { });
            String[] lines = out.trim().split("\n");
            assertTrue(lines.length >= 2, out);
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode first = mapper.readTree(lines[0]);
            org.junit.jupiter.api.Assertions.assertEquals("job:job-99", first.get("correlation_id").asText());
            org.junit.jupiter.api.Assertions.assertEquals("job-99", first.get("job_id").asText());
            org.junit.jupiter.api.Assertions.assertEquals("cp_inventory_collect", first.get("job_kind").asText());
            org.junit.jupiter.api.Assertions.assertEquals("req-abc12345", first.get("request_correlation_id").asText());
            org.junit.jupiter.api.Assertions.assertEquals("peer <ip> version 10.1.2", first.get("message").asText());
        } finally {
            if (prev == null) {
                System.clearProperty("NEXUS_LOG_FORMAT");
            } else {
                System.setProperty("NEXUS_LOG_FORMAT", prev);
            }
        }
        org.junit.jupiter.api.Assertions.assertNull(org.slf4j.MDC.get("correlation_id"));
    }

    @Test
    void rendersOneLogEventInBothFormatsWithEventCodeAndMasking() throws Exception {
        Runnable emitOne = () -> {
            // Also exercises a bracketed event code at the start of the message in both formats.
        };
        PrintStream original = System.out;
        String prevUi2 = System.getProperty("UI2_LOG_FORMAT");
        String prevNexus = System.getProperty("NEXUS_LOG_FORMAT");
        try {
            // 1. Text format (UI2_LOG_FORMAT=text)
            System.setProperty("UI2_LOG_FORMAT", "text");
            System.clearProperty("NEXUS_LOG_FORMAT");
            String textOut = runSingleEvent(
                    "[PAN_CONFIG_SYNC] peer " + V4 + " sync failed",
                    new java.io.IOException("connect " + V6));
            assertFalse(textOut.trim().startsWith("{"), textOut);
            assertTrue(textOut.contains("[PAN_CONFIG_SYNC] peer <ip> sync failed"), textOut);
            assertTrue(textOut.contains("IOException: connect <ip>"), textOut);
            assertFalse(textOut.contains(V4), textOut);
            assertFalse(textOut.contains(V6), textOut);

            // 2. JSON format (UI2_LOG_FORMAT=json)
            System.setProperty("UI2_LOG_FORMAT", "json");
            String jsonOut = runSingleEvent(
                    "[PAN_CONFIG_SYNC] peer " + V4 + " sync failed",
                    new java.io.IOException("connect " + V6));
            String[] lines = jsonOut.trim().split("\n");
            org.junit.jupiter.api.Assertions.assertEquals(1, lines.length, jsonOut);
            assertFalse(jsonOut.contains(V4), jsonOut);
            assertFalse(jsonOut.contains(V6), jsonOut);

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(lines[0]);
            org.junit.jupiter.api.Assertions.assertNotNull(
                    java.time.Instant.parse(node.get("@timestamp").asText()));
            org.junit.jupiter.api.Assertions.assertEquals("WARN", node.get("level").asText());
            org.junit.jupiter.api.Assertions.assertEquals("test.masking", node.get("logger_name").asText());
            assertTrue(node.has("thread_name") && !node.get("thread_name").asText().isBlank());
            org.junit.jupiter.api.Assertions.assertEquals(
                    "[PAN_CONFIG_SYNC] peer <ip> sync failed", node.get("message").asText());
            org.junit.jupiter.api.Assertions.assertEquals(
                    "PAN_CONFIG_SYNC", node.get("event").asText());
            assertTrue(node.get("stack_trace").asText().contains("java.io.IOException: connect <ip>"));
        } finally {
            System.setOut(original);
            if (prevUi2 == null) {
                System.clearProperty("UI2_LOG_FORMAT");
            } else {
                System.setProperty("UI2_LOG_FORMAT", prevUi2);
            }
            if (prevNexus == null) {
                System.clearProperty("NEXUS_LOG_FORMAT");
            } else {
                System.setProperty("NEXUS_LOG_FORMAT", prevNexus);
            }
        }
    }

    private static String runSingleEvent(String message, Throwable error) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        LoggerContext context = new LoggerContext();
        context.setMDCAdapter(new ch.qos.logback.classic.util.LogbackMDCAdapter());
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("logging.structured.json.customizer", MaskingJsonMembersCustomizer.class.getName());
        context.putObject(org.springframework.core.env.Environment.class.getName(), env);
        try {
            System.setOut(new PrintStream(captured, true));
            URL config = LogbackAddressMaskingTest.class.getClassLoader().getResource("logback-spring.xml");
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(config);
            Logger logger = context.getLogger("test.masking");
            logger.warn(message, error);
        } finally {
            context.stop();
            System.setOut(original);
        }
        return captured.toString();
    }
}
