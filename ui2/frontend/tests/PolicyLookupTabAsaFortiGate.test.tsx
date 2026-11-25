import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PolicyLookupTab } from "../src/screens/PolicyLookupTab";
import type { PolicyLookupResult, PolicyMetadata } from "../src/auth/adminApi";

const asaPolicy: PolicyMetadata = {
  id: "asa-1",
  sourceId: "dev-asa-1",
  sourceName: "FW-TANGO-04",
  vendor: "ASA",
  containerId: "c-asa-1",
  containerName: "single",
  name: "ASA access rules",
  collectedAt: "2026-11-24T12:00:00Z",
  artefactRef: "art-asa-1",
  targets: [],
};

const asaPeerPolicy: PolicyMetadata = {
  ...asaPolicy,
  id: "asa-2",
  sourceId: "dev-asa-2",
  sourceName: "FW-JULIET-06",
  containerId: "c-asa-2",
};

const fgtRootPolicy: PolicyMetadata = {
  id: "p-fgt-1",
  sourceId: "dev-fgt-1",
  sourceName: "FW-BRAVO-02",
  vendor: "FGT",
  containerId: "c-vdom-1",
  containerName: "VDOM-ROOT",
  name: "FGT firewall policy",
  collectedAt: "2026-11-24T12:00:00Z",
  artefactRef: "art-fgt-1",
  targets: [],
};

const fgtDmzPolicy: PolicyMetadata = {
  ...fgtRootPolicy,
  id: "p-fgt-2",
  containerId: "c-vdom-2",
  containerName: "VDOM-DMZ",
};

const panPolicy: PolicyMetadata = {
  id: "pan-1",
  sourceId: "mgr-pan-1",
  sourceName: "MGR-ALPHA-01",
  vendor: "PAN",
  containerId: "dg-alpha",
  containerName: "DG-ALPHA",
  name: "POL-PAN-ALPHA",
  collectedAt: "2026-11-24T12:00:00Z",
  artefactRef: "art-pan-1",
  targets: [],
};

