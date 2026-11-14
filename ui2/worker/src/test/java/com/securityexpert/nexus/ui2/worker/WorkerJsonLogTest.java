package com.securityexpert.nexus.ui2.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.platform.logging.JobLogContext;
import com.securityexpert.nexus.ui2.platform.logging.JsonLogFormatter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;
import org.junit.jupiter.api.Test;

/** The worker's JSON lines parse with a real JSON parser and round-trip hostile text. */
class WorkerJsonLogTest {

    @Test
    void hostileTextRoundTripsAndStaysOnOneLine() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        StreamHandler handler = new StreamHandler(bytes, new JsonLogFormatter());
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        String hostile = "quote\" back\\ nl\n cr\r tab\t ctl\u0001\u001f uml\u00fc euro\u20ac emoji\uD83D\uDE00";
        try (var scope = JobLogContext.open("42")) {
            logger.log(Level.INFO, "{0}", hostile);
            logger.log(Level.SEVERE, "boom 10.20.30.40", new IllegalStateException("bad " + hostile));
        }
        handler.flush();
        String[] lines = bytes.toString(StandardCharsets.UTF_8).split("\n");
        assertEquals(2, lines.length);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode first = mapper.readTree(lines[0]);
        assertEquals(hostile, first.get("message").asText());
        assertEquals("INFO", first.get("level").asText());
        assertEquals("42", first.get("job_id").asText());
        assertTrue(first.has("@timestamp") && first.has("logger_name") && first.has("thread_name"));
        JsonNode second = mapper.readTree(lines[1]);
        assertEquals("boom <ip>", second.get("message").asText());
        assertEquals("ERROR", second.get("level").asText());
        String trace = second.get("stack_trace").asText();
        assertTrue(trace.startsWith("java.lang.IllegalStateException: bad quote\" back\\ nl\n"), trace);
        assertTrue(trace.contains("WorkerJsonLogTest"), trace);
        assertFalse(second.get("message").asText().contains("10.20.30.40"));
    }
}
