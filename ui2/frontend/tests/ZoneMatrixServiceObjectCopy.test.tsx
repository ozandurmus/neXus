import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { listPolicies, responsesAreMasked, type PolicyInventoryObject, type PolicyMetadata, type PolicyObject } from "../src/auth/adminApi";
import { CellDetailPanel } from "../src/screens/zonematrix/MatrixCellDetail";
import { MatrixTab } from "../src/screens/zonematrix/MatrixTab";
import {
  extractServiceObjectPorts,
  mergeCopiedServiceEntries,
  resolveServiceObjectPorts,
} from "../src/screens/zonematrix/serviceObjectPorts";
import type { ZoneMatrixCan, ZoneMatrixSummaryResponse, ZoneMatrixView, ZoneVersionDetail } from "../src/screens/zonematrix/zoneMatrixApi";

const HASH_ACTIVE = "1111111111111111111111111111111111111111111111111111111111111111";
const HASH_DRAFT = "2222222222222222222222222222222222222222222222222222222222222222";
const HASH_CELL_1 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

const sizes = (ipv4: string, ipv6 = "0") => ({ IPV4: ipv4, IPV6: ipv6 });

const baseMatrix: ZoneMatrixView = {
  default_statement: "DENY_ALL",
  default_severity: "MEDIUM",
  unassigned_to_unassigned: "DENY_ALL",
  template_origin: null,
  service_sets: [],
  cells: [
    {
      source: "z1",
      destination: "z2",
      source_name: "Z-APP",
      destination_name: "Z-DB",
      statement: "ALLOW_LISTED",
      services: [{ entry: "tcp/5432", evaluable: true, not_evaluable_reason: null }],
      severity: "HIGH",
      rationale: "App to DB",
      regulation_reference: "SEC-POL-4.2",
      reference_status: "PROPOSED",
      confirmed_by: null,
      confirmed_at: null,
      cell_sha256: HASH_CELL_1,
    },
    {
      source: "z2",
      destination: "z1",
      source_name: "Z-DB",
      destination_name: "Z-APP",
      statement: "DENY_ALL",
      services: [],
      severity: "CRITICAL",
      rationale: null,
      regulation_reference: "",
      reference_status: "EMPTY",
      confirmed_by: null,
      confirmed_at: null,
      cell_sha256: HASH_ACTIVE,
    },
  ],
  cells_total: 2,
  cells_with_reference: 1,
  cells_confirmed: 0,
};

const draftVersion: ZoneVersionDetail = {
  version_id: "v-draft",
  number: null,
  state: "DRAFT",
  revision: 7,
  content_sha256: HASH_DRAFT,
  created_at: "2026-11-26T09:00:00Z",
  updated_at: "2026-11-26T10:00:00Z",
  published_by: null,
  published_at: null,
  zones: [
    { zone_id: "z1", name: "Z-APP", description: "Application tier", role: "APPLICATION", block_count: 1, blocks: null, effective_addresses: sizes("256") },
    { zone_id: "z2", name: "Z-DB", description: "Database tier", role: "DATABASE", block_count: 1, blocks: null, effective_addresses: sizes("256") },
  ],
  unassigned_addresses: sizes("4294966784"),
  redundant_blocks: 0,
  revisions: [],
  matrix: baseMatrix,
};

const FULL_CAN: ZoneMatrixCan = { view_blocks: true, edit: true, publish: true, confirm_reference: true };
const READ_CAN: ZoneMatrixCan = { view_blocks: false, edit: false, publish: false, confirm_reference: false };

const samplePolicies: PolicyMetadata[] = [
  {
    id: "pol-cp-1",
    sourceId: "src-cp-1",
    sourceName: "CLS-ROMEO-01",
    vendor: "CP",
    containerId: "dom-1",
    containerName: "DOM-CORE",
    name: "Standard",
    collectedAt: "2026-11-26T08:00:00Z",
    artefactRef: "art-1",
    targets: [],
  },
];

const invObj = (id: string, name: string, type: string, uid = id): PolicyInventoryObject => ({
  id,
  uid,
  name,
  type,
  unused: false,
  emptyGroup: false,
  singleMember: false,
  duplicateId: null,
  ruleCount: 2,
  groupCount: 0,
});

interface FetchCall {
  readonly method: string;
  readonly path: string;
  readonly body: unknown;
}

const json = (body: unknown, status = 200, headers?: HeadersInit) => new Response(JSON.stringify(body), {
  status, headers: { "Content-Type": "application/json", ...headers },
});
const objectPage = (objects: PolicyInventoryObject[], total = objects.length, types: unknown[] = []) =>
  json({ objects, total, page: 0, pageSize: 200, types });

const summaryStub = (policyNamesMasked = false) => ({
  data: {
    evaluated_at: null, state: "UNKNOWN", state_text: "No stored evaluation",
    masked: false, policy_names_masked: policyNamesMasked, witnesses_included: false,
    staleness_threshold: { seconds: 86400, hours: 24, interim: true, source: "Interim", label: "Interim" },
    active_version: null, headline: null, headline_absent_reason: "No stored evaluation", policies: [], not_evaluated: [],
    filters: { policy_id: null, outcome: null, limit: 200 }, unfiltered_findings: 0, total_findings: 0,
    returned: 0, more: 0, capped: false, findings: [],
    resolved_by_version_change: { window_days: 7, total_last_7_days: 0, returned: 0, more: 0, capped: false, last_7_days: [], by_version: [] },
  } satisfies ZoneMatrixSummaryResponse,
  loading: false, error: null, rawError: null, params: {}, setParams: vi.fn(), ensure: vi.fn(), reload: vi.fn(),
});

