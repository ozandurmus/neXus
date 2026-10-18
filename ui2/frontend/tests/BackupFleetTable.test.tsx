import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../src/theme/m3Theme";
import { TONE_COLORS } from "../src/shell/tone";
import { SessionContext, type SessionInfo } from "../src/auth/SessionContext";
import { JobWindowContext } from "../src/shell/JobWindow";
import { BACKUP_PAGE_SIZE, BackupScreen, FLEET_COLUMNS } from "../src/screens/BackupScreen";

/**
 * Backups fleet table (UI review 2026-10-18, F-4): one line per row, LATEST ATTEMPT as its own column, every action in
 * the row's menu with refused ones disabled and suffixed, 50 rows a page, a read-only pill instead of a live-looking
 * switch for a role that cannot change the target, a neutral deviation pill until a comparison has run, KPI cards first.
 */
const adminPlanes = ["Devices", "Config", "Compliance", "Operations", "Admin"];
const viewerPlanes = ["Devices", "Config", "Compliance"];
const session = (permissions: readonly string[]): SessionInfo => ({ displayName: "Synthetic user", roleTokens: [], permissions, onSignOut: () => {} });
const as = (permissions: readonly string[], node: React.ReactElement, windowView = { open: true, label: null as string | null }) => (
  <ThemeProvider theme={m3Theme}>
    <SessionContext.Provider value={session(permissions)}>
      <JobWindowContext.Provider value={windowView}>{node}</JobWindowContext.Provider>
    </SessionContext.Provider>
  </ThemeProvider>
);
const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));

const device = (i: number, over: Record<string, unknown> = {}) => ({
  device_id: `d${i}`, role: "gateway", vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: `FW-SYNTH-${String(i).padStart(3, "0")}`,
  model: "Check Point 6900", software_version: "R81.20", cluster_member_ref: null, backup_target: true, ...over,
});
const artefact = (i: number, over: Record<string, unknown> = {}) => ({
  artefact_id: `a${i}`, device_id: `d${i}`, collected_at: "2026-10-18T02:30:00Z", size_bytes: 169993690, digest_prefix: "abc", validation_level: "V1",
  deviation_state: "changed", vendor: "check_point", ...over,
});

function stub(devices: object[], backups: object[], deviations = { active_major_deviations: [], total_deviations_checked: 0 }) {
  vi.stubGlobal("fetch", vi.fn((url: string) => {
    if (url.startsWith("/api/v2/jobs")) {
      return json({ items: [{ job_id: "job-1", job_type: "backup", target_device_id: "d1", submitted_at: "2026-10-18T02:03:11Z", state: "COMPLETED", terminal_reason: "COMPLETED", has_transcript: false }], total: 1, page: 1, page_size: 1 });
    }
    if (url.startsWith("/devices")) return json({ devices });
    if (url.startsWith("/api/v2/backups/policies")) return json({ schedule_enabled: true, daily_backup_cron: "0 2 * * *", backup_retention_days: 7, snapshot_retention_depth: 1 });
    if (url.startsWith("/api/v2/backups/deviations")) return json(deviations);
    if (url.startsWith("/backups")) return json({ backups, baselines: {} });
    return json({});
  }));
}

afterEach(() => vi.unstubAllGlobals());

