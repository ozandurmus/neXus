package com.securityexpert.nexus.ui2.service.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single owner of the {@code ui2_session} cookie's attributes, so the session
 * cookie is written and cleared identically by every endpoint that touches it
 * ({@code POST /login}, {@code POST /login/resolve}, {@code POST /session/logout}).
 *
 * <p>{@code HttpOnly} and {@code SameSite=Strict} are unconditional. Only the
 * {@code Secure} attribute is configurable, and it defaults to {@code true}: a
 * deployment is secure unless it explicitly opts out.
 *
 * <p>The opt-out exists because a browser refuses to store a {@code Secure}
 * cookie received from a plain-HTTP origin. On the TLS-less development
 * deployment that made every login succeed server-side while the client stayed
 * unauthenticated — the next attempt then collided with the session the previous
 * one had left behind, so the operator could never reach the UI at all. Setting
 * {@code UI2_SESSION_COOKIE_SECURE=false} there restores a usable login without
 * changing any TLS deployment, which keeps the default.
 */
@Component
public final class SessionCookieWriter {

    static final String SESSION_COOKIE_NAME = "ui2_session";

    private final boolean secure;

    public SessionCookieWriter(@Value("${ui2.session.cookie.secure:true}") boolean secure) {
        this.secure = secure;
    }

    /** A writer with the secure-by-default posture, for call sites that carry no configuration. */
    public static SessionCookieWriter secureByDefault() {
        return new SessionCookieWriter(true);
    }

    public boolean isSecure() {
        return secure;
    }

    /** Writes the session cookie carrying {@code rawCookieValue}. */
    public void write(HttpServletResponse response, String rawCookieValue) {
        response.addCookie(cookie(rawCookieValue, -1));
    }

    /** Expires the session cookie, matching {@link #write} attribute for attribute. */
    public void clear(HttpServletResponse response) {
        response.addCookie(cookie("", 0));
    }

    private Cookie cookie(String value, int maxAge) {
        Cookie cookie = new Cookie(SESSION_COOKIE_NAME, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setPath("/");
        cookie.setAttribute("SameSite", "Strict");
        if (maxAge >= 0) {
            cookie.setMaxAge(maxAge);
        }
        return cookie;
    }
}
