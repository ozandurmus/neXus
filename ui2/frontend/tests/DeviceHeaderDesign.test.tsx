import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme, m3 } from "../src/theme/m3Theme";
import { DeviceInventoryPanels, ClusterDetailPanels, ClusterLogsPanel, HeaderReadAges, identityBadge, IDENTITY_VERIFIED_BASIS } from "../src/screens/InventoryPanels";
import { DeviceConfigurationDetail, identityTiles } from "../src/screens/ConfigurationDetail";
import type { DeviceSummary, IdentityCheckView } from "../src/auth/adminApi";

/**
 * The device header (UI review 2026-10-18, F-9) and the identity badge (PO decision 2026-10-18). Synthetic ids and
 * documentation addresses only.
 */
const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));
const H = 3_600_000;
const ago = (ms: number) => new Date(Date.now() - ms).toISOString();
afterEach(() => vi.unstubAllGlobals());

const device = (over: Partial<DeviceSummary> = {}): DeviceSummary => ({
  device_id: "dev-1", hostname: "FW-SYNTH-01", vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED", model: "Quantum",
  software_version: "R81.20", ha_role: null, cluster_member_ref: null, ip_addresses: "192.0.2.10", ...over,
});
const check = (state: IdentityCheckView["state"], over: Partial<IdentityCheckView> = {}): IdentityCheckView => ({
  state, basis: state === "NOT_VERIFIED" ? "NONE" : "SSH_HOST_KEY", first_use: false, checked_at: state === "NOT_VERIFIED" ? null : "2026-10-18T03:00:00Z", ...over,
});
const configuration = (collectedAt: string | null) => ({ device_id: "dev-1", collected_at: collectedAt, vendor: "check_point", read_kind: "cp_show_configuration",
  canonical_hash: null, change_state: null, withheld_line_count: 0, sanitized_text_available: false, text_readable: false, index: [], overrides: [], supplementary_runs: [] });

function stub(routes: Record<string, () => Promise<Response>>) {
  const fetchMock = vi.fn((url: string) => (routes[url] ?? (() => json({ error: "NOT_FOUND" }, 404)))());
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}
const base = (d: DeviceSummary = device()): Record<string, () => Promise<Response>> => ({
  [`/devices/${d.device_id}/inventory`]: () => json({ device_id: d.device_id, collected_at: ago(2 * H), contexts: [] }),
  [`/devices/${d.device_id}/https-certificate`]: () => json({ available: false }),
  [`/devices/${d.device_id}/lifecycle`]: () => json({ device_id: d.device_id, risk: "LOW", status: "MATCHED", hardware: { catalog: { end_of_support: "2031-01-01" } }, software: {} }),
});
const mount = (node: React.ReactElement) => render(<ThemeProvider theme={m3Theme}>{node}</ThemeProvider>);
const withGrid = (d: DeviceSummary) => <DeviceInventoryPanels device={d} policyHref="?screen=policy&device_id=dev-1"
  configuration={<DeviceConfigurationDetail device={d} embedded />} />;

describe("read ages in the header", () => {
  it("says how long ago the platform and the configuration were read, with the exact times on hover", async () => {
    const d = device({ platform_facts_observed_at: ago(4 * H) });
    stub({ ...base(d), "/devices/dev-1/configuration": () => json(configuration(ago(22 * H))) });
    mount(withGrid(d));
    const ages = await screen.findByTestId("header-read-ages");
    await waitFor(() => expect(ages).toHaveTextContent("Platform read 4 h ago·Config read 22 h ago"));
    // 18 hours behind the platform read is not flagged: the configuration is read less often than the platform facts
    expect(within(ages).queryByText(/Config read/)?.closest(".MuiChip-root")).toBeNull();
  });

  it("flags a configuration read more than a day behind the platform read with an amber pill that still says the age", async () => {
    const d = device({ platform_facts_observed_at: ago(4 * H) });
    stub({ ...base(d), "/devices/dev-1/configuration": () => json(configuration(ago(23 * 24 * H))) });
    mount(withGrid(d));
    const pill = await screen.findByText("Config read 23 d ago");
    expect(pill.closest(".MuiChip-root")).toHaveStyle({ backgroundColor: m3.warningContainer });
  });

  it("makes no claim about the configuration when its read is refused, and says never read for a null time", async () => {
    const d = device({ platform_facts_observed_at: ago(H) });
    stub({ ...base(d), "/devices/dev-1/configuration": () => json({ error: "ACTION_REFUSED" }, 403) });
    mount(withGrid(d));
    const ages = await screen.findByTestId("header-read-ages");
    await waitFor(() => expect(screen.getByText("Platform read 1 h ago")).toBeInTheDocument());
    expect(ages).not.toHaveTextContent("Config");
  });

  it("words a platform that was never read and a configuration that was never read", () => {
    mount(<HeaderReadAges platformAt={null} config={{ at: null }} />);
    expect(screen.getByTestId("header-read-ages")).toHaveTextContent("Platform not read yet·Config not read yet");
  });

  it("shows no configuration age for a vendor with no configuration read", async () => {
    const d = device({ vendor_hint: "infoblox", role: "appliance", platform_facts_observed_at: ago(3 * H) });
    const fetchMock = stub(base(d));
    mount(<DeviceInventoryPanels device={d} />);
    expect(await screen.findByText("Platform read 3 h ago")).toBeInTheDocument();
    expect(screen.getByTestId("header-read-ages")).not.toHaveTextContent("Config");
    expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith("/configuration"))).toBe(false);
  });
});

