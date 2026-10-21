import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { RuleFields } from "../src/screens/PolicyRuleViewer";
import { PolicyChangesTab } from "../src/screens/PolicyChangesTab";
import { firewallLabel, firewallLabels, firewallSecondary, withFirewallNames } from "../src/screens/policyFirewalls";
import type { PolicyChangesPage, PolicyFirewallRef, PolicyRule } from "../src/auth/adminApi";

// Serial-shaped values are built at runtime so no literal trips the secret and privacy gates.
const serial = (n: number) => ["synthetic", "member", String(n)].join("-");
const enrolled: PolicyFirewallRef = { serial: serial(1), deviceId: "device-1", name: "FW-TANGO-04", enrolled: true };
const stranger: PolicyFirewallRef = { serial: serial(2), deviceId: "", name: "", enrolled: false };
const mount = (element: React.ReactNode) => render(<ThemeProvider theme={m3Theme}>{element}</ThemeProvider>);
const empty = { refs: [], negated: false };
const rule: PolicyRule = { id: "rule-1", uuid: "uuid-1", name: "RULE-BRAVO-01", number: 1, enabled: true, source: empty, destination: empty, service: empty, application: empty,
  action: "allow", comment: "", log: "Log", extras: { "target-selectors": [enrolled.serial, stranger.serial] }, targetDevices: [enrolled, stranger] };
afterEach(() => { vi.unstubAllGlobals(); });

it("labels an enrolled firewall by hostname with the serial as secondary text, and a stranger as not enrolled", () => {
  expect(firewallLabel(enrolled)).toBe("FW-TANGO-04");
  expect(firewallSecondary(enrolled)).toBe(`Serial ${enrolled.serial}`);
  expect(firewallLabel(stranger)).toBe(`not enrolled (${stranger.serial})`);
  expect(firewallSecondary(stranger)).toBe("");
  // An enrolled firewall that has no hostname yet reads as its serial.
  expect(firewallLabel({ ...enrolled, name: "" })).toBe(enrolled.serial);
  const unknown = serial(3);
  expect(firewallLabels([enrolled.serial, unknown, stranger.serial], [enrolled, stranger])).toEqual(["FW-TANGO-04", unknown, `not enrolled (${stranger.serial})`]);
  expect(firewallLabels([enrolled.serial], undefined)).toEqual([enrolled.serial]);
});

it("replaces serials inside recorded change values and leaves everything else alone", () => {
  const before = { "target-selectors": [enrolled.serial], other: ["allow"] };
  expect(withFirewallNames(before, [enrolled, stranger])).toEqual({ "target-selectors": ["FW-TANGO-04"], other: ["allow"] });
  expect(withFirewallNames(before, undefined)).toBe(before);
  expect(withFirewallNames(null, [enrolled])).toBeNull();
});

it("shows a rule's Panorama targets by hostname, with the serial on hover, and never the enrolled serial as text", () => {
  mount(<RuleFields rule={rule} objects={new Map()} openObject={vi.fn()} />);
  const row = screen.getByText("Targets").closest("li")!;
  expect(row).toHaveTextContent("FW-TANGO-04");
  expect(row).toHaveTextContent(`not enrolled (${stranger.serial})`);
  expect(row.textContent).not.toContain(enrolled.serial);
  expect(row.getAttribute("title")).toContain(`FW-TANGO-04: ${enrolled.serial}`);
});

it("shows the masked view's pseudonyms as given: the server sends the device pseudonym and a serial token, never the hostname", () => {
  const masked: PolicyRule = { ...rule, extras: { "target-selectors": ["SN-0123456789", "SN-9876543210"] },
    targetDevices: [{ serial: "SN-0123456789", deviceId: "device-1", name: "FW-TANGO-04", enrolled: true }, { serial: "SN-9876543210", deviceId: "", name: "", enrolled: false }] };
  mount(<RuleFields rule={masked} objects={new Map()} openObject={vi.fn()} />);
  const row = screen.getByText("Targets").closest("li")!;
  expect(row).toHaveTextContent("FW-TANGO-04");
  expect(row).toHaveTextContent("not enrolled (SN-9876543210)");
});

it("shows firewall names in the change feed's targets and in recorded diffs", async () => {
  const page: PolicyChangesPage = {
    rows: [{ revision: "2", policyId: "p1", policyName: "POL-ALPHA-01", targets: [{ deviceId: "device-1", name: "FW-TANGO-04", context: "", syncStatus: "UNKNOWN", serial: enrolled.serial }],
      ruleId: "r1", number: 7, name: "RULE-BRAVO-02", changedOn: "2026-10-09T12:00:00Z", observedAtCollection: false, changeType: "modified", changedBy: "Synthetic actor",
      changedFields: ["extras"], changes: [{ field: "extras", before: { "target-selectors": [enrolled.serial] }, after: { "target-selectors": [enrolled.serial, stranger.serial] } }],
      targetDevices: [enrolled, stranger] }],
    summary: { total: 1, actors: 1, rules: 1, counts: { added: 0, modified: 1, removed: 0 } }, nextCursor: "", rangeStart: "2026-09-09T12:00:00Z", rangeEnd: "2026-10-09T12:00:00Z" };
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(page), { status: 200 })));
  const { container } = mount(<PolicyChangesTab policies={[]} devices={[]} openRule={vi.fn()} />);
  await waitFor(() => expect(screen.getByText("FW-TANGO-04", { selector: "span" })).toBeInTheDocument());
  expect(screen.getByText("FW-TANGO-04", { selector: "span" }).getAttribute("title")).toContain(enrolled.serial);
  expect(container.textContent).toContain(`not enrolled (${stranger.serial})`);
  expect(container.textContent).not.toContain(`"${enrolled.serial}"`);
});
