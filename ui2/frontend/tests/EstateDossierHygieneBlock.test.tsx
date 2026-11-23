import { render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  getPolicyHygieneSummary,
  listPolicies,
  type PolicyHygieneSummary,
  type PolicyHygieneSummaryRow,
  type PolicyMetadata,
} from "../src/auth/adminApi";
import { EstateDossierHygieneBlock } from "../src/estate/EstateDossierHygieneBlock";
import { DOSSIER_HYGIENE_ROW_CAP } from "../src/estate/estateDossierHygieneModel";

vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return {
    ...actual,
    listPolicies: vi.fn(),
    getPolicyHygieneSummary: vi.fn(),
  };
});

const mockedListPolicies = vi.mocked(listPolicies);
const mockedGetSummary = vi.mocked(getPolicyHygieneSummary);

// Synthetic identifiers only (FW-TANGO-04 style names, 192.0.2.x addresses).
const DEVICE_ID = "dev-tango-04";
const STAMP = new Date(Date.now() - 2 * 3_600_000).toISOString();

function policy(containerId: string, targets: string[] = [DEVICE_ID], sourceId = "src-bravo"): PolicyMetadata {
  return {
    id: `${sourceId}:${containerId}`,
    sourceId,
    sourceName: "Panorama BRAVO",
    vendor: "PAN",
    containerId,
    containerName: `Container ${containerId}`,
    name: `Package ${containerId}`,
    collectedAt: STAMP,
    artefactRef: "",
    targets: targets.map((deviceId) => ({ deviceId, name: "FW-TANGO-04", context: "", syncStatus: "IN_SYNC" })),
    ruleCount: 10,
  };
}

function readyRow(
  containerId: string,
  rules: number,
  counts: Record<string, number>,
  sourceId = "src-bravo",
): PolicyHygieneSummaryRow {
  return {
    sourceId,
    sourceName: "Panorama BRAVO",
    vendor: "PAN",
    containerId,
    containerName: `Container ${containerId}`,
    snapshotAt: STAMP,
    state: "READY",
    rules,
    counts,
  };
}

function summary(containers: PolicyHygieneSummaryRow[]): PolicyHygieneSummary {
  return { containers, totals: {}, rules: 0, pending: 0, days: 90 };
}

beforeEach(() => {
  mockedListPolicies.mockReset();
  mockedGetSummary.mockReset();
});

describe("EstateDossierHygieneBlock", () => {
  it("renders EvidenceStamp at block and row level; failed read renders unk badge and a0 stamp", async () => {
    mockedListPolicies.mockResolvedValue({ policies: [policy("c-1")], devices: [] });
    mockedGetSummary.mockResolvedValue(summary([readyRow("c-1", 4, { "shadowed-rule": 2 })]));

    const { unmount } = render(<EstateDossierHygieneBlock deviceId={DEVICE_ID} />);
    const block = await waitFor(() => screen.getByTestId("dossier-policy-hygiene-block"));

    const stamps = block.querySelectorAll("[data-evidence-stamp]");
    expect(stamps).toHaveLength(2);
    expect(block.textContent).toContain("policy hygiene");
    const row = within(block).getByTestId("dossier-hygiene-row");
    expect(row.querySelector("[data-evidence-stamp]")).not.toBeNull();
    expect(row.textContent).toContain("policy snapshot");
    unmount();

    mockedListPolicies.mockResolvedValue({ policies: [policy("c-1")], devices: [] });
    mockedGetSummary.mockRejectedValue(new Error("synthetic hygiene read failure"));

    render(<EstateDossierHygieneBlock deviceId={DEVICE_ID} />);
    const failedBlock = await waitFor(() => screen.getByTestId("dossier-policy-hygiene-block"));
    expect(failedBlock.querySelector('[data-state-badge="unk"]')).not.toBeNull();
    expect(failedBlock.querySelector('[data-evidence-stamp="a0"]')).not.toBeNull();
    expect(failedBlock.textContent).not.toContain("No findings");
  });

  it("renders UNKNOWN and never No findings when counts are missing or NaN on a READY row", async () => {
    const missingCounts = { ...readyRow("c-1", 5, {}), counts: undefined } as unknown as PolicyHygieneSummaryRow;
    mockedListPolicies.mockResolvedValue({ policies: [policy("c-1")], devices: [] });
    mockedGetSummary.mockResolvedValue(summary([missingCounts]));

    const { unmount } = render(<EstateDossierHygieneBlock deviceId={DEVICE_ID} />);
    const block1 = await waitFor(() => screen.getByTestId("dossier-policy-hygiene-block"));
    expect(block1.querySelector('[data-state-badge="unk"]')).not.toBeNull();
    expect(block1.textContent).toContain("UNKNOWN");
    expect(block1.textContent).not.toContain("No findings");
    unmount();

    const nanCounts = readyRow("c-1", 5, { "shadowed-rule": Number.NaN });
    mockedListPolicies.mockResolvedValue({ policies: [policy("c-1")], devices: [] });
    mockedGetSummary.mockResolvedValue(summary([nanCounts]));

    render(<EstateDossierHygieneBlock deviceId={DEVICE_ID} />);
    const block2 = await waitFor(() => screen.getByTestId("dossier-policy-hygiene-block"));
    expect(block2.querySelector('[data-state-badge="unk"]')).not.toBeNull();
    expect(block2.textContent).toContain("UNKNOWN");
    expect(block2.textContent).not.toContain("No findings");
  });

  it("renders +3 more containers via dossier-hygiene-more MoreFooter when containers exceed cap", async () => {
    const total = DOSSIER_HYGIENE_ROW_CAP + 3;
    const ids = Array.from({ length: total }, (_, i) => `c-${i + 1}`);
    mockedListPolicies.mockResolvedValue({ policies: ids.map((id) => policy(id)), devices: [] });
    mockedGetSummary.mockResolvedValue(summary(ids.map((id) => readyRow(id, 2, {}))));

    render(<EstateDossierHygieneBlock deviceId={DEVICE_ID} />);
    const block = await waitFor(() => screen.getByTestId("dossier-policy-hygiene-block"));
    expect(within(block).getAllByTestId("dossier-hygiene-row")).toHaveLength(DOSSIER_HYGIENE_ROW_CAP);
    const more = within(block).getByTestId("dossier-hygiene-more");
    expect(more.textContent).toBe("+3 more containers");
  });

  it("formats singular rule counts as '1 rule' rather than '1 rules', has zero action buttons, and links to standard=1", async () => {
    mockedListPolicies.mockResolvedValue({ policies: [policy("c-1")], devices: [] });
    mockedGetSummary.mockResolvedValue(summary([readyRow("c-1", 1, { "shadowed-rule": 1 })]));

    render(<EstateDossierHygieneBlock deviceId={DEVICE_ID} />);
    const block = await waitFor(() => screen.getByTestId("dossier-policy-hygiene-block"));

    expect(block.textContent).toContain("1 rule in this container");
    expect(block.textContent).toContain("shadowed-rule: 1 rule");
    expect(block.textContent).not.toContain("1 rules");
    expect(within(block).queryAllByRole("button")).toHaveLength(0);

    const link = within(block).getByRole("link", { name: /Open policy hygiene \(standard view\)/ });
    const href = link.getAttribute("href") ?? "";
    expect(href).toContain("standard=1");
    expect(href).toContain(`device_id=${DEVICE_ID}`);
  });
});
