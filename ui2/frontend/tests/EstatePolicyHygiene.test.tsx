import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type {
  PolicyHygieneAssessment,
  PolicyHygienePage,
  PolicyHygieneSummary,
  PolicyMetadata,
  PolicyPage,
} from "../src/auth/adminApi";
import { EstatePolicyBrowser } from "../src/estate/EstatePolicyBrowser";
import { EstatePolicyHygiene } from "../src/estate/EstatePolicyHygiene";
import {
  POLICY_HYGIENE_REASON_CAP,
  POLICY_HYGIENE_ROW_CAP,
  POLICY_HYGIENE_STANDARD_HREF,
  buildPolicyHygieneView,
} from "../src/estate/estatePolicyHygieneModel";

const listPolicies = vi.fn();
const getPolicy = vi.fn();
const getPolicyHygieneSummary = vi.fn();
const getPolicyHygiene = vi.fn();

vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return {
    ...actual,
    listPolicies: (...args: unknown[]) => listPolicies(...args),
    getPolicy: (...args: unknown[]) => getPolicy(...args),
    getPolicyHygieneSummary: (...args: unknown[]) => getPolicyHygieneSummary(...args),
    getPolicyHygiene: (...args: unknown[]) => getPolicyHygiene(...args),
  };
});

// Synthetic values only (FW-TANGO-04 names, POL-ALPHA-01 containers).
const NOW = new Date("2026-11-22T12:00:00Z");
const COLLECTED_AT = "2026-11-22T10:00:00Z";

const META: PolicyMetadata = {
  id: "pol-alpha-01",
  sourceId: "src-tango-04",
  sourceName: "MGR-TANGO-04",
  vendor: "check_point",
  containerId: "ctr-alpha-01",
  containerName: "POL-ALPHA-01",
  name: "FW-TANGO-04",
  collectedAt: COLLECTED_AT,
  artefactRef: "art-alpha-01",
  targets: [{ deviceId: "dev-tango-04", name: "FW-TANGO-04", context: "", syncStatus: "OK" }],
  ruleCount: 120,
};

const POLICY_PAGE: PolicyPage = {
  metadata: META,
  sections: [],
  objects: [],
  page: 0,
  pageSize: 50,
  total: 0,
};

const FLEET_SUMMARY: PolicyHygieneSummary = {
  containers: [
    {
      sourceId: "src-tango-04",
      sourceName: "MGR-TANGO-04",
      vendor: "check_point",
      containerId: "ctr-alpha-01",
      containerName: "POL-ALPHA-01",
      snapshotAt: COLLECTED_AT,
      state: "READY",
      rules: 120,
      counts: { shadowed: 4 },
    },
  ],
  totals: { shadowed: 4 },
  rules: 120,
  pending: 0,
  days: 90,
};

const BASE_ASSESSMENT: PolicyHygieneAssessment = {
  shadowStatus: "CONFLICT",
  shadowReason: null,
  coveringRuleId: "r-1",
  findings: [
    {
      findingClass: "conflict",
      severity: "HIGH",
      evidence: "Earlier enabled rule covers this match with a different action.",
    },
  ],
  permissiveness: { level: "Medium", score: 2, reasons: ["Any destination"] },
  timeStatus: "always",
  expiring: false,
  counterWindow: "90 days",
  hitSource: "mds",
  hitsCollectedAt: COLLECTED_AT,
};

function makeHygienePage(overrides: Partial<PolicyHygienePage> = {}): PolicyHygienePage {
  return {
    rows: [
      {
        ruleId: "r-10",
        number: 10,
        name: "ALLOW-TANGO-WEB",
        sectionId: "sec-1",
        findings: BASE_ASSESSMENT.findings,
        hygiene: BASE_ASSESSMENT,
      },
      {
        ruleId: "r-11",
        number: 11,
        name: "",
        sectionId: "sec-1",
        findings: [{ findingClass: "unknown", severity: "LOW", evidence: "Hit counts were not collected." }],
        hygiene: {
          ...BASE_ASSESSMENT,
          coveringRuleId: null,
          shadowStatus: "UNKNOWN",
          shadowReason: "user or category constraint",
        },
      },
    ],
    counts: {
      conflict: 1,
      shadowed: 4,
      broad: 2,
      unused: 7,
      expired: 0,
      disabled: 3,
      unknown: 15,
    },
    total: 2,
    page: 0,
    pageSize: 200,
    budgetReached: false,
    days: 90,
    coverage: {
      rules: 120,
      shadowProven: 105,
      shadowUnknown: 15,
      hitsCollected: 100,
      hitsMissing: 20,
    },
    unknownReasons: [
      { evidence: "Shadowing: user or category constraint.", rules: 10 },
      { evidence: "Hit counts were not collected.", rules: 5 },
    ],
    ...overrides,
  };
}

