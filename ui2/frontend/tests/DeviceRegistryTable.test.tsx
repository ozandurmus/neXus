import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { DeviceManagementPane, registryMatches } from "../src/screens/DeviceRegistryPanel";
import type { DeviceSummary } from "../src/auth/adminApi";

afterEach(() => vi.unstubAllGlobals());

const device = (n: number, extra: Partial<DeviceSummary> = {}): DeviceSummary => ({
  device_id: `dev-${n}`, vendor_hint: n % 3 === 0 ? "fortinet" : "check_point", enrollment_state: "ENROLLED",
  hostname: `FW-ALPHA-${String(n).padStart(3, "0")}`, model: null, software_version: null, ha_role: null, cluster_member_ref: null,
  inventory_collected_at: "2026-10-18T03:00:00Z", ...extra,
});

function mount(devices: DeviceSummary[]) {
  vi.stubGlobal("fetch", vi.fn((url: string) => Promise.resolve(new Response(JSON.stringify(url.startsWith("/devices") ? { devices } : {}), { status: 200 }))));
  return render(<ThemeProvider theme={m3Theme}><DeviceManagementPane /></ThemeProvider>);
}
const table = () => screen.getByRole("table", { name: "Device registry" });
const hostnames = () => within(table()).getAllByRole("row").slice(1).map((row) => row.textContent?.match(/FW-ALPHA-\d{3}/)?.[0]);

const FLEET = [
  device(1), device(2), device(3), device(4, { enrollment_state: "DRAFT", hostname: null, inventory_collected_at: null }),
  device(5, { latest_job_state: "FAILED" }), device(6, { enrollment_state: "UNREACHABLE" }),
];

describe("Administration device registry (UI review 2026-10-18, F-22)", () => {
  it("is a plain table with one row per device and its own column headings", async () => {
    mount(FLEET);
    await screen.findByText("FW-ALPHA-001");
    expect(within(table()).getAllByRole("columnheader").map((c) => c.textContent?.trim()).filter(Boolean))
      .toEqual(["VENDOR", "DEVICE / HOSTNAME", "ENROLLMENT STATE", "LAST COLLECTION"]);
    expect(within(table()).getAllByRole("row")).toHaveLength(1 + FLEET.length);
    expect(screen.getAllByText("Fortinet").length).toBeGreaterThan(0);
    expect(screen.queryByText("fortinet")).toBeNull();
  });

  it("filters by the Enrollment card counts, and a second click on the same count clears the filter", async () => {
    mount(FLEET);
    await screen.findByText("FW-ALPHA-001");
    const group = screen.getByRole("group", { name: "Registry filters" });
    const draft = within(group).getByRole("button", { name: /Draft · not collected/ });
    expect(draft).toHaveAttribute("aria-pressed", "false");
    fireEvent.click(draft);
    expect(draft).toHaveAttribute("aria-pressed", "true");
    expect(within(table()).getAllByRole("row")).toHaveLength(2);
    expect(within(table()).getByText("UNKNOWN hostname")).toBeInTheDocument();
    fireEvent.click(draft);
    expect(within(table()).getAllByRole("row")).toHaveLength(1 + FLEET.length);

    const failed = within(group).getByRole("button", { name: /Last collection failed/ });
    fireEvent.click(failed);
    expect(hostnames().sort()).toEqual(["FW-ALPHA-005", "FW-ALPHA-006"]);
    const enrolled = within(group).getByRole("button", { name: /^Enrolled/ });
    fireEvent.click(enrolled);
    expect(hostnames().sort()).toEqual(["FW-ALPHA-001", "FW-ALPHA-002", "FW-ALPHA-003", "FW-ALPHA-005"]);
  });

  it("matches the counts: every card count is the number of rows its filter opens", () => {
    for (const [filter, count] of [["ENROLLED", 4], ["LAST_FAILED", 2], ["DRAFT", 1]] as const) {
      expect(FLEET.filter((d) => registryMatches(d, filter, "")).length).toBe(count);
    }
  });

  it("searches hostname, vendor name and state, and says how many rows match", async () => {
    mount(FLEET);
    await screen.findByText("FW-ALPHA-001");
    const box = screen.getByRole("textbox", { name: "Search registry" });
    fireEvent.change(box, { target: { value: "fortinet" } });
    expect(hostnames()).toEqual(["FW-ALPHA-003", "FW-ALPHA-006"]);
    expect(screen.getByRole("status")).toHaveTextContent("2 of 6 match");
    fireEvent.change(box, { target: { value: "alpha-005" } });
    expect(hostnames()).toEqual(["FW-ALPHA-005"]);
    fireEvent.change(box, { target: { value: "no such device" } });
    expect(screen.getByText("No device matches these filters.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    expect(hostnames()).toHaveLength(FLEET.length);
  });

  it("pages a large registry and keeps every row reachable", async () => {
    const big = Array.from({ length: 130 }, (_, i) => device(i + 1));
    mount(big);
    await screen.findByText("FW-ALPHA-001");
    expect(within(table()).getAllByRole("row")).toHaveLength(51);
    expect(screen.getByText("1–50 of 130")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Go to next page" }));
    expect(hostnames()[0]).toBe("FW-ALPHA-051");
    fireEvent.click(screen.getByRole("button", { name: "Go to next page" }));
    expect(hostnames()).toHaveLength(30);
    expect(screen.getByText("101–130 of 130")).toBeInTheDocument();
  });

  it("selects only the rows the filters leave, so a bulk delete never reaches a row that is not in view", async () => {
    mount(FLEET);
    await screen.findByText("FW-ALPHA-001");
    fireEvent.click(within(screen.getByRole("group", { name: "Registry filters" })).getByRole("button", { name: /Draft · not collected/ }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Select all devices" }));
    expect(screen.getByText("1 of 6 selected")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Delete selected (1)" })).toBeInTheDocument();
  });

  it("does not colour an empty count green", async () => {
    mount([device(1, { enrollment_state: "DRAFT" })]);
    await screen.findByText("FW-ALPHA-001");
    const enrolled = within(screen.getByRole("group", { name: "Registry filters" })).getByRole("button", { name: /^Enrolled/ });
    const chip = within(enrolled).getByText("0 devices").closest(".MuiChip-root") as HTMLElement;
    const css = [...document.querySelectorAll("style")].map((node) => [...((node as HTMLStyleElement).sheet?.cssRules ?? [])].map((r) => r.cssText).join(" ")).join(" ");
    const className = [...chip.classList].find((c) => c.startsWith("css-"))!;
    const rule = css.match(new RegExp(`\\.${className}\\s*\\{[^}]*\\}`))?.[0] ?? "";
    expect(rule).toContain("var(--nx-sc-high)");
    expect(rule).not.toContain("var(--nx-success-container)");
  });
});
