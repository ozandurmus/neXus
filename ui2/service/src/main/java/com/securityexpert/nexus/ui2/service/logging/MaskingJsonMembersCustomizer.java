package com.securityexpert.nexus.ui2.service.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.securityexpert.nexus.ui2.platform.logging.JobLogContext;
import com.securityexpert.nexus.ui2.platform.logging.JsonLogFields;
import com.securityexpert.nexus.ui2.platform.logging.LogAddressMasker;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.json.JsonWriter.ValueProcessor;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * JSON console logs: every string value (message, stack trace, MDC) passes through {@link LogAddressMasker}, the
 * configured {@link JsonLogFields} field set (includes, excludes, renames) is applied from one shared definition, and
 * any correlation/job MDC key held in {@link JobLogContext} is written as a top-level field.
 */
public class MaskingJsonMembersCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    private final JsonLogFields fields;

    public MaskingJsonMembersCustomizer() {
        this(JsonLogFields.fromEnvironment());
    }

    MaskingJsonMembersCustomizer(JsonLogFields fields) {
        this.fields = Objects.requireNonNull(fields, "fields");
    }

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.add(JsonLogFields.EVENT, MaskingJsonMembersCustomizer::extractEvent).whenNotNull();
        members.addMapEntries(MaskingJsonMembersCustomizer::extraContextEntries);
        members.applyingPathFilter(path -> isTopLevel(path) && !fields.includes(path.name()));
        members.applyingNameProcessor((path, name) -> isTopLevel(path) ? fields.fieldName(name) : name);
        members.applyingValueProcessor(ValueProcessor.of(String.class, LogAddressMasker::mask));
    }

    private static boolean isTopLevel(JsonWriter.MemberPath path) {
        return path != null && path.parent() != null && path.parent().parent() == null && path.name() != null;
    }

    private static String extractEvent(Object event) {
        if (event instanceof ILoggingEvent loggingEvent) {
            Map<String, String> mdc = loggingEvent.getMDCPropertyMap();
            if (mdc != null && mdc.containsKey(JsonLogFields.EVENT)) {
                return null;
            }
            return JsonLogFields.extractEventCode(loggingEvent.getFormattedMessage());
        }
        return null;
    }

    private static Map<String, String> extraContextEntries(Object event) {
        Map<String, String> snapshot = JobLogContext.mdcSnapshot();
        if (snapshot.isEmpty()) {
            return Map.of();
        }
        Map<String, String> existing = event instanceof ILoggingEvent loggingEvent
                ? loggingEvent.getMDCPropertyMap()
                : Map.of();
        boolean hasExtractedEvent = extractEvent(event) != null;
        Map<String, String> extra = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            if (hasExtractedEvent && JsonLogFields.EVENT.equals(entry.getKey())) {
                continue;
            }
            if (existing == null || !existing.containsKey(entry.getKey())) {
                extra.put(entry.getKey(), entry.getValue());
            }
        }
        return extra;
    }
}
