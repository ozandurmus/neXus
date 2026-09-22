/** A query parameter of the current page (the Overview links carry their filter this way); null when absent. */
export function urlParam(name: string): string | null {
  try {
    return new URLSearchParams(window.location.search).get(name);
  } catch {
    return null;
  }
}
