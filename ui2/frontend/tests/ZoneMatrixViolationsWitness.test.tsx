import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ZoneMatrixScreen } from "../src/screens/zonematrix/ZoneMatrixScreen";
import { WITNESS_AUDIT_NOTICE } from "../src/screens/zonematrix/ViolationsTab";
import type { SummaryFinding, ZoneMatrixSummaryResponse } from "../src/screens/zonematrix/zoneMatrixApi";

/**
 * Zone matrix step 4b-2, Violations tab "Show witness" (contract 7.1, 8.1, 3.4; ZM-33): offered only when the server says
 * the session may view blocks, read only on a click (one audited read per click, never prefetched), shown with the audit
 * notice, and every refusal or failure has its own honest state. Synthetic values only.
 */
const HASH = "a".repeat(64);
const SOURCE_RANGE = "198.51.100.16-198.51.100.31";
const DESTINATION_RANGE = "198.51.100.200";
const NO_BLOCKS = { view_blocks: false, edit: false, publish: false };
const BLOCKS = { view_blocks: true, edit: true, publish: true };
const activeSummary = { version_id: "v-active", number: 3, state: "ACTIVE", revision: 4, content_sha256: HASH,
  created_at: "2026-11-10T08:00:00Z", updated_at: "2026-11-12T08:00:00Z", published_by: "USER-2", published_at: "2026-11-12T08:00:00Z" };
const activeDetail = { ...activeSummary, zones: [], unassigned_addresses: { IPV4: "0", IPV6: "0" }, redundant_blocks: 0, revisions: [] };

const finding = (over: Partial<SummaryFinding> = {}): SummaryFinding => ({
  policy_source_id: "pol-1", policy_name: "POLICY-ALFA", rule_uid: "uid-7", inline_parent_path: [], rule_id: "rule-7", rule_number: 7,
  rule_name: "RULE-ECHO", target_device_ids: [], outcome: "VIOLATION", reason: null, highest_severity: "HIGH", flags: [],
  covering_rule_id: null, undecided_parts: [],
  violated_pairs: [{ source_zone_id: "z1", destination_zone_id: "z2", source_name: "ZONE-ALFA", destination_name: "ZONE-BRAVO",
    statement: "DENY_ALL", severity: "HIGH", not_allowed_services: [] }],
  first_observed_at: "2026-11-20T08:00:00Z", first_observed_any_version: "2026-11-18T08:00:00Z", last_observed_at: "2026-11-25T07:05:00Z",
  finding_id: "4711", run_id: "run-1", version_id: "v-active", version_number: 3, content_sha256: HASH, on_active_version: true,
  policy_staleness: "CURRENT", snapshot_age_seconds: 5400, ...over,
});

const summary = (over: Partial<ZoneMatrixSummaryResponse> = {}): ZoneMatrixSummaryResponse => ({
  can: BLOCKS,
  evaluated_at: "2026-11-25T08:00:00Z", state: "READY", state_text: "findings of the latest complete run of each policy",
  masked: false, policy_names_masked: false, witnesses_included: false,
  staleness_threshold: { seconds: 86400, hours: 24, interim: true, source: "PO 2026-11-23", label: "interim" },
  active_version: { version_id: "v-active", version_number: 3, content_sha256: HASH, published_at: "2026-11-12T08:00:00Z" },
  headline: null, headline_absent_reason: "no headline in this test", policies: [], not_evaluated: [],
  filters: { policy_id: null, outcome: null, limit: 200 }, unfiltered_findings: 2, total_findings: 2, returned: 2, more: 0, capped: false,
  findings: [finding(), finding({ finding_id: "4712", rule_uid: "uid-8", rule_id: "rule-8", rule_number: 8, rule_name: "RULE-FOXTROT" })],
  resolved_by_version_change: { window_days: 7, total_last_7_days: 0, returned: 0, more: 0, capped: false, last_7_days: [], by_version: [] },
  ...over,
});

const witnessBody = (findingId: string) => ({
  finding_id: findingId, run_id: "run-1", version_id: "v-active", version_number: 3, nat_view: "ORIGINAL",
  witnesses_included: true, audited: true, cells_total: 2, witnesses_stored: 1, can: BLOCKS,
  cells: [
    { source_zone_id: "z1", destination_zone_id: "z2", nat_view: "ORIGINAL",
      witness: { source_range: SOURCE_RANGE, destination_range: DESTINATION_RANGE, protocol: "tcp", port: "22", service: "tcp/22" } },
    { source_zone_id: "z2", destination_zone_id: "z1", nat_view: "UNKNOWN", witness: null },
  ],
});

interface Witnessed { status: number; body: unknown }
interface Served { readonly gets: string[]; witness: (findingId: string) => Witnessed }

