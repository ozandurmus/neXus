import { describe, expect, it } from "vitest";
import {
  askHref,
  backupsHref,
  briefHref,
  changesHref,
  complianceHref,
  dossierHref,
  estateEntryHrefFor,
  ESTATE_LENSES,
  failoverHref,
  isEstateLens,
  leavesEstateView,
  lifecycleHref,
  parseEstateLens,
  rewindHref,
  resolveEstateRoute,
  screenIdFromLocation,
  standardBackupsHref,
  standardChangesHref,
  standardComplianceHref,
  standardHaHref,
  standardJobsHref,
  standardLifecycleHref,
  standardPolicyHref,
  standardTraceHref,
  systemHref,
  wallHref,
} from "../src/estate/estateRouting";
import type { ScreenId } from "../src/shell/types";

// Synthetic names only (FW-TANGO-04 style); no real hostnames, addresses or identities.
const DEVICE = "FW-TANGO-04";
const CLUSTER = "CLS-ROMEO-01";

describe("estate lens helpers", () => {
  it("accepts exactly the eight lens ids", () => {
    expect(ESTATE_LENSES).toEqual([
      "worst",
      "failover",
      "backups",
      "compliance",
      "exposure",
      "install",
      "health",
      "age",
    ]);
    for (const lens of ESTATE_LENSES) expect(isEstateLens(lens)).toBe(true);
  });

  it("rejects unknown, empty and missing values", () => {
    expect(isEstateLens("WORST")).toBe(false);
    expect(isEstateLens("")).toBe(false);
    expect(isEstateLens(null)).toBe(false);
    expect(isEstateLens(undefined)).toBe(false);
  });

  it("parses known lenses and falls back to worst for anything else", () => {
    expect(parseEstateLens("backups")).toBe("backups");
    expect(parseEstateLens("nope")).toBe("worst");
    expect(parseEstateLens("")).toBe("worst");
    expect(parseEstateLens(null)).toBe("worst");
    expect(parseEstateLens(undefined)).toBe("worst");
  });
});

