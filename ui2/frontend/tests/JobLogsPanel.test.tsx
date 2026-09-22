import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { JobLogsPanel, localInputToIso } from "../src/screens/JobLogsPanel";

afterEach(() => vi.unstubAllGlobals());

function stubApi(jobsPage: unknown, calls: string[] = []) {
  vi.stubGlobal("fetch", vi.fn().mockImplementation((input: RequestInfo | URL) => {
    const url = String(input);
    calls.push(url);
    if (url.startsWith("/api/v2/jobs/facets")) {
      return Promise.resolve(new Response(JSON.stringify({ states: ["COMPLETED", "FAILED"], job_types: ["inventory", "cp_gateway_backup"] })));
    }
    if (url.startsWith("/api/v2/jobs")) return Promise.resolve(new Response(JSON.stringify(jobsPage)));
    if (url === "/devices") return Promise.resolve(new Response(JSON.stringify({ devices: [{ device_id: "opaque-001", hostname: "FW-TANGO-04" }] })));
    return Promise.resolve(new Response("{}", { status: 404 }));
  }));
  return calls;
}

it("shows the recorded device name with its byte-identical identifier", async () => {
  stubApi({ items: [{ job_id: "job-1", target_device_id: "opaque-001", job_type: "inventory", state: "COMPLETED" }], page: 1, page_size: 50, total: 1 });

  render(<JobLogsPanel />);

  await waitFor(() => expect(screen.getByText("FW-TANGO-04 · opaque-001")).toBeInTheDocument());
  expect(screen.getByText("1–1 of 1 jobs")).toBeInTheDocument();
});

describe("history, filters, pages and export (PO P0, 2026-09-22)", () => {
  it("pages the whole history on the server and shows numbered pages", async () => {
    const calls = stubApi({ items: [{ job_id: "job-9", target_device_id: "opaque-001", job_type: "inventory", state: "FAILED" }], page: 1, page_size: 50, total: 137 });

    render(<JobLogsPanel />);

    await waitFor(() => expect(screen.getByText("1–50 of 137 jobs")).toBeInTheDocument());
    expect(calls.some((c) => c === "/api/v2/jobs?page=1&page_size=50")).toBe(true);
    // 137 rows at 50 per page = 3 numbered pages
    expect(screen.getByRole("button", { name: "Go to page 3" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Go to page 3" }));
    await waitFor(() => expect(calls.some((c) => c === "/api/v2/jobs?page=3&page_size=50")).toBe(true));
  });

  it("sends the state filter to the server and returns to page one", async () => {
    const calls = stubApi({ items: [], page: 1, page_size: 50, total: 0 });

    render(<JobLogsPanel />);
    await waitFor(() => expect(screen.getByLabelText("State")).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText("State"), { target: { value: "FAILED" } });
    await waitFor(() => expect(calls.some((c) => c === "/api/v2/jobs?state=FAILED&page=1&page_size=50")).toBe(true));
  });

  it("exports the current filter as CSV through the export route", async () => {
    const calls = stubApi({ items: [], page: 1, page_size: 50, total: 0 });
    vi.stubGlobal("URL", { ...URL, createObjectURL: vi.fn(() => "blob:x"), revokeObjectURL: vi.fn() });

    render(<JobLogsPanel />);
    await waitFor(() => expect(screen.getByLabelText("State")).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText("State"), { target: { value: "FAILED" } });
    fireEvent.click(screen.getByRole("button", { name: "Export CSV" }));

    await waitFor(() => expect(calls.some((c) => c === "/api/v2/jobs/export.csv?state=FAILED")).toBe(true));
  });
});

it("turns a local datetime input into an ISO instant and leaves an empty one empty", () => {
  expect(localInputToIso("")).toBeUndefined();
  expect(localInputToIso("2026-09-22T14:00")).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
});
