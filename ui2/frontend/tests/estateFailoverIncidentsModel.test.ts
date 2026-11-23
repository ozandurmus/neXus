import { describe, expect, it } from "vitest";
import type { FailoverIncident } from "../src/auth/adminApi";
import {
  buildFailoverIncidentsModel,
  FAILOVER_INCIDENT_CAP,
  incidentStatusOf,
} from "../src/estate/estateFailoverIncidentsModel";

function incident(n: number, overrides: Partial<FailoverIncident> = {}): FailoverIncident {
  return {
    incidentRef: `INC-${n}`,
    clusterId: `CLS-${n}`,
    unitId: `UNIT-${n}`,
    cluster_member_ref: `member-${n}`,
    maskedName: `FW-TANGO-0${(n % 9) + 1}`,
    reason: "synthetic",
    openedAt: new Date(Date.UTC(2026, 10, 1, 0, n)).toISOString(),
    incidentActive: true,
    blocksEstate: false,
    unresolvedSends: 0,
    members: [],
    ...overrides,
  };
}

const label = (i: FailoverIncident) => i.maskedName ?? "unlabelled";

describe("incidentStatusOf", () => {
  it("maps an open incident to act and an unresolved send to watch", () => {
    expect(incidentStatusOf({ incidentActive: true })).toEqual({ state: "act", word: "Incident open" });
    expect(incidentStatusOf({ incidentActive: false })).toEqual({ state: "watch", word: "Unresolved send" });
  });

  it("maps unknown or unrecognised status to UNKNOWN, never OK", () => {
    for (const value of [undefined, null, "OK", "active", 1, 0, ""]) {
      const status = incidentStatusOf({ incidentActive: value as unknown as boolean });
      expect(status).toEqual({ state: "unk", word: "UNKNOWN" });
      expect(status.state).not.toBe("ok");
    }
    expect(incidentStatusOf(null)).toEqual({ state: "unk", word: "UNKNOWN" });
  });
});

describe("buildFailoverIncidentsModel", () => {
  it("counts each status and orders open, UNKNOWN, then unresolved", () => {
    const model = buildFailoverIncidentsModel(
      [
        incident(1, { incidentActive: false }),
        incident(2, { incidentActive: true }),
        incident(3, { incidentActive: "maybe" as unknown as boolean }),
      ],
      label,
    );
    expect(model.total).toBe(3);
    expect(model.openCount).toBe(1);
    expect(model.unresolvedCount).toBe(1);
    expect(model.unknownCount).toBe(1);
    expect(model.rows.map((r) => r.status.word)).toEqual(["Incident open", "UNKNOWN", "Unresolved send"]);
    expect(model.moreCount).toBe(0);
  });

  it("caps the rows at 10 and reports the remainder as +N more", () => {
    const many = Array.from({ length: 13 }, (_, i) => incident(i + 1));
    const model = buildFailoverIncidentsModel(many, label);
    expect(FAILOVER_INCIDENT_CAP).toBe(10);
    expect(model.total).toBe(13);
    expect(model.rows).toHaveLength(10);
    expect(model.moreCount).toBe(3);
  });

  it("keeps the newest incident first inside a status group", () => {
    const model = buildFailoverIncidentsModel([incident(1), incident(5), incident(3)], label);
    expect(model.rows.map((r) => r.key.split(":")[0])).toEqual(["INC-5", "INC-3", "INC-1"]);
  });

  it("uses the supplied unit label and an empty read yields zero rows", () => {
    const model = buildFailoverIncidentsModel([incident(1)], () => "CLS-ROMEO-01");
    expect(model.rows[0]?.label).toBe("CLS-ROMEO-01");
    const empty = buildFailoverIncidentsModel([], label);
    expect(empty.total).toBe(0);
    expect(empty.rows).toEqual([]);
  });
});

describe("buildFailoverIncidentsModel row detail (run reference, reason, unresolved sends)", () => {
  it("extracts incidentRef, reason, unresolvedSends and the blocking sentence", () => {
    const model = buildFailoverIncidentsModel(
      [incident(1, { reason: "  synthetic reason  ", unresolvedSends: 1, blocksEstate: true })],
      label,
    );
    const row = model.rows[0]!;
    expect(row.incidentRef).toBe("INC-1");
    expect(row.reason).toBe("synthetic reason");
    expect(row.unresolvedSends).toBe(1);
    expect(row.blocksEstateText).toBe("blocks every failover in the estate (1 unresolved send)");
  });

  it("pluralises the unresolved-send count", () => {
    const model = buildFailoverIncidentsModel([incident(1, { unresolvedSends: 2, blocksEstate: true })], label);
    expect(model.rows[0]?.blocksEstateText).toBe("blocks every failover in the estate (2 unresolved sends)");
  });

  it("reports the unresolved-send count as UNKNOWN when missing or non-finite", () => {
    const notANumber = Number.NaN;
    const negative = -1;
    const missing = incident(1, { blocksEstate: true, unresolvedSends: undefined as unknown as number });
    const text = incident(2, { blocksEstate: true, unresolvedSends: "3" as unknown as number });
    const model = buildFailoverIncidentsModel(
      [missing, incident(3, { blocksEstate: true, unresolvedSends: notANumber }), incident(4, { blocksEstate: true, unresolvedSends: negative }), text],
      label,
    );
    for (const row of model.rows) {
      expect(row.unresolvedSends).toBeNull();
      expect(row.blocksEstateText).toBe("blocks every failover in the estate (unresolved sends count UNKNOWN)");
    }
  });

  it("leaves blocksEstateText null when the incident does not block the estate", () => {
    const model = buildFailoverIncidentsModel([incident(1, { blocksEstate: false, unresolvedSends: 5 })], label);
    expect(model.rows[0]?.blocksEstateText).toBeNull();
    expect(model.rows[0]?.unresolvedSends).toBe(5);
  });

  it("sets incidentRef and reason to null when blank or non-string", () => {
    const model = buildFailoverIncidentsModel(
      [
        incident(1, { incidentRef: "   ", reason: "" }),
        incident(2, { incidentRef: 7 as unknown as string, reason: null as unknown as string }),
        incident(3, { incidentRef: undefined as unknown as string, reason: "  " }),
      ],
      label,
    );
    for (const row of model.rows) {
      expect(row.incidentRef).toBeNull();
      expect(row.reason).toBeNull();
    }
  });
});
