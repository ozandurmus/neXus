package com.securityexpert.nexus.ui2.service.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import com.securityexpert.nexus.ui2.platform.logging.MaskingFormatter;

/**
 * Lets an event through only on the appender whose format is the selected one: {@code NEXUS_LOG_FORMAT=json} picks
 * the JSON appender, anything else (the default) the text appender. Configure with {@code <json>true|false</json>}.
 */
public class LogFormatFilter extends Filter<ILoggingEvent> {

    private boolean json;

    public void setJson(boolean json) {
        this.json = json;
    }

    @Override
    public FilterReply decide(ILoggingEvent event) {
        return MaskingFormatter.jsonRequested() == json ? FilterReply.NEUTRAL : FilterReply.DENY;
    }
}
