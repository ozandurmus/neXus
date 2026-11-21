/// <reference types="vite/client" />
import { describe, expect, it } from "vitest";
import { PRODUCT_NAME, PRODUCT_SHORT } from "../src/brand/product";

/**
 * The product's user-visible name is defined once in `src/brand/product.ts`
 * (`PRODUCT_NAME` and `PRODUCT_SHORT`).
 *
 * Two rules are enforced here:
 * 1. The legacy "SecurityExpert" name must never appear in `src/` or `index.html`.
 * 2. The visible string literal "neXus" must NOT appear outside `src/brand/product.ts`
 *     across any `.ts` / `.tsx` file under `src/`.
 *    - Line comments (`// ...`) and block comments (`/* ... * /`, including JSX `{/* ... * /}`)
 *      are excluded.
 *    - Code identifiers (e.g. `NexusWordmark`, `nexus_id`, `NEXUS_HOST`, or any identifier token)
 *      are excluded.
 *    - String literals (`"..."`, `'...'`), template literal text chunks (`` `...` ``),
 *      and JSX text nodes (`>...<`) are scanned and fail the test if they contain `"neXus"`.
 */
const LEGACY_NAME_PATTERN = /Security\s*Expert/i;
const BRAND_MODULE_PATH = "../src/brand/product.ts";

const srcModules = import.meta.glob("../src/**/*.{ts,tsx}", { query: "?raw", import: "default", eager: true }) as Record<
  string,
  string
>;
const indexHtmlModules = import.meta.glob("../index.html", { query: "?raw", import: "default", eager: true }) as Record<
  string,
  string
>;

/**
 * Strips `//` line comments and `/* ... * /` block comments while preserving
 * string literals (`"..."`, `'...'`), template literals (`` `...` ``), and
 * newline positions so line numbers remain exact.
 */
export function stripCommentsPreservingStrings(source: string): string {
  const out: string[] = [];
  let i = 0;
  const n = source.length;

  while (i < n) {
    const ch = source[i];
    const next = source[i + 1];

    // Line comment
    if (ch === "/" && next === "/") {
      i += 2;
      while (i < n && source[i] !== "\n") {
        i++;
      }
      continue;
    }

    // Block comment
    if (ch === "/" && next === "*") {
      i += 2;
      while (i < n && !(source[i] === "*" && source[i + 1] === "/")) {
        if (source[i] === "\n") {
          out.push("\n");
        }
        i++;
      }
      i += 2;
      continue;
    }

    // Single- or double-quoted string literal
    if (ch === '"' || ch === "'") {
      const quote = ch;
      out.push(ch);
      i++;
      while (i < n && source[i] !== quote && source[i] !== "\n") {
        if (source[i] === "\\" && i + 1 < n) {
          out.push(source[i], source[i + 1]);
          i += 2;
          continue;
        }
        out.push(source[i]);
        i++;
      }
      if (i < n) {
        out.push(source[i]);
        i++;
      }
      continue;
    }

    // Template literal: preserve static segments, strip comments inside ${...}
    if (ch === "`") {
      out.push(ch);
      i++;
      while (i < n && source[i] !== "`") {
        if (source[i] === "\\" && i + 1 < n) {
          out.push(source[i], source[i + 1]);
          i += 2;
          continue;
        }
        if (source[i] === "$" && source[i + 1] === "{") {
          out.push("${");
          i += 2;
          let depth = 1;
          let expr = "";
          while (i < n && depth > 0) {
            if (source[i] === "{") depth++;
            else if (source[i] === "}") {
              depth--;
              if (depth === 0) break;
            }
            expr += source[i];
            i++;
          }
          out.push(stripCommentsPreservingStrings(expr));
          if (i < n && source[i] === "}") {
            out.push("}");
            i++;
          }
          continue;
        }
        out.push(source[i]);
        i++;
      }
      if (i < n) {
        out.push(source[i]);
        i++;
      }
      continue;
    }

    out.push(ch);
    i++;
  }

  return out.join("");
}

/**
 * Finds visible occurrences of `neXus` in a `.ts` / `.tsx` source file:
 * - Strips comments first.
 * - Matches `neXus` inside quoted/template string literals or JSX text, and
 *   excludes compound code identifiers (such as `fooNeXusBar` or `neXus_id`).
 */
export function findVisibleProductNameOccurrences(source: string): Array<{ line: number; text: string }> {
  const stripped = stripCommentsPreservingStrings(source);
  const lines = stripped.split("\n");
  const hits: Array<{ line: number; text: string }> = [];

  lines.forEach((lineText, idx) => {
    if (!lineText.includes("neXus")) return;
    // Remove any compound identifier tokens that merely contain neXus as part of an identifier name
    // (e.g., myneXusVar or neXus_id), while keeping string literals ("neXus", 'neXus', `...neXus...`)
    // and standalone JSX text words.
    const withoutCompoundIdentifiers = lineText.replace(/[A-Za-z0-9_$]+neXus[A-Za-z0-9_$]*|[A-Za-z0-9_$]*neXus[A-Za-z0-9_$]+/g, "");
    if (withoutCompoundIdentifiers.includes("neXus")) {
      hits.push({ line: idx + 1, text: lineText.trim() });
    }
  });

  return hits;
}

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

describe("single product display-name setting (src/brand/product.ts)", () => {
  it("exports PRODUCT_NAME and PRODUCT_SHORT with the current value 'Overwatch'", () => {
    expect(PRODUCT_NAME).toBe("Overwatch");
    expect(PRODUCT_SHORT).toBe("Overwatch");
    expect(srcModules[BRAND_MODULE_PATH]).toBeDefined();
  });

  it("forbids visible string literal 'neXus' in any .ts/.tsx file under src outside src/brand/product.ts", () => {
    const offenders: string[] = [];
    for (const [path, contents] of Object.entries(srcModules)) {
      if (path === BRAND_MODULE_PATH) continue;
      const hits = findVisibleProductNameOccurrences(contents);
      for (const hit of hits) {
        offenders.push(`${path}:${hit.line}: ${hit.text}`);
      }
    }
    expect(offenders).toEqual([]);
  });

  it("does not hardcode 'neXus' in index.html", () => {
    const [contents] = Object.values(indexHtmlModules);
    expect(contents).not.toContain("neXus");
  });

  it("scanner detects visible string literals, template literals, and JSX text while ignoring comments and identifiers", () => {
    expect(
      findVisibleProductNameOccurrences(`
        // neXus in a line comment is allowed
        /* neXus in a block comment is allowed */
        const jsxComment = <div>{/* neXus in a JSX comment is allowed */}</div>;
        const neXusIdentifier = 1;
      `),
    ).toEqual([]);

    expect(findVisibleProductNameOccurrences(`const label = "neXus";`)).toHaveLength(1);
    expect(findVisibleProductNameOccurrences(`const label = 'Sign in to neXus';`)).toHaveLength(1);
    expect(findVisibleProductNameOccurrences("const msg = `Connected to neXus (${count})`;")).toHaveLength(1);
    expect(findVisibleProductNameOccurrences(`const view = <span>neXus · on-premises</span>;`)).toHaveLength(1);
  });
});
