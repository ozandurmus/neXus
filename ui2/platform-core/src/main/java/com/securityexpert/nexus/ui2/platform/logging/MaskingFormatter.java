package com.securityexpert.nexus.ui2.platform.logging;

import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * java.util.logging path of the last-line address masking (see {@link LogAddressMasker}): wraps a formatter and masks
 * the finished text, message, parameters and stack trace alike. {@link #install()} wraps the formatter of every
 * handler on the root logger, creating the default console handler when there is none.
 */
public final class MaskingFormatter extends Formatter {

    private final Formatter delegate;
    private final boolean rawAddresses;

    public MaskingFormatter(Formatter delegate) {
        this(delegate, LogAddressMasker.rawAddressesEnabled(System::getenv));
    }

    MaskingFormatter(Formatter delegate, boolean rawAddresses) {
        this.delegate = delegate;
        this.rawAddresses = rawAddresses;
    }

    @Override
    public String format(LogRecord record) {
        return LogAddressMasker.mask(delegate.format(record), rawAddresses);
    }

    @Override
    public String formatMessage(LogRecord record) {
        return LogAddressMasker.mask(delegate.formatMessage(record), rawAddresses);
    }

    @Override
    public String getHead(Handler h) {
        return delegate.getHead(h);
    }

    @Override
    public String getTail(Handler h) {
        return delegate.getTail(h);
    }

    /** Wraps the formatter of each root-logger handler (idempotent). Call once at process start. */
    public static void install() {
        install(LogManager.getLogManager().getLogger(""));
    }

    static void install(Logger root) {
        if (root.getHandlers().length == 0) {
            root.addHandler(new ConsoleHandler());
        }
        for (Handler handler : root.getHandlers()) {
            Formatter current = handler.getFormatter();
            if (!(current instanceof MaskingFormatter)) {
                handler.setFormatter(new MaskingFormatter(current));
            }
        }
    }
}
