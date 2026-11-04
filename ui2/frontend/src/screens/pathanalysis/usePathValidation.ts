import { useCallback, useEffect, useRef, useState } from "react";

import { getPathValidation, validatePath, type PathResult, type PathValidation } from "../../auth/adminApi";
import { classifyValidationFailure, requestFromResult, type ValidationFailure } from "./pathValidationWords";

/**
 * One verification of one computed result (stage A6): submit, then poll the record with backoff until no hop is
 * PENDING. The request body is the result's own query; the browser decides nothing about devices or commands. A new
 * result resets the verification (a verdict from one computation is never shown beside a verification of another).
 */

export type ValidationPhase = "idle" | "submitting" | "polling" | "complete" | "stalled" | "failed";

export interface ValidationState {
  readonly phase: ValidationPhase;
  /** The branch asked for; undefined lets the server take the first. */
  readonly branchId: string | undefined;
  readonly validationId: string | null;
  readonly validation: PathValidation | null;
  readonly failure: ValidationFailure | null;
  readonly polls: number;
  readonly lastPolledAt: string | null;
}

export interface PathVerification {
  readonly state: ValidationState;
  readonly start: (branchId?: string) => void;
  /** After a stall or a failed read: one more read, then polling resumes while hops are pending. */
  readonly checkAgain: () => void;
  readonly reset: () => void;
}

const IDLE: ValidationState = { phase: "idle", branchId: undefined, validationId: null, validation: null, failure: null, polls: 0, lastPolledAt: null };

/** 1 s, 2 s, 4 s, 8 s, then every 10 s; a verification that is still pending after five minutes stalls (the jobs go on). */
export const POLL_BACKOFF_MS = [1_000, 2_000, 4_000, 8_000] as const;
export const POLL_CEILING_MS = 10_000;
export const POLL_BUDGET_MS = 5 * 60_000;
export const POLL_READ_FAILURES = 3;

export function pollDelay(attempt: number): number {
  return attempt < POLL_BACKOFF_MS.length ? POLL_BACKOFF_MS[attempt] : POLL_CEILING_MS;
}

export function usePathValidation(result: PathResult | null, clock: () => number = Date.now): PathVerification {
  const [state, setState] = useState<ValidationState>(IDLE);
  const token = useRef(0);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const resultRef = useRef(result);
  resultRef.current = result;

  const stop = useCallback(() => {
    token.current += 1;
    if (timer.current !== null) { clearTimeout(timer.current); timer.current = null; }
  }, []);

  const reset = useCallback(() => { stop(); setState(IDLE); }, [stop]);

  useEffect(() => { reset(); }, [result, reset]);
  useEffect(() => () => stop(), [stop]);

  const poll = useCallback((validationId: string, attempt: number, startedAt: number, failures: number, mine: number) => {
    const settle = (validation: PathValidation) => {
      setState((s) => ({ ...s, validation, validationId, polls: s.polls + 1, lastPolledAt: new Date(clock()).toISOString(),
        phase: validation.result !== "PENDING" ? "complete" : clock() - startedAt >= POLL_BUDGET_MS ? "stalled" : "polling" }));
      return validation.result === "PENDING" && clock() - startedAt < POLL_BUDGET_MS;
    };
    const next = (nextFailures: number) => {
      timer.current = setTimeout(() => poll(validationId, attempt + 1, startedAt, nextFailures, mine), pollDelay(attempt));
    };
    getPathValidation(validationId).then((validation) => {
      if (mine !== token.current) return;
      if (settle(validation)) next(0);
    }, (error) => {
      if (mine !== token.current) return;
      const failure = classifyValidationFailure(error);
      if (failure.kind === "restricted" || failure.kind === "record_gone" || failures + 1 >= POLL_READ_FAILURES) {
        setState((s) => ({ ...s, phase: "failed", failure }));
        return;
      }
      next(failures + 1);
    });
  }, [clock]);

  const start = useCallback((branchId?: string) => {
    const current = resultRef.current;
    if (!current) return;
    stop();
    const mine = token.current;
    setState({ ...IDLE, phase: "submitting", branchId });
    validatePath(requestFromResult(current), branchId).then((validation) => {
      if (mine !== token.current) return;
      const pending = validation.result === "PENDING";
      setState({ phase: pending ? "polling" : "complete", branchId, validationId: validation.validation_id, validation, failure: null,
        polls: 0, lastPolledAt: new Date(clock()).toISOString() });
      if (pending) timer.current = setTimeout(() => poll(validation.validation_id, 1, clock(), 0, mine), pollDelay(0));
    }, (error) => {
      if (mine !== token.current) return;
      setState({ ...IDLE, phase: "failed", branchId, failure: classifyValidationFailure(error) });
    });
  }, [clock, poll, stop]);

  const checkAgain = useCallback(() => {
    const id = state.validationId;
    if (!id) return;
    stop();
    const mine = token.current;
    setState((s) => ({ ...s, phase: "polling", failure: null }));
    poll(id, 0, clock(), 0, mine);
  }, [clock, poll, state.validationId, stop]);

  return { state, start, checkAgain, reset };
}
