import { useEffect, useSyncExternalStore } from "react";
import { rewriteLegacyRoute } from "./legacyRoutes";

/** Announced on `window` after {@link writeUrlParams} or {@link navigateTo} changed the address, so the navigation can follow a tab or screen switch. */
export const URL_CHANGE_EVENT = "nx:urlchange";

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
    window.dispatchEvent(new Event(URL_CHANGE_EVENT));
  } catch {
    // Not a browser, or the origin refuses the history API: the in-memory selection still works.
  }
}

function isPathRoute(pathname: string): boolean {
  return (
    pathname === "/compliance" ||
    pathname === "/compliance/" ||
    pathname === "/backups" ||
    pathname === "/backups/" ||
    /^\/devices\/[^/]+$/.test(pathname)
  );
}

/** Resolves an in-app target href and applies legacy-route and `?screen=configuration` redirects without changing canonical URLs. */
export function canonicalizeAppUrl(rawHref: string): URL {
  const baseHref =
    rawHref.startsWith("?") && isPathRoute(window.location.pathname)
      ? `${window.location.origin}/`
      : window.location.href;
  const url = new URL(rawHref, baseHref);
  const rewritten = rewriteLegacyRoute(url.search);
  if (rewritten !== null) {
    url.search = `?${rewritten}`;
  }
  if (url.searchParams.get("screen") === "configuration") {
    url.searchParams.set("screen", "inventory");
    if (url.searchParams.get("device_id") || url.searchParams.get("cluster_ref")) {
      url.searchParams.set("tab", "configuration");
    }
  }
  return url;
}

/** True when `rawHref` is an in-app screen or workspace route that should navigate via `history.pushState`. */
export function isInAppNavigationHref(rawHref: string): boolean {
  const trimmed = rawHref.trim();
  // Scheme check is case-insensitive and covers every script-capable scheme, not only javascript:.
  const lowered = trimmed.toLowerCase();
  if (
    trimmed === "" ||
    trimmed.startsWith("#") ||
    lowered.startsWith("blob:") ||
    lowered.startsWith("mailto:") ||
    lowered.startsWith("javascript:") ||
    lowered.startsWith("data:") ||
    lowered.startsWith("vbscript:")
  ) {
    return false;
  }
  try {
    const url = new URL(trimmed, window.location.href);
    if (url.origin !== window.location.origin) return false;
    if (trimmed.startsWith("?")) return true;
    return url.pathname === "/" || isPathRoute(url.pathname) || (url.pathname === window.location.pathname && url.search !== "");
  } catch {
    return false;
  }
}

/** Navigates within the single-page shell without reloading the document, preserving legacy-route redirects. */
export function navigateTo(rawHref: string, mode: "push" | "replace" = "push"): void {
  try {
    const url = canonicalizeAppUrl(rawHref);
    if (url.toString() === window.location.href) return;
    if (mode === "push") window.history.pushState(null, "", url.toString());
    else window.history.replaceState(null, "", url.toString());
    window.dispatchEvent(new Event(URL_CHANGE_EVENT));
  } catch {
    // Non-browser environment or unsupported URL.
  }
}

export interface InAppClickLikeEvent {
  readonly defaultPrevented: boolean;
  readonly button: number;
  readonly metaKey: boolean;
  readonly ctrlKey: boolean;
  readonly shiftKey: boolean;
  readonly altKey: boolean;
  readonly target: EventTarget | null;
  preventDefault(): void;
}

/**
 * Intercepts a plain primary-button click on an in-app anchor (`<a href="...">`) and navigates via `history.pushState`.
 * Modifier clicks (`ctrl`, `meta`, `shift`, `alt`), non-primary buttons (middle-click), `target="_blank"`, and `download`
 * links fall through to the browser untouched.
 */
export function handleInAppLinkClick(event: InAppClickLikeEvent): boolean {
  if (event.defaultPrevented) return false;
  if (event.button !== 0) return false;
  if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return false;
  const target = event.target;
  if (!(target instanceof Element)) return false;
  const anchor = target.closest("a[href]");
  if (!anchor) return false;
  if (anchor.hasAttribute("download")) return false;
  const linkTarget = anchor.getAttribute("target");
  if (linkTarget && linkTarget !== "_self") return false;
  const rawHref = anchor.getAttribute("href");
  if (!rawHref || !isInAppNavigationHref(rawHref)) return false;
  event.preventDefault();
  navigateTo(rawHref, "push");
  return true;
}

/** Calls `onChange` with the parameter's current value whenever the browser navigates its history or an in-app link changes the URL. */
export function useUrlParamSync(name: string, onChange: (value: string | null) => void): void {
  useEffect(() => {
    const onUrlChange = () => onChange(urlParam(name));
    window.addEventListener("popstate", onUrlChange);
    window.addEventListener(URL_CHANGE_EVENT, onUrlChange);
    return () => {
      window.removeEventListener("popstate", onUrlChange);
      window.removeEventListener(URL_CHANGE_EVENT, onUrlChange);
    };
  }, [name, onChange]);
}

function subscribeToAddress(onChange: () => void): () => void {
  window.addEventListener("popstate", onChange);
  window.addEventListener(URL_CHANGE_EVENT, onChange);
  return () => {
    window.removeEventListener("popstate", onChange);
    window.removeEventListener(URL_CHANGE_EVENT, onChange);
  };
}

/** The current query string, re-read when the browser navigates its history or a screen writes a parameter. */
export function useLocationSearch(): string {
  return useSyncExternalStore(subscribeToAddress, () => window.location.search, () => "");
}

/** The current pathname, re-read when the browser navigates its history or an in-app route changes. */
export function useLocationPathname(): string {
  return useSyncExternalStore(subscribeToAddress, () => window.location.pathname, () => "/");
}
