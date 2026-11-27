import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ZoneMatrixScreen } from "../src/screens/zonematrix/ZoneMatrixScreen";
import type { ZoneMatrixCan } from "../src/screens/zonematrix/zoneMatrixApi";

const HASH_ACTIVE = "a".repeat(64);
const HASH_DRAFT = "b".repeat(64);

const EDITOR_CAN: ZoneMatrixCan = {
  view_blocks: true,
  edit: true,
  publish: true,
  confirm_reference: true,
};

const AIVIEW_CAN: ZoneMatrixCan = {
  view_blocks: false,
  edit: false,
  publish: false,
  confirm_reference: false,
};

function serveZones(can: ZoneMatrixCan) {
  const sent: { readonly method: string; readonly path: string; readonly body: unknown }[] = [];
  const reply = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

  const activeSummary = {
    version_id: "v-active",
    number: 3,
    state: "ACTIVE",
    revision: 4,
    content_sha256: HASH_ACTIVE,
    created_at: "2026-11-10T08:00:00Z",
    updated_at: "2026-11-12T08:00:00Z",
    published_by: "USER-2",
    published_at: "2026-11-12T08:00:00Z",
  };
  const draftDetail = {
    version_id: "v-draft",
    number: null,
    state: "DRAFT",
    revision: 5,
    content_sha256: HASH_DRAFT,
    created_at: "2026-11-13T08:00:00Z",
    updated_at: "2026-11-13T09:00:00Z",
    published_by: null,
    published_at: null,
    zones: [
      {
        zone_id: "z-app",
        name: "Z-APP",
        description: "Application tier",
        role: "APPLICATION",
        block_count: 1,
        effective_addresses: { IPV4: "256", IPV6: "0" },
        blocks: null,
      },
    ],
    unassigned_addresses: { IPV4: "4294967040", IPV6: "340282366920938463463374607431768211456" },
    redundant_blocks: 0,
    revisions: [],
  };

  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = (init?.method ?? "GET").toUpperCase();
    if (url === "/session/status") return reply(200, { csrf_token: "synthetic-token" });
    const [path] = url.split("?");
    const body = typeof init?.body === "string" ? JSON.parse(init.body) : undefined;
    sent.push({ method, path, body });

    if (method === "GET" && path === "/api/v2/zone-matrix/draft") {
      return reply(200, {
        active: activeSummary,
        draft: can.edit ? draftDetail : null,
        diff: null,
        publish_check: can.edit ? { eligible: true, reason: null } : null,
        can,
      });
    }
    if (method === "GET" && path === "/api/v2/zone-matrix/versions/v-active") {
      return reply(200, {
        ...activeSummary,
        zones: draftDetail.zones,
        unassigned_addresses: draftDetail.unassigned_addresses,
        redundant_blocks: 0,
        revisions: [],
        can,
      });
    }
    if (method === "GET" && path === "/api/v2/zone-matrix/versions") {
      return reply(200, { versions: [activeSummary], can });
    }
    if (method === "GET" && path === "/api/v2/zone-matrix/summary") {
      return reply(200, {
        evaluated_at: "2026-11-25T08:00:00Z",
        state: "NO_COMPLETE_RUN",
        state_text: "no policy has a complete evaluation run yet",
        masked: !can.view_blocks,
        policy_names_masked: !can.view_blocks,
        witnesses_included: false,
        staleness_threshold: { seconds: 86400, hours: 24, interim: true, source: "PO 2026-11-23", label: "interim" },
        active_version: { version_id: "v-active", version_number: 3, content_sha256: HASH_ACTIVE, published_at: "2026-11-12T08:00:00Z" },
        headline: null,
        headline_absent_reason: "no complete run",
        policies: [],
        not_evaluated: [],
        filters: { policy_id: null, outcome: null, limit: 200 },
        unfiltered_findings: 0,
        total_findings: 0,
        returned: 0,
        more: 0,
        capped: false,
        findings: [],
        resolved_by_version_change: { window_days: 7, total_last_7_days: 0, returned: 0, more: 0, capped: false, last_7_days: [], by_version: [] },
      });
    }
    return reply(404, { error: "NOT_FOUND", reason: "no such route in this test" });
  }));

  return sent;
}

