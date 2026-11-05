import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PolicyObjectSearch, SEARCH_CONTAINER_CAP } from "../src/screens/PolicyObjectSearch";
import { PolicyHygieneFleet } from "../src/screens/PolicyHygieneFleet";
import { JobLogsPanel, topFailureReasons, FAILED_DEFAULT_HOURS } from "../src/screens/JobLogsPanel";
import { installationSummary } from "../src/screens/policyFirewalls";
import { policyTabFromSlug, type ContainerSummary } from "../src/screens/PolicyScreen";

const themed = (node: React.ReactElement) => <ThemeProvider theme={m3Theme}>{node}</ThemeProvider>;
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); window.history.replaceState(null, "", "/"); });

const container = (n: number, extra: Partial<ContainerSummary> = {}): ContainerSummary => ({ key: `k${n}`, sourceId: "s1", sourceName: "MGR-ALPHA-01", vendor: "PAN", containerId: `c${n}`,
  containerName: `DG-ALPHA-0${n}`, policyIds: [`p${n}`], rules: 10 * n, gateways: n === 3 ? [] : ["FW-TANGO-04"], collectedAt: "2026-10-18T03:00:00Z", syncStates: n === 3 ? [] : ["IN_SYNC"], ...extra });

describe("Policy tab slugs (C2)", () => {
  it("maps the four slugs, defaulting to Rules & Containers", () => {
    expect(policyTabFromSlug("rules")).toBe("Rules & Containers");
    expect(policyTabFromSlug("objects")).toBe("Objects");
    expect(policyTabFromSlug("hygiene")).toBe("Hygiene");
    expect(policyTabFromSlug("changes")).toBe("Changes");
    expect(policyTabFromSlug(null)).toBe("Rules & Containers");
    expect(policyTabFromSlug("lookup")).toBe("Rules & Containers");
  });
});

describe("Installation status of a container (C2)", () => {
  it("counts the management server's reported sync states, warns on out of sync, and calls silence not-reported rather than a fault", () => {
    expect(installationSummary("PAN", ["IN_SYNC", "IN_SYNC"])).toMatchObject({ text: "2 in sync", tone: "good" });
    expect(installationSummary("PAN", ["IN_SYNC", "OUT_OF_SYNC"])).toMatchObject({ text: "1 in sync · 1 out of sync", tone: "warn" });
    expect(installationSummary("CP", ["UNKNOWN", ""])).toMatchObject({ text: "2 not reported", tone: "neutral" });
    expect(installationSummary("CP", ["UNKNOWN"]).reason).toMatch(/not a fault of the target/);
    expect(installationSummary("PAN", [])).toMatchObject({ text: "No target", tone: "neutral" });
    // A reported state the product has no word for is counted, never dropped.
    expect(installationSummary("PAN", ["IN_SYNC", "PENDING"]).text).toBe("1 in sync · 1 other");
  });
});

