package com.securityexpert.nexus.ui2.worker.transport.xmlapi;

import com.securityexpert.nexus.ui2.platform.OverwatchEnv;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.LongConsumer;

import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;

/**
 * Bounded retry for the PAN {@code type=keygen} request. A firewall under load can time out or
 * answer without a usable key; the same request is repeated a few times with backoff. It never
 * refuses anything: a device that answers with an explicit rejection (for example bad
 * credentials) is returned at once, unchanged, and is never repeated. If every attempt fails,
 * the caller gets a {@link XmlApiResult.Failed} whose reason starts with a typed {@link Reason}.
 * The key and the request values are never part of any reason text.
 */
public final class PanKeygenRetry {

    /** Why a keygen attempt produced no usable key. */
    public enum Reason { TIMEOUT, TRANSPORT_ERROR, DEVICE_BUSY, EMPTY_KEY, MALFORMED_RESPONSE }

    /** Attempts (1 = no retry), first backoff, and a cap on the total time spent waiting. */
    public record Policy(int maxAttempts, Duration initialBackoff, Duration maxTotalWait) {
        public Policy {
            maxAttempts = Math.max(1, Math.min(maxAttempts, 10));
            initialBackoff = initialBackoff == null || initialBackoff.isNegative() ? Duration.ZERO : initialBackoff;
            maxTotalWait = maxTotalWait == null || maxTotalWait.isNegative() ? Duration.ZERO : maxTotalWait;
        }

        public static Policy defaults() {
            return new Policy(3, Duration.ofSeconds(2), Duration.ofSeconds(15));
        }

        /** Reads NEXUS_PAN_KEYGEN_ATTEMPTS / _BACKOFF_MS / _MAX_WAIT_MS; unset or invalid keeps the default. */
        public static Policy fromEnvironment() {
            Policy d = defaults();
            return new Policy(
                    (int) number("NEXUS_PAN_KEYGEN_ATTEMPTS", d.maxAttempts()),
                    Duration.ofMillis(number("NEXUS_PAN_KEYGEN_BACKOFF_MS", d.initialBackoff().toMillis())),
                    Duration.ofMillis(number("NEXUS_PAN_KEYGEN_MAX_WAIT_MS", d.maxTotalWait().toMillis())));
        }

        private static long number(String name, long fallback) {
            try {
                String v = OverwatchEnv.get(name);
                return v == null || v.isBlank() ? fallback : Long.parseLong(v.trim());
            } catch (RuntimeException e) {
                return fallback;
            }
        }
    }

    private PanKeygenRetry() {
    }

    public static boolean isKeygen(XmlApiSpec spec) {
        return spec != null && "keygen".equalsIgnoreCase(spec.type());
    }

    /** Runs {@code attempt} up to the policy limit; {@code sleeper} receives each wait in milliseconds. */
    public static XmlApiResult run(Policy policy, Function<XmlApiSpec, XmlApiResult> attempt, XmlApiSpec spec,
            LongConsumer sleeper) {
        long waited = 0;
        long backoff = policy.initialBackoff().toMillis();
        Reason last = null;
        XmlApiResult result;
        int n = 0;
        while (n < policy.maxAttempts()) {
            n++;
            result = attempt.apply(spec);
            last = classify(result);
            if (last == null) return result;
            if (result instanceof XmlApiResult.Failed f && !retryableFailure(f)) return result;
            if (n == policy.maxAttempts()) break;
            long wait = Math.min(backoff, policy.maxTotalWait().toMillis() - waited);
            if (wait < 0 || (backoff > 0 && wait == 0)) break;
            if (wait > 0) sleeper.accept(wait);
            waited += wait;
            backoff = backoff * 2;
        }
        return new XmlApiResult.Failed("pan keygen " + last + ": no usable key after "
                + n + " attempt(s)");
    }

    /** Null when the response carries a key or is a device rejection that must be returned as is. */
    static Reason classify(XmlApiResult result) {
        if (result instanceof XmlApiResult.Failed f) {
            String r = f.reason() == null ? "" : f.reason().toLowerCase(java.util.Locale.ROOT);
            return r.contains("timed out") || r.contains("timeout") ? Reason.TIMEOUT : Reason.TRANSPORT_ERROR;
        }
        XmlApiResult.Completed c = (XmlApiResult.Completed) result;
        String body = c.body() == null ? "" : c.body();
        if (c.httpStatus() >= 500 || c.httpStatus() == 429) return Reason.DEVICE_BUSY;
        if (c.httpStatus() != 200) return null; // explicit rejection: not ours to repeat
        if (body.isBlank()) return Reason.EMPTY_KEY;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?s)<key>([^<]*)</key>").matcher(body);
        if (m.find()) return m.group(1).isBlank() ? Reason.EMPTY_KEY : null;
        if (body.contains("status=\"error\"") || body.contains("status='error'")) return null;
        return Reason.MALFORMED_RESPONSE;
    }

    /** Only I/O style failures are retried; trust, URI and interruption failures are final. */
    private static boolean retryableFailure(XmlApiResult.Failed f) {
        String r = f.reason() == null ? "" : f.reason();
        return r.startsWith("xml api call did not complete");
    }
}
