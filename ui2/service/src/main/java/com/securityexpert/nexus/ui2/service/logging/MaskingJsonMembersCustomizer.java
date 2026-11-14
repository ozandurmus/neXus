package com.securityexpert.nexus.ui2.service.logging;

import com.securityexpert.nexus.ui2.platform.logging.LogAddressMasker;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.json.JsonWriter.ValueProcessor;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/** JSON console logs: every string value (message, stack trace, MDC) passes through {@link LogAddressMasker}. */
public class MaskingJsonMembersCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

    @Override
    public void customize(JsonWriter.Members<Object> members) {
        members.applyingValueProcessor(ValueProcessor.of(String.class, LogAddressMasker::mask));
    }
}