describe("Objects: search across containers (C2)", () => {
  const page = (names: string[], total = names.length) => ({ objects: names.map((name, i) => ({ id: `o${i}-${name}`, uid: `u${i}`, name, type: "host", status: "RESOLVED", values: ["192.0.2.10"], members: [], unused: false,
    emptyGroup: false, singleMember: false, duplicateId: null, ruleCount: 0, groupCount: 0 })), total, page: 0, pageSize: 200, types: [] });

  it("asks every container the same question, lists the containers that answer, and opens one", async () => {
    const calls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input); calls.push(url);
      const containerId = new URL(url, "http://x").pathname.split("/")[5];
      return new Response(JSON.stringify(containerId === "c2" ? page(["HOST-TANGO-04"]) : page([])), { status: 200 });
    }));
    const open = vi.fn();
    render(themed(<PolicyObjectSearch containers={[container(1), container(2), container(3)]} open={open} />));
    fireEvent.change(screen.getByLabelText("Search all containers"), { target: { value: "TANGO" } });
    expect(await screen.findByText("HOST-TANGO-04", {}, { timeout: 3000 })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("1 match in 1 container · searched 3 of 3"));
    expect(calls).toHaveLength(3);
    for (const url of calls) expect(url).toContain("q=TANGO");
    fireEvent.click(screen.getByRole("button", { name: "Open DG-ALPHA-02" }));
    expect(open).toHaveBeenCalledWith(expect.objectContaining({ containerId: "c2" }));
  });

  it("counts a container it could not read as not searched, never as no match", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => String(input).includes("/domains/c2/") ? new Response("{}", { status: 500 }) : new Response(JSON.stringify(page([])), { status: 200 })));
    render(themed(<PolicyObjectSearch containers={[container(1), container(2)]} open={vi.fn()} />));
    fireEvent.change(screen.getByLabelText("Search all containers"), { target: { value: "ZULU" } });
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent(/1 container could not be searched \(their result is UNKNOWN, not "no match"\)/), { timeout: 3000 });
    expect(screen.queryByText("No object matches in any stored container")).toBeNull();
  });

  it("asks nothing below two characters and says how many containers are covered, with a cap", () => {
    const fetcher = vi.fn(); vi.stubGlobal("fetch", fetcher);
    const many = Array.from({ length: SEARCH_CONTAINER_CAP + 5 }, (_, i) => container(i + 10));
    render(themed(<PolicyObjectSearch containers={many} open={vi.fn()} />));
    fireEvent.change(screen.getByLabelText("Search all containers"), { target: { value: "a" } });
    expect(fetcher).not.toHaveBeenCalled();
    expect(screen.getByText(new RegExp(`across ${SEARCH_CONTAINER_CAP} containers`))).toBeInTheDocument();
  });
});

describe("Hygiene: the fleet summary while no container is open (C2)", () => {
  it("states only what the catalog proves and leaves the findings not evaluated, never zero", () => {
    const now = new Date("2026-10-18T04:00:00Z");
    render(themed(<PolicyHygieneFleet now={now} rows={[container(1), container(2, { collectedAt: "2026-10-01T00:00:00Z" }), container(3)]} />));
    const summary = screen.getByRole("region", { name: "Hygiene fleet summary" });
    const card = (label: string) => summary.querySelector(`[data-kpi="${label}"]`) as HTMLElement;
    expect(within(card("Containers")).getByText("3")).toBeInTheDocument();
    expect(card("Containers")).toHaveTextContent("3 packages stored");
    expect(within(card("Stale snapshots")).getByText("1")).toBeInTheDocument();
    expect(within(card("Without a gateway")).getByText("1")).toBeInTheDocument();
    expect(card("Findings")).toHaveTextContent("Not evaluated");
    expect(card("Findings")).not.toHaveTextContent(/\b0\b/);
  });

  it("renders nothing when no container is stored", () => {
    const { container: dom } = render(themed(<PolicyHygieneFleet now={new Date()} rows={[]} />));
    expect(dom).toBeEmptyDOMElement();
  });
});

