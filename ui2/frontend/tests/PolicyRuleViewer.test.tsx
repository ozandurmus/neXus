import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PolicyRuleTable, RuleDetailPanel, RuleHistory, RuleMetrics, RULE_ROW_HEIGHT, containerLabel, hygieneBadges, scheduleLabel, sectionLabel } from "../src/screens/PolicyRuleViewer";
import type { PolicyHygieneAssessment, PolicyMetadata, PolicyRule, PolicySchedule, PolicySection } from "../src/auth/adminApi";

const metadata: PolicyMetadata = { id: "policy-1", name: "OBJ-POLICY-01", sourceId: "source-1", sourceName: "MGR-BRAVO-01", vendor: "CP", containerId: "domain-1", containerName: "DOM-TANGO-01", collectedAt: "2026-10-02T00:00:00Z", artefactRef: "artifact-1", targets: [] };
const objects = new Map(Array.from({ length: 6 }, (_, i) => [String(i), { id: String(i), name: `OBJ-ADDRESS-0${i}`, type: "address", status: "RESOLVED" }]));
const empty = { refs: [], negated: false };
const schedule: PolicySchedule = { kind: "one-time", start: null, end: "2026-10-01T23:59", windows: [], timezone: "UTC", timezoneKnown: false };
const rule: PolicyRule = { id: "rule-1", uuid: "uuid-1", name: "OBJ-RULE-01", number: 1, enabled: false, source: { refs: [...objects.keys()], negated: true }, destination: empty, service: empty, application: empty, action: "Drop", comment: "Withheld in AIView", log: "Log", extras: { "layer-name": ["OBJ-LAYER-01"] }, timeStatus: "expired", schedules: [schedule], permissiveness: { level: "High", reasons: ["Any service", "Any destination"] } };
const section: PolicySection = { id: "section-1", name: "OBJ-SECTION-01", source: "CP access layer", parentRuleId: null, rules: [rule], total: 1 };
function mount(element: React.ReactNode) { return render(<ThemeProvider theme={m3Theme}>{element}</ThemeProvider>); }
const ruleTable = (props: Partial<React.ComponentProps<typeof PolicyRuleTable>> = {}) => <PolicyRuleTable sections={[section]} collapsed={new Set()} scrollTop={0} toggle={vi.fn()} objects={objects} metadata={metadata}
  openObject={vi.fn()} selected={new Set()} onSelect={vi.fn()} {...props} />;
