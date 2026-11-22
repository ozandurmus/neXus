// Estate Cases, Reports and System: evidence-stamp, UNKNOWN-never-OK and count-wording guards.
// Test-only lane. Mirrors the guard structure of tests/EstateEvidenceGuards.test.tsx (A, B, C) for the three
// screens that the lens guard does not yet cover. Synthetic fixtures only (FW-TANGO-04 style names, 192.0.2.x).
import { render, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  getReportCatalogue,
  getScheduledJobs,
  getSystemHealth,
  listAuditEvents,
  listDevices,
  listLocalIdentities,
  listPacketCases,
  listReportRuns,
  listSessions,
  listSupportBundles,
  type ReportCatalogue,
  type ReportRunView,
} from "../src/auth/adminApi";
import { EstateCasesScreen } from "../src/estate/EstateCasesScreen";
import { EstateReportsScreen } from "../src/estate/EstateReportsScreen";
import { EstateSystemScreen } from "../src/estate/EstateSystemScreen";

vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return {
    ...actual,
    getReportCatalogue: vi.fn(),
    listReportRuns: vi.fn(),
    listPacketCases: vi.fn(),
    listSupportBundles: vi.fn(),
    getScheduledJobs: vi.fn(),
    getSystemHealth: vi.fn(),
    listAuditEvents: vi.fn(),
    listDevices: vi.fn(),
    listLocalIdentities: vi.fn(),
    listSessions: vi.fn(),
  };
});

// Count wording: a figure must be followed by a word saying what it counts (e.g. "3 cases", "2 sessions").
const COUNT_RE = /\d+\s*%|\b\d+\s+[A-Za-z]/;
// A bare number on its own (a figure with no unit) is never allowed as a count label.
const BARE_NUMBER_RE = /^\d+$/;

const AT_OLD = new Date(Date.now() - 10 * 3_600_000).toISOString();
const AT_NEW = new Date(Date.now() - 2 * 3_600_000).toISOString();
const FUTURE = new Date(Date.now() + 86_400_000).toISOString();

const CASES = [
  {
    case_id: "case-tango-001",
    title: "FW-TANGO-04 capture",
    created_at: AT_NEW,
    expires_at: FUTURE,
    state: "ANALYZED",
    total_bytes: 4096,
    capture_count: 1,
    payload_zeroing: false,
    latest_job_id: null,
  },
  {
    case_id: "case-sierra-002",
    title: "FW-SIERRA-01 capture",
    created_at: AT_OLD,
    expires_at: FUTURE,
    state: "ANALYZED",
    total_bytes: 8192,
    capture_count: 2,
    payload_zeroing: false,
    latest_job_id: null,
  },
];

const BUNDLE = {
  id: "bundle-tango-1",
  device_id: "dev-tango-04",
  device_pseudonym: "FW-TANGO-04",
  vendor: "check_point",
  run_mode: "now",
  run_at: AT_NEW,
  requested_at: AT_NEW,
  finished_at: null,
  expires_at: FUTURE,
  expiry_warning: false,
  downloaded_at: null,
  download_count: 0,
  state: "COMPLETED",
  outcome: null,
  items: [],
  case_ref: "case-tango-001",
};

const TEMPLATES: ReportCatalogue = {
  templates: [
    {
      template_id: "rpt-compliance",
      title: "Compliance pack",
      audience: "Auditor",
      question: "Which controls fail on FW-TANGO-04?",
      formats: ["pdf"],
      framework_parameter: false,
      missing_reads: [],
    },
    {
      template_id: "rpt-backup",
      title: "Backup pack",
      audience: "Operator",
      question: "Which devices lack a backup?",
      formats: ["csv"],
      framework_parameter: false,
      missing_reads: ["backups"],
    },
  ],
  frameworks: [],
  permissions: { masked: true, raw_view: false },
  retention_days: 7,
} as unknown as ReportCatalogue;

// One run whose evidence planes are empty: its evidence age is UNKNOWN, not fresh.
const RUNS: ReportRunView[] = [
  {
    run_id: "run-synth-001",
    template_id: "rpt-compliance",
    title: "Compliance pack",
    state: "SUCCEEDED",
    projection: "masked",
    format: "pdf",
    parameters: {},
    data_as_of: { planes: [] },
    plaintext_bytes: 2048,
    error_code: null,
    requested_at: AT_NEW,
    finished_at: AT_NEW,
    expires_at: FUTURE,
    downloadable: true,
    mine: true,
  } as unknown as ReportRunView,
];

