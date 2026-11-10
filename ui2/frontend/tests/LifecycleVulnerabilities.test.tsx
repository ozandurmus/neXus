import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { LifecycleScreen, lifecycleCsv } from "../src/screens/LifecycleScreen";
import {
  ADVISORY_PAGE, AFFECTED_BY_VERSION, LifecycleVulnerabilitiesPanel, basisSentence, vendorLink, vulnerabilityBasis, vulnerabilitySummary,
} from "../src/screens/LifecycleVulnerabilitiesPanel";
import * as api from "../src/auth/adminApi";
import type { LifecycleAdvisory, LifecycleAdvisoryDevice, LifecycleAdvisorySource, LifecycleDevice, LifecycleVulnerabilities } from "../src/auth/adminApi";

vi.mock("../src/auth/adminApi", async importOriginal => ({
  ...await importOriginal<typeof import("../src/auth/adminApi")>(),
  getLifecycle: vi.fn(), getDeviceLifecycle: vi.fn(), getLifecycleCatalog: vi.fn(),
  getLifecycleAdvisories: vi.fn(), getLifecycleAdvisory: vi.fn(), getLifecycleAdvisorySources: vi.fn(),
}));

function themed(node: React.ReactNode) { return render(<ThemeProvider theme={m3Theme}>{node}</ThemeProvider>); }
const noMatch = { status: "NO_LIFECYCLE_DATA" as const, reason: "No catalog match", basis: null, catalog: null };
const device = (id: string, hostname: string, vulnerabilities?: LifecycleVulnerabilities | null, over: Partial<LifecycleDevice> = {}): LifecycleDevice => ({
  device_id: id, hostname, cluster_member_ref: null, vendor: "PALOALTO", model: "Example Appliance", software_version: "11.1.4-h7",
  hardware: noMatch, software: noMatch, status: "NO_LIFECYCLE_DATA", risk: "UNKNOWN", support_days: null, next_milestone: null,
  nearest_license: null, licenses: [], license_status: "COLLECTED", support_contract_status: "COLLECTED", live_patch: null,
  ...(vulnerabilities === undefined ? {} : { vulnerabilities }), ...over,
});
const compared = { source: "paloalto", advisories: 359, oldest: "2019-01-09", newest: "2026-11-08", loaded_at: "2026-11-09T03:00:00Z", age_days: 1 };
const evaluated = (over: Partial<LifecycleVulnerabilities> = {}): LifecycleVulnerabilities => ({ state: "EVALUATED", reason: null, product_key: "paloalto:pan-os",
  affected: 0, affected_exploited: 0, affected_critical_high: 0, not_evaluable: 0, fixed: 0, worst_severity: null, compared, ...over });
const unjudged = (state: LifecycleVulnerabilities["state"], reason: LifecycleVulnerabilities["reason"]): LifecycleVulnerabilities => ({ state, reason, product_key: null,
  affected: 0, affected_exploited: 0, affected_critical_high: 0, not_evaluable: 0, fixed: 0, worst_severity: null, compared: null });

const exploitedDevice = device("d-exploited", "FW-TANGO-04", evaluated({ affected: 3, affected_exploited: 2, affected_critical_high: 2, not_evaluable: 1, fixed: 4, worst_severity: "CRITICAL" }));
const affectedDevice = device("d-affected", "FW-JULIET-06", evaluated({ affected: 1, affected_critical_high: 0, worst_severity: "MEDIUM" }));
const cleanDevice = device("d-clean", "FW-BRAVO-02", evaluated({ fixed: 12 }));
const partlyJudgedDevice = device("d-partly", "FW-PAPA-03", evaluated({ not_evaluable: 2 }));
const neverLoadedDevice = device("d-never", "FW-OSCAR-05", unjudged("NOT_EVALUATED", "SOURCE_NEVER_LOADED"), { vendor: "FORTINET", software_version: "v7.4.3 build2573" });
const staleDevice = device("d-stale", "FW-SIERRA-07", unjudged("NOT_EVALUATED", "SOURCE_STALE"), { vendor: "CISCO_ASA", software_version: "9.18(4)22" });
const notCoveredDevice = device("d-uncovered", "CLS-ROMEO-01", unjudged("NOT_COVERED", "NO_COMPARATOR"), { vendor: "CHECKPOINT", software_version: "R81.20 Jumbo Hotfix Take 99" });
const versionUnknownDevice = device("d-noversion", "FW-KILO-08", unjudged("VERSION_UNKNOWN", "VERSION_MISSING"), { software_version: null });
const legacyDevice = device("d-legacy", "FW-LIMA-09");
// Server order on purpose: the device under an exploited advisory is last.
const fleet = [cleanDevice, notCoveredDevice, neverLoadedDevice, staleDevice, versionUnknownDevice, partlyJudgedDevice, legacyDevice, affectedDevice, exploitedDevice];
const loadFleet = (devices: LifecycleDevice[], summary: Record<string, number | null> = {}) =>
  vi.mocked(api.getLifecycle).mockResolvedValue({ as_of: "2026-11-10", devices, summary: { devices: devices.length, ...summary } });
const rowOf = (table: HTMLElement, hostname: string) => within(table).getByText(hostname).closest("tr") as HTMLElement;