describe("resolveEstateRoute", () => {
  it("returns null for every screen when standard=1 is present", () => {
    expect(resolveEstateRoute("overview", "/", "?standard=1")).toBeNull();
    expect(resolveEstateRoute("inventory", "/", "?screen=inventory&standard=1")).toBeNull();
    expect(resolveEstateRoute("changes", "/", "?standard=1&device_id=x")).toBeNull();
    expect(resolveEstateRoute("inventory", `/devices/${DEVICE}`, "?standard=1")).toBeNull();
  });

  it("treats standard values other than 1 as not standard", () => {
    expect(resolveEstateRoute("overview", "/", "?standard=0")).toEqual({ kind: "brief" });
  });

  it("maps /devices/<id> to a dossier, decoding the id", () => {
    expect(resolveEstateRoute("inventory", `/devices/${DEVICE}`, "")).toEqual({
      kind: "dossier",
      deviceId: DEVICE,
    });
    expect(resolveEstateRoute("inventory", "/devices/CLS%20ROMEO%2001", "")).toEqual({
      kind: "dossier",
      deviceId: "CLS ROMEO 01",
    });
  });

  it("falls through when the /devices/ path has an empty id", () => {
    expect(resolveEstateRoute("overview", "/devices/   ", "")).toEqual({ kind: "brief" });
  });

  it("maps overview to the brief", () => {
    expect(resolveEstateRoute("overview", "/", "?screen=overview")).toEqual({ kind: "brief" });
  });

  it("maps inventory without parameters to the wall with the worst lens", () => {
    expect(resolveEstateRoute("inventory", "/", "?screen=inventory")).toEqual({
      kind: "wall",
      lens: "worst",
      clusterRef: null,
    });
  });

  it("falls back to the worst lens for an unknown or missing lens parameter", () => {
    expect(resolveEstateRoute("inventory", "/", "?screen=inventory&lens=bogus")).toEqual({
      kind: "wall",
      lens: "worst",
      clusterRef: null,
    });
    expect(resolveEstateRoute("inventory", "/", "?screen=inventory&lens=health")).toEqual({
      kind: "wall",
      lens: "health",
      clusterRef: null,
    });
  });

  it("carries a trimmed cluster_ref on the wall and drops a blank one", () => {
    expect(resolveEstateRoute("inventory", "/", `?screen=inventory&cluster_ref=%20${CLUSTER}%20`)).toEqual({
      kind: "wall",
      lens: "worst",
      clusterRef: CLUSTER,
    });
    expect(resolveEstateRoute("inventory", "/", "?screen=inventory&cluster_ref=%20%20")).toEqual({
      kind: "wall",
      lens: "worst",
      clusterRef: null,
    });
  });

  it("maps inventory device_id to a dossier and blank device_id to the wall", () => {
    expect(resolveEstateRoute("inventory", "/", `?screen=inventory&device_id=${DEVICE}`)).toEqual({
      kind: "dossier",
      deviceId: DEVICE,
    });
    expect(resolveEstateRoute("inventory", "/", "?screen=inventory&device_id=%20%20")).toEqual({
      kind: "wall",
      lens: "worst",
      clusterRef: null,
    });
  });

  it("maps view=lifecycle and view=rewind on inventory", () => {
    expect(resolveEstateRoute("inventory", "/", "?screen=inventory&view=lifecycle")).toEqual({ kind: "lifecycle" });
    expect(resolveEstateRoute("inventory", "/", "?screen=inventory&view=rewind&at=A&compare=B")).toEqual({
      kind: "rewind",
      at: "A",
      compare: "B",
    });
    expect(resolveEstateRoute("inventory", "/", "?screen=inventory&view=rewind")).toEqual({
      kind: "rewind",
      at: null,
      compare: null,
    });
  });

  it("maps operations HA (no tab, or tab=ha) to the failover board", () => {
    expect(resolveEstateRoute("operations", "/", `?screen=operations&cluster_ref=${CLUSTER}`)).toEqual({
      kind: "failover",
      clusterRef: CLUSTER,
    });
    expect(resolveEstateRoute("operations", "/", "?screen=operations&tab=ha")).toEqual({
      kind: "failover",
      clusterRef: null,
    });
  });

  it("returns null for other operations tabs and for any job parameter", () => {
    expect(resolveEstateRoute("operations", "/", "?screen=operations&tab=jobs")).toBeNull();
    expect(resolveEstateRoute("operations", "/", "?screen=operations&tab=ha&job=J1")).toBeNull();
  });

  it("maps changes with a trimmed device_id and vendor, and blanks to null", () => {
    expect(resolveEstateRoute("changes", "/", `?screen=changes&device_id=${DEVICE}&vendor=%20acme%20`)).toEqual({
      kind: "changes",
      deviceId: DEVICE,
      vendor: "acme",
    });
    expect(resolveEstateRoute("changes", "/", "?screen=changes&device_id=%20&vendor=")).toEqual({
      kind: "changes",
      deviceId: null,
      vendor: null,
    });
  });

  it("maps compliance by screen or by pathname", () => {
    expect(resolveEstateRoute("compliance", "/", `?screen=compliance&device=${DEVICE}&family=ssh`)).toEqual({
      kind: "compliance",
      deviceId: DEVICE,
      family: "ssh",
    });
    // The pathname is checked after the screen branches that come earlier, so a screen-less address still resolves.
    expect(resolveEstateRoute("policy" as ScreenId, "/compliance", "")).toEqual({
      kind: "compliance",
      deviceId: null,
      family: null,
    });
    expect(resolveEstateRoute("policy" as ScreenId, "/compliance/", "")).toEqual({
      kind: "compliance",
      deviceId: null,
      family: null,
    });
  });

  it("maps backups by screen or by pathname, with an empty query by default", () => {
    expect(resolveEstateRoute("backups", "/", "?screen=backups&q=%20nightly%20")).toEqual({
      kind: "backups",
      query: "nightly",
    });
    expect(resolveEstateRoute("policy" as ScreenId, "/backups", "")).toEqual({ kind: "backups", query: "" });
    expect(resolveEstateRoute("policy" as ScreenId, "/backups/", "")).toEqual({ kind: "backups", query: "" });
  });

  it("gives the overview screen precedence over a compliance or backups pathname", () => {
    expect(resolveEstateRoute("overview", "/backups", "")).toEqual({ kind: "brief" });
    expect(resolveEstateRoute("overview", "/compliance", "")).toEqual({ kind: "brief" });
  });

  it("maps trace to ask, with the mode falling back to path for unknown values", () => {
    expect(resolveEstateRoute("trace", "/", "?screen=trace")).toEqual({ kind: "ask", mode: "path" });
    expect(resolveEstateRoute("trace", "/", "?screen=trace&mode=lookup")).toEqual({ kind: "ask", mode: "lookup" });
    expect(resolveEstateRoute("trace", "/", "?screen=trace&mode=bogus")).toEqual({ kind: "ask", mode: "path" });
  });

  it("maps packet_analysis to cases, reports to reports", () => {
    expect(resolveEstateRoute("packet_analysis", "/", "?screen=packet_analysis")).toEqual({ kind: "cases" });
    expect(resolveEstateRoute("reports", "/", "?screen=reports")).toEqual({ kind: "reports" });
  });

  it("maps administration only when view=system", () => {
    expect(resolveEstateRoute("administration", "/", "?screen=administration&view=system")).toEqual({ kind: "system" });
    expect(resolveEstateRoute("administration", "/", "?screen=administration")).toBeNull();
    expect(resolveEstateRoute("administration", "/", "?screen=administration&view=other")).toBeNull();
  });

  it("returns null for screens outside the Estate view", () => {
    expect(resolveEstateRoute("policy" as ScreenId, "/", "?screen=policy")).toBeNull();
    expect(resolveEstateRoute("diagnostics" as ScreenId, "/", "?screen=diagnostics")).toBeNull();
  });
});

