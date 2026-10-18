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

it("prompts for a container and lists every stored container with its installation targets", async () => {
  stub(); mount();
  await screen.findByRole("button", { name: "MGR-BRAVO-01", expanded: true });
  fireEvent.click(screen.getByRole("tab", { name: "Installation" }));
  expect(await screen.findByText("Select a policy container to see where its packages are installed")).toBeInTheDocument();
  expect(screen.getByText(/package intent, not runtime enforcement/)).toBeInTheDocument();
  const table = screen.getByRole("table", { name: "Containers and installation targets" });
  expect(within(table).getByRole("columnheader", { name: "Installed on (targets)" })).toBeInTheDocument();
  const rows = within(table).getAllByRole("row").slice(1);
  expect(rows).toHaveLength(2);
  expect(within(rows[0]).getByText("DOM-one")).toBeInTheDocument();
  expect(within(rows[0]).getByText("FW-SYNTH-01, FW-SYNTH-02")).toBeInTheDocument();
  expect(within(rows[1]).getByText("Unassigned")).toBeInTheDocument();
  expect(screen.getByText("2 stored containers.")).toBeInTheDocument();
});

it("opens a container from the list into that container's installation table, in the same tab", async () => {
  stub(); mount();
  await screen.findByRole("button", { name: "MGR-BRAVO-01", expanded: true });
  fireEvent.click(screen.getByRole("tab", { name: "Installation" }));
  fireEvent.click(await screen.findByRole("button", { name: "Open DOM-one" }));
  expect(await screen.findByRole("table", { name: "Policy installation" })).toHaveTextContent("FW-SYNTH-01");
  expect(screen.getByRole("tab", { name: "Installation" })).toHaveAttribute("aria-selected", "true");
  expect(screen.queryByText("Select a policy container to see where its packages are installed")).toBeNull();
});

it("gives Objects the same prompt instead of a blank card", async () => {
  stub(); mount();
  await screen.findByRole("button", { name: "MGR-BRAVO-01", expanded: true });
  fireEvent.click(screen.getByRole("tab", { name: "Objects" }));
  expect(await screen.findByText("Select a policy container to see its objects")).toBeInTheDocument();
  expect(screen.getByRole("table", { name: "Containers" })).toBeInTheDocument();
});
