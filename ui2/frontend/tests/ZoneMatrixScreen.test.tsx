import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { screenFromSearch } from "../src/App";
import { ZoneMatrixScreen } from "../src/screens/zonematrix/ZoneMatrixScreen";
import { moveText, zoneChangeLines } from "../src/screens/zonematrix/VersionsTab";
import { addressCount, publishCheckText, zoneErrorLines } from "../src/screens/zonematrix/zoneMatrixApi";
import { activeLeafId, groupOfLeaf } from "../src/shell/navModel";

/** Zone matrix delivery step 1 (ZONE_MATRIX_CONTRACT.md sections 3, 7.1, 10.1). Synthetic values only. */
const HASH_ACTIVE = "a".repeat(64);
const HASH_DRAFT = "b1c2d3e4f5a6".padEnd(64, "0");
const sizes = (v4: string, v6 = "0") => ({ IPV4: v4, IPV6: v6 });
const zone = (id: string, name: string, role: string | null, blocks: string[], v4: string, withBlocks: boolean, v6 = "0") => ({
  zone_id: id, name, description: name === "Z-APP" ? "Application servers" : null, role, block_count: blocks.length,
  effective_addresses: sizes(v4, v6), blocks: withBlocks ? blocks : null,
});
const activeSummary = { version_id: "v-active", number: 3, state: "ACTIVE", revision: 4, content_sha256: HASH_ACTIVE,
  created_at: "2026-11-10T08:00:00Z", updated_at: "2026-11-12T08:00:00Z", published_by: "USER-2", published_at: "2026-11-12T08:00:00Z" };
const activeDetail = (withBlocks: boolean) => ({
  ...activeSummary,
  zones: [
    zone("z1", "Z-APP", "APPLICATION", ["192.0.2.0/24", "2001:db8::/32"], "256", withBlocks, "79228162514264337593543950336"),
    zone("z2", "Z-DB", "DATABASE", ["198.51.100.0/24"], "256", withBlocks),
  ],
  unassigned_addresses: sizes("4294966784", "340282287692774301204839013793224261632"),
  redundant_blocks: withBlocks ? [] : 0,
  revisions: [],
});
const draftSummary = { version_id: "v-draft", number: null, state: "DRAFT", revision: 5, content_sha256: HASH_DRAFT,
  created_at: "2026-11-13T08:00:00Z", updated_at: "2026-11-13T09:00:00Z", published_by: null, published_at: null };
const draftDetail = (withBlocks: boolean) => ({
  ...draftSummary,
  zones: [
    zone("z1", "Z-APP", "APPLICATION", ["192.0.2.0/25", "2001:db8::/32"], "128", withBlocks, "79228162514264337593543950336"),
    zone("z2", "Z-DB", "DATABASE", ["198.51.100.0/24", "192.0.2.128/25"], "384", withBlocks),
    zone("z3", "Z-MGMT", "MANAGEMENT", ["203.0.113.0/24"], "256", withBlocks),
  ],
  unassigned_addresses: sizes("4294966528", "340282287692774301204839013793224261632"),
  redundant_blocks: withBlocks ? [] : 0,
  revisions: [
    { revision: 1, actor: "USER-1", occurred_at: "2026-11-13T08:00:00Z", change_kind: "DRAFT_CREATE", zone_id: null, content_sha256: HASH_ACTIVE, rationale_given: null },
    { revision: 5, actor: "USER-1", occurred_at: "2026-11-13T09:00:00Z", change_kind: "ZONE_UPDATE", zone_id: "z2", content_sha256: HASH_DRAFT, rationale_given: null },
  ],
});
const diff = {
  zones: [
    { zone_id: "z2", change: "CHANGED", name_before: "Z-DB", name_after: "Z-DB", role_before: "DATABASE", role_after: "DATABASE",
      blocks_added: 1, blocks_removed: 0, effective_before: sizes("256"), effective_after: sizes("384") },
    { zone_id: "z3", change: "ADDED", name_before: null, name_after: "Z-MGMT", role_before: null, role_after: "MANAGEMENT",
      blocks_added: 1, blocks_removed: 0, effective_before: sizes("0"), effective_after: sizes("256") },
  ],
  moves: [
    { from: "Z-APP", to: "Z-DB", family: "IPV4", addresses: "128", leaves_a_zone: true },
    { from: "UNASSIGNED", to: "Z-MGMT", family: "IPV4", addresses: "256", leaves_a_zone: false },
  ],
  rationale_required: true, unassigned_before: sizes("4294966784"), unassigned_after: sizes("4294966528"),
};

