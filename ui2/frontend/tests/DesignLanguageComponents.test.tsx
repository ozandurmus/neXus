import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";

import { m3, m3Theme } from "../src/theme/m3Theme";
import { StatusPill } from "../src/shell/StatusPill";
import { StatusChip } from "../src/shell/M3Widgets";
import { ObjectHeader } from "../src/shell/ObjectHeader";
import { KpiCard, KpiRow } from "../src/shell/KpiCard";
import { DataTable, PaginationFooter } from "../src/shell/DataTable";
import { RestrictedPanel, StatePanel, Ts, Unknown } from "../src/shell/States";
import { Stamp } from "../src/shell/PresentedText";
import { Timestamp } from "../src/shell/Timestamp";
import { EmptyPanel, MetricCard, ScreenRoot } from "../src/shell/ScreenLayout";
import { DensityScope } from "../src/shell/displayMode";
import { statusWord } from "../src/shell/presenters";

/**
 * The seven unified components (design language 2026-10-19, `docs/design/UI_DESIGN_LANGUAGE.md`), each asserted on
 * its own: status pill, object header, KPI card, data table, state card, label map, timestamp. The older names
 * (`StatusChip`, `MetricCard`, `EmptyPanel`, `Ts`, `Stamp`) render the same implementation.
 */
const wrap = (node: React.ReactElement) => render(<ThemeProvider theme={m3Theme}>{node}</ThemeProvider>);
const NOW = new Date("2026-10-19T10:00:00Z");

describe("status pill", () => {
  it("writes word · count · age · suffix in that order", () => {
    wrap(<StatusPill tone="bad" word="Failed" count={27} age="2026-10-19T06:00:00Z" suffix="Role: not permitted" now={NOW} />);
    const pill = screen.getByText("Failed").closest("[data-pill]") as HTMLElement;
    expect(pill.textContent).toBe("Failed · 27 · 4 h ago · Role: not permitted");
    expect(pill).toHaveAttribute("data-pill", "bad");
  });

  it("hatches the unknown words even when a caller passes neutral, and never softens bad", () => {
    wrap(<><StatusPill tone="neutral" word="UNKNOWN" /><StatusPill tone="neutral" word="0" /><StatusPill tone="bad" word="Not ready" /></>);
    expect(screen.getByText("UNKNOWN").closest("[data-pill]")).toHaveAttribute("data-pill", "unknown");
    expect(screen.getByText("0").closest("[data-pill]")).toHaveAttribute("data-pill", "neutral");
    expect(screen.getByText("Not ready").closest("[data-pill]")).toHaveAttribute("data-pill", "bad");
  });

  it("is a link with href, a button with onClick, plain text otherwise, and carries its fact key", () => {
    const onClick = vi.fn();
    wrap(<><StatusPill tone="ok" word="Ready" href="?screen=operations" fact="readiness" /><StatusPill tone="neutral" word="Queue" onClick={onClick} /><StatusPill tone="neutral" word="Plain" /></>);
    expect(screen.getByText("Ready").closest("a")).toHaveAttribute("href", "?screen=operations");
    expect(screen.getByText("Ready").closest("[data-fact]")).toHaveAttribute("data-fact", "readiness");
    fireEvent.click(screen.getByRole("button", { name: "Queue" }));
    expect(onClick).toHaveBeenCalled();
    expect(screen.getByText("Plain").closest("a, button")).toBeNull();
  });

  it("renders StatusChip through the same implementation", () => {
    wrap(<StatusChip tone="warn" label="stale" dense />);
    expect(screen.getByText("stale").closest("[data-pill]")).toHaveAttribute("data-pill", "warn");
  });
});

