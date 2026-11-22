import { describe, expect, it } from "vitest";
import type { MatrixCellView, ZoneVersionDetail } from "../src/screens/zonematrix/zoneMatrixApi";
import { ZONE_MATRIX_ROW_CAP, zoneMatrixGridModel } from "../src/estate/estateZoneMatrixModel";

const zone = (id: string, name: string) => ({
  zone_id: id, name, description: null, role: null, block_count: 1,
  effective_addresses: { IPV4: "256", IPV6: "0" }, blocks: null,
});

const cell = (source: string, destination: string, statement: MatrixCellView["statement"], reference: MatrixCellView["reference_status"] = "EMPTY"): MatrixCellView => ({
  source, destination, source_name: source, destination_name: destination, statement, services: [],
  severity: "HIGH", rationale: null, regulation_reference: null, reference_status: reference,
  confirmed_by: null, confirmed_at: null, cell_sha256: "0".repeat(64),
});

function version(zoneCount: number, cells: MatrixCellView[], publishedAt: string | null = "2026-11-20T10:00:00Z"): ZoneVersionDetail {
  const zones = Array.from({ length: zoneCount }, (_, i) => zone(`z${i}`, `ZONE-TANGO-${String.fromCharCode(65 + i)}`));
  return {
    version_id: "v3", number: 3, state: "ACTIVE", revision: 4, content_sha256: "1".repeat(64),
    created_at: "2026-11-19T10:00:00Z", updated_at: null, published_by: null, published_at: publishedAt,
    zones, unassigned_addresses: { IPV4: "0", IPV6: "0" }, redundant_blocks: 0, revisions: [],
    matrix: {
      default_statement: "DENY_ALL", default_severity: "LOW", unassigned_to_unassigned: "DENY_ALL", template_origin: null,
      service_sets: [], cells, cells_total: cells.length, cells_with_reference: 0, cells_confirmed: 0,
    },
  } as unknown as ZoneVersionDetail;
}

const NOW = new Date("2026-11-22T10:00:00Z");

describe("zoneMatrixGridModel", () => {
  it("builds one row per source zone and one cell per destination zone from the matrix", () => {
    const v = version(2, [
      cell("z0", "z1", "ALLOW_LISTED", "CONFIRMED"),
      cell("z1", "z0", "ALLOW_ANY"),
    ]);
    const m = zoneMatrixGridModel(v, NOW);
    expect(m.columns.map((c) => c.name)).toEqual(["ZONE-TANGO-A", "ZONE-TANGO-B"]);
    expect(m.rows.map((r) => r.name)).toEqual(["ZONE-TANGO-A", "ZONE-TANGO-B"]);
    const ab = m.rows[0].cells[1];
    expect(ab.word).toBe("Allow listed");
    expect(ab.state).toBe("watch");
    expect(ab.severity).toBe("High");
    expect(ab.reference).toBe("Confirmed");
    expect(m.rows[1].cells[0]).toMatchObject({ word: "Allow any", state: "act" });
  });

  it("caps the visible rows and reports the rest as +N more", () => {
    const v = version(ZONE_MATRIX_ROW_CAP + 3, []);
    const m = zoneMatrixGridModel(v, NOW);
    expect(m.totalRows).toBe(ZONE_MATRIX_ROW_CAP + 3);
    expect(m.shownRows).toBe(ZONE_MATRIX_ROW_CAP);
    expect(m.rows).toHaveLength(ZONE_MATRIX_ROW_CAP);
  });

  it("shows a missing cell as UNKNOWN, never as OK or DENY", () => {
    const v = version(2, [cell("z0", "z1", "DENY_ALL")]);
    const m = zoneMatrixGridModel(v, NOW);
    const missing = m.rows[1].cells[0];
    expect(missing.known).toBe(false);
    expect(missing.state).toBe("unk");
    expect(missing.word).toBe("UNKNOWN");
    expect(missing.word).not.toMatch(/OK|Deny/);
    expect(m.rows[0].cells[1]).toMatchObject({ state: "ok", word: "Deny all" });
  });

  it("labels the version with its own evidence age, not the current time", () => {
    const m = zoneMatrixGridModel(version(1, []), NOW);
    expect(m.evidenceAt).toBe("2026-11-20T10:00:00Z");
    expect(m.versionLabel).toBe("Version 3 · 2 d ago");
  });

  it("marks the age UNKNOWN when the version has no time", () => {
    const m = zoneMatrixGridModel(version(1, [], null), NOW);
    expect(m.evidenceAt).toBe("2026-11-19T10:00:00Z");
    expect(m.versionLabel).toBe("Version 3 · 3 d ago");
  });
});