describe("the identity badge follows the real match", () => {
  it.each([
    ["VERIFIED", "✓ Identity verified", m3.successContainer],
    ["MISMATCH", "Identity mismatch", m3.errorContainer],
    ["NOT_VERIFIED", "Not verified yet", m3.scHigh],
  ] as const)("shows %s as a word with its colour", async (state, label, background) => {
    const d = device({ identity_check: check(state) });
    stub(base(d));
    mount(<DeviceInventoryPanels device={d} />);
    const chip = (await screen.findByText(label)).closest(".MuiChip-root");
    expect(chip).toHaveStyle({ backgroundColor: background });
    // exactly one of the three, never the enrollment-only claim
    for (const other of ["✓ Identity verified", "Identity mismatch", "Not verified yet"].filter((x) => x !== label)) expect(screen.queryByText(other)).toBeNull();
  });

  it("is grey, not green, for an enrolled device that has no comparison on record (an older service sends none)", async () => {
    const d = device();
    stub(base(d));
    mount(<DeviceInventoryPanels device={d} />);
    expect(await screen.findByText("Not verified yet")).toBeInTheDocument();
    expect(screen.queryByText("✓ Identity verified")).toBeNull();
  });

  it("explains each state on hover, and why a pinned-on-first-use or Palo Alto device is not verified", () => {
    expect(identityBadge({ identity_check: check("VERIFIED") }).title).toMatch(/presented the pinned SSH host key/);
    expect(identityBadge({ identity_check: check("VERIFIED", { basis: "HTTPS_CERTIFICATE" }) }).title).toMatch(/pinned HTTPS certificate fingerprint/);
    expect(identityBadge({ identity_check: check("MISMATCH") }).title).toMatch(/did not match the pinned one/);
    expect(identityBadge({ identity_check: check("MISMATCH", { basis: "RECORDED_IDENTITY" }) }).title).toMatch(/differs from the identity recorded at enrollment/);
    expect(identityBadge({ identity_check: check("NOT_VERIFIED", { first_use: true }) }).title).toMatch(/pinned the fingerprint itself/);
    expect(identityBadge({ vendor_hint: "palo_alto", identity_check: check("NOT_VERIFIED") }).title).toMatch(/no pinned fingerprint/);
    expect(identityBadge({}).label).toBe("Not verified yet");
  });

  it("states on the Identity tab what the badge is based on, and keeps expiry out of it", async () => {
    const d = device({ identity_check: check("MISMATCH") });
    stub(base(d));
    mount(<DeviceInventoryPanels device={d} />);
    fireEvent.click(await screen.findByRole("tab", { name: "Identity" }));
    expect(await screen.findByText(IDENTITY_VERIFIED_BASIS)).toBeInTheDocument();
    expect(IDENTITY_VERIFIED_BASIS).toMatch(/Certificate expiry is not part of the check/);
  });
});

