package com.securityexpert.nexus.ui2.worker.configuration.server;

import java.io.IOException;

/**
 * Entry point for the {@code configuration} role launched by {@code Ui2WorkerMain}.
 */
public final class Ui2ConfigurationMain {

    private Ui2ConfigurationMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = resolvePort();
        Ui2ConfigurationServer server = new Ui2ConfigurationServer(port);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down ui2-configuration microservice...");
            server.stop();
        }));

        Thread.currentThread().join();
    }

    private static int resolvePort() {
        String env = System.getenv("NEXUS_CONFIG_PORT");
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return Ui2ConfigurationServer.DEFAULT_PORT;
    }
}
