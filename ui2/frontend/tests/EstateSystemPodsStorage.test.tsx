import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { PodStatusView, PodsView, StorageView } from "../src/auth/adminApi";
import { EstateSystemPodsStorage, PODS_ROW_CAP } from "../src/estate/EstateSystemPodsStorage";
import { EvidenceStamp } from "../src/estate/EstatePrimitives";
import type { TimedRead } from "../src/estate/estateSystemModel";
import { formatBytes } from "../src/screens/SystemStatusPanel";

// Synthetic fixtures only (FW-TANGO-04 style names, 192.0.2.x addresses). Nothing here names a real host.
const AT = "2026-11-22T08:00:00.000Z";
const NOW = new Date("2026-11-22T08:02:00.000Z");
const GIB = 1024 * 1024 * 1024;

const pod = (name: string, state: string, restarts = 0): PodStatusView => ({
  name,
  component: "synthetic",
  phase: "Running",
  state,
  ready: "1/1",
  restarts,
  started_at: AT,
  image_digest: null,
  cpu_millicores: null,
  cpu_limit_millicores: null,
  memory_bytes: null,
  memory_limit_bytes: null,
});

const podsView = (pods: PodStatusView[], over: Partial<PodsView> = {}): PodsView => ({
  available: true,
  read_at: AT,
  pods,
  ...over,
});

const storageView = (over: Partial<StorageView["artefact_volume"]> = {}, database = 3 * GIB): StorageView => ({
  read_at: AT,
  backups: [],
  top_devices: [],
  configuration: { artefacts: 0, stored_bytes: 0, text_in_database_bytes: 0 },
  database_bytes: database,
  artefact_volume: { total_bytes: 10 * GIB, used_bytes: 2 * GIB, ...over },
});

const ok = <T,>(value: T, readAt: string | null = AT): TimedRead<T> => ({ read: { state: "ok", value }, readAt });
const failed = <T,>(): TimedRead<T> => ({ read: { state: "failed" }, readAt: null });
const restricted = <T,>(): TimedRead<T> => ({ read: { state: "restricted" }, readAt: AT });

