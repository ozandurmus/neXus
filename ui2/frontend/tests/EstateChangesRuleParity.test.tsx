import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import {
  resetSharedApiCache,
  type PolicyChangeRow,
  type PolicyChangesPage,
} from "../src/auth/adminApi";
import {
  ambiguousRevisionsForSet,
  summarisePolicyRuleRevisions,
} from "../src/estate/estateChangesRuleParityModel";

const headers = { "X-Nexus-Masked": "true" };

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}

function stubViewport(widthPx: number) {
  Object.defineProperty(window, "innerWidth", {
    writable: true,
    configurable: true,
    value: widthPx,
  });
  vi.stubGlobal(
    "matchMedia",
    vi.fn((query: string) => {
      const minMatch = query.match(/\(min-width:\s*(\d+)px\)/);
      const maxMatch = query.match(/\(max-width:\s*(\d+(?:\.\d+)?)px\)/);
      const matches = minMatch
        ? widthPx >= Number(minMatch[1])
        : maxMatch
          ? widthPx <= Number(maxMatch[1])
          : false;
      return {
        matches,
        media: query,
        onchange: null,
        addListener: vi.fn(),
        removeListener: vi.fn(),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        dispatchEvent: vi.fn(),
      };
    }),
  );
}

const hoursAgo = (h: number) => new Date(Date.now() - h * 3_600_000).toISOString();

const DEVICES = [
  {
    device_id: "dev-tango-04",
    vendor_hint: "palo_alto",
    role: "gateway",
    enrollment_state: "ENROLLED",
    hostname: "FW-TANGO-04",
    management_ip: "192.0.2.14",
    backup_target: true,
    inventory_collected_at: hoursAgo(1),
  },
];

function emptyFeedBody() {
  return {
    events: [],
    next_cursor: "",
    range_start: hoursAgo(48),
    range_end: hoursAgo(0),
    kinds: ["USER", "NEXUS_JOB", "DEVICE_EVENT"],
    vendor: null,
    device_id: null,
    masked: true,
    ordering_note: "",
    coverage: [],
  };
}

function sampleRow(overrides: Partial<PolicyChangeRow> = {}): PolicyChangeRow {
  const at = hoursAgo(2);
  return {
    revision: "rev-401",
    policyId: "pol-alpha",
    policyName: "POL-ALPHA-01",
    targets: [{ deviceId: "dev-tango-04", name: "FW-TANGO-04", context: "", syncStatus: "OK" }],
    ruleId: "rule-7",
    number: 7,
    name: "RULE-BRAVO-02",
    changedOn: at,
    collectedAt: at,
    observedAtCollection: false,
    changedBy: "USR-BIRCH-01",
    changeType: "modified",
    changedFields: ["action"],
    changes: [{ field: "action", before: "allow", after: "deny" }],
    ...overrides,
  };
}

beforeEach(() => {
  resetSharedApiCache();
  window.localStorage.clear();
  window.localStorage.setItem("nx.view", "estate");
});

afterEach(() => {
  resetSharedApiCache();
  window.localStorage.clear();
  vi.unstubAllGlobals();
  window.history.replaceState(null, "", "/");
});

