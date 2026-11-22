import { render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { resetSharedApiCache } from "../src/auth/adminApi";

// Review fix 4 (docs/reviews/ESTATE_VIEW_EFFECTIVENESS_REVIEW_2026_11_21.md): the Changes feed says when more
// events exist than it shows, and the Brief never presents its unknown-actor count as complete on a capped feed.
// Synthetic fixtures only (FW-TANGO-04 style names, 192.0.2.x addresses).
const headers = { "X-Nexus-Masked": "true" };

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });
}

function stubViewport(widthPx: number) {
  Object.defineProperty(window, "innerWidth", { writable: true, configurable: true, value: widthPx });
  vi.stubGlobal(
    "matchMedia",
    vi.fn((query: string) => {
      const minMatch = query.match(/\(min-width:\s*(\d+)px\)/);
      const maxMatch = query.match(/\(max-width:\s*(\d+(?:\.\d+)?)px\)/);
      const matches = minMatch ? widthPx >= Number(minMatch[1]) : maxMatch ? widthPx <= Number(maxMatch[1]) : false;
      return {
        matches, media: query, onchange: null,
        addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
      };
    }),
  );
}

const daysAgo = (days: number) => new Date(Date.now() - days * 86_400_000).toISOString();

const DEVICES = [
  {
    device_id: "dev-tango-04", vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED",
    hostname: "FW-TANGO-04", management_ip: "192.0.2.14", backup_target: true,
    inventory_collected_at: daysAgo(0),
  },
];

/** One synthetic USER change whose actor the feed could not attribute (actor UNKNOWN). */
function userEvent(index: number) {
  const at = daysAgo(index + 1);
  return {
    key: `evt-tango-${index}`, kind: "USER", source: "config", time: at, time_basis: "UTC", device_time: null,
    vendor: "check_point", device_id: "dev-tango-04", device_name: "FW-TANGO-04", actor: "UNKNOWN",
    what: "Rule changed", detail: null, tone: "UNKNOWN", diff: [],
  };
}

function feedBody(events: readonly unknown[], nextCursor: string) {
  return {
    events, next_cursor: nextCursor, range_start: daysAgo(7), range_end: daysAgo(0), kinds: ["USER", "NEXUS_JOB", "DEVICE_EVENT"],
    vendor: null, device_id: null, masked: true, ordering_note: "", coverage: [],
  };
}

/** Every read answers 500 unless `routes` supplies a body; /devices always succeeds. */
function stubFetch(routes: Record<string, () => unknown>) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url === "/devices") return jsonResponse(200, { devices: DEVICES });
    for (const [prefix, body] of Object.entries(routes)) {
      if (url === prefix || url.startsWith(`${prefix}?`)) return jsonResponse(200, body());
    }
    return jsonResponse(500, { error: "DOWNSTREAM_ERROR" });
  });
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

describe("Estate Changes feed cursor notice (review fix 4)", () => {
  it("a feed with a non-empty next_cursor shows the shown count and that more events exist", async () => {
    window.history.replaceState(null, "", "/?screen=changes");
    vi.stubGlobal("fetch", stubFetch({ "/api/v2/changes/feed": () => feedBody([userEvent(0), userEvent(1)], "cur-tango-page-2") }));
    stubViewport(1280);
    render(<App />);
    const notice = await screen.findByTestId("changes-feed-truncated");
    expect(notice).toHaveTextContent(/2 events shown, more exist in this range/);
    expect(notice).toHaveTextContent(/Partial feed/);
  });

  it("a feed with an empty next_cursor shows no more-exist notice", async () => {
    window.history.replaceState(null, "", "/?screen=changes");
    vi.stubGlobal("fetch", stubFetch({ "/api/v2/changes/feed": () => feedBody([userEvent(0)], "") }));
    stubViewport(1280);
    render(<App />);
    // Wait for the feed to settle: the events list renders once the read is ok.
    await screen.findByText(/FW-TANGO-04/);
    expect(screen.queryByTestId("changes-feed-truncated")).toBeNull();
    expect(screen.queryByText(/more exist in this range/)).toBeNull();
  });
});

describe("Estate Brief unknown-actor caveat on a capped change feed (review fix 4)", () => {
  function unknownActorRow() {
    const label = screen.getByText("Changes with actor UNKNOWN (7 d)");
    return label.parentElement as HTMLElement;
  }

  it("a capped feed never presents the unknown-actor count as complete", async () => {
    window.history.replaceState(null, "", "/?screen=brief");
    vi.stubGlobal("fetch", stubFetch({ "/api/v2/changes/feed": () => feedBody([userEvent(0)], "cur-tango-page-2") }));
    stubViewport(1280);
    render(<App />);
    await screen.findByText("Changes with actor UNKNOWN (7 d)");
    const row = unknownActorRow();
    expect(within(row).getByText("1+ (first page only)")).toBeInTheDocument();
  });

  it("an uncapped feed shows the unknown-actor count without a truncation caveat", async () => {
    window.history.replaceState(null, "", "/?screen=brief");
    vi.stubGlobal("fetch", stubFetch({ "/api/v2/changes/feed": () => feedBody([userEvent(0)], "") }));
    stubViewport(1280);
    render(<App />);
    await screen.findByText("Changes with actor UNKNOWN (7 d)");
    const row = unknownActorRow();
    expect(within(row).getByText("1")).toBeInTheDocument();
    expect(row).not.toHaveTextContent(/first page only/);
    expect(row).not.toHaveTextContent(/1\+/);
  });
});
