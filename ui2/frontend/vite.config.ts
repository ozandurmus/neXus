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
    rollupOptions: {
      output: {
        // Heavy vendor libraries get their own long-lived chunks so a screen change does not invalidate them.
        manualChunks(id: string) {
          if (!id.includes("node_modules")) return undefined;
          if (id.includes("/echarts/") || id.includes("/zrender/")) return "vendor-echarts";
          if (id.includes("/@xyflow/") || id.includes("/d3-")) return "vendor-xyflow";
          if (id.includes("/@mui/") || id.includes("/@emotion/")) return "vendor-mui";
          if (id.includes("/react/") || id.includes("/react-dom/") || id.includes("/scheduler/")) return "vendor-react";
          return undefined;
        },
      },
    },
  },
  test: {
    include: ["tests/**/*.test.{ts,tsx}"],
    environment: "jsdom",
    globals: true,
    setupFiles: ["./tests/setup.ts"],
    testTimeout: 20000,
  },
});
