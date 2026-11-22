import { render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getLifecycleCatalogSources, type LifecycleCatalogSources } from "../src/auth/adminApi";
import { EstateLifecycleSources } from "../src/estate/EstateLifecycleSources";

vi.mock("../src/auth/adminApi", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../src/auth/adminApi")>()),
  getLifecycleCatalogSources: vi.fn(),
}));

const mocked = vi.mocked(getLifecycleCatalogSources);

const SOURCES: LifecycleCatalogSources = {
  feeds: [
    {
      source: "cisco_eol",
      origin: "FEED",
      last_run_outcome: "OK",
      last_run_reason: null,
      last_run_at: "2026-11-20T06:00:00.000Z",
      last_good_at: "2026-11-20T06:00:00.000Z",
      age_days: 2,
      age_state: "GREEN",
      fresh_days: 30,
      entries: 41,
      entries_needing_review: 3,
    },
    {
      source: "checkpoint_lifecycle",
      origin: "FEED",
      last_run_outcome: "FAILED",
      last_run_reason: "SYNTHETIC_UNREACHABLE",
      last_run_at: "2026-11-21T06:00:00.000Z",
      last_good_at: "2026-11-01T06:00:00.000Z",
      age_days: null,
      age_state: "RED",
      fresh_days: 30,
      entries: 7,
      entries_needing_review: 0,
    },
  ],
  page_forms: [
    { page_form: "SYN_FORM_A", vendor: "SYNTHETIC", label: "Synthetic page A", page_url: "https://vendor.example.invalid/a", online: false },
    { page_form: "SYN_FORM_B", vendor: "SYNTHETIC", label: "Synthetic page B", page_url: "https://vendor.example.invalid/b", online: false },
  ],
  uploads: [],
  needs_review: [],
  vendor_entries: 48,
  hand_loaded_fresh_days: 90,
};

function restrictedError(): Error {
  // Mirrors the shape of the API client's restricted rejection (see isRestricted in shell/States).
  return Object.assign(new Error("forbidden"), { status: 403, body: { error: "RESTRICTED" } });
}

describe("EstateLifecycleSources", () => {
  beforeEach(() => {
    mocked.mockReset();
  });

  it("renders each feed with its name, last good load and entry count", async () => {
    mocked.mockResolvedValue(SOURCES);
    render(<EstateLifecycleSources />);

    const rows = await screen.findAllByTestId("estate-lifecycle-source-row");
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText(/Cisco end-of-life bulletins/)).toBeTruthy();
    expect(within(rows[0]).getByText(/Last good load: 2026-11-20T06:00:00.000Z/)).toBeTruthy();
    expect(within(rows[0]).getByText(/41 entries · 3 need review/)).toBeTruthy();
    expect(within(rows[1]).getByText(/Check Point life cycle page/)).toBeTruthy();
    expect(within(rows[1]).getByText(/7 entries · 0 need review/)).toBeTruthy();
  });

  it("shows UNKNOWN for a null age and never a number or OK", async () => {
    mocked.mockResolvedValue(SOURCES);
    render(<EstateLifecycleSources />);

    const rows = await screen.findAllByTestId("estate-lifecycle-source-row");
    const stale = rows[1];
    expect(within(stale).getByText("UNKNOWN")).toBeTruthy();
    expect(within(stale).queryByText(/days/)).toBeNull();
    expect(within(stale).queryByText("OK")).toBeNull();
    expect(stale.getAttribute("data-state")).toBe("unk");
  });

  it("shows the error text and no feed rows when the fetch fails", async () => {
    mocked.mockRejectedValue(new Error("SYNTHETIC_NETWORK_DOWN"));
    render(<EstateLifecycleSources />);

    expect(await screen.findByText("Sources could not be loaded: SYNTHETIC_NETWORK_DOWN")).toBeTruthy();
    expect(screen.queryAllByTestId("estate-lifecycle-source-row")).toHaveLength(0);
    expect(document.querySelector("[data-evidence-stamp]")).toBeNull();
  });

  it("shows the restricted sentence for a restricted role", async () => {
    mocked.mockRejectedValue(restrictedError());
    render(<EstateLifecycleSources />);

    expect(await screen.findByText("Your role cannot read lifecycle sources.")).toBeTruthy();
    expect(screen.queryAllByTestId("estate-lifecycle-source-row")).toHaveLength(0);
  });

  it("renders the evidence stamp when feeds are shown", async () => {
    mocked.mockResolvedValue(SOURCES);
    const { container } = render(<EstateLifecycleSources />);

    await screen.findAllByTestId("estate-lifecycle-source-row");
    expect(container.querySelector("[data-evidence-stamp]")).not.toBeNull();
  });

  it("renders no upload or file input", async () => {
    mocked.mockResolvedValue(SOURCES);
    const { container } = render(<EstateLifecycleSources />);

    await screen.findAllByTestId("estate-lifecycle-source-row");
    expect(container.querySelector("input[type='file']")).toBeNull();
    expect(screen.queryByRole("button", { name: /upload/i })).toBeNull();
    expect(screen.getByText("2 vendor pages saved for upload (not shown here)")).toBeTruthy();
  });
});
