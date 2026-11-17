package com.securityexpert.nexus.ui2.service.logging;

import com.securityexpert.nexus.ui2.platform.logging.JobLogContext;
import com.securityexpert.nexus.ui2.platform.logging.JsonLogFields;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-request correlation id filter (enterprise phase 0.3).
 *
 * <p>Accepts an inbound {@code X-Request-Id} only when it matches {@code ^[A-Za-z0-9._-]{8,64}$}; otherwise generates
 * a UUID. Stores the resolved value in the MDC key {@code correlation_id} for the lifetime of the request (cleared in
 * {@code finally}) and echoes it in the {@code X-Request-Id} response header. Ordered ahead of the Spring Security
 * filter chain so denied and unauthenticated requests are correlated too. Never logs request bodies, cookies, tokens
 * or addresses.</p>
 */
@Component
public final class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

    public static final String HEADER_NAME = JsonLogFields.REQUEST_ID_HEADER;
    public static final String MDC_KEY = JsonLogFields.CORRELATION_ID;
    private static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".CORRELATION_ID";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

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
        String correlationId = resolveCorrelationId(request);
        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        try (var ignored = JobLogContext.openRequest(correlationId)) {
            chain.doFilter(request, response);
        } finally {
            if (!response.isCommitted() && !response.containsHeader(HEADER_NAME)) {
                response.setHeader(HEADER_NAME, correlationId);
            }
        }
    }

    private static String resolveCorrelationId(HttpServletRequest request) {
        DispatcherType type = request.getDispatcherType();
        if (type == DispatcherType.ASYNC || type == DispatcherType.ERROR) {
            Object existing = request.getAttribute(ATTRIBUTE);
            if (existing instanceof String id && JsonLogFields.isValidRequestId(id)) {
                return id;
            }
        }
        return JsonLogFields.resolveRequestId(request.getHeader(HEADER_NAME));
    }
}
