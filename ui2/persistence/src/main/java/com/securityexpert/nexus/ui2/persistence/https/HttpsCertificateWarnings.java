package com.securityexpert.nexus.ui2.persistence.https;

import java.util.concurrent.atomic.AtomicBoolean;

/** One job's warning, captured before TLS moves onto an HTTP client thread. */
public final class HttpsCertificateWarnings implements AutoCloseable {
    private static final ThreadLocal<AtomicBoolean> CURRENT = new ThreadLocal<>();

    private HttpsCertificateWarnings() {
        if (CURRENT.get() != null) throw new IllegalStateException("certificate warning scope already active");
        CURRENT.set(new AtomicBoolean());
    }

    public static HttpsCertificateWarnings open() { return new HttpsCertificateWarnings(); }
    public static AtomicBoolean current() { return CURRENT.get(); }
    public static String appendTo(String reason) {
        return CURRENT.get() != null && CURRENT.get().get() ? reason + "; certificate changed" : reason;
    }
    @Override public void close() { CURRENT.remove(); }
}
