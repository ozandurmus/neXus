import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";
import { resetSharedApiCache } from "../src/auth/adminApi";

// Test-only guard: the Brief headline and its item sentences never claim a cause the evidence cannot prove.
// (design/opus-bold/CONCEPT.md, "Risks and what to test": "Sentence headlines must never imply a cause the
// evidence cannot prove.") Synthetic fixtures only (FW-TANGO-04 style names, 192.0.2.x addresses).

/** Whole-word causal claim words, matched case-insensitively. Any hit fails the guard. */
const CAUSAL_CLAIM_WORDS: readonly string[] = [
  "because",
  "caused",
  "causes",
  "due to",
  "root cause",
  "as a result",
  "therefore",
  "since the",
];

/**
 * Documented exception: the Brief delta heading "Since yesterday: ..." is a time anchor, not a causal claim.
 * It is allowed because none of the banned phrases above matches it ("since the" needs the word "the" after
 * "since"). The guard never strips it; it simply does not match.
 */
const ALLOWED_DELTA_HEADING = /^Since yesterday:/;

function causalPattern(): RegExp {
  const alternatives = CAUSAL_CLAIM_WORDS.map((w) => w.split(" ").join("\\s+"));
  return new RegExp(`\\b(?:${alternatives.join("|")})\\b`, "i");
}

/** Split rendered text into sentences on terminal punctuation or line-level separators. */
function sentencesOf(text: string): string[] {
  return text
    .split(/(?<=[.!?])\s+|\n+/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
}

function violationsIn(text: string): string[] {
  const pattern = causalPattern();
  return sentencesOf(text).filter((s) => pattern.test(s) && !ALLOWED_DELTA_HEADING.test(s));
}

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

const FAILING_DEVICES = [
  {
    device_id: "dev-tango-04", vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED",
    hostname: "FW-TANGO-04", management_ip: "192.0.2.14", backup_target: true,
    inventory_collected_at: daysAgo(5),
  },
  {
    device_id: "dev-juliet-06", vendor_hint: "check_point", role: "gateway", enrollment_state: "ENROLLED",
    hostname: "FW-JULIET-06", management_ip: "192.0.2.26", backup_target: true,
    inventory_collected_at: daysAgo(0),
  },
];

/** Overview body with one unit lacking a stored archive, one stale read and failed jobs. */
function failingOverview() {
  const at = daysAgo(0);
  return {
    generated_at: at,
    masked: true,
    denominators: { active_devices: 2, enrolled_devices: 2, gateways: 2, clusters: 0, clusters_enrolled: 0, backup_targets: 2 },
    evidence: {
      inventory: { at, state: "OK" }, configuration: { at, state: "OK" }, compliance: { at, state: "OK" },
      backup: { at, state: "OK" }, jobs: { at, state: "OK" }, platform_facts: { at, state: "OK" },
    },
    attention: {
      backup_missing: { state: "OK", count: 1, of: 2, previous: 0 },
      failed_jobs_24h: { state: "OK", count: 2, terminal_24h: 2, other_terminal_24h: 0, previous: 1 },
      cluster_diff: { state: "OK", count: 0 },
      config_changed: { state: "OK", count: 0 },
    },
    estate: {
      compliance_state: "OK",
      devices: [
        { device_id: "dev-tango-04", hostname: "FW-TANGO-04", no_archive: true, backup_target: true, last_backup_at: null, last_read_at: daysAgo(5), critical_fail: 0 },
        { device_id: "dev-juliet-06", hostname: "FW-JULIET-06", no_archive: false, backup_target: true, last_backup_at: daysAgo(0), last_read_at: daysAgo(0), critical_fail: 0 },
      ],
    },
    exceptions: {
      failed_jobs: { total: 2, rows: [{ device_id: "dev-tango-04", label: "FW-TANGO-04", finished_at: daysAgo(0) }], reasons: [{ reason: "TIMEOUT", count: 2 }] },
    },
  };
}

/** Overview body with every source OK and nothing to act on. */
function allClearOverview() {
  const at = daysAgo(0);
  return {
    generated_at: at,
    masked: true,
    denominators: { active_devices: 1, enrolled_devices: 1, gateways: 1, clusters: 0, clusters_enrolled: 0, backup_targets: 1 },
    evidence: {
      inventory: { at, state: "OK" }, configuration: { at, state: "OK" }, compliance: { at, state: "OK" },
      backup: { at, state: "OK" }, jobs: { at, state: "OK" }, platform_facts: { at, state: "OK" },
    },
    attention: {
      backup_missing: { state: "OK", count: 0, of: 1 },
      failed_jobs_24h: { state: "OK", count: 0, terminal_24h: 0, other_terminal_24h: 0 },
      cluster_diff: { state: "OK", count: 0 },
      config_changed: { state: "OK", count: 0 },
    },
    estate: {
      compliance_state: "OK",
      devices: [
        { device_id: "dev-tango-04", hostname: "FW-TANGO-04", no_archive: false, backup_target: true, last_backup_at: daysAgo(0), last_read_at: daysAgo(0), critical_fail: 0 },
      ],
    },
  };
}

/** Overview with a zero-unit estate: the denominators are zero, so most lenses are not applicable. */
function notApplicableOverview() {
  const at = daysAgo(0);
  return {
    generated_at: at,
    masked: true,
    denominators: { active_devices: 0, enrolled_devices: 0, gateways: 0, clusters: 0, clusters_enrolled: 0, backup_targets: 0 },
    evidence: {
      inventory: { at, state: "NOT_APPLICABLE" }, configuration: { at, state: "NOT_APPLICABLE" }, compliance: { at, state: "NOT_APPLICABLE" },
      backup: { at, state: "NOT_APPLICABLE" }, jobs: { at, state: "NOT_APPLICABLE" }, platform_facts: { at, state: "NOT_APPLICABLE" },
    },
    attention: {
      backup_missing: { state: "NOT_APPLICABLE", count: 0, of: 0 },
      failed_jobs_24h: { state: "NOT_APPLICABLE", count: 0, terminal_24h: 0, other_terminal_24h: 0 },
      cluster_diff: { state: "NOT_APPLICABLE", count: 0 },
      config_changed: { state: "NOT_APPLICABLE", count: 0 },
    },
    estate: { compliance_state: "NOT_APPLICABLE", devices: [] },
  };
}

/** Overview whose evidence and attention blocks are malformed or empty: nothing readable may read as OK. */
function malformedOverview() {
  return {
    generated_at: null,
    masked: true,
    denominators: {},
    evidence: { inventory: { at: null, state: "" }, backup: {} },
    attention: { backup_missing: { count: "many" }, failed_jobs_24h: null },
    estate: { devices: [{ device_id: "dev-tango-04", hostname: "FW-TANGO-04" }] },
  };
}

/** Every read answers 500 unless `routes` supplies a body; the /devices read is supplied per fixture. */
function stubFetch(devices: unknown[], overview: (() => unknown) | null) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url === "/devices") return jsonResponse(200, { devices });
    if (overview && (url === "/api/v2/overview" || url.startsWith("/api/v2/overview?"))) return jsonResponse(200, overview());
    return jsonResponse(500, { error: "DOWNSTREAM_ERROR" });
  });
}

