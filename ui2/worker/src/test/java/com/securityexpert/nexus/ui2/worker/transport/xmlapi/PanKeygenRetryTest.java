package com.securityexpert.nexus.ui2.worker.transport.xmlapi;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.transport.XmlApiResult;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;

class PanKeygenRetryTest {
    private final PanKeygenRetry.Policy policy =
            new PanKeygenRetry.Policy(3, Duration.ofMillis(100), Duration.ofMillis(1000));
    private final XmlApiSpec spec = new XmlApiSpec("POST", "keygen", "", "", Map.of("user", "u"), Map.of());

    private static XmlApiResult busy() {
        return new XmlApiResult.Failed("xml api call did not complete: request timed out");
    }

    private static XmlApiResult ok(String key) {
        return new XmlApiResult.Completed(200, "<response status=\"success\"><result><key>" + key + "</key></result></response>");
    }

    private XmlApiResult run(List<Long> waits, AtomicInteger calls, Function<Integer, XmlApiResult> responder) {
        return PanKeygenRetry.run(policy, s -> responder.apply(calls.incrementAndGet()), spec, waits::add);
    }

    @Test void busyThenOkReturnsTheKey() {
        String key = "k".repeat(8) + System.nanoTime();
        List<Long> waits = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        XmlApiResult r = run(waits, calls, n -> n < 3 ? busy() : ok(key));
        assertEquals(3, calls.get());
        assertEquals(List.of(100L, 200L), waits);
        assertInstanceOf(XmlApiResult.Completed.class, r);
        assertTrue(((XmlApiResult.Completed) r).body().contains(key));
    }

    @Test void emptyKeyThenOkRetries() {
        AtomicInteger calls = new AtomicInteger();
        XmlApiResult r = run(new ArrayList<>(), calls,
                n -> n == 1 ? new XmlApiResult.Completed(200, "<response><result><key></key></result></response>") : ok("abc"));
        assertEquals(2, calls.get());
        assertInstanceOf(XmlApiResult.Completed.class, r);
    }

    @Test void alwaysBusyFailsWithTypedReasonAndNoKey() {
        AtomicInteger calls = new AtomicInteger();
        XmlApiResult r = run(new ArrayList<>(), calls, n -> busy());
        assertEquals(3, calls.get());
        String reason = ((XmlApiResult.Failed) r).reason();
        assertTrue(reason.startsWith("pan keygen TIMEOUT"), reason);
        assertTrue(reason.contains("3 attempt"));
    }

    @Test void deviceBusyStatusIsTyped() {
        XmlApiResult r = run(new ArrayList<>(), new AtomicInteger(), n -> new XmlApiResult.Completed(503, "busy"));
        assertTrue(((XmlApiResult.Failed) r).reason().startsWith("pan keygen DEVICE_BUSY"));
    }

    @Test void malformedResponseIsTypedAndBounded() {
        AtomicInteger calls = new AtomicInteger();
        XmlApiResult r = run(new ArrayList<>(), calls, n -> new XmlApiResult.Completed(200, "not xml at all"));
        assertEquals(3, calls.get());
        assertTrue(((XmlApiResult.Failed) r).reason().startsWith("pan keygen MALFORMED_RESPONSE"));
    }

    @Test void explicitRejectionIsNeverRepeated() {
        AtomicInteger calls = new AtomicInteger();
        XmlApiResult rejected = new XmlApiResult.Completed(403, "<response status=\"error\"><msg>Invalid credentials</msg></response>");
        XmlApiResult r = run(new ArrayList<>(), calls, n -> rejected);
        assertEquals(1, calls.get());
        assertSame(rejected, r);
        calls.set(0);
        XmlApiResult err200 = new XmlApiResult.Completed(200, "<response status=\"error\" code=\"403\"/>");
        assertSame(err200, run(new ArrayList<>(), calls, n -> err200));
        assertEquals(1, calls.get());
    }

    @Test void nonTransientFailureIsNotRepeated() {
        AtomicInteger calls = new AtomicInteger();
        run(new ArrayList<>(), calls, n -> new XmlApiResult.Failed("trust rule could not be resolved into a usable TLS configuration"));
        assertEquals(1, calls.get());
    }

    @Test void totalWaitIsCapped() {
        PanKeygenRetry.Policy tight = new PanKeygenRetry.Policy(10, Duration.ofMillis(100), Duration.ofMillis(250));
        List<Long> waits = new ArrayList<>();
        PanKeygenRetry.run(tight, s -> busy(), spec, waits::add);
        assertTrue(waits.stream().mapToLong(Long::longValue).sum() <= 250, waits.toString());
    }

    @Test void failureReasonNeverCarriesTheKey() {
        String key = "secret" + System.nanoTime();
        XmlApiResult r = run(new ArrayList<>(), new AtomicInteger(),
                n -> new XmlApiResult.Completed(200, "<key>" + key + "</key><junk"));
        assertInstanceOf(XmlApiResult.Completed.class, r); // a key is present: returned untouched
        XmlApiResult f = run(new ArrayList<>(), new AtomicInteger(), n -> new XmlApiResult.Completed(200, "garbage " + key));
        assertFalse(((XmlApiResult.Failed) f).reason().contains(key));
    }
}
