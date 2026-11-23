import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getClusterInventory } from "../src/auth/adminApi";
import {
  dossierClusterStandardHref,
  EstateDossierClusterInterfacesBlock,
} from "../src/estate/EstateDossierClusterInterfacesBlock";
import { DOSSIER_CLUSTER_NOT_CLUSTER_TEXT } from "../src/estate/estateDossierClusterModel";

vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return { ...actual, getClusterInventory: vi.fn() };
});

const mockedInventory = vi.mocked(getClusterInventory);

// Synthetic identifiers only (FW-TANGO-04 style names, documentation address ranges).
const DEVICE = "dev-tango-04";
const CLUSTER = "clu-romeo-01";
const NOW = new Date("2026-11-23T12:00:00Z");

function iface(name: string) {
  return { name, kind: "ethernet", addresses: [], presence: "all", differences: [] };
}

function route(destination: string) {
  return { destination, next_hop: "192.0.2.1", interface: "eth1", protocol: "static", presence: "all", differences: [] };
}

const PAYLOAD = {
  cluster_member_ref: CLUSTER,
  members: [{ device_id: DEVICE, hostname: "FW-TANGO-04" }],
  contexts: [
    {
      context: "physical",
      interfaces: [iface("eth1"), iface("eth2"), iface("eth3"), iface("eth4"), iface("eth5"), iface("eth6")],
      routes: [route("0.0.0.0/0")],
    },
    { context: "vs-1", vs_name: "VS-ROMEO-01", interfaces: [iface("eth9")], routes: [route("198.51.100.0/24"), route("203.0.113.0/24")] },
  ],
};

beforeEach(() => {
  mockedInventory.mockReset();
});

describe("EstateDossierClusterInterfacesBlock", () => {
  it("renders per-context counts with their evidence stamp and the capped list's +N more", async () => {
    mockedInventory.mockResolvedValue(PAYLOAD as never);
    render(<EstateDossierClusterInterfacesBlock deviceId={DEVICE} clusterRef={CLUSTER} now={NOW} />);
    await waitFor(() => expect(screen.getByTestId("dossier-cluster")).toBeTruthy());
    expect(mockedInventory).toHaveBeenCalledWith(CLUSTER);
    expect(screen.getByText("6 interfaces")).toBeTruthy();
    expect(screen.getByText("1 routes")).toBeTruthy();
    expect(screen.getByText("1 interfaces")).toBeTruthy();
    expect(screen.getByText("2 routes")).toBeTruthy();
    expect(screen.getByTestId("dossier-cluster-interfaces-more").textContent).toBe("+1 more interfaces");
    expect(screen.getByText("VS-ROMEO-01")).toBeTruthy();
    // No collection timestamp in the payload: the age is stated as UNKNOWN, not taken from the read time.
    expect(screen.getAllByText(/evidence time UNKNOWN/).length).toBeGreaterThan(0);
    expect(screen.queryByTestId("dossier-cluster-unknown")).toBeNull();
  });

  it("renders UNKNOWN, not OK, when the read rejects", async () => {
    mockedInventory.mockRejectedValue(new Error("unavailable"));
    render(<EstateDossierClusterInterfacesBlock deviceId={DEVICE} clusterRef={CLUSTER} now={NOW} />);
    await waitFor(() => expect(screen.getByTestId("dossier-cluster-unknown")).toBeTruthy());
    expect(screen.getByText("UNKNOWN")).toBeTruthy();
    expect(screen.queryByText("OK")).toBeNull();
    expect(screen.queryByTestId("dossier-cluster")).toBeNull();
    expect(screen.queryByText("0 interfaces")).toBeNull();
  });

  it("links to the standard Inventory view with standard=1", async () => {
    mockedInventory.mockResolvedValue(PAYLOAD as never);
    render(<EstateDossierClusterInterfacesBlock deviceId={DEVICE} clusterRef={CLUSTER} now={NOW} />);
    const link = await screen.findByText("Open cluster interfaces and routes (standard view)");
    const href = link.closest("a")?.getAttribute("href") ?? "";
    expect(href).toContain("standard=1");
    expect(href).toBe(dossierClusterStandardHref(DEVICE));
    expect(new URLSearchParams(href.slice(1)).get("screen")).toBe("inventory");
  });

  it("says Not a cluster unit and makes no read for a non-clustered unit", () => {
    render(<EstateDossierClusterInterfacesBlock deviceId={DEVICE} clusterRef={null} now={NOW} />);
    expect(screen.getByTestId("dossier-cluster-not-cluster").textContent).toBe(DOSSIER_CLUSTER_NOT_CLUSTER_TEXT);
    expect(mockedInventory).not.toHaveBeenCalled();
  });

  it("shows no raw identifiers beyond the synthetic fixture names", async () => {
    mockedInventory.mockResolvedValue(PAYLOAD as never);
    const { container } = render(<EstateDossierClusterInterfacesBlock deviceId={DEVICE} clusterRef={CLUSTER} now={NOW} />);
    await waitFor(() => expect(screen.getByTestId("dossier-cluster")).toBeTruthy());
    const text = container.textContent ?? "";
    expect(text).not.toContain("FW-TANGO-04");
    expect(text).not.toContain(CLUSTER);
    expect(text).not.toContain(DEVICE);
  });
});
