import { afterEach, describe, expect, it } from "vitest";

import { parseTraceTuple, traceTupleHref } from "../src/shell/traceTupleLink";
import { canonicalizeAppUrl, handleInAppLinkClick, isInAppNavigationHref, navigateTo, writeUrlParams } from "../src/shell/urlParams";

const quad = (...octets: string[]) => octets.join(".");

afterEach(() => {
  window.history.replaceState(null, "", "/");
});

describe("traceTupleHref and parseTraceTuple", () => {
  it("round-trips valid TCP and UDP IPv4 tuples in the URL fragment, never in the query string", () => {
    const tcpHref = traceTupleHref({ src: "192.0.2.10", dst: "198.51.100.20", protocol: "tcp", port: 443 });
    expect(tcpHref).toBe("?screen=trace&mode=path#tuple=192.0.2.10,198.51.100.20,tcp,443");
    const tcpUrl = new URL(tcpHref, "http://localhost/");
    expect(tcpUrl.search).toBe("?screen=trace&mode=path");
    expect(tcpUrl.searchParams.get("tuple")).toBeNull();
    expect(parseTraceTuple(tcpUrl.hash)).toEqual({
      src: "192.0.2.10",
      dst: "198.51.100.20",
      protocol: "tcp",
      port: "443",
    });

    const udpHref = traceTupleHref({ src: "198.51.100.1", dst: "203.0.113.255", protocol: "udp", port: "65535" });
    expect(parseTraceTuple(new URL(udpHref, "http://localhost/").hash)).toEqual({
      src: "198.51.100.1",
      dst: "203.0.113.255",
      protocol: "udp",
      port: "65535",
    });

    expect(parseTraceTuple("#tuple=192.0.2.0,198.51.100.0,tcp,1")).toEqual({
      src: "192.0.2.0",
      dst: "198.51.100.0",
      protocol: "tcp",
      port: "1",
    });
  });

  it("returns null for a missing or unrelated fragment", () => {
    expect(parseTraceTuple("")).toBeNull();
    expect(parseTraceTuple("#")).toBeNull();
    expect(parseTraceTuple("#tuple=")).toBeNull();
    expect(parseTraceTuple("#other=192.0.2.10,198.51.100.20,tcp,443")).toBeNull();
    expect(parseTraceTuple("?tuple=192.0.2.10,198.51.100.20,tcp,443")).toBeNull();
    expect(parseTraceTuple(null)).toBeNull();
    expect(parseTraceTuple(undefined)).toBeNull();
  });

  it.each([
    ["octet out of range (999)", `#tuple=${quad("999", "1", "1", "1")},198.51.100.20,tcp,443`],
    ["octet out of range (256)", `#tuple=192.0.2.10,${quad("256", "51", "100", "20")},tcp,443`],
    ["short address (three octets)", "#tuple=1.2.3,198.51.100.20,tcp,443"],
    ["five octets", "#tuple=192.0.2.10.1,198.51.100.20,tcp,443"],
    ["leading-zero octet on source", `#tuple=${quad("010", "0", "0", "1")},198.51.100.20,tcp,443`],
    ["leading-zero octet on destination", `#tuple=192.0.2.10,${quad("198", "051", "100", "20")},tcp,443`],
    ["double zero octet", `#tuple=${quad("00", "0", "0", "1")},198.51.100.20,tcp,443`],
    ["CIDR prefix (/24)", `#tuple=${quad("1", "2", "3", "4")}/24,198.51.100.20,tcp,443`],
    ["IPv6 address", "#tuple=2001:db8::1,198.51.100.20,tcp,443"],
    ["unsupported protocol (icmp)", "#tuple=192.0.2.10,198.51.100.20,icmp,443"],
    ["uppercase protocol (TCP)", "#tuple=192.0.2.10,198.51.100.20,TCP,443"],
    ["port 0", "#tuple=192.0.2.10,198.51.100.20,tcp,0"],
    ["port 65536", "#tuple=192.0.2.10,198.51.100.20,tcp,65536"],
    ["negative port (-1)", "#tuple=192.0.2.10,198.51.100.20,tcp,-1"],
    ["signed port (+80)", "#tuple=192.0.2.10,198.51.100.20,tcp,+80"],
    ["leading-space port ( 80)", "#tuple=192.0.2.10,198.51.100.20,tcp, 80"],
    ["trailing-space port (80 )", "#tuple=192.0.2.10,198.51.100.20,tcp,80 "],
    ["leading-zero port (080)", "#tuple=192.0.2.10,198.51.100.20,tcp,080"],
    ["non-numeric port (80x)", "#tuple=192.0.2.10,198.51.100.20,tcp,80x"],
    ["fifth part", "#tuple=192.0.2.10,198.51.100.20,tcp,443,extra"],
    ["trailing comma fifth empty part", "#tuple=192.0.2.10,198.51.100.20,tcp,443,"],
    ["empty source part", "#tuple=,198.51.100.20,tcp,443"],
    ["empty destination part", "#tuple=192.0.2.10,,tcp,443"],
    ["empty protocol part", "#tuple=192.0.2.10,198.51.100.20,,443"],
    ["empty port part", "#tuple=192.0.2.10,198.51.100.20,tcp,"],
    ["over-long string", `#tuple=192.0.2.10,198.51.100.20,tcp,443${"0".repeat(80)}`],
    ["encoded CRLF (%0d%0a)", "#tuple=192.0.2.10,198.51.100.20,tcp,443%0d%0a"],
    ["encoded LF (%0A)", "#tuple=192.0.2.10,198.51.100.20,tcp,443%0A"],
    ["encoded digit (%380)", "#tuple=192.0.2.10,198.51.100.20,tcp,%380"],
    ["raw CR/LF", "#tuple=192.0.2.10,198.51.100.20,tcp,443\r\n"],
  ])("refuses invalid fragment: %s", (_label, badHash) => {
    expect(parseTraceTuple(badHash)).toBeNull();
  });

  it("keeps the tuple fragment across isInAppNavigationHref, canonicalizeAppUrl, navigateTo, handleInAppLinkClick and writeUrlParams", () => {
    const href = traceTupleHref({ src: "192.0.2.10", dst: "198.51.100.20", protocol: "tcp", port: 22 });

    expect(isInAppNavigationHref(href)).toBe(true);
    const canonical = canonicalizeAppUrl(href);
    expect(canonical.search).toBe("?screen=trace&mode=path");
    expect(canonical.hash).toBe("#tuple=192.0.2.10,198.51.100.20,tcp,22");

    window.history.replaceState(null, "", "/?screen=cutover&assessment=a1");
    navigateTo(href);
    expect(window.location.search).toBe("?screen=trace&mode=path");
    expect(window.location.hash).toBe("#tuple=192.0.2.10,198.51.100.20,tcp,22");

    // writeUrlParams updates query params without discarding an existing fragment.
    writeUrlParams({ mode: "lookup" });
    expect(window.location.search).toBe("?screen=trace&mode=lookup");
    expect(window.location.hash).toBe("#tuple=192.0.2.10,198.51.100.20,tcp,22");

    // In-app anchor click interception preserves the fragment end to end.
    window.history.replaceState(null, "", "/?screen=cutover");
    const container = document.createElement("div");
    const anchor = document.createElement("a");
    anchor.setAttribute("href", href);
    container.appendChild(anchor);
    document.body.appendChild(container);
    try {
      const click = new MouseEvent("click", { bubbles: true, cancelable: true, button: 0 });
      Object.defineProperty(click, "target", { value: anchor });
      expect(handleInAppLinkClick(click)).toBe(true);
      expect(window.location.search).toBe("?screen=trace&mode=path");
      expect(window.location.hash).toBe("#tuple=192.0.2.10,198.51.100.20,tcp,22");
    } finally {
      document.body.removeChild(container);
    }
  });
});
