import { defineConfig } from "@playwright/test";

// Standalone, synthetic browser checks for the VSX chassis failover UI. No deployment/session credentials are used.
export default defineConfig({
  testDir: "./e2e", testMatch: "chassis-failover.spec.ts", workers: 1, retries: 0,
  reporter: "list", outputDir: ".cache/chassis-e2e-results",
  use: { baseURL: "http://127.0.0.1:4174", channel: "chrome", viewport: { width: 1550, height: 900 },
    serviceWorkers: "block", screenshot: "off", trace: "off", video: "off" },
  webServer: {
    command: "node_modules/.bin/vite preview --host 127.0.0.1 --port 4174 --strictPort",
    url: "http://127.0.0.1:4174", reuseExistingServer: false,
  },
});
