package com.securityexpert.nexus.ui2.platform.logging;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thread-local correlation and job MDC context for all roles ({@link JsonLogFormatter} and SLF4J {@code MDC}).
 *
 * <p>Every write updates both this thread-local map (read directly by {@link JsonLogFormatter} for
 * {@link System.Logger} and {@code java.util.logging} output) and {@code org.slf4j.MDC} when SLF4J is on the classpath,
 * and {@link #mdcSnapshot()} merges any keys placed directly into {@code org.slf4j.MDC} so both logging paths carry
 * identical top-level MDC fields.</p>
 */
public final class JobLogContext {

    private static final ThreadLocal<Map<String, String>> CONTEXT = ThreadLocal.withInitial(LinkedHashMap::new);

    private JobLogContext() {
    }

    /**
     * Sets {@code correlation_id = job:<jobId>} and {@code job_id = <jobId>} for this thread; close the returned scope
     * to restore the previous MDC state.
     */
    public static Scope open(String jobId) {
        return open(jobId, null, null);
    }

    /**
     * Sets {@code correlation_id = job:<jobId>}, {@code job_id = <jobId>} and {@code job_kind = <jobKind>} for this
     * thread; close the returned scope to restore the previous MDC state.
     */
    public static Scope open(String jobId, String jobKind) {
        return open(jobId, jobKind, null);
    }

    /**
     * Sets {@code correlation_id = job:<jobId>}, {@code job_id = <jobId>}, {@code job_kind = <jobKind>} and (when
     * non-blank) {@code request_correlation_id} for this thread; close the returned scope to restore the previous
     * MDC state.
     */
    public static Scope open(String jobId, String jobKind, String requestCorrelationId) {
        String prevCorrelation = get(JsonLogFields.CORRELATION_ID);
        String prevJobId = get(JsonLogFields.JOB_ID);
        String prevJobKind = get(JsonLogFields.JOB_KIND);
        String prevRequestCorrelation = get(JsonLogFields.REQUEST_CORRELATION_ID);
        boolean sameJob = jobId != null && jobId.equals(prevJobId);

        if (jobId == null) {
            remove(JsonLogFields.CORRELATION_ID);
            remove(JsonLogFields.JOB_ID);
            remove(JsonLogFields.JOB_KIND);
            remove(JsonLogFields.REQUEST_CORRELATION_ID);
        } else {
            put(JsonLogFields.CORRELATION_ID, JsonLogFields.jobCorrelationId(jobId));
            put(JsonLogFields.JOB_ID, jobId);
            String effectiveKind = (sameJob && prevJobKind != null && !prevJobKind.isBlank())
                    ? prevJobKind
                    : ((jobKind != null && !jobKind.isBlank()) ? jobKind : null);
            if (effectiveKind != null && !effectiveKind.isBlank()) {
                put(JsonLogFields.JOB_KIND, effectiveKind);
            } else {
                remove(JsonLogFields.JOB_KIND);
            }
            String effectiveReqCorr = (requestCorrelationId != null && !requestCorrelationId.isBlank())
                    ? requestCorrelationId
                    : (sameJob ? prevRequestCorrelation : null);
            if (effectiveReqCorr != null && !effectiveReqCorr.isBlank()) {
                put(JsonLogFields.REQUEST_CORRELATION_ID, effectiveReqCorr);
            } else {
                remove(JsonLogFields.REQUEST_CORRELATION_ID);
            }
        }
        return () -> {
            restoreKey(JsonLogFields.CORRELATION_ID, prevCorrelation);
            restoreKey(JsonLogFields.JOB_ID, prevJobId);
            restoreKey(JsonLogFields.JOB_KIND, prevJobKind);
            restoreKey(JsonLogFields.REQUEST_CORRELATION_ID, prevRequestCorrelation);
        };
    }

    /** Sets {@code correlation_id} for an HTTP request; close the returned scope to restore the previous value. */
    public static Scope openRequest(String correlationId) {
        String previous = get(JsonLogFields.CORRELATION_ID);
        restoreKey(JsonLogFields.CORRELATION_ID, correlationId);
        return () -> restoreKey(JsonLogFields.CORRELATION_ID, previous);
    }

    /** Updates {@code job_kind} inside an active job scope. */
    public static void setJobKind(String jobKind) {
        if (jobKind == null || jobKind.isBlank()) {
            remove(JsonLogFields.JOB_KIND);
        } else {
            put(JsonLogFields.JOB_KIND, jobKind);
        }
    }

    /** Updates {@code request_correlation_id} inside an active job scope. */
    public static void setRequestCorrelationId(String requestCorrelationId) {
        if (requestCorrelationId == null || requestCorrelationId.isBlank()) {
            remove(JsonLogFields.REQUEST_CORRELATION_ID);
        } else {
            put(JsonLogFields.REQUEST_CORRELATION_ID, requestCorrelationId);
        }
    }

    public static void put(String key, String value) {
        if (value == null) {
            remove(key);
            return;
        }
        CONTEXT.get().put(key, value);
        Slf4jMdcBridge.put(key, value);
    }

    public static void remove(String key) {
        Map<String, String> map = CONTEXT.get();
        map.remove(key);
        if (map.isEmpty()) {
            CONTEXT.remove();
        }
        Slf4jMdcBridge.remove(key);
    }

    public static String get(String key) {
        Map<String, String> local = CONTEXT.get();
        String val = local.get(key);
        if (val != null) {
            return val;
        }
        if (local.isEmpty()) {
            CONTEXT.remove();
        }
        return Slf4jMdcBridge.get(key);
    }

    public static String current() {
        return get(JsonLogFields.JOB_ID);
    }

    /** Snapshot of all MDC entries on the current thread (both {@code org.slf4j.MDC} and {@link JobLogContext}). */
    public static Map<String, String> mdcSnapshot() {
        Map<String, String> slf4j = Slf4jMdcBridge.getCopyOfContextMap();
        Map<String, String> local = CONTEXT.get();
        if ((slf4j == null || slf4j.isEmpty()) && local.isEmpty()) {
            CONTEXT.remove();
            return Map.of();
        }
        Map<String, String> merged = new LinkedHashMap<>();
        if (slf4j != null) {
            merged.putAll(slf4j);
        }
        merged.putAll(local);
        return Collections.unmodifiableMap(merged);
    }

    private static void restoreKey(String key, String value) {
        if (value == null) {
            remove(key);
        } else {
            put(key, value);
        }
    }

    /** Closing restores the previous MDC values; no checked exception. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Reflective bridge to {@code org.slf4j.MDC} so {@code platform-core} keeps zero external library dependencies
     * while keeping {@code org.slf4j.MDC} synchronized in {@code :service}, {@code :worker}, and tests. When SLF4J's
     * active adapter is {@code NOPMDCAdapter} (no SLF4J provider bound), installs {@code BasicMDCAdapter} so
     * {@code org.slf4j.MDC.get(...)} still observes every key.
     */
    private static final class Slf4jMdcBridge {

        private static final Method PUT;
        private static final Method GET;
        private static final Method REMOVE;
        private static final Method GET_COPY;

        static {
            Method put = null;
            Method get = null;
            Method remove = null;
            Method getCopy = null;
            try {
                Class<?> mdc = Class.forName("org.slf4j.MDC");
                put = mdc.getMethod("put", String.class, String.class);
                get = mdc.getMethod("get", String.class);
                remove = mdc.getMethod("remove", String.class);
                getCopy = mdc.getMethod("getCopyOfContextMap");
                ensureWorkingAdapter(mdc);
            } catch ( Throwable ignored) {
                // org.slf4j.MDC absent on pure platform-core classpath
            }
            PUT = put;
            GET = get;
            REMOVE = remove;
            GET_COPY = getCopy;
        }

        private static void ensureWorkingAdapter(Class<?> mdc) {
            try {
                Method getAdapter = mdc.getMethod("getMDCAdapter");
                Object adapter = getAdapter.invoke(null);
                if (adapter == null) {
                    // Trigger lazy initialization in SLF4J 2.x
                    mdc.getMethod("get", String.class).invoke(null, "_init");
                    adapter = getAdapter.invoke(null);
                }
                if (adapter == null || adapter.getClass().getName().endsWith("NOPMDCAdapter")) {
                    Class<?> basicClass = Class.forName("org.slf4j.helpers.BasicMDCAdapter");
                    Class<?> adapterInterface = Class.forName("org.slf4j.spi.MDCAdapter");
                    Object basic = basicClass.getConstructor().newInstance();
                    Method setAdapter = mdc.getDeclaredMethod("setMDCAdapter", adapterInterface);
                    setAdapter.setAccessible(true);
                    setAdapter.invoke(null, basic);
                }
            } catch (Throwable ignored) {
                // Keep default adapter if setMDCAdapter is restricted
            }
        }

        static void put(String key, String value) {
            if (PUT != null) {
                try {
                    PUT.invoke(null, key, value);
                } catch (Throwable ignored) {
                }
            }
        }

        static String get(String key) {
            if (GET != null) {
                try {
                    return (String) GET.invoke(null, key);
                } catch (Throwable ignored) {
                }
            }
            return null;
        }

        static void remove(String key) {
            if (REMOVE != null) {
                try {
                    REMOVE.invoke(null, key);
                } catch (Throwable ignored) {
                }
            }
        }

        @SuppressWarnings("unchecked")
        static Map<String, String> getCopyOfContextMap() {
            if (GET_COPY != null) {
                try {
                    return (Map<String, String>) GET_COPY.invoke(null);
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }
}