describe("object header", () => {
  it("has a title row with badges and one action, an identity row and a state row", () => {
    wrap(
      <ObjectHeader title="FW-TANGO-04" badges={<StatusPill tone="ok" word="Live" />} action={<button type="button">Collect now</button>}
        identity={<><span>Check Point ClusterXL</span><span>R81.20</span></>} readAges={<span>Platform read 4 h ago</span>}
        state={<StatusPill tone="bad" word="Compliance 3 critical failing" />} link={<a href="?screen=policy">Open assigned policy</a>} />,
    );
    expect(screen.getByRole("heading", { name: "FW-TANGO-04" })).toBeInTheDocument();
    expect(within(screen.getByTestId("object-title-row")).getByText("Live")).toBeInTheDocument();
    expect(within(screen.getByTestId("object-action")).getByRole("button", { name: "Collect now" })).toBeInTheDocument();
    expect(within(screen.getByTestId("object-identity-row")).getByText("R81.20")).toBeInTheDocument();
    const state = screen.getByTestId("object-state-row");
    expect(within(state).getByText("Platform read 4 h ago")).toBeInTheDocument();
    expect(within(state).getByText("Compliance 3 critical failing")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open assigned policy" })).toBeInTheDocument();
    expect(screen.getByTestId("entity-header")).toHaveAttribute("data-density", "comfortable");
  });

  it("leaves the rows out when nothing fills them", () => {
    wrap(<ObjectHeader title="POL-ALPHA-01" />);
    expect(screen.queryByTestId("object-identity-row")).toBeNull();
    expect(screen.queryByTestId("object-state-row")).toBeNull();
  });
});

describe("KPI card", () => {
  it("shows label, figure, unit, sentence and one link", () => {
    wrap(<KpiRow><KpiCard label="Failed jobs" value={27} unit="of 906" sentence="last 24 h" href="?screen=operations&state=FAILED" linkLabel="All failed →" /></KpiRow>);
    expect(screen.getByText("Failed jobs")).toBeInTheDocument();
    expect(screen.getByText("27")).toHaveAttribute("data-kpi-figure");
    expect(screen.getByText("of 906")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "All failed →" })).toHaveAttribute("href", "?screen=operations&state=FAILED");
  });

  it("writes UNKNOWN for an absent figure and Not evaluated for an evaluation that has not run, never 0", () => {
    wrap(<><KpiCard label="A" value={null} sentence="s" /><KpiCard label="B" value={5} state="not_evaluated" sentence="s" reason="not run" /></>);
    expect(screen.getByText("UNKNOWN")).toHaveAttribute("data-kpi-word");
    expect(screen.getByText("Not evaluated")).toHaveAttribute("title", "not run");
    expect(screen.queryByText("5")).toBeNull();
  });

  it("renders MetricCard through the same implementation", () => {
    wrap(<MetricCard title="Clusters ready" value={22} unit="of 45" note="13 unknown" />);
    expect(screen.getByText("22")).toHaveAttribute("data-kpi-figure");
    expect(screen.getByText("13 unknown")).toBeInTheDocument();
  });
});