const column = (row: HTMLElement, key: string) => row.querySelector<HTMLElement>(`[data-column="${key}"]`)!;
const expand = (number = 1) => { fireEvent.click(screen.getByRole("row", { name: new RegExp(`^Rule ${number}:`) })); return screen.getByRole("region", { name: `Rule ${number} details` }); };
afterEach(() => { vi.unstubAllGlobals(); });
it("explains disabled hit collection without showing a zero", async () => {
  mount(<RuleMetrics rule={rule} />);
  const tile = screen.getByText("Last hit").parentElement!;
  expect(tile).toHaveTextContent("UNKNOWN");
  fireEvent.mouseOver(tile);
  expect(await screen.findByRole("tooltip")).toHaveTextContent("Last hit unknown");
});
it("shows collected last hit in table rows and per-firewall counters and timestamps in the drawer", () => {
  const collectedAt = "2026-10-02T12:00:00Z", lastHit = "2026-07-01T12:00:00Z";
  const counted: PolicyRule = { ...rule, hitCounts: { hits: 5, firstHit: null, lastHit, source: "device", collectedAt,
    firewalls: [{ deviceId: "device-1", context: "CTX-TANGO-01", hits: 2, firstHit: null, lastHit, createdAt: "2026-01-01T00:00:00Z", modifiedAt: null, collectedAt },
      { deviceId: "device-2", context: "CTX-TANGO-01", hits: 3, firstHit: null, lastHit: null, createdAt: null, modifiedAt: null, collectedAt }] } };
  const { unmount } = mount(ruleTable({ sections: [{ ...section, rules: [counted] }] }));
  const row = screen.getByRole("row", { name: "Rule 1: OBJ-RULE-01" });
  expect(column(row, "lastHit")).toHaveTextContent(/d ago/);
  expect(column(row, "hits")).toHaveTextContent("5");
  const details = expand();
  expect(within(details.querySelector<HTMLElement>(".policy-rule-metrics")!).getByText("First hit").parentElement).toHaveTextContent("UNKNOWN");
  unmount();
  mount(<RuleDetailPanel rule={counted} section={section} metadata={{ ...metadata, vendor: "PAN", targets: [
    { deviceId: "device-1", name: "FW-TANGO-04", context: "CTX-TANGO-01", syncStatus: "UNKNOWN" },
    { deviceId: "device-2", name: "FW-BRAVO-02", context: "CTX-TANGO-01", syncStatus: "UNKNOWN" }] }} objects={objects} openObject={vi.fn()} />);
  const table = screen.getByRole("table", { name: "Per-firewall hit counts" });
  expect(within(table).getByText("FW-TANGO-04")).toBeInTheDocument();
  expect(within(table).getByText("FW-BRAVO-02")).toBeInTheDocument();
  expect(within(table).getByText("2")).toBeInTheDocument(); expect(within(table).getByText("3")).toBeInTheDocument();
  expect(within(table).getByText("2026-01-01T00:00:00Z")).toBeInTheDocument();
  expect(screen.getByText(/Hits: 5/)).toHaveTextContent("Source: device");
});
it("renders compact rows with +N more, negation, disabled, expired and unavailable metrics and expands every member inline", () => {
  const openObject = vi.fn(), onSelect = vi.fn();
  mount(ruleTable({ openObject, onSelect }));
  const row = screen.getByRole("row", { name: "Rule 1: OBJ-RULE-01" });
  expect(row).toHaveAttribute("aria-expanded", "false");
  expect(within(row).getByText("NOT")).toBeInTheDocument();
  expect(column(row, "hygiene")).toHaveTextContent("Disabled"); expect(column(row, "hygiene")).toHaveTextContent("Expired");
  expect(column(row, "source")).toHaveTextContent("OBJ-ADDRESS-00, OBJ-ADDRESS-01+4 more");
  expect(screen.queryByText("OBJ-ADDRESS-05")).toBeNull();
  fireEvent.click(within(row).getByText("OBJ-ADDRESS-01")); expect(openObject).toHaveBeenCalledWith("1");
  fireEvent.click(within(row).getByRole("checkbox")); expect(onSelect).toHaveBeenCalledWith("rule-1");
  expect(row).toHaveAttribute("aria-expanded", "false");
  expect(column(row, "permissiveness")).toHaveTextContent("High");
  expect(column(row, "lastHit")).toHaveTextContent("UNKNOWN");
  expect(column(row, "action")).toHaveTextContent("✕Drop");
  const details = expand();
  expect(row).toHaveAttribute("aria-expanded", "true");
  expect(within(details).getByText("Access layer: OBJ-LAYER-01", { exact: false })).toBeInTheDocument();
  expect(within(details).getByText("Disabled")).toBeInTheDocument(); expect(within(details).getByText("Expired")).toBeInTheDocument();
  fireEvent.click(within(details).getByRole("button", { name: "+2 ▾" })); expect(within(details).getByText("OBJ-ADDRESS-05")).toBeInTheDocument();
  fireEvent.click(within(details).getByText("OBJ-ADDRESS-05")); expect(openObject).toHaveBeenCalledWith("5");
  expect(within(details).getByText("Why this level: Any service; Any destination")).toBeInTheDocument();
  expect(within(details).getByText("Withheld in AIView")).toBeInTheDocument();
  expect(within(details).getByText("Log/track: Log")).toBeInTheDocument();
  expect(within(details).getByText(/Until 1 Oct 2026 23:59/)).toBeInTheDocument();
  expect(within(details).getByRole("button", { name: "Rule history" })).toBeInTheDocument();
  fireEvent.click(row);
  expect(screen.queryByRole("region", { name: "Rule 1 details" })).toBeNull();
});
it("toggles details with Enter on a focused row, moves with arrow keys and ignores keys from inner controls", () => {
  const second: PolicyRule = { ...rule, id: "rule-2", number: 2, name: "OBJ-RULE-02" };
  mount(ruleTable({ sections: [{ ...section, rules: [rule, second], total: 2 }] }));
  const first = screen.getByRole("row", { name: "Rule 1: OBJ-RULE-01" });
  expect(first).toHaveAttribute("tabindex", "0");
  first.focus(); fireEvent.keyDown(first, { key: "Enter" });
  expect(screen.getByRole("region", { name: "Rule 1 details" })).toBeInTheDocument();
  fireEvent.keyDown(first, { key: "Enter" });
  expect(screen.queryByRole("region", { name: "Rule 1 details" })).toBeNull();
  fireEvent.keyDown(first, { key: "ArrowDown" });
  expect(screen.getByRole("row", { name: "Rule 2: OBJ-RULE-02" })).toHaveFocus();
  fireEvent.keyDown(within(first).getByRole("checkbox"), { key: "Enter" });
  expect(screen.queryByRole("region", { name: "Rule 1 details" })).toBeNull();
});
it("badges hygiene only from stored findings and rule state, and says UNKNOWN when no analysis exists", () => {
  const hygiene: PolicyHygieneAssessment = { shadowStatus: "REDUNDANT", shadowReason: null, coveringRuleId: "rule-0", timeStatus: "always", expiring: false,
    counterWindow: "Counter window unknown", hitSource: "mds", hitsCollectedAt: null, permissiveness: { level: "Low", reasons: [] },
    findings: [{ findingClass: "shadowed", severity: "MEDIUM", evidence: "Earlier rule covers this match." }, { findingClass: "broad", severity: "HIGH", evidence: "Any source." },
      { findingClass: "broad", severity: "MEDIUM", evidence: "Any service." }, { findingClass: "unknown", severity: "LOW", evidence: "Hit counts not collected." }] };
  const analysed: PolicyRule = { ...rule, enabled: true, timeStatus: "always", hygiene };
  expect(hygieneBadges(analysed).map(b => [b.label, b.severity])).toEqual([["Shadowed", "MEDIUM"], ["Broad", "HIGH"], ["Unknown", "UNKNOWN"]]);
  expect(hygieneBadges({ ...analysed, hygiene: { ...hygiene, findings: [] }, expiring: true })).toEqual([expect.objectContaining({ label: "Expiring" })]);
  mount(ruleTable({ sections: [{ ...section, total: 3, rules: [analysed, { ...analysed, id: "clean", number: 2, hygiene: { ...hygiene, findings: [] } }, { ...analysed, id: "none", number: 3, hygiene: undefined }] }] }));
  const [first, clean, none] = [1, 2, 3].map(n => column(screen.getByRole("row", { name: new RegExp(`^Rule ${n}:`) }), "hygiene"));
  expect(first).toHaveTextContent("ShadowedBroadUnknown");
  expect(first.querySelector('[data-hygiene="broad"]')).toHaveAttribute("title", "HIGH · Any source.; Any service.");
  expect(first.querySelector('[data-hygiene="unknown"]')).toHaveAttribute("title", "UNKNOWN because Hit counts not collected.");
  expect(clean).toHaveTextContent("No findings");
  expect(none).toHaveTextContent("UNKNOWN");
  expect(none.textContent).not.toMatch(/No findings/);
});
it("shows the application column only for vendors or pages that carry applications", () => {
  const { unmount } = mount(ruleTable());
  expect(screen.queryByRole("columnheader", { name: "Application" })).toBeNull();
  expect(screen.getAllByRole("columnheader").map(h => h.textContent)).toEqual(["", "#", "Name", "Source", "Destination", "Service", "Action", "Hits", "Last hit", "Permissiveness", "Hygiene"]);
  unmount();
  mount(ruleTable({ metadata: { ...metadata, vendor: "PAN" }, headerSelect: <input type="checkbox" aria-label="Select all" /> }));
  expect(screen.getByRole("columnheader", { name: "Application" })).toBeInTheDocument();
  expect(within(screen.getAllByRole("columnheader")[0]).getByRole("checkbox", { name: "Select all" })).toBeInTheDocument();
});
it("keeps the rendered window bounded with an expanded detail row and reaches the last row", () => {
  const rules = Array.from({ length: 1000 }, (_, i) => ({ ...rule, id: `rule-${i}`, number: i + 1, name: `OBJ-RULE-${i}` }));
  const props = { sections: [{ ...section, rules, total: 1000 }] };
  const view = mount(ruleTable(props));
  expand(1);
  expect(view.container.querySelectorAll("[data-policy-rule]").length).toBeLessThanOrEqual(24);
  view.rerender(<ThemeProvider theme={m3Theme}>{ruleTable({ ...props, scrollTop: 1000 * RULE_ROW_HEIGHT, viewportHeight: 400 })}</ThemeProvider>);
  expect(screen.getByText("OBJ-RULE-999")).toBeInTheDocument();
  expect(screen.queryByRole("region", { name: "Rule 1 details" })).toBeNull();
  expect(view.container.querySelectorAll("[data-policy-rule]").length).toBeLessThanOrEqual(24);
  view.rerender(<ThemeProvider theme={m3Theme}>{ruleTable(props)}</ThemeProvider>);
  expect(screen.getByRole("region", { name: "Rule 1 details" })).toBeInTheDocument();
});
it("keeps detail nav on the page and exposes object and revision views", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ revisions: [], page: 0 }), { status: 200 })));
  const openObject = vi.fn();
  mount(<RuleDetailPanel rule={rule} section={section} metadata={metadata} objects={objects} openObject={openObject} />);
  expect(screen.getByText(/Domain: DOM-TANGO-01/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Shadowing" })).toBeEnabled();
  fireEvent.click(screen.getByRole("button", { name: "Objects" }));
  fireEvent.click(screen.getByRole("button", { name: "OBJ-ADDRESS-05" })); expect(openObject).toHaveBeenCalledWith("5");
  fireEvent.click(screen.getByRole("button", { name: "Rule history" }));
  expect(await screen.findByText("No recorded changes.")).toBeInTheDocument();
  expect(vi.mocked(fetch).mock.calls[0][0]).toContain("rule=rule-1");
});
it("renders history added/removed/modified and expandable field changes with name fallback", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ revisions: ["added", "removed", "modified"].map((changeType, index) => ({ revision: index + 1, ruleId: "rule-1", identityFallback: index === 0, changeType, changedOn: null, changedBy: "OBJ-EDITOR-01", collectedAt: "2026-10-02T00:00:00Z", changes: [{ field: "number", before: 1, after: 2 }, { field: "service", before: { refs: [{ name: "OBJ-SERVICE-01" }] }, after: { refs: [{ name: "OBJ-SERVICE-02" }] } }] })), page: 0 }), { status: 200 })));
  mount(<RuleHistory policy="policy-1" />);
  expect(await screen.findByText(/#1 · added · name fallback/)).toBeInTheDocument();
  const summary = screen.getByText("#3 · modified"); fireEvent.click(summary);
  expect(summary.closest("details")).toHaveAttribute("open");
  expect(screen.getAllByText("Moved position")).toHaveLength(3);
  expect(screen.getAllByText(/OBJ-SERVICE-01.*OBJ-SERVICE-02/)).toHaveLength(3);
});
it("uses vendor container terminology and labels timezone uncertainty", () => {
  expect(containerLabel(metadata, { ...section, parentRuleId: "parent-1" }, rule)).toBe("Inline layer: OBJ-LAYER-01");
  expect(containerLabel({ ...metadata, vendor: "PAN" }, { ...section, source: "Shared", name: "Pre rules" }, rule)).toBe("Shared (Pre)");
  expect(containerLabel({ ...metadata, vendor: "PAN" }, { ...section, source: "DOM-TANGO-01", name: "Post rules" }, rule)).toBe("Device group: DOM-TANGO-01 (Post)");
  expect(scheduleLabel(schedule)).toContain("Until 1 Oct 2026 23:59"); expect(scheduleLabel(schedule)).toContain("device timezone unknown");
  expect(scheduleLabel({ ...schedule, kind: "recurring", end: null, windows: [{ days: [1,2,3,4,5], monthDays: [], start: "08:00", end: "18:00" }] })).toContain("Weekdays 08:00–18:00");
});