type Can = { view_blocks: boolean; edit: boolean; publish: boolean };
interface World {
  can: Can;
  hasDraft: boolean;
  /** The server withholds the draft from a reader who may neither edit nor publish; it says only that one exists. */
  draftHidden?: boolean;
  publishCheck: { eligible: boolean; reason: string | null };
  /** No version has been published yet. */
  noActive?: boolean;
  /** Answers first, for a request the test wants to refuse or change; undefined falls through to the normal answers. */
  override?: (method: string, url: string) => { status: number; body: unknown } | undefined;
  /** Answers for non-GET requests, by `METHOD path`. */
  writes: Record<string, { status: number; body: unknown }>;
}
const ALL: Can = { view_blocks: true, edit: true, publish: true };
const READ_ONLY: Can = { view_blocks: false, edit: false, publish: false };

interface Sent { readonly method: string; readonly path: string; readonly body: unknown }

function serve(world: World) {
  const sent: Sent[] = [];
  const reply = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? "GET";
    if (url === "/session/status") return reply(200, { csrf_token: "synthetic-token" });
    const [path, query = ""] = url.split("?");
    const withBlocks = query.includes("blocks=1");
    sent.push({ method, path: url, body: init?.body ? JSON.parse(String(init.body)) : undefined });
    const forced = world.override?.(method, url);
    if (forced) return reply(forced.status, forced.body);
    if (world.noActive && method === "GET") {
      if (path === "/api/v2/zone-matrix/draft") return reply(200, { active: null, draft: null, diff: null, publish_check: null, can: world.can });
      if (path === "/api/v2/zone-matrix/versions") return reply(200, { versions: [], can: world.can });
    }
    if (method === "GET" && path === "/api/v2/zone-matrix/draft") {
      if (world.draftHidden) return reply(200, { active: activeSummary, draft_exists: true, draft: null, diff: null, publish_check: null, can: world.can });
      return reply(200, world.hasDraft
        ? { active: activeSummary, draft: draftDetail(withBlocks), diff, publish_check: world.publishCheck, can: world.can }
        : { active: activeSummary, draft: null, diff: null, publish_check: null, can: world.can });
    }
    if (method === "GET" && path === "/api/v2/zone-matrix/versions/v-active") return reply(200, { ...activeDetail(withBlocks), can: world.can });
    if (method === "GET" && path === "/api/v2/zone-matrix/versions") {
      return reply(200, { versions: world.hasDraft ? [draftSummary, activeSummary] : [activeSummary], can: world.can });
    }
    const write = world.writes[`${method} ${path}`];
    return write ? reply(write.status, write.body) : reply(404, { error: "NOT_FOUND", reason: "no such route in this test" });
  }));
  return sent;
}

const world = (over: Partial<World> = {}): World => ({ can: ALL, hasDraft: true, publishCheck: { eligible: true, reason: null }, writes: {}, ...over });
const gets = (sent: Sent[]) => sent.filter((s) => s.method === "GET").map((s) => s.path);
const openZones = async () => { fireEvent.click(await screen.findByRole("tab", { name: "Zones" })); };
const openVersions = async () => { fireEvent.click(await screen.findByRole("tab", { name: "Versions" })); await screen.findByRole("table", { name: "Version history" }); };

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); window.history.replaceState(null, "", "/"); });

describe("Zone matrix: address and menu", () => {
  it("routes ?screen=zone_matrix and lights Policy and changes > Zone matrix", () => {
    expect(screenFromSearch("?screen=zone_matrix")).toEqual({ screen: "zone_matrix", preview: false });
    expect(activeLeafId("zone_matrix", "?screen=zone_matrix")).toBe("zone-matrix");
    expect(groupOfLeaf("zone-matrix")?.label).toBe("Policy and changes");
  });
});

