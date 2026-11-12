import { describe, expect, it } from "vitest";
import {
  NAV_ENTRIES, NAV_LEAVES, activeLeafId, formatGroupChoices, groupOfLeaf, groupOpen, leafLocked, leavesOf, parseGroupChoices, visibleEntries,
  type NavGroup,
} from "../src/shell/navModel";
import { LEGACY_ADMIN_TABS, rewriteLegacyRoute } from "../src/shell/legacyRoutes";
import { SCREEN_IDS, isScreenId, type ScreenId } from "../src/shell/types";
import { SCREEN_HEADINGS, TAB_COVERAGE } from "../src/shell/tabCoverage";

const leafIds = NAV_LEAVES.map((l) => l.id);
const group = (id: string) => NAV_ENTRIES.find((e): e is NavGroup => e.kind === "group" && e.id === id)!;
const labelsOf = (entries: ReturnType<typeof visibleEntries>) => entries.map((e) => [e.label, e.kind === "group" ? e.children.map((c) => c.label) : null]);

describe("the navigation model (NAVIGATION_REDESIGN_2026_11_12.md section 2)", () => {
  it("is nine top-level entries in the order of the product principle, one level deep", () => {
    expect(NAV_ENTRIES.map((e) => [e.label, e.kind === "group" ? e.children.map((c) => c.label) : null])).toEqual([
      ["Overview", null],
      ["Reports", null],
      ["Devices", null],
      ["Compliance and security", ["Compliance", "Lifecycle and security"]],
      ["Policy and changes", ["Policy", "Trace", "Changes"]],
      ["Backups", null],
      ["Operations", ["HA and readiness", "Jobs", "System health"]],
      ["Tools", ["Packet analysis", "Data sanitizer", "Diagnostics", "Offline analysis"]],
      ["Administration", ["Device registry", "Access and identity", "Platform", "Audit log", "Delivery plan"]],
    ]);
    expect(NAV_LEAVES).toHaveLength(21);
    // Only Administration starts closed.
    expect(NAV_ENTRIES.filter((e) => e.kind === "group" && e.startsClosed).map((e) => e.id)).toEqual(["administration"]);
  });

  it("gives every destination one entry: ids and addresses are unique, and every screen has a home", () => {
    expect(new Set(leafIds).size).toBe(leafIds.length);
    expect(new Set(NAV_LEAVES.map((l) => l.href)).size).toBe(NAV_LEAVES.length);
    // configuration is a tab of Devices (PO 2026-09-25), not a destination of its own.
    expect([...new Set(NAV_LEAVES.map((l) => l.screen))].sort()).toEqual(SCREEN_IDS.filter((s) => s !== "configuration").sort());
  });

  it("names an entry as its screen heading, or as the tab it opens, in sentence case with no ampersand", () => {
    for (const leaf of NAV_LEAVES) {
      expect(leaf.label).not.toContain("&");
      expect(leaf.label.slice(1)).not.toMatch(/ [A-Z][a-z]/);
    }
    const heading = (id: string) => SCREEN_HEADINGS[NAV_LEAVES.find((l) => l.id === id)!.screen];
    for (const id of ["overview", "reports", "devices", "compliance", "policy", "trace", "changes", "backups", "packet-analysis", "sanitizer", "diagnostics", "offline-analysis"]) {
      expect(NAV_LEAVES.find((l) => l.id === id)!.label).toBe(heading(id));
    }
    expect(group("operations").children.map((c) => c.label)).toEqual(TAB_COVERAGE.find((r) => r.tabList === "Operations sections")!.labels);
    expect(group("operations").label).toBe(SCREEN_HEADINGS.operations);
  });

  it("keeps the plane gates as they were and gives Administration's pages none", () => {
    expect(Object.fromEntries(NAV_LEAVES.filter((l) => l.requiredPlane).map((l) => [l.id, l.requiredPlane]))).toEqual({
      devices: "Devices", compliance: "Compliance", lifecycle: "Devices", policy: "Policy",
      "packet-analysis": "PacketAnalysis", sanitizer: "Sanitizer", "offline-analysis": "Policy",
    });
    expect(group("administration").children.map((c) => [c.href, c.lock ?? null])).toEqual([
      ["?screen=administration&tab=registry", null],
      ["?screen=administration&tab=access", "adminRead"],
      ["?screen=administration&tab=platform", null],
      ["?screen=administration&tab=audit", "adminRead"],
      ["?screen=administration&tab=product", "adminPlane"],
    ]);
  });
});

