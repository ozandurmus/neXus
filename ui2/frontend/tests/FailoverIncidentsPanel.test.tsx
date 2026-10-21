import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { FailoverIncidentsPanel } from "../src/screens/FailoverIncidentsPanel";

const api = vi.hoisted(() => ({ incidents: vi.fn(), release: vi.fn() }));
vi.mock("../src/auth/adminApi", () => ({ listFailoverIncidents: api.incidents, releaseFailoverIncident: api.release }));

const NOTE = "Only a security_admin can release a failover incident.";
const incident = { incidentRef: "run-opaque", clusterId: "opaque-cluster", unitId: "opaque-cluster", cluster_member_ref: "CLS-ROMEO-01",
  maskedName: "CLS-ROMEO-01", reason: "MUTATION_STOPPED", openedAt: "2026-10-20T10:00:00Z", incidentActive: true, blocksEstate: false,
  unresolvedSends: 0, members: [{ device_id: "opaque-1" }, { device_id: "opaque-2" }] };
const estate = { ...incident, incidentRef: "run-other", clusterId: "opaque-other", cluster_member_ref: "CLS-TANGO-04", maskedName: "CLS-TANGO-04",
  reason: "UNRESOLVED_DISPATCH", incidentActive: false, blocksEstate: true, unresolvedSends: 1 };
const view = (props: { clusterId?: string; onReleased?: () => void } = {}) =>
  render(<ThemeProvider theme={m3Theme}><FailoverIncidentsPanel {...props} /></ThemeProvider>);

beforeEach(() => { vi.resetAllMocks(); api.release.mockResolvedValue({ incidentRef: "run-opaque", status: "RELEASED" }); });

it("security_admin releases with a mandatory reason and sends nothing else", async () => {
  const onReleased = vi.fn();
  api.incidents.mockResolvedValueOnce({ canRelease: true, roleNote: null, incidents: [incident] })
    .mockResolvedValue({ canRelease: true, roleNote: null, incidents: [] });
  view({ onReleased });
  fireEvent.click(await screen.findByRole("button", { name: "Release incident" }));
  const confirm = screen.getByRole("button", { name: "Release" });
  expect(confirm).toBeDisabled();
  fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: "   " } });
  expect(confirm).toBeDisabled();
  fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: "  Former active returned out of band  " } });
  fireEvent.click(confirm); fireEvent.click(confirm);
  await waitFor(() => expect(api.release).toHaveBeenCalledTimes(1));
  expect(api.release).toHaveBeenCalledWith("run-opaque", "Former active returned out of band");
  await waitFor(() => expect(onReleased).toHaveBeenCalledTimes(1));
  await waitFor(() => expect(screen.queryByText(/Failover incident/)).toBeNull());
});

it("a refused release shows the server code and keeps the dialog open", async () => {
  api.incidents.mockResolvedValue({ canRelease: true, roleNote: null, incidents: [incident] });
  api.release.mockRejectedValue({ body: { code: "RUN_STILL_OWNED" } });
  view();
  fireEvent.click(await screen.findByRole("button", { name: "Release incident" }));
  fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: "Reviewed" } });
  fireEvent.click(screen.getByRole("button", { name: "Release" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("RUN_STILL_OWNED");
  expect(screen.getByRole("dialog")).toBeInTheDocument();
});

it.each(["operator", "aiview"])("%s sees the incident and the role note, never the button", async () => {
  api.incidents.mockResolvedValue({ canRelease: false, roleNote: NOTE, incidents: [incident, estate] });
  view();
  expect(await screen.findByText("Failover incident · CLS-ROMEO-01")).toBeInTheDocument();
  expect(screen.getAllByText(NOTE)).toHaveLength(2);
  expect(screen.getByText(/Blocks every failover in the estate \(1 unresolved send\)/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Release incident" })).toBeNull();
});

it("a unit shows its own incidents and estate-wide sends, not another unit's incident", async () => {
  const other = { ...incident, incidentRef: "run-third", clusterId: "opaque-third", maskedName: "CLS-BRAVO-02" };
  api.incidents.mockResolvedValue({ canRelease: false, roleNote: NOTE, incidents: [incident, estate, other] });
  view({ clusterId: "opaque-cluster" });
  expect(await screen.findByText("Failover incident · CLS-ROMEO-01")).toBeInTheDocument();
  expect(screen.getByText("Failover incident · CLS-TANGO-04")).toBeInTheDocument();
  expect(screen.queryByText("Failover incident · CLS-BRAVO-02")).toBeNull();
});

it("renders nothing when there is no incident or the read fails", async () => {
  api.incidents.mockResolvedValue({});
  const first = view(); await waitFor(() => expect(api.incidents).toHaveBeenCalled());
  expect(first.container).toBeEmptyDOMElement(); first.unmount();
  api.incidents.mockRejectedValue({ body: { code: "WRONG_ROLE" } });
  const second = view(); await waitFor(() => expect(api.incidents).toHaveBeenCalledTimes(2));
  expect(second.container).toBeEmptyDOMElement();
});

it("a code-less refusal shows the server's error and the HTTP status", async () => {
  api.incidents.mockResolvedValue({ canRelease: true, roleNote: null, incidents: [incident] });
  api.release.mockRejectedValue({ status: 403, body: { error: "ACTION_MAPPING_REQUIRED" } });
  view();
  fireEvent.click(await screen.findByRole("button", { name: "Release incident" }));
  fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: "Reviewed" } });
  fireEvent.click(screen.getByRole("button", { name: "Release" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("ACTION_MAPPING_REQUIRED (HTTP 403)");
});
