import { render, screen, within } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PolicyRuleTable, RuleDetailPanel, RuleFields, actionTone, actionWord, containerLabel, policyVendorKind, unknownReasonGroup } from "../src/screens/PolicyRuleViewer";
import type { PolicyMetadata, PolicyRule, PolicySection } from "../src/auth/adminApi";

const asaMeta: PolicyMetadata = { id: "policy-asa", name: "FW-TANGO-04", sourceId: "source-1", sourceName: "FW-TANGO-04", vendor: "ASA", containerId: "c-1", containerName: "FW-TANGO-04", collectedAt: "2026-11-25T00:00:00Z", artefactRef: "artifact-1", targets: [] };
const fgtMeta: PolicyMetadata = { ...asaMeta, id: "policy-fgt", vendor: "FGT", containerName: "VDOM-ROOT-01" };
const objects = new Map<string, never>();
const empty = { refs: [], negated: false };
const baseRule: PolicyRule = { id: "rule-1", uuid: "uuid-1", name: "ACL-OUTSIDE-IN line 1", number: 1, enabled: true, source: empty, destination: empty, service: empty, application: empty, action: "permit", comment: "", log: "", extras: { "asa-acl": ["ACL-OUTSIDE-IN"] } };
const makeSection = (name: string, source: string, rules: PolicyRule[]): PolicySection => ({ id: `section-${source}`, name, source, parentRuleId: null, rules, total: rules.length });
function mount(element: React.ReactNode) { return render(<ThemeProvider theme={m3Theme}>{element}</ThemeProvider>); }
const table = (metadata: PolicyMetadata, sections: PolicySection[]) => <PolicyRuleTable sections={sections} collapsed={new Set()} scrollTop={0} toggle={vi.fn()} objects={objects} metadata={metadata}
  openObject={vi.fn()} selected={new Set()} onSelect={vi.fn()} />;

it("renders an ASA permit as allow with a check, and UNKNOWN or a FortiGate non-accept as other with a question mark", () => {
  expect(actionTone("permit")).toBe("allow"); expect(actionWord("permit")).toBe("Permit");
  for (const action of ["UNKNOWN", "", "ipsec"]) expect(actionTone(action)).toBe("other");
  const unknown: PolicyRule = { ...baseRule, id: "rule-2", number: 2, action: "UNKNOWN", extras: { "fgt-action": ["ipsec"] } };
  mount(table(asaMeta, [makeSection("IF-OUTSIDE-01 in", "ASA access-group", [baseRule, unknown])]));
  const rows = screen.getAllByRole("row").filter(row => /^Rule \d+:/.test(row.getAttribute("aria-label") ?? ""));
  const action = (row: HTMLElement) => row.querySelector<HTMLElement>('[data-column="action"], [data-field="action"]')!;
  expect(action(rows[0])).toHaveTextContent("✓Permit"); expect(action(rows[0]).firstElementChild).toHaveAttribute("data-action-tone", "allow");
  expect(action(rows[1])).toHaveTextContent("?UNKNOWN"); expect(action(rows[1]).firstElementChild).toHaveAttribute("data-action-tone", "other");
  expect(action(rows[1])).not.toHaveTextContent(/OK/);
});

it("renders the detail panel action tone and the vendor badge for ASA, FortiGate and an unknown vendor", () => {
  expect(["PAN", "CP", "ASA", "FGT", "XYZ"].map(policyVendorKind)).toEqual(["palo_alto", "check_point", "cisco_asa", "fortinet", "XYZ"]);
  const cases: [PolicyMetadata, PolicyRule, string, string, string][] = [
    [asaMeta, baseRule, "Cisco ASA", "ASA", "✓Permit"],
    [fgtMeta, { ...baseRule, action: "UNKNOWN", extras: { "fgt-action": ["ipsec"] } }, "Fortinet", "FTNT", "?UNKNOWN"],
    [{ ...asaMeta, vendor: "ZZZ" }, baseRule, "Vendor UNKNOWN", "?", "✓Permit"],
  ];
  for (const [metadata, rule, label, monogram, action] of cases) {
    const { container, unmount } = mount(<RuleDetailPanel rule={rule} section={makeSection("global", metadata.vendor === "FGT" ? "FGT firewall policy" : "ASA access-group", [rule])} metadata={metadata} objects={objects} openObject={vi.fn()} />);
    const badge = screen.getAllByLabelText(label)[0];
    expect(badge).toHaveTextContent(monogram);
    const tone = container.querySelector<HTMLElement>("[data-action-tone]")!;
    expect(tone).toHaveTextContent(action);
    expect(tone).toHaveAttribute("data-action-tone", action.startsWith("✓") ? "allow" : "other");
    unmount();
  }
});

