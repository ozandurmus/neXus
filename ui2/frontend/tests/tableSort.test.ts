import { describe, expect, it } from "vitest";

import { compareSortValues, nextTableSort, sortRows, type SortColumn } from "../src/shell/tableSort";

/**
 * The shared column sort (Product Owner, 2026-11-04): text with numeric collation, numbers as numbers, times
 * chronologically, unknown values last both ways, ties in the default order, and the click cycle
 * ascending -> descending -> default.
 */
describe("compareSortValues", () => {
  it("orders text with numeric collation, so FW-TANGO-2 comes before FW-TANGO-10", () => {
    const names = ["FW-TANGO-10", "FW-TANGO-2", "FW-TANGO-1", "fw-tango-3"];
    expect([...names].sort((a, b) => compareSortValues(a, b, "text", "asc"))).toEqual(["FW-TANGO-1", "FW-TANGO-2", "fw-tango-3", "FW-TANGO-10"]);
    expect([...names].sort((a, b) => compareSortValues(a, b, "text", "desc"))).toEqual(["FW-TANGO-10", "fw-tango-3", "FW-TANGO-2", "FW-TANGO-1"]);
    // addresses and prefixes: 10.0.2.0/24 before 10.0.10.0/24
    expect(["10.0.10.0/24", "10.0.2.0/24", "192.0.2.0/24"].sort((a, b) => compareSortValues(a, b))).toEqual(["10.0.2.0/24", "10.0.10.0/24", "192.0.2.0/24"]);
  });

  it("compares numbers as numbers, not as text", () => {
    const sizes = [9, 100, 25, 1024];
    expect([...sizes].sort((a, b) => compareSortValues(a, b, "number", "asc"))).toEqual([9, 25, 100, 1024]);
    expect([...sizes].sort((a, b) => compareSortValues(a, b, "number", "desc"))).toEqual([1024, 100, 25, 9]);
    expect(compareSortValues("100", "25", "number")).toBeGreaterThan(0);
  });

  it("compares times chronologically whatever their written form", () => {
    const times = ["2026-10-18T02:30:00Z", "2026-09-30T23:59:59Z", "2026-10-18T02:30:00+03:00"];
    expect([...times].sort((a, b) => compareSortValues(a, b, "time", "asc"))).toEqual(["2026-09-30T23:59:59Z", "2026-10-18T02:30:00+03:00", "2026-10-18T02:30:00Z"]);
    expect([...times].sort((a, b) => compareSortValues(a, b, "time", "desc"))).toEqual(["2026-10-18T02:30:00Z", "2026-10-18T02:30:00+03:00", "2026-09-30T23:59:59Z"]);
  });

  it("puts an empty or unknown value last in both directions, and ties stay +0", () => {
    for (const direction of ["asc", "desc"] as const) {
      const text = ["b", null, "a", undefined, ""].sort((x, y) => compareSortValues(x, y, "text", direction));
      expect(text.slice(0, 2)).toEqual(direction === "asc" ? ["a", "b"] : ["b", "a"]);
      expect(new Set(text.slice(2))).toEqual(new Set([null, undefined, ""]));
      const numbers = [3, null, Number.NaN, 1].sort((x, y) => compareSortValues(x, y, "number", direction));
      expect(numbers.slice(0, 2)).toEqual(direction === "asc" ? [1, 3] : [3, 1]);
      expect(numbers.slice(2)).toEqual([null, Number.NaN]);
      expect(["2026-01-01T00:00:00Z", "not a time", null].sort((x, y) => compareSortValues(x, y, "time", direction))[0]).toBe("2026-01-01T00:00:00Z");
    }
    expect(compareSortValues("same", "same", "text", "desc")).toBe(0);
    expect(Object.is(compareSortValues(5, 5, "number", "desc"), 0)).toBe(true);
    expect(compareSortValues(null, undefined)).toBe(0);
  });
});

describe("nextTableSort and sortRows", () => {
  type Row = { id: number; name: string | null; size: number | null; at: string | null };
  const columns: ReadonlyArray<SortColumn<Row>> = [
    { key: "name", value: (r) => r.name },
    { key: "size", value: (r) => r.size, kind: "number", firstDirection: "desc" },
    { key: "at", value: (r) => r.at, kind: "time" },
  ];
  const rows: Row[] = [
    { id: 1, name: "FW-TANGO-10", size: 200, at: "2026-10-02T00:00:00Z" },
    { id: 2, name: "FW-TANGO-2", size: null, at: null },
    { id: 3, name: null, size: 200, at: "2026-10-01T00:00:00Z" },
    { id: 4, name: "FW-TANGO-1", size: 50, at: "2026-10-03T00:00:00Z" },
  ];
  const ids = (list: readonly Row[]) => list.map((r) => r.id);

  it("cycles ascending, descending, default; a column may start descending", () => {
    const first = nextTableSort(null, "name");
    expect(first).toEqual({ key: "name", direction: "asc" });
    const second = nextTableSort(first, "name");
    expect(second).toEqual({ key: "name", direction: "desc" });
    expect(nextTableSort(second, "name")).toBeNull();
    // a click on another column starts that column's cycle afresh
    expect(nextTableSort(second, "size", "desc")).toEqual({ key: "size", direction: "desc" });
    expect(nextTableSort({ key: "size", direction: "desc" }, "size", "desc")).toEqual({ key: "size", direction: "asc" });
    expect(nextTableSort({ key: "size", direction: "asc" }, "size", "desc")).toBeNull();
  });

  it("orders the whole list, keeps the default order when there is no sort, and never mutates the input", () => {
    const input = [...rows];
    expect(ids(sortRows(rows, columns, null))).toEqual([1, 2, 3, 4]);
    expect(ids(sortRows(rows, columns, { key: "name", direction: "asc" }))).toEqual([4, 2, 1, 3]);
    expect(ids(sortRows(rows, columns, { key: "name", direction: "desc" }))).toEqual([1, 2, 4, 3]);
    expect(ids(sortRows(rows, columns, { key: "at", direction: "asc" }))).toEqual([3, 1, 4, 2]);
    expect(ids(sortRows(rows, columns, { key: "at", direction: "desc" }))).toEqual([4, 1, 3, 2]);
    expect(rows).toEqual(input);
  });

  it("keeps ties in the default order (stable) and an unknown column leaves the order alone", () => {
    // sizes 200 (ids 1 and 3) tie: id 1 stays before id 3 whichever way the column points
    expect(ids(sortRows(rows, columns, { key: "size", direction: "desc" }))).toEqual([1, 3, 4, 2]);
    expect(ids(sortRows(rows, columns, { key: "size", direction: "asc" }))).toEqual([4, 1, 3, 2]);
    expect(ids(sortRows(rows, columns, { key: "missing", direction: "asc" }))).toEqual([1, 2, 3, 4]);
  });
});
