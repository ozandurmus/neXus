import { render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getLifecycleAdvisories, type LifecycleAdvisory } from "../src/auth/adminApi";
import { EstateLifecycleScreen, lifecycleAdvisoryRow } from "../src/estate/EstateLifecycleScreen";
import { resolveEstateRoute, standardLifecycleHref } from "../src/estate/estateRouting";

vi.mock("../src/auth/adminApi", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../src/auth/adminApi")>()),
  getLifecycleAdvisories: vi.fn(),
}));

// The device vulnerability card reads the lifecycle fleet the Estate provider already loads; no provider here.
vi.mock("../src/estate/useEstateData", () => ({
  useEstateData: () => ({ lifecycle: { state: "failed" } }),
}));

const AS_OF = "2026-11-21T08:00:00.000Z";

function advisory(overrides: Partial<LifecycleAdvisory> & { title: string; advisory_id: string }): LifecycleAdvisory {
  return {
    source: "paloalto",
    severity: "HIGH",
    cvss_score: 7.5,
    cve_ids: [],
    exploited: false,
    exploited_since: null,
    published_on: "2026-11-01",
    updated_on: "2026-11-10",
    source_url: null,
    parse_state: "PARSED",
    parse_note: null,
    devices: { affected: 0, fixed: 0, not_affected: 0, not_evaluable: 0, pending: 0 },
    ...overrides,
  };
}

const SYNTHETIC: LifecycleAdvisory[] = [
  advisory({
    advisory_id: "paloalto:SYN-0001",
    title: "Synthetic gateway advisory one (FW-TANGO-04)",
    devices: { affected: 3, fixed: 1, not_affected: 0, not_evaluable: 0, pending: 0 },
  }),
  advisory({
    advisory_id: "cisco:SYN-0002",
    source: "cisco",
    title: "Synthetic switch advisory (FW-JULIET-06)",
    severity: "UNKNOWN",
    parse_state: "NEEDS_REVIEW",
    parse_note: "synthetic review note",
    devices: { affected: 0, fixed: 0, not_affected: 0, not_evaluable: 2, pending: 0 },
  }),
  advisory({
    advisory_id: "paloalto:SYN-0003",
    title: "Synthetic clear advisory (FW-BRAVO-02)",
    devices: { affected: 0, fixed: 0, not_affected: 4, not_evaluable: 0, pending: 0 },
  }),
];

beforeEach(() => {
  vi.mocked(getLifecycleAdvisories).mockReset();
});

describe("Estate lifecycle route", () => {
  it("resolves inventory?view=lifecycle to the Estate lifecycle route and leaves other inventory views alone", () => {
    expect(resolveEstateRoute("inventory", "/", "?screen=inventory&view=lifecycle")).toEqual({ kind: "lifecycle" });
    // Other inventory views never take the lifecycle route (they keep their existing resolution).
    expect(resolveEstateRoute("inventory", "/", "?screen=inventory&view=other")).not.toEqual({ kind: "lifecycle" });
    expect(resolveEstateRoute("inventory", "/", "?screen=inventory&view=lifecycle&standard=1")).toBeNull();
  });
});

describe("EstateLifecycleScreen", () => {
  it("lists synthetic advisories with counts labelled as devices affected and shows the as_of stamp", async () => {
    vi.mocked(getLifecycleAdvisories).mockResolvedValue({ as_of: AS_OF, advisories: SYNTHETIC });
    render(<EstateLifecycleScreen />);

    const rows = await screen.findAllByTestId("estate-lifecycle-row");
    expect(rows).toHaveLength(3);
    // Sorted by affected count, highest first.
    expect(within(rows[0]).getByText("Synthetic gateway advisory one (FW-TANGO-04)")).toBeInTheDocument();
    expect(within(rows[0]).getByTestId("estate-lifecycle-count")).toHaveTextContent("3 devices affected");
    expect(within(rows[2]).getByTestId("estate-lifecycle-count")).toHaveTextContent("0 devices affected");
    expect(screen.getAllByText(/End of support: UNKNOWN/)).toHaveLength(3);
    expect(lifecycleAdvisoryRow(SYNTHETIC[0]).word).toBe("Affected");
    expect(screen.getAllByTestId("estate-lifecycle-count")).toHaveLength(3);
  });

  it("renders an UNKNOWN status as UNKNOWN, never as OK", async () => {
    vi.mocked(getLifecycleAdvisories).mockResolvedValue({ as_of: AS_OF, advisories: SYNTHETIC });
    render(<EstateLifecycleScreen />);

    const unknownRow = (await screen.findByText("Synthetic switch advisory (FW-JULIET-06)")).closest(
      "[data-testid='estate-lifecycle-row']",
    ) as HTMLElement;
    expect(unknownRow).toHaveAttribute("data-state", "unk");
    expect(within(unknownRow).getByText("UNKNOWN")).toBeInTheDocument();
    expect(within(unknownRow).queryByText("OK")).toBeNull();

    // A missing device tally fails closed too.
    expect(lifecycleAdvisoryRow(advisory({ advisory_id: "x:1", title: "t", devices: undefined as never })).state).toBe("unk");
    // A clean tally with nothing pending is the only OK.
    expect(lifecycleAdvisoryRow(SYNTHETIC[2]).state).toBe("ok");
  });

  it("links each row to the standard view with standard=1", async () => {
    vi.mocked(getLifecycleAdvisories).mockResolvedValue({ as_of: AS_OF, advisories: SYNTHETIC.slice(0, 1) });
    render(<EstateLifecycleScreen />);

    const link = await screen.findByRole("link", { name: "Open in standard view" });
    expect(link).toHaveAttribute("href", standardLifecycleHref());
    expect(link.getAttribute("href")).toContain("standard=1");
    expect(link.getAttribute("href")).toContain("view=lifecycle");
  });

  it("shows UNKNOWN, not an empty list, when the advisory read fails", async () => {
    vi.mocked(getLifecycleAdvisories).mockRejectedValue(new Error("synthetic read failure"));
    render(<EstateLifecycleScreen />);

    await waitFor(() => expect(screen.getByText(/synthetic read failure/)).toBeInTheDocument());
    expect(screen.queryByTestId("estate-lifecycle-row")).toBeNull();
  });
});
