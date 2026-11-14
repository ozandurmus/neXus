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
        try {
            System.setOut(new PrintStream(captured, true));
            URL config = LogbackAddressMaskingTest.class.getClassLoader().getResource("logback-spring.xml");
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(config);
            Logger logger = context.getLogger("test.masking");
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
}
