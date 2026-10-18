import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3Theme } from "../src/theme/m3Theme";
import { packetContract, PacketAnalysisScreen } from "../src/screens/PacketAnalysisScreen";
import { SANITIZER_CONTRACT, SANITIZER_ROLE_REFUSAL, SanitizerScreen } from "../src/screens/SanitizerScreen";
import { ManagementTreePanel } from "../src/screens/ManagementTreePanel";
import { ROLE_REFUSAL } from "../src/shell/actionAccess";
import type { ManagementTree, ManagementTreeNode } from "../src/auth/adminApi";

afterEach(() => vi.unstubAllGlobals());
const themed = (node: React.ReactElement) => render(<ThemeProvider theme={m3Theme}>{node}</ThemeProvider>);
const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));

describe("tool pages lead with a two-sentence contract (review §3, roadmap 3.3)", () => {
  it("Packet Analysis: what it does, then what it never does, with the retention it was told", () => {
    const sentences = packetContract(7).split(/(?<=\.)\s+/);
    expect(sentences).toHaveLength(2);
    expect(sentences[0]).toMatch(/^Analyses uploaded pcap or pcapng captures offline/);
    expect(sentences[1]).toMatch(/^It never reads a device or live traffic/);
    expect(sentences[1]).toContain("deleted 7 days after the case is created");
    expect(packetContract(14)).toContain("14 days");
  });

  it("Data Sanitizer: what it does, then what it never does", () => {
    const sentences = SANITIZER_CONTRACT.split(/(?<=\.)\s+/);
    expect(sentences).toHaveLength(2);
    expect(sentences[0]).toMatch(/^Replaces addresses/);
    expect(sentences[1]).toMatch(/^It never keeps a file/);
  });

  it("Packet Analysis shows the contract, then one input region with the refusal inline and the store limits, then the cases", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string) => url.startsWith("/api/v2/packet-analysis/cases")
      ? json({ cases: [{ case_id: "c1", title: "OBJ-VORTEX-4006", created_at: "2026-10-18T07:00:00Z", expires_at: "2026-10-25T07:00:00Z", state: "FAILED", total_bytes: 10, capture_count: 6, payload_zeroing: false, latest_job_id: null }],
        permissions: { run: false, raw_view: false, masked: true }, limits: { retention_days: 7, max_file_bytes: 2 * 1024 ** 3, max_store_bytes: 100 * 1024 ** 3, used_bytes: 4096 } })
      : json({})));
    themed(<PacketAnalysisScreen />);
    const input = await screen.findByRole("region", { name: "New case" });
    expect(within(input).getByRole("button", { name: "New case" })).toBeDisabled();
    expect(within(input).getByRole("status")).toHaveTextContent(ROLE_REFUSAL);
    expect(within(input).getByText(/Store: 4.0 KB of 100.0 GB used · at most 2.0 GB per capture/)).toBeInTheDocument();
    expect(screen.getByText(packetContract(7))).toBeInTheDocument();
    // Order on the page: contract (header) → input region → history table.
    expect(input.compareDocumentPosition(screen.getByText(packetContract(7))) & Node.DOCUMENT_POSITION_PRECEDING).toBeTruthy();
    expect(screen.getByRole("table", { name: "Packet analysis cases" }).compareDocumentPosition(input) & Node.DOCUMENT_POSITION_PRECEDING).toBeTruthy();
  });

  it("lets the contract wrap under the title instead of running past the viewport (post-release review 2026-10-18, R1)", async () => {
    vi.stubGlobal("fetch", vi.fn(() => json({ permissions: { run: false, revert: false, revert_any_job: false },
      limits: { max_file_bytes: 2 * 1024 ** 3, vault_retention_days: 90, output_ttl_minutes: 60, vault_available: true } })));
    themed(<SanitizerScreen />);
    const subtitle = await screen.findByText(SANITIZER_CONTRACT);
    expect(getComputedStyle(subtitle).whiteSpace).toBe("normal");
    const block = screen.getByTestId("screen-header-title");
    const style = getComputedStyle(block);
    expect(style.minWidth).toMatch(/^0(px)?$/);
    expect(style.maxWidth).toBe("880px");
    expect(style.flexShrink).toBe("1");
  });

  it("Data Sanitizer keeps the limits line inside the input region, under the locked drop zone", async () => {
    vi.stubGlobal("fetch", vi.fn(() => json({ permissions: { run: false, revert: false, revert_any_job: false },
      limits: { max_file_bytes: 2 * 1024 ** 3, vault_retention_days: 90, output_ttl_minutes: 60, vault_available: true } })));
    themed(<SanitizerScreen />);
    const input = await screen.findByRole("region", { name: "Sanitizer input" });
    // The refusal names the roles (review 3.3): the drop zone says who may sanitize and who may revert.
    expect(within(input).getByRole("status")).toHaveTextContent(SANITIZER_ROLE_REFUSAL);
    expect(SANITIZER_ROLE_REFUSAL).toBe("Your role does not allow this action · sanitize: Operator, Operation admin, Security admin · revert: Security admin (Operator and Operation admin on their own jobs)");
    expect(within(input).queryByText(ROLE_REFUSAL)).toBeNull();
    expect(within(input).getByText(/At most 2.0 GB per file/)).toBeInTheDocument();
    expect(screen.getByText(SANITIZER_CONTRACT)).toBeInTheDocument();
  });
});

