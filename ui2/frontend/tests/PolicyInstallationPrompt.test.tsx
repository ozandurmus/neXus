import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PolicyScreen } from "../src/screens/PolicyScreen";
import type { PolicyMetadata } from "../src/auth/adminApi";

/** Policy > Installation and Objects with no container chosen (UI review 2026-10-18, P0-4B): a prompt and the container list, never a blank pane. */
const policy = (id: string, container: string, targets: PolicyMetadata["targets"]): PolicyMetadata => ({
  id, sourceId: "source-1", sourceName: "MGR-BRAVO-01", vendor: "CP", containerId: container, containerName: `DOM-${container}`, name: `POL-${id}`,
  collectedAt: "2026-10-05T00:00:00Z", artefactRef: "artifact-1", targets, ruleCount: 4,
});
const catalog = [
  policy("p1", "one", [{ deviceId: "d1", name: "FW-SYNTH-01", context: "", syncStatus: "IN_SYNC" }, { deviceId: "d2", name: "FW-SYNTH-02", context: "", syncStatus: "IN_SYNC" }]),
  policy("p2", "two", []),
];
const installationPage = { installations: [{ id: "g1", policyId: "p1", policyName: "POL-p1", name: "FW-SYNTH-01", deviceId: "d1", allTargets: true, targeted: true, installed: null }],
  types: [{ type: "hosts", status: "RESOLVED", collectedAt: "2026-10-05T00:00:00Z", objects: 1 }], total: 1, page: 0, pageSize: 200 };

function stub() {
  vi.stubGlobal("fetch", vi.fn(async (url: string) => {
    const body = url.endsWith("/sources") ? { sources: [{ sourceId: "source-1", sourceName: "MGR-BRAVO-01", vendor: "CP" }], canCollect: false }
      : url.endsWith("/devices") ? { policies: catalog, devices: [] }
      : url.includes("/domains/") ? installationPage
      : { metadata: catalog[0], sections: [], objects: [], page: 0, pageSize: 200, total: 0 };
    return new Response(JSON.stringify(body), { status: 200, headers: { "X-Nexus-Masked": "true" } });
  }));
}
afterEach(() => vi.unstubAllGlobals());
const mount = () => render(<ThemeProvider theme={m3Theme}><PolicyScreen /></ThemeProvider>);

it("shows Installation as two columns of the container table, with the management server's status, until a container is opened", async () => {
  stub(); mount();
  await screen.findByRole("button", { name: "MGR-BRAVO-01", expanded: true });
  // Installation is no tab (UI review 2026-11-05, C2): it is a column of Rules & Containers.
  expect(screen.queryByRole("tab", { name: "Installation" })).toBeNull();
  const table = await screen.findByRole("table", { name: "Stored policy containers" });
  const headers = within(table).getAllByRole("columnheader").map(cell => cell.textContent);
  expect(headers).toEqual(["Container", "Source", "Policies", "Rules", "Installation targets", "Installation status", "Last collected", ""]);
  const rows = within(table).getAllByRole("row").slice(1);
  expect(rows).toHaveLength(2);
  expect(within(rows[0]).getByText("DOM-one")).toBeInTheDocument();
  expect(within(rows[0]).getByText("FW-SYNTH-01, FW-SYNTH-02")).toBeInTheDocument();
  expect(within(rows[0]).getByText("2 in sync")).toHaveAttribute("data-installation", "good");
  expect(within(rows[1]).getByText("Unassigned")).toBeInTheDocument();
  expect(within(rows[1]).getByText("No target")).toBeInTheDocument();
  // Last collected is inked by age: the 2026-10-05 collection is weeks old, never read as current.
  expect(rows[0].querySelector("[data-freshness]")).toHaveAttribute("data-freshness", "old");
});

it("opens a container from the list, then the Installation section shows that container's installation table", async () => {
  stub(); mount();
  await screen.findByRole("button", { name: "MGR-BRAVO-01", expanded: true });
  fireEvent.click(await screen.findByRole("button", { name: "Open DOM-one" }));
  const section = await screen.findByRole("region", { name: "Installation" });
  // Nothing is read until the section is asked for.
  expect(within(section).queryByRole("table", { name: "Policy installation" })).toBeNull();
  fireEvent.click(within(section).getByRole("button", { name: /Installation: where this container's packages are installed/ }));
  expect(await within(section).findByRole("table", { name: "Policy installation" })).toHaveTextContent("FW-SYNTH-01");
  expect(screen.getByRole("tab", { name: "Rules & Containers" })).toHaveAttribute("aria-selected", "true");
});

it("gives Objects the same prompt instead of a blank card", async () => {
  stub(); mount();
  await screen.findByRole("button", { name: "MGR-BRAVO-01", expanded: true });
  fireEvent.click(screen.getByRole("tab", { name: "Objects" }));
  expect(await screen.findByText("Select a policy container to see its objects")).toBeInTheDocument();
  expect(screen.getByRole("table", { name: "Containers" })).toBeInTheDocument();
});
