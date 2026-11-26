import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import {
  STATIC_ROUTE_R0_BADGE,
  StaticRoutePreviewPanel,
} from "../src/screens/StaticRoutePreviewPanel";
import type { StaticRoutePreviewRecord, StaticRouteTargetOption } from "../src/auth/adminApi";

function withTheme(node: React.ReactElement) {
  return <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
}

const TARGETS: StaticRouteTargetOption[] = [
  {
    unit_id: "11111111-2222-4333-8444-555555555555",
    device_id: "11111111-2222-4333-8444-555555555555",
    cluster_id: "CLS-ROMEO-01",
    target_label: "FW-TANGO-04",
    vendor: "check_point",
    ha_role: "ACTIVE",
    routing_contexts: ["gaia-default"],
    interfaces: ["eth0", "eth1"],
  },
  {
    unit_id: "22222222-3333-4444-8555-666666666666",
    device_id: "22222222-3333-4444-8555-666666666666",
    cluster_id: null,
    target_label: "FW-JULIET-06",
    vendor: "cisco_asa",
    ha_role: "STANDALONE",
    routing_contexts: ["single"],
    interfaces: ["outside", "inside"],
  },
];

const SAMPLE_PREVIEW: StaticRoutePreviewRecord = {
  preview_id: "srp-99999999-8888-4777-8666-555555555555",
  idempotency_key: "ui-srp-test-1",
  requested_at: "2026-11-18T08:15:00Z",
  completed_at: "2026-11-18T08:15:02Z",
  requested_by: null,
  unit_id: "11111111-2222-4333-8444-555555555555",
  target_device_id: "11111111-2222-4333-8444-555555555555",
  target_label: "FW-TANGO-04",
  vendor: "check_point",
  routing_context: "gaia-default",
  operation: "ADD",
  destination_prefix: "192.0.2.128/25",
  next_hop_ip: "192.0.2.1",
  interface_name: null,
  distance: null,
  metric: null,
  priority: 2,
  route_name: null,
  device_comment: null,
  representative_host: "192.0.2.129",
  change_ticket_ref: null,
  reason: null,
  job_id: "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
  job_state: "COMPLETED",
  state: "DONE",
  verdict: "PASS",
  stop_code: null,
  mutation_possible: false,
  preview_badge: STATIC_ROUTE_R0_BADGE,
  pre_route_table_sha256: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  pre_checks: [
    {
      check_number: 1,
      check_id: "TARGET_REACHABLE_AND_READY",
      stage: "STAGE_B",
      status: "PASS",
      reason_code: "SSH_SESSION_READY",
      detail: "Trusted SSH session established.",
    },
    {
      check_number: 3,
      check_id: "DEFAULT_ROUTE_GUARD",
      stage: "STAGE_B",
      status: "PASS",
      reason_code: "NON_DEFAULT_PREFIX",
      detail: "Destination prefix is non-default.",
    },
    {
      check_number: 6,
      check_id: "CONTROL_PLANE_AND_OVERLAP_GUARD",
      stage: "STAGE_B",
      status: "WARN",
      reason_code: "CP_ANTISPOOFING_DEFINED_BY_ROUTES_WARNING",
      detail: "Check Point anti-spoofing topology mode is UNKNOWN in R0.",
    },
  ],
  before_path: {
    representative_host: "192.0.2.129",
    verdict: "OBSERVED",
    reason_code: "PATH_RECORDED",
    selected_prefix: "192.0.2.0/24",
    next_hop_ip: "192.0.2.254",
    egress_interface: "eth0",
    protocol: "static",
    digest: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  },
  command_plan: {
    gate_version: "R0-2026-11-18",
    request_digest: "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
    mutation_possible: false,
    note: STATIC_ROUTE_R0_BADGE,
    forward_steps: [
      {
        step_no: 1,
        phase: "WRITE",
        member_device_id: "11111111-2222-4333-8444-555555555555",
        member_label: "FW-TANGO-04",
        command_text: "clish -c 'set static-route 192.0.2.128/25 nexthop gateway address 192.0.2.1 priority 2 on'",
        command_sha256: "1111111111111111111111111111111111111111111111111111111111111111",
      },
      {
        step_no: 2,
        phase: "SAVE",
        member_device_id: "11111111-2222-4333-8444-555555555555",
        member_label: "FW-TANGO-04",
        command_text: "clish -c \"save config\"",
        command_sha256: "2222222222222222222222222222222222222222222222222222222222222222",
      },
    ],
    approved_command_sha256_set: [
      "1111111111111111111111111111111111111111111111111111111111111111",
      "2222222222222222222222222222222222222222222222222222222222222222",
    ],
    inverse_steps: [
      {
        step_no: 1,
        phase: "ROLLBACK_WRITE",
        member_device_id: "11111111-2222-4333-8444-555555555555",
        member_label: "FW-TANGO-04",
        command_text: "clish -c 'set static-route 192.0.2.128/25 nexthop gateway address 192.0.2.1 off'",
        command_sha256: "3333333333333333333333333333333333333333333333333333333333333333",
      },
      {
        step_no: 2,
        phase: "ROLLBACK_SAVE",
        member_device_id: "11111111-2222-4333-8444-555555555555",
        member_label: "FW-TANGO-04",
        command_text: "clish -c \"save config\"",
        command_sha256: "2222222222222222222222222222222222222222222222222222222222222222",
      },
    ],
    inverse_command_sha256_set: [
      "3333333333333333333333333333333333333333333333333333333333333333",
      "2222222222222222222222222222222222222222222222222222222222222222",
    ],
  },
  inverse_plan: [
    {
      step_no: 1,
      phase: "ROLLBACK_WRITE",
      member_device_id: "11111111-2222-4333-8444-555555555555",
      member_label: "FW-TANGO-04",
      command_text: "clish -c 'set static-route 192.0.2.128/25 nexthop gateway address 192.0.2.1 off'",
      command_sha256: "3333333333333333333333333333333333333333333333333333333333333333",
    },
  ],
};

