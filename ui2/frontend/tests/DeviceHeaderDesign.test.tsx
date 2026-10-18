import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme, m3 } from "../src/theme/m3Theme";
import { DeviceInventoryPanels, ClusterDetailPanels, ClusterLogsPanel, HeaderReadAges, identityBadge, IDENTITY_VERIFIED_BASIS, oldestRead, platformPillLabel, policyLink } from "../src/screens/InventoryPanels";
import { DeviceConfigurationDetail, identityTiles } from "../src/screens/ConfigurationDetail";
import { identitySummaryLine } from "../src/screens/PlatformIdentity";
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
    // R15: the hover carries the product's one stamp, `YYYY-MM-DD HH:MM GMT+3`
    fireEvent.mouseOver(within(ages).getByText(/^Platform read/));
    expect(await screen.findByText(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2} GMT\+3 \(exact times are in the Platform identity grid\)$/)).toBeInTheDocument();
  });

  it("gives a cluster the oldest member's read ages, each member on hover (R13)", async () => {
    const m1 = device({ device_id: "m-1", hostname: "FW-SYNTH-M1", cluster_member_ref: "CLS-SYNTH-01", ha_role: "ACTIVE", platform_facts_observed_at: ago(2 * H) });
    const m2 = device({ device_id: "m-2", hostname: "FW-SYNTH-M2", cluster_member_ref: "CLS-SYNTH-01", ha_role: "STANDBY", platform_facts_observed_at: ago(9 * H) });
    stub({
      "/clusters/CLS-SYNTH-01/inventory": () => json({ cluster_member_ref: "CLS-SYNTH-01", members: [m1, m2], virtual_systems: [], contexts: [] }),
      "/devices/m-1/configuration": () => json({ ...configuration(ago(5 * H)), device_id: "m-1" }),
      "/devices/m-2/configuration": () => json({ ...configuration(ago(30 * H)), device_id: "m-2" }),
      "/devices/m-1/backups": () => json({ backups: [] }), "/devices/m-2/backups": () => json({ backups: [] }),
      "/devices/m-1/compliance": () => json({ items: [] }), "/devices/m-2/compliance": () => json({ items: [] }),
    });
    mount(<ClusterDetailPanels clusterRef="CLS-SYNTH-01" members={[m1, m2]} configuration={<p>side by side</p>} />);
    const ages = await screen.findByTestId("header-read-ages");
    await waitFor(() => expect(ages).toHaveTextContent("Platform read 9 h ago·Config read 30 h ago"));
    fireEvent.mouseOver(within(ages).getByText(/^Platform read/));
    const tip = await screen.findByText(/\(oldest member\)/);
    expect(tip.textContent).toMatch(/M1 \d{4}-\d{2}-\d{2} \d{2}:\d{2} GMT\+3 · M2 \d{4}-\d{2}-\d{2} \d{2}:\d{2} GMT\+3/);
    // the header has no `Members:` line any more: the member cards below carry the names
    expect(screen.queryByText(/^Members:/)).toBeNull();
  });

  it("takes the oldest read and makes no claim while a member is unread", () => {
    expect(oldestRead(["2026-10-18T06:00:00Z", "2026-10-17T06:00:00Z"])).toBe("2026-10-17T06:00:00Z");
    expect(oldestRead(["2026-10-18T06:00:00Z", null])).toBeNull();
    expect(oldestRead([])).toBeNull();
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

  // Post-release review 2026-10-18, R10: the policy link is for devices whose policy neXus collects.
  it("words the policy link for a management server and hides it on an appliance or a vendor without a policy source", async () => {
    const href = "?screen=policy&device_id=dev-1";
    expect(policyLink(device({ role: "management_server" }), href)).toEqual({ href, label: "Open policies this server manages →" });
    expect(policyLink(device({ vendor_hint: "palo_alto" }), href)).toEqual({ href, label: "Open assigned policy" });
    expect(policyLink(device({ role: "appliance", vendor_hint: "infoblox" }), href)).toBeNull();
    expect(policyLink(device({ vendor_hint: "radware", role: "appliance" }), href)).toBeNull();
    expect(policyLink(device({ vendor_hint: "fortinet" }), href)).toBeNull();
    expect(policyLink(device(), undefined)).toBeNull();
    const d = device({ vendor_hint: "infoblox", role: "appliance", platform_facts_observed_at: ago(H) });
    stub(base(d));
    mount(<DeviceInventoryPanels device={d} policyHref={href} />);
    const header = await screen.findByTestId("entity-header");
    expect(within(header).queryByRole("link", { name: /policy/i })).toBeNull();
  });

  it("says Fortinet without Standalone beside the HA sentence (R16) and merges model and version into one pill", async () => {
    const d = device({ vendor_hint: "fortinet", model: "FortiGate 100F", software_version: "7.4.3", ha_role: "PRIMARY", ha_peer_unconfirmed: true, platform_facts_observed_at: ago(H) });
    stub(base(d));
    mount(<DeviceInventoryPanels device={d} />);
    const header = await screen.findByTestId("entity-header");
    expect(within(header).getByText("Fortinet")).toBeInTheDocument();
    expect(within(header).queryByText(/Standalone/)).toBeNull();
    expect(within(header).getAllByText(/^HA: peer named, not confirmed · observed role PRIMARY$/)).toHaveLength(1);
    expect(within(header).getByText("FortiGate 100F · 7.4.3")).toBeInTheDocument();
    expect(platformPillLabel({ vendor_hint: "check_point", model: null, platform_family: "Quantum", software_version: "R81.20" })).toBe("Quantum · R81.20 · Gaia");
    expect(platformPillLabel({ vendor_hint: "palo_alto", model: null, platform_family: null, software_version: null })).toBeNull();
  });

  it("calls a gateway outside a cluster Standalone once when its collector wrote UNKNOWN, and keeps the MDS free of an HA token (R9)", async () => {
    const d = device({ vendor_hint: "palo_alto", ha_role: "UNKNOWN", platform_facts_observed_at: ago(H) });
    stub(base(d));
    mount(<DeviceInventoryPanels device={d} />);
    const header = await screen.findByTestId("entity-header");
    expect(within(header).getByText("Palo Alto · Standalone")).toBeInTheDocument();
    expect(header.querySelector("[data-role]")).toBeNull();
    expect(identitySummaryLine(device({ role: "management_server", ha_role: "UNKNOWN", serial_number: "SN-SYNTH" }))).toBe("Check Point · Quantum · R81.20 · serial read");
    expect(identitySummaryLine(device({ ha_role: "active" }))).toBe("Check Point · Quantum · R81.20 · ACTIVE");
    const mdsTiles = identityTiles(device({ role: "management_server", ha_role: "UNKNOWN" }), null);
    const ha = mdsTiles.find((t) => t.label === "HA role");
    expect(ha?.value).toBeNull();
    expect(ha?.absentWord).toBe("Not read yet");
  });

  it("says Not read yet, not NOT EVALUATED, for a configuration that was never read (R9)", async () => {
    const d = device({ vendor_hint: "fortinet", role: "management_server", platform_facts_observed_at: ago(H) });
    stub({ ...base(d), "/devices/dev-1/configuration": () => json({ error: "NOT_FOUND" }, 404) });
    mount(withGrid(d));
    fireEvent.click(await screen.findByRole("tab", { name: "Configuration" }));
    const line = await screen.findByText(/^Last configuration read/);
    expect(line).toHaveTextContent("Last configuration read Not read yet");
    expect(screen.queryByText("NOT EVALUATED")).toBeNull();
  });

  it("fits a cluster member's header in two pill rows of at most ten pills, without the Members line or a job tick (R13)", async () => {
    const d = device({ cluster_member_ref: "CLS-SYNTH-01", ha_role: "ACTIVE", platform_facts_observed_at: ago(4 * H), model: "Quantum 29200",
      latest_job_state: "COMPLETED", latest_job_type: "cp_inventory_collect" });
    stub({ ...base(d),
      "/clusters/CLS-SYNTH-01/inventory": () => json({ cluster_member_ref: "CLS-SYNTH-01", members: [{ device_id: "dev-1", hostname: "FW-SYNTH-01" }, { device_id: "dev-2", hostname: "FW-SYNTH-02" }], contexts: [], virtual_systems: [] }),
      "/devices/dev-1/configuration": () => json({ ...configuration(ago(16 * H)), change_state: "unchanged" }),
      "/devices/dev-1/backups": () => json({ backups: [{ artefact_id: "a-1", collected_at: ago(14 * H), validation_level: "V1", size_bytes: 1 }] }),
      "/devices/dev-1/compliance": () => json({ items: [{ displayStatus: "FAIL", severity: "CRITICAL" }] }),
      "/api/v2/cp-failover/summary": () => json([{ cluster_member_ref: "CLS-SYNTH-01", unitId: "CLS-SYNTH-01", clusterId: "CLS-SYNTH-01", readiness: { status: "READY", observedAt: ago(3 * H) } }]),
    });
    mount(withGrid(d));
    const header = await screen.findByTestId("entity-header");
    await waitFor(() => expect(within(header).getByText(/^Readiness Ready/)).toBeInTheDocument());
    // the identity row: Live · badge · cluster link · role · one platform pill · counts; the state row: four facts
    const identityRow = within(header).getByRole("heading", { name: "FW-SYNTH-01" }).parentElement as HTMLElement;
    const identityPills = Array.from(identityRow.querySelectorAll(".MuiChip-root, [data-role], a, button")).map((p) => p.textContent);
    expect(identityPills).toEqual(["Live", "Not verified yet", "Check Point ClusterXL · CLS-SYNTH-01", "ACTIVE", "Quantum 29200 · R81.20 · Gaia", "0 interfaces · 0 routes"]);
    const statePills = Array.from(within(header).getByTestId("header-state-row").querySelectorAll("[data-fact]")).map((p) => p.textContent);
    expect(statePills).toEqual(["Compliance 1 critical failing · 1 checked", "Config unchanged", "Backup 14 h ago · Checked", "Readiness Ready · 3 h ago"]);
    expect(identityPills.length + statePills.length).toBeLessThanOrEqual(10);
    expect(header.querySelectorAll(".MuiChip-root").length).toBeLessThanOrEqual(10);
    // no Members: line, no separate model and version pills, no completed-job tick
    expect(within(header).queryByText(/^Members:/)).toBeNull();
    expect(within(header).queryByText("R81.20 · Gaia")).toBeNull();
    expect(within(header).queryByText("✓")).toBeNull();
    // the latest job and what Live rests on are on the Live chip's hover, with no word of identity verification
    const live = within(header).getByText("Live").closest("[title]") as HTMLElement;
    expect(live.title).toMatch(/^Live means neXus confirmed this device and has read evidence from it/);
    expect(live.title).toContain("Latest job: Check Point inventory read completed.");
    expect(live.title).not.toMatch(/verified/i);
  });

  it("has no loose Lifecycle line on any device: the cell is in the Identity tab's grid, for a device without a configuration read too", async () => {
    const withConfig = device();
    stub({ ...base(withConfig), "/devices/dev-1/configuration": () => json(configuration(ago(H))) });
    const first = mount(withGrid(withConfig));
    await screen.findByTestId("entity-header");
    expect(screen.queryByText(/^Lifecycle:/)).toBeNull();
    first.unmount();
    const appliance = device({ vendor_hint: "infoblox", role: "appliance" });
    stub(base(appliance));
    mount(<DeviceInventoryPanels device={appliance} />);
    await screen.findByTestId("entity-header");
    expect(screen.queryByText(/^Lifecycle:/)).toBeNull();
    fireEvent.click(screen.getByRole("tab", { name: "Identity" }));
    const lifecycle = (await screen.findByText("LIFECYCLE")).parentElement as HTMLElement;
    await waitFor(() => expect(lifecycle).toHaveTextContent("LOW · Hardware support: 2031-01-01"));
    // a vendor with no configuration read has no "Last configuration read" tile: absent, not "never"
    expect(screen.queryByText("LAST CONFIGURATION READ")).toBeNull();
  });
});

