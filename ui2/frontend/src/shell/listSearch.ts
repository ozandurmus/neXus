import { useEffect, useSyncExternalStore } from "react";

/**
 * One search box for the whole app (PO, 2026-09-24): the top bar's search also narrows the list of the screen that
 * shows one (Devices, Configuration). The top bar writes the term here; a list screen reads it with
 * {@link useListSearch}, which also tells the top bar that the term is filtering a list right now.
 */
/**
 * A link from the global search's "See all N devices" row opens Devices with the term already in the box
 * (`?screen=inventory&device_search=<term>`); every other page starts with an empty box.
 */
function initialTerm(): string {
  if (typeof window === "undefined") return "";
  const params = new URLSearchParams(window.location.search);
  return params.get("screen") === "inventory" ? (params.get("device_search") ?? "").slice(0, 100) : "";
}
let term = initialTerm();
let listScreens = 0;
const listeners = new Set<() => void>();

function emit() {
  listeners.forEach((l) => l());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function setListSearchTerm(value: string) {
  if (value === term) return;
  term = value;
  emit();
}

/** The current term, read by a list screen; while mounted the screen counts as filtered by the top bar. */
export function useListSearch(): string {
  useEffect(() => {
    listScreens += 1;
    emit();
    return () => {
      listScreens -= 1;
      emit();
    };
  }, []);
  return useSyncExternalStore(subscribe, () => term);
}

/** The top bar: the term, and whether a list on this screen is narrowed by it. */
export function useListSearchState(): { readonly term: string; readonly filtersList: boolean } {
  const t = useSyncExternalStore(subscribe, () => term);
  const filtersList = useSyncExternalStore(subscribe, () => listScreens > 0);
  return { term: t, filtersList };
}
