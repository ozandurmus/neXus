package com.securityexpert.nexus.ui2.service.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.securityexpert.nexus.ui2.platform.logging.LogAddressMasker;

/** Logback {@code %m}: the formatted message with IPv4/IPv6 literals masked (see {@link LogAddressMasker}). */
public class MaskingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return LogAddressMasker.mask(super.convert(event));
    }
}