function serve(body: ZoneMatrixSummaryResponse, witness: (findingId: string) => Witnessed = (id) => ({ status: 200, body: witnessBody(id) })): Served {
  const served: Served = { gets: [], witness };
  const reply = (status: number, value: unknown) => new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json" } });
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url === "/session/status") return reply(200, { csrf_token: "synthetic-token" });
    served.gets.push(url);
    const path = url.split("?")[0];
    const match = /^\/api\/v2\/zone-matrix\/findings\/([^/]+)\/witness$/.exec(path);
    if (match) {
      const answer = served.witness(decodeURIComponent(match[1]));
      return reply(answer.status, answer.body);
    }
    if (path === "/api/v2/zone-matrix/summary") return reply(200, body);
    if (path === "/api/v2/zone-matrix/draft") return reply(200, { active: activeSummary, draft: null, diff: null, publish_check: null, can: NO_BLOCKS });
    if (path === "/api/v2/zone-matrix/versions/v-active") return reply(200, { ...activeDetail, can: NO_BLOCKS });
    if (path === "/api/v2/zone-matrix/versions") return reply(200, { versions: [activeSummary], can: NO_BLOCKS });
    return reply(404, { error: "NOT_FOUND", reason: "no such route in this test" });
  }));
  return served;
}

const witnessGets = (s: Served) => s.gets.filter((u) => u.includes("/witness"));

const openViolations = async () => {
  render(<ZoneMatrixScreen />);
  fireEvent.click(await screen.findByRole("tab", { name: "Violations" }));
  return screen.findByRole("table", { name: "Violation findings" });
};

const rowOf = (table: HTMLElement, rule: RegExp) => within(table).getByText(rule).closest("tr")!;

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); window.history.replaceState(null, "", "/"); });

describe("Zone matrix Violations: Show witness", () => {
  it("offers no witness control without the right, nor for a masked session, and never reads a witness", async () => {
    for (const body of [summary({ can: NO_BLOCKS }), summary({ masked: true }), summary({ can: undefined })]) {
      const s = serve(body);
      const table = await openViolations();
      expect(within(table).queryByRole("button", { name: /Show witness/ })).toBeNull();
      expect(within(table).queryByText("Witness")).toBeNull();
      expect(witnessGets(s)).toEqual([]);
      document.body.innerHTML = "";
      vi.unstubAllGlobals();
    }
  });

  it("reads nothing before the click, then makes one audited read per click and shows the stored ranges with the notice", async () => {
    const s = serve(summary());
    const table = await openViolations();
    const row = rowOf(table, /RULE-ECHO/);
    expect(witnessGets(s)).toEqual([]);
    expect(document.body.textContent).not.toContain("198.51.100.");
    expect(screen.queryByText(WITNESS_AUDIT_NOTICE)).toBeNull();

    fireEvent.click(within(row).getByRole("button", { name: "Show witness" }));
    const shown = await within(row).findByTestId("witness-shown");
    expect(witnessGets(s)).toEqual(["/api/v2/zone-matrix/findings/4711/witness"]);
    expect(within(shown).getByTestId("witness-audit-notice")).toHaveTextContent(WITNESS_AUDIT_NOTICE);
    const tuples = within(shown).getAllByTestId("witness-tuple");
    expect(tuples).toHaveLength(1);
    // The range is shown as stored, never narrowed to one address.
    expect(tuples[0]).toHaveTextContent(`source ${SOURCE_RANGE} -> destination ${DESTINATION_RANGE} tcp/22`);
    const cells = within(shown).getAllByTestId("witness-cell");
    expect(cells[0]).toHaveTextContent("ZONE-ALFA -> ZONE-BRAVO · NAT view ORIGINAL");
    expect(cells[1]).toHaveTextContent("UNKNOWN");
    // The other row is untouched: no read, no witness text.
    const other = rowOf(table, /RULE-FOXTROT/);
    expect(within(other).queryByTestId("witness-shown")).toBeNull();
    expect(other.textContent).not.toContain("198.51.100.");

    // Hiding drops the witness; showing again is a second audited read, never a cached copy.
    fireEvent.click(within(row).getByRole("button", { name: "Hide witness" }));
    expect(row.textContent).not.toContain("198.51.100.");
    fireEvent.click(within(row).getByRole("button", { name: "Show witness" }));
    await within(row).findByTestId("witness-shown");
    expect(witnessGets(s)).toEqual(["/api/v2/zone-matrix/findings/4711/witness", "/api/v2/zone-matrix/findings/4711/witness"]);
  });

  it("shows the restricted panel on a 403 and no witness text", async () => {
    const s = serve(summary(), () => ({ status: 403, body: { error: "ZONE_BLOCKS_NOT_PERMITTED", reason: "viewing witness addresses needs zone_matrix_zones_read" } }));
    const table = await openViolations();
    const row = rowOf(table, /RULE-ECHO/);
    fireEvent.click(within(row).getByRole("button", { name: /Show witness/ }));
    const restricted = await within(row).findByTestId("witness-restricted");
    expect(restricted).toHaveTextContent("Witness addresses");
    expect(restricted).toHaveTextContent("This account can not view or change it.");
    expect(row.textContent).not.toContain("198.51.100.");
    expect(witnessGets(s)).toHaveLength(1);
  });

  it("shows an error with Retry when the read fails, and Retry reads again", async () => {
    let calls = 0;
    const s = serve(summary(), (id) => (++calls === 1
      ? { status: 503, body: { error: "ZONE_MATRIX_AUDIT_UNAVAILABLE", reason: "the view was refused because its audit row could not be written" } }
      : { status: 200, body: witnessBody(id) }));
    const table = await openViolations();
    const row = rowOf(table, /RULE-ECHO/);
    fireEvent.click(within(row).getByRole("button", { name: /Show witness/ }));
    const error = await within(row).findByTestId("witness-error");
    expect(error).toHaveTextContent("The view was refused because its audit row could not be written.");
    expect(row.textContent).not.toContain("198.51.100.");
    fireEvent.click(within(error).getByRole("button", { name: "Retry" }));
    await within(row).findByTestId("witness-shown");
    expect(witnessGets(s)).toHaveLength(2);
  });

  it("says UNKNOWN, never an empty box, when the finding stored no witness", async () => {
    serve(summary(), () => ({ status: 409, body: { error: "WITNESS_NOT_STORED", reason: "this finding stored no witness tuple", finding_id: "4711", cells_total: 1 } }));
    const table = await openViolations();
    const row = rowOf(table, /RULE-ECHO/);
    fireEvent.click(within(row).getByRole("button", { name: /Show witness/ }));
    const unknown = await within(row).findByTestId("witness-unknown");
    expect(unknown).toHaveTextContent("Witness UNKNOWN");
    expect(unknown).toHaveTextContent("it is not an empty witness");
    await waitFor(() => expect(within(row).queryByTestId("witness-tuple")).toBeNull());
  });

  it("a finding without an id offers no read and says UNKNOWN", async () => {
    const s = serve(summary({ findings: [finding({ finding_id: null })], total_findings: 1, returned: 1, unfiltered_findings: 1 }));
    const table = await openViolations();
    const row = rowOf(table, /RULE-ECHO/);
    expect(within(row).queryByRole("button", { name: /Show witness/ })).toBeNull();
    expect(within(row).getByTestId("witness-no-id")).toHaveTextContent("UNKNOWN");
    expect(witnessGets(s)).toEqual([]);
  });
});

