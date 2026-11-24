import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { ChangeDiff } from "../src/screens/ChangeDiff";
import type { ChangeFeedEvent } from "../src/auth/adminApi";

/** Synthetic, masked-shaped data only: pseudonym-style names, RFC 5737 addresses, made-up object IDs. */
type Lines = ChangeFeedEvent["diff"];
const ID = { a: "00000000-0000-4000-8000-00000000000a", b: "00000000-0000-4000-8000-00000000000b", c: "00000000-0000-4000-8000-00000000000c" };
const refs = (...items: readonly (readonly [string, string])[]) => JSON.stringify({ refs: items.map(([id, name]) => ({ id, name })) });

/** The Palo Alto "modified rule" event the PO looked at, in masked form. */
const modified: Lines = [
  { op: "-", text: `destination: ${refs([ID.a, "Host_192.0.2.10"], [ID.b, "Host_192.0.2.11"])}` },
  { op: "+", text: `destination: ${refs([ID.c, "Net_198.51.100.0/24"], [ID.a, "Host_192.0.2.10"])}` },
  { op: "-", text: 'log: "start=UNKNOWN, end=UNKNOWN"' },
  { op: "+", text: 'log: "start=no, end=yes"' },
  { op: "-", text: 'extras: {"to":["any"],"from":["ZONE-ALPHA-01"],"log-setting":["LOGPROFILE-ALPHA-01"],"vendor-key":["Withheld in AIView"]}' },
  { op: "+", text: 'extras: {"to":["any"],"from":["ZONE-ALPHA-01"],"vendor-key":["Withheld in AIView"],"target/negate":["no"]}' },
  { op: "-", text: 'comment: "12755895"' },
  { op: "+", text: 'comment: "12755895 24023036"' },
];

function mount(lines: Lines, readable = true) {
  const view = render(<ThemeProvider theme={m3Theme}><ChangeDiff lines={lines} readable={readable} /></ThemeProvider>);
  const details = view.container.querySelector("details") as HTMLDetailsElement;
  const row = (field: string) => view.container.querySelector(`[data-field="${field}"]`) as HTMLElement;
  const lineOf = (field: string, name: string) => row(field).querySelector(`[data-line="${name}"]`) as HTMLElement;
  return { ...view, details, row, lineOf };
}

