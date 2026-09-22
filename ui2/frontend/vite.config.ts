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
    rollupOptions: {
      output: {
        // React and MUI change only when a dependency is bumped; keep them in their own
        // long-cached chunks so a product build re-downloads only the product's code.
        manualChunks: {
          react: ["react", "react-dom"],
          mui: ["@mui/material", "@emotion/react", "@emotion/styled"],
        },
      },
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./tests/setup.ts"],
  },
});
