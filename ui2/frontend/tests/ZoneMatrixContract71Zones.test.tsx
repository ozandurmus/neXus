import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ZoneMatrixScreen } from "../src/screens/zonematrix/ZoneMatrixScreen";
import { REDUNDANT_BLOCKS_CAP } from "../src/screens/zonematrix/ZonesTab";
import type {
  RedundantBlock, ZoneLookupResult, ZoneMatrixCan, ZoneVersionDetail, ZoneVersionSummary, ZoneView,
} from "../src/screens/zonematrix/zoneMatrixApi";

/**
 * Contract 7.1 conformance pins for the Zones tab (docs/design/ZONE_MATRIX_CONTRACT.md sections 7.1, 3.1, 3.2, 3.3, 3.4).
 * Synthetic names and RFC 5737 documentation ranges only.
 */
const HASH_ACTIVE = "a".repeat(64);
const HASH_DRAFT = "b".repeat(64);

const ZONES_READ_CAN: ZoneMatrixCan = { view_blocks: true, edit: true, publish: true, confirm_reference: true };
const NO_ZONES_READ_CAN: ZoneMatrixCan = { view_blocks: false, edit: false, publish: false, confirm_reference: false };

const activeSummary: ZoneVersionSummary = {
  version_id: "v-active", number: 3, state: "ACTIVE", revision: 4, content_sha256: HASH_ACTIVE,
  created_at: "2026-11-10T08:00:00Z", updated_at: "2026-11-12T08:00:00Z", published_by: "USER-2", published_at: "2026-11-12T08:00:00Z",
};

const draftSummary: ZoneVersionSummary = {
  version_id: "v-draft", number: null, state: "DRAFT", revision: 5, content_sha256: HASH_DRAFT,
  created_at: "2026-11-13T08:00:00Z", updated_at: "2026-11-13T09:00:00Z", published_by: null, published_at: null,
};

const makeZone = (over: Partial<ZoneView> = {}): ZoneView => ({
  zone_id: "z-alpha",
  name: "ZONE-ALPHA",
  description: "Synthetic application tier",
  role: "APPLICATION",
  block_count: 2,
  effective_addresses: { IPV4: "256", IPV6: "0" },
  blocks: null,
  ...over,
});

const makeDetail = (summary: ZoneVersionSummary, over: Partial<ZoneVersionDetail> = {}): ZoneVersionDetail => ({
  ...summary,
  zones: [
    makeZone(),
    makeZone({
      zone_id: "z-bravo",
      name: "ZONE-BRAVO",
      description: null,
      role: "DATABASE",
      block_count: 1,
      effective_addresses: { IPV4: "128", IPV6: "79228162514264337593543950336" },
    }),
  ],
  unassigned_addresses: { IPV4: "4294966912", IPV6: "340282287692774301204839013793224261632" },
  redundant_blocks: 0,
  revisions: [],
  ...over,
});

interface ZonesWorld {
  readonly can?: ZoneMatrixCan;
  readonly active?: (withBlocks: boolean) => ZoneVersionDetail;
  readonly draft?: ((withBlocks: boolean) => ZoneVersionDetail) | null;
  readonly lookup?: (address: string, versionId?: string) => { readonly status: number; readonly body: unknown };
  readonly createZone?: (body: unknown) => { readonly status: number; readonly body: unknown };
}

