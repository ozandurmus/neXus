import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// UI 2.0 frontend: build-time only (contract DIR-8). This config emits
// static assets consumed by the `service` module; it never runs as a
// long-lived Node process in production.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "dist",
    emptyOutDir: true,
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./tests/setup.ts"],
  },
});
