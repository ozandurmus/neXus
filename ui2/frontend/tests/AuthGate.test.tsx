import { ThemeProvider } from "@mui/material/styles";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { useSession } from "../src/auth/SessionContext";
import { AuthGate } from "../src/auth/AuthGate";
import { listDevices, resetSharedApiCache, responsesAreMasked } from "../src/auth/adminApi";
import { TopAppBar } from "../src/shell/TopAppBar";
import { m3Theme } from "../src/theme/m3Theme";

function jsonResponse(status: number, body: unknown, headers?: HeadersInit): Response {
  return new Response(JSON.stringify(body), { status, headers });
}

afterEach(() => {
  resetSharedApiCache();
  vi.unstubAllGlobals();
});

describe("AuthGate", () => {
  it("sends the session's CSRF token with the sign-out request", async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      calls.push({ url, init });
      if (url === "/session/status") return jsonResponse(200, { authenticated: true, csrf_token: "synthetic-csrf" });
      return jsonResponse(200, { ok: true });
    }));
    function SignOutButton() {
      const session = useSession();
      return <button onClick={() => session?.onSignOut()}>sign out</button>;
    }
    render(<AuthGate><SignOutButton /></AuthGate>);
    fireEvent.click(await screen.findByText("sign out"));
    await waitFor(() => expect(calls.some((c) => c.url === "/session/logout")).toBe(true));
    const logout = calls.find((c) => c.url === "/session/logout");
    expect(logout?.init?.method).toBe("POST");
    expect((logout?.init?.headers as Record<string, string>)["X-CSRF-Token"]).toBe("synthetic-csrf");
  });

  it("drops every shared read cache on sign-out, so the next user never sees the previous user's device list", async () => {
    let devicesCalls = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/session/status") return jsonResponse(200, { authenticated: true });
      if (url === "/devices") {
        devicesCalls += 1;
        return jsonResponse(200, { devices: [] });
      }
      return jsonResponse(200, {});
    }));
    function SignOutButton() {
      const session = useSession();
      return <button onClick={() => session?.onSignOut()}>sign out</button>;
    }
    render(<AuthGate><SignOutButton /></AuthGate>);
    await listDevices();
    await listDevices();
    expect(devicesCalls).toBe(1);
    fireEvent.click(await screen.findByText("sign out"));
    await waitFor(() => expect(screen.queryByText("sign out")).toBeNull());
    await listDevices();
    expect(devicesCalls).toBe(2);
  });
  it.each([true, false, undefined])("carries the server Administration read affordance %s", async permitted => {
    vi.stubGlobal("fetch",vi.fn().mockResolvedValue(jsonResponse(200,{
      authenticated: true, can_read_administration: permitted,
    })));
    function ReadAffordance() {
      return <div>{useSession()?.canReadAdministration ? "Read allowed" : "Read unavailable"}</div>;
    }
    render(<AuthGate><ReadAffordance /></AuthGate>);
    expect(await screen.findByText(permitted === true ? "Read allowed" : "Read unavailable")).toBeInTheDocument();
  });
  it("never renders its children until the session check resolves as authenticated", async () => {
    let resolveStatus: (response: Response) => void = () => {};
    const statusPromise = new Promise<Response>((resolve) => {
      resolveStatus = resolve;
    });
    vi.stubGlobal("fetch", vi.fn().mockReturnValue(statusPromise));

    render(
      <AuthGate>
        <div>product screen content</div>
      </AuthGate>,
    );

    // While the check is pending, the product content must not be present.
    expect(screen.queryByText("product screen content")).toBeNull();

    resolveStatus(jsonResponse(200, { authenticated: true }));

    await waitFor(() => expect(screen.getByText("product screen content")).toBeInTheDocument());
  });

  it("shows the login screen, never the children, when there is no session", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(401, { authenticated: false })));

    render(
      <AuthGate>
        <div>product screen content</div>
      </AuthGate>,
    );

    await waitFor(() => expect(screen.getByLabelText("Username")).toBeInTheDocument());
    expect(screen.queryByText("product screen content")).toBeNull();
  });

  it("shows the login screen when the session check itself fails (e.g. network error)", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("network down")));

    render(
      <AuthGate>
        <div>product screen content</div>
      </AuthGate>,
    );

    await waitFor(() => expect(screen.getByLabelText("Username")).toBeInTheDocument());
  });

  it("renders the children after a successful login from the login screen", async () => {
    const fetchMock = vi.fn();
    fetchMock.mockResolvedValueOnce(jsonResponse(401, { authenticated: false })); // initial /session/status
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { ok: true })); // POST /login
    fetchMock.mockResolvedValueOnce(
      jsonResponse(200, { authenticated: true, display_name: "nexusadmin", role_tokens: [], must_change_password: false }),
    ); // AuthGate's post-login re-check of /session/status
    vi.stubGlobal("fetch", fetchMock);

    render(
      <AuthGate>
        <div>product screen content</div>
      </AuthGate>,
    );

    await waitFor(() => expect(screen.getByLabelText("Username")).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText("Username"), { target: { value: "nexusadmin" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "correct-horse-battery" } });
    fireEvent.click(screen.getByRole("button", { name: /sign in/i }));

    await waitFor(() => expect(screen.getByText("product screen content")).toBeInTheDocument());
  });

  it("shows the password-change screen instead of the shell while must_change_password is true", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse(200, { authenticated: true, display_name: "nexusadmin", role_tokens: [], must_change_password: true }),
      ),
    );

    render(
      <AuthGate>
        <div>product screen content</div>
      </AuthGate>,
    );

    await waitFor(() => expect(screen.getByLabelText("Current password")).toBeInTheDocument());
    expect(screen.queryByText("product screen content")).toBeNull();
  });

  it("shows the identical error message for every login failure mode (contract §5.3)", async () => {
    const fetchMock = vi.fn();
    fetchMock.mockResolvedValueOnce(jsonResponse(401, { authenticated: false })); // initial /session/status
    fetchMock.mockResolvedValueOnce(jsonResponse(401, { error: "INVALID_CREDENTIALS" })); // unknown identity
    vi.stubGlobal("fetch", fetchMock);

    render(
      <AuthGate>
        <div>product screen content</div>
      </AuthGate>,
    );

    await waitFor(() => expect(screen.getByLabelText("Username")).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText("Username"), { target: { value: "no-such-user" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "wrong" } });
    fireEvent.click(screen.getByRole("button", { name: /sign in/i }));

    const firstError = await screen.findByRole("alert");
    expect(firstError).toHaveTextContent("Incorrect username or password.");

    // A second, distinct failure mode -- a wrong password for a known
    // identity -- must produce byte-identical wording client-side too.
    fetchMock.mockResolvedValueOnce(jsonResponse(401, { error: "INVALID_CREDENTIALS" }));
    fireEvent.click(screen.getByRole("button", { name: /sign in/i }));

    const secondError = await screen.findByRole("alert");
    expect(secondError).toHaveTextContent(firstError.textContent ?? "");
  });

  it("offers single sign-on on the login screen only while the signed-out status answer says SAML is on", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(401, { authenticated: false, saml_enabled: true })));
    render(<AuthGate><div>app</div></AuthGate>);
    expect(await screen.findByRole("link", { name: "Sign in with SSO (ADFS)" })).toBeInTheDocument();
    vi.unstubAllGlobals();
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(401, { authenticated: false })));
    render(<AuthGate><div>app</div></AuthGate>);
    await screen.findAllByRole("heading", { name: "Sign in" });
    expect(screen.getAllByRole("link", { name: "Sign in with SSO (ADFS)" })).toHaveLength(1);
  });

  it("clears the aiview masking badge and state on sign-out before a subsequent security_admin login", async () => {
    let sessionKind: "aiview" | "signed_out" | "security_admin" = "aiview";
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/session/status") {
        if (sessionKind === "aiview") {
          return jsonResponse(200, {
            authenticated: true,
            display_name: "aiview",
            role_tokens: ["role:replay_viewer"],
            csrf_token: "synthetic-csrf-aiview",
          });
        }
        if (sessionKind === "security_admin") {
          return jsonResponse(200, {
            authenticated: true,
            display_name: "secadmin",
            role_tokens: ["role:security_admin"],
            csrf_token: "synthetic-csrf-admin",
          });
        }
        return jsonResponse(401, { authenticated: false });
      }
      if (url === "/session/logout") {
        sessionKind = "signed_out";
        return jsonResponse(200, { ok: true });
      }
      if (url === "/login") {
        sessionKind = "security_admin";
        return jsonResponse(200, { ok: true });
      }
      const headers = sessionKind === "aiview" ? { "X-Nexus-Masked": "true" } : undefined;
      return jsonResponse(
        200,
        {
          devices: [{ device_id: "dev-1", hostname: "FW-TANGO-04", management_address: "192.0.2.10" }],
          notifications: [],
          pods: [],
        },
        headers,
      );
    }));

    render(
      <AuthGate>
        <ThemeProvider theme={m3Theme}>
          <TopAppBar />
        </ThemeProvider>
      </AuthGate>,
    );

    expect(await screen.findByText("aiview · names masked")).toBeInTheDocument();
    expect(responsesAreMasked()).toBe(true);

    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
    await screen.findByLabelText("Username");
    expect(responsesAreMasked()).toBe(false);

    fireEvent.change(screen.getByLabelText("Username"), { target: { value: "secadmin" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "synthetic-pass" } });
    fireEvent.click(screen.getByRole("button", { name: /sign in/i }));

    expect(await screen.findByText("secadmin")).toBeInTheDocument();
    await listDevices();
    expect(responsesAreMasked()).toBe(false);
    expect(screen.queryByText("aiview · names masked")).toBeNull();
  });

  it("ignores a stale in-flight aiview response settling after sign-out and cache reset", async () => {
    let resolveInFlight!: (response: Response) => void;
    const inFlightDevices = new Promise<Response>((resolve) => {
      resolveInFlight = resolve;
    });

    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/session/status") {
        return jsonResponse(200, {
          authenticated: true,
          display_name: "aiview",
          role_tokens: ["role:replay_viewer"],
          csrf_token: "synthetic-csrf",
        });
      }
      if (url === "/session/logout") return jsonResponse(200, { ok: true });
      if (url === "/devices") return inFlightDevices;
      return jsonResponse(200, {});
    }));

    function SignOutButton() {
      const session = useSession();
      return <button onClick={() => session?.onSignOut()}>sign out</button>;
    }

    render(<AuthGate><SignOutButton /></AuthGate>);
    const signOutBtn = await screen.findByText("sign out");

    const pendingDevices = listDevices();
    fireEvent.click(signOutBtn);
    await waitFor(() => expect(screen.queryByText("sign out")).toBeNull());
    expect(responsesAreMasked()).toBe(false);

    resolveInFlight(
      jsonResponse(
        200,
        { devices: [{ device_id: "dev-1", hostname: "FW-TANGO-04", management_address: "192.0.2.10" }] },
        { "X-Nexus-Masked": "true" },
      ),
    );
    await pendingDevices;

    expect(responsesAreMasked()).toBe(false);
  });
});