it("labels ASA and FortiGate containers with their binding or VDOM, not the bare source", () => {
  const asa = (name: string, source: string, extras: Record<string, string[]> = {}) => containerLabel(asaMeta, makeSection(name, source, []), { ...baseRule, extras });
  expect(asa("IF-OUTSIDE-01 in", "ASA access-group", { "asa-acl": ["ACL-OUTSIDE-IN"] })).toBe("Access-group: ACL-OUTSIDE-IN (IF-OUTSIDE-01 in)");
  expect(asa("IF-OUTSIDE-01 in", "ASA access-group")).toBe("Access-group: IF-OUTSIDE-01 in");
  expect(asa("global", "ASA access-group", { "asa-acl": ["ACL-OUTSIDE-IN"] })).toBe("Access-group: ACL-OUTSIDE-IN (global)");
  expect(asa("ASA NAT section 1", "ASA NAT")).toBe("ASA NAT section 1");
  expect(asa("ASA security-level default", "ASA implicit")).toBe("ASA security-level default");
  expect(asa("ASA not evaluated", "ASA implicit")).toBe("ASA not evaluated");
  expect(asa("", "ASA implicit")).toBe("ASA implicit");
  const fgt = (name: string, source: string, meta: PolicyMetadata = fgtMeta) => containerLabel(meta, makeSection(name, source, []), baseRule);
  expect(fgt("VDOM-ROOT-01", "FGT firewall policy")).toBe("VDOM: VDOM-ROOT-01");
  expect(fgt("VDOM-ROOT-02", "FGT firewall policy", { ...fgtMeta, containerName: "" })).toBe("VDOM: VDOM-ROOT-02");
  expect(fgt("FGT implicit deny", "FGT implicit")).toBe("FGT implicit deny");
  expect(fgt("FGT VIP", "FGT VIP")).toBe("FGT VIP");
  expect(fgt("FGT not evaluated", "FGT not evaluated")).toBe("FGT not evaluated");
  expect(fgt("", "FGT implicit")).toBe("FGT implicit");
});

it("shows readable property labels for ASA and FortiGate extras, and keeps unknown ACL use distinct from none", () => {
  const unparsed = "access-list ACL-OUTSIDE-IN extended permit object-group-sgt";
  const extras: Record<string, string[]> = { "asa-acl": ["ACL-OUTSIDE-IN"], "asa-direction": ["in"], "asa-acl-use": ["unknown", unparsed], "security-group": ["SGT-ALPHA-01"], "asa-unparsed": [unparsed],
    "fgt-vip": ["VIP-ALPHA-01"], "fgt-match-vip": ["enable"], "fgt-missing-default": ["match-vip"], "fgt-not-evaluated-reason": ["ZONE_UNPROVEN"], "fgt-unparsed": ["set utm-status"] };
  const { unmount } = mount(<RuleFields rule={{ ...baseRule, extras }} objects={objects} openObject={vi.fn()} />);
  const properties = screen.getByText("Properties").parentElement!;
  for (const label of ["ACL", "Direction", "ACL use", "Security group (SGT)", "Unparsed CLI feature", "Destination VIP", "Match VIP", "Unproven CLI default", "Not evaluated reason"]) expect(within(properties).getAllByText(label).length).toBeGreaterThan(0);
  for (const raw of ["asa-acl-use", "fgt-vip", "fgt-match-vip", "fgt-missing-default", "fgt-not-evaluated-reason", "security-group"]) expect(within(properties).queryByText(raw)).toBeNull();
  const use = within(properties).getByText("ACL use").parentElement!;
  expect(use).toHaveTextContent("unknown"); expect(use).not.toHaveTextContent(/unused|none|OK/i);
  unmount();
  mount(<RuleFields rule={{ ...baseRule, extras: { "asa-acl-use": ["none"] } }} objects={objects} openObject={vi.fn()} />);
  expect(screen.getByText("ACL use").parentElement).toHaveTextContent("none");
  expect(screen.getByText("ACL use").parentElement).not.toHaveTextContent(/unknown/i);
});

it("groups ASA and FortiGate mapper gaps under unsupported rule constraints, leaving earlier precedence untouched", () => {
  for (const reason of ["FGT_DEFAULTS_UNPROVEN: the policy cites a default the parser could not prove", "The rule has a missing-default value", "ASA parser does not read this ACE feature", "ASA_UNPARSED line retained", "ACL_NOT_BOUND", "Policy not evaluated"]) {
    expect(unknownReasonGroup(reason)).toMatchObject({ key: "constraints", label: "Unsupported rule constraints" });
  }
  expect(unknownReasonGroup("Hit counts are missing")).toMatchObject({ key: "hits" });
  expect(unknownReasonGroup("An earlier rule is not provable")).toMatchObject({ key: "earlier" });
  expect(unknownReasonGroup("something else entirely")).toMatchObject({ key: "other" });
});
