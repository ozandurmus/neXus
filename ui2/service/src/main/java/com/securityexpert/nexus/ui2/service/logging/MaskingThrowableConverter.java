package com.securityexpert.nexus.ui2.service.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.securityexpert.nexus.ui2.platform.logging.LogAddressMasker;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

/** Logback {@code %wEx}: the stack trace text with IPv4/IPv6 literals masked (see {@link LogAddressMasker}). */
public class MaskingThrowableConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return LogAddressMasker.mask(super.convert(event));
    }
}