beforeEach(() => {
  listPolicies.mockReset();
  getPolicy.mockReset();
  getPolicyHygieneSummary.mockReset();
  getPolicyHygiene.mockReset();
  listPolicies.mockResolvedValue({ policies: [META], devices: [] });
  getPolicy.mockResolvedValue(POLICY_PAGE);
  getPolicyHygieneSummary.mockResolvedValue(FLEET_SUMMARY);
});

describe("EstatePolicyHygiene inside EstatePolicyBrowser", () => {
  it("1. switching to the Hygiene tab calls getPolicyHygiene for the selected policy and renders shadow coverage, hit-counter coverage, per-class counts in 'N rules', and the policy EvidenceStamp", async () => {
    getPolicyHygiene.mockResolvedValue(makeHygienePage({ budgetReached: true }));
    render(<EstatePolicyBrowser now={NOW} />);

    await waitFor(() => expect(getPolicy).toHaveBeenCalledWith("pol-alpha-01", 0, ""));
    expect(getPolicyHygiene).not.toHaveBeenCalled();

    const hygieneTab = screen.getByRole("tab", { name: "Hygiene" });
    fireEvent.click(hygieneTab);

    await waitFor(() => expect(getPolicyHygiene).toHaveBeenCalledWith("pol-alpha-01", 0, "", "", 90));

    const panel = await screen.findByRole("tabpanel", { name: "Hygiene" });
    expect(
      within(panel).getByText("Shadow analysis: 105 proven · 15 not provable (of 120 rules)"),
    ).toBeInTheDocument();
    expect(within(panel).getByText("Hit counters: 100 stored · 20 not collected")).toBeInTheDocument();
    expect(within(panel).getByText("Analysis limit reached")).toBeInTheDocument();

    // All 7 per-class counts in "N rules"
    const countsList = within(panel).getByRole("list", { name: "Per-class rule counts" });
    expect(countsList).toHaveTextContent("conflict:1 rules");
    expect(countsList).toHaveTextContent("shadowed:4 rules");
    expect(countsList).toHaveTextContent("broad:2 rules");
    expect(countsList).toHaveTextContent("unused:7 rules");
    expect(countsList).toHaveTextContent("expired:0 rules");
    expect(countsList).toHaveTextContent("disabled:3 rules");
    expect(countsList).toHaveTextContent("not provable (UNKNOWN):15 rules");

    // Policy snapshot EvidenceStamp
    const stamp = panel.querySelector("[data-evidence-stamp]");
    expect(stamp).not.toBeNull();
    expect(stamp?.textContent).toContain("policy snapshot");

    // Finding rows including unnamed fallback and explicit UNKNOWN shadow status
    expect(within(panel).getByText("10 · ALLOW-TANGO-WEB")).toBeInTheDocument();
    expect(within(panel).getByText("11 · Unnamed rule")).toBeInTheDocument();
    expect(within(panel).getByText("UNKNOWN because user or category constraint")).toBeInTheDocument();
  });

  it("2. missing coverage or a missing class count renders UNKNOWN (data-state-badge='unk') and never OK or 0 rules", async () => {
    const partialCounts = {
      conflict: 2,
      shadowed: 1,
      broad: 3,
      // unused is missing
      expired: 1,
      disabled: 4,
      unknown: 5,
    } as unknown as Record<string, number>;
    getPolicyHygiene.mockResolvedValue(
      makeHygienePage({
        coverage: undefined,
        counts: partialCounts,
        rows: [],
        total: 0,
        unknownReasons: [],
      }),
    );

    const { container } = render(<EstatePolicyHygiene policy="pol-alpha-01" collectedAt={COLLECTED_AT} now={NOW} />);
    await waitFor(() =>
      expect(
        screen.getByText("Shadow analysis: coverage UNKNOWN (not returned by the service)"),
      ).toBeInTheDocument(),
    );
    expect(screen.getByText("Hit counters: coverage UNKNOWN")).toBeInTheDocument();

    const coverageRegion = screen.getByRole("region", { name: "Analysis coverage" });
    expect(coverageRegion.querySelector('[data-state-badge="unk"]')).not.toBeNull();

    const unusedItem = container.querySelector('[data-hygiene-class="unused"]');
    expect(unusedItem).not.toBeNull();
    expect(unusedItem?.querySelector('[data-state-badge="unk"]')).not.toBeNull();
    expect(unusedItem?.textContent).toContain("UNKNOWN");
    expect(unusedItem?.textContent).not.toContain("0 rules");

    expect(container.querySelector('[data-state-badge="ok"]')).toBeNull();
    expect(screen.queryByText(/^(OK|Fine)$/)).toBeNull();
    expect(container.textContent).not.toContain("0 rules");
  });

  it("3. reason breakdown (> 6 groups) and finding rows (total > 10) cap their lists and print '+N more reason groups' and '+N more matching rules' via MoreFooter", async () => {
    const unknownReasons = [
      { evidence: "Shadowing: earlier rule has unsupported constraints.", rules: 30 },
      { evidence: "Hit counts were not collected.", rules: 25 },
      { evidence: "Shadowing: user or category constraint.", rules: 20 },
      { evidence: "Shadowing: zone coverage is unproven.", rules: 15 },
      { evidence: "Shadowing: unresolved install-on selector.", rules: 12 },
      { evidence: "Shadowing: section or layer boundary.", rules: 9 },
      { evidence: "Shadowing: analysis budget reached.", rules: 6 },
      { evidence: "Shadowing: unresolved object in source.", rules: 4 },
    ];
    const rows = Array.from({ length: 14 }, (_, idx) => ({
      ruleId: `r-${idx + 1}`,
      number: idx + 1,
      name: `RULE-TANGO-${String(idx + 1).padStart(2, "0")}`,
      sectionId: "sec-1",
      findings: BASE_ASSESSMENT.findings,
      hygiene: BASE_ASSESSMENT,
    }));

    getPolicyHygiene.mockResolvedValue(
      makeHygienePage({
        unknownReasons,
        rows,
        total: 14,
      }),
    );

    render(<EstatePolicyHygiene policy="pol-alpha-01" collectedAt={COLLECTED_AT} now={NOW} />);

    const reasonsMore = await screen.findByTestId("policy-hygiene-reasons-more");
    expect(reasonsMore).toHaveTextContent("+2 more reason groups");
    const reasonList = screen.getByRole("list", { name: "Not provable reasons" });
    expect(reasonList.querySelectorAll("[data-reason-group]")).toHaveLength(POLICY_HYGIENE_REASON_CAP);

    const rowsMore = screen.getByTestId("policy-hygiene-rows-more");
    expect(rowsMore).toHaveTextContent("+4 more matching rules");
    expect(screen.getByText("1 · RULE-TANGO-01")).toBeInTheDocument();
    expect(screen.getByText("10 · RULE-TANGO-10")).toBeInTheDocument();
    expect(screen.queryByText("11 · RULE-TANGO-11")).toBeNull();
    expect(POLICY_HYGIENE_ROW_CAP).toBe(10);
  });

  it("4. a rejected or malformed getPolicyHygiene response renders UNKNOWN and never OK", async () => {
    getPolicyHygiene.mockRejectedValueOnce(new Error("downstream unavailable"));
    const { container, unmount } = render(
      <EstatePolicyHygiene policy="pol-alpha-01" collectedAt={COLLECTED_AT} now={NOW} />,
    );

    expect(
      await screen.findByText("Hygiene UNKNOWN: this policy's hygiene analysis could not be read."),
    ).toBeInTheDocument();
    expect(container.querySelector('[data-state-badge="unk"]')).not.toBeNull();
    expect(container.querySelector('[data-state-badge="ok"]')).toBeNull();
    expect(screen.queryByText(/^(OK|Fine)$/)).toBeNull();
    expect(screen.queryByText(/No hygiene findings/)).toBeNull();
    unmount();

    // Malformed response (missing counts or non-array rows)
    getPolicyHygiene.mockResolvedValueOnce({ rows: null, counts: null });
    const { container: malformedContainer } = render(
      <EstatePolicyHygiene policy="pol-alpha-01" collectedAt={COLLECTED_AT} now={NOW} />,
    );
    expect(
      await screen.findByText("Hygiene UNKNOWN: this policy's hygiene analysis could not be read."),
    ).toBeInTheDocument();
    expect(malformedContainer.querySelector('[data-state-badge="unk"]')).not.toBeNull();
    expect(malformedContainer.querySelector('[data-state-badge="ok"]')).toBeNull();
    expect(buildPolicyHygieneView({ rows: [] } as unknown as PolicyHygienePage).state).toBe("unknown");
  });

  it("5. standard-view link points to ?screen=policy&tab=hygiene&standard=1 and carries (standard view) with no export or comparison buttons", async () => {
    getPolicyHygiene.mockResolvedValue(makeHygienePage());
    render(<EstatePolicyHygiene policy="pol-alpha-01" collectedAt={COLLECTED_AT} now={NOW} />);

    await screen.findByText("10 · ALLOW-TANGO-WEB");
    const link = screen.getByRole("link", { name: /\(standard view\)/i });
    expect(link.getAttribute("href")).toBe(POLICY_HYGIENE_STANDARD_HREF);
    expect(link.getAttribute("href")).toBe("?screen=policy&tab=hygiene&standard=1");
    expect(screen.queryByRole("button", { name: /export|csv|compare/i })).toBeNull();
  });
});