it("labels PAN sections precisely, collapses empty sections into one row and keeps masked group headers in the table", () => {
  const meta = { ...metadata, vendor: "PAN", name: "POL-ALPHA-01" };
  const sections = ["Pre rules", "Post rules"].flatMap(name => ["Shared", "DOM-TANGO-01"].map(source => ({ ...section, id: `${source}-${name}`, name, source })));
  expect(sections.map(s => sectionLabel(meta, s))).toEqual(["Shared pre-rules", "Device-group pre-rules", "Shared post-rules", "Device-group post-rules"]);
  expect(sectionLabel(metadata, section)).toBe("OBJ-SECTION-01");
  const { container } = mount(ruleTable({ sections: sections.map((s, i) => ({ ...s, total: i ? 0 : 1, rules: i ? [] : [{ ...rule, name: "RULE-BRAVO-12", uuid: "RULE-TANGO-01" }] })), objects: new Map([...objects].map(([id, object]) => [id, { ...object, name: `ADDR-ALPHA-0${id}` }])), metadata: meta }));
  expect(screen.getByText("3 empty sections")).toBeInTheDocument();
  const group = screen.getByRole("button", { name: /Shared pre-rules · 1/ });
  expect(group).toHaveAttribute("aria-expanded", "true");
  expect(container.querySelectorAll("[data-policy-section]")).toHaveLength(1);
  expect(screen.getByRole("table", { name: "Policy rules" })).toHaveClass("MuiTable-stickyHeader");
  expect(screen.getByRole("columnheader", { name: "Application" })).toBeInTheDocument();
  const details = expand();
  expect(within(details).getByText(/Section: Shared pre-rules/)).toBeInTheDocument();
  expect(within(details).getByText("UID on device: RULE-TANGO-01")).toBeInTheDocument();
  expect(container.querySelector(".policy-rule-fields")).toBeInTheDocument();
  expect(container.querySelector(".policy-rule-metrics")).toHaveStyle({ width: "220px", gridTemplateColumns: "repeat(2, minmax(0, 1fr))" });
  expect(container.textContent).not.toMatch(/[a-f0-9]{32}/i);
});

