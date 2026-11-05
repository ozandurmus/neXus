import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../src/theme/m3Theme";
import { LoginScreen } from "../src/auth/LoginScreen";
import { ConstellationCanvas } from "../src/auth/ConstellationCanvas";

describe("LoginScreen", () => {
  afterEach(() => vi.unstubAllGlobals());

  it.each(["local", "ldap"])("submits the selected %s mechanism without fallback", async (mechanism) => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 401 }));
    vi.stubGlobal("fetch", fetchMock);
    render(<ThemeProvider theme={m3Theme}><LoginScreen onAuthenticated={() => {}} /></ThemeProvider>);
    if (mechanism === "ldap") fireEvent.click(screen.getByRole("radio", { name: "LDAP account" }));
    expect(screen.getByRole("radio", { name: mechanism === "ldap" ? "LDAP account" : "Local account" })).toBeChecked();
    fireEvent.change(screen.getByLabelText("Username"), { target: { value: "synthetic" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "synthetic-test-input" } });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Incorrect username or password."));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(JSON.parse(fetchMock.mock.calls[0][1].body).mechanism_id).toBe(mechanism);
    if (mechanism === "ldap") expect(screen.getByLabelText("Password")).toHaveValue("");
  });
  it("shows the neXus hub wordmark and the accessible sign-in form", () => {
    render(
      <ThemeProvider theme={m3Theme}>
        <LoginScreen onAuthenticated={() => {}} />
      </ThemeProvider>,
    );
    expect(screen.getByAltText("neXus").getAttribute("src")).toContain("wordmark-light-on-dark.svg");
    expect(screen.getByRole("group", { name: "Authentication" })).toBeInTheDocument();
    expect(screen.getByLabelText("Username")).toBeInTheDocument();
    expect(screen.getByLabelText("Password")).toHaveAttribute("type", "password");
    expect(screen.getByText("neXus · on-premises")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
    expect(screen.queryByText(/Every firewall/)).toBeNull();
  });

  it("reveals the password only while the toggle is pressed", () => {
    render(<ThemeProvider theme={m3Theme}><LoginScreen onAuthenticated={() => {}} /></ThemeProvider>);
    const toggle = screen.getByRole("button", { name: "Show password" });
    fireEvent.click(toggle);
    expect(screen.getByLabelText("Password")).toHaveAttribute("type", "text");
    expect(toggle).toHaveAttribute("aria-pressed", "true");
    fireEvent.click(toggle);
    expect(screen.getByLabelText("Password")).toHaveAttribute("type", "password");
  });

  it("does not reveal whether the account exists: unknown and wrong-password failures read the same", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 401 })));
    render(<ThemeProvider theme={m3Theme}><LoginScreen onAuthenticated={() => {}} /></ThemeProvider>);
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Incorrect username or password."));
  });

  it("calls onAuthenticated on success and offers takeover on a session conflict", async () => {
    const onAuthenticated = vi.fn();
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ conflict_token: "tok" }), { status: 409 }))
      .mockResolvedValueOnce(new Response(null, { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    render(<ThemeProvider theme={m3Theme}><LoginScreen onAuthenticated={onAuthenticated} /></ThemeProvider>);
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    const takeover = await screen.findByRole("button", { name: "Terminate prior session & sign in" });
    expect(screen.getByRole("alert")).toHaveTextContent("Another session is already active for this account.");
    fireEvent.click(takeover);
    await waitFor(() => expect(onAuthenticated).toHaveBeenCalledTimes(1));
    expect(fetchMock.mock.calls[1][0]).toBe("/login/resolve");
  });
});

describe("ConstellationCanvas", () => {
  const ctxStub = new Proxy({}, { get: (_t, prop) => (prop === "createLinearGradient" || prop === "createRadialGradient" ? () => ({ addColorStop() {} }) : () => {}) });

  function stubEnvironment(reduce: boolean) {
    vi.stubGlobal("matchMedia", (query: string) => ({
      matches: reduce && query.includes("prefers-reduced-motion"),
      media: query,
      addEventListener() {},
      removeEventListener() {},
    }));
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue(ctxStub as unknown as CanvasRenderingContext2D);
    const raf = vi.fn().mockReturnValue(1);
    vi.stubGlobal("requestAnimationFrame", raf);
    vi.stubGlobal("cancelAnimationFrame", vi.fn());
    return raf;
  }

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("starts no animation loop under prefers-reduced-motion", () => {
    const raf = stubEnvironment(true);
    render(<ConstellationCanvas />);
    expect(screen.getByTestId("constellation-canvas")).toHaveAttribute("aria-hidden", "true");
    expect(raf).not.toHaveBeenCalled();
  });

  it("runs the animation loop when motion is allowed and stops it on unmount", () => {
    const raf = stubEnvironment(false);
    const { unmount } = render(<ConstellationCanvas />);
    expect(raf).toHaveBeenCalled();
    unmount();
    expect(globalThis.cancelAnimationFrame).toHaveBeenCalled();
  });
});
