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

  it("Policy > Offline analysis is under Tools; Policy > Installation is the Rules & Containers tab", () => {
    expect(to("?screen=policy&tab=offline&policy_id=p1")).toEqual({ screen: "offline_analysis", policy_id: "p1" });
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
    expect(Object.keys(LEGACY_ADMIN_TABS)).toHaveLength(13);
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
