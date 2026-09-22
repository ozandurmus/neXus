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
