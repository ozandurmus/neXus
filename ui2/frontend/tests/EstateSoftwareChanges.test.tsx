import { render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getSoftwareChanges, type SoftwareChange } from "../src/auth/adminApi";
import { EstateSoftwareChanges } from "../src/estate/EstateSoftwareChanges";

vi.mock("../src/auth/adminApi", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../src/auth/adminApi")>()),
  getSoftwareChanges: vi.fn(),
}));

const NOW = new Date("2026-11-22T12:00:00Z");
const UNITS = new Map([
  ["dev-tango-04", { name: "FW-TANGO-04" }],
  ["dev-bravo-02", { name: "FW-BRAVO-02" }],
]);

function change(deviceId: string, daysAgo: number): SoftwareChange {
  const at = new Date(NOW.getTime() - daysAgo * 86_400_000).toISOString();
  return {
    device_id: deviceId, vendor: "check_point", software_version: "R81.20", hotfix_level: null,
    first_seen_at: at, last_seen_at: at, previous_software_version: "R81.10", previous_hotfix_level: null,
    window_start: null, window_end: at, source: "inventory", hostname: "FW-ZULU-99",
    from_version: "R81.10", from_hotfix_level: null, to_version: "R81.20", to_hotfix_level: null,
  };
}

function renderSection() {
  return render(<EstateSoftwareChanges now={NOW} unitsById={UNITS} />);
}

beforeEach(() => {
  vi.mocked(getSoftwareChanges).mockReset();
});

describe("EstateSoftwareChanges", () => {
  it("shows the loading state while the read is pending", () => {
    vi.mocked(getSoftwareChanges).mockReturnValue(new Promise(() => {}));
    renderSection();
    expect(screen.getByTestId("software-changes-headline")).toHaveTextContent("Reading software changes for the last 90 days");
    expect(getSoftwareChanges).toHaveBeenCalledWith(90);
  });

  it("shows a failed read as an error, never as no changes", async () => {
    vi.mocked(getSoftwareChanges).mockRejectedValue(new Error("DOWNSTREAM_ERROR"));
    renderSection();
    await waitFor(() =>
      expect(screen.getByTestId("software-changes-headline")).toHaveTextContent("could not be read"),
    );
    expect(screen.queryByText(/No software changes/)).toBeNull();
    expect(screen.queryByRole("list", { name: "Software changes" })).toBeNull();
  });

  it("shows an empty successful read as no changes in 90 days", async () => {
    vi.mocked(getSoftwareChanges).mockResolvedValue({ days: 90, since: NOW.toISOString(), changes: [] });
    renderSection();
    expect(await screen.findByText("No software changes in 90 days")).toBeInTheDocument();
  });

  it("renders populated rows with Estate labels, summaries and an evidence stamp on every figure", async () => {
    vi.mocked(getSoftwareChanges).mockResolvedValue({
      days: 90, since: NOW.toISOString(),
      changes: [change("dev-tango-04", 2), change("dev-bravo-02", 9)],
    });
    const { container } = renderSection();
    expect(await screen.findByText("2 version changes in 90 days")).toBeInTheDocument();

    const list = screen.getByRole("list", { name: "Software changes" });
    const rows = within(list).getAllByRole("listitem");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("FW-TANGO-04");
    expect(rows[0]).toHaveTextContent("R81.10 to R81.20");
    expect(list.textContent).not.toContain("FW-ZULU-99");

    // Total + 2 rows + 2 per-unit counts: five figures, five evidence stamps.
    expect(container.querySelectorAll("[data-evidence-stamp]")).toHaveLength(5);
    expect(screen.getByTestId("software-changes-total-stamp")).toBeInTheDocument();
  });

  it("caps the row list at 10 and prints the hidden count", async () => {
    const changes = Array.from({ length: 12 }, (_, i) => change(i % 2 ? "dev-bravo-02" : "dev-tango-04", i + 1));
    vi.mocked(getSoftwareChanges).mockResolvedValue({ days: 90, since: NOW.toISOString(), changes });
    renderSection();
    const list = await screen.findByRole("list", { name: "Software changes" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(10);
    expect(screen.getByTestId("software-changes-more")).toHaveTextContent("+2 more version changes");
  });
});
