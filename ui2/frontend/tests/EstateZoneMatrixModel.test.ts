import { describe, expect, it } from "vitest";
import type { MatrixCellView, ServiceSetView, ZoneVersionDetail } from "../src/screens/zonematrix/zoneMatrixApi";
import {
  ZONE_CELL_SERVICES_CAP,
  ZONE_MATRIX_ROW_CAP,
  ZONE_SERVICE_SET_ENTRIES_CAP,
  ZONE_SERVICE_SETS_CAP,
  zoneMatrixGridModel,
} from "../src/estate/estateZoneMatrixModel";

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

  it("projects confirmationLine, unassignedPolicyLine, and templateOriginLine from the matrix header", () => {
    const v = version(2, []);
    const withHeader: ZoneVersionDetail = {
      ...v,
      matrix: {
        ...v.matrix!,
        cells_total: 5,
        cells_with_reference: 3,
        cells_confirmed: 2,
        unassigned_to_unassigned: "DENY_ALL unless an explicit cell says otherwise",
        template_origin: { name: "PCI-DSS-BASELINE", revision: "2026.2" },
      },
    };
    const m = zoneMatrixGridModel(withHeader, NOW);
    expect(m.confirmationUnknown).toBe(false);
    expect(m.confirmationLine).toBe("2 of 5 cells confirmed by compliance (3 cells with reference)");
    expect(m.unassignedPolicyLine).toBe("UNASSIGNED -> UNASSIGNED: DENY_ALL unless an explicit cell says otherwise");
    expect(m.templateOriginLine).toBe("Template origin: PCI-DSS-BASELINE (2026.2)");
  });

  it("marks confirmationUnknown when confirmation counts are missing or NaN and falls back to UNKNOWN for unassigned policy", () => {
    const v = version(1, []);
    const broken: ZoneVersionDetail = {
      ...v,
      matrix: {
        ...v.matrix!,
        cells_total: Number.NaN,
        cells_confirmed: 0,
        unassigned_to_unassigned: "   ",
        template_origin: null,
      },
    };
    const m = zoneMatrixGridModel(broken, NOW);
    expect(m.confirmationUnknown).toBe(true);
    expect(m.confirmationLine).toBeNull();
    expect(m.unassignedPolicyLine).toBe("UNASSIGNED -> UNASSIGNED: UNKNOWN");
    expect(m.templateOriginLine).toBeNull();
  });

  it("formats ALLOW_LISTED cell servicesLine (empty list, non-evaluable reasons, and +N more services cap) and confirmedLine via actorLabel", () => {
    const emptyListed: MatrixCellView = {
      ...cell("z0", "z0", "ALLOW_LISTED", "CONFIRMED"),
      services: [],
      confirmed_by: "SN-0123456789",
    };
    const cappedListed: MatrixCellView = {
      ...cell("z0", "z1", "ALLOW_LISTED", "CONFIRMED"),
      services: Array.from({ length: ZONE_CELL_SERVICES_CAP + 2 }, (_, i) =>
        i === 1
          ? { entry: "app:pan:custom-tls", evaluable: false, not_evaluable_reason: "APP_ID_DYNAMIC" }
          : i === 2
            ? { entry: "app:pan:mystery", evaluable: false, not_evaluable_reason: null }
            : { entry: `tcp/${443 + i}`, evaluable: true, not_evaluable_reason: null },
      ),
      confirmed_by: null,
    };
    const denyCell: MatrixCellView = {
      ...cell("z1", "z0", "DENY_ALL", "PROPOSED"),
      confirmed_by: "SN-0123456789",
    };
    const m = zoneMatrixGridModel(version(2, [emptyListed, cappedListed, denyCell]), NOW);

    const c00 = m.rows[0].cells[0];
    expect(c00.servicesLine).toBe("0 services (empty list)");
    expect(c00.confirmedLine).toBe("Confirmed by Masked actor");

    const c01 = m.rows[0].cells[1];
    expect(c01.servicesLine).toBe(
      "6 services: tcp/443, app:pan:custom-tls (not evaluable: APP_ID_DYNAMIC), app:pan:mystery (not evaluable: UNKNOWN), tcp/446 (+2 more services)",
    );
    expect(c01.confirmedLine).toBe("Confirmed (actor UNKNOWN)");

    const c10 = m.rows[1].cells[0];
    expect(c10.servicesLine).toBeNull();
    expect(c10.confirmedLine).toBeNull();
  });

  it("projects capped serviceSets and caps each set's entries at ZONE_SERVICE_SET_ENTRIES_CAP", () => {
    const sets: ServiceSetView[] = Array.from({ length: ZONE_SERVICE_SETS_CAP + 2 }, (_, idx) => ({
      name: `SET-TANGO-${idx + 1}`,
      entries:
        idx === 0
          ? Array.from({ length: ZONE_SERVICE_SET_ENTRIES_CAP + 2 }, (__, eIdx) =>
              eIdx === 1
                ? { entry: "app:pan:web", evaluable: false, not_evaluable_reason: "L7_APP" }
                : { entry: `tcp/${8000 + eIdx}`, evaluable: true, not_evaluable_reason: null },
            )
          : [{ entry: "udp/53", evaluable: true, not_evaluable_reason: null }],
    }));
    const v = version(1, []);
    const m = zoneMatrixGridModel({ ...v, matrix: { ...v.matrix!, service_sets: sets } }, NOW);

    expect(m.totalServiceSets).toBe(ZONE_SERVICE_SETS_CAP + 2);
    expect(m.shownServiceSets).toBe(ZONE_SERVICE_SETS_CAP);
    expect(m.serviceSets).toHaveLength(ZONE_SERVICE_SETS_CAP);

    expect(m.serviceSets[0]).toEqual({
      name: "SET-TANGO-1",
      countWord: "8 services",
      entriesText: "tcp/8000, app:pan:web (not evaluable: L7_APP), tcp/8002, tcp/8003, tcp/8004, tcp/8005 (+2 more services)",
      moreEntriesCount: 2,
      hasNotEvaluable: true,
    });
    expect(m.serviceSets[1]).toEqual({
      name: "SET-TANGO-2",
      countWord: "1 service",
      entriesText: "udp/53",
      moreEntriesCount: 0,
      hasNotEvaluable: false,
    });
  });
});
