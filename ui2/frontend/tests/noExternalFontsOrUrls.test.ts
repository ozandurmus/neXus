// @vitest-environment node
/// <reference types="vite/client" />
import { describe, expect, it } from "vitest";
import { build } from "vite";
import { existsSync, readFileSync } from "node:fs";

const EXTERNAL_URL_RE = /https?:\/\//i;
const GOOGLE_FONT_HOST_RE = /fonts\.(?:googleapis|gstatic)\.com/i;
const BUNDLED_ASSET_RE = /\.(?:css|less|sass|scss|styl|woff2?|ttf|otf|eot)(?:$|\?)/i;

const indexHtmlModules = import.meta.glob("../index.html", { query: "?raw", import: "default", eager: true }) as Record<
  string,
  string
>;
const cssModules = import.meta.glob("../src/**/*.css", { query: "?raw", import: "default", eager: true }) as Record<
  string,
  string
>;
const licenseModules = import.meta.glob("../src/assets/fonts/OFL-1.1.txt", {
  query: "?raw",
  import: "default",
  eager: true,
}) as Record<string, string>;

interface EmittedAsset {
  readonly type: "asset";
  readonly fileName: string;
  readonly source: string | Uint8Array;
}

function assetText(source: string | Uint8Array): string {
  return typeof source === "string" ? source : new TextDecoder().decode(source);
}

describe("self-hosted fonts and no external URLs in index.html or CSS", () => {
  it("contains no external http(s) URLs or Google Fonts references in source index.html and CSS", () => {
    const [indexHtml] = Object.values(indexHtmlModules);
    expect(indexHtml).toBeTruthy();
    expect(indexHtml).not.toMatch(EXTERNAL_URL_RE);
    expect(indexHtml).not.toMatch(GOOGLE_FONT_HOST_RE);

    const cssEntries = Object.entries(cssModules);
    expect(cssEntries.length).toBeGreaterThan(0);
    for (const [cssPath, css] of cssEntries) {
      expect(css, cssPath).not.toMatch(EXTERNAL_URL_RE);
      expect(css, cssPath).not.toMatch(GOOGLE_FONT_HOST_RE);
    }
  });

  it("includes the self-hosted OFL-licensed Material Symbols font asset and licence text", () => {
    const fontPath = decodeURIComponent(
      new URL("../src/assets/fonts/material-symbols-outlined-latin-full-normal.woff2", import.meta.url).pathname,
    );
    expect(existsSync(fontPath)).toBe(true);
    expect(readFileSync(fontPath, "latin1").startsWith("wOF2")).toBe(true);

    const [license] = Object.values(licenseModules);
    expect(license).toContain("SIL OPEN FONT LICENSE Version 1.1");
  });

  it("emits built index.html and CSS with zero external http(s) URLs and a bundled local woff2 font", async () => {
    const configFile = decodeURIComponent(new URL("../vite.config.ts", import.meta.url).pathname);
    const result = await build({
      configFile,
      logLevel: "silent",
      build: {
        write: false,
        // This guard inspects only emitted HTML, CSS, and font assets; externalizing resolved
        // third-party JS modules and skipping JS minification avoids 30s+ full-suite CPU
        // contention timeouts while still traversing every app module and bundling all CSS/fonts.
        minify: false,
        cssMinify: true,
        reportCompressedSize: false,
        rollupOptions: {
          external(id, _importer, isResolved) {
            if (!isResolved) return false;
            const normalized = id.replace(/\\/g, "/");
            return normalized.includes("/node_modules/") && !BUNDLED_ASSET_RE.test(normalized);
          },
        },
      },
    });
    const outputs = Array.isArray(result) ? result : [result];
    const assets: EmittedAsset[] = outputs
      .flatMap((output) => ("output" in output ? output.output : []))
      .flatMap((item): EmittedAsset[] =>
        item.type === "asset" ? [{ type: "asset", fileName: item.fileName, source: item.source }] : [],
      );

    const htmlAssets = assets.filter((item) => item.fileName.endsWith(".html"));
    const cssAssets = assets.filter((item) => item.fileName.endsWith(".css"));
    const fontAssets = new Set(assets.filter((item) => item.fileName.endsWith(".woff2")).map((item) => `/${item.fileName}`));

    expect(htmlAssets.map((a) => a.fileName)).toContain("index.html");
    expect(cssAssets.length).toBeGreaterThan(0);
    expect(fontAssets.size).toBeGreaterThan(0);

    for (const asset of [...htmlAssets, ...cssAssets]) {
      const text = assetText(asset.source);
      expect(text, asset.fileName).not.toMatch(EXTERNAL_URL_RE);
      expect(text, asset.fileName).not.toMatch(GOOGLE_FONT_HOST_RE);
    }

    const builtCss = cssAssets.map((a) => assetText(a.source)).join("\n");
    expect(builtCss).toContain("@font-face");
    expect(builtCss).toContain("Material Symbols Outlined");
    const fontUrlMatch = builtCss.match(/url\(([^)]+\.woff2)\)/);
    expect(fontUrlMatch).not.toBeNull();
    const emittedFontUrl = fontUrlMatch![1].replace(/^['"]|['"]$/g, "");
    expect(fontAssets.has(emittedFontUrl)).toBe(true);
  }, 30_000);
});