describe("estateChangesRuleParityModel pure helpers", () => {
  it("formats distinct rules and added/modified/removed counts with unit words, and fails closed to UNKNOWN on missing or malformed counts", () => {
    const okPage: PolicyChangesPage = {
      rows: [sampleRow()],
      summary: {
        total: 4,
        actors: 2,
        rules: 3,
        counts: { added: 1, modified: 2, removed: 1 },
      },
      nextCursor: "cur-next-2",
      rangeStart: hoursAgo(24),
      rangeEnd: hoursAgo(0),
    };

    const res = summarisePolicyRuleRevisions({ state: "ok", value: okPage });
    expect(res).toEqual({
      state: "ok",
      distinctRulesText: "3 distinct rules",
      breakdownText: "1 added rule · 2 modified rules · 1 removed rule",
      truncated: true,
      shownRowsCount: 1,
    });

    const malformedPage = {
      ...okPage,
      nextCursor: "",
      summary: {
        total: 2,
        actors: 1,
        rules: Number.NaN,
        counts: { added: Number.NaN, modified: 1, removed: 0 },
      },
    } as PolicyChangesPage;
    const malformed = summarisePolicyRuleRevisions({ state: "ok", value: malformedPage });
    expect(malformed.distinctRulesText).toBe("UNKNOWN");
    expect(malformed.breakdownText).toBe("UNKNOWN");
    expect(malformed.truncated).toBe(false);

    const failed = summarisePolicyRuleRevisions({ state: "failed" });
    expect(failed.state).toBe("unknown");
    expect(failed.distinctRulesText).toBe("UNKNOWN");
    expect(failed.breakdownText).toBe("UNKNOWN");
  });

  it("extracts distinct AMBIGUOUS revisions for a rule change set capped at 5", () => {
    const at = "2026-11-22T10:00:00Z";
    const rows: PolicyChangeRow[] = Array.from({ length: 7 }, (_, idx) =>
      sampleRow({
        revision: `rev-amb-${idx + 1}`,
        policyId: "pol-alpha",
        collectedAt: at,
        changedOn: at,
        changedBy: "AMBIGUOUS (2 administrators before the commit)",
        actorState: "AMBIGUOUS",
      }),
    );
    const setKey = JSON.stringify(["pol-alpha", at, "AMBIGUOUS", false]);
    const revs = ambiguousRevisionsForSet(
      { id: `pol:${setKey}`, category: "rule", actorIsAmbiguous: true },
      rows,
    );
    expect(revs).toEqual([
      "rev-amb-1",
      "rev-amb-2",
      "rev-amb-3",
      "rev-amb-4",
      "rev-amb-5",
    ]);
  });
});

