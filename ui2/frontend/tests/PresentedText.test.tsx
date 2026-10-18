import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Presented, Stamp } from "../src/shell/PresentedText";

describe("<Presented>", () => {
  it("shows the label with the raw value on hover", () => {
    render(<Presented label="Palo Alto readiness check" raw="pan_failover_readiness" />);
    expect(screen.getByText("Palo Alto readiness check")).toHaveAttribute("data-raw", "pan_failover_readiness");
  });

  it("can show the raw value as a muted suffix", () => {
    render(<Presented label="Palo Alto readiness check" raw="pan_failover_readiness" suffix />);
    expect(screen.getByText("pan_failover_readiness")).toBeInTheDocument();
    expect(screen.getByText(/Palo Alto readiness check/)).toBeInTheDocument();
  });

  it("repeats nothing when the label already is the raw value (the fallback)", () => {
    render(<Presented label="brand_new_capability" raw="brand_new_capability" suffix />);
    expect(screen.getAllByText("brand_new_capability")).toHaveLength(1);
  });
});

describe("<Stamp>", () => {
  it("writes the one format with a muted relative age and the UTC reference on hover", () => {
    render(<Stamp at="2026-10-18T03:49:00Z" now={new Date("2026-10-18T05:49:00Z")} />);
    expect(screen.getByText("2026-10-18 06:49 GMT+3")).toBeInTheDocument();
    expect(screen.getByText("2 h ago")).toBeInTheDocument();
    expect(screen.getByLabelText("2026-10-18T03:49:00Z (UTC reference)")).toHaveAttribute("datetime", "2026-10-18T03:49:00Z");
  });

  it("can leave the age out, and writes UNKNOWN for an absent value", () => {
    const { rerender } = render(<Stamp at="2026-10-18T03:49:00Z" age={false} />);
    expect(screen.queryByText(/ago/)).toBeNull();
    rerender(<Stamp at={null} />);
    expect(screen.getByText("UNKNOWN")).toBeInTheDocument();
  });
});
