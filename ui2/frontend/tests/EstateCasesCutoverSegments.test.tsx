import { render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { EstateCasesScreen } from "../src/estate/EstateCasesScreen";
import { listCutoverSegments, type CutoverSegment } from "../src/auth/adminApi";
import { buildCutoverSegmentsModel, CUTOVER_NOT_REPORTED } from "../src/estate/estateCutoverSegmentsModel";

vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return { ...actual, listCutoverSegments: vi.fn() };
});

const listMock = vi.mocked(listCutoverSegments);

const segment = (n: number): CutoverSegment => ({
  segmentId: `seg-${n}`,
  name: `FW-TANGO-0${n} cutover`,
  revision: n,
  blocks: ["192.0.2.0/24"],
  peerBlocks: ["198.51.100.0/24", "198.51.100.128/25"],
});

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

/** Packet-case and bundle reads are not under test here; they answer empty so the case list settles. */
function stubCaseReads() {
  vi.stubGlobal("fetch", vi.fn(async () => json({}, 404)));
}

function section() {
  return screen.findByTestId("cutover-segments-section");
}

describe("EstateCasesScreen cutover segments section", () => {
  beforeEach(() => {
    listMock.mockReset();
    stubCaseReads();
  });

  it("renders one read-only row per segment with no create, edit or delete control", async () => {
    listMock.mockResolvedValue({ segments: [segment(1), segment(2)], canManage: true });
    render(<EstateCasesScreen />);
    const sec = await section();
    const rows = await within(sec).findAllByTestId("cutover-segment-row");
    expect(rows).toHaveLength(2);
    expect(within(sec).getByText("FW-TANGO-01 cutover")).toBeInTheDocument();
    expect(within(sec).getByText("FW-TANGO-02 cutover")).toBeInTheDocument();
    expect(within(sec).getAllByText(/Blocks: 1/)).not.toHaveLength(0);
    for (const word of [/create/i, /edit/i, /delete/i, /rename/i, /save/i, /remove/i]) {
      expect(within(sec).queryByRole("button", { name: word })).toBeNull();
      expect(within(sec).queryByText(word)).toBeNull();
    }
    expect(within(sec).queryAllByRole("button")).toHaveLength(0);
  });

  it("shows the failed-read text with its own time and never a zero count", async () => {
    listMock.mockRejectedValue(new Error("upstream unavailable"));
    render(<EstateCasesScreen />);
    const sec = await section();
    const failed = await within(sec).findByTestId("cutover-segments-failed");
    expect(failed.textContent).toContain("Cutover segments could not be read");
    expect(failed.textContent).toMatch(/read /);
    expect(sec.textContent).not.toMatch(/\b0\b/);
    expect(within(sec).queryByTestId("cutover-segment-row")).toBeNull();
  });

  it("prints +N more when more than eight segments are returned", async () => {
    const many = Array.from({ length: 11 }, (_, i) => segment(i + 1));
    listMock.mockResolvedValue({ segments: many, canManage: false });
    render(<EstateCasesScreen />);
    const sec = await section();
    expect(await within(sec).findAllByTestId("cutover-segment-row")).toHaveLength(8);
    expect(within(sec).getByTestId("cutover-segments-more").textContent).toBe("+3 more segments");
  });

  it("links to the standard cutover screen with standard=1", async () => {
    listMock.mockResolvedValue({ segments: [], canManage: false });
    render(<EstateCasesScreen />);
    const sec = await section();
    const link = within(sec).getByRole("link", { name: "Open cutover (standard view)" });
    expect(link).toHaveAttribute("href", "?screen=cutover&standard=1");
    expect(await within(sec).findByText("No cutover segments are stored.")).toBeInTheDocument();
  });
});

describe("estateCutoverSegmentsModel", () => {
  const readAt = new Date("2026-11-22T10:00:00Z");
  const now = new Date("2026-11-22T10:02:00Z");

  it("maps a field the response does not carry to 'not reported', never 0", () => {
    const partial = [{ segmentId: "seg-x", name: "FW-TANGO-04 cutover" }] as Partial<CutoverSegment>[];
    const model = buildCutoverSegmentsModel(partial, readAt, now);
    expect(model.state).toBe("ok");
    if (model.state !== "ok") return;
    const row = model.rows[0];
    expect(row.name).toBe("FW-TANGO-04 cutover");
    for (const field of row.fields) {
      expect(field.value).toBe(CUTOVER_NOT_REPORTED);
      expect(field.value).not.toBe("0");
    }
    expect(model.stamp).toBe("read 2 min ago");
  });
});