describe("rewind origin on dossier routes", () => {
  it("carries rewind_at and rewind_compare from a rewind visit", () => {
    expect(
      resolveEstateRoute("inventory", `/devices/${DEVICE}`, "?rewind_at=2026-11-01T00:00:00Z&rewind_compare=2026-11-02T00:00:00Z"),
    ).toEqual({
      kind: "dossier",
      deviceId: DEVICE,
      fromRewind: { at: "2026-11-01T00:00:00Z", compare: "2026-11-02T00:00:00Z" },
    });
  });

  it("sets compare to null when rewind_compare is blank, and omits fromRewind when rewind_at is blank", () => {
    expect(
      resolveEstateRoute("inventory", "/", `?screen=inventory&device_id=${DEVICE}&rewind_at=2026-11-01T00:00:00Z&rewind_compare=%20`),
    ).toEqual({
      kind: "dossier",
      deviceId: DEVICE,
      fromRewind: { at: "2026-11-01T00:00:00Z", compare: null },
    });
    const noRewind = resolveEstateRoute("inventory", "/", `?screen=inventory&device_id=${DEVICE}&rewind_at=%20`);
    expect(noRewind).toEqual({ kind: "dossier", deviceId: DEVICE });
    expect(noRewind && "fromRewind" in noRewind).toBe(false);
  });
});

describe("screenIdFromLocation", () => {
  it("maps device, compliance and backups paths to their screens", () => {
    expect(screenIdFromLocation(`/devices/${DEVICE}`, "")).toBe("inventory");
    expect(screenIdFromLocation("/compliance", "")).toBe("compliance");
    expect(screenIdFromLocation("/compliance/", "")).toBe("compliance");
    expect(screenIdFromLocation("/backups", "")).toBe("backups");
    expect(screenIdFromLocation("/backups/", "")).toBe("backups");
  });

  it("reads screen from the query, mapping configuration to inventory", () => {
    expect(screenIdFromLocation("/", "?screen=changes")).toBe("changes");
    expect(screenIdFromLocation("/", "?screen=configuration")).toBe("inventory");
  });

  it("prefers preview over screen when preview names a valid screen", () => {
    expect(screenIdFromLocation("/", "?preview=reports&screen=changes")).toBe("reports");
  });

  it("falls back to screen when preview is not a valid screen", () => {
    expect(screenIdFromLocation("/", "?preview=not-a-screen&screen=changes")).toBe("changes");
  });

  it("falls back to overview for a missing or unknown screen", () => {
    expect(screenIdFromLocation("/", "")).toBe("overview");
    expect(screenIdFromLocation("/", "?screen=not-a-screen")).toBe("overview");
  });

  it("applies legacy route renames before reading the screen", () => {
    expect(screenIdFromLocation("/", "?screen=path_analysis")).toBe("trace");
  });
});