/** Renders the Brief screen with the given fixture and returns the sentences of its headline and body. */
async function renderBriefSentences(devices: unknown[], overview: (() => unknown) | null): Promise<string[]> {
  window.history.replaceState(null, "", "/?screen=overview");
  vi.stubGlobal("fetch", stubFetch(devices, overview));
  stubViewport(1280);
  render(<App />);
  await screen.findByTestId("brief-headline");
  const headline = screen.getByTestId("brief-headline").textContent ?? "";
  const body = screen.getByTestId("estate-brief-screen").textContent ?? "";
  return sentencesOf(`${headline}\n${body}`);
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

describe("Estate Brief causal wording guard (test only)", () => {
  it("self-check: the banned-word list is not empty and the guard fails on a planted sentence", () => {
    expect(CAUSAL_CLAIM_WORDS.length).toBeGreaterThan(0);
    // Planted sentences are built at runtime: test strings, not product claims.
    const plantedCausal = ["The", "backup", "is", "late", "because", "of", "the", "agent"].join(" ");
    expect(violationsIn(plantedCausal)).toHaveLength(1);
    expect(violationsIn(plantedCausal.toUpperCase())).toHaveLength(1);
    expect(violationsIn(["Two", "units", "need", "review", "due", "to", "a", "stale", "read."].join(" "))).toHaveLength(1);
    // A clean sentence without any banned phrase passes (the guard is word-based, not a bare substring check).
    expect(violationsIn(["The", "backup", "is", "late", "for", "the", "agent."].join(" "))).toHaveLength(0);
    // The delta heading exception is honoured and does not hide a real violation in the same text.
    expect(violationsIn("Since yesterday: failed jobs increased by 1.")).toHaveLength(0);
    expect(violationsIn(["Since yesterday: failed jobs increased by 1.", "Late backup", "because", "of an agent."].join(" "))).toHaveLength(1);
  });

  it("failing fixture: no headline or item sentence claims a cause", async () => {
    const sentences = await renderBriefSentences(FAILING_DEVICES, failingOverview);
    expect(sentences.length).toBeGreaterThan(0);
    expect(violationsIn(sentences.join("\n"))).toEqual([]);
  });

  it("unknown fixture (overview unreadable, devices readable): no headline sentence claims a cause", async () => {
    const sentences = await renderBriefSentences(FAILING_DEVICES, null);
    expect(sentences.length).toBeGreaterThan(0);
    expect(violationsIn(sentences.join("\n"))).toEqual([]);
  });

  it("not-applicable fixture (zero-unit estate): no sentence claims a cause", async () => {
    const sentences = await renderBriefSentences([], notApplicableOverview);
    expect(sentences.length).toBeGreaterThan(0);
    expect(violationsIn(sentences.join("\n"))).toEqual([]);
  });

  it("all-clear fixture: no sentence claims a cause", async () => {
    const devices = [{ ...FAILING_DEVICES[0], inventory_collected_at: daysAgo(0) }];
    const sentences = await renderBriefSentences(devices, allClearOverview);
    expect(sentences.length).toBeGreaterThan(0);
    expect(violationsIn(sentences.join("\n"))).toEqual([]);
  });

  it("malformed or empty read fixture: no sentence claims a cause", async () => {
    const sentences = await renderBriefSentences(FAILING_DEVICES, malformedOverview);
    expect(sentences.length).toBeGreaterThan(0);
    expect(violationsIn(sentences.join("\n"))).toEqual([]);
  });
});
