import { describe, expect, it, vi, afterEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { DirectorySettingsPanel } from "../src/components/settings/DirectorySettingsPanel";

const profile = { host: "ldap.example.test", port: 636, transport: "LDAPS", baseDn: "dc=example,dc=test", bindDn: "", groupDn: "", enabled: true };

describe("DirectorySettingsPanel save", () => {
  afterEach(() => vi.restoreAllMocks());

  it("sends the CSRF token with the LDAP PUT, like every other write", async () => {
    const calls: { url: string; init?: RequestInit }[] = [];
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push({ url, init });
      if (url.includes("/session/status")) return new Response(JSON.stringify({ csrf_token: "csrf-test-token" }), { status: 200 });
      if (url.includes("/api/v2/config/ldap") && (!init || !init.method || init.method === "GET")) return new Response(JSON.stringify(profile), { status: 200 });
      return new Response(JSON.stringify(profile), { status: 200 });
    });
    render(<DirectorySettingsPanel />);
    const save = await screen.findByRole("button", { name: /save/i });
    fireEvent.click(save);
    await waitFor(() => expect(calls.some((c) => c.init?.method === "PUT")).toBe(true));
    const put = calls.find((c) => c.init?.method === "PUT")!;
    expect(new Headers(put.init!.headers).get("X-CSRF-Token")).toBe("csrf-test-token");
    expect(await screen.findByText("LDAP settings saved.")).toBeTruthy();
  });
});
