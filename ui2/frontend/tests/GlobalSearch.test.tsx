import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { GlobalSearch } from "../src/shell/GlobalSearch";

afterEach(() => vi.unstubAllGlobals());

const response = {
  devices: [{ device_id: "opaque-001", name: "FW-TANGO-04", serial: "SN-ABC123",
    model: "Model A", software_version: "1", cluster: "CLS-ROMEO-01", management_address: "192.0.2.10",
    vendor: "check_point", href: "?screen=inventory&device_id=opaque-001" }],
  settings: [{ device: "FW-TANGO-04", cluster: "CLS-ROMEO-01", section: "DNS", setting: "Primary DNS",
    value_excerpt: "192.0.2.53", href: "?screen=configuration&device_id=opaque-001" }],
  evidence: [{ kind: "job", label: "job-123", detail: "collection failed", href: "?screen=operations&tab=jobs&q=job-123" }],
};

function api(status = 200) {
  const calls: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    calls.push(path);
    if (path === "/devices") return new Response(JSON.stringify({ devices: [{ device_id: "opaque-001", hostname: "FW-TANGO-04" }] }));
    return new Response(JSON.stringify(status === 200 ? response : { error: "ACTION_REFUSED" }), { status });
  }));
  return calls;
}

it("debounces and groups devices, settings, and evidence with their target links", async () => {
  const calls = api();
  render(<GlobalSearch />);
  const input = screen.getByRole("textbox", { name: "Search devices, settings and evidence" });
  fireEvent.focus(input);
  fireEvent.change(input, { target: { value: "FW" } });
  expect(calls.some(c => c.startsWith("/api/v2/search"))).toBe(false);
  await waitFor(() => expect(calls).toContain("/api/v2/search?q=FW&limit=20"));
  await waitFor(() => expect(screen.getByText("Settings")).toBeInTheDocument());
  expect(screen.getByText("Evidence")).toBeInTheDocument();
  expect(screen.getByText("Devices")).toBeInTheDocument();
  expect(screen.getByRole("option", { name: /Primary DNS/ })).toHaveAttribute("href", "?screen=configuration&device_id=opaque-001");
  expect(screen.getByRole("option", { name: /job-123/ })).toHaveAttribute("href", "?screen=operations&tab=jobs&q=job-123");
  fireEvent.keyDown(input, { key: "ArrowDown" });
  expect(within(screen.getByRole("listbox")).getAllByRole("option").some(option => option.getAttribute("aria-selected") === "true")).toBe(true);
});

it("shows Restricted without Retry on a 403", async () => {
  api(403);
  render(<GlobalSearch />);
  const input = screen.getByRole("textbox", { name: "Search devices, settings and evidence" });
  fireEvent.focus(input);
  fireEvent.change(input, { target: { value: "FW" } });
  await waitFor(() => expect(screen.getByText("Search results are restricted")).toBeInTheDocument());
  expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
});

it("offers Retry after a server error", async () => {
  const calls = api(500);
  render(<GlobalSearch />);
  const input = screen.getByRole("textbox", { name: "Search devices, settings and evidence" });
  fireEvent.focus(input);
  fireEvent.change(input, { target: { value: "FW" } });
  await waitFor(() => expect(screen.getByText("Search failed")).toBeInTheDocument());
  fireEvent.click(screen.getByRole("button", { name: "Retry" }));
  await waitFor(() => expect(calls.filter(c => c.startsWith("/api/v2/search"))).toHaveLength(2));
});

it("groups IP matches with counts, route details and policy usage, then pages only Routes", async () => {
  const calls: string[] = [];
  const route = { device_id: "opaque-001", device: "FW-TANGO-04", context: "VS-ROMEO-01",
    interface: "eth0", destination: "192.0.2.0/24", next_hop: "192.0.2.1", protocol: "static",
    route_table: "VRF-ROMEO-01", default_fallback: false, href: "?screen=inventory&device_id=opaque-001&tab=routes" };
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input); calls.push(path);
    if (path === "/devices") return new Response(JSON.stringify({ devices: [] }));
    return new Response(JSON.stringify(path.includes("group=routes") ? {
      devices: [], settings: [], evidence: [], counts: { routes: 2 },
      routes: [{ ...route, destination: "0.0.0.0/0", default_fallback: true }],
    } : {
      devices: [], settings: [], evidence: [], counts: { interfaces: 1, routes: 2, policy_objects: 1 },
      interfaces: [{ ...route, address: "192.0.2.10/24" }], routes: [route],
      policy_objects: [{ id: "object-1", name: "ADDR-ROMEO-01", type: "host", rule_count: 3,
        href: "?screen=policy&tab=objects&policy_id=policy-1" }],
    }));
  }));
  render(<GlobalSearch />);
  const input = screen.getByRole("textbox", { name: "Search devices, settings and evidence" });
  fireEvent.focus(input); fireEvent.change(input, { target: { value: "192.0.2.10" } });
  await waitFor(() => expect(screen.getByText("Interfaces (1)")).toBeInTheDocument());
  expect(screen.getByText("Routes (2)")).toBeInTheDocument();
  expect(screen.getByText("Policy objects (1)")).toBeInTheDocument();
  expect(screen.getByRole("option", { name: /used in 3 rules/ })).toHaveAttribute("href", "?screen=policy&tab=objects&policy_id=policy-1");
  expect(screen.getByRole("option", { name: /192.0.2.0\/24/ })).toHaveTextContent("VS-ROMEO-01 · VRF-ROMEO-01 · 192.0.2.1 · eth0 · static");
  fireEvent.click(screen.getByRole("button", { name: "Show more routes" }));
  await waitFor(() => expect(calls).toContain("/api/v2/search?q=192.0.2.10&limit=20&group=routes&offset=1"));
  await waitFor(() => expect(screen.getByRole("option", { name: /Default route fallback/ })).toBeInTheDocument());
  expect(screen.queryByRole("button", { name: "Show more routes" })).toBeNull();
  expect(screen.getByText("Interfaces (1)")).toBeInTheDocument();
});