function serveZones(opts: ZonesWorld = {}) {
  const can = opts.can ?? ZONES_READ_CAN;
  const activeBuilder = opts.active ?? ((withBlocks) => makeDetail(activeSummary, {
    zones: [
      makeZone({ blocks: withBlocks ? ["192.0.2.0/24", "192.0.2.128/25"] : null }),
      makeZone({
        zone_id: "z-bravo", name: "ZONE-BRAVO", description: null, role: "DATABASE", block_count: 1,
        effective_addresses: { IPV4: "128", IPV6: "0" }, blocks: withBlocks ? ["198.51.100.0/25"] : null,
      }),
    ],
  }));
  const draftBuilder = opts.draft ?? null;
  const requests: { readonly method: string; readonly url: string; readonly body: unknown }[] = [];
  const reply = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = (init?.method ?? "GET").toUpperCase();
    if (url === "/session/status") return reply(200, { csrf_token: "synthetic-token" });
    const parsedBody = typeof init?.body === "string" ? JSON.parse(init.body) : undefined;
    requests.push({ method, url, body: parsedBody });
    const [path, query = ""] = url.split("?");
    const withBlocks = query.includes("blocks=1");

    if (method === "GET" && path === "/api/v2/zone-matrix/draft") {
      return reply(200, {
        active: activeSummary,
        draft: draftBuilder ? draftBuilder(withBlocks) : null,
        diff: null,
        publish_check: draftBuilder ? { eligible: true, reason: null } : null,
        can,
      });
    }
    if (method === "GET" && path === "/api/v2/zone-matrix/versions/v-active") {
      return reply(200, { ...activeBuilder(withBlocks), can });
    }
    if (method === "GET" && path === "/api/v2/zone-matrix/versions") {
      return reply(200, { versions: draftBuilder ? [draftSummary, activeSummary] : [activeSummary], can });
    }
    if (method === "GET" && path === "/api/v2/zone-matrix/summary") {
      return reply(200, {
        evaluated_at: "2026-11-25T08:00:00Z", state: "NO_COMPLETE_RUN", state_text: "no policy has a complete evaluation run yet",
        masked: false, policy_names_masked: false, witnesses_included: false,
        staleness_threshold: { seconds: 86400, hours: 24, interim: true, source: "PO 2026-11-23", label: "interim" },
        active_version: { version_id: "v-active", version_number: 3, content_sha256: HASH_ACTIVE, published_at: "2026-11-12T08:00:00Z" },
        headline: null, headline_absent_reason: "no complete run", policies: [], not_evaluated: [],
        filters: { policy_id: null, outcome: null, limit: 200 }, unfiltered_findings: 0, total_findings: 0, returned: 0, more: 0, capped: false,
        findings: [], resolved_by_version_change: { window_days: 7, total_last_7_days: 0, returned: 0, more: 0, capped: false, last_7_days: [], by_version: [] },
      });
    }
    if (method === "POST" && path === "/api/v2/zone-matrix/lookup" && opts.lookup) {
      const body = parsedBody as { address: string; version_id?: string };
      const res = opts.lookup(body.address, body.version_id);
      return reply(res.status, res.body);
    }
    if (method === "POST" && path === "/api/v2/zone-matrix/draft/zones" && opts.createZone) {
      const res = opts.createZone(parsedBody);
      return reply(res.status, res.body);
    }
    return reply(404, { error: "NOT_FOUND", reason: "no such route in this test" });
  }));
  return requests;
}

const openZonesTab = async () => {
  render(<ZoneMatrixScreen />);
  fireEvent.click(await screen.findByRole("tab", { name: "Zones" }));
  return screen.findByRole("table", { name: "Zones" });
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/");
});

