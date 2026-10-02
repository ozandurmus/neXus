import { defineConfig } from "@playwright/test";

// Standalone, synthetic AIView browser checks. No deployment/session credentials are used.
export default defineConfig({
  testDir: "./browser-tests", testMatch: "policy.spec.ts", workers: 1, retries: 0,
  reporter: "list", outputDir: ".cache/policy-e2e-results",
  use: { baseURL: "http://127.0.0.1:4173", channel: "chrome", viewport: { width: 1600, height: 1000 },
    acceptDownloads: true, screenshot: "off", trace: "off", video: "off" },
  webServer: {
    command: "node_modules/.bin/vite preview --host 127.0.0.1 --port 4173 --strictPort",
    url: "http://127.0.0.1:4173", reuseExistingServer: false,
  },
});
