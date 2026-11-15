package com.securityexpert.nexus.ui2.integration.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.service.boot.Ui2Application;

/**
 * Boots the real service application ({@link Ui2Application}, with its full component scan) against a
 * real PostgreSQL 16 whose schema Flyway has just built, and asks {@code /healthz}.
 *
 * <p>Why it exists: unit tests build their classes by hand and never ask Spring to wire the service, so a bean
 * Spring cannot construct (two public constructors and no {@code @Autowired}, a missing collaborator, a bad
 * {@code @Value}) passed every test and only failed when the pod started -- twice in a ship's preview on
 * 2026-11-15, 25 minutes each. This test makes that failure a test failure.</p>
 *
 * <p>Nothing leaves the machine: the database is the fixture's own, the HTTP server listens on an ephemeral
 * loopback port, no device is contacted at startup (the service's scheduling is switched off), and the
 * secret files are throwaway values in the fixture's private temporary directory.</p>
 */
class ServiceContextBootTest {

    @Test
    void theWholeServiceContextStartsAndAnswersHealthz() throws Exception {
        try (Ui2PostgresFixture database = Ui2PostgresFixture.createAndMigrate("ctxboot")) {
            Path workDir = Files.createTempDirectory("ui2-ctxboot-");
            String key = Base64.getEncoder().encodeToString(new byte[32]);
            Map<String, Object> properties = Map.ofEntries(
                    Map.entry("server.port", "0"),
                    Map.entry("server.address", "127.0.0.1"),
                    Map.entry("ui2.db.url", database.databaseJdbcUrl()),
                    Map.entry("ui2.db.user-file", database.appUserCredentialFile().toString()),
                    Map.entry("ui2.db.password-file", database.appPasswordCredentialFile().toString()),
                    Map.entry("ui2.db.migrate-user-file", database.migrateUserCredentialFile().toString()),
                    Map.entry("ui2.db.migrate-password-file", database.migratePasswordCredentialFile().toString()),
                    Map.entry("ui2.role-binding.group-reference-key-file",
                            database.extraSecretFile("role-binding-key", key).toString()),
                    Map.entry("ui2.credential-store.key-file",
                            database.extraSecretFile("credential-store-key", key).toString()),
                    Map.entry("ui2.failover.master-key-file", workDir.resolve("failover.key").toString()),
                    Map.entry("ui2.packet-analysis.root", workDir.resolve("pa").toString()),
                    Map.entry("ui2.sanitizer.work-dir", workDir.resolve("sanitizer").toString()),
                    Map.entry("ui2.scheduling.enabled", "false"),
                    Map.entry("spring.main.banner-mode", "off"));
            SpringApplication application = new SpringApplication(Ui2Application.class);
            application.setWebApplicationType(WebApplicationType.SERVLET);
            // Command-line arguments outrank application.yml (default properties would lose to its defaults).
            String[] arguments = properties.entrySet().stream()
                    .map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
            try (ConfigurableApplicationContext context = application.run(arguments)) {
                Environment environment = context.getEnvironment();
                String port = environment.getProperty("local.server.port");
                assertTrue(port != null && !port.isBlank(), "the embedded server reported no listening port");
                HttpResponse<String> response = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/healthz")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), "/healthz did not answer 200");
            }
        }
    }
}
