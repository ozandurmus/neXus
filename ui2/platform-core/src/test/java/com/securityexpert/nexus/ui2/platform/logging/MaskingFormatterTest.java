package com.securityexpert.nexus.ui2.platform.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;
import org.junit.jupiter.api.Test;

class MaskingFormatterTest {

    private static String logOnce(boolean raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StreamHandler handler = new StreamHandler(out, new MaskingFormatter(new SimpleFormatter(), raw));
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.log(Level.INFO, "peer {0} version 10.1.2", LogAddressMaskerTest.v4());
        logger.log(Level.INFO, "failed", new java.io.IOException("connect " + LogAddressMaskerTest.v6()));
        handler.flush();
        return out.toString();
    }

    @Test
    void masksMessageParametersAndStackTrace() {
        String out = logOnce(false);
        assertFalse(out.contains(LogAddressMaskerTest.v4()), out);
        assertFalse(out.contains(LogAddressMaskerTest.v6()), out);
        assertTrue(out.contains("peer <ip> version 10.1.2"), out);
        assertTrue(out.contains("IOException: connect <ip>"), out);
    }

    @Test
    void escapeHatchKeepsAddresses() {
        String out = logOnce(true);
        assertTrue(out.contains(LogAddressMaskerTest.v4()), out);
        assertTrue(out.contains(LogAddressMaskerTest.v6()), out);
    }

    @Test
    void installWrapsRootHandlersOnce() {
        Logger root = Logger.getAnonymousLogger();
        root.setUseParentHandlers(false);
        root.addHandler(new StreamHandler(new ByteArrayOutputStream(), new SimpleFormatter()));
        MaskingFormatter.install(root);
        MaskingFormatter.install(root);
        assertTrue(root.getHandlers()[0].getFormatter() instanceof MaskingFormatter);
        assertFalse(((MaskingFormatter) root.getHandlers()[0].getFormatter()).formatMessage(
                new java.util.logging.LogRecord(Level.INFO, "x " + LogAddressMaskerTest.v4())).contains("192"));
    }
}
