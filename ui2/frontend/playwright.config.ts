import { defineConfig } from "@playwright/test";
import { baseURL, frontendRoot, statePath } from "./e2e/settings.mjs";

// Avoid automatic DOM dumps on a privacy failure. Traces also contain session cookies and API bodies.

export default defineConfig({
  testDir: "./e2e",
  testMatch: "**/*.spec.ts",
  globalSetup: "./e2e/global-setup.ts",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 30_000 },
  outputDir: `${frontendRoot}/e2e-results`,
  reporter: [["list"], ["html", { outputFolder: `${frontendRoot}/e2e-report`, open: "never" }]],
  use: {
    baseURL: baseURL(),
    storageState: statePath(),
    channel: "chrome",
    serviceWorkers: "block",
    acceptDownloads: false,
    actionTimeout: 30_000,
    navigationTimeout: 45_000,
    screenshot: "off",
    trace: "off",
    video: "off",
  },
});