const advisory = (id: string, over: Partial<LifecycleAdvisory> = {}): LifecycleAdvisory => ({ advisory_id: id, source: "paloalto", title: `Synthetic advisory ${id}`,
  severity: "HIGH", cvss_score: 8.1, cve_ids: [], exploited: false, exploited_since: null, published_on: "2026-10-01", updated_on: "2026-10-01",
  source_url: "https://vendor.example/advisories/one", parse_state: "PARSED", parse_note: null, devices: { affected: 0, fixed: 1, not_affected: 0, not_evaluable: 0 }, ...over });
const exploitedAdvisory = advisory("paloalto:CVE-2026-0001", { title: "Synthetic command injection in the management interface", severity: "CRITICAL", cvss_score: 9.8,
  cve_ids: ["CVE-2026-0001"], exploited: true, exploited_since: "2026-10-20", updated_on: "2026-10-22", devices: { affected: 2, fixed: 3, not_affected: 1, not_evaluable: 1 } });
const mediumAdvisory = advisory("fortinet:FG-IR-26-150", { source: "fortinet", title: "Synthetic buffer overflow in the web portal", severity: "MEDIUM", cvss_score: 5.4,
  cve_ids: ["CVE-2026-0002", "CVE-2026-0003"], devices: { affected: 1, fixed: 0, not_affected: 0, not_evaluable: 0 } });
const reviewAdvisory = advisory("paloalto:CVE-2026-0004", { title: "Synthetic advisory in an unrecognised form", severity: "UNKNOWN", cvss_score: null, cve_ids: ["CVE-2026-0004"],
  parse_state: "NEEDS_REVIEW", parse_note: "SELF_CHECK_FAILED", devices: { affected: 0, fixed: 0, not_affected: 0, not_evaluable: 5 } });
const fixedOnlyAdvisory = advisory("cisco:cisco-sa-synthetic-0005", { source: "cisco", title: "Synthetic denial of service", severity: "HIGH", exploited: true, exploited_since: "2026-09-01",
  cve_ids: ["CVE-2026-0005"], devices: { affected: 0, fixed: 6, not_affected: 0, not_evaluable: 0 } });
const advisories = [exploitedAdvisory, mediumAdvisory, reviewAdvisory, fixedOnlyAdvisory];
const source = (key: string, over: Partial<LifecycleAdvisorySource> = {}): LifecycleAdvisorySource => ({ source: key, last_run_at: "2026-11-09T03:00:00Z", outcome: "OK", reason: null,
  advisories: 359, needs_review: 0, oldest: "2019-01-09", newest: "2026-11-08", age_days: 1, age_state: "OK", ...over });
const never = (key: string): LifecycleAdvisorySource => source(key, { last_run_at: null, outcome: null, advisories: 0, oldest: null, newest: null, age_days: null, age_state: "NEVER" });
const sources = [source("paloalto", { needs_review: 4 }), source("fortinet", { advisories: 50, age_days: 9, age_state: "AMBER", outcome: "PARTIAL", reason: "DOCUMENT_TOO_LARGE" }),
  source("cisco", { advisories: 20, age_days: 41, age_state: "RED", outcome: "FAILED", reason: "TLS_VALIDATION" }), source("cisa_kev", { advisories: 1200 })];
const loadTab = (list: LifecycleAdvisory[] = advisories, sourceRows: LifecycleAdvisorySource[] = sources) => {
  vi.mocked(api.getLifecycleAdvisories).mockResolvedValue({ as_of: "2026-11-10T05:00:00Z", advisories: list });
  vi.mocked(api.getLifecycleAdvisorySources).mockResolvedValue({ sources: sourceRows });
};
const detailRow = (hostname: string, verdict: LifecycleAdvisoryDevice["verdict"], basis: string, over: Partial<LifecycleAdvisoryDevice> = {}): LifecycleAdvisoryDevice =>
  ({ advisory_id: "paloalto:CVE-2026-0001", device_id: `id-${hostname}`, hostname, vendor: "PALOALTO", version: "11.1.4-h7", verdict, basis, fixed_text: null, ...over });

beforeEach(() => {
  vi.clearAllMocks();
  loadFleet(fleet, { vuln_devices_exploited: 1, vuln_devices_affected: 2, vuln_devices_not_evaluated: 3, vuln_oldest_source_age_days: 9 });
  vi.mocked(api.getLifecycleCatalog).mockResolvedValue({ can_manage: false, entries: [] });
  loadTab();
});

