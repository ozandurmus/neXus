package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * Reduces one finished PAN-OS log job ({@code type=log&action=get}) to the phase A measurement summary, in memory.
 * The summary carries counts, field names, formats and enum-like vendor values only: no receive time, user name,
 * address, host name, serial, path or message text ever reaches it. A value that does not look like a vendor
 * enum (letters, digits, '-' and '_', no dots, colons or spaces) is counted as {@code OTHER}, never stored.
 */
final class PanLogMeasurementSummary {
    static final String SYSTEM = "pan_system";
    static final String CONFIG = "pan_config";
    static final int EVENTID_TOP = 20;
    static final String OTHER = "OTHER";
    private static final Pattern ENUM_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,47}");
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> SEVERITIES = Set.of("informational", "low", "medium", "high", "critical");
    private static final Pattern STATUS_SUCCESS = Pattern.compile("<response\\s[^>]*status\\s*=\\s*[\"']success[\"']");
    private static final Pattern ENQUEUED_JOB = Pattern.compile("<job>\\s*([0-9]{1,20})\\s*</job>");
    private static final Pattern JOB_STATUS = Pattern.compile("<job>.*?<status>\\s*([A-Z]{1,16})\\s*</status>", Pattern.DOTALL);
    private static final String PAN_RECEIVE_TIME_SHAPE = "9999/99/99 99:99:99";
    private static final DateTimeFormatter PAN_RECEIVE_TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    private PanLogMeasurementSummary() {}

    static boolean success(String body) {
        return body != null && STATUS_SUCCESS.matcher(body).find();
    }

    /** The digits-only log job id an enqueue answered; empty for anything else. */
    static Optional<String> enqueuedJobId(String body) {
        if (!success(body)) return Optional.empty();
        Matcher matcher = ENQUEUED_JOB.matcher(body);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** {@code FIN}, {@code ACT}, {@code PEND}...; empty when the answer carries no job status. */
    static Optional<String> jobStatus(String body) {
        if (!success(body)) return Optional.empty();
        Matcher matcher = JOB_STATUS.matcher(body);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** The summary of a FIN answer, or empty when the answer cannot be parsed (the caller records UNKNOWN). */
    static Optional<Map<String, Object>> summarize(String source, String body, long enqueueToFinMs, int polls) {
        Element logs;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(body)));
            Element result = child(document.getDocumentElement(), "result");
            Element log = result == null ? null : child(result, "log");
            logs = log == null ? null : child(log, "logs");
        } catch (Exception unparseable) {
            return Optional.empty();
        }
        if (logs == null) return Optional.empty();
        int entries = 0;
        Set<String> fieldNames = new TreeSet<>();
        int unrecognisedFieldNames = 0;
        Map<String, Integer> timeShapes = new TreeMap<>();
        LocalDateTime first = null, last = null;
        int unparsedTimes = 0;
        Map<String, Integer> subtypes = new TreeMap<>(), severities = new TreeMap<>(), eventids = new TreeMap<>(),
                commands = new TreeMap<>();
        for (Node node = logs.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element entry) || !"entry".equals(entry.getTagName())) continue;
            entries++;
            String receiveTime = null, subtype = null, severity = null, eventid = null, cmd = null;
            for (Node field = entry.getFirstChild(); field != null; field = field.getNextSibling()) {
                if (!(field instanceof Element element)) continue;
                String name = element.getTagName();
                if (FIELD_NAME.matcher(name).matches()) fieldNames.add(name); else unrecognisedFieldNames++;
                String text = element.getTextContent() == null ? "" : element.getTextContent().strip();
                switch (name) {
                    case "receive_time" -> receiveTime = text;
                    case "subtype" -> subtype = text;
                    case "severity" -> severity = text;
                    case "eventid" -> eventid = text;
                    case "cmd" -> cmd = text;
                    default -> { }
                }
            }
            if (receiveTime != null) {
                timeShapes.merge(shape(receiveTime), 1, Integer::sum);
                try {
                    LocalDateTime at = LocalDateTime.parse(receiveTime, PAN_RECEIVE_TIME);
                    if (first == null || at.isBefore(first)) first = at;
                    if (last == null || at.isAfter(last)) last = at;
                } catch (DateTimeParseException unparsed) {
                    unparsedTimes++;
                }
            }
            if (SYSTEM.equals(source)) {
                subtypes.merge(token(subtype), 1, Integer::sum);
                severities.merge(severity != null && SEVERITIES.contains(severity) ? severity : OTHER, 1, Integer::sum);
                eventids.merge(token(eventid), 1, Integer::sum);
            } else {
                commands.merge(token(cmd), 1, Integer::sum);
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "MEASURED");
        summary.put("requestedEntries", com.securityexpert.nexus.ui2.jobs.diagnostic.PanSyslogMeasure.REQUESTED_ENTRIES);
        summary.put("entries", entries);
        summary.put("reportedCount", count(logs.getAttribute("count")));
        summary.put("capped", entries >= com.securityexpert.nexus.ui2.jobs.diagnostic.PanSyslogMeasure.REQUESTED_ENTRIES);
        Long spanSeconds = first == null || unparsedTimes > 0 ? null : Duration.between(first, last).toSeconds();
        summary.put("spanSeconds", spanSeconds);
        summary.put("entriesPerDayEstimate", spanSeconds == null || spanSeconds <= 0 || entries < 2 ? null
                : Math.round(entries * 864000.0 / spanSeconds) / 10.0);
        summary.put("resultBytes", (long) body.getBytes(StandardCharsets.UTF_8).length);
        summary.put("enqueueToFinMs", enqueueToFinMs);
        summary.put("polls", polls);
        summary.put("fieldNames", new java.util.ArrayList<>(fieldNames));
        summary.put("unrecognisedFieldNames", unrecognisedFieldNames);
        summary.put("receiveTimeFormat", timeShapes.isEmpty() ? null
                : timeShapes.size() > 1 ? "MIXED" : format(timeShapes.keySet().iterator().next()));
        summary.put("receiveTimeUnparsed", unparsedTimes);
        if (SYSTEM.equals(source)) {
            summary.put("subtypeCounts", subtypes);
            summary.put("severityCounts", severities);
            summary.put("eventidTopCounts", top(eventids, EVENTID_TOP));
            summary.put("eventidDistinct", eventids.size());
        } else {
            summary.put("cmdCounts", commands);
        }
        return Optional.of(summary);
    }

    static Map<String, Object> unknown(String reason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "UNKNOWN");
        summary.put("reason", reason);
        return summary;
    }

    /** A value's character classes only: digits to 9, letters to a; everything else kept, capped at 40. */
    static String shape(String value) {
        StringBuilder shape = new StringBuilder();
        for (int i = 0; i < value.length() && shape.length() < 40; i++) {
            char c = value.charAt(i);
            shape.append(Character.isDigit(c) ? '9' : Character.isLetter(c) ? 'a'
                    : c == ' ' || c == '/' || c == ':' || c == '-' || c == '.' || c == '+' ? c : '?');
        }
        return shape.toString();
    }

    private static String format(String shape) {
        return PAN_RECEIVE_TIME_SHAPE.equals(shape) ? "yyyy/MM/dd HH:mm:ss" : shape;
    }

    private static String token(String value) {
        return value != null && ENUM_TOKEN.matcher(value).matches() ? value : OTHER;
    }

    private static Integer count(String attribute) {
        return attribute != null && attribute.matches("[0-9]{1,9}") ? Integer.valueOf(attribute) : null;
    }

    private static Map<String, Integer> top(Map<String, Integer> counts, int limit) {
        Map<String, Integer> top = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit).forEach(entry -> top.put(entry.getKey(), entry.getValue()));
        return top;
    }

    private static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) return element;
        }
        return null;
    }
}
