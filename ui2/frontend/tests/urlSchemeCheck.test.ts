import { describe, expect, it } from "vitest";
import { isInAppNavigationHref } from "../src/shell/urlParams";

describe("isInAppNavigationHref scheme check", () => {
  it.each([
    "javascript:alert(1)",
    "JavaScript:alert(1)",
    "  jAvAsCrIpT:alert(1)",
    "data:text/html,<script>alert(1)</script>",
    "DATA:text/html;base64,AAAA",
    "vbscript:msgbox(1)",
    "BLOB:https://x/y",
    "MAILTO:a@b.c",
    "#fragment",
    "",
  ])("never treats %j as an in-app navigation", href => {
    expect(isInAppNavigationHref(href)).toBe(false);
  });

  it("still routes a same-origin query link", () => {
    expect(isInAppNavigationHref("?screen=inventory")).toBe(true);
  });
});
