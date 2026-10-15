package com.securityexpert.nexus.ui2.sanitizer.format;

/** One unit of text a format hands to the rules: a log line, a cell, a JSON string value, an XML text node. */
public record Value(String text, boolean hostContext) {
}
