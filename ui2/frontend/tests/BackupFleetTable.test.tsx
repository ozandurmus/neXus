import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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

describe("Size column sort and the large-backup marker (Product Owner, 2026-11-02)", () => {
  const MB = 1024 * 1024;
  // 131 Check Point targets: 120 with a ~0.2 GB archive, 11 without one, device 77 with a 28 GB archive on page 2 in hostname order.
  const estate = Array.from({ length: 131 }, (_, i) => device(i + 1));
  const archives = Array.from({ length: 120 }, (_, i) => artefact(i + 1, { size_bytes: (160 + (i % 7)) * MB }));
  archives[76] = artefact(77, { size_bytes: 28_537 * MB });
  // the Device cell's hostname (the vendor badge's letters sit beside it in the same cell)
  const names = () => screen.getAllByRole("row").slice(1).map((r) => within(r).getByText(/^FW-SYNTH-\d{3}$/).textContent);
  const sizeHeader = () => screen.getByRole("button", { name: "Size" });

  it("sorts the whole filtered set by size, largest first, then smallest first with no-archive rows last, then back to hostname order", async () => {
    stub(estate, archives);
    render(as(adminPlanes, <BackupScreen />));
    await screen.findByText("FW-SYNTH-001");
    expect(screen.queryByText("FW-SYNTH-077")).toBeNull();
    fireEvent.click(sizeHeader());
    expect(names()[0]).toBe("FW-SYNTH-077");
    expect(within(screen.getAllByRole("row")[1]).getByText("28537.0 MB")).toBeInTheDocument();
    expect(screen.getByText("1–50 of 131")).toBeInTheDocument();
    expect(sizeHeader().closest("th")).toHaveAttribute("aria-sort", "descending");
    fireEvent.click(sizeHeader());
    expect(sizeHeader().closest("th")).toHaveAttribute("aria-sort", "ascending");
    // smallest first: the 160 MB archives lead ...
    expect(within(screen.getAllByRole("row")[1]).getByText("160.0 MB")).toBeInTheDocument();
    // ... and the eleven rows without an archive sit at the very end, after the 28 GB one
    fireEvent.click(screen.getByRole("button", { name: "Go to next page" }));
    fireEvent.click(screen.getByRole("button", { name: "Go to next page" }));
    expect(screen.getByText("101–131 of 131")).toBeInTheDocument();
    const last = names();
    expect(last.slice(-11)).toEqual(Array.from({ length: 11 }, (_, i) => `FW-SYNTH-${121 + i}`));
    expect(last[last.length - 12]).toBe("FW-SYNTH-077");
    fireEvent.click(sizeHeader());
    expect(sizeHeader().closest("th")).not.toHaveAttribute("aria-sort");
    expect(screen.getByText("1–50 of 131")).toBeInTheDocument();
    expect(names()[0]).toBe("FW-SYNTH-001");
  });

  it("returns to the first page when the sort changes", async () => {
    stub(estate, archives);
    render(as(adminPlanes, <BackupScreen />));
    await screen.findByText("FW-SYNTH-001");
    fireEvent.click(screen.getByRole("button", { name: "Go to next page" }));
    expect(screen.getByText("51–100 of 131")).toBeInTheDocument();
    fireEvent.click(sizeHeader());
    expect(screen.getByText("1–50 of 131")).toBeInTheDocument();
  });

  it("marks the archive that is 10x its vendor's median and at least 1 GiB, says why on hover, and counts it on the Large backups chip", async () => {
    stub(estate, archives);
    render(as(adminPlanes, <BackupScreen />));
    await screen.findByText("FW-SYNTH-001");
    expect(screen.getByText("Large backups · 1")).toBeInTheDocument();
    expect(screen.queryByText("Large")).toBeNull();
    fireEvent.click(screen.getByText("Large backups · 1"));
    expect(screen.getByText("1–1 of 1")).toBeInTheDocument();
    const row = screen.getByText("FW-SYNTH-077").closest("tr")!;
    const marker = within(row).getByText("Large").closest("[data-pill]") as HTMLElement;
    expect(marker).toHaveStyle({ backgroundColor: TONE_COLORS.warn.bg });
    expect(marker.closest("td")).toHaveAttribute("title", "29,923,213,312 bytes");
    // a Check Point median of 163 MB (120 archives of 160..166 MB) against 28,537 MB
    expect(within(row).getByText("28537.0 MB")).toBeInTheDocument();
    fireEvent.mouseOver(within(row).getByText("Large"));
    expect(await screen.findByRole("tooltip")).toHaveTextContent("175.1x the Check Point fleet median (163.0 MB)");
  });

  it("shows the Large backups chip at 0 and flags nothing when every archive is ordinary", async () => {
    stub([device(1), device(2), device(3)], [artefact(1), artefact(2), artefact(3)]);
    render(as(adminPlanes, <BackupScreen />));
    await screen.findByText("FW-SYNTH-001");
    expect(screen.getByText("Large backups · 0")).toBeInTheDocument();
    expect(screen.queryByText("Large")).toBeNull();
    fireEvent.click(screen.getByText("Large backups · 0"));
    expect(screen.getByText("No device matches the filter.")).toBeInTheDocument();
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
    const neutralBackground = getComputedStyle(neutral.closest("[data-pill]")!).backgroundColor;
    unmount();
    stub([device(1)], [artefact(1)], { active_major_deviations: [], total_deviations_checked: 3 });
    render(as(adminPlanes, <BackupScreen />));
    const attention = await screen.findByText("Changed since last");
    expect(screen.queryByText("Bytes changed")).toBeNull();
    expect(getComputedStyle(attention.closest("[data-pill]")!).backgroundColor).not.toBe(neutralBackground);
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
    const locked = screen.getByText("Target · locked").closest("[data-pill]") as HTMLElement;
    expect(locked).toHaveStyle({ backgroundColor: TONE_COLORS.neutral.bg });
    expect(locked).not.toHaveStyle({ backgroundColor: TONE_COLORS.ok.bg });
  });
});