it("shows hit timestamps with relative last-hit and absolute title plus the full schedule in the expanded row", async () => {
  const lastHit = new Date(Date.now() - 3600000).toISOString();
  const counted: PolicyRule = { ...rule, hitCounts: { hits: 7, firstHit: "2026-01-01T00:00:00Z", lastHit, source: "mds", collectedAt: "2026-10-05T00:00:00Z", firewalls: [] },
    schedules: [{ kind: "recurring", start: null, end: null, windows: [{ days: [1, 2, 3, 4, 5], monthDays: [], start: "08:00", end: "17:00" }], timezone: "UTC", timezoneKnown: true }] };
  mount(ruleTable({ sections: [{ ...section, rules: [counted] }] }));
  const row = screen.getByRole("row", { name: "Rule 1: OBJ-RULE-01" });
  expect(column(row, "lastHit")).toHaveTextContent("1 h ago");
  expect(column(row, "lastHit").firstElementChild).toHaveAttribute("title", expect.stringContaining(lastHit));
  expect(column(row, "hits")).toHaveTextContent("7");
  const details = expand();
  expect(within(details).getByText("First hit").parentElement).toHaveTextContent("2026-01-01 03:00:00");
  const tile = within(details).getByText("Last hit").parentElement!;
  fireEvent.mouseOver(tile);
  expect(await screen.findByRole("tooltip")).toHaveTextContent(lastHit);
  const schedule = within(details).getByText("Weekdays 08:00–17:00 · UTC");
  expect(schedule).toBeVisible();
});