describe("one line per row, every action in the row menu", () => {
  it("has a LATEST ATTEMPT column and no action button in the row but the menu trigger", async () => {
    stub([device(1)], [artefact(1)]);
    render(as(adminPlanes, <BackupScreen />));
    const row = (await screen.findByText("FW-SYNTH-001")).closest("tr")!;
    const table = row.closest("table")!;
    expect(within(table).getByRole("columnheader", { name: "Latest attempt" })).toBeInTheDocument();
    // the newest attempt sits in its own cell, one compact line, with one status word
    expect(await within(row).findByText("Completed")).toBeInTheDocument();
    expect(within(row).queryByText("COMPLETED: COMPLETED")).toBeNull();
    expect(within(row).getAllByRole("button").map((b) => b.getAttribute("aria-label"))).toEqual(["Actions for FW-SYNTH-001"]);
    // every cell is held to one line
    for (const cell of Array.from(row.querySelectorAll("td"))) expect(getComputedStyle(cell).whiteSpace).toBe("nowrap");
  });

  it("keeps all six actions in the menu and runs the one chosen", async () => {
    stub([device(1)], [artefact(1)]);
    render(as(adminPlanes, <BackupScreen />));
    const row = (await screen.findByText("FW-SYNTH-001")).closest("tr")!;
    fireEvent.click(within(row).getByRole("button", { name: "Actions for FW-SYNTH-001" }));
    const menu = await screen.findByRole("menu", { name: "Actions for FW-SYNTH-001" });
    expect(within(menu).getAllByRole("menuitem").map((item) => item.textContent)).toEqual(["Backup Now", "Snapshot", "History", "Contents", "Compare", "Download"]);
    for (const item of within(menu).getAllByRole("menuitem")) expect(item).not.toHaveAttribute("aria-disabled", "true");
    fireEvent.click(within(menu).getByRole("menuitem", { name: "History" }));
    expect(await screen.findByRole("dialog", { name: /Backup history: FW-SYNTH-001/ })).toBeInTheDocument();
  });

  it("offers Contents, Compare and Download only as disabled entries that say why when the device has no archive", async () => {
    stub([device(1)], []);
    render(as(adminPlanes, <BackupScreen />));
    const row = (await screen.findByText("FW-SYNTH-001")).closest("tr")!;
    fireEvent.click(within(row).getByRole("button", { name: "Actions for FW-SYNTH-001" }));
    for (const name of ["Contents", "Compare", "Download"]) {
      expect(await screen.findByRole("menuitem", { name: `${name} · No archive` })).toHaveAttribute("aria-disabled", "true");
    }
    expect(screen.getByRole("menuitem", { name: "Backup Now" })).not.toHaveAttribute("aria-disabled", "true");
    expect(screen.getByRole("menuitem", { name: "History" })).not.toHaveAttribute("aria-disabled", "true");
  });

  it.each([
    ["a read-only role", viewerPlanes, { open: true, label: null }, "Role: not permitted"],
    ["a role that can act outside the window", adminPlanes, { open: false, label: "Next window: 12:00" }, "Next window: 12:00"],
  ])("suffixes the device-job entries for %s with one rule", async (_name, planes, windowView, suffix) => {
    stub([device(1)], [artefact(1)]);
    render(as(planes, <BackupScreen />, windowView));
    const row = (await screen.findByText("FW-SYNTH-001")).closest("tr")!;
    fireEvent.click(within(row).getByRole("button", { name: "Actions for FW-SYNTH-001" }));
    for (const action of ["Backup Now", "Snapshot"]) {
      expect(await screen.findByRole("menuitem", { name: `${action} · ${suffix}` })).toHaveAttribute("aria-disabled", "true");
    }
    // reading entries are never gated by the window or the role plane
    expect(screen.getByRole("menuitem", { name: "History" })).not.toHaveAttribute("aria-disabled", "true");
    expect(screen.getByRole("menuitem", { name: "Contents" })).not.toHaveAttribute("aria-disabled", "true");
  });

  it("keeps Delete and MDS export in the menu where they apply", async () => {
    const mds = device(2, { role: "management_server", hostname: "FW-SYNTH-MDS" });
    stub([device(1), mds], [artefact(1), artefact(2), artefact(9, { device_id: "removed-device" })]);
    render(as(adminPlanes, <BackupScreen />));
    const mdsRow = (await screen.findByText("FW-SYNTH-MDS")).closest("tr")!;
    fireEvent.click(within(mdsRow).getByRole("button", { name: "Actions for FW-SYNTH-MDS" }));
    expect(await screen.findByRole("menuitem", { name: "MDS export" })).toBeInTheDocument();
    fireEvent.keyDown(screen.getByRole("menu"), { key: "Escape" });
    const orphanButton = (await screen.findAllByRole("button", { name: /^Actions for / })).find((b) => /removed|^Actions for removed/i.test(b.getAttribute("aria-label") ?? ""));
    expect(orphanButton).toBeDefined();
    fireEvent.click(orphanButton!);
    expect(await screen.findByRole("menuitem", { name: "Delete" })).toBeInTheDocument();
  });
});

