import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../src/theme/m3Theme";
import { LoginScreen } from "../src/auth/LoginScreen";

describe("LoginScreen", () => {
  afterEach(() => vi.unstubAllGlobals());

  it.each(["local", "ldap"])("submits the selected %s mechanism without fallback", async (mechanism) => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 401 }));
    vi.stubGlobal("fetch", fetchMock);
    render(<ThemeProvider theme={m3Theme}><LoginScreen onAuthenticated={() => {}} /></ThemeProvider>);
    if (mechanism === "ldap") fireEvent.change(screen.getByLabelText("Authentication"), { target: { value: mechanism } });
    fireEvent.change(screen.getByLabelText("Username"), { target: { value: "synthetic" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "synthetic-test-input" } });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("Incorrect username or password."));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(JSON.parse(fetchMock.mock.calls[0][1].body).mechanism_id).toBe(mechanism);
    if (mechanism === "ldap") expect(screen.getByLabelText("Password")).toHaveValue("");
  });
  it("shows the approved neXus tagline lockup above the sign-in form", () => {
    render(
      <ThemeProvider theme={m3Theme}>
        <LoginScreen onAuthenticated={() => {}} />
      </ThemeProvider>,
    );
    expect(screen.getByAltText("neXus — A CLEARER TOMORROW").getAttribute("src")).toContain("wordmark-tagline.svg");
    expect(screen.getByLabelText("Username")).toBeInTheDocument();
  });
});
