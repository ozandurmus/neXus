import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { PRODUCT_NAME } from "./src/brand/product";

// UI 2.0 frontend: build-time only (contract DIR-8). This config emits
// static assets consumed by the `service` module; it never runs as a
// long-lived Node process in production.
export default defineConfig({
  plugins: [
    react(),
    {
      name: "product-title",
      transformIndexHtml(html) {
        return html.replace(/<title>.*?<\/title>/, `<title>${PRODUCT_NAME}</title>`);
      },
    },
  ],
  cacheDir: ".cache/vite",
  build: {
    outDir: "dist",
    emptyOutDir: true,
  },
  test: {
    include: ["tests/**/*.test.{ts,tsx}"],
    environment: "jsdom",
    globals: true,
    setupFiles: ["./tests/setup.ts"],
  },
});
