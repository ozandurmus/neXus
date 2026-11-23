import { render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as adminApi from "../src/auth/adminApi";
import type { FailoverIncident } from "../src/auth/adminApi";
import { EstateFailoverIncidents } from "../src/estate/EstateFailoverIncidents";

vi.mock("../src/auth/adminApi", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../src/auth/adminApi")>()),
  listFailoverIncidents: vi.fn(),
}));

const listMock = vi.mocked(adminApi.listFailoverIncidents);

function incident(n: number, overrides: Partial<FailoverIncident> = {}): FailoverIncident {
  return {
    incidentRef: `INC-${n}`,
    clusterId: `CLS-${n}`,
    unitId: `UNIT-${n}`,
    cluster_member_ref: `member-${n}`,
    maskedName: `FW-TANGO-0${(n % 9) + 1}`,
    reason: "synthetic",
    openedAt: new Date(Date.UTC(2026, 10, 1, 0, n)).toISOString(),
    incidentActive: true,
    blocksEstate: false,
    unresolvedSends: 0,
    members: [],
    ...overrides,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const payload = (incidents: FailoverIncident[]) => ({ canRelease: false, roleNote: null, incidents });

describe("EstateFailoverIncidents", () => {
  beforeEach(() => listMock.mockReset());
  afterEach(() => vi.restoreAllMocks());

  it("shows loading while the read is in flight", async () => {
    const pending = deferred<ReturnType<typeof payload>>();
    listMock.mockReturnValueOnce(pending.promise);
    render(<EstateFailoverIncidents units={[]} />);
    expect(await screen.findByRole("status")).toHaveTextContent("Reading failover incidents");
    pending.resolve(payload([]));
    expect(await screen.findByText(/0 failover incidents in the last read/)).toBeInTheDocument();
  });

  it("shows a failed read as UNKNOWN, never as no incidents", async () => {
    listMock.mockRejectedValueOnce(new Error("synthetic failure"));
    render(<EstateFailoverIncidents units={[]} />);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("UNKNOWN");
    expect(alert).toHaveTextContent("not zero incidents");
    expect(screen.queryByText(/0 failover incidents/)).not.toBeInTheDocument();
  });

  it("treats a malformed payload as a failed read", async () => {
    listMock.mockResolvedValueOnce({ canRelease: false, roleNote: null, incidents: null } as unknown as ReturnType<typeof payload>);
    render(<EstateFailoverIncidents units={[]} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("UNKNOWN");
  });

  it("prints the empty state only after a successful read", async () => {
    listMock.mockResolvedValueOnce(payload([]));
    render(<EstateFailoverIncidents units={[]} />);
    expect(await screen.findByText("0 failover incidents in the last read")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("renders populated rows with a status word, evidence stamp and unit label", async () => {
    listMock.mockResolvedValueOnce(
      payload([
        incident(1, { incidentActive: true, maskedName: "FW-TANGO-04" }),
        incident(2, { incidentActive: false, maskedName: "FW-JULIET-06" }),
        incident(3, { incidentActive: undefined as unknown as boolean, maskedName: "FW-BRAVO-02" }),
      ]),
    );
    const units = [
      { unitId: "UNIT-1", clusterRef: "CLS-ZULU-01", label: "CLS-ROMEO-01" },
    ] as unknown as Parameters<typeof EstateFailoverIncidents>[0]["units"];
    render(<EstateFailoverIncidents units={units} />);
    const rows = await screen.findAllByTestId("failover-incident-row");
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent("Incident open");
    expect(rows[0]).toHaveTextContent("CLS-ROMEO-01");
    expect(rows[1]).toHaveTextContent("UNKNOWN");
    expect(rows[1]).not.toHaveTextContent("OK");
    expect(rows[2]).toHaveTextContent("Unresolved send");
    expect(screen.getByText("3 incidents listed: 1 open, 1 unresolved send, 1 with UNKNOWN status")).toBeInTheDocument();
    for (const row of rows) {
      expect(row.querySelector("[data-evidence-stamp]")).not.toBeNull();
    }
  });

  it("pluralises the listed count: 1 incident, 2 incidents", async () => {
    listMock.mockResolvedValueOnce(payload([incident(1, { maskedName: "FW-TANGO-04" })]));
    const { unmount } = render(<EstateFailoverIncidents units={[]} />);
    expect(await screen.findByText(/^1 incident listed:/)).toBeInTheDocument();
    unmount();
    listMock.mockResolvedValueOnce(
      payload([
        incident(1, { maskedName: "FW-TANGO-04" }),
        incident(2, { maskedName: "FW-JULIET-06" }),
      ]),
    );
    render(<EstateFailoverIncidents units={[]} />);
    expect(await screen.findByText(/^2 incidents listed:/)).toBeInTheDocument();
  });

  it("caps at 10 rows and prints +N more", async () => {
    listMock.mockResolvedValueOnce(payload(Array.from({ length: 12 }, (_, i) => incident(i + 1))));
    render(<EstateFailoverIncidents units={[]} />);
    const section = await screen.findByLabelText("Failover incidents");
    expect(await within(section).findAllByTestId("failover-incident-row")).toHaveLength(10);
    expect(within(section).getByTestId("failover-incidents-more")).toHaveTextContent("+2 more incidents");
  });

  it("offers no release or write button and never calls a write endpoint", async () => {
    listMock.mockResolvedValueOnce(payload([incident(1)]));
    render(<EstateFailoverIncidents units={[]} />);
    await screen.findAllByTestId("failover-incident-row");
    const buttons = screen.queryAllByRole("button");
    for (const button of buttons) {
      expect(button.textContent ?? "").not.toMatch(/release|install|push|apply|write|send|delete|approve/i);
    }
    expect(listMock).toHaveBeenCalledTimes(1);
  });
});

describe("EstateFailoverIncidents summary stamp and row detail", () => {
  beforeEach(() => listMock.mockReset());

  it("stamps the summary line when the list is populated", async () => {
    listMock.mockResolvedValueOnce(payload([incident(1, { maskedName: "FW-TANGO-04" })]));
    render(<EstateFailoverIncidents units={[]} />);
    const summary = await screen.findByText(/^1 incident listed:/);
    const line = summary.parentElement as HTMLElement;
    expect(line.querySelector("[data-evidence-stamp]")).not.toBeNull();
    expect(line).toHaveTextContent("incidents read");
  });

  it("stamps the empty-state line too", async () => {
    listMock.mockResolvedValueOnce(payload([]));
    render(<EstateFailoverIncidents units={[]} />);
    const line = (await screen.findByText("0 failover incidents in the last read")).parentElement as HTMLElement;
    expect(line.querySelector("[data-evidence-stamp]")).not.toBeNull();
  });

  it("renders the run reference, reason and unresolved-send count on an estate-blocking incident", async () => {
    listMock.mockResolvedValueOnce(
      payload([incident(7, { blocksEstate: true, unresolvedSends: 2, reason: "synthetic reason", maskedName: "FW-TANGO-04" })]),
    );
    render(<EstateFailoverIncidents units={[]} />);
    const row = await screen.findByTestId("failover-incident-row");
    expect(row).toHaveTextContent("Run INC-7");
    expect(row).toHaveTextContent("Reason: synthetic reason");
    expect(row).toHaveTextContent("blocks every failover in the estate (2 unresolved sends)");
  });

  it("prints UNKNOWN for a missing run reference and reason, and no count sentence for a non-blocking incident", async () => {
    listMock.mockResolvedValueOnce(
      payload([incident(8, { incidentRef: "", reason: "", blocksEstate: false, maskedName: "FW-JULIET-06" })]),
    );
    render(<EstateFailoverIncidents units={[]} />);
    const row = await screen.findByTestId("failover-incident-row");
    expect(row).toHaveTextContent("Run reference UNKNOWN");
    expect(row).toHaveTextContent("Reason UNKNOWN");
    expect(row).not.toHaveTextContent("blocks every failover");
  });

  it("says the unresolved-send count is UNKNOWN on a blocking incident without a usable count", async () => {
    listMock.mockResolvedValueOnce(
      payload([incident(9, { blocksEstate: true, unresolvedSends: undefined as unknown as number, maskedName: "FW-BRAVO-02" })]),
    );
    render(<EstateFailoverIncidents units={[]} />);
    const row = await screen.findByTestId("failover-incident-row");
    expect(row).toHaveTextContent("blocks every failover in the estate (unresolved sends count UNKNOWN)");
  });
});
