import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { ClusterConfigurationDetail, DeviceConfigurationDetail, clusterEvidenceCsv } from "../src/screens/ConfigurationDetail";
import { projectCheckPoint, projectCluster } from "../src/screens/configurationProjection";
import type { DeviceSummary } from "../src/auth/adminApi";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status });
}

function withTheme(node: React.ReactElement) {
  return <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
}

const CP_A = `# evidence (redacted)
set hostname FW-TANGO-01
set dns primary 192.0.2.53
set dns secondary 192.0.2.54
set ntp active on
set ntp server primary 192.0.2.10 version 4
set ntp server secondary 192.0.2.11 version 4
set interface eth1-01 ipv4-address 192.0.2.1 mask-length 28
`;
const CP_B = CP_A.replace("FW-TANGO-01", "FW-TANGO-02").replace("192.0.2.1 mask-length 28", "192.0.2.2 mask-length 28")
  .replace("set ntp server secondary 192.0.2.11 version 4", "set ntp server secondary 192.0.2.99 version 4");

const member = (id: string, extra: Partial<DeviceSummary>): DeviceSummary => ({
  device_id: id, vendor_hint: "check_point", enrollment_state: "ENROLLED", hostname: null, model: "Quantum 6600",
  software_version: "R81.20", ha_role: null, cluster_member_ref: "CLS-ROMEO-01", ...extra,
});
// M2 first on purpose.
const MEMBERS = [
  member("m2", { hostname: "FW-TANGO-02", ha_role: "ACTIVE", virtual_systems: "vs-a, vs-b" }),
  member("m1", { hostname: "FW-TANGO-01", ha_role: "PASSIVE", virtual_systems: "vs-a, vs-b" }),
];

function stubConfigs() {
  vi.stubGlobal("fetch", vi.fn().mockImplementation((input: RequestInfo | URL) => {
    const url = String(input);
    const cfg = { collected_at: "2026-09-22T22:57:54Z", vendor: "check_point", read_kind: "show_configuration", canonical_hash: "h",
      change_state: "unchanged", withheld_line_count: 0, sanitized_text_available: true, index: [], overrides: [], supplementary_runs: [] };
    if (url === "/devices/m1/configuration") return Promise.resolve(jsonResponse(200, { ...cfg, device_id: "m1" }));
    if (url === "/devices/m2/configuration") return Promise.resolve(jsonResponse(200, { ...cfg, device_id: "m2" }));
    if (url === "/devices/m1/configuration/text") return Promise.resolve(new Response(CP_A, { status: 200 }));
    if (url === "/devices/m2/configuration/text") return Promise.resolve(new Response(CP_B, { status: 200 }));
    return Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }));
  }));
}

