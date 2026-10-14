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
                if (request.getRequestURI().substring(request.getContextPath().length())
                        .matches("/api/v2/policy/policies/[^/]+/offline-analyses")) return false;
                return super.isMultipart(request);
            }
        };
    }
}