describe("estateEntryHrefFor", () => {
  it("returns null when the current route already renders in the Estate view", () => {
    expect(estateEntryHrefFor("overview", "/", "?screen=overview")).toBeNull();
    expect(estateEntryHrefFor("inventory", "/", "?screen=inventory")).toBeNull();
  });

  it("strips standard=1 and returns the Estate address when that address resolves", () => {
    expect(estateEntryHrefFor("inventory", "/", "?screen=inventory&lens=health&standard=1")).toBe(
      "?screen=inventory&lens=health",
    );
    expect(estateEntryHrefFor("inventory", "/", "?screen=inventory&standard=1")).toBe("?screen=inventory");
  });

  it("returns the bare pathname for a dossier opened with standard=1", () => {
    expect(estateEntryHrefFor("inventory", `/devices/${DEVICE}`, "?standard=1")).toBe(`/devices/${DEVICE}`);
  });

  it("sends a standard policy-install tab to the install lens", () => {
    expect(estateEntryHrefFor("operations", "/", "?screen=operations&tab=policy-install")).toBe(
      "?screen=inventory&lens=install",
    );
  });

  it("sends standard administration screens to the System page", () => {
    expect(estateEntryHrefFor("administration", "/", "?screen=administration")).toBe(systemHref());
  });

  it("falls back to the brief for any other standard screen", () => {
    expect(estateEntryHrefFor("operations", "/", "?screen=operations&tab=jobs")).toBe("?screen=overview");
    expect(estateEntryHrefFor("policy" as ScreenId, "/", "?screen=policy&standard=1")).toBe("?screen=overview");
  });
});

describe("leavesEstateView", () => {
  it("is false for Estate addresses, with or without a leading ?", () => {
    expect(leavesEstateView("?screen=overview")).toBe(false);
    expect(leavesEstateView(wallHref("health"))).toBe(false);
    expect(leavesEstateView(`screen=inventory&device_id=${DEVICE}`)).toBe(false);
  });

  it("is true for standard-view addresses", () => {
    expect(leavesEstateView(standardPolicyHref(DEVICE))).toBe(true);
    expect(leavesEstateView(standardJobsHref())).toBe(true);
    expect(leavesEstateView("?screen=diagnostics")).toBe(true);
  });
});

describe("Estate link builders", () => {
  it("builds the brief and lifecycle links", () => {
    expect(briefHref()).toBe("?screen=overview");
    expect(lifecycleHref()).toBe("?screen=inventory&view=lifecycle");
    expect(standardLifecycleHref()).toBe("?screen=inventory&view=lifecycle&standard=1");
  });

  it("builds the wall link, omitting the default worst lens", () => {
    expect(wallHref()).toBe("?screen=inventory");
    expect(wallHref("worst")).toBe("?screen=inventory");
    expect(wallHref("backups")).toBe("?screen=inventory&lens=backups");
  });

  it("builds the dossier link and adds rewind origin only when it has a moment", () => {
    expect(dossierHref(DEVICE)).toBe(`?screen=inventory&device_id=${DEVICE}`);
    expect(dossierHref(DEVICE, null)).toBe(`?screen=inventory&device_id=${DEVICE}`);
    expect(dossierHref(DEVICE, { at: "A", compare: null })).toBe(`?screen=inventory&device_id=${DEVICE}&rewind_at=A`);
    expect(dossierHref(DEVICE, { at: "A", compare: "B" })).toBe(
      `?screen=inventory&device_id=${DEVICE}&rewind_at=A&rewind_compare=B`,
    );
  });

  it("encodes device ids in the dossier link", () => {
    expect(dossierHref("CLS ROMEO/01")).toBe("?screen=inventory&device_id=CLS%20ROMEO%2F01");
  });

  it("builds failover links with and without a cluster", () => {
    expect(failoverHref()).toBe("?screen=operations");
    expect(failoverHref(null)).toBe("?screen=operations");
    expect(failoverHref(CLUSTER)).toBe(`?screen=operations&cluster_ref=${CLUSTER}`);
    expect(standardHaHref()).toBe("?screen=operations&standard=1");
    expect(standardHaHref(CLUSTER)).toBe(`?screen=operations&cluster_ref=${CLUSTER}&standard=1`);
  });

  it("builds changes links with and without a device, and the standard variants", () => {
    expect(changesHref()).toBe("?screen=changes");
    expect(changesHref("")).toBe("?screen=changes");
    expect(changesHref(DEVICE)).toBe(`?screen=changes&device_id=${DEVICE}`);
    expect(standardChangesHref()).toBe("?screen=changes&standard=1");
    expect(standardChangesHref("feed")).toBe("?screen=changes&view=feed&standard=1");
    expect(standardChangesHref("policy")).toBe("?screen=changes&view=policy&standard=1");
  });

  it("builds the standard failed-jobs link for the last 24 hours", () => {
    expect(standardJobsHref()).toBe("?screen=operations&tab=jobs&state=FAILED&since_hours=24&standard=1");
  });

  it("builds compliance links, encoding the device and omitting unset fields", () => {
    expect(complianceHref()).toBe("?screen=compliance");
    expect(complianceHref(null)).toBe("?screen=compliance");
    expect(complianceHref(DEVICE)).toBe(`?screen=compliance&device=${DEVICE}`);
    expect(standardComplianceHref()).toBe("?screen=compliance&standard=1");
    expect(standardComplianceHref({ deviceId: DEVICE, controlId: "C-01" })).toBe(
      `?screen=compliance&device=${DEVICE}&control=C-01&standard=1`,
    );
    expect(standardComplianceHref({ deviceId: null, controlId: "C-01" })).toBe(
      "?screen=compliance&control=C-01&standard=1",
    );
  });

  it("builds backups links with a trimmed query and no empty q", () => {
    expect(backupsHref()).toBe("?screen=backups");
    expect(backupsHref("   ")).toBe("?screen=backups");
    expect(backupsHref("  nightly run ")).toBe("?screen=backups&q=nightly%20run");
    expect(standardBackupsHref()).toBe("?screen=backups&standard=1");
    expect(standardBackupsHref("  nightly ")).toBe("?screen=backups&q=nightly&standard=1");
  });

  it("builds trace links, with path as the default mode", () => {
    expect(askHref()).toBe("?screen=trace");
    expect(askHref("path")).toBe("?screen=trace");
    expect(askHref("lookup")).toBe("?screen=trace&mode=lookup");
    expect(standardTraceHref()).toBe("?screen=trace&mode=path&standard=1");
    expect(standardTraceHref("lookup")).toBe("?screen=trace&mode=lookup&standard=1");
  });

  it("builds rewind links, omitting absent moments", () => {
    expect(rewindHref()).toBe("?screen=inventory&view=rewind");
    expect(rewindHref(null, null)).toBe("?screen=inventory&view=rewind");
    expect(rewindHref("A")).toBe("?screen=inventory&view=rewind&at=A");
    expect(rewindHref("A", "B")).toBe("?screen=inventory&view=rewind&at=A&compare=B");
  });

  it("builds the standard policy and System links", () => {
    expect(standardPolicyHref(DEVICE)).toBe(`?screen=policy&device_id=${DEVICE}&standard=1`);
    expect(systemHref()).toBe("?screen=administration&view=system");
  });
});

