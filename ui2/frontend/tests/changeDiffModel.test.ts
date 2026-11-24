import { describe, expect, it } from "vitest";
import {
  diffText, fieldLabel, formatValue, keyLabel, logSentence, pairDiffLines, summariseChanges,
  type DiffLine, type FieldChange, type KeysChange, type LogChange, type RawChange, type RefsChange, type TextChange,
} from "../src/screens/changeDiffModel";

/** Synthetic, masked-shaped data only: pseudonym-style names, RFC 5737 addresses, made-up object IDs. */
const ID = { a: "00000000-0000-4000-8000-00000000000a", b: "00000000-0000-4000-8000-00000000000b", c: "00000000-0000-4000-8000-00000000000c" };
const refs = (...items: readonly (readonly [string, string])[]) => JSON.stringify({ refs: items.map(([id, name]) => ({ id, name })) });
const minus = (field: string, json: string): DiffLine => ({ op: "-", text: `${field}: ${json}` });
const plus = (field: string, json: string): DiffLine => ({ op: "+", text: `${field}: ${json}` });
const pair = (lines: readonly DiffLine[]) => pairDiffLines(lines) as FieldChange[];
/** A reference as the model returns it: name, ID and the stored entry with its keys sorted. */
const ref = (id: string, name: string) => ({ name, id, stored: JSON.stringify({ id, name }) });
const names = (items: readonly { readonly name: string }[]) => items.map((item) => item.name);

