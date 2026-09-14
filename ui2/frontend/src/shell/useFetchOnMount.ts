import { useCallback, useEffect, useState } from "react";

export interface FetchOnMountResult<T> {
  /** `null` until the first successful response lands. */
  readonly data: T | null;
  readonly error: string | null;
  /** Re-runs the fetch. Safe to call while a previous run is still in flight. */
  readonly refresh: () => void;
}

/**
 * Fetch-on-mount with stale-response protection.
 *
 * The naive `useEffect(() => { fetcher().then(setState) }, [])` pattern has
 * no cancellation: a fast remount, or a `refresh()` fired before the
 * previous call has settled, lets an older response land after a newer
 * request has already started and silently overwrite its state. This hook
 * closes that gap with a `cancelled` guard set in the effect's own cleanup
 * -- every `then`/`catch` checks it before calling `setState`, so only the
 * most recent in-flight request for the current `generation` can ever
 * commit. `generation` bumps on every `refresh()`, which both re-triggers
 * the effect and cancels whatever the previous generation was still
 * waiting on.
 */
export function useFetchOnMount<T>(
  fetcher: () => Promise<T>,
  describeError: (err: unknown) => string,
): FetchOnMountResult<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [generation, setGeneration] = useState(0);

  const refresh = useCallback(() => setGeneration((g) => g + 1), []);

  useEffect(() => {
    let cancelled = false;
    fetcher()
      .then((result) => {
        if (cancelled) return;
        setData(result);
        setError(null);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(describeError(err));
      });
    return () => {
      cancelled = true;
    };
    // `generation` is the only intended re-trigger; `fetcher`/`describeError`
    // are expected to be stable (or freshly re-created on purpose by the
    // caller, in which case a re-run is welcome too).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [generation]);

  return { data, error, refresh };
}
