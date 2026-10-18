import { useEffect } from "react";

/** A query parameter of the current page (the Overview links carry their filter this way); null when absent. */
export function urlParam(name: string): string | null {
  try {
    return new URLSearchParams(window.location.search).get(name);
  } catch {
    return null;
  }
}

/**
 * Writes query parameters of the current page without a navigation (UI review 2026-10-18, roadmap 3.6: a drawer or a
 * job detail is addressable, so audit evidence can be shared as a link). `null` removes a parameter. `push` adds a
 * history entry, so the browser's Back closes what was opened; `replace` keeps the entry, for closing and for filters.
 * A non-browser test environment, or a URL the history API refuses, leaves the in-memory state untouched.
 */
export function writeUrlParams(changes: Readonly<Record<string, string | null>>, mode: "push" | "replace" = "replace"): void {
  try {
    const url = new URL(window.location.href);
    for (const [name, value] of Object.entries(changes)) {
      if (value === null || value === "") url.searchParams.delete(name);
      else url.searchParams.set(name, value);
    }
    if (url.toString() === window.location.href) return;
    if (mode === "push") window.history.pushState(null, "", url.toString());
    else window.history.replaceState(null, "", url.toString());
  } catch {
    // Not a browser, or the origin refuses the history API: the in-memory selection still works.
  }
}

/** Calls `onChange` with the parameter's current value whenever the browser navigates its history (Back / Forward). */
export function useUrlParamSync(name: string, onChange: (value: string | null) => void): void {
  useEffect(() => {
    const onPop = () => onChange(urlParam(name));
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, [name, onChange]);
}
