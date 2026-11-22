import { describe, expect, it } from "vitest";
import type { PacketCase, SupportBundleRequest } from "../src/auth/adminApi";
import {
  CASES_BUNDLES_PER_CASE,
  CASES_SHOWN,
  CASE_ACTION_HREFS,
  buildCasesModel,
  caseStateView,
} from "../src/estate/estateCasesModel";

const NOW = new Date("2026-11-22T12:00:00Z");
const hoursAgo = (h: number) => new Date(NOW.getTime() - h * 3_600_000).toISOString();

function caseRow(id: string, createdH: number, overrides: Partial<PacketCase> = {}): PacketCase {
  return {
    case_id: id,
    title: `Case ${id}`,
    created_at: hoursAgo(createdH),
    expires_at: hoursAgo(-24),
    state: "ANALYZED",
    total_bytes: 2048,
    capture_count: 2,
    payload_zeroing: false,
    latest_job_id: null,
    ...overrides,
  };
}

function bundle(id: string, caseRef: string | undefined, requestedH: number, overrides: Partial<SupportBundleRequest> = {}): SupportBundleRequest {
  return {
    id,
    device_id: "dev-tango-04",
    device_pseudonym: "FW-TANGO-04",
    vendor: "check_point",
    run_mode: "now",
    run_at: hoursAgo(requestedH),
    requested_at: hoursAgo(requestedH),
    finished_at: null,
    expires_at: hoursAgo(-48),
    expiry_warning: false,
    downloaded_at: null,
    download_count: 0,
    state: "COMPLETED",
    outcome: null,
    items: [],
    case_ref: caseRef,
    ...overrides,
  };
}

describe("caseStateView", () => {
  it("maps every known packet case state and never shows an unknown state as OK", () => {
    expect(caseStateView("ANALYZED")).toEqual({ state: "ok", word: "Analysed" });
    expect(caseStateView("QUEUED").state).toBe("watch");
    expect(caseStateView("ANALYZING").state).toBe("watch");
    expect(caseStateView("OPEN").state).toBe("na");
    expect(caseStateView("FAILED").state).toBe("act");
    expect(caseStateView("SOMETHING_NEW")).toEqual({ state: "unk", word: "UNKNOWN" });
    expect(caseStateView(undefined)).toEqual({ state: "unk", word: "UNKNOWN" });
  });
});

describe("buildCasesModel", () => {
  it("lists cases newest first", () => {
    const model = buildCasesModel([caseRow("c-old", 50), caseRow("c-new", 2), caseRow("c-mid", 10)], [], NOW);
    expect(model.rows.map((r) => r.caseId)).toEqual(["c-new", "c-mid", "c-old"]);
    expect(model.caseCount).toBe(3);
    expect(model.rows[0].createdAge).toBe("2 h ago");
  });

  it("links bundles only by exact case reference and counts the rest as unlinked", () => {
    const model = buildCasesModel(
      [caseRow("c-1", 5), caseRow("c-2", 6)],
      [
        bundle("b-1", "c-1", 1),
        bundle("b-2", "c-1", 3),
        bundle("b-3", "c-2 ", 2),
        bundle("b-4", "TAC-ELSEWHERE", 1),
        bundle("b-5", "", 1),
        bundle("b-6", undefined, 1),
      ],
      NOW,
    );
    const [c1, c2] = model.rows;
    expect(c1.bundleCount).toBe(2);
    expect(c1.bundles.map((b) => b.id)).toEqual(["b-1", "b-2"]);
    expect(c2.bundleCount).toBe(1);
    expect(model.bundlesUnlinked).toBe(1);
    expect(model.bundlesReferenceHidden).toBe(1);
    expect(model.bundleTotal).toBe(6);
  });

  it("caps bundles per case and prints how many are left", () => {
    const many = [1, 2, 3, 4, 5].map((h) => bundle(`b-${h}`, "c-1", h));
    const [row] = buildCasesModel([caseRow("c-1", 5)], many, NOW).rows;
    expect(row.bundleCount).toBe(5);
    expect(row.bundles).toHaveLength(CASES_BUNDLES_PER_CASE);
    expect(row.bundlesMore).toBe(5 - CASES_BUNDLES_PER_CASE);
  });

  it("caps the case list and reports the rest", () => {
    const cases = Array.from({ length: CASES_SHOWN + 4 }, (_, i) => caseRow(`c-${i}`, i + 1));
    const model = buildCasesModel(cases, [], NOW);
    expect(model.rows).toHaveLength(CASES_SHOWN);
    expect(model.rowsMore).toBe(4);
    expect(model.caseCount).toBe(CASES_SHOWN + 4);
  });

  it("marks bundles unknown, not empty, when the bundle list could not be read", () => {
    const model = buildCasesModel([caseRow("c-1", 5)], null, NOW);
    expect(model.bundlesKnown).toBe(false);
    expect(model.rows[0].bundleCount).toBe(0);
    expect(model.bundleTotal).toBe(0);
  });

  it("uses a placeholder title for untitled cases and keeps the packet state", () => {
    const [row] = buildCasesModel([caseRow("c-1", 5, { title: "  ", state: "FAILED" })], [], NOW).rows;
    expect(row.title).toBe("Untitled case");
    expect(row.view.state).toBe("act");
  });

  it("sends every action to the standard view with standard=1", () => {
    for (const href of Object.values(CASE_ACTION_HREFS)) {
      expect(href).toMatch(/standard=1$/);
    }
  });
});
