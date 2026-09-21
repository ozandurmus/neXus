import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { JobLogsPanel } from "../src/screens/JobLogsPanel";

afterEach(() => vi.unstubAllGlobals());

it("shows the recorded device name with its byte-identical identifier", async () => {
  vi.stubGlobal("fetch", vi.fn().mockImplementation((input: RequestInfo | URL) => {
    if (String(input) === "/api/v2/jobs") return Promise.resolve(new Response(JSON.stringify([{ job_id: "job-1", target_device_id: "opaque-001", job_type: "inventory", state: "COMPLETED" }])));
    if (String(input) === "/devices") return Promise.resolve(new Response(JSON.stringify({ devices: [{ device_id: "opaque-001", hostname: "FW-TANGO-04" }] })));
    return Promise.resolve(new Response("{}", { status: 404 }));
  }));

  render(<JobLogsPanel />);

  await waitFor(() => expect(screen.getByText("FW-TANGO-04 · opaque-001")).toBeInTheDocument());
});
