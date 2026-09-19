package com.securityexpert.nexus.ui2.worker.compliance.server;

import java.io.IOException;

/**
 * Entry point for the {@code compliance} role launched by {@code Ui2WorkerMain}.
 */
public final class Ui2ComplianceMain {

    private Ui2ComplianceMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = resolvePort();
        Ui2ComplianceServer server = new Ui2ComplianceServer(port);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down ui2-compliance microservice...");
            server.stop();
        }));

        Thread.currentThread().join();
    }

    private static int resolvePort() {
        String env = System.getenv("NEXUS_COMPLIANCE_PORT");
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return Ui2ComplianceServer.DEFAULT_PORT;
    }
}