describe("Configuration cluster detail after the Fable review", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("orders members M1, M2 with neutral role chips and links the cluster across screens", async () => {
    stubConfigs();
    const { container } = render(withTheme(<ClusterConfigurationDetail clusterRef="CLS-ROMEO-01" members={MEMBERS} />));
    await waitFor(() => expect(screen.getByText("1 difference")).toBeInTheDocument());
    const chips = [...container.querySelectorAll("[data-member-chip]")].map((el) => el.getAttribute("data-member-chip"));
    expect(chips).toEqual(["m1", "m2"]);
    const m1 = container.querySelector('[data-member-chip="m1"]') as HTMLElement;
    expect(within(m1).getByText("PASSIVE")).toHaveAttribute("data-role", "PASSIVE");
    const strip = screen.getByRole("navigation", { name: "This cluster on other screens" });
    expect(within(strip).getByRole("link", { name: "Backups" })).toHaveAttribute("href", "?screen=backups&q=CLS-ROMEO-01");
    // Configuration is a tab of the device screen now (PO 2026-09-25): the strip links the device screen itself.
    expect(within(strip).getByRole("link", { name: "Device screen" })).toHaveAttribute("href", "?screen=inventory&cluster_ref=CLS-ROMEO-01");
    // Setting columns run M1, M2 as well.
    const heads = screen.getAllByRole("columnheader").map((h) => h.textContent);
    expect(heads.indexOf("FW-TANGO-01")).toBeLessThan(heads.indexOf("FW-TANGO-02"));
  });

  it("defaults Differences only to on when members differ, lists the differences as jump links, and folds sections into an accordion", async () => {
    stubConfigs();
    render(withTheme(<ClusterConfigurationDetail clusterRef="CLS-ROMEO-01" members={MEMBERS} />));
    await waitFor(() => expect(screen.getByText("1 difference")).toBeInTheDocument());
    const toggle = screen.getByRole("checkbox", { name: "Differences only" });
    expect(toggle).toBeChecked();

    const strip = screen.getByRole("navigation", { name: "Differences" });
    expect(within(strip).getByRole("button", { name: "NTP › Secondary NTP Server" })).toBeInTheDocument();

    // Only the section holding the difference, expanded, with its counts.
    const ntp = screen.getByRole("button", { name: /^▾\s*NTP\s*3 settings · 1 diff$/ });
    expect(ntp).toHaveAttribute("aria-expanded", "true");
    expect(screen.queryByRole("button", { name: /^▸\s*DNS/ })).toBeNull();

    // The full view is one toggle away: sections without a difference are there, collapsed.
    fireEvent.click(toggle);
    const dns = screen.getByRole("button", { name: /^▸\s*DNS\s*2 settings · 0 diff$/ });
    expect(dns).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByText("Primary DNS")).toBeNull();
    fireEvent.click(dns);
    expect(screen.getByText("Primary DNS")).toBeInTheDocument();

    // A jump link opens its section.
    fireEvent.click(screen.getByRole("button", { name: /^▾\s*NTP/ }));
    expect(screen.queryByText("Secondary NTP Server", { selector: "td *, td" })).toBeNull();
    fireEvent.click(within(strip).getByRole("button", { name: "NTP › Secondary NTP Server" }));
    expect(screen.getByRole("button", { name: /^▾\s*NTP/ })).toHaveAttribute("aria-expanded", "true");
  });

  it("renders the per-member identity table with virtual systems collapsed to the cluster", async () => {
    stubConfigs();
    render(withTheme(<ClusterConfigurationDetail clusterRef="CLS-ROMEO-01" members={MEMBERS} />));
    const table = await screen.findByRole("table", { name: "Member identity" });
    const rows = within(table).getAllByRole("row").slice(1);
    expect(within(rows[0]).getByText("FW-TANGO-01")).toBeInTheDocument();
    const vs = within(rows[0]).getByRole("button", { name: /2 · same as cluster/ });
    expect(within(rows[0]).queryByText("vs-a, vs-b")).toBeNull();
    fireEvent.click(vs);
    expect(within(rows[0]).getByText("vs-a, vs-b")).toBeInTheDocument();
    // The full list stays in the platform identity line.
    expect(screen.getByTestId("platform-identity-line")).toHaveTextContent("2 virtual system(s) (VSX): vs-a, vs-b");
  });

  it("exports the cluster's configuration evidence as CSV from the data on screen, with the ORIGIN column", async () => {
    stubConfigs();
    const createObjectURL = vi.fn().mockReturnValue("blob:x");
    const revokeObjectURL = vi.fn();
    vi.stubGlobal("URL", Object.assign(URL, { createObjectURL, revokeObjectURL }));
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    render(withTheme(<ClusterConfigurationDetail clusterRef="CLS-ROMEO-01" members={MEMBERS} />));
    await waitFor(() => expect(screen.getByText("1 difference")).toBeInTheDocument());
    const fetchCalls = (fetch as unknown as { mock: { calls: unknown[] } }).mock.calls.length;
    fireEvent.click(screen.getByRole("button", { name: "Export evidence (CSV)" }));
    expect(createObjectURL).toHaveBeenCalledTimes(1);
    expect(click).toHaveBeenCalledTimes(1);
    click.mockRestore();
    expect((fetch as unknown as { mock: { calls: unknown[] } }).mock.calls.length).toBe(fetchCalls);
  });
});

describe("clusterEvidenceCsv", () => {
  it("writes one row per setting with member values, ORIGIN, the DIFF mark and the export time", () => {
    const cluster = projectCluster([{ id: "m1", projection: projectCheckPoint(CP_A) }, { id: "m2", projection: projectCheckPoint(CP_B) }]);
    const csv = clusterEvidenceCsv({
      clusterTitle: "CLS-ROMEO-01", clusterRef: "CLS-ROMEO-01",
      members: [{ id: "m1", name: "FW-TANGO-01", role: "PASSIVE" }, { id: "m2", name: "FW-TANGO-02", role: "ACTIVE" }],
      cluster, exportedAt: "2026-09-23T01:02:03.456Z",
    });
    const lines = csv.trim().split("\r\n");
    expect(lines[0]).toBe("cluster,cluster_ref,section,setting,FW-TANGO-01 (PASSIVE),FW-TANGO-02 (ACTIVE),origin,difference,exported_at_utc");
    expect(lines).toContain("CLS-ROMEO-01,CLS-ROMEO-01,NTP,Secondary NTP Server,192.0.2.11 version 4,192.0.2.99 version 4,LOCAL,DIFF,2026-09-23T01:02:03.456Z");
    expect(lines.some((l) => l.includes(",Hostname,FW-TANGO-01,FW-TANGO-02,MEMBER,EXPECTED (member-specific),"))).toBe(true);
  });
});

describe("Configuration device detail", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows a reported zero content version as reported and an unread one as UNKNOWN; one timestamp format", async () => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(404, { error: "NOT_FOUND" }))));
    render(withTheme(<DeviceConfigurationDetail device={{
      device_id: "p1", vendor_hint: "palo_alto", enrollment_state: "ENROLLED", hostname: "FW-BRAVO-02", model: "PA-440",
      software_version: "11.1.4", ha_role: "active", cluster_member_ref: null,
      content_versions: { threat: "0", url: "0000.00.00.000" }, platform_facts_observed_at: "2026-09-22T22:57:54.123456Z",
    }} />));
    await waitFor(() => expect(screen.getAllByText("(as reported)")).toHaveLength(2));
    expect(screen.getByText("2026-09-23 01:57:54")).toBeInTheDocument();
    expect(screen.getAllByText("ACTIVE")[0]).toHaveAttribute("data-role", "ACTIVE");
  });
});