describe("the lit entry", () => {
  it.each(SCREEN_IDS)("screen %s alone lights exactly one entry of the model", (screen) => {
    expect(leafIds.filter((id) => id === activeLeafId(screen, `?screen=${screen}`))).toHaveLength(1);
    expect(leafIds).toContain(activeLeafId(screen, ""));
  });

  it("every entry's own address lights that entry and no other", () => {
    for (const leaf of NAV_LEAVES) {
      const screen = new URLSearchParams(leaf.href).get("screen");
      expect(isScreenId(screen)).toBe(true);
      expect(activeLeafId(screen as ScreenId, leaf.href)).toBe(leaf.id);
    }
  });

  it.each<[ScreenId, string, string]>([
    ["overview", "", "overview"],
    ["inventory", "?screen=inventory&device_id=d1&tab=configuration", "devices"],
    ["inventory", "?screen=inventory&cluster_ref=c1", "devices"],
    // `/devices/<id>` : the shell passes the screen, the query is whatever it is.
    ["inventory", "", "devices"],
    ["configuration", "?screen=configuration", "devices"],
    ["inventory", "?screen=inventory&view=lifecycle", "lifecycle"],
    ["compliance", "", "compliance"],
    ["backups", "", "backups"],
    ["policy", "?screen=policy&tab=hygiene", "policy"],
    ["trace", "?screen=trace&mode=lookup", "trace"],
    ["changes", "?screen=changes&view=policy", "changes"],
    ["operations", "?screen=operations", "operations-ha"],
    ["operations", "?screen=operations&tab=ha", "operations-ha"],
    ["operations", "?screen=operations&tab=jobs", "operations-jobs"],
    ["operations", "?screen=operations&tab=queue", "operations-jobs"],
    ["operations", "?screen=operations&tab=history", "operations-jobs"],
    ["operations", "?screen=operations&job=j1", "operations-jobs"],
    ["operations", "?screen=operations&tab=jobs&job_type=backup", "operations-jobs"],
    ["operations", "?screen=operations&tab=health", "operations-health"],
    ["administration", "?screen=administration", "admin-registry"],
    ["administration", "?screen=administration&tab=registry&section=ssh-trust", "admin-registry"],
    ["administration", "?screen=administration&tab=access&section=ldap-settings", "admin-access"],
    ["administration", "?screen=administration&tab=platform&section=scheduled-jobs", "admin-platform"],
    ["administration", "?screen=administration&tab=audit", "admin-audit"],
    ["administration", "?screen=administration&tab=product", "admin-product"],
    ["administration", "?screen=administration&tab=nonsense", "admin-registry"],
  ])("%s %s lights %s", (screen, search, id) => {
    expect(activeLeafId(screen, search)).toBe(id);
  });

  it("every old address still resolves, and lights the entry of its new home", () => {
    const cases: [string, string][] = [
      ["?screen=path_analysis", "trace"],
      ["?screen=policy&tab=lookup", "trace"],
      ["?screen=policy&tab=offline", "offline-analysis"],
      ["?screen=policy&tab=offline-analysis", "offline-analysis"],
      ["?screen=policy&tab=installation", "policy"],
      ["?screen=operations&tab=changes", "changes"],
      ["?screen=operations&tab=diagnostics", "diagnostics"],
      ["?screen=administration&tab=job-logs", "operations-jobs"],
      ["?screen=administration&job=j1", "operations-jobs"],
      ...Object.entries(LEGACY_ADMIN_TABS).map(([slug, home]): [string, string] => [`?screen=administration&tab=${slug}`, `admin-${home.group}`]),
    ];
    expect(cases).toHaveLength(9 + 13);
    for (const [search, id] of cases) {
      // What the shell does: rewrite, then route on the rewritten screen.
      const rewritten = rewriteLegacyRoute(search);
      expect(rewritten).not.toBeNull();
      const screen = new URLSearchParams(rewritten!).get("screen");
      expect(isScreenId(screen)).toBe(true);
      expect(activeLeafId(screen as ScreenId, `?${rewritten}`)).toBe(id);
      expect(leafIds).toContain(id);
    }
    // The old Administration slug lights its page even before the shell has rewritten the address.
    expect(activeLeafId("administration", "?screen=administration&tab=ldap-settings")).toBe("admin-access");
  });

  it("knows the group of a child, and none for a top-level link", () => {
    expect(groupOfLeaf("operations-jobs")?.id).toBe("operations");
    expect(groupOfLeaf("lifecycle")?.id).toBe("compliance-security");
    expect(groupOfLeaf("devices")).toBeNull();
  });
});

