import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { App } from "../src/App";

describe("App", () => {
  it("renders the UI 2.0 heading", () => {
    render(<App />);
    expect(screen.getByText("UI 2.0")).toBeInTheDocument();
  });
});
