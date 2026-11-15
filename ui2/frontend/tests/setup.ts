import "@testing-library/jest-dom/vitest";

if (typeof globalThis.ResizeObserver === "undefined") {
  globalThis.ResizeObserver = class ResizeObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
}

// Screens are lazy chunks (route-level code splitting): under vitest the first import of a screen transforms its whole
// module graph, which can exceed the 1 s default of findBy*/waitFor on a busy machine.
// Stay below testTimeout (vite.config.ts) so a missing element fails with its own message, not a bare test timeout.
import { configure } from "@testing-library/react";
configure({ asyncUtilTimeout: 15000 });
