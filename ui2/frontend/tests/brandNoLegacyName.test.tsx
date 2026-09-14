/// <reference types="vite/client" />
import { describe, expect, it } from "vitest";

/**
 * The product's user-visible name is neXus and only neXus (AGENTS.md LOGO
 * decision, 2026-09-09; Product Owner directive 2026-09-14). This scans
 * every frontend source file plus index.html and fails if the legacy
 * "SecurityExpert" name still appears anywhere a user could see it.
 */
const LEGACY_NAME_PATTERN = /Security\s*Expert/i;

const srcModules = import.meta.glob("../src/**/*.{ts,tsx}", { query: "?raw", import: "default", eager: true }) as Record<
  string,
  string
>;
const indexHtmlModules = import.meta.glob("../index.html", { query: "?raw", import: "default", eager: true }) as Record<
  string,
  string
>;

describe("legacy product name removal", () => {
  it("does not appear anywhere under src", () => {
    const offenders = Object.entries(srcModules)
      .filter(([, contents]) => LEGACY_NAME_PATTERN.test(contents))
      .map(([path]) => path);
    expect(offenders).toEqual([]);
  });

  it("does not appear in index.html", () => {
    const [contents] = Object.values(indexHtmlModules);
    expect(LEGACY_NAME_PATTERN.test(contents)).toBe(false);
  });
});
