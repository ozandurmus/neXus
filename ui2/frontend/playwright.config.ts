import { defineConfig } from "@playwright/test";
import { baseURL, frontendRoot, statePath } from "./e2e/settings.mjs";

// Avoid automatic DOM dumps on a privacy failure. Traces also contain session cookies and API bodies.

// In-cluster the root filesystem is read-only: the runner points NEXUS_E2E_OUTPUT_DIR at a writable tmpfs subdirectory
// (Playwright removes and recreates outputDir, so it must not be a mount point).
const outputRoot = process.env.NEXUS_E2E_OUTPUT_DIR ?? frontendRoot;

export default defineConfig({
  testDir: "./e2e",
  testMatch: "**/*.spec.ts",
  globalSetup: "./e2e/global-setup.ts",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 30_000 },
  outputDir: `${outputRoot}/e2e-results`,
  reporter: process.env.NEXUS_E2E_MACHINE_TOKEN ? [["list"]] : [["list"], ["html", { outputFolder: `${outputRoot}/e2e-report`, open: "never" }]],
  use: {
    baseURL: baseURL(),
    storageState: statePath(),
    channel: process.env.NEXUS_E2E_MACHINE_TOKEN ? undefined : "chrome",
    serviceWorkers: "block",
    acceptDownloads: false,
    actionTimeout: 30_000,
    navigationTimeout: 45_000,
    screenshot: "off",
    trace: "off",
    video: "off",
  },
});
