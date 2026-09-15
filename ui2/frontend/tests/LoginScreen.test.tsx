import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../src/theme/m3Theme";
import { LoginScreen } from "../src/auth/LoginScreen";

describe("LoginScreen", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("shows the neXus wordmark above the sign-in form", () => {
    render(
      <ThemeProvider theme={m3Theme}>
        <LoginScreen onAuthenticated={() => {}} />
      </ThemeProvider>,
    );
    expect(screen.getByTitle("neXus")).toBeInTheDocument();
    expect(screen.getByLabelText("Username")).toBeInTheDocument();
  });

  it("shows a conflict without exposing its token", async () => {
    const token = "conflict-token-must-not-render";
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            conflict_token: token,
            prior_session: { created_at: "2026-09-15T05:00:00Z", last_seen_at: "2026-09-15T05:15:00Z" },
          }),
          { status: 409 },
        ),
      ),
    );
    const { container } = render(<LoginScreen onAuthenticated={() => {}} />);

    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByText(/A session for this account is already active/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Take over session" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Refuse and return to sign in" })).toBeInTheDocument();
    expect(screen.getByText(/Taking over ends the other session/)).toBeInTheDocument();
    expect(container.textContent).not.toContain(token);
    expect(container.innerHTML).not.toContain(token);
    expect(container.textContent).not.toContain("2026-09-15T05:00:00Z");
  });

  it("refuses by button or return without resolving the prior session", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          conflict_token: "private-token",
          prior_session: { created_at: "2026-09-15T05:00:00Z", last_seen_at: "2026-09-15T05:15:00Z" },
        }),
        { status: 409 },
      ),
    );
    vi.stubGlobal("fetch", fetchMock);
    render(<LoginScreen onAuthenticated={() => {}} />);

    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await screen.findByRole("button", { name: "Refuse and return to sign in" });
    fireEvent.click(screen.getByRole("button", { name: "Refuse and return to sign in" }));
    expect(fetchMock).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await screen.findByRole("button", { name: "Take over session" });
    fireEvent.submit(screen.getByRole("button", { name: "Take over session" }).closest("form")!);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("takes over explicitly and authenticates", async () => {
    const onAuthenticated = vi.fn();
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            conflict_token: "private-token",
            prior_session: { created_at: "2026-09-15T05:00:00Z", last_seen_at: "2026-09-15T05:15:00Z" },
          }),
          { status: 409 },
        ),
      )
      .mockResolvedValueOnce(new Response(JSON.stringify({ ok: true }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    render(<LoginScreen onAuthenticated={onAuthenticated} />);

    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    fireEvent.click(await screen.findByRole("button", { name: "Take over session" }));

    await vi.waitFor(() => expect(onAuthenticated).toHaveBeenCalledOnce());
    expect(fetchMock).toHaveBeenLastCalledWith(
      "/login/resolve",
      expect.objectContaining({ body: JSON.stringify({ conflictToken: "private-token", action: "takeover" }) }),
    );
  });

  it("explains when a conflict offer has lapsed", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            conflict_token: "private-token",
            prior_session: { created_at: "2026-09-15T05:00:00Z", last_seen_at: "2026-09-15T05:15:00Z" },
          }),
          { status: 409 },
        ),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ reason_code: "conflict_token_expired_or_unknown" }), { status: 409 }),
      );
    vi.stubGlobal("fetch", fetchMock);
    render(<LoginScreen onAuthenticated={() => {}} />);

    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    fireEvent.click(await screen.findByRole("button", { name: "Take over session" }));

    expect(await screen.findByText("This session offer has lapsed. Please sign in again.")).toBeInTheDocument();
  });
});
