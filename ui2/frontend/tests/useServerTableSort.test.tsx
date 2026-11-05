import { act, renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { useServerTableSort } from "../src/shell/tableSort";

/**
 * Server-paged sort state (night batch 2026-11-05): the same asc -> desc -> default cycle as the client sort, but the
 * hook only reports the sort; the query endpoint orders across all pages. Every change resets paging via onChange.
 */
describe("useServerTableSort", () => {
  it("cycles asc -> desc -> default on one key and calls onChange each time", () => {
    const onChange = vi.fn();
    const { result } = renderHook(() => useServerTableSort({ onChange }));
    expect(result.current.sort).toBeNull();
    expect(result.current.header("state").sortDirection).toBe(false);

    act(() => result.current.header("state").onClick());
    expect(result.current.sort).toEqual({ key: "state", direction: "asc" });
    expect(result.current.header("state")).toMatchObject({ active: true, sortDirection: "asc" });

    act(() => result.current.header("state").onClick());
    expect(result.current.sort).toEqual({ key: "state", direction: "desc" });

    act(() => result.current.header("state").onClick());
    expect(result.current.sort).toBeNull();
    expect(onChange).toHaveBeenCalledTimes(3);
  });

  it("starts a different column at its own first direction and deactivates the previous one", () => {
    const { result } = renderHook(() => useServerTableSort());
    act(() => result.current.header("device").onClick());
    act(() => result.current.header("submitted_at", "desc").onClick());
    expect(result.current.sort).toEqual({ key: "submitted_at", direction: "desc" });
    expect(result.current.header("device")).toMatchObject({ active: false, sortDirection: false });
  });

  it("honours an initial sort and setSort(null) clears it and notifies", () => {
    const onChange = vi.fn();
    const { result } = renderHook(() => useServerTableSort({ onChange, initial: { key: "duration", direction: "asc" } }));
    expect(result.current.header("duration").sortDirection).toBe("asc");
    act(() => result.current.setSort(null));
    expect(result.current.sort).toBeNull();
    expect(onChange).toHaveBeenCalledTimes(1);
  });
});
