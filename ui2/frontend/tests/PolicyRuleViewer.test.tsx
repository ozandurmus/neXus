import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PolicyRuleTable, RuleDetailPanel, RuleHistory, RuleMetrics, RULE_ROW_HEIGHT, REASON_GROUPS, POLICY_BUSY_MESSAGE, actionWord, containerLabel, documentationEntries, hygieneBadges, isPolicyBusy, objectKind, permissivenessWords, policyFailureText, reportPolicyLoadError, scheduleLabel, sectionLabel, snapshotFreshness, unknownFlagWords, unknownReasonGroup, unknownReasons, usageWords } from "../src/screens/PolicyRuleViewer";
import type { PolicyHygieneAssessment, PolicyMetadata, PolicyObject, PolicyRule, PolicySchedule, PolicySection } from "../src/auth/adminApi";

const metadata: PolicyMetadata = { id: "policy-1", name: "OBJ-POLICY-01", sourceId: "source-1", sourceName: "MGR-BRAVO-01", vendor: "CP", containerId: "domain-1", containerName: "DOM-TANGO-01", collectedAt: "2026-10-02T00:00:00Z", artefactRef: "artifact-1", targets: [] };
const objects = new Map(Array.from({ length: 6 }, (_, i) => [String(i), { id: String(i), name: `OBJ-ADDRESS-0${i}`, type: "address", status: "RESOLVED" }]));
const empty = { refs: [], negated: false };
const schedule: PolicySchedule = { kind: "one-time", start: null, end: "2026-10-01T23:59", windows: [], timezone: "UTC", timezoneKnown: false };
const rule: PolicyRule = { id: "rule-1", uuid: "uuid-1", name: "OBJ-RULE-01", number: 1, enabled: false, source: { refs: [...objects.keys()], negated: true }, destination: empty, service: empty, application: empty, action: "Drop", comment: "Withheld in AIView", log: "Log", extras: { "layer-name": ["OBJ-LAYER-01"] }, timeStatus: "expired", schedules: [schedule], permissiveness: { level: "High", reasons: ["Any service", "Any destination"] } };
const section: PolicySection = { id: "section-1", name: "OBJ-SECTION-01", source: "CP access layer", parentRuleId: null, rules: [rule], total: 1 };
function mount(element: React.ReactNode) { return render(<ThemeProvider theme={m3Theme}>{element}</ThemeProvider>); }
const ruleTable = (props: Partial<React.ComponentProps<typeof PolicyRuleTable>> = {}) => <PolicyRuleTable sections={[section]} collapsed={new Set()} scrollTop={0} toggle={vi.fn()} objects={objects} metadata={metadata}
  openObject={vi.fn()} selected={new Set()} onSelect={vi.fn()} {...props} />;