describe("link builders round trip through resolveEstateRoute", () => {
  it("wall lens links resolve back to the same lens", () => {
    for (const lens of ESTATE_LENSES) {
      const route = resolveEstateRoute("inventory", "/", wallHref(lens));
      expect(route).toEqual({ kind: "wall", lens, clusterRef: null });
    }
  });

  it("dossier links, with rewind origin, resolve back to the device and moments", () => {
    const href = dossierHref(DEVICE, { at: "A", compare: "B" });
    expect(resolveEstateRoute("inventory", "/", href)).toEqual({
      kind: "dossier",
      deviceId: DEVICE,
      fromRewind: { at: "A", compare: "B" },
    });
  });

  it("rewind links resolve back to their moments", () => {
    expect(resolveEstateRoute("inventory", "/", rewindHref("A", "B"))).toEqual({
      kind: "rewind",
      at: "A",
      compare: "B",
    });
  });

  it("ask links resolve back to their mode", () => {
    for (const mode of ["path", "lookup", "usage", "cutover", "zones"] as const) {
      expect(resolveEstateRoute("trace", "/", askHref(mode))).toEqual({ kind: "ask", mode });
    }
  });

  it("failover and backups links resolve back to their parameters", () => {
    expect(resolveEstateRoute("operations", "/", failoverHref(CLUSTER))).toEqual({
      kind: "failover",
      clusterRef: CLUSTER,
    });
    expect(resolveEstateRoute("backups", "/", backupsHref("nightly run"))).toEqual({
      kind: "backups",
      query: "nightly run",
    });
  });

  it("every Estate link resolves to a non-null route, and every standard link to null", () => {
    const estateLinks = [briefHref(), wallHref(), lifecycleHref(), changesHref(DEVICE), complianceHref(DEVICE), systemHref()];
    for (const href of estateLinks) {
      expect(leavesEstateView(href)).toBe(false);
    }
    const standardLinks = [
      standardHaHref(CLUSTER),
      standardJobsHref(),
      standardChangesHref("feed"),
      standardComplianceHref({ deviceId: DEVICE }),
      standardBackupsHref("q"),
      standardLifecycleHref(),
      standardTraceHref("lookup"),
      standardPolicyHref(DEVICE),
    ];
    for (const href of standardLinks) {
      expect(leavesEstateView(href)).toBe(true);
    }
  });
});