describe("data table", () => {
  type Row = { id: string; name: string; ip: string; state: string };
  const rows: Row[] = Array.from({ length: 3 }, (_, i) => ({ id: `d${i}`, name: `FW-TANGO-0${i + 1}`, ip: `192.0.2.${i + 1}`, state: "ENROLLED" }));
  const columns = [
    { key: "name", header: "Device", width: 200, render: (r: Row) => r.name },
    { key: "ip", header: "Management address", width: 160, mono: true, render: (r: Row) => r.ip },
    { key: "state", header: "State", render: (r: Row) => statusWord(r.state) },
  ];

  it("lays the columns out at fixed widths, mono for machine values, one line per row", () => {
    wrap(<DataTable ariaLabel="Devices" columns={columns} rows={rows} rowKey={(r) => r.id} />);
    const table = screen.getByRole("table", { name: "Devices" });
    expect(table).toHaveStyle({ tableLayout: "fixed" });
    const cols = table.querySelectorAll("colgroup col");
    expect(cols[0]).toHaveStyle({ width: "200px" });
    expect(cols[1]).toHaveStyle({ width: "160px" });
    const ip = within(table).getByText("192.0.2.1");
    expect(getComputedStyle(ip).fontFamily).toContain("Mono");
    expect(ip).toHaveAttribute("title", "192.0.2.1");
    expect(ip).toHaveStyle({ whiteSpace: "nowrap" });
    expect(within(table).getAllByText("Enrolled")).toHaveLength(3);
  });

  it("puts row actions behind a ⋮ menu and pages with 1–50 of N", () => {
    const onPage = vi.fn();
    const onSelect = vi.fn();
    wrap(<DataTable ariaLabel="Devices" columns={columns} rows={rows} rowKey={(r) => r.id}
      actions={(r) => [{ label: `Open ${r.name}`, onSelect }]} page={{ offset: 50, size: 50, total: 131, onPage }} />);
    expect(screen.getByText("51–100 of 131")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Go to next page" }));
    expect(onPage).toHaveBeenCalledWith(100);
    fireEvent.click(screen.getByRole("button", { name: "Go to previous page" }));
    expect(onPage).toHaveBeenCalledWith(0);
    fireEvent.click(screen.getByRole("button", { name: "Actions for row d0" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Open FW-TANGO-01" }));
    expect(onSelect).toHaveBeenCalled();
  });

  it("says why there are no rows in words and disables the ends of the pager", () => {
    wrap(<DataTable ariaLabel="Devices" columns={columns} rows={[]} rowKey={(r) => r.id} emptyText="No device matches these filters." page={{ offset: 0, size: 50, total: 0, onPage: () => {} }} />);
    expect(screen.getByText("No device matches these filters.")).toBeInTheDocument();
    expect(screen.getByText("0–0 of 0")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Go to next page" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Go to previous page" })).toBeDisabled();
  });

  it("pages an unknown total by whether the server said there is more, and offers sizes where a screen has them", () => {
    const onSize = vi.fn();
    wrap(<><PaginationFooter offset={50} size={50} total={null} shown={50} hasMore onPage={() => {}} /><PaginationFooter offset={0} size={25} total={300} onPage={() => {}} sizes={[25, 50, 100]} onSize={onSize} /></>);
    expect(screen.getByText("51–100")).toBeInTheDocument();
    fireEvent.change(screen.getByRole("combobox", { name: "Rows per page" }), { target: { value: "100" } });
    expect(onSize).toHaveBeenCalledWith(100);
  });

  // Post-release review 2026-10-20 (N15): the Jobs history runs to hundreds of pages; first/last join the same footer.
  it("adds first- and last-page buttons only where the caller asks and the total is known", () => {
    const onPage = vi.fn();
    const { unmount } = wrap(<PaginationFooter offset={100} size={50} total={22_917} noun="jobs" jump onPage={onPage} />);
    expect(screen.getByText("101–150 of 22,917 jobs")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Go to last page" }));
    expect(onPage).toHaveBeenLastCalledWith(22_900);
    fireEvent.click(screen.getByRole("button", { name: "Go to first page" }));
    expect(onPage).toHaveBeenLastCalledWith(0);
    unmount();
    wrap(<><PaginationFooter offset={0} size={50} total={131} onPage={() => {}} /><PaginationFooter offset={0} size={50} total={null} hasMore jump onPage={() => {}} /></>);
    expect(screen.queryByRole("button", { name: "Go to last page" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Go to first page" })).toBeNull();
  });
});

describe("state card", () => {
  it("has an icon, a state word, one sentence and one next step, as a status region", () => {
    wrap(<StatePanel variant="unknown" title="Readiness" body="No readiness check has run for this cluster." nextStep="Next window 12:00 (GMT+3)." />);
    const card = screen.getByRole("status");
    expect(card).toHaveAttribute("data-state", "unknown");
    expect(within(card).getByText("UNKNOWN")).toHaveAttribute("data-state-word");
    expect(within(card).getByText("Next window 12:00 (GMT+3).")).toHaveAttribute("data-next-step");
  });

  it("writes Not evaluated in sentence case and names the roles on a refusal without a Retry", () => {
    wrap(<><StatePanel variant="not_evaluated" title="Compliance" /><RestrictedPanel area="Credentials" role="security_admin" nextStep="Security Administrators can rotate them." /></>);
    expect(screen.getByText("Not evaluated")).toBeInTheDocument();
    expect(screen.queryByText("NOT EVALUATED")).toBeNull();
    expect(screen.getByText("Security Administrators can rotate them.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
  });

  it("renders EmptyPanel as the same card with a heading title and its children inside", () => {
    wrap(<EmptyPanel title="Enrollment" body="Enrolling a device grants read collection only."><span>child</span></EmptyPanel>);
    expect(screen.getByRole("heading", { name: "Enrollment" })).toBeInTheDocument();
    expect(within(document.querySelector("[data-state=empty]") as HTMLElement).getByText("child")).toBeInTheDocument();
    expect(screen.queryByRole("status")).toBeNull();
  });
});

describe("label map", () => {
  it("turns upper-case status tokens into sentence case and keeps UNKNOWN and roles as they are", () => {
    expect(statusWord("COMPLETED")).toBe("Completed");
    expect(statusWord("ENROLLED")).toBe("Enrolled");
    expect(statusWord("NOT EVALUATED")).toBe("Not evaluated");
    expect(statusWord("NOT_EVALUATED")).toBe("Not evaluated");
    expect(statusWord("NOT_READY")).toBe("Not ready");
    expect(statusWord("PENDING_CONFIRMATION")).toBe("Pending confirmation");
    expect(statusWord("UNKNOWN")).toBe("UNKNOWN");
    expect(statusWord("ACTIVE")).toBe("ACTIVE");
    expect(statusWord("Already a sentence")).toBe("Already a sentence");
    expect(statusWord("FW-TANGO-04")).toBe("FW-TANGO-04");
    expect(statusWord("")).toBe("UNKNOWN");
    expect(statusWord(null)).toBe("UNKNOWN");
  });

  it("is what the Unknown marker uses", () => {
    wrap(<Unknown word="NOT EVALUATED" />);
    expect(screen.getByText("Not evaluated")).toBeInTheDocument();
  });
});

describe("timestamp", () => {
  it("writes YYYY-MM-DD HH:MM GMT+3 · n h ago with the UTC instant on hover, in the mono step", () => {
    wrap(<Timestamp at="2026-10-19T06:00:00Z" now={NOW} />);
    const time = screen.getByLabelText("2026-10-19T06:00:00Z (UTC reference)");
    expect(time).toHaveAttribute("datetime", "2026-10-19T06:00:00Z");
    expect(time.textContent).toBe("2026-10-19 09:00 GMT+3· 4 h ago");
    expect(getComputedStyle(time).fontFamily).toContain("Mono");
  });

  it("writes UNKNOWN for an absent value, the age alone in compact, and seconds where asked", () => {
    wrap(<><Timestamp at={null} /><Timestamp at="2026-10-19T06:00:00Z" now={NOW} compact /><Timestamp at="2026-10-19T06:00:30Z" now={NOW} seconds age={false} /></>);
    expect(screen.getByText("UNKNOWN")).toHaveAttribute("data-stamp", "UNKNOWN");
    expect(screen.getByText("4 h ago")).toHaveAttribute("title", "2026-10-19 09:00 GMT+3 · 2026-10-19T06:00:00Z (UTC reference)");
    expect(screen.getByText("2026-10-19 09:00:30 GMT+3")).toBeInTheDocument();
  });

  it("renders Ts and Stamp through the same implementation", () => {
    wrap(<><Ts at="2026-10-19T06:00:00Z" now={NOW} /><Stamp at="2026-10-19T07:00:00Z" now={NOW} /></>);
    expect(screen.getByText("2026-10-19 09:00 GMT+3")).toBeInTheDocument();
    expect(screen.getByText("2026-10-19 10:00 GMT+3")).toBeInTheDocument();
    expect(screen.getAllByText(/ago$/)).toHaveLength(2);
  });
});

describe("density scope", () => {
  it("is compact for a list screen and comfortable for an object page, by screen type and never by a toggle", () => {
    wrap(<><ScreenRoot screenType="list"><span>list</span></ScreenRoot><ScreenRoot screenType="object"><span>object</span></ScreenRoot></>);
    expect(screen.getByText("list").closest("[data-density]")).toHaveAttribute("data-density", "compact");
    expect(screen.getByText("object").closest("[data-density]")).toHaveAttribute("data-density", "comfortable");
  });

  it("can be named outright and nests", () => {
    wrap(<DensityScope density="compact"><span>a</span><DensityScope density="comfortable"><span>b</span></DensityScope></DensityScope>);
    expect(screen.getByText("a").closest("[data-density]")).toHaveAttribute("data-density", "compact");
    expect(screen.getByText("b").closest("[data-density]")).toHaveAttribute("data-density", "comfortable");
  });
});

it("keeps the role table reachable from the pill through m3 variables", () => {
  wrap(<StatusPill tone="bad" word="Failed" />);
  expect(screen.getByText("Failed").closest("[data-pill]")).toHaveStyle({ backgroundColor: m3.errorContainer });
});