describe("the header card", () => {
  it("holds the Open assigned policy pill, with the read ages beside it", async () => {
    const d = device({ platform_facts_observed_at: ago(H) });
    stub(base(d));
    mount(<DeviceInventoryPanels device={d} policyHref="?screen=policy&device_id=dev-1" />);
    const header = await screen.findByTestId("entity-header");
    const link = within(header).getByRole("link", { name: "Open assigned policy" });
    expect(link).toHaveAttribute("href", "?screen=policy&device_id=dev-1");
    expect(within(header).getByTestId("header-read-ages")).toBeInTheDocument();
  });

  it("drops the loose Lifecycle line when the Platform identity grid holds the cell, and keeps it when there is no grid", async () => {
    const withConfig = device();
    stub({ ...base(withConfig), "/devices/dev-1/configuration": () => json(configuration(ago(H))) });
    const first = mount(withGrid(withConfig));
    await screen.findByTestId("entity-header");
    expect(screen.queryByText(/^Lifecycle:/)).toBeNull();
    first.unmount();
    const appliance = device({ vendor_hint: "infoblox", role: "appliance" });
    stub(base(appliance));
    mount(<DeviceInventoryPanels device={appliance} />);
    expect(await screen.findByText(/^Lifecycle: LOW/)).toBeInTheDocument();
  });
});

describe("the Platform identity grid carries Lifecycle and Reference", () => {
  it("has a Lifecycle cell and a Reference cell with Copy", async () => {
    const d = device();
    stub({ ...base(d), "/devices/dev-1/configuration": () => json(configuration(ago(H))) });
    mount(<DeviceConfigurationDetail device={d} embedded />);
    const lifecycle = (await screen.findByText("LIFECYCLE")).parentElement as HTMLElement;
    await waitFor(() => expect(lifecycle).toHaveTextContent("LOW · Hardware support: 2031-01-01"));
    const reference = screen.getByText("REFERENCE").parentElement as HTMLElement;
    expect(within(reference).getByText("dev-1")).toBeInTheDocument();
    expect(within(reference).getByRole("button", { name: "Copy" })).toBeInTheDocument();
    expect(identityTiles(d, null).map((t) => t.label).slice(-2)).toEqual(["Lifecycle", "Reference"]);
  });

  it("uses three words: Not applicable, Not read yet, and UNKNOWN only where a read was attempted", () => {
    const tiles = identityTiles(device({ role: "management_server", software_version: null }), null);
    const word = (label: string) => tiles.find((t) => t.label === label);
    expect(word("Policy installed")?.absentWord).toBe("Not applicable");
    expect(word("Serial number")?.absentWord).toBe("Not read yet");
    expect(word("Platform facts read")?.absentWord).toBe("Not read yet");
    expect(word("Software version")?.absentWord).toBeUndefined();
  });

  it("shows those words in the grid, and UNKNOWN for a value that was attempted and is unknown", async () => {
    const d = device({ software_version: null });
    stub({ ...base(d), "/devices/dev-1/configuration": () => json(configuration(null)) });
    mount(<DeviceConfigurationDetail device={d} embedded />);
    const serial = (await screen.findByText("SERIAL NUMBER")).parentElement as HTMLElement;
    expect(within(serial).getByText("Not read yet")).toBeInTheDocument();
    const software = screen.getByText("SOFTWARE VERSION").parentElement as HTMLElement;
    expect(within(software).getByText("UNKNOWN")).toBeInTheDocument();
  });
});

describe("the Identity tab leads to the grid", () => {
  it("opens the Configuration tab and scrolls to the Platform identity grid, and the tab keeps the certificate and the basis", async () => {
    const scroll = vi.fn();
    window.HTMLElement.prototype.scrollIntoView = scroll;
    const d = device({ identity_check: check("VERIFIED") });
    stub({ ...base(d), "/devices/dev-1/configuration": () => json(configuration(ago(H))) });
    mount(withGrid(d));
    fireEvent.click(await screen.findByRole("tab", { name: "Identity" }));
    expect(await screen.findByText(IDENTITY_VERIFIED_BASIS)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /Platform identity grid on the Configuration tab/ }));
    expect(await screen.findByRole("tab", { name: "Configuration", selected: true })).toBeInTheDocument();
    await waitFor(() => expect(scroll).toHaveBeenCalled());
    expect((scroll.mock.contexts[0] as HTMLElement).id).toBe("platform-identity-grid");
  });

  it("offers no link where there is no grid", async () => {
    const d = device({ vendor_hint: "infoblox", role: "appliance" });
    stub(base(d));
    mount(<DeviceInventoryPanels device={d} />);
    fireEvent.click(await screen.findByRole("tab", { name: "Identity" }));
    await screen.findByText(IDENTITY_VERIFIED_BASIS);
    expect(screen.queryByRole("button", { name: /Platform identity grid/ })).toBeNull();
  });
});