describe("EstateChangesScreen rule-revision parity with standard PolicyChangesTab", () => {
  it("renders the rule-revision summary breakdown with distinct rules, added/modified/removed unit words and EvidenceStamp, and shows UNKNOWN when summary counts are malformed", async () => {
    window.history.replaceState(null, "", "/?screen=changes");
    const page: PolicyChangesPage = {
      rows: [sampleRow()],
      summary: {
        total: 4,
        actors: 1,
        rules: 3,
        counts: { added: 1, modified: 2, removed: 1 },
      },
      nextCursor: "",
      rangeStart: hoursAgo(24),
      rangeEnd: hoursAgo(0),
    };

    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/devices") return jsonResponse(200, { devices: DEVICES });
        if (url.startsWith("/api/v2/policy/changes")) return jsonResponse(200, page);
        if (url.startsWith("/api/v2/changes/feed")) return jsonResponse(200, emptyFeedBody());
        return jsonResponse(200, {});
      }),
    );
    stubViewport(1280);
    const { unmount } = render(<App />);

    const breakdown = await screen.findByTestId("changes-rule-summary-breakdown");
    expect(breakdown).toHaveTextContent("3 distinct rules");
    expect(breakdown).toHaveTextContent("1 added rule · 2 modified rules · 1 removed rule");
    expect(breakdown.querySelector("[data-evidence-stamp]")).not.toBeNull();
    expect(screen.queryByTestId("changes-policy-truncated")).toBeNull();

    unmount();
    resetSharedApiCache();

    const malformedPage = {
      ...page,
      summary: {
        total: 2,
        actors: 1,
        rules: null as unknown as number,
        counts: null as unknown as Record<string, number>,
      },
    };
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/devices") return jsonResponse(200, { devices: DEVICES });
        if (url.startsWith("/api/v2/policy/changes")) return jsonResponse(200, malformedPage);
        if (url.startsWith("/api/v2/changes/feed")) return jsonResponse(200, emptyFeedBody());
        return jsonResponse(200, {});
      }),
    );
    render(<App />);

    const malformedBreakdown = await screen.findByTestId("changes-rule-summary-breakdown");
    expect(malformedBreakdown).toHaveTextContent("UNKNOWN");
    expect(malformedBreakdown).not.toHaveTextContent(/\bOK\b/);
  });

  it("renders data-testid=changes-policy-truncated with Partial rule history when getPolicyChanges returns a non-empty nextCursor", async () => {
    window.history.replaceState(null, "", "/?screen=changes");
    const page: PolicyChangesPage = {
      rows: [sampleRow({ revision: "rev-1" }), sampleRow({ revision: "rev-2", ruleId: "rule-8" })],
      summary: {
        total: 12,
        actors: 1,
        rules: 5,
        counts: { added: 2, modified: 10, removed: 0 },
      },
      nextCursor: "cursor-page-2",
      rangeStart: hoursAgo(24),
      rangeEnd: hoursAgo(0),
    };

    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/devices") return jsonResponse(200, { devices: DEVICES });
        if (url.startsWith("/api/v2/policy/changes")) return jsonResponse(200, page);
        if (url.startsWith("/api/v2/changes/feed")) return jsonResponse(200, emptyFeedBody());
        return jsonResponse(200, {});
      }),
    );
    stubViewport(1280);
    render(<App />);

    const truncated = await screen.findByTestId("changes-policy-truncated");
    expect(truncated).toHaveTextContent("Partial rule history");
    expect(truncated).toHaveTextContent(
      "2 rule revisions shown on this page; more exist in this window (open Standard Changes to page through the rest).",
    );
  });

  it("fetches AMBIGUOUS actor candidates on click, renders Withheld in AIView on HTTP 403, and renders Candidates UNKNOWN (never OK) on failure", async () => {
    window.history.replaceState(null, "", "/?screen=changes");
    const ambiguousRow = sampleRow({
      revision: "rev-amb-77",
      changedBy: "AMBIGUOUS (2 administrators before the commit)",
      actorState: "AMBIGUOUS",
      actorCandidates: 2,
    });
    const page: PolicyChangesPage = {
      rows: [ambiguousRow],
      summary: {
        total: 1,
        actors: 0,
        rules: 1,
        counts: { added: 0, modified: 1, removed: 0 },
      },
      nextCursor: "",
      rangeStart: hoursAgo(24),
      rangeEnd: hoursAgo(0),
    };

    const candidateCalls: string[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/devices") return jsonResponse(200, { devices: DEVICES });
        if (url.includes("/api/v2/policy/changes/actor-candidates")) {
          candidateCalls.push(url);
          return jsonResponse(200, {
            revision: "rev-amb-77",
            candidates: ["USR-PINE-01", "USR-MAPLE-02"],
          });
        }
        if (url.startsWith("/api/v2/policy/changes")) return jsonResponse(200, page);
        if (url.startsWith("/api/v2/changes/feed")) return jsonResponse(200, emptyFeedBody());
        return jsonResponse(200, {});
      }),
    );
    stubViewport(1280);
    const { unmount } = render(<App />);

    const btn = await screen.findByTestId("change-set-show-candidates");
    expect(candidateCalls).toHaveLength(0);
    fireEvent.click(btn);
    expect(await screen.findByText("Candidates: USR-PINE-01, USR-MAPLE-02")).toBeInTheDocument();
    expect(candidateCalls).toHaveLength(1);
    expect(candidateCalls[0]).toContain("revision=rev-amb-77");

    unmount();
    resetSharedApiCache();

    // HTTP 403 -> Withheld in AIView
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/devices") return jsonResponse(200, { devices: DEVICES });
        if (url.includes("/api/v2/policy/changes/actor-candidates")) {
          return jsonResponse(403, { error: "WITHHELD_IN_AIVIEW" });
        }
        if (url.startsWith("/api/v2/policy/changes")) return jsonResponse(200, page);
        if (url.startsWith("/api/v2/changes/feed")) return jsonResponse(200, emptyFeedBody());
        return jsonResponse(200, {});
      }),
    );
    const { unmount: unmount403 } = render(<App />);
    fireEvent.click(await screen.findByTestId("change-set-show-candidates"));
    expect(await screen.findByText("Withheld in AIView")).toBeInTheDocument();

    unmount403();
    resetSharedApiCache();

    // HTTP 500 -> Candidates UNKNOWN (never OK)
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/devices") return jsonResponse(200, { devices: DEVICES });
        if (url.includes("/api/v2/policy/changes/actor-candidates")) {
          return jsonResponse(500, { error: "DOWNSTREAM_ERROR" });
        }
        if (url.startsWith("/api/v2/policy/changes")) return jsonResponse(200, page);
        if (url.startsWith("/api/v2/changes/feed")) return jsonResponse(200, emptyFeedBody());
        return jsonResponse(200, {});
      }),
    );
    render(<App />);
    fireEvent.click(await screen.findByTestId("change-set-show-candidates"));
    const unkBadge = await screen.findByText("Candidates UNKNOWN");
    expect(unkBadge).toBeInTheDocument();
    const setRow = document.querySelector('[data-change-category="rule"]') as HTMLElement;
    expect(within(setRow).queryByText(/^(OK|Fine)$/)).toBeNull();
  });
});