describe("Panorama managed estate: n virtual systems › (P2-5B, roadmap 3.4)", () => {
  const base = {
    device_id: null, imported: false, ha_role: null, enrollment_state: null, last_collection_state: null, last_collection_at: null,
    model: null, candidate_id: null, ack_token: null, acknowledged: false, acknowledged_reason: null, children: [],
  } as const;
  const vsys = (name: string): ManagementTreeNode => ({ ...base, kind: "PALO_ALTO_VIRTUAL_SYSTEM", category: "virtual_system", virtual_system: name });
  const tree: ManagementTree = {
    device_id: "pano-1", vendor: "palo_alto", run_id: "run-1", discovered_at: "2026-10-18T03:00:00Z",
    counts: { domains: 0, clusters: 1, gateways: 2, virtual_systems: 7, imported: 2, management_appliances: 0, not_in_nexus: 0, acknowledged: 0 },
    domains: [{ domain: null, nodes: [
      { ...base, kind: "PALO_ALTO_HA_PAIR_CLUSTER", category: "cluster", cluster_member_ref: "CLS-HOTEL-04", children: [
        { ...base, kind: "PALO_ALTO_DEVICE", category: "device", hostname: "FW-HOTEL-04-M1", device_id: "d1", imported: true, ha_role: "PASSIVE",
          enrollment_state: "ENROLLED", last_collection_state: "COMPLETED", last_collection_at: "2026-10-18T03:00:00Z",
          children: ["VS-HOTEL-04-44", "VS-HOTEL-04-01", "VS-HOTEL-04-19", "VS-HOTEL-04-23", "VS-HOTEL-04-61", "VS-HOTEL-04-70", "VS-HOTEL-04-88"].map(vsys) },
      ] },
    ] }],
  };

  it("shows one disclosure line per gateway and opens every name on demand", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(tree))));
    render(<ManagementTreePanel deviceId="pano-1" onOpenDevice={vi.fn()} onOpenCluster={vi.fn()} />);
    const toggle = await screen.findByRole("button", { name: "7 virtual systems in FW-HOTEL-04-M1" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByText("VS-HOTEL-04-88")).toBeNull();
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    const list = screen.getByRole("list", { name: "Virtual systems of FW-HOTEL-04-M1" });
    expect(within(list).getAllByRole("listitem").map((li) => li.textContent)).toEqual(
      ["VS-HOTEL-04-44", "VS-HOTEL-04-01", "VS-HOTEL-04-19", "VS-HOTEL-04-23", "VS-HOTEL-04-61", "VS-HOTEL-04-70", "VS-HOTEL-04-88"]);
    fireEvent.click(toggle);
    await waitFor(() => expect(screen.queryByText("VS-HOTEL-04-88")).toBeNull());
  });
});