describe("ChangeDiff", () => {
  it("stays collapsed and says in one line what changed", () => {
    const { details } = mount(modified);
    expect(details.open).toBe(false);
    expect(details.querySelector("summary")?.textContent)
      .toBe("Diff · Destination +1 -1 · Logging at start: UNKNOWN → no · Logging at end: UNKNOWN → yes · Log setting removed · target/negate added · +1 more");
  });

  it("shows one row per changed field with a readable label, in the stored order", () => {
    const { container } = mount(modified);
    const rows = [...container.querySelectorAll("[data-field]")];
    expect(rows.map((r) => r.getAttribute("data-field"))).toEqual(["destination", "log", "extras", "comment"]);
    expect(rows.map((r) => r.querySelector("dt")?.textContent)).toEqual(["Destination", "Logging", "Other settings", "Comment"]);
    // the raw field name stays one hover away
    expect(rows[2].querySelector("dt")).toHaveAttribute("title", "extras");
    expect(screen.getByLabelText("Changed fields").tagName).toBe("DL");
  });

  it("shows an object list as added, removed and unchanged by name, with the IDs on hover and in Object IDs only", () => {
    const { row, lineOf } = mount(modified);
    expect(lineOf("destination", "added").textContent).toBe("Added: Net_198.51.100.0/24");
    expect(lineOf("destination", "removed").textContent).toBe("Removed: Host_192.0.2.11");
    expect(lineOf("destination", "unchanged").textContent).toBe("Unchanged: 1 object");
    expect(within(lineOf("destination", "added")).getByText("Net_198.51.100.0/24")).toHaveAttribute("title", `Object ID ${ID.c}`);
    // no ID in the row itself: the only place the text holds one is the closed "Object IDs" fold
    const ids = lineOf("destination", "ids") as HTMLDetailsElement;
    expect(ids.open).toBe(false);
    expect(ids.querySelector("summary")?.textContent).toBe("Object IDs");
    const outside = [...row("destination").querySelectorAll("[data-line]")].filter((el) => el !== ids).map((el) => el.textContent).join(" ");
    expect(outside).not.toMatch(/00000000-/);
    expect([...ids.querySelectorAll("li")].map((li) => li.textContent)).toEqual([
      `Added · Net_198.51.100.0/24 · ${ID.c}`, `Removed · Host_192.0.2.11 · ${ID.b}`, `Unchanged · Host_192.0.2.10 · ${ID.a}`,
    ]);
  });

  it("shows a second same-named object with a distinct ID in the row and in Object IDs", () => {
    const { lineOf, details } = mount([
      { op: "-", text: `destination: ${refs([ID.a, "Host_192.0.2.10"])}` },
      { op: "+", text: `destination: ${refs([ID.a, "Host_192.0.2.10"], [ID.b, "Host_192.0.2.10"])}` },
    ]);
    expect(details.querySelector("summary")?.textContent).toBe("Diff · Destination +1");
    expect(lineOf("destination", "added").textContent).toBe("Added: Host_192.0.2.10");
    expect(lineOf("destination", "unchanged").textContent).toBe("Unchanged: 1 object");
    expect([...lineOf("destination", "ids").querySelectorAll("li")].map((li) => li.textContent)).toEqual([
      `Added · Host_192.0.2.10 · ${ID.b}`, `Unchanged · Host_192.0.2.10 · ${ID.a}`,
    ]);
  });

  it("shows only the changed keys of extras, the log halves, and the appended part of the comment marked", () => {
    const { row, lineOf } = mount(modified);
    const keys = [...row("extras").querySelectorAll("[data-key]")];
    expect(keys.map((k) => k.textContent)).toEqual(["Log setting: LOGPROFILE-ALPHA-01 (removed)", "target/negate: no (added)"]);
    expect(lineOf("log", "log").textContent).toBe("Log at session start: UNKNOWN → no; at session end: UNKNOWN → yes");
    const comment = lineOf("comment", "text");
    expect(comment.textContent).toBe("12755895 → 12755895 24023036");
    expect(comment.querySelector("ins")?.textContent).toBe(" 24023036");
    expect(comment.querySelector("del")).toBeNull();
  });

  it("marks the replaced word on both sides of a changed text, and a key that changed as before and after", () => {
    const { lineOf, row } = mount([
      { op: "-", text: 'comment: "allow web to db"' }, { op: "+", text: 'comment: "allow api to db"' },
      { op: "-", text: 'extras: {"to":["any"]}' }, { op: "+", text: 'extras: {"to":["any","ZONE-BRAVO-02"]}' },
    ]);
    expect(lineOf("comment", "text").querySelector("del")?.textContent).toBe("web");
    expect(lineOf("comment", "text").querySelector("ins")?.textContent).toBe("api");
    expect(row("extras").querySelector("[data-key]")?.textContent).toBe("Zones to: any → any, ZONE-BRAVO-02");
    expect(row("extras").querySelector("[data-key] [title]")).toHaveAttribute("title", "to");
  });

  it("keeps today's exact lines behind the Raw toggle", () => {
    const { container, details } = mount(modified);
    const raw = within(details).getByRole("button", { name: "Raw" });
    expect(raw).toHaveAttribute("aria-pressed", "false");
    expect(container.querySelector("pre")).toBeNull();
    expect(within(details).getByText("4 fields · stored lines: 4 added · 4 removed")).toBeInTheDocument();

    fireEvent.click(raw);
    expect(raw).toHaveAttribute("aria-pressed", "true");
    expect(container.querySelector("dl")).toBeNull();
    const lines = [...container.querySelectorAll("pre [data-op]")];
    expect(lines.map((l) => l.textContent)).toEqual(modified.map((l) => `${l.op} ${l.text}`));
    expect(lines.map((l) => l.getAttribute("data-op"))).toEqual(modified.map((l) => l.op));
    expect(getComputedStyle(lines[0]).color).not.toEqual(getComputedStyle(lines[1]).color);

    fireEvent.click(raw);
    expect(container.querySelector("pre")).toBeNull();
    expect(container.querySelector("dl")).not.toBeNull();
  });

  it("serves an added rule as all after and a removed rule as all before, with no arrows and no added/removed marks", () => {
    const rule = (op: "+" | "-"): Lines => [
      { op, text: 'name: "Rule_47"' }, { op, text: `source: ${refs([ID.a, "Host_192.0.2.10"], [ID.b, "Net_198.51.100.0/24"])}` },
      { op, text: 'action: "allow"' }, { op, text: 'log: "start=no, end=yes"' }, { op, text: 'extras: {"to":["any"],"from":["ZONE-ALPHA-01"]}' },
    ];
    const added = mount(rule("+"));
    expect(added.details.querySelector("summary")?.textContent).toBe("Diff · 5 fields added");
    expect([...added.container.querySelectorAll("[data-field]")].map((r) => r.getAttribute("data-side"))).toEqual(["after", "after", "after", "after", "after"]);
    expect(added.lineOf("name", "text").textContent).toBe("Rule_47");
    expect(added.lineOf("source", "after").textContent).toBe("Host_192.0.2.10, Net_198.51.100.0/24");
    expect(added.lineOf("log", "log").textContent).toBe("Log at session start: no; at session end: yes");
    expect([...added.row("extras").querySelectorAll("[data-key]")].map((k) => k.textContent)).toEqual(["Zones to: any", "Zones from: ZONE-ALPHA-01"]);
    expect(added.container.querySelector("dl")?.textContent).not.toMatch(/→|\(added\)|Added:/);
    expect([...added.lineOf("source", "ids").querySelectorAll("li")].map((li) => li.textContent))
      .toEqual([`After · Host_192.0.2.10 · ${ID.a}`, `After · Net_198.51.100.0/24 · ${ID.b}`]);
    added.unmount();

    const removed = mount(rule("-"));
    expect(removed.details.querySelector("summary")?.textContent).toBe("Diff · 5 fields removed");
    expect(removed.lineOf("source", "before").textContent).toBe("Host_192.0.2.10, Net_198.51.100.0/24");
    expect(removed.lineOf("action", "text").textContent).toBe("allow");
    expect(removed.container.querySelector("dl")?.textContent).not.toMatch(/→|\(removed\)|Removed:/);
  });

  it("shows a value it cannot read exactly as stored, beside the readable rows", () => {
    const { row, lineOf, details } = mount([
      { op: "-", text: 'comment: {"broken' }, { op: "+", text: 'comment: "12755895"' },
      { op: "-", text: 'action: "allow"' }, { op: "+", text: 'action: "deny"' },
    ]);
    expect(row("comment")).toHaveAttribute("data-kind", "raw");
    expect(lineOf("comment", "raw").textContent).toBe('{"broken → "12755895"');
    expect(lineOf("action", "text").textContent).toBe("allow → deny");
    expect(details.querySelector("summary")?.textContent).toBe("Diff · Comment changed · Action: allow → deny");
  });

  it("is the raw block alone, as before, for a diff that is not field lines or not a rule diff", () => {
    const freeText: Lines = [{ op: "-", text: "<action>deny</action>" }, { op: "+", text: "<action>allow</action>" }];
    const unpaired = mount(freeText);
    expect(unpaired.details.querySelector("summary")?.textContent).toBe("Diff · 1 added · 1 removed");
    expect(within(unpaired.details).queryByRole("button", { name: "Raw" })).toBeNull();
    expect(unpaired.container.querySelector("dl")).toBeNull();
    expect([...unpaired.container.querySelectorAll("pre [data-op]")].map((l) => l.textContent)).toEqual(["- <action>deny</action>", "+ <action>allow</action>"]);
    unpaired.unmount();

    // another source's lines are never read as fields, even when they happen to look like them
    const other = mount([{ op: "-", text: 'action: "allow"' }, { op: "+", text: 'action: "deny"' }], false);
    expect(other.details.querySelector("summary")?.textContent).toBe("Diff · 1 added · 1 removed");
    expect(other.container.querySelector("dl")).toBeNull();
    expect(other.container.querySelector("pre")).not.toBeNull();
  });

  it("renders masked names exactly as the server sent them and adds no other name or address", () => {
    const { container, details } = mount(modified);
    const text = `${details.querySelector("summary")?.textContent} ${container.querySelector("dl")?.textContent}`;
    for (const name of ["Net_198.51.100.0/24", "Host_192.0.2.11", "LOGPROFILE-ALPHA-01"]) expect(text).toContain(name);
    // the unchanged extras keys, including the withheld marker, are not repeated in the readable rows
    expect(text).not.toContain("Withheld in AIView");
    expect(text).not.toContain("ZONE-ALPHA-01");
    // every address in the readable view is one of the fixture's own documentation addresses
    const addresses = text.match(/\d{1,3}(?:\.\d{1,3}){3}/g) ?? [];
    expect(addresses.length).toBeGreaterThan(0);
    expect(addresses.every((ip) => ip.startsWith("192.0.2.") || ip.startsWith("198.51.100."))).toBe(true);
  });
});