describe("Fleet tab: the Vulnerabilities cell", () => {
  it("words every device state and keeps the exploited marker the loudest thing on the row", async () => {
    themed(<LifecycleScreen />);
    const table = await screen.findByRole("table", { name: "Fleet lifecycle" });
    expect(within(table).getByRole("columnheader", { name: "Vulnerabilities" })).toBeInTheDocument();
    expect(within(table).getAllByRole("columnheader").map(h => h.textContent).slice(3, 5)).toEqual(["Version", "Vulnerabilities"]);

    const exploited = rowOf(table, "FW-TANGO-04");
    expect(within(exploited).getByText("3 affected").closest("[data-pill]")).toHaveAttribute("data-pill", "warn");
    expect(within(exploited).getByText("2 exploited").closest("[data-pill]")).toHaveAttribute("data-pill", "bad");
    expect(within(exploited).getByText("2 critical or high · 1 could not be judged")).toBeInTheDocument();
    expect(table.querySelectorAll('[data-pill="bad"]')).toHaveLength(1);

    const affected = rowOf(table, "FW-JULIET-06");
    expect(within(affected).getByText("1 affected")).toBeInTheDocument();
    expect(within(affected).queryByText(/exploited/)).toBeNull();

    const clean = rowOf(table, "FW-BRAVO-02");
    expect(within(clean).getByText("None affected")).toBeInTheDocument();
    expect(within(clean).getByText("of 359 advisories, loaded 1 d ago")).toBeInTheDocument();

    const partly = rowOf(table, "FW-PAPA-03");
    expect(within(partly).getByText("None affected")).toBeInTheDocument();
    expect(within(partly).getByText("2 could not be judged")).toBeInTheDocument();

    expect(within(rowOf(table, "FW-OSCAR-05")).getByText("Not evaluated").closest("[data-pill]")).toHaveAttribute("data-pill", "unknown");
    expect(within(rowOf(table, "FW-OSCAR-05")).getByText("advisory source never loaded")).toBeInTheDocument();
    expect(within(rowOf(table, "FW-SIERRA-07")).getByText("advisory source older than 30 days")).toBeInTheDocument();
    expect(within(rowOf(table, "CLS-ROMEO-01")).getByText("No advisory source for this product")).toBeInTheDocument();
    expect(within(rowOf(table, "FW-KILO-08")).getByText("Version not known")).toBeInTheDocument();
    expect(within(rowOf(table, "FW-KILO-08")).getByText("no software version stored")).toBeInTheDocument();
  });

  it("never green: a device that was not judged shows no positive wording and no positive colour", async () => {
    themed(<LifecycleScreen />);
    const table = await screen.findByRole("table", { name: "Fleet lifecycle" });
    for (const hostname of ["FW-OSCAR-05", "FW-SIERRA-07", "CLS-ROMEO-01", "FW-KILO-08", "FW-LIMA-09"]) {
      const row = rowOf(table, hostname);
      expect(row.textContent).not.toMatch(/none affected|no findings|not affected|up to date|secure|safe|clean|\bok\b|✓|✔/i);
      expect(row.querySelector('[data-pill="ok"]')).toBeNull();
    }
    // Nothing on the whole table is green, a clean device included: it is plain text with what it was compared against.
    expect(table.querySelector('[data-pill="ok"]')).toBeNull();
    expect(vulnerabilitySummary(evaluated({ compared: null }))).toBe("None affected");
    expect(vulnerabilityBasis(evaluated({ compared: null }))).toBe("comparison basis not reported");
    expect(vulnerabilitySummary(null)).toBe("No vulnerability data");
    expect(vulnerabilitySummary({ state: "SOMETHING_NEW" } as unknown as LifecycleVulnerabilities)).toBe("Not evaluated · SOMETHING_NEW");
  });

  it("puts devices under an exploited advisory first, then the other affected devices, and filters by state", async () => {
    themed(<LifecycleScreen />);
    const table = await screen.findByRole("table", { name: "Fleet lifecycle" });
    const names = () => within(table).getAllByRole("link").map(a => a.textContent);
    expect(names().slice(0, 3)).toEqual(["FW-TANGO-04", "FW-JULIET-06", "FW-BRAVO-02"]);
    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Vulnerability state" }));
    fireEvent.click(screen.getByRole("option", { name: "Not evaluated" }));
    expect(names()).toEqual(["FW-OSCAR-05", "FW-SIERRA-07", "FW-KILO-08"]);
    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Vulnerability state" }));
    fireEvent.click(screen.getByRole("option", { name: "Exploited advisory" }));
    expect(names()).toEqual(["FW-TANGO-04"]);
    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Vulnerability state" }));
    fireEvent.click(screen.getByRole("option", { name: "Any vulnerability state" }));
    fireEvent.change(screen.getByLabelText("Search lifecycle"), { target: { value: "exploited" } });
    expect(names()).toEqual(["FW-TANGO-04"]);
  });

  it("shows the four cards with the term defined once, and UNKNOWN instead of zero when the backend sent no counter", async () => {
    themed(<LifecycleScreen />);
    await screen.findByRole("table", { name: "Fleet lifecycle" });
    const cards = screen.getByRole("group", { name: "Vulnerability summary" });
    const card = (key: string) => cards.querySelector(`[data-vulnerability-card="${key}"]`) as HTMLElement;
    expect(card("vuln_devices_exploited")).toHaveTextContent("1Devices with an exploited advisory");
    expect(card("vuln_devices_affected")).toHaveTextContent("2Devices affected by an advisory");
    expect(card("vuln_devices_not_evaluated")).toHaveTextContent("3Devices not evaluated");
    expect(card("vuln_oldest_source_age_days")).toHaveTextContent("9 d agoOldest advisory source load");
    // a count is said with what it was counted over, and the devices outside the check are not left out of sight
    expect(card("vuln_devices_exploited")).toHaveTextContent("of 4 evaluated");
    expect(card("vuln_devices_not_evaluated")).toHaveTextContent("1 more without an advisory source");
    // the vulnerability cards come before the lifecycle cards
    expect(cards.compareDocumentPosition(screen.getByText("Past end of support")) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getAllByText(/Exploited: the advisory is listed in the CISA Known Exploited Vulnerabilities catalog/)).toHaveLength(1);
    // the existing cards are still there
    expect(screen.getByText("Past end of support")).toBeInTheDocument();
    expect(screen.getByText("Live Patch not active")).toBeInTheDocument();
  });

  it("still renders a payload without vulnerabilities (older backend)", async () => {
    loadFleet([legacyDevice, device("d-null", "FW-MIKE-10", null)]);
    themed(<LifecycleScreen />);
    const table = await screen.findByRole("table", { name: "Fleet lifecycle" });
    expect(within(table).getAllByText("No vulnerability data")).toHaveLength(2);
    const cards = screen.getByRole("group", { name: "Vulnerability summary" });
    expect(within(cards).getAllByText("UNKNOWN")).toHaveLength(4);
    expect(within(cards).queryByText("0")).toBeNull();
    fireEvent.click(within(table).getAllByRole("button", { name: "Details" })[0]);
    expect(within(screen.getByRole("group", { name: /Details of FW-LIMA-09/ })).queryByText("Vulnerabilities")).toBeNull();
  });

  it("carries the counts and the comparison basis in the detail row", async () => {
    loadFleet([exploitedDevice, neverLoadedDevice]);
    themed(<LifecycleScreen />);
    const table = await screen.findByRole("table", { name: "Fleet lifecycle" });
    fireEvent.click(within(rowOf(table, "FW-TANGO-04")).getByRole("button", { name: "Details" }));
    const detail = screen.getByRole("group", { name: /Details of FW-TANGO-04/ });
    expect(within(detail).getByText("3 advisories name this version as affected · 2 exploited · 2 critical or high · worst severity Critical · 4 fixed in this version · 1 could not be judged")).toBeInTheDocument();
    expect(within(detail).getByText(/^Compared against Palo Alto Networks: 359 advisories from 2019-01-09 to 2026-11-08, loaded 1 d ago \(2026-11-09 06:00 GMT\+3\)$/)).toBeInTheDocument();
    expect(within(detail).getByText(/Affected by version; exposure depends on configuration\./)).toBeInTheDocument();
    fireEvent.click(within(rowOf(table, "FW-OSCAR-05")).getByRole("button", { name: "Details" }));
    expect(within(screen.getByRole("group", { name: /Details of FW-OSCAR-05/ }))
      .getByText("Not evaluated · advisory source never loaded. This device is not known to be unaffected.")).toBeInTheDocument();
  });

  it("exports the vulnerability columns: numbers for a judged device, blanks for one that was not", () => {
    const [head, first, second, third] = lifecycleCsv([exploitedDevice, neverLoadedDevice, legacyDevice]).trim().split("\r\n");
    const columns = head.split(",");
    expect(columns.slice(3, 5)).toEqual(["Version", "Vulnerabilities"]);
    for (const h of ["Vulnerability state", "Vulnerability reason", "Advisories affecting", "Affecting and exploited", "Affecting and critical or high", "Advisories not judged",
      "Advisories fixed", "Worst severity", "Advisory source", "Advisories compared", "Oldest advisory", "Newest advisory", "Advisory source age (days)"]) expect(columns).toContain(h);
    expect(columns.slice(-6)).toEqual(["License problem", "License collection by kind", "Live Patch package take", "Live Patch armed", "Live Patch ready", "Live Patch jumbofix"]);
    expect(first).toContain("3 affected · 2 exploited · 1 could not be judged");
    expect(first).toContain("EVALUATED,,3,2,2,1,4,Critical,Palo Alto Networks,359,2019-01-09,2026-11-08,1");
    expect(second).toContain("Not evaluated · advisory source never loaded");
    expect(second).toContain("NOT_EVALUATED,SOURCE_NEVER_LOADED,,,,,,,,,,,");
    expect(third).toContain("No vulnerability data");
    expect(third.split(",")).toHaveLength(columns.length);
  });
});