describe("pairDiffLines", () => {
  it("pairs the - and + lines by field, in the order the fields first appear, also when they are not adjacent", () => {
    const changes = pair([
      minus("action", '"allow"'), minus("comment", '"12755895"'),
      plus("comment", '"12755895 24023036"'), plus("action", '"deny"'),
    ]);
    expect(changes.map((c) => [c.field, c.label, c.side])).toEqual([["action", "Action", "both"], ["comment", "Comment", "both"]]);
  });

  it("labels the known rule fields and shows an unknown field by its raw name", () => {
    expect(["source", "destination", "service", "application", "action", "log", "comment", "extras"].map(fieldLabel))
      .toEqual(["Source", "Destination", "Service", "Application", "Action", "Logging", "Comment", "Other settings"]);
    expect(fieldLabel("vendorThing")).toBe("vendorThing");
    expect(pair([plus("vendorThing", '"x"')])[0].label).toBe("vendorThing");
    expect([keyLabel("from"), keyLabel("to"), keyLabel("profile-setting/group"), keyLabel("profile-setting/profiles/virus"), keyLabel("target/negate")])
      .toEqual(["Zones from", "Zones to", "Profile group", "Profile virus", "target/negate"]);
  });

  it("reads an object list as a set difference by name and keeps the IDs beside the names", () => {
    const [change] = pair([
      minus("destination", refs([ID.a, "Host_192.0.2.10"], [ID.b, "Host_192.0.2.11"])),
      plus("destination", refs([ID.c, "Net_198.51.100.0/24"], [ID.a, "Host_192.0.2.10"])),
    ]) as RefsChange[];
    expect(change.kind).toBe("refs");
    expect(change.added).toEqual([ref(ID.c, "Net_198.51.100.0/24")]);
    expect(change.removed).toEqual([ref(ID.b, "Host_192.0.2.11")]);
    expect(change.unchanged).toEqual([ref(ID.a, "Host_192.0.2.10")]);
    expect(change.reordered).toBe(false);
    expect(summariseChanges([change])).toBe("Destination +1 -1");
  });

  it("accepts bare names as references, and reports a pure reorder and a changed cell key apart from the set", () => {
    const [reorder] = pair([minus("service", '{"refs":["tcp_443","tcp_80"]}'), plus("service", '{"refs":["tcp_80","tcp_443"]}')]) as RefsChange[];
    expect([reorder.added, reorder.removed, names(reorder.unchanged), reorder.reordered]).toEqual([[], [], ["tcp_80", "tcp_443"], true]);
    expect(summariseChanges([reorder])).toBe("Service reordered");

    const [negated] = pair([minus("source", '{"refs":["Host_192.0.2.10"],"negated":false}'), plus("source", '{"refs":["Host_192.0.2.10"],"negated":true}')]) as RefsChange[];
    expect(negated.reordered).toBe(false);
    expect(negated.keys).toEqual([{ key: "negated", label: "Negated", state: "changed", before: "false", after: "true" }]);
    expect(summariseChanges([negated])).toBe("Source changed");
  });

  it("does not call an object unchanged in silence when the same name is a different stored entry", () => {
    const [change] = pair([minus("source", refs([ID.a, "Host_192.0.2.10"])), plus("source", refs([ID.b, "Host_192.0.2.10"]))]) as RefsChange[];
    expect(change.added).toEqual([]);
    expect(change.removed).toEqual([]);
    expect(change.unchanged).toEqual([{ ...ref(ID.b, "Host_192.0.2.10"), previous: { id: ID.a, stored: ref(ID.a, "Host_192.0.2.10").stored } }]);

    // an entry may carry more than id and name; a change there is kept, and key order alone is not a change
    const entry = (values: string[]) => `{"refs":[{"id":"${ID.a}","name":"Host_192.0.2.10","values":${JSON.stringify(values)}}]}`;
    const [attribute] = pair([minus("source", entry(["192.0.2.10"])), plus("source", entry(["192.0.2.11"]))]) as RefsChange[];
    expect(attribute.kind).toBe("refs");
    expect(attribute.unchanged[0].previous?.stored).toBe(`{"id":"${ID.a}","name":"Host_192.0.2.10","values":["192.0.2.10"]}`);
    const [same] = pair([minus("source", `{"refs":[{"name":"Host_192.0.2.10","id":"${ID.a}"}],"negated":false}`), plus("source", `{"refs":[{"id":"${ID.a}","name":"Host_192.0.2.10"}],"negated":true}`)]) as RefsChange[];
    expect(same.unchanged[0].previous).toBeUndefined();
  });

  it("keeps duplicate-named objects with distinct IDs instead of dropping the second one", () => {
    const [addedDup] = pair([
      minus("destination", refs([ID.a, "Host_192.0.2.10"])),
      plus("destination", refs([ID.a, "Host_192.0.2.10"], [ID.b, "Host_192.0.2.10"])),
    ]) as RefsChange[];
    expect(addedDup.added).toEqual([ref(ID.b, "Host_192.0.2.10")]);
    expect(addedDup.removed).toEqual([]);
    expect(addedDup.unchanged).toEqual([ref(ID.a, "Host_192.0.2.10")]);
    expect(summariseChanges([addedDup])).toBe("Destination +1");

    const [removedDup] = pair([
      minus("destination", refs([ID.a, "Host_192.0.2.10"], [ID.b, "Host_192.0.2.10"])),
      plus("destination", refs([ID.b, "Host_192.0.2.10"])),
    ]) as RefsChange[];
    expect(removedDup.added).toEqual([]);
    expect(removedDup.removed).toEqual([ref(ID.a, "Host_192.0.2.10")]);
    expect(removedDup.unchanged).toEqual([ref(ID.b, "Host_192.0.2.10")]);
    expect(summariseChanges([removedDup])).toBe("Destination -1");

    const [reorderedDup] = pair([
      minus("destination", refs([ID.a, "Host_192.0.2.10"], [ID.b, "Host_192.0.2.10"])),
      plus("destination", refs([ID.b, "Host_192.0.2.10"], [ID.a, "Host_192.0.2.10"])),
    ]) as RefsChange[];
    expect(reorderedDup.added).toEqual([]);
    expect(reorderedDup.removed).toEqual([]);
    expect(reorderedDup.unchanged).toEqual([ref(ID.b, "Host_192.0.2.10"), ref(ID.a, "Host_192.0.2.10")]);
    expect(reorderedDup.reordered).toBe(true);
    expect(summariseChanges([reorderedDup])).toBe("Destination reordered");
  });

  it("shows only the keys of extras that changed, flattening nested objects, with added and removed marked", () => {
    const [change] = pair([
      minus("extras", JSON.stringify({ to: ["any"], from: ["Zone_A"], "log-setting": ["LOGPROFILE-ALPHA-01"], "profile-setting": { group: ["PG_1"], profiles: { virus: ["AV_1"] } } })),
      plus("extras", JSON.stringify({ from: ["Zone_A"], to: ["any", "Zone_B"], "target/negate": ["no"], "profile-setting": { group: ["PG_1"], profiles: { virus: ["AV_2"] } } })),
    ]) as KeysChange[];
    expect(change.kind).toBe("keys");
    expect(change.keys).toEqual([
      { key: "to", label: "Zones to", state: "changed", before: "any", after: "any, Zone_B" },
      { key: "log-setting", label: "Log setting", state: "removed", before: "LOGPROFILE-ALPHA-01", after: null },
      { key: "profile-setting.profiles.virus", label: "profile-setting.profiles.virus", state: "changed", before: "AV_1", after: "AV_2" },
      { key: "target/negate", label: "target/negate", state: "added", before: null, after: "no" },
    ]);
    expect(summariseChanges([change])).toBe("Other settings: 4 keys changed");
  });

  it("names the changed keys in the summary when there are one or two, and treats key order as no change", () => {
    const [two] = pair([minus("extras", '{"log-setting":["LOGPROFILE-ALPHA-01"],"tag":["t1"]}'), plus("extras", '{"tag":["t1"],"target/negate":["no"]}')]) as KeysChange[];
    expect(summariseChanges([two])).toBe("Log setting removed · target/negate added");
    const [order] = pair([minus("extras", '{"a":{"x":1,"y":2},"b":[1]}'), plus("extras", '{"b":[1],"a":{"y":2,"x":1}}')]) as KeysChange[];
    expect(order.keys).toEqual([]);
  });

  it("reads the log string as its two halves and never calls a transition from UNKNOWN turned on or off", () => {
    const [fromUnknown] = pair([minus("log", '"start=UNKNOWN, end=UNKNOWN"'), plus("log", '"start=no, end=yes"')]) as LogChange[];
    expect(fromUnknown.kind).toBe("log");
    expect(fromUnknown.parts).toEqual([{ when: "start", before: "UNKNOWN", after: "no" }, { when: "end", before: "UNKNOWN", after: "yes" }]);
    expect(logSentence(fromUnknown)).toBe("Log at session start: UNKNOWN → no; at session end: UNKNOWN → yes");
    expect(summariseChanges([fromUnknown])).toBe("Logging at start: UNKNOWN → no · Logging at end: UNKNOWN → yes");

    const [on] = pair([minus("log", '"start=no, end=no"'), plus("log", '"start=no, end=yes"')]) as LogChange[];
    expect(summariseChanges([on])).toBe("Logging at end turned on");

    const [off] = pair([minus("log", '"start=yes, end=yes"'), plus("log", '"start=no, end=yes"')]) as LogChange[];
    expect(logSentence(off)).toBe("Log at session start: yes → no; at session end: yes (unchanged)");
    expect(summariseChanges([off])).toBe("Logging at start turned off");

    const [toUnknown] = pair([minus("log", '"start=no, end=yes"'), plus("log", '"start=no, end=UNKNOWN"')]) as LogChange[];
    expect(summariseChanges([toUnknown])).toBe("Logging at end: yes → UNKNOWN");
  });

  it("keeps a log value that is not the known shape as plain text, never a guessed reading", () => {
    const [change] = pair([minus("log", '"UNKNOWN"'), plus("log", '"start=no, end=yes"')]);
    expect(change.kind).toBe("text");
  });

  it("marks the appended or changed words of a text", () => {
    const [change] = pair([minus("comment", '"12755895"'), plus("comment", '"12755895 24023036"')]) as TextChange[];
    expect(change.kind).toBe("text");
    expect([change.before, change.after]).toEqual(["12755895", "12755895 24023036"]);
    expect(change.beforeSegments).toEqual([{ text: "12755895", changed: false }]);
    expect(change.afterSegments).toEqual([{ text: "12755895", changed: false }, { text: " 24023036", changed: true }]);
    expect(summariseChanges([change])).toBe("Comment changed");

    expect(diffText("allow web to db", "allow api to db")).toEqual([
      [{ text: "allow ", changed: false }, { text: "web", changed: true }, { text: " to db", changed: false }],
      [{ text: "allow ", changed: false }, { text: "api", changed: true }, { text: " to db", changed: false }],
    ]);
    // whole words only: two values that merely share letters are marked whole
    expect(diffText("allow", "alter")).toEqual([[{ text: "allow", changed: true }], [{ text: "alter", changed: true }]]);
  });

  it("reads numbers and booleans as text and puts a short value in the summary", () => {
    const changes = pair([minus("action", '"allow"'), plus("action", '"deny"'), minus("enabled", "true"), plus("enabled", "false"), minus("number", "7"), plus("number", "8")]);
    expect(changes.map((c) => c.kind)).toEqual(["text", "text", "text"]);
    expect(summariseChanges(changes)).toBe("Action: allow → deny · Enabled: true → false · Rule number: 7 → 8");
  });

  it("keeps numeric identifiers above 2^53 as exact raw text without rounding", () => {
    const [change] = pair([minus("parentRuleId", "9007199254740992"), plus("parentRuleId", "9007199254740993")]) as TextChange[];
    expect(change.kind).toBe("text");
    expect([change.before, change.after]).toEqual(["9007199254740992", "9007199254740993"]);
    expect(change.beforeSegments).toEqual([{ text: "9007199254740992", changed: true }]);
    expect(change.afterSegments).toEqual([{ text: "9007199254740993", changed: true }]);
    expect(summariseChanges([change])).toBe("Parent rule: 9007199254740992 → 9007199254740993");
  });

  it("serves an added rule as all after and a removed rule as all before", () => {
    const lines = (op: "+" | "-"): DiffLine[] => [
      { op, text: 'name: "Rule_47"' }, { op, text: `source: ${refs([ID.a, "Host_192.0.2.10"])}` }, { op, text: 'action: "allow"' },
      { op, text: 'log: "start=no, end=yes"' }, { op, text: 'extras: {"to":["any"]}' },
    ];
    const addedRule = pair(lines("+")), removedRule = pair(lines("-"));
    expect(addedRule.map((c) => c.side)).toEqual(["after", "after", "after", "after", "after"]);
    expect(removedRule.map((c) => c.side)).toEqual(["before", "before", "before", "before", "before"]);
    expect(names((addedRule[1] as RefsChange).added)).toEqual(["Host_192.0.2.10"]);
    expect(names((removedRule[1] as RefsChange).removed)).toEqual(["Host_192.0.2.10"]);
    expect((addedRule[0] as TextChange).afterSegments).toEqual([{ text: "Rule_47", changed: false }]);
    expect(logSentence(addedRule[3] as LogChange)).toBe("Log at session start: no; at session end: yes");
    expect((addedRule[4] as KeysChange).keys).toEqual([{ key: "to", label: "Zones to", state: "added", before: null, after: "any" }]);
    expect(summariseChanges(addedRule)).toBe("5 fields added");
    expect(summariseChanges(removedRule)).toBe("5 fields removed");
    // a single one-sided field is named, not counted
    expect(summariseChanges(pair([plus("comment", '"12755895"')]))).toBe("Comment added");
  });

  it("falls back to the raw value of one field when it is not JSON or not a shape it can read", () => {
    const changes = pair([
      minus("comment", '{"broken'), plus("comment", '"12755895"'),
      minus("service", '["tcp_443"]'), plus("service", '["tcp_80"]'),
      minus("source", '{"refs":[{"id":"x","label":"Host_192.0.2.10"}]}'), plus("source", '"any"'),
      minus("action", '"allow"'), plus("action", '"deny"'),
    ]);
    expect(changes.map((c) => c.kind)).toEqual(["raw", "raw", "raw", "text"]);
    expect([(changes[0] as RawChange).before, (changes[0] as RawChange).after]).toEqual(['{"broken', '"12755895"']);
    expect((changes[1] as RawChange).before).toBe('["tcp_443"]');
    expect(summariseChanges(changes)).toBe("Comment changed · Service changed · Source changed · Action: allow → deny");
  });

  it("declines the whole diff when the lines cannot be paired without guessing", () => {
    expect(pairDiffLines([])).toBeNull();
    expect(pairDiffLines([{ op: "-", text: "<action>deny</action>" }, { op: "+", text: "<action>allow</action>" }])).toBeNull();
    expect(pairDiffLines([minus("action", '"allow"'), { op: " ", text: 'name: "Rule_47"' }])).toBeNull();
    expect(pairDiffLines([minus("action", '"allow"'), minus("action", '"drop"'), plus("action", '"deny"')])).toBeNull();
  });

  it("never rewrites a value: masked names and withheld markers come out as they went in", () => {
    const [extras] = pair([minus("extras", '{"vendor-key":["Withheld in AIView"]}'), plus("extras", '{"vendor-key":["Withheld in AIView"],"tag":["TAG-ALPHA-01"]}')]) as KeysChange[];
    expect(extras.keys).toEqual([{ key: "tag", label: "Tags", state: "added", before: null, after: "TAG-ALPHA-01" }]);
    expect(formatValue(["Withheld in AIView"])).toBe("Withheld in AIView");
    expect([formatValue(""), formatValue([]), formatValue([{ a: 1 }]), formatValue(null)]).toEqual(["(empty)", "(none)", '[{"a":1}]', "null"]);
  });

  it("caps a long summary and says how many parts it left out", () => {
    const changes = pair(["name", "action", "section", "container", "parentRuleId", "number", "enabled"].flatMap((f) => [minus(f, '"a"'), plus(f, '"b"')]));
    expect(summariseChanges(changes)).toBe("Name: a → b · Action: a → b · Section: a → b · Container: a → b · Parent rule: a → b · +2 more");
  });
});
