import { render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getClusterMemberDiff, type ClusterMemberDiff } from "../src/auth/adminApi";
import { EstateDossierMemberDiffBlock, MEMBER_DIFF_UNKNOWN_TEXT } from "../src/estate/EstateDossierMemberDiffBlock";

vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return { ...actual, getClusterMemberDiff: vi.fn() };
});

const mockedRead = vi.mocked(getClusterMemberDiff);

// Synthetic identifiers only (FW-TANGO-04 style names, 192.0.2.x addresses).
const DEVICE_A = "dev-tango-04";
const DEVICE_B = "dev-tango-05";
const LABELS = { [DEVICE_A]: "FW-TANGO-04", [DEVICE_B]: "FW-TANGO-05" };

const DIFF: ClusterMemberDiff = {
  cluster_member_ref: "CLS-TANGO-01",
  members: [{ device_id: DEVICE_A }, { device_id: DEVICE_B }],
  comparable: true,
  reason: null,
  summary: { same: 1, differs: 1, unknown: 0 },
  fields: [
    {
      key: "mgmt_address",
      label: "Management address",
      note: null,
      status: "DIFFERS",
      values: { [DEVICE_A]: "192.0.2.10", [DEVICE_B]: "192.0.2.11" },
      detail: [],
    },
    {
      key: "software_version",
      label: "Software version",
      note: null,
      status: "SAME",
      values: { [DEVICE_A]: "R81.10", [DEVICE_B]: "R81.10" },
      detail: [],
    },
  ],
};

describe("EstateDossierMemberDiffBlock", () => {
  beforeEach(() => {
    mockedRead.mockReset();
  });

  it("renders the member rows from the mocked diff for a cluster unit", async () => {
    mockedRead.mockResolvedValue(DIFF);

    render(<EstateDossierMemberDiffBlock clusterRef="CLS-TANGO-01" labels={LABELS} />);

    expect(screen.getByText("Loading member differences…")).toBeTruthy();
    const table = await screen.findByRole("table", { name: "Member differences" });
    expect(mockedRead).toHaveBeenCalledWith("CLS-TANGO-01");
    expect(within(table).getByText("Management address")).toBeTruthy();
    expect(within(table).getByText("Software version")).toBeTruthy();
    expect(within(table).getByText("192.0.2.10")).toBeTruthy();
    expect(within(table).getByText("192.0.2.11")).toBeTruthy();
    expect(within(table).getByText("FW-TANGO-05")).toBeTruthy();
    expect(screen.queryByTestId("dossier-member-diff-more")).toBeNull();
  });

  it("shows the UNKNOWN text and no OK word when the read fails", async () => {
    mockedRead.mockRejectedValue(new Error("synthetic failure"));

    const { container } = render(<EstateDossierMemberDiffBlock clusterRef="CLS-TANGO-01" labels={LABELS} />);

    const unknown = await screen.findByText(MEMBER_DIFF_UNKNOWN_TEXT);
    expect(unknown).toBeTruthy();
    expect(screen.queryByRole("table")).toBeNull();
    expect(container.textContent ?? "").not.toMatch(/\bOK\b/);
    expect(screen.queryByText("Loading member differences…")).toBeNull();
  });

  it("makes no call for a unit without a cluster ref", async () => {
    const { container } = render(<EstateDossierMemberDiffBlock clusterRef={null} labels={LABELS} />);

    await waitFor(() => expect(container.textContent).toBe(""));
    expect(mockedRead).not.toHaveBeenCalled();
    expect(container.innerHTML).toBe("");
  });
});