describe("Zone matrix: Zones tab", () => {
  it("lists the active version's zones without asking for blocks, and shows no editor to a reader", async () => {
    const sent = serve(world({ can: READ_ONLY, hasDraft: false }));
    render(<ZoneMatrixScreen />);
    await openZones();
    const table = await screen.findByRole("table", { name: "Zones" });
    expect(screen.getByText("Zones are sets of IP blocks the bank maintains. Statements and evaluations show policy intent as defined in management, not observed traffic.")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /Version 3/ })).toBeInTheDocument();
    const app = within(table).getByText("Z-APP").closest("tr")!;
    expect(within(app).getByText("Application")).toBeInTheDocument();
    expect(within(app).getByText("256")).toBeInTheDocument();
    expect(within(app).getByText("about 7.92 x 10^28")).toBeInTheDocument();
    expect(within(table).getByText("Z-DB")).toBeInTheDocument();
    expect(screen.getByTestId("unassigned-space")).toHaveTextContent("IPv4 4,294,966,784");
    // Blocks are an audited read: never fetched by default.
    expect(gets(sent)).toEqual(["/api/v2/zone-matrix/draft?blocks=0", "/api/v2/zone-matrix/versions/v-active?blocks=0", "/api/v2/zone-matrix/versions"]);
    expect(screen.queryByText("192.0.2.0/24")).not.toBeInTheDocument();
    // can.edit false, can.view_blocks false: no editor, no blocks toggle, no lookup.
    for (const name of ["Add zone", "Edit", "Delete", "Show blocks"]) expect(screen.queryByRole("button", { name })).not.toBeInTheDocument();
    expect(screen.queryByText(/recorded in the audit log/)).not.toBeInTheDocument();
    expect(screen.queryByRole("form", { name: "Address lookup" })).not.toBeInTheDocument();
    expect(screen.getAllByRole("tab").map((t) => t.textContent)).toEqual(["Matrix", "Violations", "Zones", "Versions", "Evaluate", "Coverage"]);
  });

  it("renders a masked response as it is", async () => {
    serve(world({ can: READ_ONLY, hasDraft: false }));
    const masked = { ...activeDetail(false), zones: [zone("z1", "ZONE-1", null, ["x"], "256", false)], can: READ_ONLY };
    const inner = vi.mocked(fetch).getMockImplementation()!;
    vi.mocked(fetch).mockImplementation(async (input, init) => String(input).startsWith("/api/v2/zone-matrix/versions/v-active")
      ? new Response(JSON.stringify(masked), { status: 200 }) : inner(input, init));
    render(<ZoneMatrixScreen />);
    await openZones();
    const row = (await screen.findByText("ZONE-1")).closest("tr")!;
    expect(within(row).getByText("None")).toBeInTheDocument();
  });

  it("loads blocks only on Show blocks, and looks an address up on request", async () => {
    const sent = serve(world({ hasDraft: false, writes: { "POST /api/v2/zone-matrix/lookup": { status: 200,
      body: { version_id: "v-active", number: 3, state: "ACTIVE", family: "IPV4", zone_id: "z1", zone_name: "Z-APP" } } } }));
    render(<ZoneMatrixScreen />);
    await openZones();
    // The action and its audit line sit together; nothing but the action reads blocks.
    expect(await screen.findByText("Viewing blocks is recorded in the audit log")).toBeInTheDocument();
    expect(gets(sent).some((path) => path.includes("blocks=1"))).toBe(false);
    fireEvent.click(await screen.findByRole("button", { name: "Show blocks" }));
    expect(await screen.findByText("192.0.2.0/24")).toBeInTheDocument();
    expect(gets(sent)).toContain("/api/v2/zone-matrix/versions/v-active?blocks=1");
    expect(screen.getByRole("button", { name: "Hide blocks" })).toBeInTheDocument();
    expect(screen.getByText("A lookup is recorded in the audit log")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Address"), { target: { value: "192.0.2.7" } });
    fireEvent.click(screen.getByRole("button", { name: "Look up" }));
    expect(await screen.findByTestId("lookup-result")).toHaveTextContent("192.0.2.7 is in Z-APP.");
    expect(screen.getByTestId("lookup-result")).toHaveTextContent("IPv4, version 3");
    expect(sent.find((s) => s.path === "/api/v2/zone-matrix/lookup")?.body).toEqual({ address: "192.0.2.7", version_id: "v-active" });
  });

  it("shows the draft to an editor and sends the draft revision with an edit", async () => {
    const sent = serve(world({ writes: { "POST /api/v2/zone-matrix/draft/zones": { status: 200,
      body: { version_id: "v-draft", revision: 6, content_sha256: HASH_DRAFT, zone_id: "z9" } } } }));
    render(<ZoneMatrixScreen />);
    await openZones();
    expect(await screen.findByText("Draft r5, not published")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /Draft r5/ })).toBeInTheDocument();
    expect(screen.getByText("Changes apply only after another person publishes the draft.")).toBeInTheDocument();
    expect(screen.getByText("Z-MGMT")).toBeInTheDocument();
    // Without the blocks on screen a zone cannot be edited (saving replaces the whole list).
    expect(screen.getAllByRole("button", { name: "Edit" })[0]).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Add zone" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/canonical CIDR form/)).toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText(/Name/), { target: { value: "Z-PARTNER" } });
    fireEvent.change(within(dialog).getByLabelText("Blocks"), { target: { value: "203.0.113.0/25\n\n 2001:db8:1::/48 \n" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save to draft" }));
    expect(await screen.findByText("Zone Z-PARTNER was saved to the draft. The draft is not published.")).toBeInTheDocument();
    expect(sent.find((s) => s.method === "POST" && s.path === "/api/v2/zone-matrix/draft/zones")?.body)
      .toEqual({ revision: 5, name: "Z-PARTNER", description: null, role: null, blocks: ["203.0.113.0/25", "2001:db8:1::/48"] });
  });

  it("sends revision null when there is no draft, and shows refused blocks per line", async () => {
    const sent = serve(world({ hasDraft: false, writes: { "POST /api/v2/zone-matrix/draft/zones": { status: 400,
      body: { error: "INVALID_BLOCKS", reason: "some blocks are not canonical CIDR blocks", errors: [{ index: 1, reason: "host bits are set" }] } } } }));
    render(<ZoneMatrixScreen />);
    await openZones();
    fireEvent.click(await screen.findByRole("button", { name: "Add zone" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/Name/), { target: { value: "Z-PARTNER" } });
    fireEvent.change(within(dialog).getByLabelText("Blocks"), { target: { value: "203.0.113.0/25\n192.0.2.7/24" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save to draft" }));
    expect(await within(dialog).findByText("Line 2 (192.0.2.7/24): host bits are set")).toBeInTheDocument();
    expect(within(dialog).getByText("Some blocks are not canonical CIDR blocks.")).toBeInTheDocument();
    expect((sent.find((s) => s.method === "POST")?.body as { revision: unknown }).revision).toBeNull();
  });

  it("tells the user to check the reloaded draft when the draft changed under an edit", async () => {
    const sent = serve(world({ writes: { "POST /api/v2/zone-matrix/draft/zones/z3/delete": { status: 409,
      body: { error: "REQUEST_CHANGED", reason: "the draft changed since you loaded it; reload and try again" } } } }));
    render(<ZoneMatrixScreen />);
    await openZones();
    const row = (await screen.findByText("Z-MGMT")).closest("tr")!;
    fireEvent.click(within(row).getByRole("button", { name: "Delete" }));
    const dialog = await screen.findByRole("dialog");
    expect(sent.some((s) => s.path.endsWith("/delete"))).toBe(false);
    const before = gets(sent).length;
    fireEvent.click(within(dialog).getByRole("button", { name: "Confirm delete" }));
    expect(await screen.findByText("The draft changed since you loaded it. The latest draft is loaded now; check it and try again.")).toBeInTheDocument();
    expect(sent.find((s) => s.path.endsWith("/z3/delete"))?.body).toEqual({ revision: 5 });
    // The data is read again.
    await waitFor(() => expect(gets(sent).length).toBeGreaterThan(before));
  });
});

describe("Zone matrix: Versions tab", () => {
  it("lists versions, the draft's changes with the loosening move, and its revisions", async () => {
    serve(world());
    render(<ZoneMatrixScreen />);
    await openVersions();
    const history = screen.getByRole("table", { name: "Version history" });
    const activeRow = within(history).getByText("Active").closest("tr")!;
    expect(within(activeRow).getByText("3")).toBeInTheDocument();
    expect(within(activeRow).getByText("USER-2")).toBeInTheDocument();
    const hash = within(activeRow).getByText("aaaaaaaaaaaa");
    expect(hash).toHaveAttribute("title", HASH_ACTIVE);
    expect(within(history).getByText("Draft r5")).toBeInTheDocument();
    expect(screen.getByText("Zone Z-MGMT added")).toBeInTheDocument();
    expect(screen.getByText("Zone Z-DB changed")).toBeInTheDocument();
    const moves = screen.getByRole("list", { name: "Address moves" });
    const loosening = within(moves).getByText(/128 IPv4 addresses move from Z-APP to Z-DB/).closest("li")!;
    expect(loosening).toHaveAttribute("data-loosening", "true");
    expect(within(loosening).getByText("Loosening change")).toBeInTheDocument();
    expect(within(loosening).getByText("These addresses leave zone Z-APP. Publishing needs a rationale.")).toBeInTheDocument();
    const gain = within(moves).getByText(/256 IPv4 addresses move from unassigned to Z-MGMT/).closest("li")!;
    expect(gain).not.toHaveAttribute("data-loosening");
    expect(screen.getByTestId("draft-summary")).toHaveTextContent("1 zone added, 0 zones removed, 1 zone changed, 2 address moves; 1 loosening change.");
    const changed = screen.getByRole("table", { name: "Changed zones" });
    expect(within(within(changed).getByText("Zone Z-DB changed").closest("tr")!).getByText("256 to 384")).toBeInTheDocument();
    expect(screen.getByText(/Unassigned address space: IPv4 4,294,966,784, IPv6 0 before; IPv4 4,294,966,528, IPv6 0 after\./)).toBeInTheDocument();
    const revisions = screen.getByRole("table", { name: "Draft revisions" });
    expect(within(revisions).getAllByText("USER-1")).toHaveLength(2);
    expect(within(revisions).getByText("Zone changed")).toBeInTheDocument();
    expect(within(revisions).getByText("b1c2d3e4f5a6")).toHaveAttribute("title", HASH_DRAFT);
  });

  it("keeps Publish closed with the SELF_APPROVAL sentence", async () => {
    serve(world({ publishCheck: { eligible: false, reason: "SELF_APPROVAL" } }));
    render(<ZoneMatrixScreen />);
    await openVersions();
    expect(screen.getByTestId("publish-check")).toHaveTextContent("You changed this draft; another person must publish it.");
    fireEvent.change(screen.getByLabelText(/Rationale/), { target: { value: "Synthetic reason" } });
    expect(screen.getByRole("button", { name: "Publish draft" })).toBeDisabled();
  });

  it.each([
    ["SELF_APPROVAL", "You changed this draft; another person must publish it."],
    ["COMPLIANCE_ROLE_MISSING", "The publisher or an editor of this draft must hold the compliance administrator role."],
    ["PRINCIPAL_NOT_ELIGIBLE", "This account cannot publish."],
    ["NOTHING_TO_PUBLISH", "The draft equals the active version; there is nothing to publish."],
  ])("keeps Publish closed and says why for %s", async (reason, sentence) => {
    serve(world({ publishCheck: { eligible: false, reason } }));
    render(<ZoneMatrixScreen />);
    await openVersions();
    expect(screen.getByTestId("publish-check")).toHaveTextContent(sentence);
    expect(screen.getByRole("button", { name: "Publish draft" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Confirm publish" })).not.toBeInTheDocument();
  });

  it("requires a rationale, then publishes the revision and hash on screen", async () => {
    const sent = serve(world({ writes: { "POST /api/v2/zone-matrix/draft/publish": { status: 200,
      body: { version_id: "v-draft", number: 4, content_sha256: HASH_DRAFT } } } }));
    render(<ZoneMatrixScreen />);
    await openVersions();
    expect(screen.getByTestId("publish-check")).toHaveTextContent("This account can publish this draft.");
    const publish = screen.getByRole("button", { name: "Publish draft" });
    expect(screen.getByLabelText(/Rationale/)).toBeRequired();
    expect(publish).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/Rationale/), { target: { value: "  Database subnet moved  " } });
    expect(publish).toBeEnabled();
    // Publishing is confirmed first, and the confirmation says a published version is final.
    fireEvent.click(publish);
    expect(sent.some((s) => s.path.endsWith("/publish"))).toBe(false);
    expect(screen.getByText(/A published version cannot be changed afterwards/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Confirm publish" }));
    expect(await screen.findByText("Published as version 4.")).toBeInTheDocument();
    expect(sent.find((s) => s.path === "/api/v2/zone-matrix/draft/publish")?.body)
      .toEqual({ revision: 5, content_sha256: HASH_DRAFT, rationale: "Database subnet moved" });
  });

  it("shows the reload message and reads again when the draft changed before publish", async () => {
    const sent = serve(world({ writes: { "POST /api/v2/zone-matrix/draft/publish": { status: 409,
      body: { error: "REQUEST_CHANGED", reason: "the draft changed since you loaded it; reload and try again" } } } }));
    render(<ZoneMatrixScreen />);
    await openVersions();
    fireEvent.change(screen.getByLabelText(/Rationale/), { target: { value: "Synthetic reason" } });
    const before = gets(sent).length;
    fireEvent.click(screen.getByRole("button", { name: "Publish draft" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirm publish" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The draft changed since you loaded it. The latest draft is loaded now; check it and try again.");
    await waitFor(() => expect(gets(sent).length).toBeGreaterThan(before));
  });

  it("offers Publish and Discard only from the server's booleans", async () => {
    serve(world({ can: { view_blocks: false, edit: false, publish: false } }));
    render(<ZoneMatrixScreen />);
    await openVersions();
    expect(screen.getByRole("list", { name: "Address moves" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Publish draft" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Discard draft" })).not.toBeInTheDocument();
  });

  it("tells a reader a draft is being prepared without showing it", async () => {
    serve(world({ can: READ_ONLY, draftHidden: true }));
    render(<ZoneMatrixScreen />);
    expect(await screen.findByText("A draft is being prepared")).toBeInTheDocument();
    await openVersions();
    expect(screen.getByText("A draft is being prepared")).toBeInTheDocument();
    expect(screen.queryByText("There is no draft")).not.toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "Address moves" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Publish draft" })).not.toBeInTheDocument();
  });

  it("discards the draft after a confirmation, with the revision on screen", async () => {
    const sent = serve(world({ writes: { "POST /api/v2/zone-matrix/draft/discard": { status: 200, body: { discarded: "v-draft" } } } }));
    render(<ZoneMatrixScreen />);
    await openVersions();
    fireEvent.click(screen.getByRole("button", { name: "Discard draft" }));
    expect(sent.some((s) => s.path.endsWith("/discard"))).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Confirm discard" }));
    expect(await screen.findByText("The draft was discarded. The active version is unchanged.")).toBeInTheDocument();
    expect(sent.find((s) => s.path.endsWith("/discard"))?.body).toEqual({ revision: 5 });
  });
});

describe("Zone matrix: states and refusals", () => {
  it("shows a read error with Retry and reads again", async () => {
    let failing = true;
    serve(world({ can: READ_ONLY, hasDraft: false, override: (method, url) => (failing && method === "GET" && url.startsWith("/api/v2/zone-matrix/draft")
      ? { status: 500, body: { error: "INTERNAL", reason: "the zones could not be loaded" } } : undefined) }));
    render(<ZoneMatrixScreen />);
    expect(await screen.findByText("The zones could not be read")).toBeInTheDocument();
    expect(screen.getByText("The zones could not be loaded.")).toBeInTheDocument();
    failing = false;
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    await openZones();
    expect(await screen.findByRole("table", { name: "Zones" })).toBeInTheDocument();
  });

  it("says that no version has been published, and offers the first zone only to an editor", async () => {
    serve(world({ noActive: true, hasDraft: false }));
    render(<ZoneMatrixScreen />);
    expect(await screen.findByText("No version has been published")).toBeInTheDocument();
    await openZones();
    expect(screen.getByRole("button", { name: "Add zone" })).toBeInTheDocument();
    await openVersions();
    expect(screen.getByText("No version has been published.")).toBeInTheDocument();
  });

  it("says a draft has no zones, and shows a reader the empty active version", async () => {
    serve(world({ can: READ_ONLY, hasDraft: false }));
    const empty = { ...activeDetail(false), zones: [], can: READ_ONLY };
    const inner = vi.mocked(fetch).getMockImplementation()!;
    vi.mocked(fetch).mockImplementation(async (input, init) => String(input).startsWith("/api/v2/zone-matrix/versions/v-active")
      ? new Response(JSON.stringify(empty), { status: 200 }) : inner(input, init));
    render(<ZoneMatrixScreen />);
    await openZones();
    expect(await screen.findByText("This version has no zones. Every address is unassigned.")).toBeInTheDocument();
  });

  it("reads the draft's blocks on request only, and the active version's blocks on a second request", async () => {
    const sent = serve(world());
    render(<ZoneMatrixScreen />);
    await openZones();
    await screen.findByRole("table", { name: "Zones" });
    expect(gets(sent).some((path) => path.includes("blocks=1"))).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Show blocks" }));
    expect(await screen.findByText("203.0.113.0/24")).toBeInTheDocument();
    // With a draft the server already reads the active blocks for the comparison: the screen does not read them again.
    expect(gets(sent).filter((path) => path.includes("blocks=1"))).toEqual(["/api/v2/zone-matrix/draft?blocks=1"]);
    expect(screen.getByText(/so is every reload while blocks are shown/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Active version" }));
    expect(screen.queryByText("203.0.113.0/24")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add zone" })).not.toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: "Show blocks of the active version" }));
    expect(await screen.findByText("2001:db8::/32")).toBeInTheDocument();
    expect(gets(sent).filter((path) => path.includes("blocks=1"))).toEqual(["/api/v2/zone-matrix/draft?blocks=1", "/api/v2/zone-matrix/versions/v-active?blocks=1"]);
  });

  it("does not ask for blocks again after a refused blocks read", async () => {
    const sent = serve(world({ hasDraft: false, override: (method, url) => (method === "GET" && url.includes("blocks=1")
      ? { status: 503, body: { error: "ZONE_MATRIX_AUDIT_UNAVAILABLE", reason: "the view was refused because its audit row could not be written" } } : undefined) }));
    render(<ZoneMatrixScreen />);
    await openZones();
    fireEvent.click(await screen.findByRole("button", { name: "Show blocks" }));
    expect(await screen.findByText(/The view was refused because its audit row could not be written\./)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Show blocks" })).toBeEnabled();
    const asked = gets(sent).filter((path) => path.includes("blocks=1")).length;
    fireEvent.click(screen.getByRole("tab", { name: "Versions" }));
    expect(gets(sent).filter((path) => path.includes("blocks=1")).length).toBe(asked);
  });

  it("edits a zone with its blocks on screen and sends the whole list", async () => {
    const sent = serve(world({ writes: { "PUT /api/v2/zone-matrix/draft/zones/z3": { status: 200,
      body: { version_id: "v-draft", revision: 6, content_sha256: HASH_DRAFT, zone_id: "z3" } } } }));
    render(<ZoneMatrixScreen />);
    await openZones();
    fireEvent.click(await screen.findByRole("button", { name: "Show blocks" }));
    const row = (await screen.findByText("Z-MGMT")).closest("tr")!;
    await within(row).findByText("203.0.113.0/24");
    fireEvent.click(within(row).getByRole("button", { name: "Edit" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByLabelText("Blocks")).toHaveValue("203.0.113.0/24");
    fireEvent.change(within(dialog).getByLabelText("Blocks"), { target: { value: "203.0.113.0/24\n198.51.100.0/25" } });
    expect(within(dialog).getByText(/2 blocks\./)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Save to draft" }));
    expect(await screen.findByText("Zone Z-MGMT was saved to the draft. The draft is not published.")).toBeInTheDocument();
    expect(sent.find((s) => s.method === "PUT")?.body).toEqual({ revision: 5, name: "Z-MGMT", description: null, role: "MANAGEMENT", blocks: ["203.0.113.0/24", "198.51.100.0/25"] });
  });

  it("names the block and both zones when a block is in two zones", async () => {
    serve(world({ writes: { "POST /api/v2/zone-matrix/draft/zones": { status: 409,
      body: { error: "ZONE_BLOCK_DUPLICATE", reason: "the same block is in two zones", block: "192.0.2.0/24", zones: ["Z-APP", "Z-PARTNER"] } } } }));
    render(<ZoneMatrixScreen />);
    await openZones();
    fireEvent.click(await screen.findByRole("button", { name: "Add zone" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText(/Name/), { target: { value: "Z-PARTNER" } });
    fireEvent.change(within(dialog).getByLabelText("Blocks"), { target: { value: "192.0.2.0/24" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save to draft" }));
    expect(await within(dialog).findByText("192.0.2.0/24 is in Z-APP and Z-PARTNER.")).toBeInTheDocument();
    expect(within(dialog).getByText("The same block is in two zones.")).toBeInTheDocument();
  });

  it("shows the lookup result for an unassigned address as unassigned, not as an error", async () => {
    serve(world({ hasDraft: false, writes: { "POST /api/v2/zone-matrix/lookup": { status: 200,
      body: { version_id: "v-active", number: 3, state: "ACTIVE", family: "IPV6", zone_id: null, zone_name: "UNASSIGNED" } } } }));
    render(<ZoneMatrixScreen />);
    await openZones();
    fireEvent.change(await screen.findByLabelText("Address"), { target: { value: "2001:db8:ffff::1" } });
    fireEvent.click(screen.getByRole("button", { name: "Look up" }));
    expect(await screen.findByTestId("lookup-result")).toHaveTextContent("2001:db8:ffff::1 is not in any zone (unassigned).");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("says why publishing was refused when the server refuses it after all", async () => {
    serve(world({ writes: { "POST /api/v2/zone-matrix/draft/publish": { status: 409,
      body: { error: "RATIONALE_REQUIRED", reason: "this version loosens the segmentation policy; give a rationale" } } } }));
    render(<ZoneMatrixScreen />);
    await openVersions();
    fireEvent.change(screen.getByLabelText(/Rationale/), { target: { value: "x" } });
    fireEvent.click(screen.getByRole("button", { name: "Publish draft" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirm publish" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("This version loosens the segmentation policy; give a rationale.");
  });

  it("asks for no rationale when nothing leaves a zone, and sends none", async () => {
    const quiet = { ...diff, moves: [diff.moves[1]], rationale_required: false };
    const sent = serve(world({ writes: { "POST /api/v2/zone-matrix/draft/publish": { status: 200, body: { version_id: "v-draft", number: 4, content_sha256: HASH_DRAFT } } },
      override: (method, url) => (method === "GET" && url.startsWith("/api/v2/zone-matrix/draft")
        ? { status: 200, body: { active: activeSummary, draft: draftDetail(false), diff: quiet, publish_check: { eligible: true, reason: null }, can: ALL } } : undefined) }));
    render(<ZoneMatrixScreen />);
    await openVersions();
    expect(screen.queryByLabelText(/Rationale/)).not.toBeInTheDocument();
    expect(screen.queryByText("Loosening change")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Publish draft" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirm publish" }));
    await screen.findByText("Published as version 4.");
    expect(sent.find((s) => s.path.endsWith("/publish"))?.body).toEqual({ revision: 5, content_sha256: HASH_DRAFT, rationale: null });
  });

  it("closes the confirmation with Cancel and sends nothing", async () => {
    const sent = serve(world());
    render(<ZoneMatrixScreen />);
    await openVersions();
    fireEvent.change(screen.getByLabelText(/Rationale/), { target: { value: "Synthetic reason" } });
    fireEvent.click(screen.getByRole("button", { name: "Publish draft" }));
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("button", { name: "Confirm publish" })).not.toBeInTheDocument();
    expect(sent.some((s) => s.method === "POST")).toBe(false);
  });

  it("limits the rationale to 2000 characters and marks a loosening move in words", async () => {
    serve(world());
    render(<ZoneMatrixScreen />);
    await openVersions();
    expect(screen.getByLabelText(/Rationale/)).toHaveAttribute("maxlength", "2000");
    expect(screen.getByText(/addresses leave a zone in this draft/i)).toBeInTheDocument();
    expect(screen.getByText("Loosening change")).toBeInTheDocument();
  });

  it("never uses the words compliant, pass, score or percent", async () => {
    serve(world());
    const { container } = render(<ZoneMatrixScreen />);
    await openVersions();
    expect(container.textContent ?? "").not.toMatch(/compliant|\bpass(ed)?\b|score|%/i);
  });
});

describe("Zone matrix: words", () => {
  it("maps the publish refusals to plain sentences", () => {
    expect(publishCheckText("NOTHING_TO_PUBLISH")).toBe("The draft equals the active version; there is nothing to publish.");
    expect(publishCheckText("RATIONALE_REQUIRED")).toBe("This version loosens the segmentation policy; give a rationale.");
    expect(publishCheckText("SELF_APPROVAL")).toBe("You changed this draft; another person must publish it.");
    expect(publishCheckText("COMPLIANCE_ROLE_MISSING")).toBe("The publisher or an editor of this draft must hold the compliance administrator role.");
    expect(publishCheckText("PRINCIPAL_NOT_ELIGIBLE")).toBe("This account cannot publish.");
  });

  it("shows the server's reason, the duplicated block and its zones", () => {
    expect(zoneErrorLines({ status: 409, body: { error: "ZONE_BLOCK_DUPLICATE", reason: "the same block is in two zones", block: "192.0.2.0/24", zones: ["Z-APP", "Z-DB"] } }))
      .toEqual(["The same block is in two zones.", "192.0.2.0/24 is in Z-APP and Z-DB."]);
    expect(zoneErrorLines({ status: 400, body: { error: "INVALID_ADDRESS", reason: "not an IP address" } })).toEqual(["Not an IP address."]);
    expect(zoneErrorLines({ status: 409, body: { error: "RATIONALE_REQUIRED", reason: "x" } })).toEqual(["This version loosens the segmentation policy; give a rationale."]);
    expect(zoneErrorLines({ status: 503, body: { error: "ZONE_MATRIX_AUDIT_UNAVAILABLE" } })).toEqual(["The view was refused because its audit row could not be written."]);
    expect(zoneErrorLines({ status: 403, body: { error: "READ_ONLY_PERSONA", reason: "the masked persona cannot change or look up zones" } })).toEqual(["The masked persona cannot change or look up zones."]);
    expect(zoneErrorLines({ status: 403, body: { error: "ZONE_BLOCKS_NOT_PERMITTED", reason: "viewing zone blocks needs zone_matrix_zones_read" } })).toEqual(["Viewing zone blocks needs zone_matrix_zones_read."]);
  });

  it("formats address counts from decimal strings and words a move and a change", () => {
    expect(addressCount("0")).toBe("0");
    expect(addressCount("4294967296")).toBe("4,294,967,296");
    expect(addressCount("340282366920938463463374607431768211456")).toBe("about 3.40 x 10^38");
    expect(addressCount(undefined)).toBe("UNKNOWN");
    expect(moveText({ from: "Z-APP", to: "UNASSIGNED", family: "IPV6", addresses: "1", leaves_a_zone: true })).toBe("1 IPv6 address moves from Z-APP to unassigned");
    expect(zoneChangeLines({ zone_id: "z1", change: "CHANGED", name_before: "Z-OLD", name_after: "Z-APP", role_before: "USER", role_after: "APPLICATION",
      blocks_added: ["192.0.2.0/24"], blocks_removed: 0, effective_before: sizes("0"), effective_after: sizes("256") }))
      .toEqual({ headline: "Zone Z-APP changed (was Z-OLD)", details: ["Zone role: User to Application", "Blocks added: 192.0.2.0/24"] });
  });
});
