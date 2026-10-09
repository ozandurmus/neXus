import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { VirtualPolicyCards, RuleDetailPanel, RuleHistory, RuleMetrics, containerLabel, scheduleLabel, sectionLabel } from "../src/screens/PolicyRuleViewer";
import type { PolicyMetadata, PolicyRule, PolicySchedule, PolicySection } from "../src/auth/adminApi";

const metadata: PolicyMetadata = { id: "policy-1", name: "OBJ-POLICY-01", sourceId: "source-1", sourceName: "MGR-BRAVO-01", vendor: "CP", containerId: "domain-1", containerName: "DOM-TANGO-01", collectedAt: "2026-10-02T00:00:00Z", artefactRef: "artifact-1", targets: [] };
const objects = new Map(Array.from({ length: 6 }, (_, i) => [String(i), { id: String(i), name: `OBJ-ADDRESS-0${i}`, type: "address", status: "RESOLVED" }]));
const empty = { refs: [], negated: false };
const schedule: PolicySchedule = { kind: "one-time", start: null, end: "2026-10-01T23:59", windows: [], timezone: "UTC", timezoneKnown: false };
const rule: PolicyRule = { id: "rule-1", uuid: "uuid-1", name: "OBJ-RULE-01", number: 1, enabled: false, source: { refs: [...objects.keys()], negated: true }, destination: empty, service: empty, application: empty, action: "Drop", comment: "Withheld in AIView", log: "Log", extras: { "layer-name": ["OBJ-LAYER-01"] }, timeStatus: "expired", schedules: [schedule], permissiveness: { level: "High", reasons: ["Any service", "Any destination"] } };
const section: PolicySection = { id: "section-1", name: "OBJ-SECTION-01", source: "CP access layer", parentRuleId: null, rules: [rule], total: 1 };
function mount(element: React.ReactNode) { return render(<ThemeProvider theme={m3Theme}>{element}</ThemeProvider>); }
afterEach(() => { vi.unstubAllGlobals(); });
it("explains disabled hit collection without showing a zero", async () => {
  mount(<RuleMetrics rule={rule} />);
  const tile = screen.getByText("Last hit").parentElement!;
  expect(tile).toHaveTextContent("UNKNOWN");
  fireEvent.mouseOver(tile);
  expect(await screen.findByRole("tooltip")).toHaveTextContent("Last hit unknown");
});
it("shows collected last hit in cards and per-firewall counters and timestamps in the drawer", () => {
  const collectedAt = "2026-10-02T12:00:00Z", lastHit = "2026-07-01T12:00:00Z";
  const counted: PolicyRule = { ...rule, hitCounts: { hits: 5, firstHit: null, lastHit, source: "device", collectedAt,
    firewalls: [{ deviceId: "device-1", context: "CTX-TANGO-01", hits: 2, firstHit: null, lastHit, createdAt: "2026-01-01T00:00:00Z", modifiedAt: null, collectedAt },
      { deviceId: "device-2", context: "CTX-TANGO-01", hits: 3, firstHit: null, lastHit: null, createdAt: null, modifiedAt: null, collectedAt }] } };
  const { unmount } = mount(<VirtualPolicyCards sections={[{ ...section, rules: [counted] }]} collapsed={new Set()} scrollTop={0} toggle={vi.fn()} objects={objects} metadata={metadata} openObject={vi.fn()} openRule={vi.fn()} selected={new Set()} onSelect={vi.fn()} />);
  expect(screen.getByText("Last hit").parentElement).toHaveTextContent(/d ago/);
  expect(screen.getByText("Hits").parentElement).toHaveTextContent("5");
  expect(screen.getByText("First hit").parentElement).toHaveTextContent("UNKNOWN");
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
it("renders dense cards with expansion, negation, disabled, expired and unavailable metrics", () => {
  const openObject = vi.fn(), onSelect = vi.fn(), openRule = vi.fn();
  mount(<VirtualPolicyCards sections={[section]} collapsed={new Set()} scrollTop={0} toggle={vi.fn()} objects={objects} metadata={metadata} openObject={openObject} openRule={openRule} selected={new Set()} onSelect={onSelect} />);
  const card = screen.getByRole("article");
  expect(within(card).getByText("NOT")).toBeInTheDocument();
  expect(within(card).getByText("Disabled")).toBeInTheDocument(); expect(within(card).getByText("Expired")).toBeInTheDocument();
  expect(within(card).queryByText("OBJ-ADDRESS-05")).toBeNull();
  fireEvent.click(within(card).getByRole("button", { name: "+2 ▾" })); expect(within(card).getByText("OBJ-ADDRESS-05")).toBeInTheDocument();
  fireEvent.click(within(card).getByText("OBJ-ADDRESS-05")); expect(openObject).toHaveBeenCalledWith("5");
  fireEvent.click(within(card).getByRole("checkbox")); expect(onSelect).toHaveBeenCalledWith("rule-1");
  fireEvent.click(within(card).getByRole("button", { name: "OBJ-RULE-01" })); expect(openRule).toHaveBeenCalledWith(rule);
  expect(within(card).getByText("Access layer: OBJ-LAYER-01")).toBeInTheDocument();
  expect(within(card).getByText("High")).toBeInTheDocument();
  expect(within(card).getByText("Last hit").parentElement).toHaveTextContent("UNKNOWN");
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

it("labels PAN sections precisely, collapses empty sections and uses responsive masked cards", () => {
  const meta = { ...metadata, vendor: "PAN", name: "POL-ALPHA-01" };
  const sections = ["Pre rules", "Post rules"].flatMap(name => ["Shared", "DOM-TANGO-01"].map(source => ({ ...section, id: `${source}-${name}`, name, source })));
  expect(sections.map(s => sectionLabel(meta, s))).toEqual(["Shared pre-rules", "Device-group pre-rules", "Shared post-rules", "Device-group post-rules"]);
  expect(sectionLabel(metadata, section)).toBe("OBJ-SECTION-01");
  const { container } = mount(<VirtualPolicyCards sections={sections.map((s, i) => ({ ...s, total: i ? 0 : 1, rules: i ? [] : [{ ...rule, name: "RULE-BRAVO-12", uuid: "RULE-TANGO-01" }] }))} collapsed={new Set()} scrollTop={0} toggle={vi.fn()} objects={new Map([...objects].map(([id, object]) => [id, { ...object, name: `ADDR-ALPHA-0${id}` }]))} metadata={meta} openObject={vi.fn()} openRule={vi.fn()} selected={new Set()} onSelect={vi.fn()} />);
  expect(screen.getByText("3 empty sections")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /Shared pre-rules/ })).toBeInTheDocument();
  expect(container.querySelector(".policy-rule-card.wide")).toBeInTheDocument();
  expect(container.querySelector(".policy-rule-fields.compact")).toBeInTheDocument();
  expect(container.querySelector(".policy-rule-misc details")).toBeInTheDocument();
  expect(container.querySelector(".policy-rule-metrics")).toHaveStyle({ width: "220px", gridTemplateColumns: "repeat(2, minmax(0, 1fr))" });
  expect(container.textContent).not.toMatch(/[a-f0-9]{32}/i);
});

it("shows hit timestamps with relative last-hit and absolute tooltip plus the full schedule in compact cards", async () => {
  const lastHit = new Date(Date.now() - 3600000).toISOString();
  const counted: PolicyRule = { ...rule, hitCounts: { hits: 7, firstHit: "2026-01-01T00:00:00Z", lastHit, source: "mds", collectedAt: "2026-10-05T00:00:00Z", firewalls: [] },
    schedules: [{ kind: "recurring", start: null, end: null, windows: [{ days: [1, 2, 3, 4, 5], monthDays: [], start: "08:00", end: "17:00" }], timezone: "UTC", timezoneKnown: true }] };
  mount(<VirtualPolicyCards sections={[{ ...section, rules: [counted] }]} collapsed={new Set()} scrollTop={0} toggle={vi.fn()} objects={objects} metadata={metadata} openObject={vi.fn()} openRule={vi.fn()} selected={new Set()} onSelect={vi.fn()} />);
  const tile = screen.getByText("Last hit").parentElement!;
  expect(tile).toHaveTextContent("1 h ago");
  fireEvent.mouseOver(tile);
  expect(await screen.findByRole("tooltip")).toHaveTextContent(lastHit);
  expect(screen.getByText("First hit").parentElement).toHaveTextContent("2026-01-01 03:00:00");
  expect(screen.getByText("Hits").parentElement).toHaveTextContent("7");
  const scheduleCell = screen.getByText("SCHEDULE:").parentElement!;
  expect(scheduleCell).toHaveTextContent("Weekdays 08:00–17:00 · UTC");
  expect(scheduleCell).toBeVisible();
});