describe("Zone matrix Violations: Open in Trace", () => {
  const hostWitness = (over: Record<string, string> = {}) => (id: string) => ({ status: 200, body: {
    ...witnessBody(id), cells: [{ source_zone_id: "z1", destination_zone_id: "z2", nat_view: "ORIGINAL",
      witness: { source_range: "198.51.100.17", destination_range: "198.51.100.200", protocol: "tcp", port: "22", service: "tcp/22", ...over } }] } });
  const show = async () => {
    const table = await openViolations();
    const row = rowOf(table, /RULE-ECHO/);
    fireEvent.click(within(row).getByRole("button", { name: "Show witness" }));
    return { row, shown: await within(row).findByTestId("witness-shown") };
  };

  it("links single IPv4 hosts over tcp/udp with a fragment tuple and no query-string addresses", async () => {
    serve(summary(), hostWitness({ protocol: "udp", port: "53" }));
    const { shown } = await show();
    const link = within(shown).getByTestId("witness-trace-link");
    expect(link).toHaveTextContent("Open in Trace");
    expect(link.getAttribute("href")).toBe("?screen=trace&mode=path#tuple=198.51.100.17,198.51.100.200,udp,53");
    expect(link.getAttribute("href")!.split("#")[0]).not.toContain("198.51.100");
    expect(within(shown).getByTestId("witness-tuple")).toBeInTheDocument();
  });

  it("offers no link for ranges, other protocols, any/UNKNOWN or bad ports, only the copyable text", async () => {
    const cases: Array<Record<string, string>> = [
      { source_range: SOURCE_RANGE }, { destination_range: "198.51.100.0/24" }, { protocol: "icmp" }, { protocol: "any" },
      { protocol: "UNKNOWN", port: "UNKNOWN" }, { port: "any" }, { port: "0" }, { port: "65536" }, { source_range: "UNKNOWN" },
    ];
    for (const over of cases) {
      serve(summary(), hostWitness(over));
      const { shown } = await show();
      expect(within(shown).queryByTestId("witness-trace-link")).toBeNull();
      expect(within(shown).getByTestId("witness-tuple")).toBeInTheDocument();
      document.body.innerHTML = "";
      vi.unstubAllGlobals();
    }
  });

  it("never offers the witness, hence no link, to a masked or blocks-less session", async () => {
    for (const body of [summary({ masked: true }), summary({ can: NO_BLOCKS })]) {
      const s = serve(body, hostWitness());
      const table = await openViolations();
      expect(within(table).queryByTestId("witness-trace-link")).toBeNull();
      expect(within(table).queryByRole("button", { name: /Show witness/ })).toBeNull();
      expect(document.body.textContent).not.toContain("198.51.100.17");
      expect(witnessGets(s)).toEqual([]);
      document.body.innerHTML = "";
      vi.unstubAllGlobals();
    }
  });
});