describe("Failed jobs: seven days by default and the top five reasons (B6)", () => {
  const failed = (id: string, reason?: string) => ({ job_id: id, job_type: "cp_gateway_backup", state: "FAILED", outcome: "FAILURE", ...(reason !== undefined ? { terminal_reason: reason } : {}), submitted_at: "2026-10-18T02:00:00Z", has_transcript: false });

  it("counts reasons with identifiers masked out, most frequent first, at most five, and names a missing reason", () => {
    const jobs = [
      ...Array.from({ length: 4 }, (_, i) => failed(`a${i}`, "COLLECTION_FAILED")),
      failed("b1", "device 123e4567-e89b-12d3-a456-426614174000 unreachable"), failed("b2", "device 123e4567-e89b-12d3-a456-426614174999 unreachable"),
      failed("c1", "TIMEOUT"), failed("d1", "REJECTED_BY_GATE"), failed("e1", "SSH_AUTH"), failed("f1", "ZZZ_LAST"), failed("n1"),
      { state: "COMPLETED", terminal_reason: "COMPLETED" },
    ];
    const top = topFailureReasons(jobs);
    expect(top).toHaveLength(5);
    expect(top[0]).toMatchObject({ key: "COLLECTION_FAILED", count: 4 });
    expect(top[1]).toMatchObject({ key: "device <id> unreachable", count: 2 });
    expect(top.some((r) => r.key === "COMPLETED")).toBe(false);
    expect(topFailureReasons([failed("n1"), failed("n2")])).toEqual([{ key: "", label: "No reason recorded", count: 2 }]);
  });

  function stubJobs(items: unknown[], total = items.length) {
    const calls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input); calls.push(url);
      const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200 });
      if (url.startsWith("/api/v2/jobs/stats")) return json({ total: 9, total_24h: 9, completed_24h: 4, failed_24h: 5, running: 0, by_state: { COMPLETED: 4, FAILED: 5 } });
      if (url.startsWith("/api/v2/jobs/facets")) return json({ states: ["COMPLETED", "FAILED"], job_types: [] });
      if (url.startsWith("/api/v2/jobs")) return json({ items, page: 1, page_size: 50, total });
      if (url === "/devices") return json({ devices: [] });
      return json({});
    }));
    return calls;
  }

  it("opens the Failed pill on the last seven days, lists the top reasons, and Show all time drops the window", async () => {
    const calls = stubJobs([failed("a1", "COLLECTION_FAILED"), failed("a2", "COLLECTION_FAILED"), failed("b1", "TIMEOUT")], 3);
    render(themed(<JobLogsPanel pillRequest={{ pill: "failed", seq: 0 }} />));
    const summary = await screen.findByTestId("failed-summary");
    await waitFor(() => expect(within(summary).getByRole("region", { name: "Top failure reasons" })).toBeInTheDocument());
    const list = within(within(summary).getByRole("region", { name: "Top failure reasons" })).getAllByRole("listitem");
    expect(list[0]).toHaveTextContent("Collection failed · 2");
    expect(summary).toHaveTextContent("Counted over all 3 failed jobs.");
    expect(summary).toHaveTextContent("Failed jobs of the last 7 days (the default window).");
    const since = calls.map((c) => new URL(c, "http://x").searchParams.get("since")).filter((v): v is string => Boolean(v));
    expect(since.length).toBeGreaterThan(0);
    const ageHours = (Date.now() - Date.parse(since[0])) / 3_600_000;
    expect(ageHours).toBeGreaterThan(FAILED_DEFAULT_HOURS - 1);
    expect(ageHours).toBeLessThan(FAILED_DEFAULT_HOURS + 1);
    const before = calls.length;
    fireEvent.click(within(summary).getByRole("button", { name: "Show all time" }));
    await waitFor(() => expect(calls.slice(before).some((c) => c.startsWith("/api/v2/jobs?state=FAILED&page=1&page_size=50"))).toBe(true));
    expect(screen.getByTestId("failed-summary")).toHaveTextContent("Failed jobs in the window chosen below.");
  });

  it("says how much was counted when there are more failures than the sample, and shows nothing for other states", async () => {
    stubJobs([failed("a1", "TIMEOUT")], 1234);
    render(themed(<JobLogsPanel pillRequest={{ pill: "failed", seq: 0 }} />));
    expect(await screen.findByText("Counted over the newest 1 of 1,234 failed jobs.")).toBeInTheDocument();
  });

  it("does not default the window, or show the reasons, for All", async () => {
    const calls = stubJobs([failed("a1", "TIMEOUT")]);
    render(themed(<JobLogsPanel />));
    await screen.findByRole("table", { name: "Job logs" });
    expect(screen.queryByTestId("failed-summary")).toBeNull();
    expect(calls.some((c) => c.includes("since="))).toBe(false);
  });
});
