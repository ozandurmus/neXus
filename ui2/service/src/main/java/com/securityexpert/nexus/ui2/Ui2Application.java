package com.securityexpert.nexus.ui2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.WebApplicationType;

@SpringBootApplication
public class Ui2Application {
    private static final Set<String> ROLES = Set.of("service", "worker", "scheduler", "migrate");

    public static void main(String[] args) throws IOException, InterruptedException {
        String role = args.length == 0 ? "service" : args[0];
        if (!ROLES.contains(role)) {
            throw new IllegalArgumentException("unsupported UI2 role: " + role);
        }

        requireReadableFile(environmentVariableFor(role));
        if (role.equals("migrate")) {
            MigrationRunner.migrate(readSecret(environmentVariableFor(role)));
            return;
        }

        SpringApplication application = new SpringApplication(Ui2Application.class);
        if (!role.equals("service")) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        application.setDefaultProperties(java.util.Map.of("ui2.role", role));
        application.run(args);
        if (!role.equals("service")) {
            new CountDownLatch(1).await();
        }
    }

    private static void requireReadableFile(String variable) throws IOException {
        String value = System.getenv(variable);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("required secret file is missing: " + variable);
        }
        Path path = Path.of(value);
        if (!Files.isRegularFile(path) || !Files.isReadable(path) || Files.size(path) == 0) {
            throw new IllegalStateException("required secret file is unreadable or empty: " + variable);
        }
    }

    private static String readSecret(String variable) throws IOException {
        return Files.readString(Path.of(System.getenv(variable))).strip();
    }

    private static String environmentVariableFor(String role) {
        return switch (role) {
            case "service" -> "SECURITYEXPERT_UI2_SERVICE_DB_DSN_FILE";
            case "worker" -> "SECURITYEXPERT_UI2_WORKER_DB_DSN_FILE";
            case "scheduler" -> "SECURITYEXPERT_UI2_SCHEDULER_DB_DSN_FILE";
            case "migrate" -> "SECURITYEXPERT_UI2_MIGRATE_DB_DSN_FILE";
            default -> throw new IllegalArgumentException("unsupported UI2 role: " + role);
        };
    }
}
