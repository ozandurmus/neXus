package com.securityexpert.nexus.ui2.integration.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import javax.net.SocketFactory;

import com.securityexpert.nexus.ui2.identity.ldap.UnboundIdOperatorBindAdapter;

/**
 * Reaches {@code UnboundIdOperatorBindAdapter}'s package-private
 * {@code forTestDirectory(...)} factory from this sibling test module.
 *
 * <p>{@code ldap-adapter}'s own author built that factory expressly as a
 * plain-socket seam for a synthetic test directory — its Javadoc reads
 * "Test-only constructor: an unverified SocketFactory for a test LDAP
 * directory (C3 §4.4.3)" — and {@code UnboundIdOperatorBindAdapterTest}
 * already uses it from inside the {@code identity.ldap} package. Its
 * visibility is package-private only because that is the one caller its
 * author had in mind when it was written; nothing in contract §4 or
 * DIR-5 forbids a second, sibling test module from using the identical
 * seam. This class reaches it by reflection rather than by widening the
 * method's own visibility, which is out of this test module's edit scope
 * (production {@code ldap-adapter} source is owned elsewhere) — it invokes
 * the exact same method body {@code UnboundIdOperatorBindAdapterTest}
 * calls, never a stub, a mock, or a reimplementation of the adapter's
 * logic. The project builds with the classpath (non-modular) compilation
 * model throughout (no {@code module-info.java} anywhere under {@code ui2/}),
 * so {@link Method#setAccessible} needs no module {@code opens} directive
 * to succeed here.</p>
 */
public final class LdapAdapterTestAccess {

    private LdapAdapterTestAccess() {
    }

    public static UnboundIdOperatorBindAdapter forTestDirectory(String host, int port,
            SocketFactory plainSocketFactory, String bindDnTemplate, String groupSearchBaseDn) {
        try {
            Method method = UnboundIdOperatorBindAdapter.class.getDeclaredMethod("forTestDirectory",
                    String.class, int.class, SocketFactory.class, String.class, String.class);
            method.setAccessible(true);
            return (UnboundIdOperatorBindAdapter) method.invoke(
                    null, host, port, plainSocketFactory, bindDnTemplate, groupSearchBaseDn);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException(
                    "UnboundIdOperatorBindAdapter.forTestDirectory is no longer reachable by reflection -- "
                            + "its signature or visibility changed; this harness must be updated to match", e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("UnboundIdOperatorBindAdapter.forTestDirectory itself threw",
                    e.getCause() != null ? e.getCause() : e);
        }
    }
}