describe("what a session is shown (plane checks only)", () => {
  it("lists everything for a session with no plane list", () => {
    expect(visibleEntries(undefined, "overview")).toEqual(NAV_ENTRIES);
    expect(visibleEntries([], "overview")).toEqual(NAV_ENTRIES);
  });

  it("hides an entry whose plane the session lacks, and a group once none of its children is left", () => {
    expect(labelsOf(visibleEntries(["Policy", "PacketAnalysis"], "operations-ha"))).toEqual([
      ["Overview", null],
      ["Reports", null],
      ["Policy and changes", ["Policy", "Trace", "Changes"]],
      ["Backups", null],
      ["Operations", ["HA and readiness", "Jobs", "System health"]],
      ["Tools", ["Packet analysis", "Diagnostics", "Offline analysis"]],
      ["Administration", ["Device registry", "Access and identity", "Platform", "Audit log", "Delivery plan"]],
    ]);
  });

  it("a session with Devices, Compliance and Policy sees Tools with Diagnostics and Offline analysis only", () => {
    const tools = visibleEntries(["Devices", "Compliance", "Policy"], "overview").find((e) => e.id === "tools")!;
    expect(leavesOf(tools).map((l) => l.label)).toEqual(["Diagnostics", "Offline analysis"]);
  });

  it("always keeps the entry of the open page, whatever the plane list says, and nothing else with it", () => {
    const ids = (activeId: string) => visibleEntries(["Policy"], activeId).flatMap(leavesOf).map((l) => l.id);
    expect(ids("sanitizer")).toContain("sanitizer");
    expect(ids("sanitizer")).not.toContain("packet-analysis");
    // Devices and Lifecycle and security share one screen and one plane: only the lit one of the two is kept.
    expect(ids(activeLeafId("inventory", "?screen=inventory"))).toContain("devices");
    expect(ids(activeLeafId("inventory", "?screen=inventory"))).not.toContain("lifecycle");
    expect(ids(activeLeafId("inventory", "?screen=inventory&view=lifecycle"))).toContain("lifecycle");
    expect(ids(activeLeafId("inventory", "?screen=inventory&view=lifecycle"))).not.toContain("devices");
    // Its group is listed for that one child alone.
    const group = visibleEntries(["Policy"], "lifecycle").find((e) => e.id === "compliance-security")!;
    expect(leavesOf(group).map((l) => l.label)).toEqual(["Lifecycle and security"]);
    // Being there grants nothing once the reader has left.
    expect(ids("overview")).not.toContain("devices");
    expect(ids("overview")).not.toContain("lifecycle");
    // A session that holds the plane sees both, wherever it is.
    expect(visibleEntries(["Devices"], "overview").flatMap(leavesOf).map((l) => l.id)).toEqual(expect.arrayContaining(["devices", "lifecycle"]));
  });

  it("lists the five Administration pages for every session and locks, never hides, what it may not use", () => {
    const pages = group("administration").children;
    const noGrant = { permissions: ["Devices", "Compliance", "Policy"], canReadAdministration: false };
    expect(pages.filter((p) => leafLocked(p, noGrant)).map((p) => p.label)).toEqual(["Access and identity", "Audit log", "Delivery plan"]);
    // The administration-read grant opens Access and Audit; the Delivery plan follows the Admin plane.
    expect(pages.filter((p) => leafLocked(p, { permissions: ["Devices"], canReadAdministration: true })).map((p) => p.label)).toEqual(["Delivery plan"]);
    expect(pages.filter((p) => leafLocked(p, { permissions: ["Admin"], canReadAdministration: true }))).toEqual([]);
    // No plane list is unrestricted for the plane check; no session at all (previews) locks nothing.
    expect(pages.filter((p) => leafLocked(p, { permissions: [], canReadAdministration: true }))).toEqual([]);
    expect(pages.filter((p) => leafLocked(p, null))).toEqual([]);
    expect(NAV_LEAVES.filter((l) => !l.lock).some((l) => leafLocked(l, noGrant))).toBe(false);
  });
});

describe("open and closed groups", () => {
  it("opens every group but Administration on a first visit, follows the viewer's choice, and never closes the active group", () => {
    expect(groupOpen(group("tools"), {}, "overview")).toBe(true);
    expect(groupOpen(group("administration"), {}, "overview")).toBe(false);
    expect(groupOpen(group("tools"), { tools: false }, "overview")).toBe(false);
    expect(groupOpen(group("administration"), { administration: true }, "overview")).toBe(true);
    expect(groupOpen(group("tools"), { tools: false }, "diagnostics")).toBe(true);
    expect(groupOpen(group("administration"), {}, "admin-platform")).toBe(true);
  });

  it("stores the choices as text and ignores what it does not know", () => {
    expect(parseGroupChoices(formatGroupChoices({ tools: false, administration: true }))).toEqual({ tools: false, administration: true });
    expect(parseGroupChoices(null)).toEqual({});
    expect(parseGroupChoices("tools:0,nonsense:1,operations:x,,:1")).toEqual({ tools: false });
  });
});
