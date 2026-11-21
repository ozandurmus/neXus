import { render, screen, waitFor, within, fireEvent } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { EstateRewindScreen } from "../src/estate/EstateRewindScreen";
import { resetSharedApiCache } from "../src/auth/adminApi";

function unit(verdict: string, at: string | null) {
  return {
    device_id: "dev-1",
    hostname: "FW-TANGO-04",
    failover: { verdict, evidence_at: at, reason: at ? null : "NO_RUN_AT_MOMENT" },
    backup: { verdict: "OK", evidence_at: at },
    compliance: { state: "EVIDENCED", pass_count: 2, fail_count: 0, critical_fail_count: 0, evidence_at: at },
  };
}

describe("EstateRewindScreen", () => {
  beforeEach(() => resetSharedApiCache());
  afterEach(() => vi.unstubAllGlobals());

  it("reads as-of, shows UNKNOWN and compares only differences", async () => {
    const evidence = new Date(Date.now() - 3 * 86_400_000).toISOString();
    const calls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      calls.push(url);
      const at = new URL(url, "http://x").searchParams.get("at") ?? "";
      const old = Date.parse(at) < Date.now() - 86_400_000;
      const body = { as_of: at, generated_at: at, warnings: [], units: [old ? unit("UNKNOWN", null) : unit("NOT_READY", evidence)] };
      return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
    }));
    render(<EstateRewindScreen initialAt={new Date(Date.now() - 10 * 86_400_000).toISOString()} initialCompare={null} />);
    await waitFor(() => expect(screen.getByTestId("rewind-counts")).toHaveTextContent("1 UNKNOWN"));
    expect(calls.every((u) => u.startsWith("/api/v2/estate/as-of?at="))).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Compare two moments" }));
    const table = await screen.findByTestId("rewind-transitions");
    expect(within(table).getByText("FW-TANGO-04")).toBeInTheDocument();
    expect(within(table).getByText("got worse")).toBeInTheDocument();
    expect(screen.getByTestId("rewind-slider")).toBeInTheDocument();
  });
});
