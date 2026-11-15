import { readFileSync } from "node:fs";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { ThemeProvider } from "@mui/material/styles";
import { afterEach, describe, expect, it, vi } from "vitest";
import { downloadPolicyChanges, getSessionStatus, resetSharedApiCache } from "../src/auth/adminApi";
import { CustomRolesPanel } from "../src/screens/CustomRolesPanel";
import { m3Theme } from "../src/theme/m3Theme";

/**
 * 2026-11-15: an error response whose body is never read leaves the Chrome request pending when the response carries
 * Cache-Control: no-store. Every `!response.ok` / 403 branch in these modules must read or cancel the body.
 */
const MODULES = ["src/auth/adminApi.ts", "src/screens/CustomRolesPanel.tsx"];
const CONSUMES = /\.(text|json|blob|arrayBuffer|formData)\(|body\?*\.cancel\(/;
const BRANCH = /!response\.ok|response\.status === 403/g;

/** The statement a branch guards: its `{ ... }` block, or the rest of the line for a braceless `if`. */
function branchBody(source: string, matchIndex: number): string {
  const open = source.indexOf(")", matchIndex);
  const afterParen = source.slice(open + 1).trimStart();
  if (!afterParen.startsWith("{")) return source.slice(open + 1, source.indexOf("\n", open));
  let depth = 0;
  const start = source.indexOf("{", open);
  for (let i = start; i < source.length; i += 1) {
    if (source[i] === "{") depth += 1;
    if (source[i] === "}" && --depth === 0) return source.slice(start, i + 1);
  }
  return source.slice(start);
}

/** The body may also have been read just above the branch (`const parsed = await response.json()...`). */
function readBefore(source: string, matchIndex: number): boolean {
  const lines = source.slice(0, matchIndex).split("\n").slice(-6).join("\n");
  return /await\s+(?:withTimeout\()?response\.(text|json|blob)\(/.test(lines);
}

describe("error response bodies are consumed", () => {
  for (const module of MODULES) {
    it(`${module}: every !response.ok / 403 branch reads or cancels the body`, () => {
      const source = readFileSync(module, "utf8");
      const offenders: string[] = [];
      let found = 0;
      for (const match of source.matchAll(BRANCH)) {
        found += 1;
        const index = match.index ?? 0;
        if (CONSUMES.test(branchBody(source, index)) || readBefore(source, index)) continue;
        offenders.push(`${module}:${source.slice(0, index).split("\n").length}`);
      }
      expect(found).toBeGreaterThan(0);
      expect(offenders).toEqual([]);
    });
  }

  it("the lint itself flags a branch that returns without consuming the body", () => {
    const source = 'if (!response.ok) {\n  return null;\n}\nreturn await response.json();';
    expect(CONSUMES.test(branchBody(source, 0))).toBe(false);
    expect(readBefore(source, 0)).toBe(false);
  });
});

/** A response whose body reads are observable. */
function spiedResponse(status: number, body = '{"error":"x"}') {
  const response = new Response(body, { status, headers: { "Cache-Control": "no-store" } });
  const reads = vi.spyOn(response, "text");
  return { response, reads };
}

afterEach(() => {
  vi.unstubAllGlobals();
  resetSharedApiCache();
});

describe("error branches consume the body and keep their behaviour", () => {
  it("getSessionStatus returns null after reading the error body", async () => {
    const { response, reads } = spiedResponse(401);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response));
    await expect(getSessionStatus({ force: true })).resolves.toBeNull();
    expect(reads).toHaveBeenCalled();
    expect(response.bodyUsed).toBe(true);
  });

  it("downloadPolicyChanges still throws 'Change export failed' after reading the error body", async () => {
    const { response, reads } = spiedResponse(500);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response));
    await expect(downloadPolicyChanges({} as Parameters<typeof downloadPolicyChanges>[0])).rejects.toThrow("Change export failed");
    expect(reads).toHaveBeenCalled();
    expect(response.bodyUsed).toBe(true);
  });

  it("CustomRolesPanel reads the 403 body of the role list and still shows the restricted state", async () => {
    const { response, reads } = spiedResponse(403);
    vi.stubGlobal("fetch", vi.fn().mockImplementation((input: RequestInfo | URL) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : (input as Request).url;
      return Promise.resolve(url.endsWith("/roles") ? response : new Response("[]", { status: 200 }));
    }));
    render(<ThemeProvider theme={m3Theme}><CustomRolesPanel /></ThemeProvider>);
    await waitFor(() => expect(reads).toHaveBeenCalled());
    expect(response.bodyUsed).toBe(true);
  });

  it("CustomRolesPanel reads the error body of a refused role creation", async () => {
    const refused = spiedResponse(403);
    vi.stubGlobal("fetch", vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : (input as Request).url;
      if (url.endsWith("/roles") && init?.method === "POST") return Promise.resolve(refused.response);
      return Promise.resolve(new Response("[]", { status: 200 }));
    }));
    render(<ThemeProvider theme={m3Theme}><CustomRolesPanel /></ThemeProvider>);
    await waitFor(() => expect(screen.getByRole("button", { name: "Create role" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Create role" }));
    fireEvent.change(screen.getByLabelText(/Role name/), { target: { value: "Reader" } });
    fireEvent.change(screen.getByLabelText(/Role token/), { target: { value: "reader" } });
    fireEvent.click(screen.getByRole("checkbox", { name: "Devices" }));
    fireEvent.click(screen.getAllByRole("button", { name: "Create role" }).pop() as HTMLElement);
    await waitFor(() => expect(refused.reads).toHaveBeenCalled());
    expect(refused.response.bodyUsed).toBe(true);
  });
});
