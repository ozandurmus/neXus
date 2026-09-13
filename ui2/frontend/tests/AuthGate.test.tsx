import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { AuthGate } from "../src/auth/AuthGate";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("AuthGate", () => {
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
});
