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
