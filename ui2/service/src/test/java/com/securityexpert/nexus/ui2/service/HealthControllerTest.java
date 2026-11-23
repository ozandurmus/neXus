package com.securityexpert.nexus.ui2.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ResponseEntity;

class HealthControllerTest {

    private static HealthController controller(DataSource source) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (source != null) beans.addBean("dataSource", source);
        return new HealthController(beans.getBeanProvider(DataSource.class));
    }

    private static Connection connection(boolean valid) {
        return (Connection) Proxy.newProxyInstance(HealthControllerTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isValid" -> valid;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static DataSource source(java.util.concurrent.Callable<Connection> open) {
        return (DataSource) Proxy.newProxyInstance(HealthControllerTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) return open.call();
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void healthzAndLivezNeverTouchTheDatabase() {
        HealthController health = controller(source(() -> { throw new AssertionError("database touched"); }));
        assertEquals("ok", health.health());
        assertEquals("ok", health.live());
    }

    @Test
    void readyzIsOkWhenTheDatabaseAnswers() {
        ResponseEntity<String> answer = controller(source(() -> connection(true))).ready();
        assertEquals(200, answer.getStatusCode().value());
        assertEquals("ok", answer.getBody());
    }

    @Test
    void readyzIs503WhenTheDatabaseRefusesOrIsInvalid() {
        ResponseEntity<String> refused = controller(source(() -> { throw new SQLException("refused"); })).ready();
        assertEquals(503, refused.getStatusCode().value());
        assertEquals("db_unreachable", refused.getBody());
        assertEquals(503, controller(source(() -> connection(false))).ready().getStatusCode().value());
        assertEquals(503, controller(null).ready().getStatusCode().value());
    }

    @Test
    void readyzAnswersWithinTheTimeoutAndNeverStacksChecksOnASlowDatabase() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger opened = new AtomicInteger();
        HealthController health = controller(source(() -> {
            opened.incrementAndGet();
            release.await();
            return connection(true);
        }));
        long started = System.nanoTime();
        ResponseEntity<String> first = health.ready();
        ResponseEntity<String> second = health.ready();
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertEquals("db_timeout", first.getBody());
        assertEquals(503, second.getStatusCode().value());
        assertTrue(elapsedMillis < 2 * HealthController.DB_CHECK_TIMEOUT.toMillis() + 1_000, "took " + elapsedMillis);
        assertEquals(1, opened.get(), "one check in flight at a time");
        release.countDown();
        for (int i = 0; i < 50 && !"ok".equals(health.ready().getBody()); i++) Thread.sleep(50);
        assertEquals("ok", health.ready().getBody());
    }
}