function stubFetch(previews: StaticRoutePreviewRecord[] = [SAMPLE_PREVIEW]) {
  return vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? "GET";
      if (url === "/api/v2/operations/static-route/preview" && method === "GET") {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              preview_badge: STATIC_ROUTE_R0_BADGE,
              mutation_possible: false,
              targets: TARGETS,
              previews,
            }),
            { status: 200 },
          ),
        );
      }
      if (url === "/api/v2/operations/static-route/preview" && method === "POST") {
        return Promise.resolve(new Response(JSON.stringify(SAMPLE_PREVIEW), { status: 202 }));
      }
      return Promise.resolve(new Response(JSON.stringify({}), { status: 200 }));
    }),
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("StaticRoutePreviewPanel (Phase R0)", () => {
  it("renders the preview-only badge, pre-checks, before path, forward plan and inverse plan with zero execute buttons", async () => {
    stubFetch();
    render(withTheme(<StaticRoutePreviewPanel />));

    expect(await screen.findByRole("region", { name: "Static route preview result" })).toBeInTheDocument();
    expect(screen.getAllByText(STATIC_ROUTE_R0_BADGE).length).toBeGreaterThanOrEqual(2);

    // No execute / apply / approve button exists in R0
    expect(screen.queryByRole("button", { name: /execute|apply|approve|submit for approval/i })).toBeNull();
    expect(screen.getByRole("button", { name: "Preview" })).toBeInTheDocument();

    const preChecksTable = screen.getByRole("table", { name: "Static route pre-checks" });
    expect(within(preChecksTable).getByText("TARGET_REACHABLE_AND_READY")).toBeInTheDocument();
    expect(within(preChecksTable).getByText("CP_ANTISPOOFING_DEFINED_BY_ROUTES_WARNING")).toBeInTheDocument();

    const beforePathTable = screen.getByRole("table", { name: "Before-state path lookup" });
    expect(within(beforePathTable).getByText("192.0.2.0/24")).toBeInTheDocument();
    expect(within(beforePathTable).getByText("192.0.2.254")).toBeInTheDocument();
    expect(within(beforePathTable).getByText("eth0")).toBeInTheDocument();

    const forwardTable = screen.getByRole("table", { name: "Forward command plan" });
    expect(
      within(forwardTable).getByText(
        "clish -c 'set static-route 192.0.2.128/25 nexthop gateway address 192.0.2.1 priority 2 on'",
      ),
    ).toBeInTheDocument();

    const inverseTable = screen.getByRole("table", { name: "Inverse command plan" });
    expect(
      within(inverseTable).getByText(
        "clish -c 'set static-route 192.0.2.128/25 nexthop gateway address 192.0.2.1 off'",
      ),
    ).toBeInTheDocument();
  });

  it("refuses 0.0.0.0/0 client-side before sending and submits valid preview requests", async () => {
    stubFetch([]);
    render(withTheme(<StaticRoutePreviewPanel />));
    await screen.findByRole("button", { name: "Preview" });

    fireEvent.change(screen.getByLabelText("Destination prefix (CIDR)"), { target: { value: "0.0.0.0/0" } });
    fireEvent.change(screen.getByLabelText("Next-hop IP"), { target: { value: "192.0.2.1" } });
    fireEvent.click(screen.getByRole("button", { name: "Preview" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("DEFAULT_ROUTE_FORBIDDEN");
    expect(
      vi.mocked(fetch).mock.calls.some(([, init]) => (init as RequestInit | undefined)?.method === "POST"),
    ).toBe(false);

    fireEvent.change(screen.getByLabelText("Destination prefix (CIDR)"), { target: { value: "192.0.2.128/25" } });
    fireEvent.click(screen.getByRole("button", { name: "Preview" }));

    await waitFor(() =>
      expect(
        vi.mocked(fetch).mock.calls.some(
          ([url, init]) =>
            String(url) === "/api/v2/operations/static-route/preview" &&
            (init as RequestInit | undefined)?.method === "POST",
        ),
      ).toBe(true),
    );
  });
});