describe("pagination at 50", () => {
  const many = Array.from({ length: 131 }, (_, i) => device(i + 1));

  it("shows 1–50 of 131 and moves page by page without losing a row", async () => {
    stub(many, []);
    render(as(adminPlanes, <BackupScreen />));
    await screen.findByText("FW-SYNTH-001");
    expect(BACKUP_PAGE_SIZE).toBe(50);
    expect(screen.getByText("1–50 of 131")).toBeInTheDocument();
    const rows = () => screen.getAllByRole("row").slice(1);
    expect(rows()).toHaveLength(50);
    expect(screen.queryByText("FW-SYNTH-051")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Go to next page" }));
    expect(screen.getByText("51–100 of 131")).toBeInTheDocument();
    expect(screen.getByText("FW-SYNTH-051")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Go to next page" }));
    expect(screen.getByText("101–131 of 131")).toBeInTheDocument();
    expect(rows()).toHaveLength(31);
    expect(screen.getByText("FW-SYNTH-131")).toBeInTheDocument();
  });

  it("returns to the first page when the filter changes", async () => {
    stub(many, []);
    render(as(adminPlanes, <BackupScreen />));
    await screen.findByText("FW-SYNTH-001");
    fireEvent.click(screen.getByRole("button", { name: "Go to next page" }));
    expect(screen.getByText("51–100 of 131")).toBeInTheDocument();
    fireEvent.change(screen.getByRole("textbox", { name: "Filter the backup fleet" }), { target: { value: "SYNTH-12" } });
    expect(screen.getByText("1–10 of 10")).toBeInTheDocument();
  });
});

describe("the target toggle, the deviation pill and the KPI cards", () => {
  it("is a switch for a role that can change it and a read-only pill for one that cannot", async () => {
    stub([device(1), device(2, { backup_target: false })], []);
    const { unmount } = render(as(adminPlanes, <BackupScreen />));
    expect((await screen.findAllByRole("checkbox", { name: /^Backup target / }))).toHaveLength(2);
    unmount();
    render(as(viewerPlanes, <BackupScreen />));
    await screen.findByText("FW-SYNTH-001");
    expect(screen.queryAllByRole("checkbox")).toHaveLength(0);
    expect(screen.getByRole("img", { name: "Backup target FW-SYNTH-001: on, locked for your role" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "Backup target FW-SYNTH-002: off, locked for your role" })).toBeInTheDocument();
    expect(screen.getByText("Target · locked")).toBeInTheDocument();
    expect(screen.getByText("Not a target · locked")).toBeInTheDocument();
  });

  it("says Bytes changed (neutral) until a comparison has run, then Changed since last", async () => {
    stub([device(1)], [artefact(1)], { active_major_deviations: [], total_deviations_checked: 0 });
    const { unmount } = render(as(adminPlanes, <BackupScreen />));
    const neutral = await screen.findByText("Bytes changed");
    expect(screen.queryByText("Changed since last")).toBeNull();
    const neutralBackground = getComputedStyle(neutral.closest(".MuiChip-root")!).backgroundColor;
    unmount();
    stub([device(1)], [artefact(1)], { active_major_deviations: [], total_deviations_checked: 3 });
    render(as(adminPlanes, <BackupScreen />));
    const attention = await screen.findByText("Changed since last");
    expect(screen.queryByText("Bytes changed")).toBeNull();
    expect(getComputedStyle(attention.closest(".MuiChip-root")!).backgroundColor).not.toBe(neutralBackground);
  });

  it("puts the KPI cards above the fleet table", async () => {
    stub([device(1)], [artefact(1)]);
    render(as(adminPlanes, <BackupScreen />));
    const card = (await screen.findByText("Targets with a stored backup")).closest(".MuiCard-root")!;
    const table = screen.getByRole("table");
    // eslint-disable-next-line no-bitwise
    expect(card.compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});

describe("fixed column widths (post-release review 2026-10-18, R3 / R11)", () => {
  it("lays the fleet table out with fixed columns so LATEST ATTEMPT keeps its room, ages with the exact stamp on hover, neutral locked pill", async () => {
    stub([device(1)], [artefact(1)]);
    render(as(viewerPlanes, <BackupScreen />));
    const row = (await screen.findByText("FW-SYNTH-001")).closest("tr")!;
    const table = row.closest("table")!;
    expect(table).toHaveStyle({ tableLayout: "fixed" });
    expect(Array.from(table.querySelectorAll("colgroup col")).map((col) => (col as HTMLElement).style.width)).toEqual(FLEET_COLUMNS.map(([, width]) => width));
    expect(FLEET_COLUMNS.map(([, width]) => Number.parseFloat(width)).reduce((n, w) => n + w, 0)).toBe(100);
    // the archive and the attempt cells show the age; the exact GMT+3 stamp and the UTC value are on hover
    const ages = within(row).getAllByText(/ago$|^just now$/);
    expect(ages.length).toBeGreaterThanOrEqual(1);
    for (const age of ages) expect(age).toHaveAttribute("title", expect.stringMatching(/^2026-10-18 \d\d:\d\d GMT\+3 · 2026-10-18T/));
    expect(await within(row).findByText("Completed")).toBeInTheDocument();
    // "Target · locked" is a neutral fact, never green (R11)
    const locked = screen.getByText("Target · locked").closest(".MuiChip-root") as HTMLElement;
    expect(locked).toHaveStyle({ backgroundColor: TONE_COLORS.neutral.bg });
    expect(locked).not.toHaveStyle({ backgroundColor: TONE_COLORS.ok.bg });
  });
});
