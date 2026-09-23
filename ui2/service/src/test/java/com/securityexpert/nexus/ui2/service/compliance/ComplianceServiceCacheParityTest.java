package com.securityexpert.nexus.ui2.service.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.service.compliance.ComplianceService.Target;

/**
 * Parity of the compliance aggregation before and after the 2026-09-23 caching change: the old behaviour (every
 * device evaluated one by one, nothing cached) and the new one (8 in parallel, cached by configuration hash and rule
 * set, no 10-minute expiry) produce identical Overview and Controls output on the same fixture; and the cache
 * re-evaluates exactly what changed.
 */
class ComplianceServiceCacheParityTest {

    private static final int DEVICES = 40;
    private static final String[] CONTROLS = {"C1", "C2", "C3", "C4", "C5", "C6"};

    private HttpServer server;
    private final AtomicInteger evaluations = new AtomicInteger();
    private final AtomicReference<String> catalogVersion = new AtomicReference<>("2026.09.1");
    private final Map<String, String> configs = new ConcurrentHashMap<>();
    private final Map<String, String> hashes = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-23T09:00:00Z"));

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/healthz", ex -> send(ex, "{\"status\":\"UP\",\"catalog_version\":\"" + catalogVersion.get()
                + "\",\"controls_count\":6,\"controls_by_vendor\":{\"check_point\":6}}"));
        server.createContext("/api/v1/compliance/catalog", ex -> {
            StringBuilder b = new StringBuilder("{\"controls\":[");
            for (int i = 0; i < CONTROLS.length; i++) {
                b.append(i == 0 ? "" : ",").append("{\"id\":\"").append(CONTROLS[i]).append("\",\"title\":\"t").append(i)
                        .append("\",\"description\":\"d\",\"severity\":\"").append(i == 0 ? "CRITICAL" : "HIGH")
                        .append("\",\"frameworks\":[{\"framework\":\"CIS\"}]}");
            }
            send(ex, b.append("]}").toString());
        });
        server.createContext("/api/v1/compliance/evaluate", ex -> {
            evaluations.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("UNEVALUABLE")) {
                ex.sendResponseHeaders(500, -1);
                ex.close();
                return;
            }
            int pass = 0;
            int fail = 0;
            int gap = 0;
            StringBuilder items = new StringBuilder();
            for (int i = 0; i < CONTROLS.length; i++) {
                String c = CONTROLS[i];
                String st = body.contains(c + "=on") ? "PASS" : body.contains(c + "=off") ? "FAIL" : body.contains(c + "=na")
                        ? "NOT_APPLICABLE" : "DATA_UNAVAILABLE";
                pass += st.equals("PASS") ? 1 : 0;
                fail += st.equals("FAIL") ? 1 : 0;
                gap += st.equals("DATA_UNAVAILABLE") ? 1 : 0;
                items.append(i == 0 ? "" : ",").append("{\"controlId\":\"").append(c).append("\",\"displayStatus\":\"").append(st)
                        .append("\",\"severity\":\"").append(i == 0 ? "CRITICAL" : "HIGH").append("\",\"message\":\"m-").append(c)
                        .append("\",\"frameworks\":[{\"framework\":\"").append(i % 2 == 0 ? "CIS" : "NIST_800_53").append("\"}]}");
            }
            double observed = pass + fail == 0 ? 0 : Math.round(pass * 1000.0 / (pass + fail)) / 10.0;
            send(ex, "{\"passCount\":" + pass + ",\"failCount\":" + fail + ",\"dataUnavailableCount\":" + gap
                    + ",\"observedCompliance\":" + observed + ",\"evidenceCoverage\":" + (100.0 - gap * 10) + ",\"assuredCompliance\":"
                    + Math.round(observed * (100.0 - gap * 10)) / 100.0 + ",\"items\":[" + items + "]}");
        });
        server.start();
        for (int d = 0; d < DEVICES; d++) {
            StringBuilder cfg = new StringBuilder();
            for (int i = 0; i < CONTROLS.length; i++) {
                int v = (d * 7 + i * 3) % 4;
                cfg.append(CONTROLS[i]).append(v == 0 ? "=on" : v == 1 ? "=off" : v == 2 ? "=na" : "=?").append('\n');
            }
            configs.put("dev-" + d, cfg.toString());
            hashes.put("dev-" + d, "h-" + d + "-1");
        }
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void send(HttpExchange ex, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private ComplianceService service(int parallelism) {
        return service(parallelism, ComplianceEvaluationStore.inMemory());
    }

    private ComplianceService service(int parallelism, ComplianceEvaluationStore store) {
        DeviceRepository repo = (DeviceRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DeviceRepository.class}, (p, m, a) -> m.getName().startsWith("find") ? Optional.empty() : null);
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new ComplianceService(null, repo, url, () -> {
            List<Target> out = new ArrayList<>();
            for (int d = 0; d < DEVICES; d++) {
                String id = "dev-" + d;
                out.add(new Target(id, Optional.of("FW-TANGO-" + d), d % 3 == 0 ? "palo_alto" : "check_point", Optional.of(hashes.get(id))));
            }
            out.add(new Target("dev-other", Optional.of("FW-OTHER-01"), "fortinet", Optional.of("h")));
            return out;
        }, id -> Optional.ofNullable(configs.get(id)), now::get, parallelism, Duration.ofHours(6), store);
    }

    @Test
    void newCachedParallelOutputEqualsTheOldSequentialUncachedOutput() {
        ComplianceService legacy = service(1);
        Map<String, Object> legacyOverview = legacy.getOverview();
        List<Map<String, Object>> legacyControls = legacy.getControls();

        ComplianceService current = service(ComplianceService.PARALLELISM);
        current.getControls(); // warm
        now.set(now.get().plus(Duration.ofMinutes(30))); // past the old 10-minute expiry
        evaluations.set(0);
        assertThat(current.getOverview()).isEqualTo(legacyOverview);
        assertThat(current.getControls()).isEqualTo(legacyControls);
        assertThat(evaluations.get()).as("unchanged configurations are not re-evaluated").isZero();
        assertThat(current.isEvaluationCacheWarm()).isTrue();
    }

    @Test
    void aChangedConfigurationIsShownFromTheStoredEvaluationUntilTheBackgroundCatchesUp() {
        ComplianceService current = service(ComplianceService.PARALLELISM);
        Map<String, Object> before = current.getOverview();
        configs.put("dev-5", "C1=on\nC2=on\nC3=on\nC4=on\nC5=on\nC6=on\n");
        hashes.put("dev-5", "h-5-2");
        evaluations.set(0);
        Map<String, Object> screen = current.getOverview();
        assertThat(evaluations.get()).as("a screen never waits for a re-evaluation").isZero();
        assertThat(screen.get("pending_reevaluation")).isEqualTo(1);
        assertThat(without(screen)).isEqualTo(without(before));
        assertThat(current.refreshInBackground()).isEqualTo(1);
        assertThat(evaluations.get()).isEqualTo(1);
        Map<String, Object> after = current.getOverview();
        assertThat(after.get("pending_reevaluation")).isEqualTo(0);
        assertThat(after).isEqualTo(service(1).getOverview());
    }

    @Test
    void aRestartStartsFromTheStoredEvaluationsWithoutReEvaluating() {
        ComplianceEvaluationStore store = ComplianceEvaluationStore.inMemory();
        Map<String, Object> first = service(ComplianceService.PARALLELISM, store).getOverview();
        evaluations.set(0);
        ComplianceService restarted = service(ComplianceService.PARALLELISM, store);
        assertThat(restarted.isEvaluationCacheWarm()).isTrue();
        assertThat(restarted.getOverview()).isEqualTo(first);
        assertThat(restarted.refreshInBackground()).isZero();
        assertThat(evaluations.get()).isZero();
    }

    @Test
    void aRuleSetChangeIsCaughtUpInTheBackground() {
        ComplianceService current = service(ComplianceService.PARALLELISM);
        current.getOverview();
        catalogVersion.set("2026.10.1");
        now.set(now.get().plus(Duration.ofMinutes(2))); // the fingerprint is re-read at most once a minute
        evaluations.set(0);
        assertThat(current.getOverview().get("pending_reevaluation")).isEqualTo(DEVICES);
        assertThat(evaluations.get()).isZero();
        assertThat(current.refreshInBackground()).isEqualTo(DEVICES);
        assertThat(evaluations.get()).isEqualTo(DEVICES);
    }

    @Test
    void theBackstopAgeReEvaluatesInTheBackgroundOnly() {
        ComplianceService current = service(ComplianceService.PARALLELISM);
        current.getOverview();
        now.set(now.get().plus(Duration.ofHours(7)));
        evaluations.set(0);
        assertThat(current.getOverview().get("pending_reevaluation")).isEqualTo(0); // same configuration, same rules: exact
        assertThat(evaluations.get()).isZero();
        assertThat(current.refreshInBackground()).isEqualTo(DEVICES);
    }

    @Test
    void aDeviceThatCannotBeEvaluatedIsNotRetriedOnEveryScreenOpen() {
        configs.put("dev-7", "UNEVALUABLE\n");
        ComplianceService current = service(ComplianceService.PARALLELISM);
        current.getOverview();
        evaluations.set(0);
        current.getOverview();
        current.getControls();
        assertThat(evaluations.get()).as("the failed device waits for the retry window").isZero();
        now.set(now.get().plus(ComplianceService.FAILURE_RETRY).plusSeconds(60));
        current.refreshInBackground();
        assertThat(evaluations.get()).isEqualTo(1);
    }

    private static Map<String, Object> without(Map<String, Object> overview) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(overview);
        m.remove("pending_reevaluation");
        return m;
    }
}
