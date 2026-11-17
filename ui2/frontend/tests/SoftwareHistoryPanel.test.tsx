import { render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { DeviceSoftwareHistory, RecentUpgradesPanel, changeWindowText } from "../src/screens/SoftwareHistoryPanel";
import * as api from "../src/auth/adminApi";

vi.mock("../src/auth/adminApi", async importOriginal => ({
  ...await importOriginal<typeof import("../src/auth/adminApi")>(),
  getDeviceSoftwareHistory: vi.fn(), getSoftwareChanges: vi.fn(),
}));
const themed = (node: React.ReactNode) => render(<ThemeProvider theme={m3Theme}>{node}</ThemeProvider>);
const stretch = (o: Partial<api.SoftwareStretch>): api.SoftwareStretch => ({
  device_id: "dev-1", vendor: "check_point", software_version: "R81.20", hotfix_level: null, first_seen_at: "2026-10-01T10:00:00Z",
  last_seen_at: "2026-11-01T10:00:00Z", previous_software_version: null, previous_hotfix_level: null, window_start: null, window_end: null,
  source: "inventory", ...o });

beforeEach(() => vi.clearAllMocks());

describe("device software history", () => {
  it("lists stretches newest first with the change window and says UNKNOWN for the first one", async () => {
    vi.mocked(api.getDeviceSoftwareHistory).mockResolvedValue({ device_id: "dev-1", known: true, stretches: [
      stretch({ software_version: "R82", hotfix_level: "Take 10", first_seen_at: "2026-11-01T10:00:00Z", previous_software_version: "R81.20",
        window_start: "2026-10-30T10:00:00Z", window_end: "2026-11-01T10:00:00Z", source: "inventory" }),
      stretch({ software_version: "R81.20", source: "discovery_backfill" })] });
    themed(<DeviceSoftwareHistory deviceId="dev-1" />);
    const table = await screen.findByRole("table", { name: "Software version history" });
    expect(within(table).getByText("R82 (current)")).toBeInTheDocument();
    expect(within(table).getByText("Take 10")).toBeInTheDocument();
    expect(within(table).getByText(/from R81.20: 2026-10-30 13:00 GMT\+3 to 2026-11-01 13:00 GMT\+3/)).toBeInTheDocument();
    expect(within(table).getByText("UNKNOWN (first version seen)")).toBeInTheDocument();
    expect(within(table).getByText("Discovery history")).toBeInTheDocument();
  });
  it("says UNKNOWN honestly when nothing was recorded", async () => {
    vi.mocked(api.getDeviceSoftwareHistory).mockResolvedValue({ device_id: "dev-1", known: false, stretches: [] });
    themed(<DeviceSoftwareHistory deviceId="dev-1" />);
    expect(await screen.findByText(/UNKNOWN: no software version has been recorded/)).toBeInTheDocument();
  });
  it("change window text is UNKNOWN without both ends", () => {
    expect(changeWindowText({ window_start: null, window_end: "2026-11-01T10:00:00Z" })).toMatch(/UNKNOWN/);
  });
});

describe("Check Point install log", () => {
  const ev = (o: Partial<api.CpInstallEvent>): api.CpInstallEvent => ({ kind: "JUMBO_INSTALL", product_version: "R81.20", jumbo_take: null,
    installed_at_local: "2026-01-24T03:10:51", file_name: "install_SecurePlatform_HOTFIX_R81_20_JUMBO_HF_MAIN_3.log", first_observed_at: "2026-11-17T10:00:00Z", ...o });
  it("shows the events below the stretches in the device's own clock, only when there are some", async () => {
    vi.mocked(api.getDeviceSoftwareHistory).mockResolvedValue({ device_id: "dev-1", known: true, stretches: [stretch({})], install_events: [
      ev({ jumbo_take: 119 }), ev({ jumbo_take: null, installed_at_local: "2025-01-02T04:05:06", file_name: "b.log" }),
      ev({ kind: "BASE_INSTALL", file_name: "install_fw1_R81.20.elg", installed_at_local: "2024-02-23T12:33:58" })] });
    themed(<DeviceSoftwareHistory deviceId="dev-1" />);
    const table = await screen.findByRole("table", { name: "Check Point install log" });
    expect(within(table).getByText("Jumbo R81.20 Take 119")).toBeInTheDocument();
    expect(within(table).getByText("Jumbo R81.20 (take not in log)")).toBeInTheDocument();
    expect(within(table).getByText("Base install R81.20")).toBeInTheDocument();
    expect(within(table).getByText("2026-01-24 03:10:51")).toBeInTheDocument();
    expect(screen.getByText(/device's own clock/)).toBeInTheDocument();
  });
  it("shows no install table without events", async () => {
    vi.mocked(api.getDeviceSoftwareHistory).mockResolvedValue({ device_id: "dev-1", known: true, stretches: [stretch({})], install_events: [] });
    themed(<DeviceSoftwareHistory deviceId="dev-1" />);
    await screen.findByRole("table", { name: "Software version history" });
    expect(screen.queryByRole("table", { name: "Check Point install log" })).toBeNull();
  });
  it("lists the fleet's installs in Recent upgrades", async () => {
    vi.mocked(api.getSoftwareChanges).mockResolvedValue({ days: 90, since: "2026-08-01T00:00:00Z", changes: [], install_events: [
      { ...ev({ jumbo_take: 119 }), device_id: "dev-1", hostname: "FW-TANGO-04" }] });
    themed(<RecentUpgradesPanel />);
    const table = await screen.findByRole("table", { name: "Check Point installs" });
    expect(within(table).getByText("FW-TANGO-04")).toBeInTheDocument();
    expect(within(table).getByText("Jumbo R81.20 Take 119")).toBeInTheDocument();
  });
});

describe("recent upgrades", () => {
  it("shows from, to and window per device and an empty message", async () => {
    vi.mocked(api.getSoftwareChanges).mockResolvedValue({ days: 90, since: "2026-08-01T00:00:00Z", changes: [{
      ...stretch({ software_version: "R82", window_start: "2026-10-30T10:00:00Z", window_end: "2026-11-01T10:00:00Z", previous_software_version: "R81.20" }),
      hostname: "FW-TANGO-04", from_version: "R81.20", from_hotfix_level: null, to_version: "R82", to_hotfix_level: "Take 10" }] });
    themed(<RecentUpgradesPanel />);
    const table = await screen.findByRole("table", { name: "Recent software upgrades" });
    expect(within(table).getByText("FW-TANGO-04")).toBeInTheDocument();
    expect(within(table).getByText("R81.20")).toBeInTheDocument();
    expect(within(table).getByText("R82 (Take 10)")).toBeInTheDocument();
  });
  it("shows the empty row", async () => {
    vi.mocked(api.getSoftwareChanges).mockResolvedValue({ days: 90, since: "2026-08-01T00:00:00Z", changes: [] });
    themed(<RecentUpgradesPanel />);
    expect(await screen.findByText("No software changes observed in this period")).toBeInTheDocument();
  });
});