describe("the Identity tab holds the Platform identity grid (roadmap 2.6)", () => {
  const openIdentity = async () => fireEvent.click(await screen.findByRole("tab", { name: "Identity" }));

  it("has a Lifecycle cell and a Reference cell with Copy, after the certificate and the badge's basis come", async () => {
    const d = device();
    stub({ ...base(d), "/devices/dev-1/configuration": () => json(configuration(ago(H))) });
    mount(withGrid(d));
    await openIdentity();
    const lifecycle = (await screen.findByText("LIFECYCLE")).parentElement as HTMLElement;
    await waitFor(() => expect(lifecycle).toHaveTextContent("LOW · Hardware support: 2031-01-01"));
    const reference = screen.getByText("REFERENCE").parentElement as HTMLElement;
    expect(within(reference).getByText("dev-1")).toBeInTheDocument();
    expect(within(reference).getByRole("button", { name: "Copy" })).toBeInTheDocument();
    expect(identityTiles(d, null).map((t) => t.label).slice(-2)).toEqual(["Lifecycle", "Reference"]);
    // the two columns only the old identity table carried are grid cells now
    expect(screen.getByText("LAST INVENTORY READ")).toBeInTheDocument();
    expect(screen.getByText("EVIDENCE SOURCE")).toBeInTheDocument();
    expect(screen.queryByRole("table", { name: "Device identity" })).toBeNull();
    const basis = screen.getByText(IDENTITY_VERIFIED_BASIS);
    expect(reference.compareDocumentPosition(basis) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    // the badge word appears twice: in the header, and beside its explanation on the Identity tab
    expect(screen.getAllByText("Not verified yet")).toHaveLength(2);
  });

  it("uses three words: Not applicable, Not read yet, and UNKNOWN only where a read was attempted", () => {
    const tiles = identityTiles(device({ role: "management_server", software_version: null }), null);
    const word = (label: string) => tiles.find((t) => t.label === label);
    expect(word("Policy installed")?.absentWord).toBe("Not applicable");
    expect(word("Serial number")?.absentWord).toBe("Not read yet");
    expect(word("Platform facts read")?.absentWord).toBe("Not read yet");
    expect(word("Software version")?.absentWord).toBeUndefined();
    expect(identityTiles(device(), null, { configurationRead: false }).some((t) => t.label === "Last configuration read")).toBe(false);
    expect(identityTiles(device(), null).some((t) => t.label === "Last configuration read")).toBe(true);
  });

  it("shows those words in the grid, and UNKNOWN for a value that was attempted and is unknown", async () => {
    const d = device({ software_version: null });
    stub({ ...base(d), "/devices/dev-1/configuration": () => json(configuration(null)) });
    mount(withGrid(d));
    await openIdentity();
    const serial = (await screen.findByText("SERIAL NUMBER")).parentElement as HTMLElement;
    expect(within(serial).getByText("Not read yet")).toBeInTheDocument();
    const software = screen.getByText("SOFTWARE VERSION").parentElement as HTMLElement;
    expect(within(software).getByText("UNKNOWN")).toBeInTheDocument();
  });

  it("collapses the grid to one line on the Configuration tab, whose link opens the Identity tab on the grid", async () => {
    const scroll = vi.fn();
    window.HTMLElement.prototype.scrollIntoView = scroll;
    const d = device({ identity_check: check("VERIFIED"), serial_number: "SN-SYNTH-01" });
    stub({ ...base(d), "/devices/dev-1/configuration": () => json(configuration(ago(H))) });
    mount(withGrid(d));
    fireEvent.click(await screen.findByRole("tab", { name: "Configuration" }));
    const line = await screen.findByTestId("platform-identity-summary");
    expect(line).toHaveTextContent("Check Point · Quantum · R81.20 · serial read");
    expect(screen.queryByText("LIFECYCLE")).toBeNull();
    fireEvent.click(within(line).getByRole("button", { name: /Full grid in Identity/ }));
    expect(await screen.findByRole("tab", { name: "Identity", selected: true })).toBeInTheDocument();
    await waitFor(() => expect(scroll).toHaveBeenCalled());
    expect((scroll.mock.contexts[0] as HTMLElement).id).toBe("platform-identity-grid");
    expect(screen.getByText("LIFECYCLE")).toBeInTheDocument();
    expect(screen.getByText(IDENTITY_VERIFIED_BASIS)).toBeInTheDocument();
  });

  it("keeps the full grid on the standalone configuration view, which has no Identity tab to lead to", async () => {
    const d = device();
    stub({ ...base(d), "/devices/dev-1/configuration": () => json(configuration(ago(H))) });
    mount(<DeviceConfigurationDetail device={d} />);
    expect(await screen.findByText("LIFECYCLE")).toBeInTheDocument();
    expect(screen.queryByTestId("platform-identity-summary")).toBeNull();
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
