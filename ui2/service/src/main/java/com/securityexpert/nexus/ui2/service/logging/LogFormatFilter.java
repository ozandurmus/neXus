package com.securityexpert.nexus.ui2.service.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import com.securityexpert.nexus.ui2.platform.logging.MaskingFormatter;

/**
 * Lets an event through only on the appender whose format is the selected one: {@code UI2_LOG_FORMAT=json} (or legacy
 * {@code NEXUS_LOG_FORMAT=json}) picks the JSON appender, {@code UI2_LOG_FORMAT=text} or unset (the default) picks the
 * text appender. Configure with {@code <json>true|false</json>}.
 */
public class LogFormatFilter extends Filter<ILoggingEvent> {

    private boolean json;

    public void setJson(boolean json) {
        this.json = json;
    }

    @Override
    public FilterReply decide(ILoggingEvent event) {
        return isJsonEnabled() == json ? FilterReply.NEUTRAL : FilterReply.DENY;
    }

    private boolean isJsonEnabled() {
        if (getContext() != null) {
            Object envObj = getContext().getObject(org.springframework.core.env.Environment.class.getName());
            if (envObj instanceof org.springframework.core.env.Environment env) {
                String ui2 = env.getProperty(MaskingFormatter.LOG_FORMAT_ENV);
                if (ui2 != null && !ui2.isBlank()) {
                    return "json".equalsIgnoreCase(ui2.trim());
                }
                String legacy = env.getProperty(MaskingFormatter.LEGACY_LOG_FORMAT_ENV);
                if (legacy != null && !legacy.isBlank()) {
                    return "json".equalsIgnoreCase(legacy.trim());
                }
            }
        }
        return MaskingFormatter.jsonRequested();
    }
}
