import { render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { getLifecycleAdvisories, getLifecycleCatalog, type LifecycleCatalogEntry } from "../src/auth/adminApi";
import { EstateLifecycleScreen } from "../src/estate/EstateLifecycleScreen";
import { LIFECYCLE_CATALOG_CAP, catalogMilestone, catalogRowAgeDays, lifecycleCatalogRow } from "../src/estate/estateLifecycleCatalogModel";

vi.mock("../src/auth/adminApi", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../src/auth/adminApi")>()),
  getLifecycleAdvisories: vi.fn(),
  getLifecycleCatalog: vi.fn(),
}));

// The device vulnerability card reads the lifecycle fleet the Estate provider already loads; no provider here.
vi.mock("../src/estate/useEstateData", () => ({
  useEstateData: () => ({ lifecycle: { state: "failed" } }),
}));

const NOW = new Date("2026-11-22T08:00:00.000Z");
const DAY = 24 * 60 * 60 * 1000;
const daysAgo = (days: number) => new Date(NOW.getTime() - days * DAY).toISOString();

function entry(overrides: Partial<LifecycleCatalogEntry> & { catalog_id: string; product: string }): LifecycleCatalogEntry {
  return {
    vendor: "CHECKPOINT",
    kind: "HARDWARE",
    end_of_sale: null,
    end_of_support: null,
    end_of_engineering: null,
    source: "MANUAL",
    note: null,
    imported_by: "operator-one",
    imported_at: daysAgo(10),
    ...overrides,
  } as LifecycleCatalogEntry;
}

const FIXTURE: LifecycleCatalogEntry[] = [
  entry({ catalog_id: "cat-1", product: "FW-TANGO-04 model", end_of_support: "2027-06-30", note: "synthetic contract note" }),
  entry({ catalog_id: "cat-2", product: "R81.20", vendor: "CHECKPOINT", kind: "SOFTWARE", end_of_sale: "2026-01-15", end_of_support: "" }),
  entry({ catalog_id: "cat-3", product: "PA-SYN-3220", vendor: "PALOALTO", imported_at: daysAgo(200), end_of_support: "2028-01-01" }),
];

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(NOW);
  vi.mocked(getLifecycleAdvisories).mockReset();
  vi.mocked(getLifecycleCatalog).mockReset();
  vi.mocked(getLifecycleAdvisories).mockResolvedValue({ advisories: [], as_of: NOW.toISOString() } as never);
});

afterEach(() => {
  vi.useRealTimers();
});

async function renderCatalog(entries: LifecycleCatalogEntry[]) {
  vi.mocked(getLifecycleCatalog).mockResolvedValue({ entries, can_manage: false } as never);
  render(<EstateLifecycleScreen />);
  const section = await screen.findByTestId("estate-catalog-section");
  await waitFor(() => expect(within(section).queryByText(/Loading operator catalog/)).toBeNull());
  return section;
}

describe("Estate operator catalog section", () => {
  it("renders the catalog rows with vendor, kind, product, milestones, source and import actor", async () => {
    const section = await renderCatalog(FIXTURE);
    expect(within(section).getByText("Operator catalog")).toBeTruthy();
    expect(within(section).getByText(/CHECKPOINT · FW-TANGO-04 model/)).toBeTruthy();
    expect(within(section).getByText(/End of support: 2027-06-30/)).toBeTruthy();
    expect(within(section).getByText(/Source: MANUAL · synthetic contract note/)).toBeTruthy();
    expect(within(section).getAllByText(/imported by operator-one/)).toHaveLength(3);
  });

  it("shows UNKNOWN for a blank milestone date, never a date and never OK", async () => {
    const section = await renderCatalog([FIXTURE[1]]);
    const row = within(section).getByTestId("estate-catalog-row");
    expect(row.textContent).toContain("End of sale: 2026-01-15");
    expect(row.textContent).toContain("End of support: UNKNOWN");
    expect(row.textContent).toContain("End of engineering: UNKNOWN");
    expect(row.textContent).not.toMatch(/\bOK\b/);
  });

  it("shows an import older than 180 days as NOT_EVALUATED and counts its age from the import stamp", async () => {
    const section = await renderCatalog(FIXTURE);
    const rows = within(section).getAllByTestId("estate-catalog-row");
    const stale = rows.find((r) => r.textContent?.includes("PA-SYN-3220"));
    expect(stale?.getAttribute("data-status")).toBe("NOT_EVALUATED");
    expect(stale?.textContent).toContain("NOT_EVALUATED");
    expect(stale?.textContent).toContain("imported 200 days ago");
    const fresh = rows.find((r) => r.textContent?.includes("FW-TANGO-04 model"));
    expect(fresh?.getAttribute("data-status")).toBe("CURRENT");
    expect(fresh?.textContent).not.toContain("NOT_EVALUATED");
  });

  it("has no button, input or select control and points changes to the standard view", async () => {
    const section = await renderCatalog(FIXTURE);
    expect(section.querySelectorAll("button, input, select, textarea, [role='button'], [role='textbox']").length).toBe(0);
    expect(within(section).queryByRole("button")).toBeNull();
    const link = within(section).getByText("Catalog is changed on the standard view").closest("a");
    expect(link?.getAttribute("href")).toContain("standard=1");
  });

  it("shows 'No lifecycle catalog data' for an empty catalog", async () => {
    const section = await renderCatalog([]);
    expect(within(section).getByText("No lifecycle catalog data")).toBeTruthy();
    expect(within(section).queryAllByTestId("estate-catalog-row")).toHaveLength(0);
  });

  it("caps the rows and prints the +N more footer with the true total", async () => {
    const many = Array.from({ length: LIFECYCLE_CATALOG_CAP + 7 }, (_, i) =>
      entry({ catalog_id: `cap-${i}`, product: `SYN-${String(i).padStart(3, "0")}` }),
    );
    const section = await renderCatalog(many);
    expect(within(section).getAllByTestId("estate-catalog-row")).toHaveLength(LIFECYCLE_CATALOG_CAP);
    expect(within(section).getByTestId("estate-catalog-more").textContent).toBe("+7 more catalog rows");
    expect(within(section).getByTestId("estate-catalog-count").textContent).toContain(`${LIFECYCLE_CATALOG_CAP + 7}`);
  });
});

describe("estateLifecycleCatalogModel", () => {
  it("treats blank or whitespace milestones as UNKNOWN and an unreadable import stamp as NOT_EVALUATED", () => {
    expect(catalogMilestone(null)).toBe("UNKNOWN");
    expect(catalogMilestone("   ")).toBe("UNKNOWN");
    expect(catalogMilestone("2027-01-01")).toBe("2027-01-01");
    const row = lifecycleCatalogRow(entry({ catalog_id: "x", product: "SYN", imported_at: "not-a-date" }), NOW);
    expect(row.status).toBe("NOT_EVALUATED");
    expect(row.reason).toBe("import age UNKNOWN");
  });

  it("keeps the 180-day boundary: exactly 180 days is still CURRENT, 181 is NOT_EVALUATED", () => {
    expect(catalogRowAgeDays(daysAgo(180), NOW)).toBe(180);
    expect(lifecycleCatalogRow(entry({ catalog_id: "a", product: "SYN", imported_at: daysAgo(180) }), NOW).status).toBe("CURRENT");
    expect(lifecycleCatalogRow(entry({ catalog_id: "b", product: "SYN", imported_at: daysAgo(181) }), NOW).status).toBe("NOT_EVALUATED");
  });
});