const matrixEditor = (policyNamesMasked = false, draft = draftVersion) => (
  <MatrixTab data={{
    draft: { active: null, draft, diff: null, publish_check: { eligible: true, reason: null }, can: FULL_CAN },
    active: null, versions: [], blocks: false,
  }} refresh={vi.fn()} notify={vi.fn()} reading={false} summary={summaryStub(policyNamesMasked)} onOpenCellViolations={vi.fn()} />
);

function stubPolicyAndMatrixFetch(
  objectDetails: Record<string, PolicyObject>,
  listedServices: PolicyInventoryObject[] = [
    invObj("svc-web", "SVC-WEB-PORTS", "service-tcp"),
    invObj("svc-srcport", "SVC-SRC-NARROWED", "service-tcp"),
  ],
  listedGroups: PolicyInventoryObject[] = [
    invObj("grp-app", "GRP-APP-PORTS", "service-group"),
  ],
  override?: (url: URL) => Response | Promise<Response> | undefined,
): FetchCall[] {
  const calls: FetchCall[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      const method = (init?.method ?? "GET").toUpperCase();
      const body = typeof init?.body === "string" ? JSON.parse(init.body) : null;
      calls.push({ method, path, body });

      const url = new URL(path, window.location.origin);
      if (method === "GET") {
        const response = override?.(url);
        if (response) return response;
      }

      if (method === "GET" && path === "/session/status") {
        return new Response(JSON.stringify({ csrf_token: "tok-1" }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (method === "GET" && path === "/api/v2/policy/devices") {
        return new Response(JSON.stringify({ policies: samplePolicies, devices: [] }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      if (method === "GET" && url.pathname.endsWith("/object-usage")) {
        const uid = url.searchParams.get("uid");
        const row = [...listedServices, ...listedGroups].find((item) => item.uid === uid);
        const detail = row && objectDetails[row.id];
        if (!detail) return json({ error: "NOT_FOUND" }, 404);
        const { children: _children, ...object } = detail; // The usage route returns a row, not a tree.
        return json({ object: { ...row, ...object }, rules: [], groups: [] });
      }
      if (method === "GET" && path.startsWith("/api/v2/policy/domains/")) {
        const isGroup = path.includes("type=service-group");
        const qMatch = /[?&]q=([^&]*)/.exec(path);
        const q = qMatch ? decodeURIComponent(qMatch[1]).toLowerCase() : "";
        const source = isGroup ? listedGroups : listedServices;
        const filtered = q ? source.filter((o) => o.name.toLowerCase().includes(q)) : source;
        return new Response(
          JSON.stringify({ objects: filtered, total: filtered.length, page: 0, pageSize: 50, types: [] }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        );
      }
      if (method === "GET" && path.startsWith("/api/v2/policy/objects/")) {
        const idPart = path.slice("/api/v2/policy/objects/".length).split("?")[0];
        const obj = objectDetails[decodeURIComponent(idPart)];
        if (!obj) {
          return new Response(JSON.stringify({ error: "NOT_FOUND" }), {
            status: 404,
            headers: { "Content-Type": "application/json" },
          });
        }
        return new Response(JSON.stringify({ object: obj }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      return new Response(
        JSON.stringify({ version_id: "v-draft", revision: 8, content_sha256: HASH_DRAFT, zone_id: null }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      );
    }),
  );
  return calls;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("extractServiceObjectPorts & resolveServiceObjectPorts (pure extractor)", () => {
  it("extracts Check Point service-tcp and service-udp and normalizes N-N ranges", () => {
    const tcpObj: PolicyObject = {
      id: "cp-1",
      name: "HTTPS-ALT",
      type: "service-tcp",
      status: "RESOLVED",
      values: ["protocol: TCP", "port: 443-443, 8443, 8000-8099"],
    };
    expect(extractServiceObjectPorts(tcpObj)).toEqual({
      ok: true,
      entries: ["tcp/443", "tcp/8000-8099", "tcp/8443"],
    });

    const udpObj: PolicyObject = {
      id: "cp-2",
      name: "DNS-UDP",
      type: "service-udp",
      status: "RESOLVED",
      values: ["protocol: udp", "port: 53"],
    };
    expect(extractServiceObjectPorts(udpObj)).toEqual({
      ok: true,
      entries: ["udp/53"],
    });
  });

  it("extracts Palo Alto multi-family protocol/tcp/port and protocol/udp/port with sorting and adjacent/overlap merging", () => {
    const panObj: PolicyObject = {
      id: "pan-1",
      name: "SVC-PAN-MULTI",
      type: "service",
      status: "RESOLVED",
      values: [
        "protocol/tcp/port: 8081, 8080, 443, 8082-8090, 8085-8095",
        "protocol/udp/port: 53, 54, 123",
      ],
    };
    expect(extractServiceObjectPorts(panObj)).toEqual({
      ok: true,
      entries: ["tcp/443", "tcp/8080-8095", "udp/53-54", "udp/123"],
    });
  });

  it("extracts ASA and FortiGate resolved TCP/UDP services", () => {
    const asaObj: PolicyObject = {
      id: "asa-1",
      name: "ASA-SQL",
      type: "service",
      status: "RESOLVED",
      values: ["protocol: tcp", "port: 1433, 1434"],
    };
    expect(extractServiceObjectPorts(asaObj)).toEqual({
      ok: true,
      entries: ["tcp/1433-1434"],
    });

    const fgtObj: PolicyObject = {
      id: "fgt-1",
      name: "FGT-SYSLOG",
      type: "service",
      status: "RESOLVED",
      values: ["protocol: udp", "port: 514"],
    };
    expect(extractServiceObjectPorts(fgtObj)).toEqual({
      ok: true,
      entries: ["udp/514"],
    });
  });

  it("expands nested Check Point service groups recursively and merges ports", async () => {
    const leafA: PolicyObject = {
      id: "leaf-a",
      name: "TCP-443",
      type: "service-tcp",
      status: "RESOLVED",
      values: ["protocol: tcp", "port: 443"],
    };
    const leafB: PolicyObject = {
      id: "leaf-b",
      name: "TCP-444",
      type: "service-tcp",
      status: "RESOLVED",
      values: ["protocol: tcp", "port: 444"],
    };
    const leafC: PolicyObject = {
      id: "leaf-c",
      name: "UDP-53",
      type: "service-udp",
      status: "RESOLVED",
      values: ["protocol: udp", "port: 53"],
    };
    const subGroup: PolicyObject = {
      id: "sub-grp",
      name: "CP-SUBGROUP",
      type: "service-group",
      status: "RESOLVED",
      members: ["leaf-b", "leaf-c"],
    };
    const rootGroup: PolicyObject = {
      id: "root-grp",
      name: "GRP-WEB-DNS",
      type: "service-group",
      status: "RESOLVED",
      members: ["leaf-a", "sub-grp"],
      children: [leafA],
    };

    const store: Record<string, PolicyObject> = {
      "leaf-b": leafB,
      "leaf-c": leafC,
      "sub-grp": subGroup,
    };
    const res = await resolveServiceObjectPorts(rootGroup, async (id) => store[id] ?? null);
    expect(res).toEqual({
      ok: true,
      entries: ["tcp/443-444", "udp/53"],
    });
  });

  it("fails closed on empty groups, cycles, and depth > 32", async () => {
    const emptyGroup: PolicyObject = {
      id: "g-empty",
      name: "GRP-EMPTY",
      type: "service-group",
      status: "RESOLVED",
      children: [],
    };
    const emptyRes = extractServiceObjectPorts(emptyGroup);
    expect(emptyRes.ok).toBe(false);
    if (!emptyRes.ok) expect(emptyRes.reason).toMatch(/empty/i);

    const cyclicA: PolicyObject = {
      id: "g-cyc-a",
      name: "GRP-CYC-A",
      type: "service-group",
      status: "RESOLVED",
      members: ["g-cyc-b"],
    };
    const cyclicB: PolicyObject = {
      id: "g-cyc-b",
      name: "GRP-CYC-B",
      type: "service-group",
      status: "RESOLVED",
      members: ["g-cyc-a"],
    };
    const cycRes = await resolveServiceObjectPorts(cyclicA, async (id) => (id === "g-cyc-b" ? cyclicB : cyclicA));
    expect(cycRes.ok).toBe(false);
    if (!cycRes.ok) expect(cycRes.reason).toMatch(/cyclic/i);

    const deepMap: Record<string, PolicyObject> = {};
    for (let i = 0; i <= 33; i += 1) {
      deepMap[`g-${i}`] = {
        id: `g-${i}`,
        name: `GRP-${i}`,
        type: "service-group",
        status: "RESOLVED",
        members: [i < 33 ? `g-${i + 1}` : "leaf-end"],
      };
    }
    deepMap["leaf-end"] = {
      id: "leaf-end",
      name: "LEAF",
      type: "service-tcp",
      status: "RESOLVED",
      values: ["protocol: tcp", "port: 443"],
    };
    const deepRes = await resolveServiceObjectPorts(deepMap["g-0"], async (id) => deepMap[id] ?? null);
    expect(deepRes.ok).toBe(false);
    if (!deepRes.ok) expect(deepRes.reason).toMatch(/depth/i);
  });

  it("fails closed on source-port constraints, ICMP/IP-protocol/SCTP, non-RESOLVED objects, and invalid ports", () => {
    const flatSrcPort: PolicyObject = {
      id: "sp-1",
      name: "SVC-SRC",
      type: "service-tcp",
      status: "RESOLVED",
      values: ["protocol: tcp", "port: 443", "source-port: 1024-65535"],
    };
    const r1 = extractServiceObjectPorts(flatSrcPort);
    expect(r1.ok).toBe(false);
    if (!r1.ok) expect(r1.reason).toMatch(/source-port/i);

    const panSrcPort: PolicyObject = {
      id: "sp-2",
      name: "SVC-PAN-SRC",
      type: "service",
      status: "RESOLVED",
      values: ["protocol/tcp/port: 443", "protocol/tcp/source-port: 1024-65535"],
    };
    const r2 = extractServiceObjectPorts(panSrcPort);
    expect(r2.ok).toBe(false);
    if (!r2.ok) expect(r2.reason).toMatch(/source-port/i);

    const icmpObj: PolicyObject = {
      id: "icmp-1",
      name: "SVC-ICMP",
      type: "service-icmp",
      status: "RESOLVED",
      values: ["protocol: icmp", "icmp-type: 8", "icmp-code: 0"],
    };
    expect(extractServiceObjectPorts(icmpObj).ok).toBe(false);

    const ipProtoObj: PolicyObject = {
      id: "ipproto-1",
      name: "SVC-ESP",
      type: "service",
      status: "RESOLVED",
      values: ["protocol: 6", "port: 443"],
    };
    expect(extractServiceObjectPorts(ipProtoObj).ok).toBe(false);

    const sctpObj: PolicyObject = {
      id: "sctp-1",
      name: "SVC-SCTP",
      type: "service",
      status: "RESOLVED",
      values: ["protocol/sctp/port: 3868"],
    };
    expect(extractServiceObjectPorts(sctpObj).ok).toBe(false);

    const extraKeyObj: PolicyObject = {
      id: "extra-1",
      name: "SVC-MATCH-ANY",
      type: "service-tcp",
      status: "RESOLVED",
      values: ["protocol: tcp", "port: 443", "match-for-any: true"],
    };
    expect(extractServiceObjectPorts(extraKeyObj).ok).toBe(false);

    const unresolvedObj: PolicyObject = {
      id: "unres-1",
      name: "SVC-UNRES",
      type: "service-tcp",
      status: "UNRESOLVED",
      values: ["protocol: tcp", "port: 443"],
    };
    expect(extractServiceObjectPorts(unresolvedObj).ok).toBe(false);

    // ServiceEntry.java explicitly accepts ports 0-65535; verify 0 succeeds and 65536 / reversed / >1023 fail closed.
    const portZeroObj: PolicyObject = {
      id: "p0",
      name: "SVC-PORT-ZERO",
      type: "service-tcp",
      status: "RESOLVED",
      values: ["protocol: tcp", "port: 0"],
    };
    expect(extractServiceObjectPorts(portZeroObj)).toEqual({ ok: true, entries: ["tcp/0"] });

    const outOfRangeObj: PolicyObject = {
      id: "p-oob",
      name: "SVC-OOB",
      type: "service-tcp",
      status: "RESOLVED",
      values: ["protocol: tcp", "port: 65536"],
    };
    expect(extractServiceObjectPorts(outOfRangeObj).ok).toBe(false);

    const reversedObj: PolicyObject = {
      id: "p-rev",
      name: "SVC-REV",
      type: "service-tcp",
      status: "RESOLVED",
      values: ["protocol: tcp", "port: 8099-8000"],
    };
    expect(extractServiceObjectPorts(reversedObj).ok).toBe(false);

    const cmpObj: PolicyObject = {
      id: "p-cmp",
      name: "SVC-CMP",
      type: "service-tcp",
      status: "RESOLVED",
      values: ["protocol: tcp", "port: >1023"],
    };
    expect(extractServiceObjectPorts(cmpObj).ok).toBe(false);
  });

  it("merges copied entries into existing textarea without duplicating lines or embedding vendor names", () => {
    const merged = mergeCopiedServiceEntries("tcp/5432\ntcp/443\n", ["tcp/443", "tcp/8443", "udp/53"]);
    expect(merged).toEqual({
      text: "tcp/5432\ntcp/443\ntcp/8443\nudp/53",
      added: 2,
    });
    expect(merged.text).not.toContain("SVC-");

    const noOp = mergeCopiedServiceEntries("tcp/443", ["tcp/443"]);
    expect(noOp).toEqual({ text: "tcp/443", added: 0 });
  });
});

describe("CellDetailPanel and MatrixTab service-object copy UI", () => {
  it("makes no /api/v2/policy/devices call until opened, copies canonical ports into CellDetailPanel, and saves them", async () => {
    const calls = stubPolicyAndMatrixFetch({
      "svc-web": {
        id: "svc-web",
        name: "SVC-WEB-PORTS",
        type: "service-tcp",
        status: "RESOLVED",
        values: ["protocol: tcp", "port: 443, 8443"],
      },
      "svc-srcport": {
        id: "svc-srcport",
        name: "SVC-SRC-NARROWED",
        type: "service-tcp",
        status: "RESOLVED",
        values: ["protocol: tcp", "port: 443", "source-port: 1024-65535"],
      },
    });

    const onRefresh = vi.fn();
    render(
      <CellDetailPanel
        pair={{ sourceId: "z1", sourceName: "Z-APP", destinationId: "z2", destinationName: "Z-DB" }}
        explicitCell={baseMatrix.cells[0]}
        matrix={baseMatrix}
        isMasked={false}
        editable={true}
        canCopyServicePorts
        canConfirm={false}
        revision={7}
        onRefresh={onRefresh}
        onChanged={vi.fn()}
        notify={vi.fn()}
      />,
    );

    expect(calls.some((c) => c.path === "/api/v2/policy/devices")).toBe(false);

    fireEvent.click(screen.getByTestId("toggle-copy-service-object"));
    const webBtn = await screen.findByTestId("select-service-object-svc-web");
    expect(calls.some((c) => c.path === "/api/v2/policy/devices")).toBe(true);

    // Refused object displays an alert and leaves the Services textarea unchanged.
    fireEvent.click(screen.getByTestId("select-service-object-svc-srcport"));
    const refusal = await screen.findByTestId("copy-service-refusal");
    expect(refusal).toHaveTextContent(/source-port/i);
    expect(screen.queryByTestId("confirm-copy-service-ports")).not.toBeInTheDocument();
    expect((screen.getByLabelText("Services") as HTMLTextAreaElement).value).toBe("tcp/5432");

    // Resolved object previews canonical entries and copies them without linking the vendor object.
    fireEvent.click(webBtn);
    const preview = await screen.findByTestId("resolved-service-ports-preview");
    expect(preview).toHaveTextContent("tcp/443, tcp/8443");

    fireEvent.click(screen.getByTestId("confirm-copy-service-ports"));
    expect(screen.getByTestId("copy-service-status")).toHaveTextContent(
      "Copied 2 service entries from SVC-WEB-PORTS (resolved values only; vendor object not linked).",
    );
    expect((screen.getByLabelText("Services") as HTMLTextAreaElement).value).toBe("tcp/5432\ntcp/443\ntcp/8443");

    fireEvent.click(screen.getByRole("button", { name: "Save cell" }));
    await vi.waitFor(() => {
      const saveCall = calls.find((c) => c.method === "POST" && c.path === "/api/v2/zone-matrix/draft/cells");
      expect(saveCall?.body).toMatchObject({
        source: "z1",
        destination: "z2",
        statement: "ALLOW_LISTED",
        services: ["tcp/5432", "tcp/443", "tcp/8443"],
      });
    });
  });

  it("copies resolved service-group ports into MatrixTab ServiceSetsSection and saves canonical entries", async () => {
    const calls = stubPolicyAndMatrixFetch({
      "grp-app": {
        id: "grp-app",
        name: "GRP-APP-PORTS",
        type: "service-group",
        status: "RESOLVED",
        members: ["m1", "m2"],
        children: [
          { id: "m1", name: "HTTPS", type: "service-tcp", status: "RESOLVED", values: ["protocol: tcp", "port: 443"] },
          { id: "m2", name: "DNS", type: "service-udp", status: "RESOLVED", values: ["protocol: udp", "port: 53"] },
        ],
      },
    });

    render(matrixEditor());

    expect(calls.some((c) => c.path === "/api/v2/policy/devices")).toBe(false);

    const setsSection = screen.getByRole("region", { name: "Service sets" });
    fireEvent.click(within(setsSection).getByTestId("toggle-copy-service-object"));
    const grpBtn = await within(setsSection).findByTestId("select-service-object-grp-app");
    fireEvent.click(grpBtn);

    const preview = await within(setsSection).findByTestId("resolved-service-ports-preview");
    expect(preview).toHaveTextContent("tcp/443, udp/53");

    fireEvent.click(within(setsSection).getByTestId("confirm-copy-service-ports"));
    expect(within(setsSection).getByTestId("copy-service-status")).toHaveTextContent(
      "Copied 2 service entries from GRP-APP-PORTS (resolved values only; vendor object not linked).",
    );

    fireEvent.change(within(setsSection).getByLabelText("Service set name"), { target: { value: "app-core" } });
    fireEvent.click(within(setsSection).getByRole("button", { name: "Save service set" }));

    await vi.waitFor(() => {
      const saveCall = calls.find((c) => c.method === "POST" && c.path === "/api/v2/zone-matrix/draft/service-sets");
      expect(saveCall?.body).toEqual({
        revision: 7,
        name: "app-core",
        entries: ["tcp/443", "udp/53"],
      });
    });
  });

  it("never renders the helper for non-editable, masked (aiview), or non-ALLOW_LISTED views", () => {
    stubPolicyAndMatrixFetch({});

    // Non-editable CellDetailPanel
    const { rerender } = render(
      <CellDetailPanel
        pair={{ sourceId: "z1", sourceName: "Z-APP", destinationId: "z2", destinationName: "Z-DB" }}
        explicitCell={baseMatrix.cells[0]}
        matrix={baseMatrix}
        isMasked={false}
        editable={false}
        canConfirm={false}
        revision={7}
        onRefresh={vi.fn()}
        onChanged={vi.fn()}
        notify={vi.fn()}
      />,
    );
    expect(screen.queryByTestId("toggle-copy-service-object")).not.toBeInTheDocument();

    // Masked CellDetailPanel even if editable flag were true
    rerender(
      <CellDetailPanel
        pair={{ sourceId: "z1", sourceName: "ZONE-1", destinationId: "z2", destinationName: "ZONE-2" }}
        explicitCell={baseMatrix.cells[0]}
        matrix={baseMatrix}
        isMasked={true}
        editable={true}
        canConfirm={false}
        revision={7}
        onRefresh={vi.fn()}
        onChanged={vi.fn()}
        notify={vi.fn()}
      />,
    );
    expect(screen.queryByTestId("toggle-copy-service-object")).not.toBeInTheDocument();

    // Editable DENY_ALL CellDetailPanel
    rerender(
      <CellDetailPanel
        pair={{ sourceId: "z2", sourceName: "Z-DB", destinationId: "z1", destinationName: "Z-APP" }}
        explicitCell={baseMatrix.cells[1]}
        matrix={baseMatrix}
        isMasked={false}
        editable={true}
        canConfirm={false}
        revision={7}
        onRefresh={vi.fn()}
        onChanged={vi.fn()}
        notify={vi.fn()}
      />,
    );
    expect(screen.queryByTestId("toggle-copy-service-object")).not.toBeInTheDocument();

    // Masked MatrixTab (aiview: ZONE-1, SET-1, READ_CAN)
    const maskedVersion: ZoneVersionDetail = {
      ...draftVersion,
      state: "ACTIVE",
      number: 1,
      zones: [
        { zone_id: "z1", name: "ZONE-1", description: null, role: "APPLICATION", block_count: 1, blocks: null, effective_addresses: sizes("256") },
        { zone_id: "z2", name: "ZONE-2", description: null, role: "DATABASE", block_count: 1, blocks: null, effective_addresses: sizes("256") },
      ],
      matrix: {
        ...baseMatrix,
        service_sets: [{ name: "SET-1", entries: [{ entry: "tcp/443", evaluable: true, not_evaluable_reason: null }] }],
      },
    };
    render(
      <MatrixTab
        data={{
          draft: { active: null, draft: null, diff: null, publish_check: { eligible: false, reason: null }, can: READ_CAN },
          active: maskedVersion,
          versions: [],
          blocks: false,
        }}
        refresh={vi.fn()}
        notify={vi.fn()}
        reading={false}
        summary={{
          data: null,
          loading: false,
          error: null,
          rawError: null,
          params: {},
          setParams: vi.fn(),
          ensure: vi.fn(),
          reload: vi.fn(),
        }}
        onOpenCellViolations={vi.fn()}
      />,
    );
    expect(screen.queryByTestId("toggle-copy-service-object")).not.toBeInTheDocument();
  });
});

const openCellPicker = () => {
  fireEvent.click(screen.getByTestId("matrix-cell-z1-z2"));
  fireEvent.click(screen.getAllByTestId("toggle-copy-service-object")[0]);
};

const deferredResponse = () => {
  let resolve!: (response: Response) => void;
  const promise = new Promise<Response>((done) => { resolve = done; });
  return { promise, resolve };
};

const cpLeaf = (id: string, name: string, values: string[], type = "service-tcp") =>
  ({ id, name, type, status: "RESOLVED", values });

describe("service-object copy review regressions", () => {
  it("reads Check Point inventory via the Objects drawer UID, not a differently scoped snapshot ID", async () => {
    const web = invObj("domain-web-ref", "CP-WEB", "service-tcp", "uid-web");
    const unreadable = invObj("domain-other-ref", "CP-OTHER", "service-tcp", "uid-other");
    const calls = stubPolicyAndMatrixFetch({
      [web.id]: cpLeaf(web.id, web.name, ["port: 8443"]),
      "package-web-ref": cpLeaf("package-web-ref", web.name, ["port: 9443"]),
    }, [web, unreadable], []);
    render(matrixEditor());
    openCellPicker();
    fireEvent.click(await screen.findByTestId(`select-service-object-${web.id}`));
    expect(await screen.findByTestId("resolved-service-ports-preview")).toHaveTextContent("tcp/8443");
    expect(calls.map(({ path }) => path)).toContain("/api/v2/policy/domains/dom-1/object-usage?source=src-cp-1&page=0&q=&uid=uid-web");
    expect(calls.some(({ path }) => path.startsWith("/api/v2/policy/objects/"))).toBe(false);

    fireEvent.click(screen.getByTestId(`select-service-object-${unreadable.id}`));
    expect(await screen.findByTestId("copy-service-refusal")).toHaveTextContent("These ports cannot be read for this object here");
    expect(screen.queryByTestId("confirm-copy-service-ports")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Services")).toHaveValue("tcp/5432");
  });

  it("resolves UID members in domain usage and joins ordered inventory expansion children with different IDs", async () => {
    const group = invObj("domain-group-ref", "CP-GROUP", "service-group", "uid-group");
    const web = invObj("domain-web-ref", "CP-WEB", "service-tcp", "uid-web");
    const dns = invObj("domain-dns-ref", "CP-DNS", "service-udp", "uid-dns");
    const domainGroup = { ...group, status: "RESOLVED", members: [web.uid, dns.uid] };
    const tcp = cpLeaf(web.id, web.name, ["port: 8443"]);
    const udp = cpLeaf(dns.id, dns.name, ["port: 53"], dns.type);
    const calls = stubPolicyAndMatrixFetch({ [group.id]: domainGroup, [web.id]: tcp, [dns.id]: udp }, [web, dns], [group]);
    render(matrixEditor());
    openCellPicker();
    fireEvent.click(await screen.findByTestId(`select-service-object-${group.id}`));
    expect(await screen.findByTestId("resolved-service-ports-preview")).toHaveTextContent("tcp/8443, udp/53");
    expect(calls.filter(({ path }) => path.includes("object-usage")).map(({ path }) => path.split("&uid=")[1]))
      .toEqual([group.uid, web.uid, dns.uid]);

    const expanded = { ...domainGroup, resolution: "DOMAIN_INVENTORY", children: [tcp, udp].map((leaf) => ({ ...leaf, resolution: "DOMAIN_INVENTORY" })) };
    const expected = { ok: true, entries: ["tcp/8443", "udp/53"] };
    expect(extractServiceObjectPorts(expanded)).toEqual(expected);
    const fetchMember = vi.fn(async () => null);
    expect(await resolveServiceObjectPorts(expanded, fetchMember)).toEqual(expected);
    expect(fetchMember).not.toHaveBeenCalled();
    expect(extractServiceObjectPorts({ ...expanded, totalMembers: 3 }).ok).toBe(false);
  });

  it("takes native Check Point TCP/UDP transport from the type, with or without a handler field", () => {
    expect(extractServiceObjectPorts(cpLeaf("cp-tcp", "CP-TCP", ["port: 8443", "protocol: HTTP"])))
      .toEqual({ ok: true, entries: ["tcp/8443"] });
    expect(extractServiceObjectPorts(cpLeaf("cp-udp", "CP-UDP", ["port: 53", "port-range: 54"], "service-udp")).ok)
      .toBe(false);
    expect(extractServiceObjectPorts(cpLeaf("cp-udp", "CP-UDP", ["port: 53"], "service-udp")))
      .toEqual({ ok: true, entries: ["udp/53"] });
  });

  it("hides both helpers from a matrix editor without policy read", () => {
    const calls = stubPolicyAndMatrixFetch({});
    render(matrixEditor(true));
    fireEvent.click(screen.getByTestId("matrix-cell-z1-z2"));
    expect(screen.getByLabelText("Services")).toBeEnabled();
    expect(screen.getByLabelText("Service set entries")).toBeEnabled();
    expect(screen.queryByTestId("toggle-copy-service-object")).not.toBeInTheDocument();
    expect(calls.some(({ path }) => path.startsWith("/api/v2/policy/"))).toBe(false);
  });

  it("offers retry for failed loads, labels incomplete inventory, and counts omitted rows using both totals", async () => {
    let attempts = 0;
    const web = invObj("ref-web", "CP-WEB", "service-tcp");
    stubPolicyAndMatrixFetch({}, [], [], (url) => {
      if (url.pathname.endsWith("/devices") && ++attempts === 1) return json({ error: "POLICIES_UNAVAILABLE" }, 500);
      if (!url.pathname.endsWith("/objects")) return;
      if (attempts === 2) return json({ error: "OBJECTS_UNAVAILABLE" }, 503);
      if (url.searchParams.get("q") === "incomplete") return objectPage([], 0, [{ type: "services-tcp", status: "UNKNOWN" }]);
      return url.searchParams.get("type") === "service" ? objectPage([web], 206) : objectPage([], 3);
    });
    render(matrixEditor());
    openCellPicker();
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not load collected policies");
    expect(screen.queryByText("No collected policies available.")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Retry policy load" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not search service objects");
    expect(screen.queryByText("No matching service objects in this policy domain.")).not.toBeInTheDocument();
    attempts += 1;
    fireEvent.click(screen.getByRole("button", { name: "Retry search" }));
    expect(await screen.findByText("+208 more (refine the search)")).toBeInTheDocument();
    expect(screen.getByTestId("select-service-object-ref-web")).toBeVisible();

    fireEvent.change(screen.getByLabelText("Search service object"), { target: { value: "incomplete" } });
    fireEvent.click(screen.getByRole("button", { name: /^Search$/ }));
    expect(await screen.findByText(/inventory is incomplete/i)).toHaveTextContent("services-tcp");
    expect(screen.queryByText("No matching service objects in this policy domain.")).not.toBeInTheDocument();
  });

  it("refuses group values and Check Point address groups before resolving any member", async () => {
    const leaf = cpLeaf("leaf", "CP-WEB", ["port: 8443"]);
    const group = { id: "ref-group", name: "CP-GROUP", type: "service-group", status: "RESOLVED", members: [leaf.id], children: [leaf] };
    const fetchMember = vi.fn(async () => leaf);
    const unsupported: [PolicyObject, RegExp][] = [
      [{ ...group, values: ["except: uid-excluded"] }, /dynamic or exclusion/],
      [{ ...group, type: "group" }, /Unsupported object type/],
    ];
    for (const [object, reason] of unsupported) {
      expect(extractServiceObjectPorts(object)).toEqual({ ok: false, reason: expect.stringMatching(reason) });
      expect(await resolveServiceObjectPorts(object, fetchMember)).toEqual({ ok: false, reason: expect.stringMatching(reason) });
    }
    expect(fetchMember).not.toHaveBeenCalled();
  });

  it("ignores Enter while loading and discards stale search and inspection replies after policy/query changes", async () => {
    const oldSearch = deferredResponse();
    const oldInspection = deferredResponse();
    const web = invObj("web-a", "CP-WEB", "service-tcp");
    const newRow = invObj("web-b", "CP-WEB-B", "service-tcp");
    const calls = stubPolicyAndMatrixFetch({}, [web], [], (url) => {
      if (url.pathname.endsWith("/devices")) return json({ policies: [...samplePolicies, { ...samplePolicies[0], id: "package-b", containerId: "dom-2", name: "Package B" }], devices: [] });
      if (url.pathname.endsWith("/object-usage")) return oldInspection.promise;
      if (url.pathname.endsWith("/objects") && url.searchParams.get("type") === "service") {
        if (url.pathname.includes("/dom-2/")) return objectPage([newRow]);
        if (url.searchParams.get("q")) return oldSearch.promise;
      }
    });
    render(matrixEditor());
    openCellPicker();
    fireEvent.click(await screen.findByTestId("select-service-object-web-a"));
    const query = screen.getByLabelText("Search service object");
    fireEvent.change(query, { target: { value: "old query" } });
    fireEvent.keyDown(query, { key: "Enter" });
    expect(screen.getByText("Searching service objects…")).toBeVisible();
    fireEvent.keyDown(query, { key: "Enter" });
    expect(calls.filter(({ path }) => path.includes("q=old%20query"))).toHaveLength(2); // One request per type.
    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Policy / domain" }));
    fireEvent.click(await screen.findByRole("option", { name: /Package B/ }));
    expect(await screen.findByTestId("select-service-object-web-b")).toBeVisible();
    await act(async () => {
      oldSearch.resolve(objectPage([web]));
      oldInspection.resolve(json({ object: { ...web, ...cpLeaf(web.id, web.name, ["port: 8443"]) } }));
    });
    expect(screen.getByTestId("select-service-object-web-b")).toBeVisible();
    expect(screen.queryByTestId("select-service-object-web-a")).not.toBeInTheDocument();
    expect(screen.queryByTestId("resolved-service-ports-preview")).not.toBeInTheDocument();
    expect(screen.queryByText("Resolving service object ports…")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Services")).toHaveValue("tcp/5432");
  });

  it("clears the query on reopening and resets copy state between two ALLOW_LISTED pairs", async () => {
    const web = invObj("web", "CP-WEB", "service-tcp");
    const dns = invObj("dns", "CP-DNS", "service-udp");
    stubPolicyAndMatrixFetch({ web: cpLeaf(web.id, web.name, ["port: 8443"]) }, [web, dns], []);
    const matrix = { ...baseMatrix, cells: [baseMatrix.cells[0], { ...baseMatrix.cells[1], statement: "ALLOW_LISTED" as const }] };
    render(matrixEditor(false, { ...draftVersion, matrix }));
    openCellPicker();
    expect(await screen.findByTestId("select-service-object-dns")).toBeVisible();
    fireEvent.change(screen.getByLabelText("Search service object"), { target: { value: "WEB" } });
    fireEvent.click(screen.getByRole("button", { name: /^Search$/ }));
    fireEvent.click(await screen.findByTestId("select-service-object-web"));
    expect(screen.queryByTestId("select-service-object-dns")).not.toBeInTheDocument();
    fireEvent.click(await screen.findByTestId("confirm-copy-service-ports"));
    expect(screen.getByTestId("copy-service-status")).toHaveTextContent("Copied 1 service entries");
    const toggle = screen.getAllByTestId("toggle-copy-service-object")[0];
    fireEvent.click(toggle);
    fireEvent.click(toggle);
    expect(screen.getByLabelText("Search service object")).toHaveValue("");
    expect(await screen.findByTestId("select-service-object-dns")).toBeVisible();
    expect(screen.getByTestId("copy-service-status")).toBeVisible();
    fireEvent.click(screen.getByTestId("matrix-cell-z2-z1"));
    expect(screen.queryByTestId("copy-service-status")).not.toBeInTheDocument();
    expect(screen.queryByTestId("copy-service-object-panel")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Services")).toHaveValue("");
  });

  it("uses the response masking header rather than legitimate ZONE-1 and SET-1 names", async () => {
    stubPolicyAndMatrixFetch({}, [], [], (url) => url.pathname.endsWith("/devices")
      ? json({ policies: [], devices: [] }, 200, { "X-Nexus-Masked": "true" }) : undefined);
    const draft = {
      ...draftVersion,
      zones: draftVersion.zones.map((zone, i) => ({ ...zone, name: `ZONE-${i + 1}` })),
      matrix: { ...baseMatrix, service_sets: [{ name: "SET-1", entries: [] }] },
    };
    render(matrixEditor(false, draft));
    fireEvent.click(screen.getByTestId("matrix-cell-z1-z2"));
    expect(screen.getAllByTestId("toggle-copy-service-object")).toHaveLength(2);
    await act(async () => { await listPolicies(); });
    expect(responsesAreMasked()).toBe(true);
    expect(screen.queryByTestId("toggle-copy-service-object")).not.toBeInTheDocument();
  });
});