describe("Vulnerabilities tab", () => {
  it("is the third tab and lists sources and advisories with the counts line", async () => {
    themed(<LifecycleScreen />);
    await screen.findByRole("table", { name: "Fleet lifecycle" });
    expect(screen.getAllByRole("tab").map(t => t.textContent)).toEqual(["Fleet", "Catalog", "Vulnerabilities"]);
    fireEvent.click(screen.getByRole("tab", { name: "Vulnerabilities" }));
    const list = await screen.findByRole("table", { name: "Security advisories" });
    expect(within(list).getAllByRole("columnheader").map(h => h.textContent)).toEqual(["Severity", "Advisory", "Exploited", "Devices", "Published", "Vendor page"]);
    expect(document.querySelector("[data-advisory-counts]")).toHaveTextContent(
      "4 advisories concern this fleet · 2 affect at least one device · 1 of those exploited · 1 not read in full · as of 2026-11-10 08:00 GMT+3.");
    expect(screen.getAllByText(/Exploited: listed in the CISA Known Exploited Vulnerabilities catalog/)).toHaveLength(1);

    const first = list.querySelector('[data-advisory="paloalto:CVE-2026-0001"]') as HTMLElement;
    expect(within(first).getByText("Critical")).toBeInTheDocument();
    expect(within(first).getByText("CVSS 9.8")).toBeInTheDocument();
    expect(within(first).getByText("Synthetic command injection in the management interface")).toBeInTheDocument();
    expect(within(first).getByText("Palo Alto Networks · paloalto:CVE-2026-0001")).toBeInTheDocument();
    expect(first.querySelector('[data-fact="advisory:exploited"]')).toHaveTextContent("Exploited · since 2026-10-20");
    expect(first.querySelector('[data-fact="advisory:exploited"]')).toHaveAttribute("data-pill", "bad");
    expect(first.querySelector('[data-fact="advisory:affected"]')).toHaveTextContent("2 affected");
    expect(within(first).getByText("3 fixed · 1 not affected · 1 could not be judged")).toBeInTheDocument();
    expect(within(first).getByText("2026-10-01")).toBeInTheDocument();
    expect(within(first).getByText("updated 2026-10-22")).toBeInTheDocument();

    const second = list.querySelector('[data-advisory="fortinet:FG-IR-26-150"]') as HTMLElement;
    expect(within(second).getByText("CVE-2026-0002, CVE-2026-0003")).toBeInTheDocument();
    expect(within(second).getByText("—")).toBeInTheDocument();
    // exploited in the wild but no device of this fleet is affected: said, not red, and never green
    const fixedOnly = list.querySelector('[data-advisory="cisco:cisco-sa-synthetic-0005"]') as HTMLElement;
    expect(fixedOnly.querySelector('[data-fact="advisory:exploited"]')).toHaveAttribute("data-pill", "neutral");
    expect(within(fixedOnly).getByText("None affected")).toBeInTheDocument();
    expect(within(fixedOnly).getByText("6 fixed")).toBeInTheDocument();
    expect(list.querySelector('[data-pill="ok"]')).toBeNull();
  });

  it("filters by text, by 'exploited only' and by severity", async () => {
    themed(<LifecycleVulnerabilitiesPanel />);
    const list = await screen.findByRole("table", { name: "Security advisories" });
    const ids = () => [...list.querySelectorAll("[data-advisory]")].map(r => r.getAttribute("data-advisory"));
    expect(ids()).toEqual(advisories.map(a => a.advisory_id));
    fireEvent.change(screen.getByLabelText("Search advisories"), { target: { value: "cve-2026-0003" } });
    expect(ids()).toEqual(["fortinet:FG-IR-26-150"]);
    fireEvent.change(screen.getByLabelText("Search advisories"), { target: { value: "no such advisory" } });
    expect(within(list).getByText("No advisories match these filters")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Search advisories"), { target: { value: "" } });
    fireEvent.click(screen.getByLabelText("Exploited only"));
    expect(ids()).toEqual(["paloalto:CVE-2026-0001", "cisco:cisco-sa-synthetic-0005"]);
    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Severity" }));
    fireEvent.click(screen.getByRole("option", { name: "Critical" }));
    expect(ids()).toEqual(["paloalto:CVE-2026-0001"]);
    fireEvent.click(screen.getByLabelText("Exploited only"));
    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Severity" }));
    fireEvent.click(screen.getByRole("option", { name: "Medium" }));
    expect(ids()).toEqual(["fortinet:FG-IR-26-150"]);
  });

  it("opens a row: verdict, version compared, the basis in plain words, the fix statement, and the version sentence once", async () => {
    vi.mocked(api.getLifecycleAdvisory).mockResolvedValue({ advisory: exploitedAdvisory, devices: [
      detailRow("FW-BRAVO-02", "FIXED", "AT_OR_ABOVE_FIX", { version: "11.1.6-h1", fixed_text: "11.1.4-h9, 11.1.6" }),
      detailRow("FW-TANGO-04", "AFFECTED", "IN_RANGE", { fixed_text: "11.1.4-h9, 11.1.6" }),
      detailRow("FW-JULIET-06", "AFFECTED", "BELOW_HOTFIX", { version: "11.1.4-h2" }),
      detailRow("FW-PAPA-03", "NOT_AFFECTED", "TRAIN_UNAFFECTED", { version: "10.2.9" }),
      detailRow("FW-KILO-08", "NOT_EVALUABLE", "VERSION_UNPARSED", { version: null }),
      detailRow("FW-LIMA-09", "NOT_AFFECTED", "BRAND_NEW_CODE_WORD", { version: "10.2.9" }),
    ] });
    themed(<LifecycleVulnerabilitiesPanel />);
    const list = await screen.findByRole("table", { name: "Security advisories" });
    expect(api.getLifecycleAdvisory).not.toHaveBeenCalled();
    const row = list.querySelector('[data-advisory="paloalto:CVE-2026-0001"]') as HTMLElement;
    fireEvent.click(within(row).getByRole("button", { name: "Show 7 devices" }));
    const devices = await screen.findByRole("table", { name: "Devices judged against paloalto:CVE-2026-0001" });
    expect(api.getLifecycleAdvisory).toHaveBeenCalledWith("paloalto:CVE-2026-0001");
    expect(within(devices).getAllByRole("columnheader").map(h => h.textContent)).toEqual(["Device", "Vendor", "Version compared", "Verdict", "Basis", "Vendor fix statement"]);
    // affected devices first, then the ones that were not judged
    expect(within(devices).getAllByRole("link").map(a => a.textContent)).toEqual(["FW-TANGO-04", "FW-JULIET-06", "FW-KILO-08", "FW-BRAVO-02", "FW-PAPA-03", "FW-LIMA-09"]);
    const tango = rowOf(devices, "FW-TANGO-04");
    expect(within(tango).getByText("Affected").closest("[data-pill]")).toHaveAttribute("data-pill", "bad");
    expect(within(tango).getByText("11.1.4-h7")).toBeInTheDocument();
    expect(within(tango).getByText("The version lies inside the affected range.")).toBeInTheDocument();
    expect(within(tango).getByText("11.1.4-h9, 11.1.6")).toBeInTheDocument();
    expect(within(rowOf(devices, "FW-JULIET-06")).getByText("The version is below the hotfix the vendor names as fixed.")).toBeInTheDocument();
    expect(within(rowOf(devices, "FW-BRAVO-02")).getByText("Fixed")).toBeInTheDocument();
    expect(within(rowOf(devices, "FW-BRAVO-02")).getByText("The version is at or above the vendor's fixed version.")).toBeInTheDocument();
    expect(within(rowOf(devices, "FW-PAPA-03")).getByText("The vendor names this release line as unaffected.")).toBeInTheDocument();
    const kilo = rowOf(devices, "FW-KILO-08");
    expect(within(kilo).getByText("Could not be judged").closest("[data-pill]")).toHaveAttribute("data-pill", "unknown");
    expect(within(kilo).getByText("The device's version text was not recognised.")).toBeInTheDocument();
    expect(within(kilo).getByText("UNKNOWN")).toBeInTheDocument();
    expect(within(rowOf(devices, "FW-LIMA-09")).getByText("BRAND_NEW_CODE_WORD")).toBeInTheDocument();
    expect(devices.querySelector('[data-pill="ok"]')).toBeNull();
    expect(screen.getAllByText(AFFECTED_BY_VERSION)).toHaveLength(1);
    fireEvent.click(within(row).getByRole("button", { name: "Hide devices" }));
    expect(screen.queryByRole("table", { name: "Devices judged against paloalto:CVE-2026-0001" })).toBeNull();
  });

  it("maps every basis code word to a sentence and shows an unknown one as it is", () => {
    for (const code of ["IN_RANGE", "BELOW_HOTFIX", "EXACT_VERSION_LISTED", "AT_OR_ABOVE_FIX", "TRAIN_UNAFFECTED", "VERSION_NOT_LISTED_IN_TRAIN", "BELOW_AFFECTED_RANGE",
      "VERSION_UNPARSED", "ADVISORY_NEEDS_REVIEW", "NO_RULE_COVERS_VERSION", "NO_COMPARATOR", "TRAIN_AFFECTED", "CHANGE_POINT_AFFECTED", "IN_UNAFFECTED_RANGE",
      "EXACT_VERSION_UNAFFECTED", "BELOW_HOTFIX_UNAFFECTED", "PRODUCT_NOT_NAMED", "TRAIN_NOT_NAMED", "EVALUATION_ERROR"]) {
      expect(basisSentence(code)).toMatch(/^[A-Zn][^_]+\.$/);
    }
    expect(basisSentence("SOMETHING_ELSE")).toBe("SOMETHING_ELSE");
    // a code word is never looked up on an object: these stay words
    expect(basisSentence("constructor")).toBe("constructor");
    expect(basisSentence("__proto__")).toBe("__proto__");
    expect(basisSentence(null)).toBe("UNKNOWN");
  });

  it("does not say the version sentence when no device is affected, and reports a failed device read", async () => {
    vi.mocked(api.getLifecycleAdvisory).mockResolvedValueOnce({ advisory: fixedOnlyAdvisory, devices: [detailRow("CLS-ROMEO-01", "FIXED", "AT_OR_ABOVE_FIX", { version: "9.18(4)22", vendor: "CISCO_ASA" })] });
    themed(<LifecycleVulnerabilitiesPanel />);
    const list = await screen.findByRole("table", { name: "Security advisories" });
    fireEvent.click(within(list.querySelector('[data-advisory="cisco:cisco-sa-synthetic-0005"]') as HTMLElement).getByRole("button", { name: "Show 6 devices" }));
    await screen.findByRole("table", { name: "Devices judged against cisco:cisco-sa-synthetic-0005" });
    expect(screen.queryByText(AFFECTED_BY_VERSION)).toBeNull();
    vi.mocked(api.getLifecycleAdvisory).mockRejectedValueOnce({ status: 503, body: { error: "UNAVAILABLE" } });
    fireEvent.click(within(list.querySelector('[data-advisory="fortinet:FG-IR-26-150"]') as HTMLElement).getByRole("button", { name: "Show 1 device" }));
    expect(await screen.findByText("UNAVAILABLE")).toBeInTheDocument();
  });

  it("marks an advisory that was not read in full, with its code word as secondary text", async () => {
    themed(<LifecycleVulnerabilitiesPanel />);
    const list = await screen.findByRole("table", { name: "Security advisories" });
    const row = list.querySelector('[data-advisory="paloalto:CVE-2026-0004"]') as HTMLElement;
    expect(row.querySelector('[data-fact="advisory:needs-review"]')).toHaveTextContent("Not read in full");
    expect(row.querySelector('[data-fact="advisory:needs-review"]')).toHaveAttribute("data-pill", "warn");
    expect(within(row).getByText(/Devices of this product cannot be judged against it\./)).toBeInTheDocument();
    expect(within(row).getByText(/SELF_CHECK_FAILED/)).toBeInTheDocument();
    expect(within(row).getByText("5 could not be judged").closest("[data-pill]")).toHaveAttribute("data-pill", "unknown");
    expect(within(row).queryByText("None affected")).toBeNull();
    expect(list.querySelectorAll('[data-fact="advisory:needs-review"]')).toHaveLength(1);
    // the deploy end-to-end run treats these phrases as an unhandled load error; the marking must not trip it
    expect(document.body.textContent).not.toMatch(/could not be (?:read|loaded)|(?:api|request|read|load) (?:failed|error)/i);
  });

  it("links the vendor page only for an https address; anything else is text", async () => {
    loadTab([
      advisory("paloalto:CVE-2026-0011", { source_url: "https://vendor.example/advisories/CVE-2026-0011" }),
      advisory("paloalto:CVE-2026-0012", { source_url: "javascript:alert(1)" }),
      advisory("paloalto:CVE-2026-0013", { source_url: "http://vendor.example/advisories/CVE-2026-0013" }),
      advisory("paloalto:CVE-2026-0014", { source_url: null }),
      advisory("paloalto:CVE-2026-0015", { source_url: "https://" }),
    ]);
    themed(<LifecycleVulnerabilitiesPanel />);
    const list = await screen.findByRole("table", { name: "Security advisories" });
    const links = within(list).getAllByRole("link");
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveAttribute("href", "https://vendor.example/advisories/CVE-2026-0011");
    expect(links[0]).toHaveAttribute("target", "_blank");
    expect(links[0]).toHaveAttribute("rel", "noopener noreferrer");
    expect(links[0]).toHaveTextContent("vendor.example");
    expect(within(list).getByText("Not linked: javascript:alert(1)")).toBeInTheDocument();
    expect(within(list).getByText("Not linked: http://vendor.example/advisories/CVE-2026-0013")).toBeInTheDocument();
    expect(list.querySelector('a[href^="javascript"], a[href^="http:"]')).toBeNull();
    expect(within(list.querySelector('[data-advisory="paloalto:CVE-2026-0014"]') as HTMLElement).queryByRole("link")).toBeNull();
    expect(vendorLink("https://vendor.example/a")?.hostname).toBe("vendor.example");
    for (const bad of ["javascript:alert(1)", "http://vendor.example", " https://vendor.example", "HTTPS://vendor.example", "//vendor.example", "https://", "", null, undefined]) expect(vendorLink(bad)).toBeNull();
  });

  it("renders advisory text as plain text, never as markup", async () => {
    loadTab([advisory("paloalto:CVE-2026-0021", { title: '<img src=x onerror="alert(1)"> **bold** [link](https://vendor.example)', parse_state: "NEEDS_REVIEW", parse_note: "<b>NOTE</b>",
      devices: { affected: 1, fixed: 0, not_affected: 0, not_evaluable: 0 } })]);
    vi.mocked(api.getLifecycleAdvisory).mockResolvedValue({ advisory: advisory("paloalto:CVE-2026-0021"), devices: [detailRow("FW-TANGO-04", "AFFECTED", "<i>IN_RANGE</i>", { fixed_text: "<script>alert(1)</script>" })] });
    themed(<LifecycleVulnerabilitiesPanel />);
    const list = await screen.findByRole("table", { name: "Security advisories" });
    expect(within(list).getByText('<img src=x onerror="alert(1)"> **bold** [link](https://vendor.example)')).toBeInTheDocument();
    fireEvent.click(within(list).getByRole("button", { name: "Show 1 device" }));
    const devices = await screen.findByRole("table", { name: "Devices judged against paloalto:CVE-2026-0021" });
    expect(within(devices).getByText("<i>IN_RANGE</i>")).toBeInTheDocument();
    expect(within(devices).getByText("<script>alert(1)</script>")).toBeInTheDocument();
    expect(document.querySelector("img, script, b, i, strong")).toBeNull();
  });

  it("shows the sources: name in plain words, last run, outcome, counts, coverage and age", async () => {
    themed(<LifecycleVulnerabilitiesPanel />);
    const table = await screen.findByRole("table", { name: "Advisory sources" });
    expect(within(table).getAllByRole("columnheader").map(h => h.textContent)).toEqual(["Source", "Last run", "Outcome", "Advisories", "Not read in full", "Coverage", "Age of last load"]);
    const pan = rowOf(table, "Palo Alto Networks");
    expect(within(pan).getByText(/2026-11-09 06:00 GMT\+3/)).toBeInTheDocument();
    expect(within(pan).getByText("Complete")).toBeInTheDocument();
    expect(within(pan).getByText("359")).toBeInTheDocument();
    expect(within(pan).getByText("4")).toBeInTheDocument();
    expect(within(pan).getByText("from 2019-01-09 to 2026-11-08")).toBeInTheDocument();
    expect(pan.querySelector("[data-pill]")).toHaveTextContent("Current · 1 d");
    const fortinet = rowOf(table, "Fortinet");
    expect(within(fortinet).getByText("Partial").closest("[data-pill]")).toHaveAttribute("data-pill", "warn");
    expect(within(fortinet).getByText("DOCUMENT_TOO_LARGE")).toBeInTheDocument();
    expect(within(fortinet).getByText(/Older than 7 days/).closest("[data-pill]")).toHaveAttribute("data-pill", "warn");
    const cisco = rowOf(table, "Cisco");
    expect(within(cisco).getByText("Failed").closest("[data-pill]")).toHaveAttribute("data-pill", "bad");
    expect(within(cisco).getByText(/Older than 30 days/).closest("[data-pill]")).toHaveTextContent("Older than 30 days · 41 d");
    expect(within(table).getByText("CISA Known Exploited Vulnerabilities")).toBeInTheDocument();
    expect(screen.queryByText(/No advisory source has been loaded yet/)).toBeNull();
  });

  it("says plainly when no source has ever loaded, instead of an empty table", async () => {
    loadTab([], ["paloalto", "fortinet", "cisco", "cisa_kev"].map(never));
    themed(<LifecycleVulnerabilitiesPanel />);
    const notice = await screen.findByText("No advisory source has been loaded yet. Devices are not evaluated.");
    // a statement, not a failure: the deploy end-to-end run fails on an error or warning alert
    expect(notice.closest('[role="alert"]')?.className).toMatch(/MuiAlert-standardInfo/);
    await waitFor(() => expect(screen.queryByText("Loading advisories…")).toBeNull());
    expect(screen.queryByRole("table")).toBeNull();
    expect(document.body.textContent).not.toMatch(/none affected|no advisory in the loaded sources/i);
  });

  it("keeps the sources table when every load failed, so the reason is visible", async () => {
    loadTab([], [{ ...never("paloalto"), last_run_at: "2026-11-10T03:00:00Z", outcome: "FAILED", reason: "TLS_VALIDATION" }, never("cisa_kev")]);
    themed(<LifecycleVulnerabilitiesPanel />);
    expect(await screen.findByText("No advisory source has been loaded yet. Devices are not evaluated.")).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Advisory sources" });
    expect(within(table).getByText("TLS_VALIDATION")).toBeInTheDocument();
    expect(within(table).getAllByText("Never loaded")).toHaveLength(2);
    expect(screen.queryByRole("table", { name: "Security advisories" })).toBeNull();
  });

  it("does not let an unflagged advisory read as 'not exploited' while the known-exploited catalog is not loaded", async () => {
    loadTab([mediumAdvisory], [source("fortinet"), never("cisa_kev")]);
    themed(<LifecycleVulnerabilitiesPanel />);
    const list = await screen.findByRole("table", { name: "Security advisories" });
    expect(await within(list).findByText("Not known")).toBeInTheDocument();
    expect(screen.getByText("The known-exploited catalog has not been loaded: no advisory can be marked exploited.")).toBeInTheDocument();
  });

  it("says when the loaded sources name nothing in this fleet, next to the sources it was compared against", async () => {
    loadTab([], sources);
    themed(<LifecycleVulnerabilitiesPanel />);
    const list = await screen.findByRole("table", { name: "Security advisories" });
    expect(within(list).getByText("No advisory in the loaded sources names a product and version of this fleet.")).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Advisory sources" })).toBeInTheDocument();
  });

  it("pages the advisories at 50 with '+N more'", async () => {
    loadTab(Array.from({ length: ADVISORY_PAGE + 7 }, (_, i) => advisory(`paloalto:CVE-2026-${String(1000 + i)}`)));
    themed(<LifecycleVulnerabilitiesPanel />);
    const list = await screen.findByRole("table", { name: "Security advisories" });
    expect(list.querySelectorAll("[data-advisory]")).toHaveLength(ADVISORY_PAGE);
    expect(document.querySelector("[data-advisory-page]")).toHaveTextContent(`1–${ADVISORY_PAGE} of ${ADVISORY_PAGE + 7} · +7 more`);
    fireEvent.click(screen.getByRole("button", { name: "Show next 7" }));
    expect(list.querySelectorAll("[data-advisory]")).toHaveLength(ADVISORY_PAGE + 7);
    expect(screen.queryByRole("button", { name: /^Show next/ })).toBeNull();
  });

  it("reports a failed read of either list without fabricating data", async () => {
    vi.mocked(api.getLifecycleAdvisories).mockRejectedValue({ status: 503, body: { error: "ADVISORIES_UNAVAILABLE" } });
    vi.mocked(api.getLifecycleAdvisorySources).mockRejectedValue({ status: 503, body: { error: "SOURCES_UNAVAILABLE" } });
    themed(<LifecycleVulnerabilitiesPanel />);
    expect(await screen.findByText("ADVISORIES_UNAVAILABLE")).toBeInTheDocument();
    expect(await screen.findByText("SOURCES_UNAVAILABLE")).toBeInTheDocument();
    expect(screen.queryByRole("table")).toBeNull();
    expect(screen.queryByText(/No advisory source has been loaded yet/)).toBeNull();
  });
});

describe("advisory reads", () => {
  afterEach(() => { vi.unstubAllGlobals(); });
  it("asks the three addresses and encodes the colon of an advisory id", async () => {
    const actual = await vi.importActual<typeof import("../src/auth/adminApi")>("../src/auth/adminApi");
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ advisories: [], sources: [], devices: [] }), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    await actual.getLifecycleAdvisories();
    await actual.getLifecycleAdvisory("paloalto:CVE-2026-0001/../x");
    await actual.getLifecycleAdvisorySources();
    expect(fetchMock.mock.calls.map(c => (c as unknown[])[0])).toEqual(["/api/v2/lifecycle/advisories", "/api/v2/lifecycle/advisories/paloalto%3ACVE-2026-0001%2F..%2Fx", "/api/v2/lifecycle/advisory-sources"]);
    for (const c of fetchMock.mock.calls) expect(((c as unknown[])[1] as RequestInit).method).toBe("GET");
  });
});
