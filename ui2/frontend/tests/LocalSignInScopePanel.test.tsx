import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { LocalSignInScopePanel } from "../src/components/settings/LocalSignInScopePanel";

const view = (mode: string, canWrite = true) => ({
  mode, modes: ["ALL_LOCAL", "BREAK_GLASS_ONLY"], break_glass_accounts: ["nexusadmin (root identity)", "aiview"],
  updated_at: "2026-11-15T09:00:00Z", updated_by: "af3a9c1e2b7d", can_write: canWrite,
});

interface Call { readonly url: string; readonly method: string; readonly body: any }
type Reply = { status?: number; body: unknown };

function mockApi(replies: { get?: Reply; put?: Reply }): Call[] {
  const calls: Call[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? "GET";
    const reply = (r: Reply) => new Response(JSON.stringify(r.body), { status: r.status ?? 200, headers: { "Content-Type": "application/json" } });
    calls.push({ url, method, body: typeof init?.body === "string" ? JSON.parse(init.body) : undefined });
    if (url.includes("/session/status")) return reply({ body: { csrf_token: "csrf-test-token" } });
    if (url.endsWith("/api/v2/config/local-auth-mode") && method === "PUT") return reply(replies.put ?? { body: view("BREAK_GLASS_ONLY") });
    if (url.endsWith("/api/v2/config/local-auth-mode")) return reply(replies.get ?? { body: view("ALL_LOCAL") });
    return reply({ status: 404, body: {} });
  });
  return calls;
}

describe("LocalSignInScopePanel", () => {
  afterEach(() => vi.restoreAllMocks());

  it("shows the current mode and switches it with the CSRF-protected PUT", async () => {
    const calls = mockApi({});
    render(<LocalSignInScopePanel />);
    const all = await screen.findByLabelText("All local accounts (current behaviour)");
    expect(all).toBeChecked();
    const save = screen.getByRole("button", { name: "Save" });
    expect(save).toBeDisabled();
    fireEvent.click(screen.getByLabelText("Break-glass only: nexusadmin (root identity) and aiview"));
    expect(save).toBeEnabled();
    fireEvent.click(save);
    expect(await screen.findByTestId("local-auth-mode-saved")).toBeInTheDocument();
    const put = calls.find((c) => c.method === "PUT");
    expect(put?.body).toEqual({ mode: "BREAK_GLASS_ONLY" });
    expect(screen.getByLabelText("Break-glass only: nexusadmin (root identity) and aiview")).toBeChecked();
  });

  it("shows the server's lock-out refusal and keeps the saved mode", async () => {
    mockApi({ put: { status: 409, body: { code: "LOCAL_AUTH_MODE_ACTOR_WOULD_LOCK_OUT", message: "BREAK_GLASS_ONLY was not enabled: you are signed in with a local account that this mode refuses." } } });
    render(<LocalSignInScopePanel />);
    fireEvent.click(await screen.findByLabelText("Break-glass only: nexusadmin (root identity) and aiview"));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByTestId("local-auth-mode-problem")).toHaveTextContent("this mode refuses");
    expect(screen.queryByTestId("local-auth-mode-saved")).toBeNull();
  });

  it("is read-only without write permission", async () => {
    mockApi({ get: { body: view("ALL_LOCAL", false) } });
    render(<LocalSignInScopePanel />);
    await waitFor(() => expect(screen.getByLabelText("All local accounts (current behaviour)")).toBeDisabled());
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });
});
