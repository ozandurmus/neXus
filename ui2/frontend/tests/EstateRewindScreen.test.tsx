import { render, screen, waitFor, within, fireEvent } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { EstateRewindScreen } from "../src/estate/EstateRewindScreen";
import { resetSharedApiCache } from "../src/auth/adminApi";
import { dossierHref } from "../src/estate/estateRouting";
import { REWIND_TRANSITION_CAP } from "../src/estate/estateRewindModel";

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

describe("EstateRewindScreen rings", () => {
  beforeEach(() => resetSharedApiCache());
  afterEach(() => vi.unstubAllGlobals());

  function unitOf(id: string, name: string, verdict: string, at: string) {
    return {
      device_id: id,
      hostname: name,
      failover: { verdict, evidence_at: at, reason: null },
      backup: { verdict: "OK", evidence_at: at },
      compliance: { state: "EVIDENCED", pass_count: 2, fail_count: 0, critical_fail_count: 0, evidence_at: at },
    };
  }

  function stubTwoUnits() {
    const evidence = new Date(Date.now() - 3 * 86_400_000).toISOString();
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const at = new URL(String(input), "http://x").searchParams.get("at") ?? "";
      const old = Date.parse(at) < Date.now() - 86_400_000;
      const units = old
        ? [unitOf("dev-1", "FW-TANGO-04", "UNKNOWN", null as unknown as string), unitOf("dev-2", "FW-TANGO-02", "READY", evidence)]
        : [unitOf("dev-1", "FW-TANGO-04", "NOT_READY", evidence), unitOf("dev-2", "FW-TANGO-02", "READY", evidence)];
      const body = { as_of: at, generated_at: at, warnings: [], units };
      return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
    }));
  }

  it("in compare mode shows every unit of the lens, rings the worse one and fades the unchanged one", async () => {
    stubTwoUnits();
    render(<EstateRewindScreen initialAt={new Date(Date.now() - 10 * 86_400_000).toISOString()} initialCompare={null} />);
    await waitFor(() => expect(screen.getByTestId("rewind-counts")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Compare two moments" }));
    const wall = await screen.findByTestId("rewind-wall");
    await waitFor(() => expect(wall.querySelectorAll("a")).toHaveLength(2));

    const worse = screen.getByRole("img", { name: /^FW-TANGO-04: UNKNOWN, .*; worse; evidence/ }).closest("a");
    expect(worse).toHaveAttribute("data-rewind-ring", "worse");
    expect(worse).not.toHaveAttribute("data-rewind-faded");
    expect(worse?.getAttribute("title")).toContain("worse");

    const unchanged = screen.getByRole("img", { name: /^FW-TANGO-02: .*unchanged;/ }).closest("a");
    expect(unchanged).toHaveAttribute("data-rewind-faded", "true");
    expect(unchanged).toHaveAttribute("data-rewind-ring", "unchanged");
  });

  it("in one-moment mode there are no rings and no fading", async () => {
    stubTwoUnits();
    render(<EstateRewindScreen initialAt={new Date(Date.now() - 10 * 86_400_000).toISOString()} initialCompare={null} />);
    await waitFor(() => expect(screen.getByTestId("rewind-counts")).toBeInTheDocument());
    const wall = screen.getByTestId("rewind-wall");
    expect(wall.querySelectorAll("a")).toHaveLength(2);
    expect(wall.querySelectorAll("[data-rewind-ring]")).toHaveLength(0);
    expect(wall.querySelectorAll("[data-rewind-faded]")).toHaveLength(0);
    expect(screen.getByRole("img", { name: /^FW-TANGO-04: .*; evidence/ })).not.toHaveAccessibleName(/worse|unchanged/);
  });
});

describe("EstateRewindScreen drill-down links", () => {
  beforeEach(() => resetSharedApiCache());
  afterEach(() => vi.unstubAllGlobals());

  it("unit swatch and diff row link to the dossier for that unit at the rewound moment", async () => {
    const evidence = new Date(Date.now() - 3 * 86_400_000).toISOString();
    const at = new Date(Date.now() - 10 * 86_400_000).toISOString();
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const requested = new URL(String(input), "http://x").searchParams.get("at") ?? "";
      const old = Date.parse(requested) < Date.now() - 86_400_000;
      const unit = {
        device_id: "dev-tango-04",
        hostname: "FW-TANGO-04",
        failover: { verdict: old ? "UNKNOWN" : "NOT_READY", evidence_at: old ? null : evidence, reason: old ? "NO_RUN_AT_MOMENT" : null },
        backup: { verdict: "OK", evidence_at: evidence },
        compliance: { state: "EVIDENCED", pass_count: 2, fail_count: 0, critical_fail_count: 0, evidence_at: evidence },
      };
      return new Response(JSON.stringify({ as_of: requested, generated_at: requested, warnings: [], units: [unit] }), { status: 200, headers: { "Content-Type": "application/json" } });
    }));
    render(<EstateRewindScreen initialAt={at} initialCompare={null} />);
    await waitFor(() => expect(screen.getByTestId("rewind-counts")).toBeInTheDocument());

    const swatch = screen.getByRole("img", { name: /^FW-TANGO-04: / }).closest("a");
    expect(swatch).not.toBeNull();
    const expected = dossierHref("dev-tango-04", { at: new Date(at).toISOString(), compare: null });
    expect(swatch).toHaveAttribute("href", expected);
    expect(swatch?.getAttribute("href")).toContain("device_id=dev-tango-04");
    expect(swatch?.getAttribute("href")).toContain("rewind_at=");

    fireEvent.click(screen.getByRole("button", { name: "Compare two moments" }));
    const table = await screen.findByTestId("rewind-transitions");
    const rowLink = within(table).getByRole("link", { name: /^FW-TANGO-04: open dossier at this moment$/ });
    const rowHref = rowLink.getAttribute("href") ?? "";
    expect(rowHref.startsWith(`${expected}&rewind_compare=`)).toBe(true);
    rowLink.focus();
    expect(rowLink).toHaveFocus();
  });
});

