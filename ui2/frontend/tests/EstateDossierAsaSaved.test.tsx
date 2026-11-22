import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getBackupDeviations, type AsaConfigRelation, type BackupDeviations } from "../src/auth/adminApi";
import { ASA_UNSAVED_HINT } from "../src/screens/AsaSavedConfigDiff";
import { EstateDossierAsaSavedBlock, ASA_SAVED_UNAVAILABLE_TEXT } from "../src/estate/EstateDossierAsaSavedBlock";

vi.mock("../src/auth/adminApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../src/auth/adminApi")>();
  return { ...actual, getBackupDeviations: vi.fn() };
});

const mockedRead = vi.mocked(getBackupDeviations);

// Synthetic identifiers only (FW-TANGO-04 style names, 192.0.2.x addresses).
const DEVICE = "dev-tango-04";
const OTHER = "dev-tango-09";

const rel = (over: Partial<AsaConfigRelation>): AsaConfigRelation => ({
  device_id: DEVICE,
  hostname: "FW-TANGO-04",
  relation: "MISMATCH",
  lines_differing: 4,
  differing_keywords: { "object-group": 1, "access-list": 3 },
  backup_created_at: "2026-11-19T03:00:00Z",
  derived_at: "2026-11-19T03:01:00Z",
  ...over,
});

const deviations = (rows: readonly AsaConfigRelation[]): BackupDeviations => ({
  asa_config_relations: rows,
  active_major_deviations: [],
  total_deviations_checked: rows.length,
});

describe("EstateDossierAsaSavedBlock", () => {
  beforeEach(() => {
    mockedRead.mockReset();
  });

  it("a MISMATCH row for this device renders the note and the keyword words", async () => {
    mockedRead.mockResolvedValue(deviations([rel({})]));
    render(<EstateDossierAsaSavedBlock deviceId={DEVICE} vendor="cisco_asa" />);
    expect(await screen.findByText("Saved config differs")).toBeTruthy();
    const note = screen.getByTestId("asa-saved-config-diff");
    expect(note.textContent).toContain("access-list 3, object-group 1");
    expect(note.textContent).toContain(ASA_UNSAVED_HINT);
    const link = screen.getByRole("link", { name: /Open in standard view/ });
    expect(link.getAttribute("href")).toBe(`?screen=inventory&device_id=${DEVICE}&tab=configuration&standard=1`);
  });

  it("a MATCH row for this device renders nothing", async () => {
    mockedRead.mockResolvedValue(deviations([rel({ relation: "MATCH", lines_differing: 0, differing_keywords: {} })]));
    const { container } = render(<EstateDossierAsaSavedBlock deviceId={DEVICE} vendor="cisco_asa" />);
    await waitFor(() => expect(mockedRead).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 0));
    expect(container.textContent).toBe("");
    expect(screen.queryByTestId("dossier-asa-saved")).toBeNull();
  });

  it("a NOT_EVALUABLE row renders nothing and does not say differs", async () => {
    mockedRead.mockResolvedValue(deviations([rel({ relation: "NOT_EVALUABLE", lines_differing: 0, differing_keywords: {} })]));
    const { container } = render(<EstateDossierAsaSavedBlock deviceId={DEVICE} vendor="cisco_asa" />);
    await waitFor(() => expect(mockedRead).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 0));
    expect(container.textContent).not.toContain("differs");
    expect(container.textContent).toBe("");
  });

  it("a MISMATCH row for a different device renders nothing", async () => {
    mockedRead.mockResolvedValue(deviations([rel({ device_id: OTHER, hostname: "FW-TANGO-09" })]));
    const { container } = render(<EstateDossierAsaSavedBlock deviceId={DEVICE} vendor="cisco_asa" />);
    await waitFor(() => expect(mockedRead).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 0));
    expect(container.textContent).toBe("");
  });

  it("a rejected read renders no note and no OK text; an ASA says the comparison is unavailable", async () => {
    mockedRead.mockRejectedValue(new Error("synthetic read failure"));
    const { container } = render(<EstateDossierAsaSavedBlock deviceId={DEVICE} vendor="fortigate" />);
    await waitFor(() => expect(mockedRead).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 0));
    expect(container.textContent).toBe("");

    mockedRead.mockRejectedValue(new Error("synthetic read failure"));
    render(<EstateDossierAsaSavedBlock deviceId={DEVICE} vendor="cisco_asa" />);
    expect(await screen.findByText(ASA_SAVED_UNAVAILABLE_TEXT)).toBeTruthy();
    expect(screen.queryByText(/differs/)).toBeNull();
    expect(screen.queryByText(/\bOK\b/)).toBeNull();
  });
});
