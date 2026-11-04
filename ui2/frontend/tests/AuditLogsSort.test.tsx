import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../src/theme/m3Theme";
import { AuditLogsPanel } from "../src/screens/AuditLogsPanel";

/** The audit trail is the one data table with the shared column sort: the whole trail is ordered, then paged at 10. */
afterEach(() => vi.unstubAllGlobals());

const events = Array.from({ length: 25 }, (_, k) => {
  const n = k + 1;
  return { id: `e${n}`, occurred_at: new Date(Date.UTC(2026, 9, 1, 0, (n * 7) % 60)).toISOString(), actor: `operator-${(n * 11) % 25 + 1}`, action: n % 2 ? "devices.update" : "backups.request", target: `FW-TANGO-${n}`, outcome: "allowed" };
});

function mount() {
  vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve(new Response(JSON.stringify(url.startsWith("/audit-logs") ? { events } : {}), { status: 200 }))));
  return render(<ThemeProvider theme={m3Theme}><AuditLogsPanel /></ThemeProvider>);
}
const table = () => screen.getByRole("table", { name: "Audit logs table" });
const actors = () => within(table()).getAllByRole("row").slice(1).map((row) => within(row).getAllByRole("cell")[1].textContent);
const header = (name: string) => within(table()).getByRole("button", { name });

describe("audit logs column sort (Product Owner, 2026-11-04)", () => {
  it("orders Actor over all 25 events with numeric collation, page 2 continues it, descending flips it, the third click restores the trail's order", async () => {
    mount();
    await screen.findByText("FW-TANGO-1");
    expect(screen.getByText("1–10 of 25")).toBeInTheDocument();
    expect(actors().slice(0, 2)).toEqual(["operator-12", "operator-23"]);
    fireEvent.click(header("Actor"));
    expect(header("Actor").closest("th")).toHaveAttribute("aria-sort", "ascending");
    expect(actors()).toEqual(Array.from({ length: 10 }, (_, i) => `operator-${i + 1}`));
    fireEvent.click(screen.getByRole("button", { name: "Go to next page" }));
    expect(screen.getByText("11–20 of 25")).toBeInTheDocument();
    expect(actors()).toEqual(Array.from({ length: 10 }, (_, i) => `operator-${i + 11}`));
    fireEvent.click(header("Actor"));
    expect(header("Actor").closest("th")).toHaveAttribute("aria-sort", "descending");
    expect(screen.getByText("1–10 of 25")).toBeInTheDocument();
    expect(actors().slice(0, 2)).toEqual(["operator-25", "operator-24"]);
    fireEvent.click(header("Actor"));
    expect(header("Actor").closest("th")).not.toHaveAttribute("aria-sort");
    expect(actors().slice(0, 2)).toEqual(["operator-12", "operator-23"]);
  });

  it("orders Timestamp chronologically over the whole trail", async () => {
    mount();
    await screen.findByText("FW-TANGO-1");
    fireEvent.click(header("Timestamp"));
    // the minute is 7n mod 60: smallest for n = 9 (63 -> 3), then n = 18 (126 -> 6); largest for n = 17 (119 -> 59)
    const targets = () => within(table()).getAllByRole("row").slice(1).map((row) => within(row).getAllByRole("cell")[3].textContent);
    expect(targets().slice(0, 2)).toEqual(["FW-TANGO-9", "FW-TANGO-18"]);
    fireEvent.click(header("Timestamp"));
    expect(targets()[0]).toBe("FW-TANGO-17");
  });
});
