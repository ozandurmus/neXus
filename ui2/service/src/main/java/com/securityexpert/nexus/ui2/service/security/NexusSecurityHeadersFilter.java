package com.securityexpert.nexus.ui2.service.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.util.OnCommittedResponseWrapper;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The response security headers decided by the PO on 2026-11-15: {@code X-Content-Type-Options: nosniff},
 * {@code X-Frame-Options: DENY} and {@code Cache-Control: no-store}. Each is written only when the response has not already set
 * it, at the moment the response is committed (so a handler's own cache policy, the immutable {@code /assets/*} and the revalidated
 * index page win, and a controller that sets nosniff itself is never duplicated).
 *
 * <p>This replaces Spring Security's {@code HeaderWriterFilter} on purpose. For an asynchronous response (a streamed download, a
 * deferred result) the servlet container commits on another thread than the one leaving the filter chain, and the stock filter
 * lets both threads write headers at once; Tomcat's header list is not thread-safe, and under load a response left the service with
 * a blank header line between two real ones (seen in the HTTP characterization test). Here every write for one request goes through
 * one lock, taken by whichever thread commits first, and the other waits for it.</p>
 */
public final class NexusSecurityHeadersFilter extends OncePerRequestFilter {

    private static final String LOCK_ATTRIBUTE = NexusSecurityHeadersFilter.class.getName() + ".LOCK";

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Object lock = request.getAttribute(LOCK_ATTRIBUTE);
        if (lock == null) {
            lock = new Object();
            request.setAttribute(LOCK_ATTRIBUTE, lock);
        }
        HeaderResponse wrapped = new HeaderResponse(response, lock);
        try {
            chain.doFilter(request, wrapped);
        } finally {
            wrapped.writeHeaders();
        }
    }

    private static final class HeaderResponse extends OnCommittedResponseWrapper {

        private final Object lock;
        private boolean written;

        HeaderResponse(HttpServletResponse response, Object lock) {
            super(response);
            this.lock = lock;
        }

        @Override
        protected void onResponseCommitted() {
            writeHeaders();
            disableOnResponseCommitted();
        }

        void writeHeaders() {
            HttpServletResponse target = (HttpServletResponse) getResponse();
            synchronized (lock) {
                if (written || target.isCommitted()) {
                    written = true;
                    return;
                }
                written = true;
                setIfAbsent(target, "X-Content-Type-Options", "nosniff");
                setIfAbsent(target, "X-Frame-Options", "DENY");
                setIfAbsent(target, "Cache-Control", "no-store");
            }
        }

        private static void setIfAbsent(HttpServletResponse target, String name, String value) {
            if (!target.containsHeader(name)) {
                target.setHeader(name, value);
            }
        }
    }
}
