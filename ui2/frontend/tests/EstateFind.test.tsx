import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { useState } from "react";
import { afterEach, describe, expect, it } from "vitest";
import type { EstateCluster, EstateUnit } from "../src/estate/estateModel";
import {
  buildEstateFindIndex,
  findEstateEntries,
  isFindShortcut,
  useEstateFindShortcut,
} from "../src/estate/estateFind";
import { EstateFindPalette } from "../src/estate/EstateFindPalette";

// Synthetic fixtures only (FW-TANGO-04 style names, RFC 5737 documentation addresses).
function unit(deviceId: string, name: string, clusterRef: string | null, managementIp: string | null = null): EstateUnit {
  return { deviceId, name, clusterRef, managementIp, vendorName: "Synthetic vendor" } as unknown as EstateUnit;
}

function cluster(ref: string, title: string, members: readonly EstateUnit[]): EstateCluster {
  return { ref, title, members } as unknown as EstateCluster;
}

const UNITS: readonly EstateUnit[] = [
  unit("dev-1", "FW-TANGO-04", "cls-romeo", "192.0.2.10"),
  unit("dev-2", "FW-JULIET-06", "cls-romeo", "192.0.2.11"),
  unit("dev-3", "FW-BRAVO-02", null, "198.51.100.7"),
];
const CLUSTERS: readonly EstateCluster[] = [cluster("cls-romeo", "CLS-ROMEO-01", [UNITS[0], UNITS[1]])];

describe("estate find matching", () => {
  const index = buildEstateFindIndex(UNITS, CLUSTERS);

  it("finds a unit by name and points it at the dossier", () => {
    const { hits, total } = findEstateEntries(index, "tango");
    expect(total).toBe(1);
    expect(hits[0]).toMatchObject({ kind: "unit", label: "FW-TANGO-04", href: "?screen=inventory&device_id=dev-1" });
  });

  it("finds units by cluster title and names the cluster in the sub line", () => {
    const { hits } = findEstateEntries(index, "romeo");
    const labels = hits.map((h) => h.label);
    expect(labels).toEqual(expect.arrayContaining(["FW-TANGO-04", "FW-JULIET-06", "CLS-ROMEO-01"]));
    expect(hits.find((h) => h.label === "FW-TANGO-04")?.sub).toContain("CLS-ROMEO-01");
  });

  it("requires every term to match", () => {
    expect(findEstateEntries(index, "tango 04").hits.map((h) => h.label)).toEqual(["FW-TANGO-04"]);
    expect(findEstateEntries(index, "tango juliet").total).toBe(0);
  });

  it("matches the management address without ever rendering it", () => {
    const { hits } = findEstateEntries(index, "198.51.100.7");
    expect(hits.map((h) => h.label)).toEqual(["FW-BRAVO-02"]);
    expect(hits[0].label).not.toContain("198.51.100");
    expect(hits[0].sub).not.toContain("198.51.100");
  });

  it("finds Estate screens and lenses by name", () => {
    expect(findEstateEntries(index, "backups").hits.find((h) => h.kind === "screen")?.href).toBe("?screen=backups");
    expect(findEstateEntries(index, "exposure").hits[0]).toMatchObject({ kind: "screen", href: "?screen=inventory&lens=exposure" });
  });

  it("returns the screens for an empty query and caps long lists with the true total", () => {
    const empty = findEstateEntries(index, "   ");
    expect(empty.hits.every((h) => h.kind === "screen")).toBe(true);

    const many = Array.from({ length: 30 }, (_, i) => unit(`dev-x${i}`, `FW-XRAY-${String(i).padStart(2, "0")}`, null));
    const capped = findEstateEntries(buildEstateFindIndex(many, []), "xray", 20);
    expect(capped.hits).toHaveLength(20);
    expect(capped.total).toBe(30);
  });

  it("ranks names that start with the query above names that only contain it", () => {
    const idx = buildEstateFindIndex([unit("a", "CORE-TANGO-01", null), unit("b", "TANGO-DESK-02", null)], []);
    expect(findEstateEntries(idx, "tango").hits.map((h) => h.label)).toEqual(["TANGO-DESK-02", "CORE-TANGO-01"]);
  });
});

describe("estate find keyboard", () => {
  afterEach(() => {
    window.history.replaceState(null, "", "/");
  });

  it("recognises Cmd+K and Ctrl+K only, without Alt or Shift", () => {
    expect(isFindShortcut({ key: "k", metaKey: true, ctrlKey: false, altKey: false, shiftKey: false })).toBe(true);
    expect(isFindShortcut({ key: "K", metaKey: false, ctrlKey: true, altKey: false, shiftKey: false })).toBe(true);
    expect(isFindShortcut({ key: "k", metaKey: true, ctrlKey: false, altKey: false, shiftKey: true })).toBe(false);
    expect(isFindShortcut({ key: "j", metaKey: true, ctrlKey: false, altKey: false, shiftKey: false })).toBe(false);
  });

  function Harness() {
    const [open, setOpen] = useState(false);
    useEstateFindShortcut(() => setOpen((o) => !o));
    return (
      <>
        <button type="button">behind</button>
        <EstateFindPalette open={open} onClose={() => setOpen(false)} units={UNITS} clusters={CLUSTERS} />
      </>
    );
  }

  it("opens with Ctrl+K, filters, moves with arrows, opens the unit on Enter and closes", async () => {
    render(<Harness />);
    expect(screen.queryByRole("dialog")).toBeNull();

    fireEvent.keyDown(window, { key: "k", ctrlKey: true });
    const dialog = await screen.findByRole("dialog");
    const input = within(dialog).getByRole("combobox");
    expect(input).toHaveFocus();

    fireEvent.change(input, { target: { value: "fw" } });
    const listbox = within(dialog).getByRole("listbox");
    const options = within(listbox).getAllByRole("option");
    expect(options.length).toBeGreaterThan(1);
    expect(options[0]).toHaveAttribute("aria-selected", "true");
    expect(input).toHaveAttribute("aria-activedescendant", options[0].id);

    fireEvent.keyDown(input, { key: "ArrowDown" });
    expect(options[1]).toHaveAttribute("aria-selected", "true");

    fireEvent.change(input, { target: { value: "tango" } });
    fireEvent.keyDown(input, { key: "Enter" });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(window.location.search).toBe("?screen=inventory&device_id=dev-1");
  });

  it("closes with Escape and toggles off with a second Cmd+K", async () => {
    render(<Harness />);
    fireEvent.keyDown(window, { key: "k", metaKey: true });
    const dialog = await screen.findByRole("dialog");
    fireEvent.keyDown(within(dialog).getByRole("combobox"), { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    fireEvent.keyDown(window, { key: "k", metaKey: true });
    await screen.findByRole("dialog");
    fireEvent.keyDown(window, { key: "k", metaKey: true });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  });

  it("announces the match count and says when nothing matches", async () => {
    render(<Harness />);
    fireEvent.keyDown(window, { key: "k", ctrlKey: true });
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByRole("combobox"), { target: { value: "no-such-unit-zz" } });
    expect(within(dialog).getByRole("status")).toHaveTextContent("No matches");
    expect(within(dialog).queryAllByRole("option")).toHaveLength(0);
  });
});
