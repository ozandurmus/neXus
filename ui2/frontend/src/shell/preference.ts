import { useCallback, useState } from "react";

/**
 * A per-viewer convenience remembered in the browser (a collapsed list, a wrapped viewer). Nothing here changes what is
 * fetched, shown or allowed: a blocked, full or absent store (private window, cleared site data, jsdom) is the same as
 * a viewer who never touched the control, so every read and write is wrapped and falls back to the default.
 */
export function readPreference(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

export function writePreference(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // preference only
  }
}

/** A yes/no preference as React state that also lands in storage; `fallback` wins when nothing is stored. */
export function usePreferenceFlag(key: string, fallback: boolean): [boolean, (next: boolean) => void] {
  const [value, setValue] = useState<boolean>(() => {
    const stored = readPreference(key);
    return stored === null ? fallback : stored === "1";
  });
  const set = useCallback((next: boolean) => {
    setValue(next);
    writePreference(key, next ? "1" : "0");
  }, [key]);
  return [value, set];
}
