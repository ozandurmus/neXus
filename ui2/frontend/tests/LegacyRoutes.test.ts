import { describe, expect, it } from "vitest";
import { LEGACY_ADMIN_TABS, lookupInPolicyHref, rewriteLegacyRoute } from "../src/shell/legacyRoutes";

/** Old addresses keep working (UI review 2026-11-05, C2/C3/C4/B6): each lands on its new home with its own parameters kept. */
const to = (search: string) => { const out = rewriteLegacyRoute(search); return out === null ? null : Object.fromEntries(new URLSearchParams(out)); };

describe("legacy route rewrites", () => {
  it("Path analysis is Trace in path mode", () => {
    expect(to("?screen=path_analysis")).toEqual({ screen: "trace", mode: "path" });
    expect(to("?preview=path_analysis")).toEqual({ preview: "trace", mode: "path" });
  });

  it("Policy > Lookup is Trace in lookup mode, its policy kept", () => {
    expect(to("?screen=policy&tab=lookup&policy_id=p1")).toEqual({ screen: "trace", mode: "lookup", policy_id: "p1" });
  });

  // PO 2026-11-13 (RULE_OBJECT_USAGE_CONTRACT.md, ROU-7): every old Offline analysis address lands on Rule object usage.
  it("Offline analysis is Rule object usage in Whole policy mode, its parameters kept", () => {
    expect(to("?screen=offline_analysis")).toEqual({ screen: "rule_usage", mode: "policy" });
    expect(to("?screen=offline_analysis&policy_id=p1")).toEqual({ screen: "rule_usage", mode: "policy", policy_id: "p1" });
    expect(to("?screen=policy&tab=offline&policy_id=p1")).toEqual({ screen: "rule_usage", mode: "policy", policy_id: "p1" });
    expect(to("?screen=policy&tab=offline-analysis&policy_id=p1&display=wall")).toEqual({ screen: "rule_usage", mode: "policy", policy_id: "p1", display: "wall" });
    expect(to("?preview=offline_analysis")).toEqual({ preview: "rule_usage", mode: "policy" });
    // a mode the address already names is kept, and the new address is left alone
    expect(to("?screen=offline_analysis&mode=rule")).toEqual({ screen: "rule_usage", mode: "rule" });
    for (const search of ["?screen=rule_usage", "?screen=rule_usage&mode=policy&policy_id=p1", "?screen=rule_usage&mode=rule"]) expect(rewriteLegacyRoute(search)).toBeNull();
  });

  it("Policy > Installation is the Rules & Containers tab", () => {
    expect(to("?screen=policy&tab=installation")).toEqual({ screen: "policy", tab: "rules" });
  });

  it("Operations > Change feed is Changes (timeline), its device filter kept; Operations > Diagnostics is under Tools", () => {
    expect(to("?screen=operations&tab=changes&device_id=d1")).toEqual({ screen: "changes", view: "feed", device_id: "d1" });
    expect(to("?screen=operations&tab=diagnostics")).toEqual({ screen: "diagnostics" });
  });

  it("Administration > Job Logs is Operations > Jobs, with or without a tab, its job kept", () => {
    expect(to("?screen=administration&tab=job-logs")).toEqual({ screen: "operations", tab: "jobs" });
    expect(to("?screen=administration&job=j1")).toEqual({ screen: "operations", tab: "jobs", job: "j1" });
  });

  it("every former Administration tab lands on its group with that section named", () => {
    for (const [slug, home] of Object.entries(LEGACY_ADMIN_TABS)) {
      expect(to(`?screen=administration&tab=${slug}`)).toEqual({ screen: "administration", tab: home.group, section: home.section });
    }
    expect(Object.keys(LEGACY_ADMIN_TABS)).toHaveLength(12);
  });

  // PO 2026-11-13 (F1, F2): two former Platform sections live under Operations.
  it("Administration > Platform > System is Operations > Platform status", () => {
    expect(to("?screen=administration&tab=platform&section=system")).toEqual({ screen: "operations", tab: "platform-status" });
    expect(to("?screen=administration&tab=system")).toEqual({ screen: "operations", tab: "platform-status" });
    // the short-lived address of the first form of this change
    expect(to("?screen=operations&tab=health&focus=platform")).toEqual({ screen: "operations", tab: "platform-status" });
    expect(LEGACY_ADMIN_TABS.system).toBeUndefined();
  });

  it("Administration > Platform > Scheduled jobs is Operations > Scheduled jobs", () => {
    expect(to("?screen=administration&tab=platform&section=scheduled-jobs")).toEqual({ screen: "operations", tab: "schedules" });
  });

  it("leaves Notifications and logging at its address: the page slug stays `platform`", () => {
    for (const search of ["?screen=administration&tab=platform", "?screen=administration&tab=platform&section=notifications", "?screen=administration&tab=platform&section=system-logs",
      "?screen=operations&tab=schedules", "?screen=operations&tab=health", "?screen=operations&tab=platform-status"]) {
      expect(rewriteLegacyRoute(search)).toBeNull();
    }
    expect(to("?screen=administration&tab=system-logs")).toEqual({ screen: "administration", tab: "platform", section: "system-logs" });
  });

  it("leaves a current address alone, and the other screens' own tab slugs", () => {
    for (const search of ["", "?screen=overview", "?screen=trace&mode=lookup", "?screen=changes&view=policy", "?screen=administration&tab=registry&section=ssh-trust",
      "?screen=operations&tab=jobs", "?screen=operations&tab=health", "?screen=policy&tab=objects", "?screen=inventory&tab=configuration"]) {
      expect(rewriteLegacyRoute(search), search).toBeNull();
    }
  });

  it("builds the Search in this policy link", () => {
    expect(lookupInPolicyHref("a b")).toBe("?screen=trace&mode=lookup&policy_id=a%20b");
  });
});