const cpPolicy: PolicyMetadata = {
  id: "cp-1",
  sourceId: "mgr-cp-1",
  sourceName: "MGR-CHARLIE-01",
  vendor: "CP",
  containerId: "dom-charlie",
  containerName: "DOM-CHARLIE",
  name: "POL-CP-CHARLIE",
  collectedAt: "2026-11-24T12:00:00Z",
  artefactRef: "art-cp-1",
  targets: [],
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function assertAllLabelsShort(expectedLabels: readonly string[]) {
  for (const label of expectedLabels) {
    const field = screen.getAllByText(label).find((node) => node.tagName === "LABEL");
    expect(field, label).toBeDefined();
    expect(label.length).toBeLessThanOrEqual(18);
  }
  const allLabels = Array.from(document.querySelectorAll("label"));
  for (const node of allLabels) {
    const raw = (node.childNodes[0]?.textContent ?? "").trim();
    expect(raw.length, `label "${raw}" exceeds 18 chars`).toBeLessThanOrEqual(18);
  }
}

describe("PolicyLookupTab Cisco ASA and FortiGate vendor-aware lookup", () => {
  it("requires Cisco ASA ingress and egress nameif interfaces, trims/sends them, and renders ASA context and verdicts", async () => {
    const responses: PolicyLookupResult[] = [
      {
        policyId: "asa-1",
        collectedAt: asaPolicy.collectedAt,
        verdict: "ALLOWED",
        lookupReason: "first matching rule",
        decidingRule: {
          ruleId: "asa-r1",
          number: 1,
          name: "INSIDE-IN:1",
          sectionId: "sec-in",
          sectionName: "inside in",
          action: "permit",
        },
        undecidableRules: [],
        query: { querySource: "192.0.2.10", queryDestination: "198.51.100.20", queryService: "tcp/443" },
        parts: [],
        limitations: "Policy intent only. NAT, routing and anti-spoofing are not evaluated; IPv6 is unsupported.",
      },
      {
        policyId: "asa-1",
        collectedAt: asaPolicy.collectedAt,
        verdict: "DENIED",
        lookupReason: "no rule matched; implicit deny of the access list",
        decidingRule: null,
        undecidableRules: [],
        query: { querySource: "192.0.2.10", queryDestination: "198.51.100.20", queryService: "tcp/443" },
        parts: [],
        limitations: "Policy intent only. NAT, routing and anti-spoofing are not evaluated; IPv6 is unsupported.",
      },
      {
        policyId: "asa-1",
        collectedAt: asaPolicy.collectedAt,
        verdict: "UNKNOWN",
        lookupReason: "no access list applies; security-level default unproven",
        decidingRule: null,
        undecidableRules: [],
        query: { querySource: "192.0.2.10", queryDestination: "198.51.100.20", queryService: "tcp/443" },
        parts: [],
        limitations: "Policy intent only. NAT, routing and anti-spoofing are not evaluated; IPv6 is unsupported.",
      },
    ];
    let callIndex = 0;
    const fetcher = vi.fn(async (input: RequestInfo | URL) =>
      String(input) === "/session/status"
        ? new Response("{}", { status: 200 })
        : new Response(JSON.stringify(responses[callIndex++] ?? responses[0]), { status: 200 }),
    );
    vi.stubGlobal("fetch", fetcher);
    const openRule = vi.fn();

    render(<PolicyLookupTab policies={[asaPolicy, asaPeerPolicy]} policy="asa-1" selectPolicy={vi.fn()} openRule={openRule} />);

    assertAllLabelsShort([
      "Lookup policy",
      "Source",
      "Destination",
      "Service",
      "Ingress interface",
      "Egress interface",
      "Install-on target",
      "Device context",
    ]);

    expect(screen.getByText(/NAT, routing and anti-spoofing are not evaluated; IPv6 is unsupported\. This is not observed traffic\./)).toHaveTextContent(/nameif/);
    expect(screen.getAllByText("Required · Cisco ASA nameif")).toHaveLength(2);

    fireEvent.mouseDown(screen.getByLabelText(/Lookup policy/));
    const asaOption = await screen.findByRole("option", { name: "ASA access rules · FW-TANGO-04" });
    expect(asaOption).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "ASA access rules · FW-JULIET-06" })).toBeInTheDocument();
    fireEvent.click(asaOption);

    const ingress = screen.getByLabelText(/^Ingress interface\s*\*?$/);
    const egress = screen.getByLabelText(/^Egress interface\s*\*?$/);
    expect(ingress).toBeRequired();
    expect(egress).toBeRequired();
    expect(ingress.closest("details")).toBeNull();
    expect(egress.closest("details")).toBeNull();

    fireEvent.change(screen.getByLabelText(/^Source\s*\*?$/), { target: { value: "192.0.2.10" } });
    fireEvent.change(screen.getByLabelText(/^Destination\s*\*?$/), { target: { value: "198.51.100.20" } });

    expect(
      screen.getByRole("button", { name: "Look up policy intent · Needs an ingress interface, an egress interface" }),
    ).toBeDisabled();

    fireEvent.change(ingress, { target: { value: "   " } });
    expect(
      screen.getByRole("button", { name: "Look up policy intent · Needs an ingress interface, an egress interface" }),
    ).toBeDisabled();

    fireEvent.change(ingress, { target: { value: " inside " } });
    expect(screen.getByRole("button", { name: "Look up policy intent · Needs an egress interface" })).toBeDisabled();

    fireEvent.change(egress, { target: { value: " outside " } });
    const submitButton = screen.getByRole("button", { name: "Look up policy intent" });
    expect(submitButton).toBeEnabled();

    fireEvent.click(submitButton);
    const permitCard = await screen.findByLabelText("Lookup result 1");
    expect(fetcher).toHaveBeenCalledWith(
      "/api/v2/policy/policies/asa-1/lookup",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({
          source: "192.0.2.10",
          destination: "198.51.100.20",
          service: "tcp/443",
          sourceZone: "inside",
          destinationZone: "outside",
        }),
      }),
    );
    expect(within(permitCard).getByText("Allowed")).toBeInTheDocument();
    expect(within(permitCard).getByText("Snapshot: ASA access rules")).toBeInTheDocument();
    expect(within(permitCard).getByText("Cisco ASA")).toBeInTheDocument();
    expect(within(permitCard).getByText("inside in · permit")).toBeInTheDocument();
    fireEvent.click(within(permitCard).getByRole("button", { name: "1 · INSIDE-IN:1" }));
    expect(openRule).toHaveBeenCalledWith("asa-1", "asa-r1");

    fireEvent.click(screen.getByRole("button", { name: "Look up policy intent" }));
    await screen.findByLabelText("Lookup result 2");
    const denyCard = screen.getByLabelText("Lookup result 1");
    expect(within(denyCard).getByText("Denied")).toBeInTheDocument();
    expect(within(denyCard).getByText("no rule matched; implicit deny of the access list")).toBeInTheDocument();
    expect(within(denyCard).getByText("Cisco ASA")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Look up policy intent" }));
    await screen.findByLabelText("Lookup result 3");
    const levelCard = screen.getByLabelText("Lookup result 1");
    expect(within(levelCard).getByText("UNKNOWN")).toBeInTheDocument();
    expect(within(levelCard).getByText("no access list applies; security-level default unproven")).toBeInTheDocument();
    expect(within(levelCard).getByText("Snapshot: ASA access rules")).toBeInTheDocument();
    expect(within(levelCard).getByText("Cisco ASA")).toBeInTheDocument();
  });

  it("disambiguates FortiGate multi-VDOM policies, keeps srcintf/dstintf visible and optional, and formats VIP uncertainty reasons", async () => {
    const vipPriorityResult: PolicyLookupResult = {
      policyId: "p-fgt-1",
      collectedAt: fgtRootPolicy.collectedAt,
      verdict: "UNKNOWN",
      lookupReason: "FGT_VIP_PRIORITY_UNPROVEN",
      decidingRule: {
        ruleId: "fgt-r10",
        number: 1,
        name: "ALLOW-WEB-ORDINARY",
        sectionId: "sec-vdom-1",
        sectionName: "VDOM-ROOT policy",
        action: "accept",
      },
      undecidableRules: [
        {
          ruleId: "fgt-r25",
          number: 4,
          name: "VIP-PUBLISH-HTTPS",
          sectionId: "sec-vdom-1",
          sectionName: "VDOM-ROOT policy",
          action: "accept",
          lookupReason: "FGT_VIP_PRIORITY_UNPROVEN",
        },
      ],
      query: { querySource: "192.0.2.30", queryDestination: "198.51.100.40", queryService: "tcp/443" },
      parts: [],
      limitations: "Policy intent only. NAT, routing and anti-spoofing are not evaluated; IPv6 is unsupported.",
    };

    const matchVipResult: PolicyLookupResult = {
      policyId: "p-fgt-2",
      collectedAt: fgtDmzPolicy.collectedAt,
      verdict: "UNKNOWN",
      lookupReason: "earlier rule could match",
      decidingRule: {
        ruleId: "fgt-r40",
        number: 3,
        name: "ALLOW-DMZ-APP",
        sectionId: "sec-vdom-2",
        sectionName: "VDOM-DMZ policy",
        action: "accept",
      },
      undecidableRules: [
        {
          ruleId: "fgt-r31",
          number: 1,
          name: "DENY-MAPPED-INTERNAL",
          sectionId: "sec-vdom-2",
          sectionName: "VDOM-DMZ policy",
          action: "deny",
          lookupReason: "FGT_MATCH_VIP_UNPROVEN",
        },
      ],
      query: { querySource: "192.0.2.30", queryDestination: "198.51.100.40", queryService: "tcp/443" },
      parts: [],
      limitations: "Policy intent only. NAT, routing and anti-spoofing are not evaluated; IPv6 is unsupported.",
    };

    let callIndex = 0;
    const fetcher = vi.fn(async (input: RequestInfo | URL) =>
      String(input) === "/session/status"
        ? new Response("{}", { status: 200 })
        : new Response(JSON.stringify(callIndex++ === 0 ? vipPriorityResult : matchVipResult), { status: 200 }),
    );
    vi.stubGlobal("fetch", fetcher);
    const selectPolicy = vi.fn();

    const { rerender } = render(
      <PolicyLookupTab policies={[fgtRootPolicy, fgtDmzPolicy]} policy="p-fgt-1" selectPolicy={selectPolicy} openRule={vi.fn()} />,
    );

    assertAllLabelsShort([
      "Lookup policy",
      "Source",
      "Destination",
      "Service",
      "Ingress interface",
      "Egress interface",
      "Install-on target",
      "Device context",
    ]);

    const advanced = screen.getByTestId("lookup-advanced");
    expect(advanced).toHaveAttribute("open");

    const ingress = screen.getByLabelText("Ingress interface");
    const egress = screen.getByLabelText("Egress interface");
    expect(ingress).not.toBeRequired();
    expect(egress).not.toBeRequired();
    expect(screen.getByText("Optional · FortiGate srcintf (unless rules use any)")).toBeInTheDocument();
    expect(screen.getByText("Optional · FortiGate dstintf (unless rules use any)")).toBeInTheDocument();

    fireEvent.mouseDown(screen.getByLabelText(/Lookup policy/));
    const rootOption = await screen.findByRole("option", { name: "FGT firewall policy · VDOM-ROOT" });
    expect(rootOption).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "FGT firewall policy · VDOM-DMZ" })).toBeInTheDocument();
    fireEvent.click(rootOption);

    fireEvent.change(screen.getByLabelText(/^Source\s*\*?$/), { target: { value: "192.0.2.30" } });
    fireEvent.change(screen.getByLabelText(/^Destination\s*\*?$/), { target: { value: "198.51.100.40" } });

    const submitButton = screen.getByRole("button", { name: "Look up policy intent" });
    expect(submitButton).toBeEnabled();
    fireEvent.click(submitButton);

    const rootCard = await screen.findByLabelText("Lookup result 1");
    expect(fetcher).toHaveBeenCalledWith(
      "/api/v2/policy/policies/p-fgt-1/lookup",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ source: "192.0.2.30", destination: "198.51.100.40", service: "tcp/443" }),
      }),
    );
    expect(within(rootCard).getByText("UNKNOWN")).toBeInTheDocument();
    expect(within(rootCard).getByText("Snapshot: FGT firewall policy")).toBeInTheDocument();
    expect(within(rootCard).getByText("FortiGate · VDOM-ROOT")).toBeInTheDocument();
    expect(
      within(rootCard).getByText(
        "VIP priority unproven (FGT_VIP_PRIORITY_UNPROVEN): a later VIP policy may take precedence over an earlier ordinary rule",
      ),
    ).toBeInTheDocument();
    expect(within(rootCard).getByText(/UNKNOWN: rule 4 could match/)).toHaveTextContent(
      "VIP priority unproven (FGT_VIP_PRIORITY_UNPROVEN): a later VIP policy may take precedence over an earlier ordinary rule",
    );
    expect(rootCard.textContent).not.toMatch(/\b(safe|pass|green)\b/i);

    rerender(
      <PolicyLookupTab policies={[fgtRootPolicy, fgtDmzPolicy]} policy="p-fgt-2" selectPolicy={selectPolicy} openRule={vi.fn()} />,
    );
    fireEvent.change(screen.getByLabelText("Ingress interface"), { target: { value: " port1 " } });
    fireEvent.change(screen.getByLabelText("Egress interface"), { target: { value: " port2 " } });
    fireEvent.click(screen.getByRole("button", { name: "Look up policy intent" }));

    await screen.findByLabelText("Lookup result 2");
    const dmzCard = screen.getByLabelText("Lookup result 1");
    expect(fetcher).toHaveBeenCalledWith(
      "/api/v2/policy/policies/p-fgt-2/lookup",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({
          source: "192.0.2.30",
          destination: "198.51.100.40",
          service: "tcp/443",
          sourceZone: "port1",
          destinationZone: "port2",
        }),
      }),
    );
    expect(within(dmzCard).getByText("UNKNOWN")).toBeInTheDocument();
    expect(within(dmzCard).getByText("Snapshot: FGT firewall policy")).toBeInTheDocument();
    expect(within(dmzCard).getByText("FortiGate · VDOM-DMZ")).toBeInTheDocument();
    expect(within(dmzCard).getByText(/UNKNOWN: rule 1 could match/)).toHaveTextContent(
      "VIP destination match unproven (FGT_MATCH_VIP_UNPROVEN): a deny rule with match-vip could match the mapped destination",
    );
    expect(dmzCard.textContent).not.toMatch(/\b(safe|pass|green)\b/i);
  });

  it("restores Source zone and Destination zone labels and optional behavior when switching back to PAN or CP", () => {
    const catalog = [asaPolicy, fgtRootPolicy, panPolicy, cpPolicy];
    const { rerender } = render(
      <PolicyLookupTab policies={catalog} policy="asa-1" selectPolicy={vi.fn()} openRule={vi.fn()} />,
    );

    fireEvent.change(screen.getByLabelText(/^Source\s*\*?$/), { target: { value: "192.0.2.50" } });
    fireEvent.change(screen.getByLabelText(/^Destination\s*\*?$/), { target: { value: "198.51.100.60" } });
    expect(
      screen.getByRole("button", { name: "Look up policy intent · Needs an ingress interface, an egress interface" }),
    ).toBeDisabled();

    rerender(<PolicyLookupTab policies={catalog} policy="pan-1" selectPolicy={vi.fn()} openRule={vi.fn()} />);
    assertAllLabelsShort([
      "Lookup policy",
      "Source",
      "Destination",
      "Service",
      "Source zone",
      "Destination zone",
      "Install-on target",
      "Device context",
    ]);
    expect(screen.queryByText("Ingress interface")).toBeNull();
    expect(screen.queryByText("Egress interface")).toBeNull();
    expect(screen.getByTestId("lookup-advanced")).not.toHaveAttribute("open");
    expect(screen.getByRole("button", { name: "Look up policy intent" })).toBeEnabled();

    rerender(<PolicyLookupTab policies={catalog} policy="cp-1" selectPolicy={vi.fn()} openRule={vi.fn()} />);
    assertAllLabelsShort([
      "Lookup policy",
      "Source",
      "Destination",
      "Service",
      "Source zone",
      "Destination zone",
      "Install-on target",
      "Device context",
    ]);
    expect(screen.getByTestId("lookup-advanced")).not.toHaveAttribute("open");
    expect(screen.getByRole("button", { name: "Look up policy intent" })).toBeEnabled();
  });
});
