package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SessionCookieWriterTest {

    private static Cookie captureWrite(SessionCookieWriter writer) {
        HttpServletResponse response = mock(HttpServletResponse.class);
        writer.write(response, "raw-session-value");
        ArgumentCaptor<Cookie> captor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(captor.capture());
        return captor.getValue();
    }

    private static Cookie captureClear(SessionCookieWriter writer) {
        HttpServletResponse response = mock(HttpServletResponse.class);
        writer.clear(response);
        ArgumentCaptor<Cookie> captor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(captor.capture());
        return captor.getValue();
    }

    @Test
    void secureByDefaultCarriesTheSecureAttribute() {
        assertTrue(SessionCookieWriter.secureByDefault().isSecure());
        assertTrue(captureWrite(SessionCookieWriter.secureByDefault()).getSecure());
    }

    @Test
    void secureIsOmittedOnlyWhenTheDeploymentExplicitlyOptsOut() {
        Cookie cookie = captureWrite(new SessionCookieWriter(false));

        assertFalse(cookie.getSecure(),
                "a plain-HTTP deployment must be able to opt out, or the browser drops the cookie and no login completes");
    }

    @Test
    void httpOnlyAndSameSiteAreUnconditional() {
        for (boolean secure : new boolean[] {true, false}) {
            Cookie cookie = captureWrite(new SessionCookieWriter(secure));

            assertTrue(cookie.isHttpOnly(), "HttpOnly is never configurable");
            assertEquals("Strict", cookie.getAttribute("SameSite"), "SameSite is never configurable");
            assertEquals("/", cookie.getPath());
            assertEquals("ui2_session", cookie.getName());
        }
    }

    @Test
    void clearMatchesWriteAttributeForAttributeAndExpiresTheCookie() {
        for (boolean secure : new boolean[] {true, false}) {
            Cookie written = captureWrite(new SessionCookieWriter(secure));
            Cookie cleared = captureClear(new SessionCookieWriter(secure));

            assertEquals(written.getName(), cleared.getName());
            assertEquals(written.getPath(), cleared.getPath());
            assertEquals(written.getSecure(), cleared.getSecure());
            assertEquals(written.isHttpOnly(), cleared.isHttpOnly());
            assertEquals(written.getAttribute("SameSite"), cleared.getAttribute("SameSite"));
            assertEquals(0, cleared.getMaxAge(), "clearing must expire the cookie");
            assertEquals("", cleared.getValue());
        }
    }
}
