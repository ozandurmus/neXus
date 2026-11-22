import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { EstateUnit } from "../src/estate/estateModel";
import type { EstateLens } from "../src/estate/estateRouting";
import { UnitTile } from "../src/estate/EstatePrimitives";

// Synthetic units only (FW-TANGO-04 style names, 192.0.2.x addresses).
const NOW = new Date("2026-11-22T12:00:00Z");
const READ_AT = "2026-11-22T10:00:00Z"; // inventory read, 2 h before NOW
const BACKUP_AT = "2026-11-19T12:00:00Z"; // backup evidence, 3 days before NOW

function answer(lens: string, evidenceAt: string | null, evidenceSource: string) {
  return {
    lens,
    tileState: "ok",
    summary: "Synthetic answer",
    evidenceAt,
    evidenceSource,
  };
}

function unit(lastReadAt: string | null, backups: ReturnType<typeof answer>): EstateUnit {
  const lenses: Record<string, unknown> = {
    age: answer("age", lastReadAt, "inventory"),
    backups,
    worst: answer("worst", lastReadAt, "inventory"),
  };
  return {
    name: "FW-TANGO-04",
    deviceId: "dev-tango-04",
    vendorName: "Synthetic vendor",
    role: "gateway",
    clusterRef: null,
    haRole: null,
    lastReadAt,
    ageBucket: "fresh",
    lenses,
  } as unknown as EstateUnit;
}

function tileLabel(u: EstateUnit, lens: EstateLens): string {
  render(<UnitTile unit={u} lens={lens} now={NOW} />);
  return screen.getByRole("link", { name: /FW-TANGO-04/ }).getAttribute("aria-label") ?? "";
}

describe("Estate wall per-lens age on tiles", () => {
  it("states the lens evidence age under the Backups lens, not the inventory read age", () => {
    const label = tileLabel(unit(READ_AT, answer("backups", BACKUP_AT, "backup")), "backups");
    expect(label).toContain("backup evidence 3 d ago");
    expect(label).not.toContain("2 h ago");
  });

  it("says evidence time unknown under a non-inventory lens with no evidence time and no inventory read", () => {
    const label = tileLabel(unit(null, answer("backups", null, "backup")), "backups");
    expect(label).toContain("backup evidence time unknown");
    expect(label).not.toContain("never read");
  });

  it("says evidence time unknown under a non-inventory lens with no evidence time even when the inventory was read", () => {
    const label = tileLabel(unit(READ_AT, answer("backups", null, "backup")), "backups");
    expect(label).toContain("backup evidence time unknown");
    expect(label).not.toContain("2 h ago");
  });

  it("keeps the inventory age under the age lens", () => {
    const label = tileLabel(unit(READ_AT, answer("backups", BACKUP_AT, "backup")), "age");
    expect(label).toContain("inventory evidence 2 h ago");
  });

  it("says never read under the inventory lens when the inventory was never read", () => {
    const label = tileLabel(unit(null, answer("backups", null, "backup")), "age");
    expect(label).toContain("never read");
  });
});