const identity = (name: string, enabled: boolean) => ({
  local_identity_id: `id-${name}`,
  local_identity_name: name,
  enabled,
  must_change_password: false,
  created_at: AT_NEW,
  password_set_at: AT_NEW,
});

const session = (id: string) => ({
  session_id: id,
  actor_fingerprint: "fp-synthetic",
  created_at: AT_NEW,
  last_seen_at: AT_NEW,
  idle_deadline_at: AT_NEW,
  absolute_expires_at: AT_NEW,
  state: "ACTIVE",
  end_reason: null,
  ended_by_actor_fingerprint: null,
  superseded_by_session_id: null,
});

function mockCasesReads() {
  vi.mocked(listPacketCases).mockResolvedValue({
    cases: CASES,
    permissions: {},
    limits: { max_file_bytes: 1, max_store_bytes: 1, used_bytes: 0, retention_days: 7 },
  } as never);
  // Support-bundle read fails: the bundle link must read UNKNOWN on every case.
  vi.mocked(listSupportBundles).mockRejectedValue(new Error("synthetic"));
}

function mockReportsReads() {
  vi.mocked(getReportCatalogue).mockResolvedValue(TEMPLATES);
  vi.mocked(listReportRuns).mockResolvedValue({ runs: RUNS } as never);
}

function mockSystemReads() {
  vi.mocked(getScheduledJobs).mockResolvedValue({
    master_enabled: true, master_env: "SYNTHETIC_MASTER", can_write: false, masked: true, business_hours: null, families: [],
  } as never);
  vi.mocked(listDevices).mockResolvedValue({ devices: [] } as never);
  // Health read fails: the platform-health section must read UNKNOWN, never OK.
  vi.mocked(getSystemHealth).mockRejectedValue(new Error("synthetic"));
  vi.mocked(listAuditEvents).mockResolvedValue({ events: [] } as never);
  vi.mocked(listLocalIdentities).mockResolvedValue({
    identities: [identity("FW-TANGO-04-admin", true), identity("FW-TANGO-04-ops", false)],
  } as never);
  vi.mocked(listSessions).mockResolvedValue({
    sessions: [session("SES-SYNTH-0001")], identity_labels: {}, idle_timeout_seconds: 900, absolute_lifetime_seconds: 3600,
  } as never);
}

/** The unit row of an UNKNOWN mark: nearest ancestor holding exactly one state badge, else its parent. */
function rowScopeOf(mark: Element, root: Element): HTMLElement {
  let row: Element | null = mark.parentElement;
  while (row && row !== root && row.querySelectorAll("[data-state-badge]").length !== 1) row = row.parentElement;
  return (row && row !== root ? row : (mark.parentElement ?? root)) as HTMLElement;
}

/**
 * UNKNOWN marks: a state badge reading UNKNOWN, or any element carrying an UNKNOWN text node
 * (e.g. an evidence-age cell whose age is UNKNOWN is text, not a badge).
 */
function unknownMarks(root: Element): Element[] {
  const marks = new Set<Element>(Array.from(root.querySelectorAll('[data-state-badge="unk"]')));
  for (const el of Array.from(root.querySelectorAll("*"))) {
    if (Array.from(el.childNodes).some((n) => n.nodeType === Node.TEXT_NODE && (n.textContent ?? "").trim() === "UNKNOWN")) {
      marks.add(el);
    }
  }
  return Array.from(marks);
}

/** Text elements whose whole text is a bare number (no unit word). */
function bareNumbers(root: HTMLElement): string[] {
  return Array.from(root.querySelectorAll("*"))
    .filter((el) => el.children.length === 0 && BARE_NUMBER_RE.test((el.textContent ?? "").trim()))
    .map((el) => el.textContent ?? "");
}

