import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../src/theme/m3Theme";
import { LoginScreen } from "../src/auth/LoginScreen";

describe("LoginScreen", () => {
  it("shows the neXus wordmark above the sign-in form", () => {
    render(
      <ThemeProvider theme={m3Theme}>
        <LoginScreen onAuthenticated={() => {}} />
      </ThemeProvider>,
    );
    expect(screen.getByTitle("neXus")).toBeInTheDocument();
    expect(screen.getByLabelText("Username")).toBeInTheDocument();
  });
});