describe("Estate System: Pods and storage section", () => {
  it("renders each pod with its state word and restart count, and counts the pods by state", () => {
    render(
      <EstateSystemPodsStorage
        pods={ok(podsView([pod("pod-tango-01", "Running", 0), pod("pod-tango-02", "Running", 3), pod("pod-tango-03", "Pending", 1)]))}
        storage={ok(storageView())}
        now={NOW}
      />,
    );
    const rows = screen.getAllByTestId("estate-system-pod-row");
    expect(rows).toHaveLength(3);
    expect(within(rows[0]).getByText("pod-tango-01")).toBeTruthy();
    expect(within(rows[0]).getByText("Running")).toBeTruthy();
    expect(within(rows[1]).getByText("restarts: 3")).toBeTruthy();
    expect(within(rows[2]).getByText("Pending")).toBeTruthy();
    expect(screen.getByText("Pods listed")).toBeTruthy();
    expect(screen.getByText("Pods in state Running")).toBeTruthy();
    expect(screen.getByText("Pods in state Pending")).toBeTruthy();
  });

  it("shows a pod with a missing or unlisted state as UNKNOWN, never as Running", () => {
    const missing = { ...pod("pod-tango-04", "Running"), state: undefined as unknown as string };
    render(
      <EstateSystemPodsStorage
        pods={ok(podsView([missing, pod("pod-tango-05", "Ready"), pod("pod-tango-06", "Running")]))}
        storage={ok(storageView())}
        now={NOW}
      />,
    );
    const rows = screen.getAllByTestId("estate-system-pod-row");
    expect(rows[0].getAttribute("data-state")).toBe("UNKNOWN");
    expect(rows[1].getAttribute("data-state")).toBe("UNKNOWN");
    expect(rows[1].textContent).not.toContain("Running");
    expect(rows[2].getAttribute("data-state")).toBe("Running");
    expect(screen.getByText("Pods in state UNKNOWN")).toBeTruthy();
  });

  it("caps the pod rows at 50 and prints +10 more pods for 60 pods", () => {
    const many = Array.from({ length: 60 }, (_, i) => pod(`pod-tango-${String(i).padStart(2, "0")}`, "Running"));
    render(<EstateSystemPodsStorage pods={ok(podsView(many))} storage={ok(storageView())} now={NOW} />);
    expect(PODS_ROW_CAP).toBe(50);
    expect(screen.getAllByTestId("estate-system-pod-row")).toHaveLength(50);
    expect(screen.getByTestId("estate-system-pods-more").textContent).toBe("+10 more pods");
    expect(screen.getByText("Pods listed").nextElementSibling?.textContent).toBe("60");
  });

  it("renders storage figures through formatBytes, with the evidence stamp from this read", () => {
    const { container } = render(<EstateSystemPodsStorage pods={ok(podsView([]))} storage={ok(storageView({ total_bytes: 10 * GIB, used_bytes: 2 * GIB }, 3 * GIB))} now={NOW} />);
    expect(screen.getByText(`${formatBytes(2 * GIB)} of ${formatBytes(10 * GIB)}`)).toBeTruthy();
    expect(screen.getByText(formatBytes(3 * GIB))).toBeTruthy();
    // The stamp is the age of this read (AT, two minutes before NOW), so it matches a stamp built from the same instant.
    const stamp = render(<EvidenceStamp at={AT} source="pods and storage" now={NOW} />).container.textContent;
    expect(stamp).not.toBe("UNKNOWN");
    expect(container.textContent).toContain(stamp);
  });

  it("shows UNKNOWN for a missing storage figure", () => {
    render(<EstateSystemPodsStorage pods={ok(podsView([]))} storage={ok(storageView({ total_bytes: undefined, used_bytes: undefined }))} now={NOW} />);
    expect(screen.getByText("UNKNOWN of UNKNOWN")).toBeTruthy();
  });

  it("shows an error line and no pod rows when the pod read fails, and still shows storage", () => {
    render(<EstateSystemPodsStorage pods={failed<PodsView>()} storage={ok(storageView())} now={NOW} />);
    expect(screen.queryAllByTestId("estate-system-pod-row")).toHaveLength(0);
    expect(screen.getByText("Pods could not be read: UNKNOWN. This is not a clean state.")).toBeTruthy();
    expect(screen.queryByText("Your role cannot read pods and storage.")).toBeNull();
    expect(screen.getByText("Artefact volume, used of total")).toBeTruthy();
  });

  it("shows the restricted sentence for a restricted read, not a clean state", () => {
    render(<EstateSystemPodsStorage pods={restricted<PodsView>()} storage={restricted<StorageView>()} now={NOW} />);
    expect(screen.getByText("Your role cannot read pods and storage.")).toBeTruthy();
    expect(screen.queryAllByTestId("estate-system-pod-row")).toHaveLength(0);
    expect(screen.queryByText("Artefact volume, used of total")).toBeNull();
  });

  it("keeps a readable storage read visible when the pods read is restricted", () => {
    render(<EstateSystemPodsStorage pods={restricted<PodsView>()} storage={ok(storageView())} now={NOW} />);
    expect(screen.getByText("Artefact volume, used of total")).toBeTruthy();
    expect(screen.getByText(`${formatBytes(2 * GIB)} of ${formatBytes(10 * GIB)}`)).toBeTruthy();
    expect(screen.getByText("Your role cannot read pods.")).toBeTruthy();
    expect(screen.queryByText("Your role cannot read pods and storage.")).toBeNull();
    expect(screen.queryByText("Your role cannot read storage.")).toBeNull();
    expect(screen.queryAllByTestId("estate-system-pod-row")).toHaveLength(0);
  });

  it("keeps readable pod rows visible when the storage read is restricted", () => {
    render(
      <EstateSystemPodsStorage
        pods={ok(podsView([pod("pod-tango-01", "Running", 0), pod("pod-tango-02", "Pending", 1)]))}
        storage={restricted<StorageView>()}
        now={NOW}
      />,
    );
    expect(screen.getAllByTestId("estate-system-pod-row")).toHaveLength(2);
    expect(screen.getByText("Pods listed")).toBeTruthy();
    expect(screen.getByText("Your role cannot read storage.")).toBeTruthy();
    expect(screen.queryByText("Your role cannot read pods and storage.")).toBeNull();
    expect(screen.queryByText("Artefact volume, used of total")).toBeNull();
    // One read is refused, so the section is not "Restricted" and not "Counted": it is UNKNOWN.
    expect(screen.getByText("UNKNOWN")).toBeTruthy();
    expect(screen.queryByText("Restricted")).toBeNull();
  });

  it("links to the standard System view with standard=1 and states it is read-only", () => {
    render(<EstateSystemPodsStorage pods={ok(podsView([]))} storage={ok(storageView())} now={NOW} />);
    expect(screen.getByText("Read-only. Changes happen in the standard view.")).toBeTruthy();
    const link = screen.getByRole("link", { name: /Open system status \(standard\)/ });
    expect(link.getAttribute("href")).toBe("?screen=administration&view=system&standard=1");
  });
});