describe("Contract 7.1 Zones tab conformance", () => {
  it("zones: lists zones with role, description, explicit 'Block count' header, UNASSIGNED space, and UNKNOWN on invalid counts", async () => {
    serveZones({
      can: NO_ZONES_READ_CAN,
      active: () => makeDetail(activeSummary, {
        zones: [
          makeZone(),
          makeZone({ zone_id: "z-corrupt", name: "ZONE-CHARLIE", description: null, role: null, block_count: -1 }),
        ],
      }),
    });
    const table = await openZonesTab();

    expect(within(table).getByRole("columnheader", { name: "Block count" })).toBeInTheDocument();
    const alphaRow = within(table).getByText("ZONE-ALPHA").closest("tr")!;
    expect(within(alphaRow).getByText("Synthetic application tier")).toBeInTheDocument();
    expect(within(alphaRow).getByText("Application")).toBeInTheDocument();
    expect(within(alphaRow).getByText("2")).toBeInTheDocument();

    const corruptRow = within(table).getByText("ZONE-CHARLIE").closest("tr")!;
    expect(within(corruptRow).getByText("None")).toBeInTheDocument();
    expect(within(corruptRow).getByText("UNKNOWN")).toBeInTheDocument();

    expect(screen.getByTestId("unassigned-space")).toHaveTextContent("Unassigned");
  });

  it("effective sizes: renders IPv4 and IPv6 effective address counts per zone and UNASSIGNED, and UNKNOWN when missing", async () => {
    serveZones({
      can: NO_ZONES_READ_CAN,
      active: () => makeDetail(activeSummary, {
        zones: [
          makeZone({ effective_addresses: { IPV4: "256", IPV6: "79228162514264337593543950336" } }),
          makeZone({ zone_id: "z-missing", name: "ZONE-DELTA", effective_addresses: { IPV4: "", IPV6: "bad" } }),
        ],
        unassigned_addresses: { IPV4: "4294966912", IPV6: "" },
      }),
    });
    const table = await openZonesTab();

    const alphaRow = within(table).getByText("ZONE-ALPHA").closest("tr")!;
    expect(within(alphaRow).getByText("256")).toBeInTheDocument();
    expect(within(alphaRow).getByText("about 7.92 x 10^28")).toBeInTheDocument();

    const missingRow = within(table).getByText("ZONE-DELTA").closest("tr")!;
    expect(within(missingRow).getAllByText("UNKNOWN")).toHaveLength(2);

    const unassigned = screen.getByTestId("unassigned-space");
    expect(unassigned).toHaveTextContent("IPv4 4,294,966,912");
    expect(unassigned).toHaveTextContent("IPv6 UNKNOWN");
  });

  it("overlap warnings: hides at 0, shows count without blocks, lists overlapping CIDRs with '+N more' when blocks are shown, and shows UNKNOWN on null", async () => {
    // 1. Zero overlaps -> no warning panel.
    serveZones({ can: NO_ZONES_READ_CAN, active: () => makeDetail(activeSummary, { redundant_blocks: 0 }) });
    await openZonesTab();
    expect(screen.queryByTestId("redundant-blocks")).not.toBeInTheDocument();
    document.body.innerHTML = "";
    vi.unstubAllGlobals();

    // 2. Null / unreadable redundant_blocks -> explicit UNKNOWN, never hidden as OK.
    serveZones({
      can: NO_ZONES_READ_CAN,
      active: () => makeDetail(activeSummary, { redundant_blocks: null as unknown as number }),
    });
    await openZonesTab();
    expect(screen.getByTestId("redundant-blocks")).toHaveTextContent("Overlap warnings: UNKNOWN");
    document.body.innerHTML = "";
    vi.unstubAllGlobals();

    // 3. Capped list (> REDUNDANT_BLOCKS_CAP): count only before Show blocks, then CIDR details + "+N more" after Show blocks.
    const overlaps: readonly RedundantBlock[] = Array.from({ length: REDUNDANT_BLOCKS_CAP + 3 }, (_, i) => ({
      zone: "ZONE-ALPHA",
      block: `192.0.2.${i}/32`,
      within: "192.0.2.0/24",
    }));
    serveZones({
      can: ZONES_READ_CAN,
      active: (withBlocks) => makeDetail(activeSummary, {
        zones: [makeZone({ blocks: withBlocks ? ["192.0.2.0/24", "192.0.2.0/32"] : null })],
        redundant_blocks: withBlocks ? overlaps : overlaps.length,
      }),
    });
    await openZonesTab();

    const countOnly = screen.getByTestId("redundant-blocks");
    expect(countOnly).toHaveTextContent(`Overlap warning: ${REDUNDANT_BLOCKS_CAP + 3} blocks repeat or sit inside another block of the same zone.`);
    expect(within(countOnly).queryByRole("list", { name: "Overlapping blocks" })).not.toBeInTheDocument();
    expect(screen.queryByText("192.0.2.0/32")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Show blocks" }));
    const list = await screen.findByRole("list", { name: "Overlapping blocks" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(REDUNDANT_BLOCKS_CAP);
    expect(within(list).getByText("192.0.2.0/32")).toBeInTheDocument();
    expect(screen.getByTestId("redundant-blocks-more")).toHaveTextContent("+3 more overlapping blocks not shown.");
  });

  it("blocks only with zone_matrix_zones_read: omits block controls and raw CIDR lists completely without the right, and audits on Show blocks with it", async () => {
    // Even if a synthetic payload mistakenly carries raw blocks while can.view_blocks is false, nothing gated is shown or disabled.
    const requests = serveZones({
      can: NO_ZONES_READ_CAN,
      active: () => makeDetail(activeSummary, {
        zones: [makeZone({ name: "ZONE-1", blocks: ["192.0.2.0/24"] })],
        redundant_blocks: [{ zone: "ZONE-1", block: "192.0.2.1/32", within: "192.0.2.0/24" }],
      }),
    });
    const { container } = render(<ZoneMatrixScreen />);
    fireEvent.click(await screen.findByRole("tab", { name: "Zones" }));
    await screen.findByRole("table", { name: "Zones" });

    expect(screen.queryByRole("button", { name: /Show blocks/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Hide blocks/i })).not.toBeInTheDocument();
    expect(screen.queryByText("192.0.2.0/24")).not.toBeInTheDocument();
    expect(screen.queryByText("192.0.2.1/32")).not.toBeInTheDocument();
    expect(requests.some((r) => r.url.includes("blocks=1"))).toBe(false);
    expect(container.textContent ?? "").not.toMatch(/compliant|bddk|\bpass(ed)?\b|score|%/i);
  });

  it("address lookup only with zone_matrix_zones_read: omits lookup without the right, clears stale lookup on version switch, and guards unknown family/version", async () => {
    serveZones({ can: NO_ZONES_READ_CAN });
    await openZonesTab();
    expect(screen.queryByRole("form", { name: "Address lookup" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Address")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Look up" })).not.toBeInTheDocument();
    document.body.innerHTML = "";
    vi.unstubAllGlobals();

    const requests = serveZones({
      can: ZONES_READ_CAN,
      draft: (withBlocks) => makeDetail(draftSummary, {
        zones: [makeZone({ blocks: withBlocks ? ["198.51.100.0/24"] : null })],
      }),
      lookup: (_addr, versionId) => ({
        status: 200,
        body: {
          version_id: versionId ?? "v-active",
          number: null,
          state: versionId === "v-draft" ? "DRAFT" : "ACTIVE",
          family: (versionId === "v-draft" ? "IPV4" : "UNRECOGNISED") as ZoneLookupResult["family"],
          zone_id: "z-alpha",
          zone_name: "ZONE-ALPHA",
        },
      }),
    });
    await openZonesTab();

    fireEvent.change(screen.getByLabelText("Address"), { target: { value: "198.51.100.42" } });
    fireEvent.click(screen.getByRole("button", { name: "Look up" }));
    expect(await screen.findByTestId("lookup-result")).toHaveTextContent("198.51.100.42 is in ZONE-ALPHA.");
    expect(screen.getByTestId("lookup-result")).toHaveTextContent("IPv4, draft, not published");
    expect(requests.find((r) => r.url === "/api/v2/zone-matrix/lookup")?.body).toEqual({
      address: "198.51.100.42",
      version_id: "v-draft",
    });

    // Switching to Active version resets the draft lookup result instead of leaving stale draft output under version 3.
    fireEvent.click(screen.getByRole("button", { name: "Active version" }));
    expect(screen.queryByTestId("lookup-result")).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Address"), { target: { value: "192.0.2.9" } });
    fireEvent.click(screen.getByRole("button", { name: "Look up" }));
    expect(await screen.findByTestId("lookup-result")).toHaveTextContent("family UNKNOWN, version UNKNOWN");
  });

  it("cidr decomposition (3.1): 192.0.2.7/24 shows no decomposition preview and leaves Save to draft enabled", async () => {
    const requests = serveZones({
      can: ZONES_READ_CAN,
      createZone: () => ({
        status: 400,
        body: {
          error: "INVALID_BLOCKS",
          reason: "some blocks are not canonical CIDR blocks",
          errors: [{ index: 0, reason: "host bits are set" }],
        },
      }),
    });
    await openZonesTab();

    fireEvent.click(screen.getByRole("button", { name: "Add zone" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/Name/), { target: { value: "Z-PARTNER" } });
    fireEvent.change(within(dialog).getByLabelText("Blocks"), { target: { value: "192.0.2.7/24" } });

    expect(within(dialog).queryByTestId("cidr-decomposition-preview")).not.toBeInTheDocument();
    const saveButton = within(dialog).getByRole("button", { name: "Save to draft" });
    expect(saveButton).toBeEnabled();

    fireEvent.click(saveButton);
    expect(await within(dialog).findByText("Line 1 (192.0.2.7/24): host bits are set")).toBeInTheDocument();
    expect(requests.find((r) => r.method === "POST" && r.url === "/api/v2/zone-matrix/draft/zones")?.body).toEqual({
      revision: null,
      name: "Z-PARTNER",
      description: null,
      role: null,
      blocks: ["192.0.2.7/24"],
    });
  });

  it("cidr decomposition (3.1): reversed or invalid range shows role='alert' and disables Save to draft", async () => {
    serveZones({ can: ZONES_READ_CAN });
    await openZonesTab();

    fireEvent.click(screen.getByRole("button", { name: "Add zone" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/Name/), { target: { value: "Z-PARTNER" } });
    fireEvent.change(within(dialog).getByLabelText("Blocks"), { target: { value: "192.0.2.20 - 192.0.2.10" } });

    const preview = within(dialog).getByTestId("cidr-decomposition-preview");
    expect(within(preview).getByRole("alert")).toHaveTextContent(/192\.0\.2\.20 - 192\.0\.2\.10/);
    expect(within(preview).getByRole("button", { name: "Apply CIDR decomposition" })).toBeDisabled();
    expect(within(dialog).getByRole("button", { name: "Save to draft" })).toBeDisabled();
  });

  it("cidr decomposition (3.1, 7.1): previews IPv4 + IPv6 ranges, blocks Save until applied, expands in place, and submits exact canonical CIDRs", async () => {
    const requests = serveZones({
      can: ZONES_READ_CAN,
      draft: (withBlocks) => makeDetail(draftSummary, {
        zones: [makeZone({ blocks: withBlocks ? ["198.51.100.0/24"] : null })],
      }),
      createZone: () => ({
        status: 200,
        body: { version_id: "v-draft", revision: 6, content_sha256: HASH_DRAFT, zone_id: "z-partner" },
      }),
    });
    await openZonesTab();

    fireEvent.click(screen.getByRole("button", { name: "Add zone" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/Name/), { target: { value: "Z-PARTNER" } });
    fireEvent.change(within(dialog).getByLabelText("Blocks"), {
      target: {
        value: "203.0.113.0/25\n192.0.2.10 - 192.0.2.20\n2001:db8::1 - 2001:db8::4",
      },
    });

    const preview = within(dialog).getByTestId("cidr-decomposition-preview");
    expect(preview).toHaveTextContent("7 canonical CIDR blocks");
    expect(preview).toHaveTextContent("IPv4, 11 addresses");
    expect(preview).toHaveTextContent("IPv6, 4 addresses");

    const v4List = within(preview).getByRole("list", { name: "CIDR decomposition for 192.0.2.10 - 192.0.2.20" });
    expect(within(v4List).getAllByRole("listitem").map((el) => el.textContent)).toEqual([
      "192.0.2.10/31",
      "192.0.2.12/30",
      "192.0.2.16/30",
      "192.0.2.20/32",
    ]);

    const v6List = within(preview).getByRole("list", { name: "CIDR decomposition for 2001:db8::1 - 2001:db8::4" });
    expect(within(v6List).getAllByRole("listitem").map((el) => el.textContent)).toEqual([
      "2001:db8::1/128",
      "2001:db8::2/127",
      "2001:db8::4/128",
    ]);

    const saveButton = within(dialog).getByRole("button", { name: "Save to draft" });
    expect(saveButton).toBeDisabled();

    fireEvent.click(within(preview).getByRole("button", { name: "Apply CIDR decomposition" }));
    expect(within(dialog).queryByTestId("cidr-decomposition-preview")).not.toBeInTheDocument();
    expect(within(dialog).getByLabelText("Blocks")).toHaveValue(
      [
        "203.0.113.0/25",
        "192.0.2.10/31",
        "192.0.2.12/30",
        "192.0.2.16/30",
        "192.0.2.20/32",
        "2001:db8::1/128",
        "2001:db8::2/127",
        "2001:db8::4/128",
      ].join("\n"),
    );
    expect(saveButton).toBeEnabled();

    fireEvent.click(saveButton);
    expect(await screen.findByText("Zone Z-PARTNER was saved to the draft. The draft is not published.")).toBeInTheDocument();
    expect(requests.find((r) => r.method === "POST" && r.url === "/api/v2/zone-matrix/draft/zones")?.body).toEqual({
      revision: 5,
      name: "Z-PARTNER",
      description: null,
      role: null,
      blocks: [
        "203.0.113.0/25",
        "192.0.2.10/31",
        "192.0.2.12/30",
        "192.0.2.16/30",
        "192.0.2.20/32",
        "2001:db8::1/128",
        "2001:db8::2/127",
        "2001:db8::4/128",
      ],
    });
  });

  it("cidr decomposition helper: Decompose IP range previews, alerts on invalid bounds, and appends canonical CIDRs cleanly", async () => {
    serveZones({ can: ZONES_READ_CAN });
    const { container } = render(<ZoneMatrixScreen />);
    fireEvent.click(await screen.findByRole("tab", { name: "Zones" }));
    await screen.findByRole("table", { name: "Zones" });

    fireEvent.click(screen.getByRole("button", { name: "Add zone" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/Name/), { target: { value: "ZONE-ALPHA" } });
    fireEvent.change(within(dialog).getByLabelText("Blocks"), { target: { value: "203.0.113.0/24\n\n  " } });

    expect(within(dialog).queryByTestId("range-helper-panel")).not.toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Decompose IP range" }));
    const helper = within(dialog).getByTestId("range-helper-panel");

    // Reversed / mixed-family bounds show role="alert" and keep Add CIDRs to blocks disabled.
    fireEvent.change(within(helper).getByLabelText("Start address"), { target: { value: "192.0.2.1" } });
    fireEvent.change(within(helper).getByLabelText("End address"), { target: { value: "2001:db8::1" } });
    expect(within(helper).getByRole("alert")).toHaveTextContent(/same IP family/i);
    expect(within(helper).getByRole("button", { name: "Add CIDRs to blocks" })).toBeDisabled();

    // Valid range previews live CIDRs + count and appends cleanly with trimEnd().
    fireEvent.change(within(helper).getByLabelText("Start address"), { target: { value: "198.51.100.10" } });
    fireEvent.change(within(helper).getByLabelText("End address"), { target: { value: "198.51.100.13" } });
    expect(helper).toHaveTextContent("IPv4");
    expect(helper).toHaveTextContent("4 addresses");
    expect(helper).toHaveTextContent("2 canonical CIDR blocks");

    const addButton = within(helper).getByRole("button", { name: "Add CIDRs to blocks" });
    expect(addButton).toBeEnabled();
    fireEvent.click(addButton);

    expect(within(dialog).getByLabelText("Blocks")).toHaveValue(
      "203.0.113.0/24\n198.51.100.10/31\n198.51.100.12/31",
    );
    expect(within(dialog).queryByTestId("cidr-decomposition-preview")).not.toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Save to draft" })).toBeEnabled();
    expect((container.textContent ?? "") + (dialog.textContent ?? "")).not.toMatch(/compliant|bddk|\bpass(ed)?\b|score|%/i);
  });
});