describe("EstateRewindScreen UNKNOWN, warnings and Moment B", () => {
  beforeEach(() => resetSharedApiCache());
  afterEach(() => vi.unstubAllGlobals());

  it("marks a failed historical read UNKNOWN with a stamp", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => {
      throw new TypeError("network down");
    }));
    render(<EstateRewindScreen initialAt={new Date(Date.now() - 10 * 86_400_000).toISOString()} initialCompare={null} />);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Rewind read failed");
    expect(within(alert).getAllByText("UNKNOWN").length).toBeGreaterThan(0);
  });

  it("shows snapshot warnings with a Warning badge", async () => {
    const at = new Date(Date.now() - 10 * 86_400_000).toISOString();
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const requested = new URL(String(input), "http://x").searchParams.get("at") ?? "";
      const body = { as_of: requested, generated_at: requested, warnings: [{ code: "W", message: "Synthetic snapshot warning" }], units: [] };
      return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
    }));
    render(<EstateRewindScreen initialAt={at} initialCompare={null} />);
    const text = await screen.findByText("Synthetic snapshot warning");
    expect(text.parentElement).toHaveTextContent("Warning");
  });

  it("jumps Moment B to a stored snapshot and back to live", async () => {
    const evidence = new Date(Date.now() - 3 * 86_400_000).toISOString();
    const calls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const requested = new URL(String(input), "http://x").searchParams.get("at") ?? "";
      calls.push(requested);
      const body = { as_of: requested, generated_at: requested, warnings: [], units: [unit("NOT_READY", evidence)] };
      return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
    }));
    render(<EstateRewindScreen initialAt={new Date(Date.now() - 10 * 86_400_000).toISOString()} initialCompare={null} />);
    await waitFor(() => expect(screen.getByTestId("rewind-counts")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Compare two moments" }));
    const strip = await screen.findByTestId("rewind-jump-b-strip");
    const liveBtn = within(strip).getByRole("button", { name: "Live (now)" });
    expect(liveBtn).toHaveAttribute("aria-pressed", "true");

    fireEvent.click(within(strip).getByRole("button", { name: "7 days ago" }));
    await waitFor(() => expect(screen.getByText("pinned")).toBeInTheDocument());
    expect(calls).toContain(evidence);
    expect(within(strip).getByRole("button", { name: "Live (now)" })).toHaveAttribute("aria-pressed", "false");

    fireEvent.click(within(strip).getByRole("button", { name: "Live (now)" }));
    await waitFor(() => expect(screen.getByText("live")).toBeInTheDocument());
    expect(within(strip).getByRole("button", { name: "Live (now)" })).toHaveAttribute("aria-pressed", "true");
  });

  it("caps the transition table at 25 rows with a +N more footer and prints then/now detail", async () => {
    const evidence = new Date(Date.now() - 3 * 86_400_000).toISOString();
    const count = REWIND_TRANSITION_CAP + 2;
    const units = (old: boolean) => Array.from({ length: count }, (_, i) => ({
      device_id: `dev-${i}`,
      hostname: `FW-TANGO-${String(i).padStart(2, "0")}`,
      failover: old ? { verdict: "READY", evidence_at: evidence, reason: null } : { verdict: "NOT_READY", evidence_at: evidence, reason: "SYNTHETIC_REASON" },
      backup: { verdict: "OK", evidence_at: evidence },
      compliance: { state: "EVIDENCED", pass_count: 2, fail_count: 0, critical_fail_count: 0, evidence_at: evidence },
    }));
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const requested = new URL(String(input), "http://x").searchParams.get("at") ?? "";
      const old = Date.parse(requested) < Date.now() - 86_400_000;
      return new Response(JSON.stringify({ as_of: requested, generated_at: requested, warnings: [], units: units(old) }), { status: 200, headers: { "Content-Type": "application/json" } });
    }));
    render(<EstateRewindScreen initialAt={new Date(Date.now() - 10 * 86_400_000).toISOString()} initialCompare={null} />);
    await waitFor(() => expect(screen.getByTestId("rewind-counts")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Compare two moments" }));
    const table = await screen.findByTestId("rewind-transitions");
    expect(screen.getByTestId("rewind-transitions-count")).toHaveTextContent(`${count} transitions on Failover`);
    expect(within(table).getAllByRole("link")).toHaveLength(REWIND_TRANSITION_CAP);
    expect(screen.getByTestId("rewind-transitions-more")).toHaveTextContent("+2 more transitions");
    expect(within(table).getAllByText("synthetic reason").length).toBeGreaterThan(0);
  });
});
