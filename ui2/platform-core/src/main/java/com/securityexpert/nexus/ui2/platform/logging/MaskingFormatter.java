package com.securityexpert.nexus.ui2.platform.logging;

import com.securityexpert.nexus.ui2.platform.OverwatchEnv;

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

    public static final String LOG_FORMAT_ENV = "UI2_LOG_FORMAT";
    public static final String LEGACY_LOG_FORMAT_ENV = "NEXUS_LOG_FORMAT";

    private final Formatter delegate;
    private final boolean rawAddresses;

    public MaskingFormatter(Formatter delegate) {
        this(delegate, LogAddressMasker.rawAddressesEnabled(OverwatchEnv::get));
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

    /**
     * True when {@code UI2_LOG_FORMAT=json} (or legacy {@code NEXUS_LOG_FORMAT=json} when {@code UI2_LOG_FORMAT}
     * is unset) in the environment or system properties. Default is {@code text} ({@code false}).
     */
    public static boolean jsonRequested() {
        return jsonRequested(MaskingFormatter::lookupFormatProperty);
    }

    public static boolean jsonRequested(java.util.function.UnaryOperator<String> lookup) {
        String ui2 = lookup.apply(LOG_FORMAT_ENV);
        if (ui2 != null && !ui2.isBlank()) {
            return "json".equalsIgnoreCase(ui2.trim());
        }
        String legacy = lookup.apply(LEGACY_LOG_FORMAT_ENV);
        return legacy != null && "json".equalsIgnoreCase(legacy.trim());
    }

    private static String lookupFormatProperty(String key) {
        String value = OverwatchEnv.get(key);
        if (value == null || value.isBlank()) {
            value = System.getProperty(key);
        }
        return value;
    }

    /**
     * Wraps the formatter of each root-logger handler (idempotent). Call once at process start. With
     * {@code UI2_LOG_FORMAT=json} (or {@code NEXUS_LOG_FORMAT=json}) each handler gets the {@link JsonLogFormatter}
     * (which masks on its own); otherwise the existing text format is kept and masked.
     */
    public static void install() {
        install(LogManager.getLogManager().getLogger(""), jsonRequested());
    }

    static void install(Logger root) {
        install(root, false);
    }

    static void install(Logger root, boolean json) {
        if (root.getHandlers().length == 0) {
            root.addHandler(new ConsoleHandler());
        }
        for (Handler handler : root.getHandlers()) {
            Formatter current = handler.getFormatter();
            if (json) {
                if (!(current instanceof JsonLogFormatter)) {
                    handler.setFormatter(new JsonLogFormatter());
                }
            } else if (!(current instanceof MaskingFormatter)) {
                handler.setFormatter(new MaskingFormatter(current));
            }
        }
    }
}