describe("the HA role line", () => {
  it("is gone where the badge already shows the role, leaving only the cluster mode", async () => {
    const d = device({ ha_role: "ACTIVE" });
    stub({ ...base(d), "/devices/dev-1/inventory": () => json({ device_id: "dev-1", collected_at: ago(H), contexts: [
      { context: "physical", vs_name: null, ha: { role: "ACTIVE", cluster_mode: "High Availability" }, interfaces: [{ name: "eth0", parent: null, kind: "physical", state: "up", addresses: [] }], routes: [] }] }) });
    mount(<DeviceInventoryPanels device={d} />);
    expect(await screen.findByText("Cluster mode: High Availability")).toBeInTheDocument();
    expect(screen.queryByText("HA role:")).toBeNull();
  });
});

describe("the cluster Logs tab (F-21)", () => {
  const members: DeviceSummary[] = [
    device({ device_id: "m-1", hostname: "FW-SYNTH-M1", cluster_member_ref: "CLS-SYNTH-01", ha_role: "ACTIVE" }),
    device({ device_id: "m-2", hostname: "FW-SYNTH-M2", cluster_member_ref: "CLS-SYNTH-01", ha_role: "STANDBY" }),
  ];
  const sources = (id: string) => ({ device_id: id, collection_state: "NO_COLLECTION_YET", can_view_unmasked: false, default_page_bytes: 65536, max_page_bytes: 262144, last_job: null, sources: [] });

  it("picks the member with M1 / M2 pills and reuses the member Logs panel for that member", async () => {
    const fetchMock = stub({
      "/api/v2/system-logs/m-1/sources": () => json(sources("m-1")),
      "/api/v2/system-logs/m-2/sources": () => json(sources("m-2")),
    });
    mount(<ClusterLogsPanel members={members} allMembers={members} vendorHint="check_point" />);
    const pills = screen.getByRole("group", { name: "Cluster member" });
    const [m1, m2] = within(pills).getAllByRole("button");
    expect([m1.textContent, m2.textContent]).toEqual(["M1", "M2"]);
    expect(m1).toHaveAttribute("aria-pressed", "true");
    expect(pills).toHaveTextContent("FW-SYNTH-M1 · ACTIVE");
    await waitFor(() => expect(fetchMock.mock.calls.some(([u]) => u === "/api/v2/system-logs/m-1/sources")).toBe(true));
    fireEvent.click(m2);
    expect(m2).toHaveAttribute("aria-pressed", "true");
    expect(pills).toHaveTextContent("FW-SYNTH-M2 · STANDBY");
    await waitFor(() => expect(fetchMock.mock.calls.some(([u]) => u === "/api/v2/system-logs/m-2/sources")).toBe(true));
  });

  it("is a tab of the cluster pane for Check Point and Palo Alto only", async () => {
    stub({ "/clusters/CLS-SYNTH-01/inventory": () => json({ cluster_member_ref: "CLS-SYNTH-01", members, virtual_systems: [], contexts: [] }) });
    const cp = mount(<ClusterDetailPanels clusterRef="CLS-SYNTH-01" members={members} />);
    expect(await screen.findByRole("tab", { name: "Logs" })).toBeInTheDocument();
    cp.unmount();
    const fortinet = members.map((m) => ({ ...m, vendor_hint: "fortinet" }));
    mount(<ClusterDetailPanels clusterRef="CLS-SYNTH-01" members={fortinet} />);
    await screen.findByRole("tablist", { name: "Cluster detail" });
    expect(screen.queryByRole("tab", { name: "Logs" })).toBeNull();
  });

  it("titles the cluster without a CLS > prefix and shows Backups and Readiness as header pills", async () => {
    stub({ "/clusters/CLS-SYNTH-01/inventory": () => json({ cluster_member_ref: "CLS-SYNTH-01", members, virtual_systems: [], contexts: [] }) });
    mount(<ClusterDetailPanels clusterRef="CLS-SYNTH-01" members={members} />);
    const header = await screen.findByTestId("entity-header");
    expect(within(header).getByRole("heading", { name: "CLS-SYNTH-01" })).toBeInTheDocument();
    expect(within(header).getByRole("link", { name: "Backups" })).toHaveAttribute("href", "?screen=backups&q=CLS-SYNTH-01");
    expect(within(header).getByRole("link", { name: "Readiness" })).toHaveAttribute("href", "?screen=operations&cluster_ref=CLS-SYNTH-01");
    expect(screen.queryByText("This cluster in:")).toBeNull();
  });
});