describe("every header sorts the whole filtered set, not the page (Product Owner, 2026-11-04)", () => {
  // 120 targets named without zero padding, so plain text order (FW-TANGO-1, FW-TANGO-10, FW-TANGO-100 ...) differs
  // from numeric order; every third device standalone; an archive on all but every ninth; an attempt on all but every tenth.
  const estate = Array.from({ length: 120 }, (_, k) => {
    const i = k + 1;
    return device(i, { hostname: `FW-TANGO-${i}`, cluster_member_ref: i % 3 === 0 ? null : `CLS-ROMEO-${i % 7}` });
  });
  const archives = estate.flatMap((_, k) => (k % 9 === 8 ? [] : [artefact(k + 1, { collected_at: new Date(Date.UTC(2026, 9, 1) + ((k * 53) % 120) * 3_600_000).toISOString() })]));
  function stubWithAttempts() {
    stub(estate, archives);
    const base = vi.mocked(fetch);
    const inner = base.getMockImplementation()!;
    base.mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.startsWith("/api/v2/jobs")) {
        const n = Number(new URLSearchParams(url.slice(url.indexOf("?"))).get("device_id")?.slice(1));
        if (n % 10 === 0) return json({ items: [], total: 0, page: 1, page_size: 1 });
        const submitted_at = new Date(Date.UTC(2026, 9, 1) + n * 3_600_000).toISOString();
        return json({ items: [{ job_id: `job-${n}`, job_type: "backup", target_device_id: `d${n}`, submitted_at, state: "COMPLETED", terminal_reason: "COMPLETED", has_transcript: false }], total: 1, page: 1, page_size: 1 });
      }
      return inner(input);
    });
  }
  const rows = () => screen.getAllByRole("row").slice(1);
  const names = () => rows().map((r) => within(r).getByText(/^FW-TANGO-\d+$/).textContent);
  const cell = (row: HTMLElement, index: number) => within(row).getAllByRole("cell")[index];
  const header = (name: string) => screen.getByRole("button", { name });
  const next = () => fireEvent.click(screen.getByRole("button", { name: "Go to next page" }));

  it("orders Device over all 120 rows with numeric collation, page 2 continues it, descending flips it, the third click restores hostname order", async () => {
    stubWithAttempts();
    render(as(adminPlanes, <BackupScreen />));
    await screen.findByText("FW-TANGO-1");
    expect(names().slice(0, 3)).toEqual(["FW-TANGO-1", "FW-TANGO-10", "FW-TANGO-100"]);
    fireEvent.click(header("Device"));
    expect(header("Device").closest("th")).toHaveAttribute("aria-sort", "ascending");
    expect(names()).toEqual(Array.from({ length: 50 }, (_, i) => `FW-TANGO-${i + 1}`));
    next();
    expect(screen.getByText("51–100 of 120")).toBeInTheDocument();
    expect(names()).toEqual(Array.from({ length: 50 }, (_, i) => `FW-TANGO-${i + 51}`));
    fireEvent.click(header("Device"));
    expect(header("Device").closest("th")).toHaveAttribute("aria-sort", "descending");
    expect(screen.getByText("1–50 of 120")).toBeInTheDocument();
    expect(names().slice(0, 3)).toEqual(["FW-TANGO-120", "FW-TANGO-119", "FW-TANGO-118"]);
    fireEvent.click(header("Device"));
    expect(header("Device").closest("th")).not.toHaveAttribute("aria-sort");
    expect(names().slice(0, 3)).toEqual(["FW-TANGO-1", "FW-TANGO-10", "FW-TANGO-100"]);
  });

  it("puts standalone devices last under Cluster whichever way it points, and ties keep hostname order", async () => {
    stubWithAttempts();
    render(as(adminPlanes, <BackupScreen />));
    await screen.findByText("FW-TANGO-1");
    fireEvent.click(header("Cluster"));
    expect(cell(rows()[0], 2)).toHaveTextContent("CLS-ROMEO-0");
    // the CLS-ROMEO-0 rows (7, 14, 28 ... 112, 119) keep the table's hostname order among themselves
    expect(names().slice(0, 3)).toEqual(["FW-TANGO-112", "FW-TANGO-119", "FW-TANGO-14"]);
    next(); next();
    expect(screen.getByText("101–120 of 120")).toBeInTheDocument();
    for (const row of rows()) expect(cell(row, 2)).toHaveTextContent("standalone");
    fireEvent.click(header("Cluster"));
    expect(cell(rows()[0], 2)).toHaveTextContent("CLS-ROMEO-6");
    next(); next();
    for (const row of rows()) expect(cell(row, 2)).toHaveTextContent("standalone");
  });

  it("orders Latest archive and Latest attempt by time over every row, with rows that have none last", async () => {
    stubWithAttempts();
    render(as(adminPlanes, <BackupScreen />));
    await screen.findByText("FW-TANGO-1");
    fireEvent.click(header("Latest archive"));
    // the oldest archive: k with (k * 53) % 120 === 0 and an archive, k = 0 -> FW-TANGO-1
    expect(names()[0]).toBe("FW-TANGO-1");
    next(); next();
    // the 13 devices without an archive (every ninth) close the list
    expect(rows().slice(-13).every((r) => within(r).getByText("No archive"))).toBe(true);
    fireEvent.click(header("Latest attempt"));
    expect(screen.getByText("1–50 of 120")).toBeInTheDocument();
    next(); next();
    // oldest attempt first over the 108 devices that have one: rows 101-108 of the list are devices 112-119, then the 12 without
    await waitFor(() => {
      expect(names().slice(0, 8)).toEqual(["FW-TANGO-112", "FW-TANGO-113", "FW-TANGO-114", "FW-TANGO-115", "FW-TANGO-116", "FW-TANGO-117", "FW-TANGO-118", "FW-TANGO-119"]);
    }, { timeout: 4000 });
    expect(rows().slice(-12).map((r) => within(r).queryByText("No attempt") !== null)).toEqual(Array(12).fill(true));
    fireEvent.click(header("Latest attempt"));
    expect(header("Latest attempt").closest("th")).toHaveAttribute("aria-sort", "descending");
    expect(names().slice(0, 2)).toEqual(["FW-TANGO-119", "FW-TANGO-118"]);
    next(); next();
    expect(rows().slice(-12).map((r) => within(r).queryByText("No attempt") !== null)).toEqual(Array(12).fill(true));
  });

  it("returns to page 1 when any header changes the order, and the Large backups chip still leads with the largest", async () => {
    stubWithAttempts();
    render(as(adminPlanes, <BackupScreen />));
    await screen.findByText("FW-TANGO-1");
    next();
    expect(screen.getByText("51–100 of 120")).toBeInTheDocument();
    fireEvent.click(header("Model · version"));
    expect(screen.getByText("1–50 of 120")).toBeInTheDocument();
    next();
    fireEvent.click(header("Validation"));
    expect(screen.getByText("1–50 of 120")).toBeInTheDocument();
    // Size keeps its own first step: largest first, without a chip
    fireEvent.click(header("Size"));
    expect(header("Size").closest("th")).toHaveAttribute("aria-sort", "descending");
  });
});