const column = (row: HTMLElement, key: string) => row.querySelector<HTMLElement>(`[data-column="${key}"], [data-field="${key}"]`)!;
// The usage cell carries its state and hover detail on the box inside the td.
const usageBox = (row: HTMLElement) => column(row, "usage").firstElementChild!;
const expand = (number = 1) => { fireEvent.click(screen.getByRole("row", { name: new RegExp(`^Rule ${number}:`) })); return screen.getByRole("region", { name: `Rule ${number} details` }); };
afterEach(() => { vi.unstubAllGlobals(); });
it("explains missing hit evidence without showing a zero", async () => {
  mount(<RuleMetrics rule={rule} />);
  const tile = screen.getByText("Last hit").parentElement!;
  expect(tile).toHaveTextContent("UNKNOWN");
  fireEvent.mouseOver(tile);
  expect(await screen.findByRole("tooltip")).toHaveTextContent("Hit evidence is missing from this snapshot.");
});
it("words usage honestly: UNKNOWN when missing, Not collected when null, Never at zero, partial without a time, stale and recent by age", () => {
  const now = new Date("2026-10-19T12:00:00Z");
  const base = { hits: 12, firstHit: null, lastHit: "2026-10-16T12:00:00Z", source: "device" as const, collectedAt: "2026-10-19T00:00:00Z", firewalls: [] };
  expect(usageWords({}, now)).toMatchObject({ hits: "UNKNOWN", lastHit: "UNKNOWN", state: "unknown" });
  expect(usageWords({ hitCounts: null }, now)).toMatchObject({ hits: "Not collected", lastHit: "Not collected", state: "not-collected" });
  // Zero hits is "none recorded", not "never": the counters' window is unknown unless the analysis says otherwise.
  const none = usageWords({ hitCounts: { ...base, hits: 0, lastHit: null } }, now);
  expect(none).toMatchObject({ hits: "0", lastHit: "None recorded", state: "never", window: "Counter window unknown" });
  expect(none.detail).toContain("Counter window unknown"); expect(none.detail).not.toMatch(/never matched/i);
  const windowed = usageWords({ hitCounts: { ...base, hits: 0, lastHit: null, level: "zero" }, hygiene: { counterWindow: "Counter window unknown; first hit is not a counter start." } as PolicyHygieneAssessment }, now);
  expect(windowed.window).toBe("Counter window unknown; first hit is not a counter start"); expect(windowed.detail).toContain("level zero");
  expect(usageWords({ hitCounts: { ...base, level: "UNKNOWN" } }, now).detail).not.toContain("level");
  expect(usageWords({ hitCounts: { ...base, lastHit: null } }, now)).toMatchObject({ hits: "12", lastHit: "UNKNOWN", state: "partial" });
  expect(usageWords({ hitCounts: { ...base, hits: null, lastHit: null } }, now)).toMatchObject({ hits: "UNKNOWN", lastHit: "UNKNOWN", state: "partial" });
  expect(usageWords({ hitCounts: { ...base, hits: 1234567 } }, now)).toMatchObject({ hits: "1,234,567", lastHit: "3 d ago", state: "recent" });
  const stale = usageWords({ hitCounts: { ...base, lastHit: "2026-05-01T00:00:00Z", source: "mds" } }, now);
  expect(stale.state).toBe("stale"); expect(stale.detail).toContain("2026-05-01T00:00:00Z"); expect(stale.detail).toContain("Management server counters");
  expect(usageWords({ hitCounts: { ...base, lastHit: "not-a-time" } }, now)).toMatchObject({ lastHit: "UNKNOWN", state: "partial" });
});
it("judges stale usage at the counters' collection time, as the hygiene unused finding does, while the age shown stays wall-clock", () => {
  const now = new Date("2026-10-30T12:00:00Z");
  // Collected 29 days ago; last hit 65 days before that collection: 94 days on the wall clock, 65 at collection time.
  const counts = { hits: 12, firstHit: null, lastHit: "2026-07-28T12:00:00Z", source: "mds" as const, collectedAt: "2026-10-01T12:00:00Z", firewalls: [] };
  const words = usageWords({ hitCounts: counts }, now);
  expect(words.state).toBe("recent"); expect(words.lastHit).toBe("94 d ago");
  expect(usageWords({ hitCounts: counts }, now, 60).state).toBe("stale");
  // Without a readable collection time the wall clock is the only anchor left.
  expect(usageWords({ hitCounts: { ...counts, collectedAt: "not-a-time" } }, now).state).toBe("stale");
});
it("reads the bulkhead's POLICY_BUSY as a wait on every load path and records it as its own category", () => {
  const busy = { status: 503, body: { error: "POLICY_BUSY" } };
  expect(isPolicyBusy(busy)).toBe(true); expect(isPolicyBusy({ status: 503, body: { error: "Withheld in AIView" } })).toBe(false); expect(isPolicyBusy(new Error("x"))).toBe(false);
  expect(policyFailureText(busy, "Rules could not be loaded.")).toBe(POLICY_BUSY_MESSAGE);
  expect(policyFailureText({ status: 500, body: {} }, "Rules could not be loaded.")).toBe("Rules could not be loaded.");
  const debug = vi.spyOn(console, "debug").mockImplementation(() => {});
  expect(reportPolicyLoadError("rules", busy)).toBe("POLICY_BUSY");
  expect(debug).toHaveBeenCalledWith("policy-load", "rules", "POLICY_BUSY");
});
it("words permissiveness with reasons and never a dash, reads object kinds and action words, and finds documentation fields", () => {
  expect(permissivenessWords({})).toEqual({ level: "Unknown", word: "UNKNOWN", reasons: ["Not assessed in this snapshot"], known: false });
  expect(permissivenessWords({ permissiveness: { level: "Unknown", reasons: ["Incomplete objects; assessment requires analysis"] } })).toMatchObject({ word: "UNKNOWN", reasons: ["Incomplete objects; assessment requires analysis"] });
  expect(permissivenessWords({ permissiveness: { level: "Low", reasons: [] } })).toMatchObject({ word: "Low", reasons: ["No broad element found"], known: true });
  expect(permissivenessWords({ permissiveness: { level: "High", reasons: ["Any source", "Any service"] } })).toMatchObject({ word: "High", reasons: ["Any source", "Any service"] });
  const object = (type: string, values: string[] = [], members: string[] = []) => ({ id: "o", name: "OBJ", type, status: "RESOLVED", values, members });
  expect(objectKind(undefined)).toBe("unresolved"); expect(objectKind(object("any"))).toBe("any"); expect(objectKind(object("address-group"))).toBe("group");
  expect(objectKind(object("service-tcp"))).toBe("service"); expect(objectKind(object("application"))).toBe("application"); expect(objectKind(object("security-zone"))).toBe("zone");
  expect(objectKind(object("address", ["192.0.2.0/24"]))).toBe("network"); expect(objectKind(object("address", ["192.0.2.8/32"]))).toBe("host");
  expect(objectKind(object("address", ["192.0.2.10-192.0.2.20"]))).toBe("range"); expect(objectKind(object("address-range"))).toBe("range");
  expect(objectKind(object("host"))).toBe("host"); expect(objectKind(object("network"))).toBe("network"); expect(objectKind(object("time"))).toBe("time"); expect(objectKind(object("tag"))).toBe("other");
  expect(["allow", "Accept", "deny", "reset-client", "source-and-destination-nat", "no-nat", ""].map(actionWord)).toEqual(["Allow", "Accept", "Deny", "Reset client", "Source and destination NAT", "No NAT", "UNKNOWN"]);
  expect(documentationEntries({ "rule-owner": ["OWNER-ALPHA-01"], tag: ["x"], "ticket-id": ["T-1"] }).map(([key]) => key)).toEqual(["rule-owner", "ticket-id"]);
});
it("shows collected last hit in table rows and per-firewall counters and timestamps in the drawer", () => {
  const collectedAt = "2026-10-02T12:00:00Z", lastHit = "2026-07-01T12:00:00Z";
  const counted: PolicyRule = { ...rule, hitCounts: { hits: 5, firstHit: null, lastHit, source: "device", collectedAt,
    firewalls: [{ deviceId: "device-1", context: "CTX-TANGO-01", hits: 2, firstHit: null, lastHit, createdAt: "2026-01-01T00:00:00Z", modifiedAt: null, collectedAt },
      { deviceId: "device-2", context: "CTX-TANGO-01", hits: 3, firstHit: null, lastHit: null, createdAt: null, modifiedAt: null, collectedAt }] } };
  const { unmount } = mount(ruleTable({ sections: [{ ...section, rules: [counted] }] }));
  const row = screen.getByRole("row", { name: "Rule 1: OBJ-RULE-01" });
  expect(column(row, "lastHit")).toHaveTextContent(/d ago/);
  expect(column(row, "hits")).toHaveTextContent("5 hits");
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
it("renders rule rows as chips with +N, negation, a Disabled word, expired and unavailable evidence, and lists every member in the details", () => {
  const openObject = vi.fn(), onSelect = vi.fn(), openHygiene = vi.fn();
  mount(ruleTable({ openObject, onSelect, openHygiene }));
  const row = screen.getByRole("row", { name: "Rule 1: OBJ-RULE-01" });
  expect(row).toHaveAttribute("aria-expanded", "false");
  expect(within(row).getByText("NOT")).toBeInTheDocument();
  // The disabled word sits next to the name; the flags column keeps the other findings and does not repeat it.
  expect(column(row, "name")).toHaveTextContent("Disabled"); expect(column(row, "name")).toHaveTextContent("Comment withheld");
  expect(column(row, "hygiene")).toHaveTextContent("Expired"); expect(column(row, "hygiene")).not.toHaveTextContent("Disabled");
  const chips = within(column(row, "source")).getAllByRole("button");
  expect(chips.map(chip => chip.textContent)).toEqual(["OBJ-ADDRESS-00", "OBJ-ADDRESS-01", "+4"]);
  expect(chips[0]).toHaveAttribute("data-object-kind", "host"); expect(chips[0]).toHaveAttribute("title", expect.stringContaining("Host"));
  expect(screen.queryByText("OBJ-ADDRESS-05")).toBeNull();
  fireEvent.click(within(row).getByText("OBJ-ADDRESS-01")); expect(openObject).toHaveBeenCalledWith("1");
  fireEvent.click(within(row).getByRole("checkbox")); expect(onSelect).toHaveBeenCalledWith("rule-1");
  expect(row).toHaveAttribute("aria-expanded", "false");
  expect(column(row, "permissiveness")).toHaveTextContent("High"); expect(column(row, "permissiveness")).toHaveTextContent("Any service · Any destination");
  expect(column(row, "lastHit")).toHaveTextContent("UNKNOWN"); expect(column(row, "hits")).toHaveTextContent("UNKNOWN");
  expect(column(row, "action")).toHaveTextContent("✕Drop"); expect(column(row, "action").firstElementChild).toHaveAttribute("data-action-tone", "deny");
  fireEvent.click(within(row).getByRole("button", { name: "4 more objects" }));
  const details = screen.getByRole("region", { name: "Rule 1 details" });
  expect(row).toHaveAttribute("aria-expanded", "true");
  expect(within(details).getByText("Access layer: OBJ-LAYER-01", { exact: false })).toBeInTheDocument();
  expect(within(details).getByText("Disabled")).toBeInTheDocument(); expect(within(details).getByText("Expired")).toBeInTheDocument();
  const sentence = within(details).getByRole("group", { name: "Rule sentence" });
  expect(within(sentence).getAllByRole("button").map(chip => chip.textContent).slice(0, 6)).toEqual(["OBJ-ADDRESS-00", "OBJ-ADDRESS-01", "OBJ-ADDRESS-02", "OBJ-ADDRESS-03", "OBJ-ADDRESS-04", "OBJ-ADDRESS-05"]);
  fireEvent.click(within(sentence).getByText("OBJ-ADDRESS-05")); expect(openObject).toHaveBeenCalledWith("5");
  expect(within(details).getByText("Why this level")).toBeInTheDocument();
  expect(within(within(details).getByRole("list", { name: "Reasons" })).getAllByRole("listitem").map(li => li.textContent)).toEqual(["Any service", "Any destination"]);
  expect(within(details).getByText("Withheld in AIView")).toBeInTheDocument();
  expect(within(details).getByText("Log/track").closest("li")).toHaveTextContent("Log");
  expect(within(details).getAllByText(/Until 1 Oct 2026 23:59/).length).toBeGreaterThan(0);
  expect(within(details).getByText(/Owner, ticket and expiry are not recorded/)).toBeInTheDocument();
  expect(within(details).getByRole("button", { name: "Rule history" })).toBeInTheDocument();
  fireEvent.click(within(details).getByRole("button", { name: "Open in Hygiene" })); expect(openHygiene).toHaveBeenCalledWith("");
  fireEvent.click(row);
  expect(screen.queryByRole("region", { name: "Rule 1 details" })).toBeNull();
});
it("shows Never and Not collected in the row and links flags to the Hygiene tab", () => {
  const openHygiene = vi.fn();
  const never: PolicyRule = { ...rule, id: "never", number: 1, enabled: true, timeStatus: "always", hitCounts: { hits: 0, firstHit: null, lastHit: null, source: "mds", collectedAt: "2026-10-02T00:00:00Z", firewalls: [] },
    permissiveness: { level: "Unknown", reasons: ["Incomplete objects; assessment requires analysis"] },
    hygiene: { shadowStatus: "NOT_SHADOWED", shadowReason: null, coveringRuleId: null, timeStatus: "always", expiring: false, counterWindow: "90 d", hitSource: "mds", hitsCollectedAt: "2026-10-02T00:00:00Z",
      permissiveness: { level: "Unknown", reasons: ["Incomplete objects; assessment requires analysis"] }, findings: [{ findingClass: "unused", severity: "MEDIUM", evidence: "No hits in 90 days." }] } };
  const uncollected: PolicyRule = { ...rule, id: "uncollected", number: 2, enabled: null, hitCounts: null, permissiveness: undefined, source: { refs: ["0"], negated: false } };
  mount(ruleTable({ openHygiene, sections: [{ ...section, total: 2, rules: [never, uncollected] }], objects: new Map<string, PolicyObject>([...objects, ["0", { id: "0", name: "GRP-ALPHA-01", type: "address-group", status: "RESOLVED", members: ["1", "2", "3"] }]]) }));
  const first = screen.getByRole("row", { name: "Rule 1: OBJ-RULE-01" }), second = screen.getByRole("row", { name: "Rule 2: OBJ-RULE-01" });
  expect(column(first, "hits")).toHaveTextContent("0 hits"); expect(column(first, "lastHit")).toHaveTextContent("No hits recorded");
  expect(usageBox(first)).toHaveAttribute("title", expect.stringContaining("90 d"));
  expect(usageBox(first)).toHaveAttribute("data-usage-state", "never");
  expect(column(first, "permissiveness")).toHaveTextContent("UNKNOWN"); expect(column(first, "permissiveness")).toHaveTextContent("Incomplete objects; assessment requires analysis");
  fireEvent.click(within(column(first, "hygiene")).getByRole("button", { name: "Unused" })); expect(openHygiene).toHaveBeenCalledWith("unused");
  expect(column(second, "hits")).toHaveTextContent("Not collected"); expect(column(second, "lastHit")).toHaveTextContent("Not collected");
  expect(usageBox(second)).toHaveAttribute("title", expect.stringContaining("full refresh"));
  expect(column(second, "permissiveness")).toHaveTextContent("UNKNOWNNot assessed in this snapshot");
  expect(column(second, "name")).toHaveTextContent("Enabled UNKNOWN");
  const group = within(column(second, "source")).getByRole("button", { name: /GRP-ALPHA-01/ });
  expect(group).toHaveAttribute("data-object-kind", "group"); expect(group).toHaveTextContent("GRP-ALPHA-013"); expect(group).toHaveAttribute("title", expect.stringContaining("3 members"));
  expect(screen.queryByText("0", { exact: true })).toBeNull();
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
  // The `unknown` class is not a finding: never a badge (it would sit on nearly every row); the cell says it in muted words.
  expect(hygieneBadges(analysed).map(b => [b.label, b.severity])).toEqual([["Shadowed", "MEDIUM"], ["Broad", "HIGH"]]);
  expect(unknownFlagWords(hygiene)).toEqual({ text: "usage not provable", title: "Hit counts not collected." });
  expect(unknownFlagWords({ ...hygiene, findings: [] })).toBeNull(); expect(unknownFlagWords(undefined)).toBeNull();
  expect(hygieneBadges({ ...analysed, hygiene: { ...hygiene, findings: [] }, expiring: true })).toEqual([expect.objectContaining({ label: "Expiring" })]);
  mount(ruleTable({ sections: [{ ...section, total: 3, rules: [analysed, { ...analysed, id: "clean", number: 2, hygiene: { ...hygiene, findings: [] } }, { ...analysed, id: "none", number: 3, hygiene: undefined }] }] }));
  const [first, clean, none] = [1, 2, 3].map(n => column(screen.getByRole("row", { name: new RegExp(`^Rule ${n}:`) }), "hygiene"));
  expect(first).toHaveTextContent("ShadowedBroadusage not provable");
  expect(first.querySelector('[data-hygiene="broad"]')).toHaveAttribute("title", "HIGH · Any source.; Any service.");
  expect(first.querySelector('[data-hygiene="unknown"]')).toBeNull();
  expect(first.querySelector("[data-hygiene-gap]")).toHaveAttribute("title", "Hit counts not collected.");
  expect(clean).toHaveTextContent("No findings");
  expect(none).toHaveTextContent("UNKNOWN");
  expect(none.textContent).not.toMatch(/No findings/);
});
it("shows the application column only for vendors or pages that carry applications", () => {
  const { unmount } = mount(ruleTable());
  expect(screen.queryByRole("columnheader", { name: "Application" })).toBeNull();
  expect(screen.getAllByRole("columnheader").map(h => h.textContent)).toEqual(["", "#", "Rule", "Source", "Destination", "Service", "Action", "Usage", "Permissive", "Flags"]);
  // The short header word keeps the full word as the column's accessible name and on hover (post-release review 2026-10-20, N4).
  expect(screen.getByRole("columnheader", { name: "Permissiveness" })).toHaveAttribute("title", "Permissiveness");
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
  // Shadowing and violations are the Overview's hygiene card; separate views only repeated it.
  expect(screen.getAllByRole("button", { name: /^(Overview|Rule history|Objects|Shadowing|Violations)$/ }).map(b => b.textContent)).toEqual(["Overview", "Rule history", "Objects"]);
  expect(document.querySelector('[aria-label="Rule metrics"] [data-shadow-status]')).toBeInTheDocument();
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

it("shows Palo Alto NAT and default-rule sections in the same rule table with their own labels, action and NAT fields", () => {
  const meta = { ...metadata, vendor: "PAN", name: "POL-ALPHA-01" };
  const named = (name: string, source: string) => ({ ...section, id: `${source}-${name}`, name, source });
  expect(["Pre NAT rules", "Post NAT rules", "Pre default rules", "Post default rules"].map(name => sectionLabel(meta, named(name, "Shared"))))
    .toEqual(["Shared pre-NAT rules", "Shared post-NAT rules", "Shared pre-default rules", "Shared post-default rules"]);
  expect(sectionLabel(meta, named("Post NAT rules", "DOM-TANGO-01"))).toBe("Device-group post-NAT rules");
  expect(containerLabel(meta, named("Pre NAT rules", "Shared"), rule)).toBe("Shared (Pre NAT)");
  expect(containerLabel(meta, named("Post default rules", "DOM-TANGO-01"), rule)).toBe("Device group: DOM-TANGO-01 (Post default)");
  // The CP NAT section keeps its own name; only the fixed PAN names are relabelled.
  expect(sectionLabel(metadata, named("Pre NAT rules", "CP NAT rulebase"))).toBe("Pre NAT rules");
  const natRule = { ...rule, name: "RULE-NAT-01", uuid: "RULE-TANGO-09", action: "source-and-destination-nat",
    extras: { "source-translation-type": ["dynamic-ip-and-port"], "translated-destination-port": ["8443"] } };
  const { container } = mount(ruleTable({ sections: [{ ...named("Pre NAT rules", "Shared"), total: 1, rules: [natRule] }], objects, metadata: meta }));
  const group = screen.getByRole("button", { name: /Shared pre-NAT rules/ });
  expect(group).toHaveAttribute("aria-expanded", "true"); expect(group).toHaveTextContent("1");
  expect(screen.getByText("Source and destination NAT")).toBeInTheDocument();
  expect(container.textContent).toContain("⇄");
});
it("labels PAN sections precisely, collapses empty sections into one row and keeps masked group headers in the table", () => {
  const meta = { ...metadata, vendor: "PAN", name: "POL-ALPHA-01" };
  const sections = ["Pre rules", "Post rules"].flatMap(name => ["Shared", "DOM-TANGO-01"].map(source => ({ ...section, id: `${source}-${name}`, name, source })));
  expect(sections.map(s => sectionLabel(meta, s))).toEqual(["Shared pre-rules", "Device-group pre-rules", "Shared post-rules", "Device-group post-rules"]);
  expect(sectionLabel(metadata, section)).toBe("OBJ-SECTION-01");
  const { container } = mount(ruleTable({ sections: sections.map((s, i) => ({ ...s, total: i ? 0 : 1, rules: i ? [] : [{ ...rule, name: "RULE-BRAVO-12", uuid: "RULE-TANGO-01" }] })), objects: new Map([...objects].map(([id, object]) => [id, { ...object, name: `ADDR-ALPHA-0${id}` }])), metadata: meta }));
  expect(screen.getByText("3 empty sections")).toBeInTheDocument();
  const group = screen.getByRole("button", { name: /Shared pre-rules/ });
  expect(group).toHaveAttribute("aria-expanded", "true"); expect(group).toHaveTextContent("1");
  expect(container.querySelectorAll("[data-policy-section]")).toHaveLength(1);
  expect(screen.getByRole("table", { name: "Policy rules" })).toHaveClass("MuiTable-stickyHeader");
  expect(screen.getByRole("columnheader", { name: "Application" })).toBeInTheDocument();
  const details = expand();
  expect(within(details).getByText(/Section: Shared pre-rules/)).toBeInTheDocument();
  expect(within(details).getByText("UID on device").closest("li")).toHaveTextContent("RULE-TANGO-01");
  expect(container.querySelector(".policy-rule-fields")).toBeInTheDocument();
  expect(container.querySelector(".policy-rule-metrics")).toBeInTheDocument();
  expect(container.textContent).not.toMatch(/[a-f0-9]{32}/i);
});

it("shows hit timestamps with relative last-hit and absolute title plus the full schedule in the expanded row", async () => {
  const lastHit = new Date(Date.now() - 3600000).toISOString();
  const counted: PolicyRule = { ...rule, hitCounts: { hits: 7, firstHit: "2026-01-01T00:00:00Z", lastHit, source: "mds", collectedAt: "2026-10-05T00:00:00Z", firewalls: [] },
    schedules: [{ kind: "recurring", start: null, end: null, windows: [{ days: [1, 2, 3, 4, 5], monthDays: [], start: "08:00", end: "17:00" }], timezone: "UTC", timezoneKnown: true }] };
  mount(ruleTable({ sections: [{ ...section, rules: [counted] }] }));
  const row = screen.getByRole("row", { name: "Rule 1: OBJ-RULE-01" });
  expect(column(row, "lastHit")).toHaveTextContent("last 1 h ago");
  expect(usageBox(row)).toHaveAttribute("title", expect.stringContaining(lastHit));
  expect(usageBox(row)).toHaveAttribute("data-usage-state", "recent");
  expect(column(row, "hits")).toHaveTextContent("7 hits");
  const details = expand();
  expect(within(details).getByText("First hit").parentElement).toHaveTextContent("2026-01-01 03:00 GMT+3");
  const tile = within(details).getByText("Last hit").parentElement!;
  fireEvent.mouseOver(tile);
  expect(await screen.findByRole("tooltip")).toHaveTextContent(lastHit);
  const [schedule] = within(details).getAllByText("Weekdays 08:00–17:00 · UTC");
  expect(schedule).toBeVisible();
});

// Post-release review 2026-10-20 (N4, N5, N6, L16): room for object names, flags with a subject, a withheld comment as a token.
it("gives a lone object chip two lines with its full name on hover, and keeps two chips on one line each", () => {
  const long = new Map([["g1", { id: "g1", name: "GRP-DELTA-ENGINEERING-HOSTS", type: "group", status: "RESOLVED", members: ["a", "b"] }], ["h1", { id: "h1", name: "ADDR-PAPA-01", type: "host", status: "RESOLVED" }]]);
  const lone = { ...rule, id: "lone", number: 1, source: { refs: ["g1"], negated: false }, destination: { refs: ["g1", "h1"], negated: false } };
  mount(ruleTable({ sections: [{ ...section, rules: [lone] }], objects: long }));
  const row = screen.getByRole("row", { name: /^Rule 1:/ });
  const [source] = within(column(row, "source")).getAllByRole("button");
  expect(source).toHaveAttribute("title", expect.stringMatching(/^GRP-DELTA-ENGINEERING-HOSTS · Group · 2 members/));
  expect(getComputedStyle(source).whiteSpace).toBe("normal");
  const [first, second] = within(column(row, "destination")).getAllByRole("button");
  expect(getComputedStyle(first).whiteSpace).toBe("nowrap");
  expect(second).toHaveAttribute("title", expect.stringMatching(/^ADDR-PAPA-01 · Host/));
});
it("says in muted words what was not decided instead of a Hygiene UNKNOWN pill, and shows a withheld comment as a masked token", () => {
  const hygiene: PolicyHygieneAssessment = { shadowStatus: "UNKNOWN", shadowReason: "analysis budget", coveringRuleId: null, timeStatus: "always", expiring: false, counterWindow: "90 d", hitSource: "mds", hitsCollectedAt: null,
    permissiveness: { level: "Low", reasons: [] }, findings: [{ findingClass: "unknown", severity: "LOW", evidence: "Shadowing: analysis budget." }, { findingClass: "unknown", severity: "LOW", evidence: "Hit counts not collected." }] };
  mount(ruleTable({ sections: [{ ...section, rules: [{ ...rule, enabled: true, timeStatus: "always", hygiene }] }], openHygiene: vi.fn() }));
  const row = screen.getByRole("row", { name: /^Rule 1:/ });
  expect(within(column(row, "hygiene")).queryByRole("button")).toBeNull();
  const words = within(column(row, "hygiene")).getByText("No findings · shadowing not provable");
  expect(words).toHaveAttribute("title", "Shadowing not provable: analysis budget · Hit counts not collected.");
  expect(within(column(row, "name")).getByText("Comment withheld")).toHaveAttribute("title", expect.stringContaining("masked view"));
  expect(column(row, "name").textContent).not.toContain("Withheld in AIView");
});
it("folds the empty sections into one group row that opens to name them", () => {
  const sections = [section, { ...section, id: "s-empty-1", name: "OBJ-SECTION-02", total: 0, rules: [] }, { ...section, id: "s-empty-2", name: "OBJ-SECTION-03", total: 0, rules: [] }];
  const { container } = mount(ruleTable({ sections }));
  const group = screen.getByRole("button", { name: /2 empty sections/ });
  expect(group).toHaveAttribute("aria-expanded", "false");
  expect(container.querySelectorAll("[data-empty-section]")).toHaveLength(0);
  fireEvent.click(group);
  expect(group).toHaveAttribute("aria-expanded", "true");
  expect([...container.querySelectorAll("[data-empty-section]")].map(el => el.textContent)).toEqual(["OBJ-SECTION-02 · 0 rules", "OBJ-SECTION-03 · 0 rules"]);
});

it("judges snapshot freshness against the refresh schedule: fresh within a day, stale past it, old past a week, unknown without a time", () => {
  const now = new Date("2026-10-30T12:00:00Z");
  expect(snapshotFreshness("2026-10-30T06:00:00Z", now)).toMatchObject({ state: "fresh", label: "Collected 6 h ago (30 Oct 2026, 09:00 GMT+3)" });
  expect(snapshotFreshness("2026-10-29T11:59:00Z", now).state).toBe("stale");
  expect(snapshotFreshness("2026-10-29T12:30:00Z", now).state).toBe("fresh");
  const old = snapshotFreshness("2026-10-01T12:00:00Z", now);
  expect(old).toMatchObject({ state: "old", label: "Collected 29 d ago (1 Oct 2026, 15:00 GMT+3)" });
  expect(old.reason).toMatch(/^Snapshot is 29 days old: the scheduled refresh \(every 6 h at 00, 06, 12 and 18 GMT\+3; full collection daily at 00:00\) has not updated it\./);
  expect(snapshotFreshness("2026-10-25T12:00:00Z", now).state).toBe("stale");
  expect(snapshotFreshness("2026-10-23T11:00:00Z", now).state).toBe("old");
  expect(snapshotFreshness("", now)).toMatchObject({ state: "unknown", label: "Collection time unknown", ageHours: null });
  expect(snapshotFreshness(undefined, now).state).toBe("unknown");
});
it("groups the analysis' not-provable vocabulary into plain words", () => {
  const key = (reason: string | null) => unknownReasonGroup(reason).key;
  expect(key("user or category constraint")).toBe("identity");
  expect(key("access-role objects cannot be resolved")).toBe("identity");
  expect(key("zone or install-on coverage is unproven")).toBe("zone");
  expect(key("unresolved install-on selector")).toBe("install-on");
  expect(key("earlier sections not analysed")).toBe("layers");
  expect(key("analysis budget")).toBe("budget");
  expect(key("unresolved object")).toBe("objects");
  // The cascade names the earlier rule as its subject, never this rule's constraints.
  expect(key("earlier rule has unsupported constraints")).toBe("earlier");
  expect(key("Shadowing: earlier rule has unsupported constraints.")).toBe("earlier");
  expect(unknownReasonGroup("earlier rule has unsupported constraints").label).toBe("Earlier rule in this layer not provable");
  expect(unknownReasonGroup("earlier rule has unsupported constraints").explain).toContain("Not a problem with this rule itself");
  expect(key("unsupported rule constraint")).toBe("constraints");
  expect(key("Hit counts were not collected or are invalid.")).toBe("hits"); expect(key("Hit counts are missing for a target.")).toBe("hits");
  expect(key("something new")).toBe("other"); expect(key(null)).toBe("other");
  expect(unknownReasonGroup("analysis budget")).toMatchObject({ label: "Analysis limit reached" });
  expect(REASON_GROUPS.every(group => group.label && group.explain)).toBe(true);
  const assessment: PolicyHygieneAssessment = { shadowStatus: "UNKNOWN", shadowReason: "analysis budget", coveringRuleId: null, timeStatus: "always", expiring: false, counterWindow: "90 d", hitSource: "mds", hitsCollectedAt: null,
    permissiveness: { level: "Low", reasons: [] }, findings: [{ findingClass: "unknown", severity: "LOW", evidence: "Hit counts not collected." }, { findingClass: "broad", severity: "HIGH", evidence: "Any source." }] };
  expect(unknownReasons(assessment)).toEqual(["analysis budget", "Hit counts not collected."]);
  expect(unknownReasons({ ...assessment, shadowStatus: "NOT_SHADOWED", findings: [] })).toEqual([]);
  expect(unknownReasons({ ...assessment, shadowReason: null, findings: [] })).toEqual(["reason not recorded"]);
  expect(unknownReasons(undefined)).toEqual([]);
});
it("says in words why a rule's shadowing is not provable and never paints an undecided zero green", () => {
  const hygiene: PolicyHygieneAssessment = { shadowStatus: "UNKNOWN", shadowReason: "user or category constraint", coveringRuleId: null, timeStatus: "always", expiring: false,
    counterWindow: "Counter window unknown", hitSource: "mds", hitsCollectedAt: null, permissiveness: { level: "Low", reasons: [] }, findings: [] };
  mount(<RuleMetrics rule={{ ...rule, hygiene }} />);
  expect(screen.getByText("UNKNOWN because user or category constraint")).toBeInTheDocument();
  const group = screen.getByText(/^Not provable · Identity or dynamic objects:/);
  expect(group).toHaveAttribute("data-reason-group", "identity");
  expect(group).toHaveTextContent("An evidence limit, not a rule problem.");
  expect(screen.getByText("none proven; shadowing not analysed")).toBeInTheDocument();
  // The undecided zero takes the same neutral ink as the UNKNOWN shadow word beside it, not the green of a proven clean rule.
  const card = document.querySelector<HTMLElement>('[data-shadow-status="UNKNOWN"]')!;
  expect(getComputedStyle(within(card).getByText("0", { exact: true })).color).toBe(getComputedStyle(within(card).getByText("UNKNOWN", { exact: true })).color);
  mount(<RuleMetrics rule={{ ...rule, id: "clean", hygiene: { ...hygiene, shadowStatus: "NOT_SHADOWED", shadowReason: null } }} />);
  const clean = document.querySelector<HTMLElement>('[data-shadow-status="NOT_SHADOWED"]')!;
  expect(getComputedStyle(within(clean).getByText("0", { exact: true })).color).not.toBe(getComputedStyle(within(card).getByText("0", { exact: true })).color);
  expect(within(clean).queryByText(/none proven/)).toBeNull();
});
it("does not claim 'No findings' for a rule whose shadowing was not analysed", () => {
  const hygiene: PolicyHygieneAssessment = { shadowStatus: "UNKNOWN", shadowReason: "earlier sections not analysed", coveringRuleId: null, timeStatus: "always", expiring: false,
    counterWindow: "90 d", hitSource: "mds", hitsCollectedAt: null, permissiveness: { level: "Low", reasons: [] }, findings: [] };
  mount(ruleTable({ sections: [{ ...section, rules: [{ ...rule, enabled: true, timeStatus: "always", hygiene }] }] }));
  const flags = column(screen.getByRole("row", { name: /^Rule 1:/ }), "hygiene");
  expect(flags).toHaveTextContent("No findings · shadowing not provable");
  expect(within(flags).getByText(/No findings/)).toHaveAttribute("title", "Shadowing not provable: earlier sections not analysed");
});
it("draws no header for a section whose rules are on other pages", () => {
  const other: PolicySection = { ...section, id: "section-2", name: "OBJ-SECTION-02", rules: [], total: 100 };
  const { container } = mount(ruleTable({ sections: [section, other] }));
  expect(container.querySelectorAll("[data-policy-section]")).toHaveLength(1);
  expect(screen.queryByText(/OBJ-SECTION-02/)).toBeNull();
});