type ScreenCase = {
  readonly screen: string;
  readonly render: () => ReturnType<typeof render>;
  readonly mockReads: () => void;
  /** Count text the screen must print (guard C). */
  readonly expectedCountText: RegExp;
  /**
   * KNOWN OPEN FINDINGS. Each listed guard is registered with it.fails. A guard is flipped to plain `it` once its
   * screen passes; the flipped entries were the Cases, Reports and System stamp and unit gaps (guard A and C).
   * Keys: "A", "B", "C".
   */
  readonly knownOpen: Partial<Record<"A" | "B" | "C", string>>;
};

const SCREEN_CASES: readonly ScreenCase[] = [
  {
    screen: "Cases",
    render: () => render(<EstateCasesScreen />),
    mockReads: mockCasesReads,
    expectedCountText: /2 cases listed/,
    // Captures and support-bundle figures per case card, and the counts line, carry the packet read's own time.
    knownOpen: {},
  },
  {
    screen: "Reports",
    render: () => render(<EstateReportsScreen />),
    mockReads: mockReportsReads,
    expectedCountText: /2 templates listed/,
    // "N templates listed" and "N report runs listed" carry the report reads' own time.
    knownOpen: {},
  },
  {
    screen: "System",
    render: () => render(<EstateSystemScreen />),
    mockReads: mockSystemReads,
    expectedCountText: /2 local identities/,
    // Access counts carry the identity and session reads' own time; every dl value names its unit.
    knownOpen: {},
  },
];

describe("Estate Cases, Reports and System stamp guards", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  for (const c of SCREEN_CASES) {
    const guardA = c.knownOpen.A ? it.fails : it;
    const guardB = c.knownOpen.B ? it.fails : it;
    const guardC = c.knownOpen.C ? it.fails : it;

    guardA(`${c.screen}: every counted figure carries an evidence stamp (guard A)`, async () => {
      c.mockReads();
      const { container } = c.render();
      await waitFor(() => expect(container.querySelector("[data-evidence-stamp]")).not.toBeNull());

      // Each count-bearing card must hold its own stamp, not only one stamp elsewhere on the screen.
      const cards: Element[] = c.screen === "System"
        ? Array.from(container.querySelectorAll("dl")).map((dl) => dl.parentElement as Element)
        : c.screen === "Cases"
          ? Array.from(container.querySelectorAll('[data-testid="case-row"]'))
          : Array.from(container.querySelectorAll("h2")).map((h) => h.parentElement?.parentElement ?? h);
      const counted = cards.filter((card) => COUNT_RE.test(card.textContent ?? ""));
      expect(counted.length, `${c.screen}: no counted cards found to guard`).toBeGreaterThan(0);
      for (const card of counted) {
        const heading = card.querySelector("h1, h2")?.textContent ?? "(untitled card)";
        expect(
          card.querySelector("[data-evidence-stamp]"),
          `${c.screen}: card "${heading}" shows a figure without an EvidenceStamp`,
        ).not.toBeNull();
      }
    });

    it(`${c.screen}: the UNKNOWN text is visible when the fixture has an UNKNOWN read (guard B, presence)`, async () => {
      c.mockReads();
      const { container } = c.render();
      await waitFor(() => expect(unknownMarks(container).length).toBeGreaterThan(0));
      expect(container.textContent ?? "", `${c.screen}: UNKNOWN text missing`).toMatch(/UNKNOWN/);
    });

    guardB(`${c.screen}: no OK/Fine on a row holding an UNKNOWN mark (guard B)`, async () => {
      c.mockReads();
      const { container } = c.render();
      await waitFor(() => expect(unknownMarks(container).length).toBeGreaterThan(0));
      for (const mark of unknownMarks(container)) {
        const scope = rowScopeOf(mark, container);
        expect(scope.querySelector('[data-state-badge="ok"]'), `${c.screen}: state badge OK on a row holding an UNKNOWN mark`).toBeNull();
        expect(within(scope).queryAllByText(/^(OK|Fine)$/), `${c.screen}: OK/Fine on a row holding an UNKNOWN mark`).toHaveLength(0);
      }
    });

    guardC(`${c.screen}: the count text says what it counts (guard C)`, async () => {
      c.mockReads();
      const { container } = c.render();
      await waitFor(() => expect(container.textContent ?? "").toMatch(c.expectedCountText));
      expect(bareNumbers(container), `${c.screen}: bare number without a unit word`).toHaveLength(0);
    });
  }
});
