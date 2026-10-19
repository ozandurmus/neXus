package com.securityexpert.nexus.ui2.sanitizer.format;

/**
 * One unit of text a format hands to the rules: a log line, a cell, a JSON string value, an XML text node.
 * {@code hostContext}: under a host-like header or key; {@code serialContext}: under a serial-like header or key
 * ({@code SerialRule#isSerialKey}), so the whole value is a serial.
 */
public record Value(String text, boolean hostContext, boolean serialContext) {

    public Value(String text, boolean hostContext) {
        this(text, hostContext, false);
    }
}
