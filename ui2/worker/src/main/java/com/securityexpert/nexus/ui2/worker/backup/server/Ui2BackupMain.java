package com.securityexpert.nexus.ui2.worker.backup.server;

import java.io.IOException;

/**
 * Entry point for the {@code backup} role launched by {@code Ui2WorkerMain}.
 */
public final class Ui2BackupMain {

    private Ui2BackupMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = resolvePort();
        Ui2BackupServer server = new Ui2BackupServer(port);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down ui2-backup microservice...");
            server.stop();
        }));

        Thread.currentThread().join();
    }

    private static int resolvePort() {
        String env = System.getenv("NEXUS_BACKUP_PORT");
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return Ui2BackupServer.DEFAULT_PORT;
    }
}
