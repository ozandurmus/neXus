package com.securityexpert.nexus.ui2.worker.compliance.server;

import com.securityexpert.nexus.ui2.platform.OverwatchEnv;

import java.io.IOException;

/**
 * Entry point for the {@code compliance} role launched by {@code Ui2WorkerMain}.
 */
public final class Ui2ComplianceMain {

    private Ui2ComplianceMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        com.securityexpert.nexus.ui2.platform.logging.MaskingFormatter.install();
        int port = resolvePort();
        Ui2ComplianceServer server = new Ui2ComplianceServer(port);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.getLogger(Ui2ComplianceMain.class.getName()).log(
                    System.Logger.Level.INFO, "Shutting down ui2-compliance microservice...");
            server.stop();
        }));

        Thread.currentThread().join();
    }

    private static int resolvePort() {
        String env = OverwatchEnv.get("NEXUS_COMPLIANCE_PORT");
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return Ui2ComplianceServer.DEFAULT_PORT;
    }
}
