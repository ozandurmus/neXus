package com.securityexpert.nexus.ui2.service.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The console entry page ({@code /} and {@code /index.html}) names the
 * content-hashed bundle of the current build. Served with only a
 * Last-Modified header, a browser cached it heuristically and kept loading
 * the previous build's bundle after a deploy (2026-09-22: a blank console
 * until a hard refresh). It is revalidated on every load; the hashed
 * {@code /assets/*} files stay immutably cached.
 */
@Component
public class IndexHtmlNoCacheFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if ("/".equals(path) || "/index.html".equals(path)) {
            response.setHeader("Cache-Control", "no-cache, must-revalidate");
        }
        chain.doFilter(request, response);
    }
}
