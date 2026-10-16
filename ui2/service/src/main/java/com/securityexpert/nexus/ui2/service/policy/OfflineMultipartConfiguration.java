package com.securityexpert.nexus.ui2.service.policy;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.*;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

@Configuration
public class OfflineMultipartConfiguration {
    @Bean(name = "multipartResolver")
    public StandardServletMultipartResolver multipartResolver() {
        return new StandardServletMultipartResolver() {
            @Override public boolean isMultipart(HttpServletRequest request) {
                // Spring's default resolver may spool before authentication. This route streams after the gates.
                String path = request.getRequestURI().substring(request.getContextPath().length());
                if (path.matches("/api/v2/policy/policies/[^/]+/offline-analyses")) return false;
                // Packet Analysis captures (up to 2 GB) are streamed to the analysis volume after the gates.
                if (path.matches("/api/v2/packet-analysis/cases/[^/]+/captures")) return false;
                // Host telemetry text files (up to 200 MB) take the same path.
                if (path.matches("/api/v2/packet-analysis/cases/[^/]+/telemetry")) return false;
                // Data Sanitizer uploads (up to 2 GB) are streamed to the sanitizer work area after the gates.
                if (path.equals("/api/v2/sanitize") || path.equals("/api/v2/sanitize/revert")) return false;
                return super.isMultipart(request);
            }
        };
    }
}