async function openAddZoneDialog() {
  render(<ZoneMatrixScreen />);
  fireEvent.click(await screen.findByRole("tab", { name: "Zones" }));
  fireEvent.click(await screen.findByRole("button", { name: "Add zone" }));
  return screen.findByRole("dialog");
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/");
});

describe("ZonesTab IP range to CIDR decomposition helper", () => {
  it("previews the exact canonical CIDR list before saving and appends without submitting", async () => {
    const sent = serveZones(EDITOR_CAN);
    const dialog = await openAddZoneDialog();

    const blocksField = within(dialog).getByLabelText("Blocks") as HTMLTextAreaElement;
    fireEvent.change(blocksField, { target: { value: "203.0.113.0/25" } });

    const rangeInput = within(dialog).getByPlaceholderText("192.0.2.10-192.0.2.25 or 2001:db8::1-2001:db8::10") as HTMLInputElement;
    const addButton = within(dialog).getByRole("button", { name: "Add decomposed CIDRs to blocks" });
    expect(addButton).toBeDisabled();

    fireEvent.change(rangeInput, { target: { value: "192.0.2.10-192.0.2.25" } });

    const preview = within(dialog).getByTestId("cidr-decomposition-preview");
    expect(preview).toHaveTextContent("4 canonical CIDR blocks (16 addresses)");
    const previewList = within(preview).getByRole("list", { name: "Decomposed CIDR preview" });
    expect(within(previewList).getAllByRole("listitem").map((li) => li.textContent)).toEqual([
      "192.0.2.10/31",
      "192.0.2.12/30",
      "192.0.2.16/29",
      "192.0.2.24/31",
    ]);
    expect(addButton).toBeEnabled();
    expect(sent.filter((req) => req.method === "POST")).toHaveLength(0);

    fireEvent.click(addButton);

    expect(blocksField.value).toBe(
      "203.0.113.0/25\n192.0.2.10/31\n192.0.2.12/30\n192.0.2.16/29\n192.0.2.24/31",
    );
    expect(rangeInput.value).toBe("");
    expect(within(dialog).queryByTestId("cidr-decomposition-preview")).not.toBeInTheDocument();
    expect(addButton).toBeDisabled();
    expect(sent.filter((req) => req.method === "POST")).toHaveLength(0);
  });

  it("shows an inline alert and disables the add button on invalid, reversed, or non-canonical CIDR input", async () => {
    serveZones(EDITOR_CAN);
    const dialog = await openAddZoneDialog();

    const rangeInput = within(dialog).getByPlaceholderText("192.0.2.10-192.0.2.25 or 2001:db8::1-2001:db8::10");
    const addButton = within(dialog).getByRole("button", { name: "Add decomposed CIDRs to blocks" });

    fireEvent.change(rangeInput, { target: { value: "192.0.2.25-192.0.2.10" } });
    expect(within(dialog).getByRole("alert")).toHaveTextContent("start address must be less than or equal to the end address");
    expect(addButton).toBeDisabled();

    fireEvent.change(rangeInput, { target: { value: "192.0.2.7/24" } });
    expect(within(dialog).getByRole("alert")).toHaveTextContent("CIDR notation (/prefix) is not an IP range");
    expect(addButton).toBeDisabled();

    fireEvent.change(rangeInput, { target: { value: "not-a-range" } });
    expect(within(dialog).getByRole("alert")).toBeInTheDocument();
    expect(addButton).toBeDisabled();
  });

  it("never renders the editor or range helper in aiview / read-only mode", async () => {
    serveZones(AIVIEW_CAN);
    render(<ZoneMatrixScreen />);
    fireEvent.click(await screen.findByRole("tab", { name: "Zones" }));
    await screen.findByRole("table", { name: "Zones" });

    expect(screen.queryByRole("button", { name: "Add zone" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit" })).not.toBeInTheDocument();
    expect(screen.queryByTestId("cidr-decomposition-helper")).not.toBeInTheDocument();
    expect(screen.queryByText("Decompose IP range")).not.toBeInTheDocument();
  });
});
